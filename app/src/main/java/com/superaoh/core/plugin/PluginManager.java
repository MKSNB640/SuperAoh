package com.superaoh.core.plugin;

import android.content.Context;
import android.util.Log;

import com.superaoh.core.Json;
import com.superaoh.core.Workspace;
import com.superaoh.core.tool.ToolRegistry;
import com.superaoh.core.tool.ToolResult;
import com.superaoh.core.tool.ToolSpec;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ---------------------------------------------------------------------------
// 插件系统
//
// 插件 = 一个可安装的能力包，给 Agent 增加工具。格式刻意做成「纯声明式」：
//
//   <plugins>/<插件名>/
//       plugin.json
//       (可选) 其他脚本/资源文件
//
// plugin.json 结构：
//   {
//     "name": "天气",
//     "version": "1.0",
//     "description": "查天气",
//     "tools": [
//       {
//         "name": "weather",
//         "description": "查询城市天气",
//         "type": "http",
//         "method": "GET",
//         "url": "https://api.example.com/w?city={city}",
//         "headers": {"X-Key": "abc"},
//         "params": {"city": "城市名"}
//       },
//       {
//         "name": "mytool",
//         "description": "本地脚本",
//         "type": "shell",
//         "command": "bash {__dir__}/run.sh {arg}"
//       }
//     ]
//   }
//
// 为什么不做 JS 插件：
//   Android 上没有现成 JS 引擎（要么塞 QuickJS/V8 的 so，要么用已废弃的
//   Rhino）。声明式 + 系统 shell/HTTP 已能覆盖绝大多数需求，且零额外体积、
//   零崩溃风险。真要写逻辑，用户可以在沙箱里放脚本，插件只负责传参数。
//
// 与 Skill 的区别：
//   Skill 主要注入提示词（可选带工具），是「教模型怎么做事」；
//   插件只提供工具，是「给模型新的手脚」。来源标签不同，设置页分开管。
// ---------------------------------------------------------------------------

public final class PluginManager {

    private static final String TAG = "PluginManager";

    /** 一个已加载的插件。 */
    public static final class Plugin {
        public String id = "";
        public String name = "";
        public String version = "";
        public String description = "";
        public int toolCount = 0;
        public String error;

        public boolean ok() {
            return error == null;
        }
    }

    private final Context appCtx;
    private final ToolRegistry registry;
    private final File root;

    private final List<Plugin> plugins = new ArrayList<>();

    public PluginManager(Context ctx, ToolRegistry registry) {
        this.appCtx = ctx.getApplicationContext();
        this.registry = registry;
        this.root = resolveRoot(appCtx);
        if (!root.exists()) {
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
        }
    }

    /**
     * 插件根目录：优先外部存储，不可用则退回私有目录。
     *
     * 为什么要放外部存储（/sdcard/Android/data/<pkg>/files/SuperAoh/plugins）：
     *   私有目录 /data/data/<pkg>/files 用户用任何文件管理器都看不到，
     *   既没法往里放插件，也没法查看加载日志 —— 插件系统等于不可用。
     *   Android/data 下的目录可以直接用文件管理器、USB、甚至 adb push 操作，
     *   而且不需要任何存储权限（App 自己的外部目录）。
     *
     * 为什么不放 /sdcard/SuperAoh（非 Android/data）：
     *   Android 11+ 的分区存储下，非 media 类型文件写到公共目录会被
     *   MediaStore 拦截，卸载 App 也不会清理垃圾。Android/data 是唯一
     *   既免权限、卸载自动清理、又能被外部工具访问的位置。
     *
     * 退回私有目录的场景：手机存储未挂载（极少见），此时插件功能退化，
     *   但 App 不能因此崩溃。
     */
    private static File resolveRoot(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) {
            File dir = new File(ext, "SuperAoh/plugins");
            if (dir.exists() || dir.mkdirs()) {
                return dir;
            }
            Log.w(TAG, "外部插件目录不可写，退回私有目录: " + dir);
        }
        return new File(ctx.getFilesDir(), "plugins");
    }

    public File root() {
        return root;
    }

    public synchronized List<Plugin> plugins() {
        return new ArrayList<>(plugins);
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /** 全量重新加载所有插件。返回成功加载的工具总数。 */
    public synchronized int loadAll() {
        plugins.clear();
        registry.unregisterBySource(ToolRegistry.SRC_PLUGIN);

        File[] dirs = root.listFiles();
        if (dirs == null) return 0;

        int total = 0;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            try {
                Plugin p = loadOne(d);
                if (p == null) continue;
                plugins.add(p);
                total += p.toolCount;
            } catch (Throwable t) {
                Plugin bad = new Plugin();
                bad.id = d.getName();
                bad.name = d.getName();
                bad.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                plugins.add(bad);
                Log.w(TAG, "插件加载失败 " + d.getName() + ": " + bad.error);
            }
        }
        Log.i(TAG, "已加载 " + plugins.size() + " 个插件 / " + total + " 个工具");
        return total;
    }

    private Plugin loadOne(File dir) throws Exception {
        File meta = new File(dir, "plugin.json");
        if (!meta.isFile()) return null;

        Map<String, Object> m = Json.parseObject(readText(meta));
        Plugin p = new Plugin();
        p.id = dir.getName();
        p.name = strOr(Json.str(m, "name"), dir.getName());
        p.version = strOr(Json.str(m, "version"), "");
        p.description = strOr(Json.str(m, "description"), "");

        List<Object> list = Json.arr(m, "tools");
        if (list == null || list.isEmpty()) return p;

        int n = 0;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> tm = (Map<String, Object>) o;
            ToolSpec spec = buildTool(dir, p, tm);
            if (spec != null) {
                registry.register(spec);
                n++;
            }
        }
        p.toolCount = n;
        return p;
    }

    @SuppressWarnings("unchecked")
    private ToolSpec buildTool(File dir, Plugin p, Map<String, Object> tm) {
        String name = Json.str(tm, "name");
        if (name == null || name.isEmpty()) return null;

        String desc = strOr(Json.str(tm, "description"), "");
        String type = strOr(Json.str(tm, "type"), "shell");

        Map<String, String> params = new LinkedHashMap<>();
        Map<String, Object> pm = Json.obj(tm, "params");
        if (pm != null) {
            for (Map.Entry<String, Object> e : pm.entrySet()) {
                params.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        List<String> required = new ArrayList<>(params.keySet());

        // 工具名加插件前缀，避免两个插件撞名互相覆盖
        String full = p.id + "__" + name;

        if ("http".equalsIgnoreCase(type)) {
            String url = Json.str(tm, "url");
            if (url == null || url.isEmpty()) return null;
            String method = strOr(Json.str(tm, "method"), "GET");
            Map<String, String> headers = new LinkedHashMap<>();
            Map<String, Object> hm = Json.obj(tm, "headers");
            if (hm != null) {
                for (Map.Entry<String, Object> e : hm.entrySet()) {
                    headers.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            String bodyTmpl = Json.str(tm, "body");
            return new HttpTool(full, desc, params, required, method, url, headers, bodyTmpl);
        }

        String cmd = Json.str(tm, "command");
        if (cmd == null || cmd.isEmpty()) return null;
        // {__dir__} 换成插件目录，方便引用插件自带脚本
        cmd = cmd.replace("{__dir__}", dir.getAbsolutePath());
        return new ShellTool(full, desc, params, required, cmd);
    }

    // ------------------------------------------------------------------
    // 工具实现
    // ------------------------------------------------------------------

    /** shell 型插件工具。 */
    static final class ShellTool implements ToolSpec {

        private final String name;
        private final String description;
        private final Map<String, String> params;
        private final List<String> required;
        private final String template;

        ShellTool(String name, String description, Map<String, String> params,
                  List<String> required, String template) {
            this.name = name;
            this.description = description;
            this.params = params;
            this.required = required;
            this.template = template;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public Map<String, String> params() {
            return params;
        }

        @Override
        public List<String> required() {
            return required;
        }

        @Override
        public String source() {
            return ToolRegistry.SRC_PLUGIN;
        }

        @Override
        public boolean needsWorkspace() {
            return false;
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            String cmd = template;
            for (Map.Entry<String, String> e : params.entrySet()) {
                String k = e.getKey();
                Object v = args == null ? null : args.get(k);
                cmd = cmd.replace("{" + k + "}", shellQuote(v == null ? "" : String.valueOf(v)));
            }
            try {
                Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                        .redirectErrorStream(true).start();
                String out = readAll(p.getInputStream(), 512 * 1024);
                int code = p.waitFor();
                return code == 0
                        ? ToolResult.ok(out.isEmpty() ? "(无输出)" : out)
                        : ToolResult.fail("退出码 " + code + "\n" + out);
            } catch (Throwable t) {
                return ToolResult.fail("执行失败: " + t.getMessage());
            }
        }
    }

    /** HTTP 型插件工具：把参数填进 URL/body 再发请求。 */
    static final class HttpTool implements ToolSpec {

        private final String name;
        private final String description;
        private final Map<String, String> params;
        private final List<String> required;
        private final String method;
        private final String urlTemplate;
        private final Map<String, String> headers;
        private final String bodyTemplate;

        HttpTool(String name, String description, Map<String, String> params,
                 List<String> required, String method, String urlTemplate,
                 Map<String, String> headers, String bodyTemplate) {
            this.name = name;
            this.description = description;
            this.params = params;
            this.required = required;
            this.method = method == null ? "GET" : method.toUpperCase();
            this.urlTemplate = urlTemplate;
            this.headers = headers;
            this.bodyTemplate = bodyTemplate;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public Map<String, String> params() {
            return params;
        }

        @Override
        public List<String> required() {
            return required;
        }

        @Override
        public String source() {
            return ToolRegistry.SRC_PLUGIN;
        }

        @Override
        public boolean needsWorkspace() {
            return false;
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            String url = urlTemplate;
            for (Map.Entry<String, String> e : params.entrySet()) {
                String k = e.getKey();
                Object v = args == null ? null : args.get(k);
                // URL 里要做 percent 编码，否则中文和空格会把请求打坏
                url = url.replace("{" + k + "}", urlEncode(v == null ? "" : String.valueOf(v)));
            }

            String body = null;
            if (bodyTemplate != null && !bodyTemplate.isEmpty()) {
                body = bodyTemplate;
                for (Map.Entry<String, String> e : params.entrySet()) {
                    String k = e.getKey();
                    Object v = args == null ? null : args.get(k);
                    body = body.replace("{" + k + "}", jsonEscape(v == null ? "" : String.valueOf(v)));
                }
            }

            java.net.HttpURLConnection conn = null;
            try {
                conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    conn.setRequestProperty(h.getKey(), h.getValue());
                }
                if (body != null) {
                    conn.setDoOutput(true);
                    if (!headers.containsKey("Content-Type")) {
                        conn.setRequestProperty("Content-Type", "application/json");
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
                }
                int code = conn.getResponseCode();
                java.io.InputStream in = (code >= 200 && code < 300)
                        ? conn.getInputStream() : conn.getErrorStream();
                String resp = in == null ? "" : readAll(in, 512 * 1024);
                if (code >= 200 && code < 300) {
                    return ToolResult.ok(resp.isEmpty() ? "(空响应)" : resp);
                }
                return ToolResult.fail("HTTP " + code + "\n" + resp);
            } catch (Throwable t) {
                return ToolResult.fail("请求失败: " + t.getMessage());
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) { }
                }
            }
        }

        private static String urlEncode(String s) {
            try {
                return java.net.URLEncoder.encode(s, "UTF-8");
            } catch (Throwable t) {
                return s;
            }
        }

        private static String jsonEscape(String s) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String strOr(String s, String def) {
        return (s == null || s.isEmpty()) ? def : s;
    }

    private static String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            return readAll(in, 2 * 1024 * 1024);
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static String readAll(java.io.InputStream in, int limit) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(4096);
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > limit) break;
        }
        try { in.close(); } catch (Throwable ignored) { }
        return bos.toString("UTF-8").trim();
    }
}