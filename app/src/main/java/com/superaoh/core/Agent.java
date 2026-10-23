package com.superaoh.core;

import android.content.Context;
import android.util.Log;

import com.superaoh.core.tool.ToolProvider;
import com.superaoh.core.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI Agent 主循环（OpenAI 兼容 tool calling 闭环，流式）。
 *
 * 一次完整回合：
 *   1. 组装 messages（system + 历史 + 用户输入）
 *   2. POST /chat/completions，带 tools 定义，stream=true
 *   3. 边收边把增量推给 UI（打字机效果）
 *   4. 流结束后若拿到 tool_calls：
 *        逐个执行本地工具 -> 结果作为 role=tool 消息塞回 messages
 *        -> 回到第 2 步（最多 MAX_TOOL_ROUNDS 轮）
 *      若拿到正文：
 *        结束，把完整回答交给 UI
 *
 * 为什么要循环而不是单次：
 *   模型经常需要「先看目录再决定读哪个文件」，单次调用会让它凭幻觉回答。
 *
 * 中止：
 *   cancel() 是线程安全的。中止标志会被 SSE 循环检查，
 *   下一次 onDelta 返回 false 即可立刻断开连接，不用等整个流跑完。
 *
 * 线程模型：run() 是阻塞的，必须在后台线程调用。
 */
public final class Agent {

    private static final String TAG = "SuperAoh/Agent";

    /** 单次对话最多几轮工具调用，防止死循环烧 token。 */
    private static final int MAX_TOOL_ROUNDS = 16;

    private static final int CONNECT_TIMEOUT = 20000;
    /** 流式场景需要更长：模型思考时可能长时间没有数据。 */
    private static final int READ_TIMEOUT = 180000;

    /** 未设置上下文窗口时，最多保留多少条历史消息。 */
    private static final int MAX_KEEP_MESSAGES = 60;

    // ---------------- 回调 ----------------

    public interface Listener {
        /** 新的一轮请求开始。 */
        void onThinking(int round);

        /** 收到正文增量（打字机）。 */
        void onContentDelta(String delta);

        /** 收到思维链增量（推理模型专用，可能一直不触发）。 */
        void onReasoningDelta(String delta);

        /** 某个工具已执行完毕。 */
        void onToolCall(String toolName, String argsSummary, boolean ok,
                        String resultPreview);

        /**
         * 服务端回报的 token 用量。
         *
         * 只在流里真的带了 usage 时才回调，缺失的字段一律给 0——
         * 绝不用字符数估算，否则界面上会显示一个骗人的数字。
         *
         * @param promptTokens     输入 token
         * @param completionTokens 输出 token
         * @param cachedTokens     命中缓存的输入 token（0 表示不支持或未命中）
         * @param reasoningTokens  推理 token（非推理模型为 0）
         */
        default void onUsage(int promptTokens, int completionTokens,
                             int cachedTokens, int reasoningTokens) {
        }

        /** 收到最终回答。 */
        void onAnswer(String answer);

        /** 出错。 */
        void onError(String message);

        /** 被用户中止。 */
        void onCancelled();

        /** 每轮结束，供 UI 刷新消息列表。 */
        void onMessagesChanged(List<Message> messages);
    }

    // ---------------- 消息模型 ----------------

    /** 一条对话消息。role: system / user / assistant / tool */
    public static final class Message {
        public String role;
        public String content;
        /** 仅 assistant 带工具调用时有值 */
        public List<ToolCall> toolCalls;
        /** 仅 role=tool 时有值 */
        public String toolCallId;
        public String toolName;
        /**
         * 用户消息附带的图片，存的是 data URL（data:image/png;base64,...）。
         * 只有支持视觉的模型才会用到；不支持的模型会自动降级为纯文本。
         */
        public List<String> imageDataUrls;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public static Message user(String text) {
            return new Message("user", text);
        }

        public boolean hasImages() {
            return imageDataUrls != null && !imageDataUrls.isEmpty();
        }
    }

    /** 模型请求的一次工具调用。 */
    public static final class ToolCall {
        public String id;
        public String name;
        public String argumentsRaw;
    }

    // ---------------- 状态 ----------------

    private final Context appCtx;
    private final Workspace workspace;
    private final AiConfig.Profile profile;
    private final String systemPrompt;
    private final ToolProvider tools;
    private final Listener listener;

    private final List<Message> messages = new ArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 图片精度：low 省 token，high 更清晰。默认 low。 */
    private String imageDetail = "low";
    /**
     * 思考等级（reasoning_effort）。null 表示不指定。
     * 取值由服务端决定，常见为 low / medium / high / xhigh / max。
     */
    private String reasoningEffort = null;

    public Agent(Context ctx, Workspace ws, AiConfig.Profile profile,
                 String systemPrompt, ToolProvider tools, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.workspace = ws;
        this.profile = profile;
        this.systemPrompt = systemPrompt;
        this.tools = tools;
        this.listener = listener;
        if (tools instanceof AgentTools && ws != null) {
            ((AgentTools) tools).setPatchesDir(ws.patchesDir(ctx));
        }
    }

    public List<Message> messages() {
        return messages;
    }

    public void clearHistory() {
        messages.clear();
    }

    /** 直接注入一条消息（例如从历史恢复）。 */
    public void addMessage(Message m) {
        messages.add(m);
    }

    /** 请求中止。线程安全，可在任意线程调用。 */
    public void cancel() {
        cancelled.set(true);
    }

    /** 设置图片精度：low / high。 */
    public void setImageDetail(String detail) {
        if ("high".equalsIgnoreCase(detail)) {
            this.imageDetail = "high";
        } else {
            this.imageDetail = "low";
        }
    }

    /**
     * 设置思考等级（reasoning_effort）。传 null 或空串表示不指定。
     *
     * 不同服务商支持的档位不同：
     *   OpenAI o 系列      low / medium / high
     *   GLM-5.2            minimal / low / medium / high / xhigh / max
     *   GLM-5.3            low / high / max
     * 传了不支持的值通常会被服务端忽略或报错，这里不做白名单校验。
     */
    public void setReasoningEffort(String effort) {
        if (effort == null || effort.trim().isEmpty()) {
            this.reasoningEffort = null;
        } else {
            this.reasoningEffort = effort.trim();
        }
    }

    /** 当前思考等级，null 表示未指定。 */
    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    // ---------------- 主循环 ----------------

    /** 发送一条用户消息并跑完整个工具闭环。阻塞，必须在后台线程调用。 */
    public void run(String userInput) {
        run(userInput, null);
    }

    /**
     * 发送一条带图片的用户消息。
     *
     * @param imageDataUrls data URL 列表（data:image/png;base64,...）
     */
    public void run(String userInput, List<String> imageDataUrls) {
        cancelled.set(false);

        if (profile == null || !profile.usable()) {
            listener.onError("尚未配置 AI 服务。请先到「AI 设置」里填写 BaseUrl 与模型名。");
            return;
        }

        Message um = new Message("user", userInput);
        if (imageDataUrls != null && !imageDataUrls.isEmpty()) {
            um.imageDataUrls = new ArrayList<>(imageDataUrls);
        }
        messages.add(um);
        listener.onMessagesChanged(messages);

        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            if (cancelled.get()) {
                listener.onCancelled();
                return;
            }
            listener.onThinking(round);

            RoundResult rr = doStreamRound();
            if (rr == null) {
                return; // 出错或已中止，内部已回调
            }

            // ---- 情况 A：模型要调工具 ----
            if (!rr.toolCalls.isEmpty()) {
                Message assistant = new Message("assistant", rr.content);
                assistant.toolCalls = rr.toolCalls;
                messages.add(assistant);

                for (ToolCall call : rr.toolCalls) {
                    if (cancelled.get()) {
                        listener.onCancelled();
                        return;
                    }
                    Map<String, Object> args;
                    try {
                        args = Json.parseObject(call.argumentsRaw);
                    } catch (Throwable t) {
                        args = new LinkedHashMap<>();
                    }
                    ToolResult result = tools.execute(
                            call.name, args, workspace, appCtx);
                    Log.i(TAG, "tool " + call.name + " ok=" + result.ok);

                    Message toolMsg = new Message("tool", result.text);
                    toolMsg.toolCallId = call.id;
                    toolMsg.toolName = call.name;
                    messages.add(toolMsg);

                    listener.onToolCall(call.name, summarizeArgs(args), result.ok,
                            head(result.text, 600));
                }
                listener.onMessagesChanged(messages);
                continue;
            }

            // ---- 情况 B：拿到最终回答 ----
            String answer = rr.content == null ? "" : rr.content;
            if (answer.trim().isEmpty()) {
                listener.onError("模型返回了空内容。");
                return;
            }
            messages.add(new Message("assistant", answer));
            listener.onMessagesChanged(messages);
            listener.onAnswer(answer);
            return;
        }

        listener.onError("工具调用超过 " + MAX_TOOL_ROUNDS
                + " 轮仍未收敛，已中止。请把问题拆小一点。");
    }

    /** 一轮流式请求的结果。 */
    static final class RoundResult {
        String content = "";
        final List<ToolCall> toolCalls = new ArrayList<>();
        /** 本次请求（单次 LLM 调用）的用量；服务端没给就保持 0。 */
        int promptTokens;
        int completionTokens;
        int cachedTokens;
        int reasoningTokens;
        boolean hasUsage;
    }

    /**
     * 执行一轮流式请求。
     *
     * @return null 表示已中止或出错（错误/中止回调已触发）；否则返回本轮结果
     */
    private RoundResult doStreamRound() {
        final RoundResult rr = new RoundResult();
        final StringBuilder contentBuf = new StringBuilder();

        final boolean[] errored = {false};
        final boolean[] aborted = {false};

        Http.StreamHandler handler = new RoundStreamHandler(
                rr, contentBuf, listener, cancelled, errored, aborted);

        Http.postJsonStream(profile.chatUrl(), profile.apiKey,
                buildRequestBody(), CONNECT_TIMEOUT, READ_TIMEOUT, handler);

        // 服务端给了用量就报给上层；没给（很多自建网关不返回）就静默跳过，
        // 让 UI 自己去决定显示什么，绝不在这里编数字。
        if (rr.hasUsage) {
            listener.onUsage(rr.promptTokens, rr.completionTokens,
                    rr.cachedTokens, rr.reasoningTokens);
        }

        if (errored[0] || aborted[0] || cancelled.get()) {
            if (cancelled.get() || aborted[0]) {
                listener.onCancelled();
            }
            return null;
        }
        rr.content = contentBuf.toString();
        return rr;
    }

    /**
     * 单轮流式的回调实现。
     *
     * 必须是具名静态嵌套类：D8 4.0.48 处理「实现接口的匿名内部类」时
     * 会抛 NullPointerException（Cannot invoke "String.length()"），
     * 直接让整个构建失败。这是本项目代码风格的硬约束。
     */
    static final class RoundStreamHandler implements Http.StreamHandler {
        private final RoundResult rr;
        private final StringBuilder contentBuf;
        private final Listener listener;
        private final AtomicBoolean cancelled;
        private final boolean[] errored;
        private final boolean[] aborted;

        RoundStreamHandler(RoundResult rr, StringBuilder contentBuf,
                           Listener listener, AtomicBoolean cancelled,
                           boolean[] errored, boolean[] aborted) {
            this.rr = rr;
            this.contentBuf = contentBuf;
            this.listener = listener;
            this.cancelled = cancelled;
            this.errored = errored;
            this.aborted = aborted;
        }

        @Override
        public boolean onDelta(String contentDelta, String reasoningDelta) {
            if (cancelled.get()) {
                aborted[0] = true;
                return false;
            }
            if (contentDelta != null && !contentDelta.isEmpty()) {
                contentBuf.append(contentDelta);
                listener.onContentDelta(contentDelta);
            }
            if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
                listener.onReasoningDelta(reasoningDelta);
            }
            return true;
        }
        @Override
        public void onToolCall(String id, String name, String arguments) {
            ToolCall call = new ToolCall();
            call.id = id;
            call.name = name;
            call.argumentsRaw = (arguments == null || arguments.trim().isEmpty())
                    ? "{}" : arguments;
            rr.toolCalls.add(call);
        }

        @Override
        public void onUsage(int promptTokens, int completionTokens,
                            int cachedTokens, int reasoningTokens) {
            // 同一轮里可能来多个 usage 包（多个 choice、或末尾补发一份），
            // 取「更完整的那份」：新的非零值覆盖，0 值不覆盖已有值。
            if (promptTokens > 0) rr.promptTokens = promptTokens;
            if (completionTokens > 0) rr.completionTokens = completionTokens;
            if (cachedTokens > 0) rr.cachedTokens = cachedTokens;
            if (reasoningTokens > 0) rr.reasoningTokens = reasoningTokens;
            rr.hasUsage = true;
        }


        @Override
        public void onDone() {
            // 正常结束，无需额外动作
        }

        @Override
        public void onError(String message) {
            if (cancelled.get()) {
                aborted[0] = true;
                return;
            }
            errored[0] = true;
            listener.onError(message);
        }
    }

    // ---------------- 上下文窗口 ----------------

    /**
     * 按配置的上下文窗口裁剪历史。
     *
     * 策略（从粗到细）：
     *   1. 若 contextWindow <= 0（未设置），不做 token 裁剪，
     *      但仍限制最多 MAX_KEEP_MESSAGES 条，避免无限增长。
     *   2. 预留 maxTokens 给回复、预留 1500 给 system+工具定义。
     *   3. 从最新消息往前累加，直到超出预算为止。
     *   4. 裁剪时保证「tool 消息」不会与它的 assistant.tool_calls 分离——
     *      否则 API 会报 "tool message without preceding tool_calls"。
     *
     * 估算方式：中文按 1 字 ≈ 0.7 token、英文按 4 字符 ≈ 1 token，
     * 这里统一用「字符数 / 1.7」做保守近似，宁可少发也不超限。
     */
    private List<Message> trimmedHistory() {
        int window = profile == null ? 0 : profile.contextWindow;
        int reserve = (profile == null ? 4096 : profile.maxTokens) + 1500;

        if (window <= 0) {
            // 未设置窗口：只做条数保护
            int from = Math.max(0, messages.size() - MAX_KEEP_MESSAGES);
            return new ArrayList<>(messages.subList(from, messages.size()));
        }

        int budget = window - reserve;
        if (budget < 1000) {
            // 窗口设得太小，至少留 1000 token 给历史，否则对话无法进行
            budget = 1000;
        }

        // 交给 Kotlin 侧的精确预算器：按字符类别计价，
        // 并自动保证 tool 消息不被与其 assistant(tool_calls) 拆散。
        int start = com.superaoh.ai.ContextBudget.selectFrom(messages, budget);
        return new ArrayList<>(messages.subList(start, messages.size()));
    }

    /** 当前历史占用的 token 估算（用于 UI 展示上下文水位）。 */
    public int contextTokensUsed() {
        return com.superaoh.ai.ContextBudget.totalTokens(messages);
    }

    /** 供 UI 显示的上下文占用描述，例如 "12.3K / 128K（9%）"。 */
    public String contextUsageText() {
        int window = profile == null ? 0 : profile.contextWindow;
        return com.superaoh.ai.ContextBudget.describeUsage(contextTokensUsed(), window);
    }

    /** 粗略估算一条消息消耗多少 token。 */
    private static int estimateTokens(Message m) {
        int chars = 0;
        if (m.content != null) {
            chars += m.content.length();
        }
        if (m.toolCalls != null) {
            for (ToolCall c : m.toolCalls) {
                if (c.name != null) {
                    chars += c.name.length();
                }
                if (c.argumentsRaw != null) {
                    chars += c.argumentsRaw.length();
                }
            }
        }
        // 每条消息的固定开销
        return (int) Math.ceil(chars / 1.7) + 4;
    }

    /** 当前历史估算总量，供 UI 显示。 */
    public int estimateContextTokens() {
        int total = 0;
        for (Message m : messages) {
            total += estimateTokens(m);
        }
        if (systemPrompt != null) {
            total += (int) Math.ceil(systemPrompt.length() / 1.7);
        }
        return total;
    }

    // ---------------- 请求组装 ----------------

    private String buildRequestBody() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", profile.model);
        req.put("temperature", profile.temperature);
        req.put("max_tokens", profile.maxTokens);
        req.put("stream", Boolean.TRUE);

        List<Object> msgs = new ArrayList<>();

        // system
        Map<String, Object> sys = new LinkedHashMap<>();
        sys.put("role", "system");
        sys.put("content", buildSystemContent());
        msgs.add(sys);

        /** 历史 */
        for (Message m : trimmedHistory()) {
            msgs.add(encodeMessage(m));
        }
        req.put("messages", msgs);
        // 思考等级（reasoning_effort）。部分模型支持，如 o 系列 / GLM / DeepSeek-R1 等。
        // 为空表示不指定，交给服务端默认。
        if (reasoningEffort != null && !reasoningEffort.trim().isEmpty()) {
            req.put(profile.reasoningEffortField, reasoningEffort.trim());
        }

        // 部分服务要求显式声明（如某些 vLLM 网关），不支持的会忽略
        if (profile.topP > 0) {
            req.put("top_p", profile.topP);
        }
        if (tools != null) {
            List<Object> toolDefs = tools.toOpenAiTools();
            if (!toolDefs.isEmpty()) {
                req.put("tools", toolDefs);
                req.put("tool_choice", "auto");
            }
        }
        return Json.write(req);
    }

    /** 系统提示词 + 当前工作区摘要，让模型一开始就知道自己在哪。 */
    private String buildSystemContent() {
        StringBuilder sb = new StringBuilder();
        sb.append(systemPrompt == null ? "" : systemPrompt);

        if (workspace != null) {
            sb.append("\n\n【当前工作区】\n");
            sb.append("名称: ").append(workspace.name).append('\n');
            sb.append("ID: ").append(workspace.id).append('\n');
            sb.append("游戏包大小: ").append(ApkInspector.formatSize(workspace.apkSize)).append('\n');
            sb.append("编辑器: ").append(workspace.editorPresent ? "完整" : "缺失").append('\n');
            sb.append("可用工具: list_workspace / list_dir / read_text / write_text / "
                    + "delete_path / apk_info / apk_list_assets / apk_extract / "
                    + "search_in_apk / dex_list / runtime_status\n");
        } else {
            sb.append("\n\n【当前没有工作区】\n");
            sb.append("用户还没导入游戏 APK，涉及工作区的工具会失败。\n");
        }
        return sb.toString();
    }

    private Map<String, Object> encodeMessage(Message m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("role", m.role);
        if ("tool".equals(m.role)) {
            o.put("tool_call_id", m.toolCallId);
            o.put("content", m.content == null ? "" : m.content);
            return o;
        }
        if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
            o.put("content", m.content == null ? "" : m.content);
            List<Object> calls = new ArrayList<>();
            for (ToolCall c : m.toolCalls) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("id", c.id);
                one.put("type", "function");
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", c.name);
                fn.put("arguments", c.argumentsRaw);
                one.put("function", fn);
                calls.add(one);
            }
            o.put("tool_calls", calls);
            return o;
        }

        // ---- 多模态：user 消息带图片时改用 content 数组 ----
        if (m.hasImages() && "user".equals(m.role)) {
            List<Object> parts = new ArrayList<>();

            String text = m.content == null ? "" : m.content;
            if (!text.trim().isEmpty()) {
                Map<String, Object> tp = new LinkedHashMap<>();
                tp.put("type", "text");
                tp.put("text", text);
                parts.add(tp);
            }
            for (String url : m.imageDataUrls) {
                if (url == null || url.trim().isEmpty()) {
                    continue;
                }
                Map<String, Object> ip = new LinkedHashMap<>();
                ip.put("type", "image_url");
                Map<String, Object> inner = new LinkedHashMap<>();
                inner.put("url", url);
                // low 会显著省 token；用户在设置里开高精度时会改成 high
                inner.put("detail", imageDetail);
                ip.put("image_url", inner);
                parts.add(ip);
            }
            o.put("content", parts);
            return o;
        }

        o.put("content", m.content == null ? "" : m.content);
        return o;
    }

    // ---------------- 小工具 ----------------

    private static String summarizeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (n++ > 0) {
                sb.append(", ");
            }
            String v = String.valueOf(e.getValue());
            sb.append(e.getKey()).append('=').append(head(v, 70));
            if (n >= 4) {
                sb.append("...");
                break;
            }
        }
        return sb.toString();
    }

    private static String head(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}