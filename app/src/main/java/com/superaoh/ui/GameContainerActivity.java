package com.superaoh.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.superaoh.container.GameBridge;
import com.superaoh.container.GameFilesProxy;
import com.superaoh.container.GameRuntime;
import com.superaoh.container.GameInputBridge;
import com.superaoh.core.Workspace;
import com.superaoh.ui.chat.ChatSessionRegistry;
import com.superaoh.ui.chat.ChatWebView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * 游戏容器 Activity。
 *
 * 职责：
 *   1. 初始化 libGDX 宿主（GLSurfaceView），接管 GL 生命周期
 *   2. 从工作区的 APK 加载游戏 DEX，构造 AoCGame
 *   3. 用 GameFilesProxy 劫持 Gdx.files，让游戏读到 APK 里的 assets
 *   4. 等游戏自身启动完成后，按用户选择跳转到编辑器或保留主菜单
 *
 * 刻意不做的事：
 *   · 不加载 APK 里的任何 .so（libstub.so 会在 JNI_OnLoad 触发 SIGABRT）
 *   · 不启动原游戏的 AndroidLauncher Activity
 *   · 不模拟 ActivityManager / Binder 等系统服务
 */
public class GameContainerActivity extends AndroidApplication
        implements GameBridge.Listener {

    private static final String TAG = "SuperAoh/Container";

    public static final String EXTRA_WORKSPACE_ID = "workspaceId";
    public static final String EXTRA_OPEN_EDITOR = "openEditor";
    public static final String EXTRA_DIRECT_EDITOR_MODE = "directEditorMode";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Workspace workspace;
    private GameBridge bridge;
    private GameFilesProxy filesProxy;
    private ZipFile apkZip;

    private FrameLayout container;
    private LinearLayout loadingPanel;
    private View directEditorCover;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView logText;
    private Button retryButton;
    private Button skipButton;

    private boolean openEditorRequested;
    private boolean directEditorMode;
    private boolean editorOpened;
    private boolean startupHandled;
    private final StringBuilder log = new StringBuilder();
    private String failure;

    private FloatingDock dock;
    private OverlayPanel overlay;
    private boolean dockVisible;
    private ChatWebView gameChatView;
    private View gameSurfaceView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String wsId = getIntent().getStringExtra(EXTRA_WORKSPACE_ID);
        openEditorRequested = getIntent().getBooleanExtra(EXTRA_OPEN_EDITOR, false);
        directEditorMode = getIntent().getBooleanExtra(EXTRA_DIRECT_EDITOR_MODE, false);

        if (wsId == null) {
            wsId = Workspace.getActiveId(this);
        }
        workspace = wsId == null ? null : Workspace.load(this, wsId);
        if (workspace == null) {
            finishWithError("没有可用的工作区，请先在主页导入游戏包。");
            return;
        }
        Workspace.setActiveId(this, workspace.id);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        buildUi();

        // 宿主 GL 必须先就绪，游戏构造函数里会用到 Gdx.app / Gdx.files
        bridge = new GameBridge();
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useImmersiveMode = true;
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useWakelock = true;
        config.numSamples = 0;

        View glView = initializeForView(bridge, config);
        gameSurfaceView = glView;
        GameInputBridge.bind(this, glView);
        container.addView(glView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (directEditorMode) {
            directEditorCover = new View(this);
            directEditorCover.setBackgroundColor(0xFF000000);
            container.addView(directEditorCover, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // 悬浮球 + 面板：必须在 GL 之后添加，才能叠在游戏画面之上
        overlay = new OverlayPanel(this, container);
        createDock();

        // 加载面板盖在 GL 之上
        if (!directEditorMode) {
            container.bringChildToFront(loadingPanel);
        }

        startLoadThread();
    }

    /** 创建圆形悬浮球，并把菜单动作接到本 Activity。 */
    private void createDock() {
        dock = new FloatingDock(this, container, new DockActions(this));
        dock.hide(); // 加载阶段不需要球，进入游戏后再显示
    }

    /**
     * 悬浮球回调。
     *
     * 为什么要单独一个静态嵌套类而不是匿名 new：
     *   D8 4.0.48 在处理「实现接口的匿名内部类」时会抛
     *   NullPointerException（Cannot invoke "String.length()" because null），
     *   导致整个构建失败。换成具名静态类即可绕开。
     */
    static final class DockActions implements FloatingDock.Callbacks {
        private final GameContainerActivity host;

        DockActions(GameContainerActivity host) {
            this.host = host;
        }

        @Override
        public void onHome() {
            host.goHome();
        }

        @Override
        public void onEditor() {
            host.requestEditor();
        }

        @Override
        public void onLog() {
            host.showLogPanel();
        }

        @Override
        public void onDebug() {
            host.showDebugPanel();
        }

        @Override
        public void onAi() {
            host.openAiChat();
        }

        @Override
        public void onExit() {
            host.exitGame();
        }
    }

    // ---------------- 悬浮球菜单动作 ----------------

    /** 回到 SuperAoh 主页。游戏 Activity 结束，工作区与存档都保留。 */
    private void goHome() {
        appendLog("用户选择：返回主页");
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    /**
     * 唤起 AI 助手。
     *
     * 注意不能直接 finish 当前容器：游戏引擎还在跑，
     * 用普通的 startActivity 把 AI 页面盖在上面，
     * 用户从 AI 页返回时游戏画面还在原处。
     */
    private void openAiChat() {
        appendLog("用户选择：打开游戏内 AI 助手");
        if (dock != null) dock.hideMenu();
        try {
            if (gameChatView == null) {
                String wsId = workspace == null ? null : workspace.id;
                gameChatView = new ChatWebView(this, ChatSessionRegistry.get(this, wsId),
                        this::dismissChatOverlay, true, this::captureGameFrame);
            }
            overlay.showChat("SuperAoh AI", gameChatView);
        } catch (Throwable t) {
            appendLog("打开游戏内 AI 失败: " + t);
            Toast.makeText(this, "打开 AI 助手失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void captureGameFrame() {
        if (gameSurfaceView == null || container == null) return;
        try {
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                    Math.max(1, container.getWidth()), Math.max(1, container.getHeight()),
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            gameSurfaceView.draw(canvas);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, bytes);
            bitmap.recycle();
            java.io.File dir = com.superaoh.core.ChatStore.imageDir(this,
                    workspace == null ? null : workspace.id,
                    ChatSessionRegistry.get(this, workspace == null ? null : workspace.id).currentSessionId());
            java.io.File file = new java.io.File(dir, "game_" + System.currentTimeMillis() + ".jpg");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { out.write(bytes.toByteArray()); }
            ChatSessionRegistry.get(this, workspace == null ? null : workspace.id).addExternalImage(file);
            Toast.makeText(this, "游戏画面已附加", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "当前游戏渲染视图不支持直接采集画面", Toast.LENGTH_SHORT).show();
            appendLog("游戏画面采集失败: " + t);
        }
    }

    /** 手动请求打开游戏内置编辑器。 */
    private void requestEditor() {
        if (editorOpened) {
            toast("编辑器已经打开过了");
            return;
        }
        appendLog("用户选择：打开编辑器");
        boolean ok = GameRuntime.openEditor();
        if (ok) {
            editorOpened = true;
            toast("已跳转编辑器");
        } else {
            String diag = GameRuntime.diagnose();
            appendLog("跳转编辑器失败\n" + diag);
            toast("打开编辑器失败，请看日志");
            showLogPanel();
        }
    }

    /** 查看运行日志（含探针输出的游戏内部状态）。 */
    private void showLogPanel() {
        revokeGameControl();
        String snapshot = log.toString();
        String diag = GameRuntime.diagnose();
        OverlayPanel.Provider provider = () -> "──── 运行日志 ────\n" + log
                + "\n──── 当前状态 ────\n" + diag;
        overlay.show("运行日志", provider, null);
        // snapshot/diag 仅用于首次渲染，后续刷新走 provider
        if (snapshot.isEmpty()) {
            appendLog("（暂无日志）");
        }
    }

    /** 在线调试面板：实时反射读取游戏内部状态。 */
    private void showDebugPanel() {
        revokeGameControl();
        OverlayPanel.Provider provider = () -> {
            StringBuilder sb = new StringBuilder();
            sb.append("──── 游戏内部状态（实时） ────\n");
            sb.append(GameRuntime.probeLine()).append("\n\n");
            sb.append("──── 详细诊断 ────\n");
            sb.append(GameRuntime.diagnose()).append('\n');
            sb.append("──── 工作区 ────\n");
            sb.append("id      = ").append(workspace.id).append('\n');
            sb.append("name    = ").append(workspace.name).append('\n');
            sb.append("sha256  = ").append(workspace.apkSha256).append('\n');
            sb.append("editor  = ").append(workspace.editorPresent).append('\n');
            sb.append("data    = ").append(workspace.dataDir(this).getAbsolutePath()).append('\n');
            sb.append("overlay = ").append(workspace.overlayAssetsDir(this).getAbsolutePath()).append('\n');
            sb.append("patches = ").append(workspace.patchesDir(this).getAbsolutePath()).append('\n');
            sb.append("\n──── 资源索引 ────\n");
            if (filesProxy != null) {
                sb.append("assets files = ").append(filesProxy.getAssetFileCount()).append('\n');
                sb.append("assets dirs  = ").append(filesProxy.getAssetDirCount()).append('\n');
                sb.append("top level    = ").append(filesProxy.topLevelAssets()).append('\n');
            } else {
                sb.append("资源代理未安装\n");
            }
            return sb.toString();
        };
        overlay.show("在线调试", provider, null);
    }

    /** 彻底退出游戏：结束 Activity 与进程级 GL 资源。 */
    private void exitGame() {
        appendLog("用户选择：退出游戏");
        try {
            if (dock != null) {
                dock.hide();
            }
            finishAndRemoveTask();
        } catch (Throwable t) {
            finish();
        }
    }

    private void revokeGameControl() {
        GameInputBridge.setControlsEnabled(false);
    }

    private void dismissChatOverlay() {
        revokeGameControl();
        if (overlay != null) overlay.hide();
        if (gameChatView != null) gameChatView.refreshGameState();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------------- UI ----------------

    private void buildUi() {
        container = new FrameLayout(this);
        setContentView(container);

        loadingPanel = new LinearLayout(this);
        loadingPanel.setOrientation(LinearLayout.VERTICAL);
        loadingPanel.setBackgroundColor(0xFF10131A);
        int pad = dp(20);
        loadingPanel.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("SuperAoh 容器");
        title.setTextColor(0xFFE8EAFF);
        title.setTextSize(20f);
        loadingPanel.addView(title);

        TextView sub = new TextView(this);
        sub.setText(workspace.name + "\n" + workspace.apkSha256.substring(0,
                Math.min(12, workspace.apkSha256.length())) + "...");
        sub.setTextColor(0xFF8A93B2);
        sub.setTextSize(11f);
        sub.setPadding(0, dp(4), 0, dp(14));
        loadingPanel.addView(sub);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        loadingPanel.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        statusText = new TextView(this);
        statusText.setText("准备中...");
        statusText.setTextColor(0xFFB9C2DE);
        statusText.setTextSize(13f);
        statusText.setPadding(0, dp(10), 0, dp(10));
        loadingPanel.addView(statusText);

        ScrollView scroll = new ScrollView(this);
        logText = new TextView(this);
        logText.setTextColor(0xFF6F7A9A);
        logText.setTextSize(10f);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        scroll.addView(logText);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        loadingPanel.addView(scroll, scrollLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(12), 0, 0);

        retryButton = new Button(this);
        retryButton.setText("重试");
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> recreate());
        buttons.addView(retryButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        skipButton = new Button(this);
        skipButton.setText("查看画面");
        skipButton.setVisibility(View.VISIBLE);
        skipButton.setOnClickListener(v -> hideLoadingPanel());
        buttons.addView(skipButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        loadingPanel.addView(buttons);

        container.addView(loadingPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (directEditorMode) {
            // Dedicated editor entry: keep the GL surface black/clean instead of showing
            // the game's loading panel, progress log, or a second loading screen.
            loadingPanel.setVisibility(View.GONE);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void uiProgress(String stage, int percent) {
        ui.post(() -> {
            statusText.setText(stage);
            progressBar.setProgress(Math.max(0, Math.min(100, percent)));
            appendLog(stage);
        });
    }

    private void appendLog(String line) {
        log.append(line).append('\n');
        if (log.length() > 8000) {
            log.delete(0, log.length() - 8000);
        }
        logText.setText(log);
    }

    private void appendLogThread(String line) {
        ui.post(() -> appendLog(line));
    }

    private void hideLoadingPanel() {
        ui.post(() -> {
            loadingPanel.setVisibility(View.GONE);
            // The direct editor surface stays unobstructed; Back remains the exit route.
            if (directEditorMode && directEditorCover != null) {
                container.removeView(directEditorCover);
                directEditorCover = null;
            }
            if (!directEditorMode && dock != null) {
                dock.show();
                dockVisible = true;
            }
        });
    }

    private void finishWithError(String message) {
        failure = message;
        Log.e(TAG, message);
        ui.post(() -> {
            if (directEditorMode) {
                if (directEditorCover != null) {
                    container.removeView(directEditorCover);
                    directEditorCover = null;
                }
                if (loadingPanel != null) {
                    loadingPanel.setVisibility(View.VISIBLE);
                    container.bringChildToFront(loadingPanel);
                }
            }
            if (statusText != null) {
                statusText.setText(message);
            }
            if (progressBar != null) {
                progressBar.setProgress(0);
            }
            if (retryButton != null) {
                retryButton.setVisibility(View.VISIBLE);
            }
            if (skipButton != null) {
                skipButton.setVisibility(View.VISIBLE);
            }
        });
    }

    // ---------------- 加载流程 ----------------

    private void startLoadThread() {
        Thread t = new Thread(this::doLoad, "SuperAoh-Loader");
        t.start();
    }

    private void doLoad() {
        try {
            uiProgress("打开游戏包...", 4);
            File apk = workspace.apk(this);
            if (!apk.exists()) {
                finishWithError("游戏包丢失: " + apk.getAbsolutePath());
                return;
            }

            // 1. 打开 ZIP，所有权本 Activity 持有，供资源代理读取
            apkZip = new ZipFile(apk);
            uiProgress("扫描游戏包...", 8);
            appendLogThread("APK: " + apk.getName() + " (" + apk.length() + "B)");

            // 2. 部署 DEX（Android 14+ 必须只读）
            uiProgress("提取并锁定 DEX...", 16);
            GameRuntime.DeployResult dex = GameRuntime.deployDex(apkZip, getCodeCacheDir());
            for (File f : dex.dexFiles) {
                appendLogThread("DEX: " + f.getName() + " " + f.length() + "B readonly="
                        + !f.canWrite());
            }

            // 3. 构造游戏实例
            uiProgress("构造游戏引擎...", 34);
            Object game = GameRuntime.instantiate(this, dex.dexFiles, getCodeCacheDir());
            appendLogThread("引擎: " + game.getClass().getName());

            // 4. 劫持资源
            uiProgress("接管游戏资源...", 48);
            // 注意：不要用非静态内部类做工厂，D8 4.0.48 处理其 synthetic
            // 构造器会抛 NPE 崩溃，这里直接内联。
            filesProxy = new GameFilesProxy(
                    com.badlogic.gdx.Gdx.files,
                    apkZip,
                    workspace.assetCacheDir(this),
                    workspace.dataDir(this),
                    workspace.overlayAssetsDir(this),
                    workspace.hotCacheDir(this));
            filesProxy.install();
            appendLogThread("资源: " + filesProxy.getAssetFileCount() + " 文件 / "
                    + filesProxy.getAssetDirCount() + " 目录");
            appendLogThread("顶层: " + filesProxy.topLevelAssets());

            // 5. 交给桥接层
            uiProgress("等待游戏启动...", 60);
            ui.post(() -> bridge.attach(game, this));

            uiProgress("加载中（游戏自身初始化）...", 70);
            startProbeLoop();
        } catch (Throwable t) {
            Log.e(TAG, "加载失败", t);
            finishWithError("加载失败: " + t.getClass().getSimpleName() + "\n" + t.getMessage());
        }
    }
    /**
     * 周期性探测游戏真实状态。
     * 只读反射，不干扰游戏逻辑。每 2 秒打一行，最多 30 次。
     */
    private void startProbeLoop() {
        final int maxRounds = 30;
        final Runnable[] task = new Runnable[1];
        final int[] round = {0};
        task[0] = () -> {
            round[0]++;
            String diag = GameRuntime.probeLine();
            appendLog("探针[" + round[0] + "] " + diag);
            Log.i(TAG, "probe[" + round[0] + "] " + diag);

            boolean ready = GameRuntime.isGameReady();
            if (ready && !startupHandled) {
                startupHandled = true;
                onStartupComplete();
            }
            if (round[0] < maxRounds && !isFinishing()) {
                ui.postDelayed(task[0], 2000);
            }
        };
        ui.postDelayed(task[0], 1500);
    }

    // ---------------- GameBridge.Listener ----------------

    @Override
    public void onFirstFrameRendered() {
        appendLogThread("引擎 create() 完成，首帧已提交");
        uiProgress("首帧已渲染", 80);
    }

    @Override
    public void onStartupComplete() {
        appendLogThread("游戏启动完成 viewID=" + GameRuntime.currentViewId());
        if (openEditorRequested && !editorOpened) {
            editorOpened = true;
            // Let the game finish wiring CFG.map/CFG.game; on direct launch, suppress
            // the title/menu frame before switching to the editor scene.
            ui.postDelayed(() -> {
                boolean ok = GameRuntime.openEditor();
                appendLogThread("跳转编辑器: " + (ok ? "成功" : "失败"));
                if (!ok) {
                    appendLogThread(GameRuntime.diagnose());
                    if (directEditorMode) {
                        if (directEditorCover != null) {
                            container.removeView(directEditorCover);
                            directEditorCover = null;
                        }
                        loadingPanel.setVisibility(View.VISIBLE);
                        container.bringChildToFront(loadingPanel);
                        new AlertDialog.Builder(this)
                                .setTitle("无法打开编辑器")
                                .setMessage("游戏引擎已启动，但编辑器入口未能打开。\\n\\n"
                                        + GameRuntime.diagnose())
                                .setPositiveButton("返回主页", (d, w) -> goHome())
                                .setNegativeButton("继续", null)
                                .show();
                    } else {
                        Toast.makeText(this, "打开编辑器失败，请看日志",
                                Toast.LENGTH_LONG).show();
                    }
                    if (!directEditorMode) {
                        hideLoadingPanel();
                    }
                } else {
                    hideLoadingPanel();
                }
            }, directEditorMode ? 80 : 600);
        } else {
            hideLoadingPanel();
        }
    }

    @Override
    public void onError(String stage, Throwable error) {
        appendLogThread("[" + stage + "] " + error);
        Log.e(TAG, stage, error);
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onResume() {
        super.onResume();
        if (gameChatView != null) gameChatView.refreshGameState();
    }

    @Override
    protected void onPause() {
        revokeGameControl();
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (gameChatView != null && gameChatView.onActivityResult(requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * 游戏会消费返回键，所以在真正把事件交给按键前，
     * 优先处理我们自己的覆盖层：面板 → 悬浮菜单 → 退出确认。
     */
    @Override
    public void onBackPressed() {
        if (overlay != null && overlay.isShowing()) {
            if (overlay.isChatShowing() && gameChatView != null) {
                gameChatView.onBackPressed(() -> { }, this::dismissChatOverlay);
                return;
            }
            overlay.hide();
            return;
        }
        if (dock != null && dock.isMenuOpen()) {
            dock.hideMenu();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("退出游戏")
                .setItems(new String[]{"返回 SuperAoh 主页", "彻底退出游戏", "继续游戏"}, (d, which) -> {
                    d.dismiss();
                    if (which == 0) {
                        goHome();
                    } else if (which == 1) {
                        exitGame();
                    }
                })
                .show();
    }

    @Override
    protected void onDestroy() {
        try {
            GameInputBridge.unbind(this);
            if (gameChatView != null) {
                gameChatView.destroy();
                gameChatView = null;
            }
            if (apkZip != null) {
                apkZip.close();
            }
        } catch (Throwable ignored) {
        }
        apkZip = null;
        super.onDestroy();
    }
}
