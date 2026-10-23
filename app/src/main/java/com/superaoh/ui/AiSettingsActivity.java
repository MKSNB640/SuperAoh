package com.superaoh.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.superaoh.core.AiConfig;
import com.superaoh.core.ModelCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 设置界面。
 *
 * 三块内容：
 *   1. 连接配置 —— BaseURL / APIKey / 模型（可从服务端自动拉取）
 *   2. 生成参数 —— 温度、回复上限、上下文窗口
 *   3. 系统提示词
 *
 * 模型列表：
 *   点「拉取」会请求 {baseUrl}/models。成功后弹列表选择，
 *   同时缓存起来供下次直接下拉；失败则保留手动输入，不阻塞用户。
 *
 * 上下文窗口：
 *   用户可手动填写，也可以从预设里选。这个值会真正影响 Agent
 *   的历史裁剪逻辑（见 Agent.trimmedHistory）。
 */
public class AiSettingsActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText nameInput;
    private EditText baseUrlInput;
    private EditText apiKeyInput;
    private EditText modelInput;
    private EditText maxTokensInput;
    private EditText contextInput;
    private EditText systemInput;
    private SeekBar tempBar;
    private TextView tempValue;

    private Button fetchModelsBtn;
    private TextView fetchStatus;

    /** 从服务端拉到、或上次缓存的模型候选。 */
    private final List<String> modelCandidates = new ArrayList<>();

    private AiConfig.Profile current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = AiConfig.getActive(this);
        if (current == null) {
            current = new AiConfig.Profile("default", "默认", "", "", "", 0.7, 4096);
        }
        buildUi();
        fillForm(current);
    }

    // ============================================================
    // UI
    // ============================================================

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 22), p, Ui.dp(this, 28));
        scroll.addView(root);

        // ---- 标题行 ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = Ui.text(this, "‹", 26f, Ui.TEXT_SUB);
        back.setPadding(0, 0, Ui.dp(this, 12), Ui.dp(this, 2));
        back.setOnClickListener(v -> {
            save(false);
            finish();
        });
        header.addView(back);

        header.addView(Ui.title(this, "AI 设置"),
                new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button profileButton = Ui.ghostButton(this, "配置档");
        profileButton.setOnClickListener(v -> showProfilePicker());
        header.addView(profileButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(header, Ui.lpMatchWrap());
        root.addView(Ui.subtitle(this, "配置模型服务与生成参数"), Ui.lpMatchWrap(2, this));
        TextView profileHint = Ui.text(this,
                "可切换已有配置，或复制当前配置后填写另一组服务地址与模型。",
                9f, Ui.TEXT_DIM);
        profileHint.setPadding(Ui.dp(this, 44), Ui.dp(this, 4), 0, 0);
        root.addView(profileHint, Ui.lpMatchWrap());

        // ---- 连接配置 ----
        root.addView(sectionTitle("连接配置"), Ui.lpMatchWrap(20, this));
        LinearLayout connCard = Ui.card(this);

        nameInput = addField(connCard, "配置名称", "例如：官方 / 中转站", false);
        baseUrlInput = addField(connCard, "BaseURL",
                "https://api.openai.com/v1", false);
        apiKeyInput = addField(connCard, "API Key", "sk-...", true);

        // 模型行：输入框 + 拉取按钮
        connCard.addView(fieldLabel("模型"), Ui.lpMatchWrap(10, this));

        LinearLayout modelRow = new LinearLayout(this);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.setGravity(Gravity.CENTER_VERTICAL);

        modelInput = buildInput("gpt-4o", false);
        LinearLayout.LayoutParams mip = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mip.rightMargin = Ui.dp(this, 8);
        modelRow.addView(modelInput, mip);

        fetchModelsBtn = Ui.ghostButton(this, "拉取");
        fetchModelsBtn.setOnClickListener(v -> fetchModels());
        modelRow.addView(fetchModelsBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        connCard.addView(modelRow, Ui.lpMatchWrap(4, this));

        fetchStatus = Ui.text(this, "", 10f, Ui.TEXT_DIM);
        fetchStatus.setPadding(0, Ui.dp(this, 5), 0, 0);
        connCard.addView(fetchStatus, Ui.lpMatchWrap());

        root.addView(connCard, Ui.lpMatchWrap(8, this));

        // ---- 生成参数 ----
        root.addView(sectionTitle("生成参数"), Ui.lpMatchWrap(20, this));
        LinearLayout genCard = Ui.card(this);

        // 温度
        LinearLayout tempHead = new LinearLayout(this);
        tempHead.setOrientation(LinearLayout.HORIZONTAL);
        tempHead.setGravity(Gravity.CENTER_VERTICAL);
        tempHead.addView(fieldLabelInline("温度"), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tempValue = Ui.mono(this, "0.7", 12f, Ui.ACCENT);
        tempHead.addView(tempValue);
        genCard.addView(tempHead, Ui.lpMatchWrap());

        tempBar = new SeekBar(this);
        tempBar.setMax(20); // 0.00 - 2.00，步进 0.1
        tempBar.setOnSeekBarChangeListener(new TempWatcher(this));
        genCard.addView(tempBar, Ui.lpMatchWrap(2, this));

        TextView tempHint = Ui.text(this,
                "低 = 更稳定严谨，高 = 更有想法。改代码建议 0.2~0.5。",
                9f, Ui.TEXT_DIM);
        genCard.addView(tempHint, Ui.lpMatchWrap());

        maxTokensInput = addField(genCard, "回复上限 (token)",
                "4096", false);

        contextInput = addField(genCard, "上下文窗口 (token)",
                "留空或 0 = 不限制，按条数保留", false);

        // 上下文预设
        TextView presetLabel = Ui.text(this, "常用窗口", 10f, Ui.TEXT_DIM);
        presetLabel.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        genCard.addView(presetLabel);

        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] presets = {"8K", "16K", "32K", "64K", "128K", "不限"};
        for (int i = 0; i < presets.length; i++) {
            final String label = presets[i];
            final int value = presetValue(label);
            Button b = Ui.ghostButton(this, label);
            b.setTextSize(11f);
            b.setOnClickListener(v -> {
                contextInput.setText(value == 0 ? "" : String.valueOf(value));
                toast("已设为 " + label);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                lp.leftMargin = Ui.dp(this, 4);
            }
            presetRow.addView(b, lp);
        }
        genCard.addView(presetRow, Ui.lpMatchWrap(2, this));

        TextView ctxHint = Ui.text(this,
                "填模型真实的窗口大小。超出会导致接口报错，"
                        + "填小了则长对话会被提前裁剪。",
                9f, Ui.TEXT_DIM);
        ctxHint.setPadding(0, Ui.dp(this, 6), 0, 0);
        ctxHint.setLineSpacing(Ui.dp(this, 1), 1f);
        genCard.addView(ctxHint);

        root.addView(genCard, Ui.lpMatchWrap(8, this));

        // ---- 系统提示词 ----
        root.addView(sectionTitle("系统提示词"), Ui.lpMatchWrap(20, this));
        LinearLayout sysCard = Ui.card(this);

        systemInput = buildInput("", false);
        systemInput.setMinLines(6);
        systemInput.setGravity(Gravity.TOP | Gravity.START);
        systemInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        sysCard.addView(systemInput, Ui.lpMatchWrap());

        LinearLayout sysRow = new LinearLayout(this);
        sysRow.setOrientation(LinearLayout.HORIZONTAL);

        Button resetPrompt = Ui.ghostButton(this, "恢复默认");
        resetPrompt.setOnClickListener(v -> {
            systemInput.setText(AiConfig.defaultSystemPrompt());
            toast("已恢复默认提示词");
        });
        sysRow.addView(resetPrompt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(sysCard, Ui.lpMatchWrap(8, this));
        root.addView(sysRow, Ui.lpMatchWrap(8, this));

        // ---- 底部按钮 ----
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button saveBtn = Ui.primaryButton(this, "保存");
        saveBtn.setOnClickListener(v -> {
            if (save(true)) {
                toast("已保存");
            }
        });
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sbp.rightMargin = Ui.dp(this, 8);
        actions.addView(saveBtn, sbp);

        Button testBtn = Ui.ghostButton(this, "测试连接");
        testBtn.setOnClickListener(v -> testConnection());
        actions.addView(testBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(actions, Ui.lpMatchWrap(22, this));

        setContentView(scroll);
    }

    private TextView sectionTitle(String text) {
        TextView tv = Ui.text(this, text, 13f, Ui.TEXT_SUB);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView fieldLabel(String text) {
        TextView tv = Ui.text(this, text, 10f, Ui.TEXT_DIM);
        tv.setPadding(0, Ui.dp(this, 2), 0, 0);
        return tv;
    }

    private TextView fieldLabelInline(String text) {
        TextView tv = Ui.text(this, text, 12f, Ui.TEXT_SUB);
        return tv;
    }

    private EditText buildInput(String hint, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Ui.TEXT_DIM);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(13f);
        e.setBackground(Ui.roundRectStroke(Ui.SURFACE_2, 9, Ui.BORDER, 1, this));
        int p = Ui.dp(this, 10);
        e.setPadding(p, Ui.dp(this, 7), p, Ui.dp(this, 7));
        if (password) {
            e.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        return e;
    }

    private EditText addField(LinearLayout parent, String label, String hint,
                              boolean password) {
        parent.addView(fieldLabel(label), Ui.lpMatchWrap(10, this));
        EditText e = buildInput(hint, password);
        parent.addView(e, Ui.lpMatchWrap(3, this));
        return e;
    }

    // ============================================================
    // 表单读写
    // ============================================================

    /** 选择已有配置档；切换前先保存正在编辑的配置，避免丢失改动。 */
    private void showProfilePicker() {
        if (!save(false)) return;
        List<AiConfig.Profile> profiles = AiConfig.listProfiles(this);
        String[] labels = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            AiConfig.Profile p = profiles.get(i);
            String name = emptyTo(p.name, "未命名配置");
            String model = emptyTo(p.model, "未填写模型");
            labels[i] = name + "  ·  " + model;
            if (p.id != null && p.id.equals(current.id)) {
                labels[i] = "✓  " + labels[i];
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("选择 AI 配置档")
                .setItems(labels, (dialog, which) -> {
                    if (which < 0 || which >= profiles.size()) return;
                    current = profiles.get(which).copy();
                    AiConfig.setActive(this, current.id);
                    fillForm(current);
                    toast("已切换到「" + emptyTo(current.name, "未命名配置") + "」");
                })
                .setNeutralButton("复制为新配置", (dialog, which) -> createProfileCopy())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 从当前档复制连接参数，创建独立配置，之后可改名、改 Key 或模型。 */
    private void createProfileCopy() {
        if (!save(false)) return;
        AiConfig.Profile copy = current.copy();
        copy.id = "profile_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String baseName = emptyTo(current.name, "模型配置");
        copy.name = baseName.endsWith("副本") ? baseName + " 2" : baseName + " 副本";

        List<AiConfig.Profile> profiles = AiConfig.listProfiles(this);
        profiles.add(copy);
        AiConfig.saveProfiles(this, profiles);
        AiConfig.setActive(this, copy.id);
        current = copy;
        fillForm(current);
        toast("已创建配置副本，请修改名称、服务地址或模型后保存");
    }

    private void fillForm(AiConfig.Profile p) {
        nameInput.setText(p.name);
        baseUrlInput.setText(p.baseUrl);
        apiKeyInput.setText(p.apiKey);
        modelInput.setText(p.model);

        maxTokensInput.setText(p.maxTokens > 0
                ? String.valueOf(p.maxTokens) : "4096");
        contextInput.setText(p.contextWindow > 0
                ? String.valueOf(p.contextWindow) : "");

        int tempInt = (int) Math.round(p.temperature * 10);
        tempBar.setProgress(Math.max(0, Math.min(20, tempInt)));
        tempValue.setText(String.format(java.util.Locale.US, "%.1f", p.temperature));

        systemInput.setText(AiConfig.getSystemPrompt(this));
    }

    private boolean save(boolean validate) {
        String baseUrl = baseUrlInput.getText().toString().trim();
        String model = modelInput.getText().toString().trim();

        if (validate) {
            if (baseUrl.isEmpty()) {
                toast("BaseURL 不能为空");
                return false;
            }
            if (model.isEmpty()) {
                toast("模型名不能为空，可点「拉取」自动获取");
                return false;
            }
        }

        AiConfig.Profile p = current.copy();
        p.name = emptyTo(nameInput.getText().toString().trim(), "默认");

        // 用户可能只填了域名，补 /v1 更符合多数服务的习惯
        p.baseUrl = normalizeBaseUrl(baseUrl);
        p.apiKey = apiKeyInput.getText().toString().trim();
        p.model = model;
        p.maxTokens = parseInt(maxTokensInput.getText().toString(), 4096, 1, 200000);
        p.contextWindow = parseInt(contextInput.getText().toString(), 0, 0, 4000000);
        p.temperature = tempBar.getProgress() / 10.0;

        List<AiConfig.Profile> all = AiConfig.listProfiles(this);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id != null && all.get(i).id.equals(p.id)) {
                all.set(i, p);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            all.add(p);
        }
        AiConfig.saveProfiles(this, all);
        AiConfig.setActive(this, p.id);
        AiConfig.setSystemPrompt(this, systemInput.getText().toString());

        current = p;
        return true;
    }

    /**
     * 规范化 BaseURL。
     *
     * 用户经常只写 https://api.xxx.com，但多数服务需要 /v1 前缀。
     * 只在「没有任何路径」时才补 /v1，避免破坏已经写全的地址。
     */
    private static String normalizeBaseUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return "";
        }
        // 去掉协议后看还有没有路径
        String rest = s;
        int schemeIdx = rest.indexOf("://");
        if (schemeIdx >= 0) {
            rest = rest.substring(schemeIdx + 3);
        }
        if (rest.indexOf('/') < 0) {
            return s + "/v1";
        }
        return s;
    }

    private static int parseInt(String s, int def, int min, int max) {
        if (s == null || s.trim().isEmpty()) {
            return def;
        }
        try {
            int v = Integer.parseInt(s.trim());
            return Math.max(min, Math.min(max, v));
        } catch (Throwable t) {
            return def;
        }
    }

    private static String emptyTo(String s, String def) {
        return s == null || s.trim().isEmpty() ? def : s;
    }

    private static int presetValue(String label) {
        if ("8K".equals(label)) {
            return 8192;
        } else if ("16K".equals(label)) {
            return 16384;
        } else if ("32K".equals(label)) {
            return 32768;
        } else if ("64K".equals(label)) {
            return 65536;
        } else if ("128K".equals(label)) {
            return 131072;
        }
        return 0;
    }

    // ============================================================
    // 温度滑块
    // ============================================================

    /** SeekBar 监听。具名静态类：避免 D8 4.0.48 的匿名内部类 bug。 */
    static final class TempWatcher
            implements SeekBar.OnSeekBarChangeListener {
        private final AiSettingsActivity host;

        TempWatcher(AiSettingsActivity host) {
            this.host = host;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            host.tempValue.setText(
                    String.format(java.util.Locale.US, "%.1f", progress / 10.0));
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }

    // ============================================================
    // 拉取模型
    // ============================================================

    private void fetchModels() {
        String baseUrl = normalizeBaseUrl(baseUrlInput.getText().toString().trim());
        if (baseUrl.isEmpty()) {
            toast("请先填 BaseURL");
            return;
        }
        String key = apiKeyInput.getText().toString().trim();

        fetchModelsBtn.setEnabled(false);
        fetchModelsBtn.setText("拉取中");
        fetchStatus.setTextColor(Ui.TEXT_DIM);
        fetchStatus.setText("正在请求 " + baseUrl + "/models …");

        new Thread(() -> {
            ModelCatalog.Result r = ModelCatalog.fetch(baseUrl, key);
            ui.post(() -> onModelsFetched(r));
        }, "SuperAoh-Models").start();
    }

    private void onModelsFetched(ModelCatalog.Result r) {
        fetchModelsBtn.setEnabled(true);
        fetchModelsBtn.setText("拉取");

        if (!r.ok()) {
            fetchStatus.setTextColor(Ui.WARN);
            fetchStatus.setText("拉取失败：" + firstLine(r.error)
                    + "\n可以直接手动填模型名。");
            return;
        }

        modelCandidates.clear();
        modelCandidates.addAll(r.models);

        fetchStatus.setTextColor(Ui.OK);
        fetchStatus.setText("✓ 拉到 " + r.models.size() + " 个模型");

        showModelPicker();
    }

    private void showModelPicker() {
        if (modelCandidates.isEmpty()) {
            toast("没有可选模型");
            return;
        }

        final String[] items = modelCandidates.toArray(new String[0]);
        final String selected = modelInput.getText().toString().trim();
        int checked = modelCandidates.indexOf(selected);

        new AlertDialog.Builder(this)
                .setTitle("选择模型（共 " + items.length + " 个）")
                .setSingleChoiceItems(items, checked, new PickerWatcher(this, items))
                .setPositiveButton("确定", (d, w) -> {
                    int idx = PickerWatcher.lastIndex;
                    if (idx >= 0 && idx < items.length) {
                        modelInput.setText(items[idx]);
                        toast("已选择 " + items[idx]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 选择列表监听。具名静态类：绕开 D8 匿名内部类 bug。 */
    static final class PickerWatcher
            implements android.content.DialogInterface.OnClickListener {
        static int lastIndex = -1;
        private final String[] items;

        PickerWatcher(AiSettingsActivity host, String[] items) {
            this.items = items;
            lastIndex = -1;
        }

        @Override
        public void onClick(android.content.DialogInterface dialog, int which) {
            lastIndex = which;
        }
    }

    // ============================================================
    // 测试连接
    // ============================================================

    private void testConnection() {
        if (!save(false)) {
            return;
        }
        AiConfig.Profile p = current;
        if (!p.usable()) {
            toast("BaseURL 或模型名为空");
            return;
        }

        fetchStatus.setTextColor(Ui.TEXT_DIM);
        fetchStatus.setText("测试中…");

        new Thread(() -> {
            String body = buildTestBody(p);
            com.superaoh.core.Http.Response r = com.superaoh.core.Http.postJson(
                    p.chatUrl(), p.apiKey, body, 15000, 30000);
            ui.post(() -> onTested(r));
        }, "SuperAoh-AiTest").start();
    }

    private static String buildTestBody(AiConfig.Profile p) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":").append(quote(p.model));
        sb.append(",\"max_tokens\":16");
        sb.append(",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}");
        return sb.toString();
    }

    private void onTested(com.superaoh.core.Http.Response r) {
        if (r.ok()) {
            boolean looksOk = r.body != null && r.body.contains("choices");
            fetchStatus.setTextColor(looksOk ? Ui.OK : Ui.WARN);
            fetchStatus.setText(looksOk
                    ? "✓ 连接正常（HTTP " + r.code + "）"
                    : "⚠ 有响应但格式异常（HTTP " + r.code + "）");
        } else {
            fetchStatus.setTextColor(Ui.DANGER);
            fetchStatus.setText("✗ " + firstLine(r.describe()));
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    private static String quote(String s) {
        if (s == null) {
            s = "";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        String one = nl < 0 ? s : s.substring(0, nl);
        return one.length() > 160 ? one.substring(0, 160) + "…" : one;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        save(false);
        super.onBackPressed();
    }
}