package com.superaoh.core.mcp;

import android.util.Log;

import com.superaoh.core.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ---------------------------------------------------------------------------
// MCP 客户端（Model Context Protocol，HTTP JSON-RPC 传输）
//
// MCP 是 Anthropic 提出的工具协议，本质是 JSON-RPC 2.0：
//   初始化：initialize → notifications/initialized
//   发现：  tools/list  → 拿到 name/description/inputSchema
//   调用：  tools/call  → {"content":[{"type":"text","text":"..."}]}
//
// 为什么只做 HTTP 传输而不用 stdio：
//   stdio 传输要客户端自己 fork 子进程跑 MCP server，在 Android 上要么
//   需要 proot 沙箱（重），要么要打包 node/python 运行时（更重）。
//   而实际生态里绝大多数 MCP server 都支持 HTTP/SSE 形态 —— 远程的、
//   docker 化的、自建的，都能直接连。这是投入产出比最高的选择。
//
// 关于协议版本：写成单版本 + 宽容解析。服务器返回什么 version 都接受，
// 因为 discover/call 的报文结构在各版本间是稳定的。
// ---------------------------------------------------------------------------

public final class McpClient {

    private static final String TAG = "McpClient";

    /** 我们声明的协议版本。服务器若不认同会返回它自己的，接受即可。 */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final String name;
    private final String url;
    private final String apiKey;
    private final Map<String, String> extraHeaders;

    /** initialize 拿到的服务器信息，连上之后才有值。 */
    private volatile String serverName;
    private volatile String serverVersion;
    private volatile String negotiatedVersion;
    private volatile boolean initialized;

    /** 会话 ID，部分服务器要求后续请求带上（Streamable HTTP 传输）。 */
    private volatile String sessionId;

    /** 递增的请求 ID。JSON-RPC 要求 id 唯一。 */
    private int nextId = 1;

    public McpClient(String name, String url, String apiKey, Map<String, String> extraHeaders) {
        this.name = name;
        this.url = url;
        this.apiKey = apiKey;
        this.extraHeaders = extraHeaders == null
                ? Collections.<String, String>emptyMap()
                : new LinkedHashMap<>(extraHeaders);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 建立会话：initialize + notifications/initialized。
     *
     * @throws McpException 握手失败
     */
    public void connect() throws McpException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);

        Map<String, Object> clientInfo = new LinkedHashMap<>();
        clientInfo.put("name", "SuperAoh");
        clientInfo.put("version", "1.0");
        params.put("clientInfo", clientInfo);

        // capabilities 声明我们只用到 tools。声明多了服务器会尝试推送
        // resource/prompt 相关通知，而我们没有对应处理逻辑。
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("tools", new LinkedHashMap<String, Object>());
        params.put("capabilities", caps);

        Map<String, Object> result = request("initialize", params);

        Map<String, Object> info = Json.obj(result, "serverInfo");
        serverName = Json.str(info, "name");
        serverVersion = Json.str(info, "version");
        String v = Json.str(result, "protocolVersion");
        negotiatedVersion = (v == null || v.isEmpty()) ? PROTOCOL_VERSION : v;

        // 通知不需要响应，失败也不阻断（有些实现会忽略它）
        notify("notifications/initialized", new LinkedHashMap<String, Object>());

        initialized = true;
        Log.i(TAG, "connected " + name + " server=" + serverName + " v=" + negotiatedVersion);
    }

    public boolean isConnected() {
        return initialized;
    }

    /** MCP 连接不会长期挂着，断开只是标记状态，真正释放靠服务器侧超时。 */
    public void disconnect() {
        initialized = false;
    }

    // ------------------------------------------------------------------
    // 工具发现
    // ------------------------------------------------------------------

    /** 服务器上的一个工具。 */
    public static final class RemoteTool {
        public final String name;
        public final String description;
        public final Map<String, Object> inputSchema;

        RemoteTool(String name, String description, Map<String, Object> inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }
    }

    /**
     * 拉取工具列表。
     *
     * 处理了分页：服务器可能只返回一页（nextCursor 非空），
     * 循环取到没有 cursor 为止。漏页会导致部分工具在界面上消失。
     */
    public List<RemoteTool> listTools() throws McpException {
        List<RemoteTool> out = new ArrayList<>();
        String cursor = null;
        // 防御性上限：正常服务器不会超过几十页，卡住说明服务器有问题
        for (int page = 0; page < 50; page++) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (cursor != null) params.put("cursor", cursor);

            Map<String, Object> result = request("tools/list", params);
            List<Object> items = Json.arr(result, "tools");
            if (items != null) {
                for (Object o : items) {
                    if (!(o instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    String n = Json.str(m, "name");
                    if (n == null || n.isEmpty()) continue;
                    String d = Json.str(m, "description");
                    Map<String, Object> schema = Json.obj(m, "inputSchema");
                    out.add(new RemoteTool(n, d == null ? "" : d, schema));
                }
            }

            String next = Json.str(result, "nextCursor");
            if (next == null || next.isEmpty()) break;
            cursor = next;
        }
        Log.i(TAG, name + " exposes " + out.size() + " tools");
        return out;
    }

    // ------------------------------------------------------------------
    // 工具调用
    // ------------------------------------------------------------------

    /**
     * 调用一个工具。
     *
     * MCP 的返回是 content 数组，元素可能是 text / image / resource。
     * 这里把 text 拼起来返回给模型；非文本内容用占位说明，
     * 因为模型上下文塞不进二进制，而静默丢弃会让它误判调用失败。
     */
    public String callTool(String toolName, Map<String, Object> args) throws McpException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", args == null ? new LinkedHashMap<String, Object>() : args);

        Map<String, Object> result = request("tools/call", params);

        // isError 为 true 表示工具执行失败，但协议层是成功的
        boolean isError = Boolean.TRUE.equals(result.get("isError"));

        StringBuilder sb = new StringBuilder();
        List<Object> content = Json.arr(result, "content");
        if (content != null) {
            for (Object o : content) {
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> c = (Map<String, Object>) o;
                String type = Json.str(c, "type");
                if ("text".equals(type)) {
                    String t = Json.str(c, "text");
                    if (t != null) sb.append(t);
                } else if ("image".equals(type)) {
                    String mime = Json.str(c, "mimeType");
                    sb.append("[图片内容 ").append(mime == null ? "" : mime).append("]");
                } else if ("resource".equals(type)) {
                    Map<String, Object> res = Json.obj(c, "resource");
                    String uri = Json.str(res, "uri");
                    sb.append("[资源 ").append(uri == null ? "" : uri).append("]");
                } else if (type != null) {
                    sb.append("[").append(type).append("]");
                }
                sb.append('\n');
            }
        }

        if (sb.length() == 0) {
            // 有些服务器把结果放在 structuredContent 里
            Map<String, Object> sc = Json.obj(result, "structuredContent");
            if (sc != null) sb.append(Json.write(sc));
        }
        if (sb.length() == 0) sb.append("(工具无输出)");

        String text = sb.toString().trim();
        if (isError) {
            throw new McpException("工具返回错误: " + text);
        }
        return text;
    }

    // ------------------------------------------------------------------
    // JSON-RPC 底层
    // ------------------------------------------------------------------

    /** 发一次请求，返回 result 对象。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> request(String method, Map<String, Object> params)
            throws McpException {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        int id;
        synchronized (this) {
            id = nextId++;
        }
        req.put("id", id);
        req.put("method", method);
        if (params != null) req.put("params", params);

        String body = Json.write(req);
        String resp = postJsonRpc(body);

        Map<String, Object> root;
        try {
            root = Json.parseObject(resp);
        } catch (Throwable t) {
            // 服务器可能返回空体（例如对通知的响应），或返回 SSE 包装
            String salvaged = extractSseData(resp);
            if (salvaged != null) {
                root = Json.parseObject(salvaged);
            } else {
                throw new McpException("无法解析响应: " + head(resp, 300));
            }
        }

        if (root == null) {
            throw new McpException("空响应");
        }

        // JSON-RPC 错误
        Map<String, Object> err = Json.obj(root, "error");
        if (err != null) {
            int code = Json.intVal(err, "code", 0);
            String msg = Json.str(err, "message");
            throw new McpException("MCP 错误 " + code + ": " + (msg == null ? "" : msg));
        }

        Map<String, Object> result = Json.obj(root, "result");
        if (result == null) {
            // 少数实现把 result 直接摊平在顶层
            result = new LinkedHashMap<>();
        }
        return result;
    }

    /** 发通知，不关心响应。 */
    private void notify(String method, Map<String, Object> params) {
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("jsonrpc", "2.0");
            req.put("method", method);
            if (params != null) req.put("params", params);
            postJsonRpc(Json.write(req));
        } catch (Throwable t) {
            Log.w(TAG, "notify " + method + " failed: " + t.getMessage());
        }
    }

    /** 真正发 HTTP。手写而不是用 Http.postJson，因为要加自定义头与会话 ID。 */
    private String postJsonRpc(String body) throws McpException {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            // 工具调用可能很慢（比如跑一段脚本），读超时给足
            conn.setReadTimeout(180000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json, text/event-stream");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }
            String sid = sessionId;
            if (sid != null) {
                conn.setRequestProperty("Mcp-Session-Id", sid);
            }
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }

            byte[] payload = body.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(payload.length);
            java.io.OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                try { os.close(); } catch (Throwable ignored) { }
            }

            // 抓住会话 ID，后续请求要带
            String newSid = conn.getHeaderField("Mcp-Session-Id");
            if (newSid != null && !newSid.isEmpty()) {
                sessionId = newSid;
            }

            int code = conn.getResponseCode();
            java.io.InputStream raw = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();

            boolean gz = "gzip".equalsIgnoreCase(conn.getContentEncoding());
            String respBody = raw == null ? "" : readBody(raw, gz);

            if (code < 200 || code >= 300) {
                throw new McpException("HTTP " + code + ": " + head(respBody, 300));
            }
            return respBody;
        } catch (McpException e) {
            throw e;
        } catch (Throwable t) {
            throw new McpException("网络失败: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage()));
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    private static String readBody(java.io.InputStream in, boolean gzip) throws java.io.IOException {
        java.io.InputStream src = in;
        try {
            if (gzip) {
                src = new java.util.zip.GZIPInputStream(in);
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = src.read(buf)) > 0) {
                bos.write(buf, 0, n);
                // 工具返回可能很大（比如读了整个文件），设个上限防止 OOM
                if (bos.size() > 8 * 1024 * 1024) break;
            }
            return bos.toString("UTF-8");
        } finally {
            if (src != in) {
                try { src.close(); } catch (Throwable ignored) { }
            }
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 从 SSE 格式里抽出 data 行。
     *
     * Streamable HTTP 传输下，服务器可能用 text/event-stream 回包：
     *   event: message
     *   data: {"jsonrpc":"2.0",...}
     * 取最后一条 data 就是我们要的响应。
     */
    private static String extractSseData(String body) {
        if (body == null || body.isEmpty()) return null;
        String last = null;
        for (String line : body.split("\n")) {
            String s = line.trim();
            if (s.startsWith("data:")) {
                String payload = s.substring(5).trim();
                if (!payload.isEmpty() && !"[DONE]".equals(payload)) {
                    last = payload;
                }
            }
        }
        return last;
    }

    private static String head(String s, int n) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    // ------------------------------------------------------------------
    // 元信息
    // ------------------------------------------------------------------

    public String name() {
        return name;
    }

    public String url() {
        return url;
    }

    public String serverName() {
        return serverName;
    }

    public String serverVersion() {
        return serverVersion;
    }

    public String negotiatedVersion() {
        return negotiatedVersion;
    }

    /** MCP 相关异常。统一类型，方便上层区分「协议错」和「网络错」。 */
    public static final class McpException extends Exception {
        public McpException(String message) {
            super(message);
        }
    }
}