package com.superaoh.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.superaoh.core.Agent;
import com.superaoh.core.AgentTools;
import com.superaoh.core.AiConfig;
import com.superaoh.core.ChatStore;
import com.superaoh.core.ImageUtil;
import com.superaoh.core.Json;
import com.superaoh.core.Workspace;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * AI 助手对话界面。
 *
 * 相比上一版补齐的关键能力：
 *   1. 会话持久化 —— 关闭再打开，聊天记录与模型记忆都在
 *   2. 多会话管理 —— 可以建新对话、切换、删除、重命名
 *   3. 图片上传 —— 支持视觉模型，自动压缩到 1280 长边
 *   4. 工具卡片 —— 修掉了重复气泡与错位问题，状态清晰
 *   5. 流式渲染 + 中止 + 思维链折叠
 *
 * 持久化时机：每轮回答结束、每次工具调用、用户发消息后都会立即落盘，
 * 避免用户直接划掉后台导致记录丢失。
 */
public class AiChatActivity extends Activity {

    public static final String EXTRA_WORKSPACE_ID = "workspaceId";
    public static final String EXTRA_SESSION_ID = "sessionId";

    private static final int REQ_PICK_IMAGE = 2001;
    private static final int MAX_HISTORY_VIEWS = 300;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private String wsId;
    private Workspace workspace;
    private ChatStore.Session session;
    private Agent agent;
    private AiConfig.Profile profileNow;
    private Thread worker;

    // ---- 视图 ----
    private LinearLayout messagesBox;
    private ScrollView messageScroll;
    private EditText input;
    private Button sendBtn;
    private TextView statusLine;
    private TextView headerTitle;
    private TextView headerSub;
    private LinearLayout attachBar;
    private TextView attachInfo;

    // ---- 流式状态 ----
    private LinearLayout streamingBubble;
    private TextView streamingText;
    private StringBuilder streamingBuf;
    private LinearLayout reasoningBox;
    private TextView reasoningText;
    private boolean reasoningVisible;

    // ---- 待发送的图片 ----
    private final List<String> pendingImagePaths = new ArrayList<>();
    private final List<String> pendingImageDataUrls = new ArrayList<>();

    private boolean busy;
    private int viewCount;

    // ============================================================
    // 生命周期
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        wsId = getIntent().getStringExtra(EXTRA_WORKSPACE_ID);
        if (wsId == null || wsId.trim().isEmpty()) {
            wsId = Workspace.getActiveId(this);
        }
        if (wsId != null && !wsId.trim().isEmpty()) {
            workspace = Workspace.load(this, wsId);
        }
        if (workspace == null) {
            wsId = null;
        }

        buildUi();

        String sid = getIntent().getStringExtra(EXTRA_SESSION_ID);
        if (sid != null && !sid.trim().isEmpty()) {
            session = ChatStore.load(this, wsId, sid);
        }
        if (session == null) {
            // 没有指定就接着最近的会话，没有则新建
            List<ChatStore.Meta> all = ChatStore.listSessions(this, wsId);
            if (!all.isEmpty()) {
                session = ChatStore.load(this, wsId, all.get(0).id);
            }
        }
        if (session == null) {
            session = ChatStore.newSession(wsId);
        }

        initAgent();
        renderSession(session);
    }

    // ============================================================
    // UI 骨架
    // ============================================================

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        root.addView(buildHeader(), Ui.lpMatchWrap());

        messageScroll = new ScrollView(this);
        messageScroll.setBackgroundColor(Ui.BG);
        messageScroll.setFillViewport(true);

        messagesBox = new LinearLayout(this);
        messagesBox.setOrientation(LinearLayout.VERTICAL);
        int mp = Ui.dp(this, 14);
        messagesBox.setPadding(mp, Ui.dp(this, 8), mp, Ui.dp(this, 12));
        messageScroll.addView(messagesBox);

        root.addView(messageScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusLine = Ui.text(this, "", 11f, Ui.TEXT_DIM);
        statusLine.setPadding(Ui.dp(this, 16), Ui.dp(this, 2),
                Ui.dp(this, 16), Ui.dp(this, 2));
        statusLine.setVisibility(View.GONE);
        root.addView(statusLine, Ui.lpMatchWrap());

        root.addView(buildAttachBar(), Ui.lpMatchWrap());
        root.addView(buildInputBar(), Ui.lpMatchWrap());

        setContentView(root);
    }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.SURFACE);
        int p = Ui.dp(this, 12);
        bar.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));

        TextView back = Ui.text(this, "‹", 26f, Ui.TEXT_SUB);
        back.setGravity(Gravity.CENTER);
        back.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 10), Ui.dp(this, 2));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setGravity(Gravity.CENTER_VERTICAL);

        headerTitle = Ui.text(this, "新对话", 15f, Ui.TEXT);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitle.setSingleLine(true);
        headerTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBox.addView(headerTitle);

        headerSub = Ui.text(this, "", 10f, Ui.TEXT_DIM);
        titleBox.addView(headerSub);

        bar.addView(titleBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 会话列表
        TextView history = Ui.text(this, "☰", 18f, Ui.TEXT_SUB);
        history.setGravity(Gravity.CENTER);
        history.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        history.setOnClickListener(v -> showSessionList());
        bar.addView(history);

        // 更多
        TextView menu = Ui.text(this, "⋮", 20f, Ui.TEXT_SUB);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        menu.setOnClickListener(v -> showMoreMenu());
        bar.addView(menu);

        return bar;
    }

    private View buildAttachBar() {
        attachBar = new LinearLayout(this);
        attachBar.setOrientation(LinearLayout.HORIZONTAL);
        attachBar.setGravity(Gravity.CENTER_VERTICAL);
        attachBar.setBackgroundColor(Ui.SURFACE_2);
        int p = Ui.dp(this, 10);
        attachBar.setPadding(p, Ui.dp(this, 6), p, Ui.dp(this, 6));
        attachBar.setVisibility(View.GONE);

        attachInfo = Ui.text(this, "", 11f, Ui.ACCENT);
        attachBar.addView(attachInfo, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = Ui.text(this, "移除", 11f, Ui.DANGER);
        clear.setPadding(Ui.dp(this, 8), 0, 0, 0);
        clear.setOnClickListener(v -> clearPendingImages());
        attachBar.addView(clear);

        return attachBar;
    }

    private View buildInputBar() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.BOTTOM);
        wrap.setBackgroundColor(Ui.SURFACE);
        int p = Ui.dp(this, 8);
        wrap.setPadding(p, p, p, p);

        // 附件按钮
        TextView attach = Ui.text(this, "＋", 22f, Ui.TEXT_SUB);
        attach.setGravity(Gravity.CENTER);
        attach.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 6));
        attach.setOnClickListener(v -> pickImage());
        wrap.addView(attach, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        input = new EditText(this);
        input.setHint("问点什么…");
        input.setHintTextColor(Ui.TEXT_DIM);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(14f);
        input.setBackground(Ui.roundRectStroke(Ui.SURFACE_2, 12, Ui.BORDER, 1, this));
        int ip = Ui.dp(this, 12);
        input.setPadding(ip, Ui.dp(this, 8), ip, Ui.dp(this, 8));
        input.setMaxLines(5);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send();
                return true;
            }
            return false;
        });

        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.rightMargin = Ui.dp(this, 8);
        wrap.addView(input, ilp);

        sendBtn = Ui.primaryButton(this, "发送");
        sendBtn.setOnClickListener(v -> {
            if (busy) {
                cancelCurrent();
            } else {
                send();
            }
        });
        wrap.addView(sendBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        return wrap;
    }

    // ============================================================
    // Agent
    // ============================================================

    private void initAgent() {
        AiConfig.Profile profile = AiConfig.getActive(this);
        profileNow = profile;
        String sysPrompt = AiConfig.getSystemPrompt(this);

        if (profile == null || !profile.usable()) {
            headerSub.setText("未配置模型");
            headerSub.setTextColor(Ui.WARN);
            addSystemNote("还没有可用的模型配置。\n点右上角 ⋮ → AI 设置 填写接口信息。");
            setInputEnabled(false);
            return;
        }

        headerSub.setText(profile.displayLine());
        headerSub.setTextColor(Ui.TEXT_DIM);
        updateContextMeter();

        AgentTools tools = new AgentTools();
        agent = new Agent(this, workspace, profile, sysPrompt, tools,
                new ChatListener(this));
        agent.setImageDetail("low");
    }

    /**
     * 刷新上下文水位显示。
     *
     * 数字来自 Kotlin 侧 ContextBudget 的精确估算（按 CJK / 拉丁 /
     * 数字 / 标点分类计价），比原先「字符数 / 1.7」准确得多。
     * 超过 80% 变黄、超过 95% 变红，让用户提前知道该开新会话了。
     */
    private void updateContextMeter() {
        if (agent == null || headerSub == null || profileNow == null) {
            return;
        }
        try {
            int used = agent.contextTokensUsed();
            int window = profileNow.contextWindow;
            String text = profileNow.displayLine()
                    + " \u00b7 上下文 " + agent.contextUsageText();
            headerSub.setText(text);
            if (window > 0 && used * 100 / window >= 95) {
                headerSub.setTextColor(Ui.DANGER);
            } else if (window > 0 && used * 100 / window >= 80) {
                headerSub.setTextColor(Ui.WARN);
            } else {
                headerSub.setTextColor(Ui.TEXT_DIM);
            }
        } catch (Throwable t) {
            // 任何异常都不能影响聊天主流程
        }
    }

    // ============================================================
    // 会话渲染
    // ============================================================

    private void renderSession(ChatStore.Session s) {
        messagesBox.removeAllViews();
        viewCount = 0;

        String title = s.title == null || s.title.trim().isEmpty()
                ? "新对话" : s.title;
        headerTitle.setText(title);

        if (s.messages.isEmpty()) {
            if (workspace != null) {
                addSystemNote("已连接工作区「" + workspace.name + "」");
            } else {
                addSystemNote("还没有工作区，可以随便聊，"
                        + "但涉及游戏文件的工具会不可用。");
            }
            return;
        }

        for (ChatStore.StoredMessage m : s.messages) {
            renderStored(m);
        }
        scrollToBottom();
    }

    private void renderStored(ChatStore.StoredMessage m) {
        if ("user".equals(m.role)) {
            addUserBubble(m.content, m.images());
        } else if ("assistant".equals(m.role)) {
            if (m.content != null && !m.content.trim().isEmpty()) {
                addRichBubble(m.content);
            }
        } else if ("tool".equals(m.role)) {
            addToolCard(m.toolName, m.toolArgs, m.toolOk, m.content);
        }
    }

    /** 把当前会话写回磁盘。 */
    private void persist() {
        if (session == null) {
            return;
        }
        ChatStore.save(this, wsId, session);
        if (headerTitle != null) {
            headerTitle.setText(session.title == null || session.title.trim().isEmpty()
                    ? "新对话" : session.title);
        }
    }

    // ============================================================
    // 发送
    // ============================================================

    private void send() {
        if (busy || agent == null) {
            return;
        }
        String text = input.getText().toString().trim();
        if (text.isEmpty() && pendingImageDataUrls.isEmpty()) {
            return;
        }
        input.setText("");

        final List<String> imgs = new ArrayList<>(pendingImageDataUrls);
        final List<String> imgPaths = new ArrayList<>(pendingImagePaths);
        clearPendingImages();

        // 1) 立即写进会话并渲染
        ChatStore.StoredMessage sm = new ChatStore.StoredMessage("user", text);
        sm.setImages(imgPaths);
        session.messages.add(sm);
        if (session.title == null || "新对话".equals(session.title)) {
            session.title = text.isEmpty()
                    ? ("图片 · " + imgPaths.size() + " 张")
                    : (text.length() > 24 ? text.substring(0, 24) + "…" : text);
        }
        persist();

        addUserBubble(text, imgPaths);
        beginStreaming();

        busy = true;
        setBusy(true);

        final String payload = text;
        worker = new Thread(() -> {
            try {
                agent.run(payload, imgs);
            } catch (Throwable t) {
                final String msg = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage());
                ui.post(() -> onAgentError(msg));
            }
        }, "SuperAoh-AiChat");
        worker.start();
    }

    private void cancelCurrent() {
        if (agent != null) {
            agent.cancel();
        }
        setBusy(false);
        statusLine.setVisibility(View.GONE);
        finishStreaming(true);
        addSystemNote("已停止生成");
    }

    private void setBusy(boolean b) {
        busy = b;
        sendBtn.setText(b ? "停止" : "发送");
        sendBtn.setTextColor(b ? Ui.DANGER : 0xFFFFFFFF);
        sendBtn.setBackground(Ui.roundRect(b ? Ui.withAlpha(Ui.DANGER, 0x22)
                : Ui.ACCENT_DIM, 10, this));
        input.setEnabled(!b);
    }

    private void setInputEnabled(boolean enabled) {
        input.setEnabled(enabled);
        sendBtn.setEnabled(enabled);
        sendBtn.setAlpha(enabled ? 1f : 0.5f);
    }

    // ============================================================
    // 图片
    // ============================================================

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQ_PICK_IMAGE);
        } catch (Throwable t) {
            toast("无法打开相册: " + t.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        processImage(uri);
    }

    private void processImage(Uri uri) {
        if (pendingImageDataUrls.size() >= 4) {
            toast("一次最多 4 张图");
            return;
        }
        if (session == null) {
            return;
        }
        final String name = "img_" + System.currentTimeMillis();
        final File dir = ChatStore.imageDir(this, wsId, session.id);

        toast("正在处理图片…");
        new Thread(() -> {
            ImageUtil.Prepared p = ImageUtil.prepare(this, uri, dir, name);
            ui.post(() -> {
                if (p == null) {
                    toast("图片处理失败，换一张试试");
                    return;
                }
                pendingImageDataUrls.add(p.dataUrl);
                pendingImagePaths.add(p.file.getAbsolutePath());
                refreshAttachBar(p.sizeText());
            });
        }, "SuperAoh-Image").start();
    }

    private void refreshAttachBar(String lastSize) {
        int n = pendingImagePaths.size();
        if (n == 0) {
            attachBar.setVisibility(View.GONE);
            return;
        }
        attachBar.setVisibility(View.VISIBLE);
        String info = "已附加 " + n + " 张图";
        if (lastSize != null) {
            info += "（" + lastSize + "）";
        }
        attachInfo.setText(info);
    }

    private void clearPendingImages() {
        pendingImageDataUrls.clear();
        pendingImagePaths.clear();
        if (attachBar != null) {
            attachBar.setVisibility(View.GONE);
        }
    }

    // ============================================================
    // 流式气泡
    // ============================================================

    private void beginStreaming() {
        streamingBuf = new StringBuilder();

        streamingBubble = new LinearLayout(this);
        streamingBubble.setOrientation(LinearLayout.VERTICAL);
        streamingBubble.setBackground(Ui.bubble(Ui.BUBBLE_AI, this,
                false, true, true, true));
        int p = Ui.dp(this, 12);
        streamingBubble.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));

        reasoningBox = new LinearLayout(this);
        reasoningBox.setOrientation(LinearLayout.VERTICAL);
        reasoningBox.setBackground(Ui.roundRect(Ui.BG, 8, this));
        int rp = Ui.dp(this, 10);
        reasoningBox.setPadding(rp, rp, rp, rp);
        reasoningBox.setVisibility(View.GONE);

        TextView rTitle = Ui.text(this, "▸ 思考过程", 10f, Ui.TEXT_DIM);
        rTitle.setOnClickListener(v -> {
            reasoningVisible = !reasoningVisible;
            reasoningText.setVisibility(reasoningVisible ? View.VISIBLE : View.GONE);
            rTitle.setText(reasoningVisible ? "▾ 思考过程" : "▸ 思考过程");
        });
        reasoningBox.addView(rTitle);

        reasoningText = Ui.mono(this, "", 10f, Ui.TEXT_DIM);
        reasoningText.setLineSpacing(Ui.dp(this, 2), 1f);
        reasoningText.setVisibility(View.GONE);
        reasoningBox.addView(reasoningText, Ui.lpMatchWrap(4, this));

        streamingBubble.addView(reasoningBox, Ui.lpMatchWrap());

        streamingText = Ui.text(this, "", 14f, Ui.TEXT);
        streamingText.setLineSpacing(Ui.dp(this, 3), 1f);
        streamingText.setTextIsSelectable(true);
        streamingBubble.addView(streamingText, Ui.lpMatchWrap(2, this));

        messagesBox.addView(wrapLeft(streamingBubble, Ui.dp(this, 40),
                Ui.dp(this, 5), Ui.dp(this, 5)));
        viewCount++;
        scrollToBottom();
    }

    private void appendContent(String delta) {
        if (streamingBuf == null) {
            beginStreaming();
        }
        streamingBuf.append(delta);
        streamingText.setText(streamingBuf.toString());
        scrollToBottom();
    }

    private void appendReasoning(String delta) {
        if (reasoningBox == null) {
            return;
        }
        reasoningBox.setVisibility(View.VISIBLE);
        reasoningText.setText(android.text.TextUtils.concat(
                reasoningText.getText(), delta));
        if (reasoningVisible) {
            scrollToBottom();
        }
    }

    /**
     * 结束流式气泡。
     *
     * @param keepContent true = 把已生成内容保留成正式消息；false = 保留但不可再追加
     */
    private void finishStreaming(boolean keepContent) {
        if (streamingBubble == null) {
            return;
        }
        String full = streamingBuf == null ? "" : streamingBuf.toString();

        if (!full.trim().isEmpty()) {
            // 把纯文本换成富文本（支持代码块）
            streamingBubble.removeView(streamingText);
            streamingBubble.addView(buildRichContent(full), Ui.lpMatchWrap(2, this));

            // 落盘
            ChatStore.StoredMessage sm = new ChatStore.StoredMessage("assistant", full);
            if (session != null) {
                session.messages.add(sm);
                persist();
            }
        } else {
            // 没有任何内容：整块移除，避免留下空气泡
            View parent = (View) streamingBubble.getParent();
            if (parent != null) {
                View grand = (View) parent.getParent();
                if (grand != null) {
                    messagesBox.removeView(grand);
                }
            }
        }

        if (reasoningText != null && reasoningText.getText().length() > 0) {
            reasoningVisible = false;
            reasoningText.setVisibility(View.GONE);
        }

        streamingBubble = null;
        streamingText = null;
        streamingBuf = null;
        reasoningBox = null;
        reasoningText = null;
    }

    // ============================================================
    // 消息渲染
    // ============================================================

    /** 居中的浅色提示条。 */
    private void addSystemNote(String text) {
        TextView tv = Ui.text(this, text, 11f, Ui.TEXT_DIM);
        tv.setGravity(Gravity.CENTER);
        tv.setLineSpacing(Ui.dp(this, 2), 1f);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 8);
        wrap.setPadding(Ui.dp(this, 24), p, Ui.dp(this, 24), Ui.dp(this, 14));
        wrap.addView(tv, Ui.lpMatchWrap());
        messagesBox.addView(wrap);
        scrollToBottom();
    }

    /** 左对齐包装（AI 侧）。 */
    private View wrapLeft(View bubble, int rightPad, int topPad, int bottomPad) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(0, topPad, rightPad, bottomPad);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);
        row.addView(bubble, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        outer.addView(row, Ui.lpMatchWrap());
        return outer;
    }

    /** 右对齐包装（用户侧）。 */
    private View wrapRight(View bubble, int leftPad, int topPad, int bottomPad) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(leftPad, topPad, 0, bottomPad);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        row.addView(bubble, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        outer.addView(row, Ui.lpMatchWrap());
        return outer;
    }

    /** 用户气泡（可带图）。 */
    private void addUserBubble(String text, List<String> images) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackground(Ui.bubble(Ui.BUBBLE_USER, this, true, false, true, true));
        int p = Ui.dp(this, 12);
        bubble.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));

        // 图片区
        if (images != null && !images.isEmpty()) {
            for (String path : images) {
                View iv = buildThumb(path);
                if (iv != null) {
                    bubble.addView(iv, Ui.lpMatchWrap(3, this));
                }
            }
        }

        if (text != null && !text.trim().isEmpty()) {
            TextView tv = Ui.text(this, text, 14f, 0xFFF2F5FF);
            tv.setLineSpacing(Ui.dp(this, 3), 1f);
            tv.setTextIsSelectable(true);
            bubble.addView(tv, Ui.lpMatchWrap(
                    images != null && !images.isEmpty() ? 6 : 0, this));
        }

        messagesBox.addView(wrapRight(bubble, Ui.dp(this, 40),
                Ui.dp(this, 5), Ui.dp(this, 5)));
        trimViews();
        scrollToBottom();
    }

    /** AI 富文本气泡（历史消息用）。 */
    private void addRichBubble(String text) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackground(Ui.bubble(Ui.BUBBLE_AI, this, false, true, true, true));
        int p = Ui.dp(this, 12);
        bubble.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));
        bubble.addView(buildRichContent(text), Ui.lpMatchWrap());

        messagesBox.addView(wrapLeft(bubble, Ui.dp(this, 40),
                Ui.dp(this, 5), Ui.dp(this, 5)));
        trimViews();
        scrollToBottom();
    }

    // ============================================================
    // Markdown 渲染（Markwon）
    // ============================================================

    /** 懒加载 Markwon 实例；失败时降级为纯文本。 */
    private Object markwon;

    /**
     * 用 Markwon 把 Markdown 渲染成 Spanned。
     *
     * Markwon（io.noties.markwon，3419★，Apache-2.0）是纯 Java、不依赖
     * WebView 的 Android Markdown 渲染库，把 md 转成 Spannable，
     * 直接 setText 到 TextView 即可，不需要任何 View 容器。
     *
     * 失败时返回 null，由调用方降级到纯文本，保证不影响聊天主流程。
     */
    private CharSequence renderMarkdown(String md) {
        if (md == null || md.isEmpty()) {
            return "";
        }
        try {
            if (markwon == null) {
                markwon = io.noties.markwon.Markwon.create(this);
            }
            String fixed = normalizeMarkdown(md);
            android.text.Spanned sp =
                    ((io.noties.markwon.Markwon) markwon).toMarkdown(fixed);
            return sp != null ? sp : fixed;
        } catch (Throwable t) {
            return normalizeMarkdown(md);
        }
    }

    /**
     * 预处理 Markdown：
     *  - 模型常把代码块写成 ```java 但没有闭合，补齐避免整段被吞
     *  - 把连续 3 个以上空行压成 2 个，避免气泡过高
     */
    private static String normalizeMarkdown(String md) {
        String s = md.replace("\r\n", "\n");
        int fences = 0;
        int idx = 0;
        while (true) {
            int f = s.indexOf("```", idx);
            if (f < 0) {
                break;
            }
            fences++;
            idx = f + 3;
        }
        if (fences % 2 == 1) {
            s = s + "\n```";
        }
        s = s.replaceAll("\n{3,}", "\n\n");
        return s;
    }

    /** 缩略图。点击可放大。 */
    private View buildThumb(String path) {
        File f = new File(path);
        if (!f.exists()) {
            return null;
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            int longEdge = Math.max(o.outWidth, o.outHeight);
            while (longEdge / sample > 480) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(path, o2);
            if (bmp == null) {
                return null;
            }

            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bmp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setAdjustViewBounds(true);
            iv.setBackground(Ui.roundRect(Ui.BG, 8, this));
            iv.setClipToOutline(true);
            int maxW = Ui.dp(this, 200);
            iv.setMaxWidth(maxW);
            iv.setMaxHeight(Ui.dp(this, 200));
            iv.setOnClickListener(v -> showImageFull(path));
            return iv;
        } catch (Throwable t) {
            return null;
        }
    }

    private void showImageFull(String path) {
        try {
            Bitmap bmp = BitmapFactory.decodeFile(path);
            if (bmp == null) {
                return;
            }
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bmp);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xFF000000);

            new AlertDialog.Builder(this)
                    .setView(iv)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 富文本渲染：识别 ``` 代码块 与 **粗体**。
     *
     * 零依赖约束下不引入 Markdown 库，只做最影响可读性的两种。
     * 其余原样输出，保证不吞字符。
     */
    /**
     * 富文本渲染。
     *
     * 采用「代码块单独渲染 + 其余交给 Markwon」的混合策略：
     *   · 代码块单独用等宽 TextView + 横向滚动，保证长行不被折断
     *     （Markwon 也能渲染代码块，但横向滚动在纯 TextView 里更可控）
     *   · 普通段落、标题、列表、引用、粗体、链接、行内代码
     *     全部交给 Markwon（3419★，纯 Java，no WebView）
     */
    private View buildRichContent(String text) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        if (text == null) {
            text = "";
        }

        String[] parts = text.split("```", -1);
        // 只有一段且无代码块 → 直接走 Markwon
        if (parts.length == 1) {
            col.addView(buildMarkdownBlock(text), Ui.lpMatchWrap());
            return col;
        }

        for (int i = 0; i < parts.length; i++) {
            String seg = parts[i];
            if (i % 2 == 1) {
                String lang = "";
                String code = seg;
                int nl = seg.indexOf('\n');
                if (nl > 0 && nl < 20) {
                    String first = seg.substring(0, nl).trim();
                    if (first.matches("[A-Za-z0-9+#._-]{0,16}")) {
                        lang = first;
                        code = seg.substring(nl + 1);
                    }
                }
                col.addView(buildCodeBlock(lang, code), Ui.lpMatchWrap(6, this));
            } else {
                if (seg.trim().isEmpty()) {
                    continue;
                }
                col.addView(buildMarkdownBlock(seg), Ui.lpMatchWrap());
            }
        }
        return col;
    }

    /** Markwon 渲染的普通文本块。 */
    private View buildMarkdownBlock(String md) {
        TextView tv = Ui.text(this, "", 14f, Ui.TEXT);
        tv.setLineSpacing(Ui.dp(this, 3), 1f);
        tv.setTextIsSelectable(true);
        tv.setText(renderMarkdown(md));
        tv.setLinkTextColor(Ui.ACCENT);
        return tv;
    }

    private View buildCodeBlock(String lang, String code) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.roundRect(Ui.BG, 8, this));
        int p = Ui.dp(this, 10);
        box.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));

        if (lang != null && !lang.isEmpty()) {
            TextView tag = Ui.text(this, lang, 9f, Ui.ACCENT);
            tag.setTypeface(Typeface.MONOSPACE);
            box.addView(tag);
        }

        TextView tv = Ui.mono(this, code, 11f, 0xFFC8D3F0);
        tv.setTextIsSelectable(true);
        tv.setHorizontallyScrolling(true);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(tv);
        box.addView(hs, Ui.lpMatchWrap(4, this));

        return box;
    }

    private View buildTextBlock(String seg) {
        // 简单处理 **粗体**：直接去掉标记，避免残留符号影响阅读
        String cleaned = seg.replace("**", "");
        TextView tv = Ui.text(this, cleaned, 14f, Ui.TEXT);
        tv.setLineSpacing(Ui.dp(this, 3), 1f);
        tv.setTextIsSelectable(true);
        return tv;
    }

    /**
     * 工具调用卡片。
     *
     * 上一版的显示问题：
     *   · onToolCall 里先 finishStreaming 又 beginStreaming，
     *     导致空气泡残留、卡片位置错乱；
     *   · 结果区永远折叠且没有明显的可点击提示。
     *
     * 现在的处理：
     *   · 卡片独立成块，左边留白与 AI 气泡对齐；
     *   · 默认展示参数摘要与结果前几行，点击展开全部；
     *   · 状态用颜色区分成功/失败。
     */
    private void addToolCard(String toolName, String argsSummary,
                             boolean ok, String resultPreview) {
        if (toolName == null || toolName.trim().isEmpty()) {
            return;
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.roundRectStroke(
                Ui.BUBBLE_TOOL, 10, ok ? Ui.OK : Ui.WARN, 1, this));
        int p = Ui.dp(this, 11);
        card.setPadding(p, Ui.dp(this, 9), p, Ui.dp(this, 9));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView dot = Ui.text(this, "●", 10f, ok ? Ui.OK : Ui.WARN);
        dot.setPadding(0, 0, Ui.dp(this, 6), 0);
        head.addView(dot);

        TextView name = Ui.mono(this, toolName, 11f, ok ? Ui.OK : Ui.WARN);
        head.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView state = Ui.text(this, ok ? "完成" : "失败", 9f,
                ok ? Ui.OK : Ui.WARN);
        state.setPadding(0, 0, Ui.dp(this, 6), 0);
        head.addView(state);

        TextView arrow = Ui.text(this, "▾", 12f, Ui.TEXT_DIM);
        head.addView(arrow);

        card.addView(head, Ui.lpMatchWrap());

        if (argsSummary != null && !argsSummary.trim().isEmpty()) {
            TextView args = Ui.mono(this, argsSummary, 10f, Ui.TEXT_DIM);
            args.setPadding(0, Ui.dp(this, 4), 0, 0);
            args.setTextIsSelectable(true);
            card.addView(args, Ui.lpMatchWrap());
        }

        // ---- 结果区（借鉴 Operit 的 ToolResultDisplay 策略）----
        // Operit 的做法：行内只显示 200 字摘要，点击弹出详情对话框，
        // 并提供「复制」。比就地展开更省空间，长输出也不会撑爆列表。
        final String full = resultPreview == null ? "" : resultPreview;
        final boolean hasContent = !full.trim().isEmpty();
        final String summary = hasContent ? summarize(full) : (ok ? "执行成功" : "执行失败");

        final TextView result = Ui.mono(this, summary, 10f,
                hasContent ? Ui.TEXT_SUB : (ok ? Ui.OK : Ui.WARN));
        result.setPadding(0, Ui.dp(this, 6), 0, 0);
        result.setTextIsSelectable(true);
        card.addView(result, Ui.lpMatchWrap());

        // 详情按钮（仅有内容时显示）
        if (hasContent) {
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(0, Ui.dp(this, 8), 0, 0);

            TextView more = Ui.text(this, "查看详情", 10f, Ui.ACCENT);
            more.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
            bar.addView(more, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView copy = Ui.text(this, "复制", 10f, Ui.TEXT_DIM);
            copy.setPadding(Ui.dp(this, 10), Ui.dp(this, 2),
                    Ui.dp(this, 4), Ui.dp(this, 2));
            bar.addView(copy);

            card.addView(bar, Ui.lpMatchWrap());

            more.setOnClickListener(v -> showToolDetail(toolName, full));
            copy.setOnClickListener(v -> {
                copyToClipboard(full);
                copy.setText("已复制");
                copy.setTextColor(Ui.OK);
                copy.postDelayed(() -> {
                    copy.setText("复制");
                    copy.setTextColor(Ui.TEXT_DIM);
                }, 1500);
            });
        }

        messagesBox.addView(wrapLeft(card, Ui.dp(this, 48),
                Ui.dp(this, 4), Ui.dp(this, 4)));
        trimViews();
        scrollToBottom();
    }

    /**
     * 生成行内摘要：单行化 + 截断 200 字。
     * 与 Operit ToolResultDisplay 的 summaryText 一致。
     */
    private static String summarize(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (one.length() <= 200) {
            return one;
        }
        return one.substring(0, 200) + "…";
    }

    /** 弹出工具结果详情：等宽字体、可滚动、可全选复制。 */
    private void showToolDetail(String toolName, String content) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 14);
        box.setPadding(p, p, p, p);
        box.setBackground(Ui.roundRect(Ui.SURFACE, 14, this));

        TextView head = Ui.text(this, toolName, 12f, Ui.ACCENT);
        head.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.addView(head, Ui.lpMatchWrap());

        ScrollView sv = new ScrollView(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = Ui.dp(this, 8);

        TextView body = Ui.mono(this, content, 11f, Ui.TEXT);
        body.setTextIsSelectable(true);
        sv.addView(body, Ui.lpMatchWrap());
        box.addView(sv, slp);

        new android.app.AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton("复制全部", (d, w) -> copyToClipboard(content))
                .setNeutralButton("关闭", null)
                .show();
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("tool", text));
                toast("已复制到剪贴板");
            }
        } catch (Throwable t) {
            toast("复制失败：" + t.getMessage());
        }
    }

    /** 取前 n 行，超出加省略提示。 */
    private static String headLines(String s, int n) {
        if (s == null) {
            return "";
        }
        String[] lines = s.split("\n", -1);
        if (lines.length <= n) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        sb.append("\n… 共 ").append(lines.length).append(" 行，点击展开");
        return sb.toString();
    }

    private void trimViews() {
        viewCount++;
        if (viewCount <= MAX_HISTORY_VIEWS) {
            return;
        }
        int remove = viewCount - MAX_HISTORY_VIEWS;
        for (int i = 0; i < remove && messagesBox.getChildCount() > 1; i++) {
            messagesBox.removeViewAt(0);
        }
        viewCount = MAX_HISTORY_VIEWS;
    }

    private void scrollToBottom() {
        messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ============================================================
    // Agent 回调
    // ============================================================

    /**
     * Agent 回调。
     *
     * 必须是具名静态嵌套类：D8 4.0.48 处理「实现接口的匿名内部类」时
     * 会抛 NullPointerException（Cannot invoke "String.length()"），
     * 直接让整个构建失败。
     */
    static final class ChatListener implements Agent.Listener {
        private final AiChatActivity host;

        ChatListener(AiChatActivity host) {
            this.host = host;
        }

        @Override
        public void onThinking(final int round) {
            host.ui.post(() -> {
                host.statusLine.setVisibility(View.VISIBLE);
                host.statusLine.setText(round <= 1
                        ? "正在思考…"
                        : "第 " + round + " 轮 · 继续处理…");
            });
        }

        @Override
        public void onContentDelta(final String delta) {
            host.ui.post(() -> host.appendContent(delta));
        }

        @Override
        public void onReasoningDelta(final String delta) {
            host.ui.post(() -> host.appendReasoning(delta));
        }

        @Override
        public void onToolCall(final String toolName, final String argsSummary,
                               final boolean ok, final String resultPreview) {
            host.ui.post(() -> {
                // 先把当前已生成内容收尾，再插入工具卡片，然后开新气泡
                host.finishStreaming(true);
                host.addToolCard(toolName, argsSummary, ok, resultPreview);
                host.beginStreaming();
                host.statusLine.setVisibility(View.VISIBLE);
                host.statusLine.setText("工具执行中…");
            });
        }

        @Override
        public void onAnswer(final String answer) {
            host.ui.post(() -> {
                host.finishStreaming(true);
                host.setBusy(false);
                host.statusLine.setVisibility(View.GONE);
                host.updateContextMeter();
                host.persist();
            });
        }

        @Override
        public void onError(final String message) {
            host.ui.post(() -> host.onAgentError(message));
        }

        @Override
        public void onCancelled() {
            host.ui.post(() -> {
                host.finishStreaming(true);
                host.setBusy(false);
                host.statusLine.setVisibility(View.GONE);
            });
        }

        @Override
        public void onMessagesChanged(List<Agent.Message> messages) {
            // 各回调已增量渲染，这里不重绘
        }
    }

    private void onAgentError(String message) {
        finishStreaming(true);
        setBusy(false);
        statusLine.setVisibility(View.GONE);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackground(Ui.roundRectStroke(
                Ui.withAlpha(Ui.DANGER, 0x1F), 12, Ui.DANGER, 1, this));
        int p = Ui.dp(this, 12);
        bubble.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));

        TextView tv = Ui.text(this, message, 12f, Ui.DANGER);
        tv.setLineSpacing(Ui.dp(this, 2), 1f);
        tv.setTextIsSelectable(true);
        bubble.addView(tv);

        messagesBox.addView(wrapLeft(bubble, Ui.dp(this, 40),
                Ui.dp(this, 5), Ui.dp(this, 5)));
        scrollToBottom();
    }

    // ============================================================
    // 会话列表
    // ============================================================

    private void showSessionList() {
        List<ChatStore.Meta> all = ChatStore.listSessions(this, wsId);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Ui.SURFACE);
        int p = Ui.dp(this, 12);
        box.setPadding(p, p, p, p);

        TextView title = Ui.text(this, "对话记录", 15f, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        Button newBtn = Ui.primaryButton(this, "＋ 新建对话");
        newBtn.setOnClickListener(v -> {
            startNewSession();
        });
        box.addView(newBtn, Ui.lpMatchWrap(10, this));

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 340)));

        if (all.isEmpty()) {
            list.addView(Ui.text(this, "还没有历史对话", 12f, Ui.TEXT_DIM),
                    Ui.lpMatchWrap(10, this));
        } else {
            SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
            for (ChatStore.Meta m : all) {
                list.addView(buildSessionRow(m, fmt));
            }
        }

        new AlertDialog.Builder(this)
                .setView(box)
                .setNegativeButton("关闭", null)
                .show();
    }

    private View buildSessionRow(ChatStore.Meta m, SimpleDateFormat fmt) {
        boolean current = session != null && session.id != null
                && session.id.equals(m.id);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(Ui.roundRectStroke(
                current ? Ui.withAlpha(Ui.ACCENT, 0x1A) : Ui.SURFACE_2,
                10, current ? Ui.ACCENT : Ui.BORDER, 1, this));
        int p = Ui.dp(this, 10);
        row.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        LinearLayout.LayoutParams lp = Ui.lpMatchWrap();
        lp.bottomMargin = Ui.dp(this, 6);
        row.setLayoutParams(lp);

        String t = m.title == null || m.title.trim().isEmpty() ? "未命名" : m.title;
        TextView name = Ui.text(this, t, 13f, current ? Ui.ACCENT : Ui.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name);

        String when = m.updatedAt > 0 ? fmt.format(new Date(m.updatedAt)) : "";
        TextView meta = Ui.text(this, when + " · " + m.messageCount + " 条",
                10f, Ui.TEXT_DIM);
        row.addView(meta, Ui.lpMatchWrap(2, this));

        row.setOnClickListener(v -> {
            if (current) {
                return;
            }
            openSession(m.id);
        });

        // 长按删除
        row.setOnLongClickListener(v -> {
            confirmDeleteSession(m);
            return true;
        });

        return row;
    }

    private void confirmDeleteSession(ChatStore.Meta m) {
        new AlertDialog.Builder(this)
                .setTitle("删除对话")
                .setMessage("会永久删除这条对话记录与其中的图片。\n\n"
                        + (m.title == null ? "未命名" : m.title))
                .setPositiveButton("删除", (d, w) -> {
                    ChatStore.delete(this, wsId, m.id);
                    if (session != null && m.id.equals(session.id)) {
                        startNewSession();
                    } else {
                        toast("已删除");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openSession(String sid) {
        ChatStore.Session s = ChatStore.load(this, wsId, sid);
        if (s == null) {
            toast("打开失败");
            return;
        }
        if (agent != null) {
            agent.cancel();
        }
        session = s;
        rebuildAgentHistory(s);
        renderSession(s);
    }

    private void startNewSession() {
        if (agent != null) {
            agent.cancel();
        }
        if (session != null) {
            persist(); // 保存旧会话
        }
        session = ChatStore.newSession(wsId);
        if (agent != null) {
            agent.clearHistory();
        }
        renderSession(session);
        persist();
        toast("已新建对话");
    }

    /**
     * 把持久化的历史灌回 Agent，让模型恢复记忆。
     *
     * 只恢复 user / assistant 的正文。
     * 不恢复 tool 消息与 tool_calls：历史工具调用对当前上下文意义不大，
     * 且容易与裁剪逻辑冲突导致 API 报错。
     */
    private void rebuildAgentHistory(ChatStore.Session s) {
        if (agent == null) {
            return;
        }
        agent.clearHistory();
        for (ChatStore.StoredMessage m : s.messages) {
            if ("user".equals(m.role) && m.content != null
                    && !m.content.trim().isEmpty()) {
                Agent.Message am = new Agent.Message("user", m.content);
                List<String> imgs = loadImageDataUrls(m);
                if (!imgs.isEmpty()) {
                    am.imageDataUrls = imgs;
                }
                agent.addMessage(am);
            } else if ("assistant".equals(m.role) && m.content != null
                    && !m.content.trim().isEmpty()) {
                agent.addMessage(new Agent.Message("assistant", m.content));
            }
        }
    }

    private List<String> loadImageDataUrls(ChatStore.StoredMessage m) {
        List<String> out = new ArrayList<>();
        for (String path : m.images()) {
            String url = ImageUtil.fileToDataUrl(new File(path));
            if (url != null) {
                out.add(url);
            }
        }
        return out;
    }

    // ============================================================
    // 菜单
    // ============================================================

    private void showMoreMenu() {
        List<String> items = new ArrayList<>();
        items.add("重命名对话");
        items.add("AI 设置");
        items.add("清空当前对话");
        if (workspace != null) {
            items.add("查看工作区");
        }
        items.add("工具说明");

        new AlertDialog.Builder(this)
                .setTitle("操作")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String picked = items.get(which);
                    if ("重命名对话".equals(picked)) {
                        promptRename();
                    } else if ("AI 设置".equals(picked)) {
                        startActivity(new Intent(this, AiSettingsActivity.class));
                    } else if ("清空当前对话".equals(picked)) {
                        confirmClear();
                    } else if ("查看工作区".equals(picked)) {
                        startActivity(new Intent(this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                        finish();
                    } else if ("工具说明".equals(picked)) {
                        showToolHelp();
                    }
                })
                .show();
    }

    private void promptRename() {
        final EditText e = new EditText(this);
        e.setText(session == null ? "" : session.title);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.TEXT_DIM);
        e.setBackground(Ui.roundRectStroke(Ui.SURFACE_2, 9, Ui.BORDER, 1, this));
        int p = Ui.dp(this, 10);
        e.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int bp = Ui.dp(this, 16);
        box.setPadding(bp, bp, bp, bp);
        box.addView(e);

        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    if (session != null) {
                        String t = e.getText().toString().trim();
                        session.title = t.isEmpty() ? "新对话" : t;
                        persist();
                        headerTitle.setText(session.title);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空当前对话")
                .setMessage("会删除这条对话的所有消息记录。")
                .setPositiveButton("清空", (d, w) -> {
                    if (session == null) {
                        return;
                    }
                    ChatStore.delete(this, wsId, session.id);
                    session = ChatStore.newSession(wsId);
                    if (agent != null) {
                        agent.clearHistory();
                    }
                    renderSession(session);
                    persist();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showToolHelp() {
        String msg = "AI 可以调用这些工具操作工作区：\n\n"
                + "· list_workspace — 列出工作区\n"
                + "· list_dir — 列出目录\n"
                + "· read_text — 读文本文件\n"
                + "· write_text — 写文本文件\n"
                + "· delete_path — 删除文件\n"
                + "· apk_info — APK 基本信息\n"
                + "· apk_list_assets — APK 内资源\n"
                + "· apk_extract — 从 APK 提取\n"
                + "· search_in_apk — 搜索字符串\n"
                + "· dex_list — DEX 类列表\n"
                + "· runtime_status — 运行状态\n\n"
                + "所有路径都限制在工作区内。";
        new AlertDialog.Builder(this)
                .setTitle("工具能力")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ============================================================
    // 生命周期
    // ============================================================

    @Override
    protected void onPause() {
        super.onPause();
        persist(); // 退到后台先存一次
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (agent != null) {
            agent.cancel();
        }
        ui.removeCallbacksAndMessages(null);
    }

    @Override
    public void onBackPressed() {
        if (busy) {
            new AlertDialog.Builder(this)
                    .setTitle("正在生成")
                    .setMessage("要停止并退出吗？")
                    .setPositiveButton("停止退出", (d, w) -> {
                        cancelCurrent();
                        finish();
                    })
                    .setNegativeButton("继续等待", null)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}