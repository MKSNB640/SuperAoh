package com.superaoh.core.mcp;

import android.content.Context;
import android.util.Log;

import com.superaoh.core.Json;
import com.superaoh.core.Workspace;
import com.superaoh.core.tool.ToolRegistry;
import com.superaoh.core.tool.ToolResult;
import com.superaoh.core.tool.ToolSpec;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ---------------------------------------------------------------------------
// MCP 服务器管理
//
// 职责：
//   1. 读写服务器配置（文件放在 App 私有目录，不进工作区 —— 里面有 token）
//   2. 连接服务器、拉工具列表、把远程工具注册进 ToolRegistry
//   3. 提供「重新加载全部」的入口给设置页
//
// 工具命名约定：mcp__<服务器名>__<工具名>
//   服务器名要先做安全化（去掉空格与 __），否则名字会歧义；
//   前缀统一，断线时能用 unregisterByPrefix 一把清干净。
// ---------------------------------------------------------------------------

public final class McpManager {

    private static final String TAG = "McpManager";

    /** 配置文件名。 */
    private static final String CONFIG_FILE = "mcp_servers.json";

    /** 一台 MCP 服务器的配置。 */
    public static final class Server {
        public String name = "";
        public String url = "";
        public String apiKey = "";
        /** 额外请求头，例如某些网关需要的 X-Api-Key。key=value 用换行分隔。 */
        public String headersRaw = "";
        /** 是否启用。关掉后不连接、工具不注册。 */
        public boolean enabled = true;

        /** 运行状态，不持久化。 */
        public transient boolean connected;
        public transient int toolCount;
        public transient String lastError;

        public Server() {
        }

        public Server(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private final Context appCtx;
    private final ToolRegistry registry;

    /** 已配置的服务器。 */
    private final List<Server> servers = new ArrayList<>();

    /** 已建立的客户端，按安全化后的服务器名索引。 */
    private final Map<String, McpClient> clients = new LinkedHashMap<>();

    public McpManager(Context ctx, ToolRegistry registry) {
        this.appCtx = ctx.getApplicationContext();
        this.registry = registry;
        load();
    }

    // ------------------------------------------------------------------
    // 配置持久化
    // ------------------------------------------------------------------

    private File configFile() {
        return new File(appCtx.getFilesDir(), CONFIG_FILE);
    }

    /** 读取配置。文件不存在或损坏都退回空列表，绝不让设置页打不开。 */
    @SuppressWarnings("unchecked")
    public synchronized void load() {
        servers.clear();
        File f = configFile();
        if (!f.isFile()) return;
        try {
            String text = readText(f);
            Map<String, Object> root = Json.parseObject(text);
            List<Object> list = Json.arr(root, "servers");
            if (list == null) return;
            for (Object o : list) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;
                Server s = new Server();
                s.name = nz(Json.str(m, "name"));
                s.url = nz(Json.str(m, "url"));
                s.apiKey = nz(Json.str(m, "apiKey"));
                s.headersRaw = nz(Json.str(m, "headers"));
                Object en = m.get("enabled");
                s.enabled = !(en instanceof Boolean) || (Boolean) en;
                if (!s.name.isEmpty() && !s.url.isEmpty()) {
                    servers.add(s);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取 MCP 配置失败: " + t.getMessage());
        }
    }

    /** 写入配置。token 明文落盘在 App 私有目录，其他应用读不到。 */
    public synchronized void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> list = new ArrayList<>();
        for (Server s : servers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name);
            m.put("url", s.url);
            m.put("apiKey", s.apiKey);
            m.put("headers", s.headersRaw);
            m.put("enabled", s.enabled);
            list.add(m);
        }
        root.put("servers", list);
        try {
            File f = configFile();
            FileOutputStream o = new FileOutputStream(f);
            try {
                o.write(Json.write(root).getBytes("UTF-8"));
                o.flush();
            } finally {
                o.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "保存 MCP 配置失败: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 服务器增删
    // ------------------------------------------------------------------

    public synchronized List<Server> servers() {
        return new ArrayList<>(servers);
    }

    public synchronized Server find(String name) {
        for (Server s : servers) {
            if (s.name.equals(name)) return s;
        }
        return null;
    }

    /** 新增或更新（同名覆盖）。 */
    public synchronized void upsert(Server s) {
        if (s == null || s.name == null || s.name.isEmpty()) return;
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).name.equals(s.name)) {
                servers.set(i, s);
                save();
                return;
            }
        }
        servers.add(s);
        save();
    }

    /** 删除一台服务器，同时清掉它注册的工具。 */
    public synchronized void remove(String name) {
        Server s = find(name);
        if (s == null) return;
        disconnect(name);
        servers.remove(s);
        save();
    }

    // ------------------------------------------------------------------
    // 连接 / 注册
    // ------------------------------------------------------------------

    /**
     * 连接一台服务器并注册它的工具。
     *
     * 失败不抛异常，写进 server.lastError 供界面展示 —— 一台服务器挂了
     * 不该影响其他服务器，也不该让整个 Agent 起不来。
     */
    public synchronized boolean connect(String name) {
        Server s = find(name);
        if (s == null) return false;
        if (!s.enabled) {
            s.lastError = "服务器已禁用";
            return false;
        }

        // 重连前先清掉旧工具，避免改名后残留
        String safe = safeName(s.name);
        registry.unregisterByPrefix(prefix(safe));
        McpClient old = clients.remove(safe);
        if (old != null) old.disconnect();

        McpClient client = new McpClient(s.name, s.url, s.apiKey, parseHeaders(s.headersRaw));
        try {
            client.connect();
            List<McpClient.RemoteTool> tools = client.listTools();
            for (McpClient.RemoteTool rt : tools) {
                registry.register(new McpTool(safe, s.name, client, rt));
            }
            clients.put(safe, client);
            s.connected = true;
            s.toolCount = tools.size();
            s.lastError = null;
            Log.i(TAG, "server " + s.name + " 注册 " + tools.size() + " 个工具");
            return true;
        } catch (Throwable t) {
            s.connected = false;
            s.toolCount = 0;
            s.lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            Log.w(TAG, "连接 " + s.name + " 失败: " + s.lastError);
            return false;
        }
    }

    /** 断开一台服务器，注销其工具。 */
    public synchronized void disconnect(String name) {
        String safe = safeName(name);
        registry.unregisterByPrefix(prefix(safe));
        McpClient c = clients.remove(safe);
        if (c != null) c.disconnect();
        Server s = find(name);
        if (s != null) {
            s.connected = false;
            s.toolCount = 0;
        }
    }

    /**
     * 重连全部已启用的服务器。
     *
     * 供设置页「刷新」和 App 启动时调用。串行连接是有意为之：
     * 并发连接会同时占多个 socket，弱网下更容易集体超时。
     */
    public synchronized int connectAllEnabled() {
        int ok = 0;
        for (Server s : new ArrayList<>(servers)) {
            if (s.enabled && connect(s.name)) ok++;
        }
        return ok;
    }

    /** 断开全部。 */
    public synchronized void disconnectAll() {
        for (Server s : servers) {
            disconnect(s.name);
        }
    }

    /** 已注册的远程工具总数。 */
    public synchronized int totalToolCount() {
        int n = 0;
        for (Server s : servers) {
            if (s.connected) n += s.toolCount;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 工具名前缀。 */
    private static String prefix(String safeServerName) {
        return "mcp__" + safeServerName + "__";
    }

    /**
     * 服务器名安全化：只保留字母数字和中文，其余换下划线。
     *
     * 原因是工具名会进 OpenAI tools 数组，部分服务端会校验名字必须匹配
     * ^[a-zA-Z0-9_-]+$。中文实测可以通过，但空格和点号不行。
     */
    static String safeName(String raw) {
        if (raw == null) return "server";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String s = sb.toString();
        return s.isEmpty() ? "server" : s;
    }

    /** 解析 "Key: Value" 每行一个 的额外头。 */
    private static Map<String, String> parseHeaders(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            int i = s.indexOf(':');
            if (i <= 0) continue;
            String k = s.substring(0, i).trim();
            String v = s.substring(i + 1).trim();
            if (!k.isEmpty()) out.put(k, v);
        }
        return out;
    }

    private static String readText(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 1024 * 1024) break; // 配置文件不该超过 1MB
            }
            return bos.toString("UTF-8");
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------
    // 远程工具 → ToolSpec
    // ------------------------------------------------------------------

    /**
     * 把 MCP 远程工具适配成 ToolSpec。
     *
     * 有一个关键取舍：schema 透传。
     * MCP 的 inputSchema 是完整 JSON Schema，含 integer/boolean/array/object，
     * 直接透传比压平成 string 准得多 —— 模型知道该给数组还是字符串，
     * 参数出错率明显下降。所以这里实现 customInputSchema()。
     */
    static final class McpTool implements ToolSpec {

        private final String safeServer;
        private final String displayServer;
        private final McpClient client;
        private final McpClient.RemoteTool meta;

        McpTool(String safeServer, String displayServer, McpClient client,
                McpClient.RemoteTool meta) {
            this.safeServer = safeServer;
            this.displayServer = displayServer;
            this.client = client;
            this.meta = meta;
        }

        @Override
        public String name() {
            return prefix(safeServer) + meta.name;
        }

        @Override
        public String description() {
            String d = meta.description == null ? "" : meta.description;
            // 标上来源，模型在多个同功能工具间更容易选对
            return d.isEmpty() ? ("来自 MCP 服务器 " + displayServer + " 的工具 " + meta.name)
                    : d;
        }

        @Override
        public Map<String, String> params() {
            return new LinkedHashMap<>();
        }

        @Override
        public List<String> required() {
            return new ArrayList<>();
        }

        @Override
        public String source() {
            return ToolRegistry.SRC_MCP;
        }

        @Override
        public boolean needsWorkspace() {
            // MCP 工具跑在远端，不依赖本地工作区
            return false;
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            try {
                return ToolResult.ok(client.callTool(meta.name, args));
            } catch (McpClient.McpException e) {
                return ToolResult.fail("MCP 调用失败: " + e.getMessage());
            }
        }

        @Override
        public Map<String, Object> customInputSchema() {
            Map<String, Object> schema = meta.inputSchema;
            if (schema == null || schema.isEmpty()) return null;
            return schema;
        }
    }
}