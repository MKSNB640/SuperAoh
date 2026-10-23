package com.superaoh.core;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * 极简 HTTP 客户端（基于 HttpURLConnection）。
 *
 * 为什么不引入 OkHttp / Gson：
 *   项目是零依赖 + 手工构建链（javac → D8 → aapt2）。
 *   HttpURLConnection 在 Android 上足够稳定，且原生支持 gzip、超时、
 *   chunked 分块读取——这正是 SSE 流式输出需要的能力。
 *
 * 已处理的坑：
 *   · 默认不接受 gzip，但很多中转站强制 gzip，这里显式声明并手动解压
 *   · 错误流必须读，否则拿不到 4xx/5xx 的真实错误信息
 *   · 超时时间分连接与读取两段，避免长回答被误判为超时
 *   · SSE 场景下 readTimeout 必须给足，否则长思考会被截断
 */
public final class Http {

    /** 非流式请求的返回。 */
    public static final class Response {
        public final int code;
        public final String body;
        public final String error;

        Response(int code, String body, String error) {
            this.code = code;
            this.body = body;
            this.error = error;
        }

        public boolean ok() {
            return code >= 200 && code < 300 && error == null;
        }

        public String describe() {
            if (error != null) {
                return "网络错误: " + error;
            }
            return "HTTP " + code + "\n" + (body == null ? "" : body);
        }
    }

    /** SSE 流式回调。所有方法都在调用线程（后台线程）里被触发。 */
    public interface StreamHandler {
        /**
         * 收到一个增量片段。
         *
         * @param contentDelta  正文增量，可能为空字符串
         * @param reasoningDelta 思维链增量（DeepSeek-R1 / o1 等），可能为空
         * @return false 表示要求中止（用户点了停止）
         */
        boolean onDelta(String contentDelta, String reasoningDelta);

        /**
         * 收到一个完整的工具调用（流式结束后拼接好的）。
         *
         * @param id        调用 id
         * @param name      函数名
         * @param arguments 参数 JSON 原文
         */
        void onToolCall(String id, String name, String arguments);

        /**
         * 收到 usage 统计。
         *
         * 服务端通常在流末尾给一次（有的中转站会在中途补一个 usage-only 包），
         * 因此可能被调用多次，调用方应累加或覆盖而不是假设只调一次。
         *
         * @param prompt     输入 token（含历史、系统提示、工具定义）
         * @param completion 输出 token
         * @param cached     输入中命中缓存的部分，无则为 0
         * @param reasoning  输出中用于思考的部分，无则为 0
         */
        default void onUsage(int prompt, int completion, int cached, int reasoning) {}

        /** 正常结束。 */
        void onDone();

        /** 出错。 */
        void onError(String message);
    }

    private Http() {
    }

    // ============================================================
    // 非流式
    // ============================================================

    /** GET 请求，用于拉取模型列表等只读接口。 */
    public static Response get(String url, String apiKey,
                               int connectTimeoutMs, int readTimeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }

            int code = conn.getResponseCode();
            InputStream raw = code >= 200 && code < 300
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            String body = raw == null ? "" : readBody(raw,
                    "gzip".equalsIgnoreCase(conn.getContentEncoding()));
            return new Response(code, body, null);
        } catch (Throwable t) {
            return new Response(-1, null, describeThrowable(t));
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static Response postJson(String url, String apiKey, String jsonBody,
                                    int connectTimeoutMs, int readTimeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = open(url, apiKey, connectTimeoutMs, readTimeoutMs, false);
            writeBody(conn, jsonBody);

            int code = conn.getResponseCode();
            InputStream raw = code >= 200 && code < 300
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            String body = raw == null ? "" : readBody(raw,
                    "gzip".equalsIgnoreCase(conn.getContentEncoding()));
            return new Response(code, body, null);
        } catch (Throwable t) {
            return new Response(-1, null, describeThrowable(t));
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ============================================================
    // 流式（SSE）
    // ============================================================

    /**
     * 发起流式请求并逐块回调。
     *
     * 调用方必须自行保证在后台线程执行——这个方法会阻塞到流结束。
     * 返回值表示是否正常完成（false = 被中止或出错，细节走 handler.onError）。
     */
    public static boolean postJsonStream(String url, String apiKey, String jsonBody,
                                         int connectTimeoutMs, int readTimeoutMs,
                                         StreamHandler handler) {
        HttpURLConnection conn = null;
        try {
            conn = open(url, apiKey, connectTimeoutMs, readTimeoutMs, true);
            writeBody(conn, jsonBody);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                InputStream err = conn.getErrorStream();
                String body = err == null ? "" : readBody(err,
                        "gzip".equalsIgnoreCase(conn.getContentEncoding()));
                handler.onError("HTTP " + code + "\n" + head(body, 1200));
                return false;
            }

            InputStream in = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }

            boolean completed = readSse(in, handler);
            if (completed) {
                handler.onDone();
            }
            return completed;
        } catch (Throwable t) {
            handler.onError(describeThrowable(t));
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 逐行解析 SSE 协议。
     *
     * 协议要点：
     *   · 每行形如 "data: {...}"
     *   · "data: [DONE]" 表示结束
     *   · 空行是事件分隔，可忽略
     *   · 注释行以 ":" 开头，可忽略
     *
     * tool_calls 在流式里是分片下发的，同一 index 的片段需要拼起来，
     * 这里用 accumulator 累积，流结束时一次性交给 handler。
     */
    private static boolean readSse(InputStream in, StreamHandler handler) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, "UTF-8"), 8192);

        // 工具调用累积器：key = index
        java.util.Map<Integer, ToolAcc> toolAccs = new java.util.LinkedHashMap<>();
        StringBuilder reasoning = new StringBuilder();

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || line.startsWith(":")) {
                continue; // 事件分隔或注释
            }
            if (!line.startsWith("data:")) {
                // 有些 OpenAI 兼容网关会忽略 stream=true，直接返回单个 JSON。
                // 不能静默跳过，否则 UI 看起来像“等到最后才突然出结果”或空白。
                if (emitJsonCompletion(line, handler, toolAccs)) {
                    emitToolCalls(toolAccs, handler);
                    return true;
                }
                continue; // 忽略 event: / id: 等非数据行
            }
            String payload = line.substring(5).trim();
            if (payload.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(payload)) {
                break;
            }

            java.util.Map<String, Object> chunk;
            try {
                chunk = Json.parseObject(payload);
            } catch (Throwable t) {
                continue; // 单条坏数据不致命，跳过
            }
            if (chunk == null) {
                continue;
            }

            Object errObj = chunk.get("error");
            if (errObj != null) {
                handler.onError("接口返回错误:\n" + Json.write(errObj));
                return false;
            }

            Object choiceObj = Json.path(chunk, "choices", "0");
            if (!(choiceObj instanceof java.util.Map)) {
                // usage-only 包：流末尾服务端会单独发一条 {"usage":{...}}，
                // 它没有 choices。原来这里直接 continue 就把它丢了，
                // 结果 UI 永远拿不到真实 token 数。
                emitUsage(chunk, handler);
                continue;
            }
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> choice = (java.util.Map<String, Object>) choiceObj;

            Object deltaObj = choice.get("delta");
            if (deltaObj instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> delta = (java.util.Map<String, Object>) deltaObj;

                String c = Json.str(delta, "content");
                String r = Json.str(delta, "reasoning_content");
                if (r == null) {
                    r = Json.str(delta, "reasoning");
                }
                if (r != null && !r.isEmpty()) {
                    reasoning.append(r);
                }

                // 累积 tool_calls 分片
                java.util.List<Object> tcArr = Json.arr(delta, "tool_calls");
                if (tcArr != null) {
                    for (Object o : tcArr) {
                        accumulateToolCall(o, toolAccs);
                    }
                }

                boolean hasContent = (c != null && !c.isEmpty())
                        || (r != null && !r.isEmpty());
                if (hasContent) {
                    if (!handler.onDelta(c == null ? "" : c, r == null ? "" : r)) {
                        return false; // 用户中止
                    }
                }
            }
        }

        // 流结束：把拼装好的工具调用一次性交出去
        emitToolCalls(toolAccs, handler);
        return true;
    }

    private static void emitToolCalls(java.util.Map<Integer, ToolAcc> toolAccs,
                                      StreamHandler handler) {
        for (ToolAcc acc : toolAccs.values()) {
            if (acc.name != null) {
                handler.onToolCall(acc.id, acc.name, acc.args.toString());
            }
        }
    }

    /**
     * 兼容忽略 stream=true 的网关返回普通 OpenAI JSON 的情况。
     * 这种网关本身无法实时流式，但至少能正常显示最终正文/思考/工具调用，
     * 而不是被 SSE 解析器静默丢弃。
     */
    @SuppressWarnings("unchecked")
    private static boolean emitJsonCompletion(String line, StreamHandler handler,
                                              java.util.Map<Integer, ToolAcc> toolAccs) {
        String trimmed = line == null ? "" : line.trim();
        if (!trimmed.startsWith("{")) return false;
        java.util.Map<String, Object> chunk;
        try {
            chunk = Json.parseObject(trimmed);
        } catch (Throwable ignored) {
            return false;
        }
        if (chunk == null) return false;
        Object error = chunk.get("error");
        if (error != null) {
            handler.onError("接口返回错误:\\n" + Json.write(error));
            return true;
        }
        emitUsage(chunk, handler);
        Object choicesObj = chunk.get("choices");
        if (!(choicesObj instanceof java.util.List) || ((java.util.List<?>) choicesObj).isEmpty()) {
            return chunk.containsKey("usage");
        }
        Object first = ((java.util.List<?>) choicesObj).get(0);
        if (!(first instanceof java.util.Map)) return false;
        java.util.Map<String, Object> choice = (java.util.Map<String, Object>) first;
        Object messageObj = choice.get("message");
        if (!(messageObj instanceof java.util.Map)) messageObj = choice.get("delta");
        if (messageObj instanceof java.util.Map) {
            java.util.Map<String, Object> message = (java.util.Map<String, Object>) messageObj;
            String content = Json.str(message, "content");
            String reasoning = Json.str(message, "reasoning_content");
            if (reasoning == null) reasoning = Json.str(message, "reasoning");
            if ((content != null && !content.isEmpty()) || (reasoning != null && !reasoning.isEmpty())) {
                handler.onDelta(content == null ? "" : content, reasoning == null ? "" : reasoning);
            }
            java.util.List<Object> calls = Json.arr(message, "tool_calls");
            if (calls != null) {
                for (Object call : calls) accumulateToolCall(call, toolAccs);
            }
        }
        return true;
    }

    /**
     * 从任意 chunk 里解析 usage 并回调。
     *
     * 兼容三种真实世界里都见过的写法：
     *   usage.prompt_tokens / completion_tokens            —— 标准
     *   usage.prompt_tokens_details.cached_tokens          —— OpenAI/DeepSeek 缓存命中
     *   usage.prompt_cache_hit_tokens                      —— 部分中转站
     *   usage.completion_tokens_details.reasoning_tokens    —— 推理消耗
     *
     * 缺字段一律当 0，不猜。
     */
    @SuppressWarnings("unchecked")
    private static void emitUsage(java.util.Map<String, Object> chunk,
                                  StreamHandler handler) {
        Object u = chunk.get("usage");
        if (!(u instanceof java.util.Map)) {
            return;
        }
        java.util.Map<String, Object> usage = (java.util.Map<String, Object>) u;
        int prompt = intOf(usage.get("prompt_tokens"), 0);
        int completion = intOf(usage.get("completion_tokens"), 0);

        int cached = 0;
        Object pd = usage.get("prompt_tokens_details");
        if (pd instanceof java.util.Map) {
            cached = intOf(((java.util.Map<String, Object>) pd).get("cached_tokens"), 0);
        }
        if (cached <= 0) {
            cached = intOf(usage.get("cached_tokens"), 0);
        }
        if (cached <= 0) {
            cached = intOf(usage.get("prompt_cache_hit_tokens"), 0);
        }

        int reasoning = 0;
        Object cd = usage.get("completion_tokens_details");
        if (cd instanceof java.util.Map) {
            reasoning = intOf(((java.util.Map<String, Object>) cd).get("reasoning_tokens"), 0);
        }

        if (prompt <= 0 && completion <= 0) {
            return;
        }
        handler.onUsage(prompt, completion, cached, reasoning);
    }

    /** 把 JSON 里的数字宽松地转成 int。 */
    private static int intOf(Object v, int fallback) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return (int) Double.parseDouble((String) v);
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    /** 累积一个 tool_calls 分片。 */
    @SuppressWarnings("unchecked")
    private static void accumulateToolCall(Object o,
                                           java.util.Map<Integer, ToolAcc> accs) {
        if (!(o instanceof java.util.Map)) {
            return;
        }
        java.util.Map<String, Object> tc = (java.util.Map<String, Object>) o;

        int index = 0;
        Object idxObj = tc.get("index");
        if (idxObj instanceof Number) {
            index = ((Number) idxObj).intValue();
        }

        ToolAcc acc = accs.get(index);
        if (acc == null) {
            acc = new ToolAcc();
            accs.put(index, acc);
        }

        String id = Json.str(tc, "id");
        if (id != null && !id.isEmpty()) {
            acc.id = id;
        }

        Object fnObj = tc.get("function");
        if (fnObj instanceof java.util.Map) {
            java.util.Map<String, Object> fn = (java.util.Map<String, Object>) fnObj;
            String name = Json.str(fn, "name");
            if (name != null && !name.isEmpty()) {
                acc.name = name;
            }
            String args = Json.str(fn, "arguments");
            if (args != null && !args.isEmpty()) {
                acc.args.append(args);
            }
        }
    }

    static final class ToolAcc {
        String id;
        String name;
        final StringBuilder args = new StringBuilder();
    }

    // ============================================================
    // 底层
    // ============================================================

    private static HttpURLConnection open(String url, String apiKey,
                                          int connectTimeoutMs, int readTimeoutMs,
                                          boolean stream) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
        // SSE 不能接受 gzip：一些 CDN/中转站会先把压缩响应攒到完整
        // 才交给客户端，表现就是 UI 直到最后才收到一个大块。普通请求
        // 仍然允许 gzip，流式请求强制 identity 保证每个事件及时到达。
        conn.setRequestProperty("Accept-Encoding", stream ? "identity" : "gzip");
        // 部分中转站会检查这些头来决定是否走流式/关闭代理缓冲。
        conn.setRequestProperty("Cache-Control", "no-cache");
        if (stream) {
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setRequestProperty("X-Accel-Buffering", "no");
        }
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        }
        return conn;
    }

    private static void writeBody(HttpURLConnection conn, String jsonBody) throws Exception {
        byte[] payload = jsonBody.getBytes("UTF-8");
        conn.setFixedLengthStreamingMode(payload.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
            os.flush();
        }
    }

    private static String readBody(InputStream in, boolean gzip) throws Exception {
        InputStream stream = gzip ? new GZIPInputStream(in) : in;
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, "UTF-8"), 8192);
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = reader.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        reader.close();
        return sb.toString();
    }

    private static String describeThrowable(Throwable t) {
        String msg = t.getClass().getSimpleName();
        if (t.getMessage() != null) {
            msg += ": " + t.getMessage();
        }
        return msg;
    }

    private static String head(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
