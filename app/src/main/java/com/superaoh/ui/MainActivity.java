package com.superaoh.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.superaoh.core.AiConfig;
import com.superaoh.core.ApkInspector;
import com.superaoh.core.Workspace;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * SuperAoh 主页。
 *
 * 三条主线在这里汇合：
 *   1. 导入游戏包（选 APK -> 探测 -> 建工作区）
 *   2. 进入容器（编辑器 / 直接游戏）
 *   3. AI 助手与设置
 *
 * 视觉全部走 Ui 主题库，不再散落写死颜色。
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_APK = 1001;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout listContainer;
    private LinearLayout inspectorCard;
    private TextView statusText;
    private TextView inspectorText;
    private ProgressBar parseProgress;

    private TextView aiTitle;
    private TextView aiHint;

    private File pendingApk;
    private ApkInspector.Report pendingReport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshWorkspaces();
        refreshAiState();
    }

    // ---------------- UI ----------------

    private void buildUi() {
        final int bg = 0xFF0A0D12;
        final int plate = 0xFF121821;
        final int plateRaised = 0xFF19222D;
        final int line = 0xFF27313D;
        final int text = 0xFFF0F3F7;
        final int muted = 0xFF9AA8B8;
        final int accent = 0xFFB7D65C;

        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(0);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int horizontal = Ui.dp(this, 22);
        root.setPadding(horizontal, Ui.dp(this, 18), horizontal, Ui.dp(this, 30));
        scroll.addView(root);

        // A compact wordmark and system status: the workspaces remain the main content.
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = Ui.text(this, "SA", 15f, bg);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(Ui.roundRect(accent, 11, this));
        header.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(Ui.dp(this, 12), 0, 0, 0);
        TextView name = Ui.text(this, "SuperAoh", 19f, text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        brand.addView(name);
        TextView descriptor = Ui.text(this, "文明时代工作台", 11f, muted);
        brand.addView(descriptor);
        header.addView(brand, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(Ui.chip(this, "0.1.0", accent));
        root.addView(header, Ui.lpMatchWrap());

        // One decisive primary action, with truthful copy about workspace isolation.
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(0, Ui.dp(this, 8), 0, 0);
        TextView heroTitle = Ui.text(this, "你的战局，\n从这里开始。", 27f, text);
        heroTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        heroTitle.setLineSpacing(Ui.dp(this, 1), 1f);
        hero.addView(heroTitle);
        TextView heroCopy = Ui.text(this,
                "导入游戏包，在独立工作区中启动编辑器或游戏。原始 APK 保持不变。", 13f, muted);
        heroCopy.setLineSpacing(Ui.dp(this, 3), 1f);
        hero.addView(heroCopy, Ui.lpMatchWrap(10, this));

        Button pick = Ui.primaryButton(this, "导入文明时代 II 游戏包");
        pick.setTextColor(colorOnAccent(accent, 0xFFFFFFFF, 0xFF14190B));
        pick.setTypeface(Typeface.DEFAULT_BOLD);
        pick.setBackground(Ui.roundRect(accent, 13, this));
        pick.setMinHeight(Ui.dp(this, 52));
        pick.setOnClickListener(v -> pickApk());
        hero.addView(pick, Ui.lpMatchWrap(18, this));

        // APK inspection state remains inside the import surface.
        inspectorCard = new LinearLayout(this);
        inspectorCard.setOrientation(LinearLayout.VERTICAL);
        inspectorCard.setBackground(Ui.roundRect(plateRaised, 12, this));
        int ip = Ui.dp(this, 13);
        inspectorCard.setPadding(ip, ip, ip, ip);
        inspectorCard.setVisibility(View.GONE);
        hero.addView(inspectorCard, Ui.lpMatchWrap(12, this));

        statusText = Ui.text(this, "", 12f, Ui.TEXT_SUB);
        inspectorCard.addView(statusText);
        parseProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        parseProgress.setMax(100);
        parseProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 4));
        progLp.topMargin = Ui.dp(this, 8);
        inspectorCard.addView(parseProgress, progLp);
        inspectorText = Ui.mono(this, "", 10f, Ui.TEXT_DIM);
        inspectorText.setPadding(0, Ui.dp(this, 8), 0, 0);
        inspectorCard.addView(inspectorText);
        root.addView(hero, Ui.lpMatchWrap(24, this));

        // AI is an auxiliary tool, not a competing hero.
        LinearLayout aiStrip = new LinearLayout(this);
        aiStrip.setOrientation(LinearLayout.VERTICAL);
        aiStrip.setBackground(Ui.roundRectStroke(plate, 16, line, 1, this));
        aiStrip.setPadding(Ui.dp(this, 16), Ui.dp(this, 15), Ui.dp(this, 16), Ui.dp(this, 14));
        aiTitle = Ui.text(this, "AI 工作台", 15f, text);
        aiTitle.setTypeface(Typeface.DEFAULT_BOLD);
        aiStrip.addView(aiTitle);
        aiHint = Ui.text(this, "", 12f, muted);
        aiHint.setLineSpacing(Ui.dp(this, 2), 1f);
        aiStrip.addView(aiHint, Ui.lpMatchWrap(5, this));
        LinearLayout aiRow = new LinearLayout(this);
        aiRow.setOrientation(LinearLayout.HORIZONTAL);
        Button chatBtn = Ui.primaryButton(this, "打开对话");
        chatBtn.setMinHeight(Ui.dp(this, 48));
        chatBtn.setOnClickListener(v -> openComposeChat());
        LinearLayout.LayoutParams chatLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        chatLp.rightMargin = Ui.dp(this, 8);
        aiRow.addView(chatBtn, chatLp);
        Button cfgBtn = Ui.ghostButton(this, "模型设置");
        cfgBtn.setMinHeight(Ui.dp(this, 48));
        cfgBtn.setOnClickListener(v -> openAiSettings());
        aiRow.addView(cfgBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        aiStrip.addView(aiRow, Ui.lpMatchWrap(11, this));
        root.addView(aiStrip, Ui.lpMatchWrap(14, this));

        // Heading and count update when the persisted workspace list changes.
        LinearLayout workspaceHeading = new LinearLayout(this);
        workspaceHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView wsHeader = Ui.text(this, "工作区", 20f, text);
        wsHeader.setTypeface(Typeface.DEFAULT_BOLD);
        workspaceHeading.addView(wsHeader, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView wsHint = Ui.text(this, "已导入的游戏包", 11f, muted);
        workspaceHeading.addView(wsHint);
        root.addView(workspaceHeading, Ui.lpMatchWrap(27, this));

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer, Ui.lpMatchWrap(12, this));
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(bg);
        int maxContentWidth = Ui.dp(this, 720);
        int viewportWidth = getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams pageLp = new FrameLayout.LayoutParams(
                Math.min(viewportWidth, maxContentWidth),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        page.addView(scroll, pageLp);
        setContentView(page);
    }

    private int colorOnAccent(int color, int light, int dark) {
        double r = android.graphics.Color.red(color) / 255.0;
        double g = android.graphics.Color.green(color) / 255.0;
        double b = android.graphics.Color.blue(color) / 255.0;
        r = r <= 0.04045 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.04045 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        b = b <= 0.04045 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.36 ? dark : light;
    }

    private void refreshAiState() {
        if (aiTitle == null) {
            return;
        }
        AiConfig.Profile active = AiConfig.getActive(this);
        boolean ready = active != null && active.usable();
        if (ready) {
            aiTitle.setText("AI 助手");
            aiTitle.setTextColor(0xFFF0F3F7);
            aiHint.setText("已配置：" + active.displayLine()
                    + "\n可以读写工作区文件、解析 APK、查看运行状态。");
            aiHint.setTextColor(0xFF9AA8B8);
        } else {
            aiTitle.setText("AI 助手 · 未配置");
            aiTitle.setTextColor(Ui.WARN);
            aiHint.setText("还没有可用的模型配置。先去「AI 设置」填 BaseURL、"
                    + "API Key 和模型名，然后就能对话了。");
            aiHint.setTextColor(Ui.WARN);
        }
    }

    // ---------------- AI ----------------

    private void openAiChat() {
        AiConfig.Profile active = AiConfig.getActive(this);
        if (active == null || !active.usable()) {
            new AlertDialog.Builder(this)
                    .setTitle("还没配置模型")
                    .setMessage("需要先填写接口地址和模型名。\n现在去设置？")
                    .setPositiveButton("去设置", (d, w) -> openAiSettings())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        String wsId = Workspace.getActiveId(this);
        if (wsId == null || wsId.trim().isEmpty()) {
            List<Workspace> all = Workspace.listAll(this);
            if (!all.isEmpty()) {
                wsId = all.get(0).id;
            }
        }
        Intent intent = new Intent(this,
                com.superaoh.ui.compose.ChatComposeActivity.class);
        if (wsId != null && !wsId.trim().isEmpty()) {
            intent.putExtra(com.superaoh.ui.compose.ChatComposeActivity.EXTRA_WORKSPACE_ID, wsId);
        }
        startActivity(intent);
    }

    private void openComposeChat() {
        AiConfig.Profile active = AiConfig.getActive(this);
        if (active == null || !active.usable()) {
            new AlertDialog.Builder(this)
                    .setTitle("还没配置模型")
                    .setMessage("需要先填写接口地址和模型名。\n现在去设置？")
                    .setPositiveButton("去设置", (d, w) -> openAiSettings())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        startActivity(new Intent(this,
                com.superaoh.ui.compose.ChatComposeActivity.class));
    }

    private void openAiSettings() {
        startActivity(new Intent(this, AiSettingsActivity.class));
    }

    // ---------------- 选择 APK ----------------

    private void pickApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_PICK_APK);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开文件选择器: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_APK || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        startInspect(uri);
    }

    private void startInspect(Uri uri) {
        inspectorCard.setVisibility(View.VISIBLE);
        parseProgress.setVisibility(View.VISIBLE);
        parseProgress.setProgress(0);
        statusText.setTextColor(Ui.TEXT_SUB);
        statusText.setText("正在导入...");
        inspectorText.setText("");

        new Thread(() -> {
            try {
                // 先落到应用私有缓存，避免 SAF 流被长时间占用
                File tmpDir = new File(getCacheDir(), "import");
                Workspace.ensureDir(tmpDir);
                File local = new File(tmpDir, "picked.apk");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(local)) {
                    if (in == null) {
                        throw new IllegalStateException("无法读取所选文件");
                    }
                    byte[] buf = new byte[512 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                }

                final File apkFile = local;
                runOnUi(() -> {
                    statusText.setText("正在解析 " + ApkInspector.formatSize(apkFile.length()) + "...");
                    parseProgress.setProgress(6);
                });

                ApkInspector.Report report = ApkInspector.inspect(apkFile,
                        (stage, percent) -> runOnUi(() -> {
                            statusText.setText(stage);
                            parseProgress.setProgress(percent);
                        }));

                runOnUi(() -> showReport(apkFile, report));
            } catch (Throwable t) {
                final String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
                runOnUi(() -> {
                    statusText.setTextColor(Ui.DANGER);
                    statusText.setText("解析失败");
                    parseProgress.setVisibility(View.GONE);
                    inspectorText.setText(msg);
                });
            }
        }, "SuperAoh-Inspect").start();
    }

    private void showReport(File apk, ApkInspector.Report report) {
        pendingApk = apk;
        pendingReport = report;

        parseProgress.setVisibility(View.GONE);
        inspectorCard.removeAllViews();
        inspectorCard.addView(statusText);
        inspectorCard.addView(inspectorText);

        if (report.error != null) {
            statusText.setTextColor(Ui.DANGER);
            statusText.setText("解析出错");
            inspectorText.setText(report.error);
            return;
        }

        if (report.isGame) {
            statusText.setTextColor(report.editorPresent ? Ui.OK : Ui.WARN);
            statusText.setText(report.editorPresent
                    ? "✓ 识别为文明时代2（编辑器完整）"
                    : "⚠ 识别为文明时代2（编辑器缺失）");
        } else {
            statusText.setTextColor(Ui.DANGER);
            statusText.setText("✗ 未识别为文明时代2");
        }
        inspectorText.setText(report.summary());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button importBtn = Ui.primaryButton(this, "导入为工作区");
        importBtn.setEnabled(report.isGame && !report.dexEntries.isEmpty());
        importBtn.setOnClickListener(v -> doImport());
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.rightMargin = Ui.dp(this, 8);
        actions.addView(importBtn, ilp);

        Button cancelBtn = Ui.ghostButton(this, "取消");
        cancelBtn.setOnClickListener(v -> {
            inspectorCard.setVisibility(View.GONE);
            pendingApk = null;
            pendingReport = null;
        });
        actions.addView(cancelBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        inspectorCard.addView(actions, Ui.lpMatchWrap(10, this));
    }

    private void doImport() {
        if (pendingApk == null || pendingReport == null) {
            return;
        }
        final File apk = pendingApk;
        final ApkInspector.Report report = pendingReport;

        statusText.setTextColor(Ui.TEXT_SUB);
        statusText.setText("正在创建工作区...");
        new Thread(() -> {
            try {
                Workspace ws = Workspace.importApk(this, apk, report,
                        (stage, percent) -> runOnUi(() -> statusText.setText(stage)));
                runOnUi(() -> {
                    Toast.makeText(this, "工作区已创建: " + ws.name,
                            Toast.LENGTH_SHORT).show();
                    inspectorCard.setVisibility(View.GONE);
                    pendingApk = null;
                    pendingReport = null;
                    refreshWorkspaces();
                });
            } catch (Throwable t) {
                final String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
                runOnUi(() -> {
                    statusText.setTextColor(Ui.DANGER);
                    statusText.setText("导入失败");
                    inspectorText.setText(msg);
                });
            }
        }, "SuperAoh-Import").start();
    }

    // ---------------- 工作区列表 ----------------

    private void refreshWorkspaces() {
        if (listContainer == null) {
            return;
        }
        listContainer.removeAllViews();
        List<Workspace> all = Workspace.listAll(this);

        if (all.isEmpty()) {
            LinearLayout emptyState = new LinearLayout(this);
            emptyState.setOrientation(LinearLayout.VERTICAL);
            emptyState.setBackground(Ui.roundRectStroke(0xFF121821, 16, 0xFF27313D, 1, this));
            int pad = Ui.dp(this, 18);
            emptyState.setPadding(pad, pad, pad, pad);
            TextView emptyTitle = Ui.text(this, "尚未添加游戏包", 16f, 0xFFF0F3F7);
            emptyTitle.setTypeface(Typeface.DEFAULT_BOLD);
            emptyState.addView(emptyTitle);
            TextView empty = Ui.text(this,
                    "导入后，每个游戏都会拥有独立的存档、补丁和编辑器入口。", 12f, 0xFF9AA8B8);
            empty.setLineSpacing(Ui.dp(this, 3), 1f);
            emptyState.addView(empty, Ui.lpMatchWrap(6, this));
            Button emptyPick = Ui.ghostButton(this, "选择游戏 APK");
            emptyPick.setMinHeight(Ui.dp(this, 48));
            emptyPick.setOnClickListener(v -> pickApk());
            emptyState.addView(emptyPick, Ui.lpMatchWrap(12, this));
            listContainer.addView(emptyState);
            return;
        }
        for (Workspace ws : all) {
            listContainer.addView(buildWorkspaceCard(ws));
        }
    }

    private View buildWorkspaceCard(Workspace ws) {
        boolean active = ws.id.equals(Workspace.getActiveId(this));
        final int plate = 0xFF121821;
        final int line = active ? 0xFF667A3C : 0xFF27313D;
        final int text = 0xFFF0F3F7;
        final int muted = 0xFF9AA8B8;
        final int accent = 0xFFB7D65C;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.roundRectStroke(plate, 17, line, 1, this));
        int pad = Ui.dp(this, 16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardLp = Ui.lpMatchWrap();
        cardLp.bottomMargin = Ui.dp(this, 12);
        card.setLayoutParams(cardLp);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.text(this, ws.name, 17f, text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(Ui.chip(this, active ? "当前工作区" : "备用工作区",
                active ? accent : muted));
        card.addView(top);

        TextView meta = Ui.text(this,
                ApkInspector.formatSize(ws.apkSize) + "  ·  "
                        + (ws.editorPresent ? "编辑器可用" : "未检测到内置编辑器"),
                12f, muted);
        card.addView(meta, Ui.lpMatchWrap(6, this));

        LinearLayout primaryRow = new LinearLayout(this);
        primaryRow.setOrientation(LinearLayout.HORIZONTAL);
        Button editor = Ui.primaryButton(this,
                ws.editorPresent ? "打开编辑器" : "编辑器缺失");
        editor.setEnabled(ws.editorPresent);
        editor.setTextColor(ws.editorPresent ? 0xFF14190B : 0xFF69717B);
        editor.setBackground(Ui.roundRect(ws.editorPresent ? accent : 0xFF303842, 12, this));
        editor.setMinHeight(Ui.dp(this, 50));
        editor.setOnClickListener(v -> launchEditor(ws));
        LinearLayout.LayoutParams editorLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        editorLp.rightMargin = Ui.dp(this, 8);
        primaryRow.addView(editor, editorLp);

        Button game = Ui.ghostButton(this, "进入游戏");
        game.setMinHeight(Ui.dp(this, 50));
        game.setOnClickListener(v -> launch(ws, false));
        primaryRow.addView(game, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f));
        card.addView(primaryRow, Ui.lpMatchWrap(14, this));

        // 数据编辑器：直接改文明/政体数据，不启动游戏本体
        Button dataEditor = Ui.ghostButton(this, "数据编辑（文明 / 政体）");
        dataEditor.setMinHeight(Ui.dp(this, 44));
        dataEditor.setOnClickListener(v -> openDataEditor(ws));
        card.addView(dataEditor, Ui.lpMatchWrap(8, this));

        LinearLayout secondaryRow = new LinearLayout(this);
        secondaryRow.setGravity(Gravity.CENTER_VERTICAL);
        Button aiForWs = Ui.ghostButton(this, "AI 助手");
        aiForWs.setMinHeight(Ui.dp(this, 46));
        aiForWs.setOnClickListener(v -> {
            Workspace.setActiveId(this, ws.id);
            AiConfig.Profile prof = AiConfig.getActive(this);
            if (prof == null || !prof.usable()) {
                openAiSettings();
                return;
            }
            Intent it = new Intent(this, com.superaoh.ui.compose.ChatComposeActivity.class);
            it.putExtra(com.superaoh.ui.compose.ChatComposeActivity.EXTRA_WORKSPACE_ID, ws.id);
            startActivity(it);
        });
        LinearLayout.LayoutParams aiLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        aiLp.rightMargin = Ui.dp(this, 8);
        secondaryRow.addView(aiForWs, aiLp);

        Button setActive = Ui.ghostButton(this, active ? "当前" : "设为当前");
        setActive.setMinHeight(Ui.dp(this, 46));
        setActive.setEnabled(!active);
        setActive.setOnClickListener(v -> {
            Workspace.setActiveId(this, ws.id);
            refreshWorkspaces();
        });
        LinearLayout.LayoutParams activeLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        activeLp.rightMargin = Ui.dp(this, 8);
        secondaryRow.addView(setActive, activeLp);

        Button remove = Ui.dangerButton(this, "删除");
        remove.setMinHeight(Ui.dp(this, 46));
        remove.setOnClickListener(v -> confirmDelete(ws));
        secondaryRow.addView(remove, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f));
        card.addView(secondaryRow, Ui.lpMatchWrap(8, this));
        return card;
    }

    private void confirmDelete(Workspace ws) {
        new AlertDialog.Builder(this)
                .setTitle("删除工作区")
                .setMessage("会删除该工作区的游戏包副本、存档和补丁。\n\n" + ws.name)
                .setPositiveButton("删除", (d, w) -> new Thread(() -> {
                    Workspace.delete(this, ws.id);
                    runOnUi(this::refreshWorkspaces);
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    private void launchEditor(Workspace ws) {
        if (ws == null || !ws.editorPresent) {
            Toast.makeText(this, "这个游戏包未检测到内置编辑器", Toast.LENGTH_SHORT).show();
            return;
        }
        Workspace.setActiveId(this, ws.id);
        Intent intent = new Intent(this, EditorActivity.class);
        intent.putExtra(GameContainerActivity.EXTRA_WORKSPACE_ID, ws.id);
        intent.putExtra(GameContainerActivity.EXTRA_OPEN_EDITOR, true);
        intent.putExtra(GameContainerActivity.EXTRA_DIRECT_EDITOR_MODE, true);
        startActivity(intent);
    }
    /**
     * 打开独立数据编辑器（文明 / 政体）。
     *
     * 只读工作区 APK 的 assets 并写回 overlay，完全不加载游戏进程，
     * 因此不需要 GL 环境、不需要 dex 预热，秒开。
     */
    private void openDataEditor(Workspace ws) {
        Workspace.setActiveId(this, ws.id);
        Intent intent = new Intent(this, com.superaoh.ui.compose.DataEditorActivity.class);
        intent.putExtra(com.superaoh.ui.compose.DataEditorActivity.EXTRA_WORKSPACE_ID, ws.id);
        startActivity(intent);
    }

    private void launch(Workspace ws, boolean openEditor) {
        Workspace.setActiveId(this, ws.id);
        Intent intent = new Intent(this, GameContainerActivity.class);
        intent.putExtra(GameContainerActivity.EXTRA_WORKSPACE_ID, ws.id);
        intent.putExtra(GameContainerActivity.EXTRA_OPEN_EDITOR, openEditor);
        startActivity(intent);
    }

    private void runOnUi(Runnable r) {
        ui.post(r);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshWorkspaces();
        refreshAiState();
    }
}