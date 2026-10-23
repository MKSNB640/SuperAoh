package com.superaoh.core.tool.builtin;

import android.content.Context;

import com.superaoh.core.Workspace;
import com.superaoh.core.sandbox.UbuntuSandbox;
import com.superaoh.core.tool.ToolRegistry;
import com.superaoh.core.tool.ToolResult;
import com.superaoh.core.tool.ToolSpec;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 面向工程工作的补充工具。
 *
 * 这些工具是 SuperAoh 自己实现的 ToolSpec，不依赖或复制其他应用的
 * DEX、源码、图标或品牌资源。它们把常见的「先探测、再处理、可复核」
 * 操作暴露给 Agent，所有工作区文件访问仍然限制在当前 Workspace 内。
 */
public final class EngineeringTools {

    private EngineeringTools() {
    }

    public static void installAll(ToolRegistry registry, UbuntuSandbox sandbox) {
        if (registry == null) return;
        registry.register(new ToolCatalog(registry));
        registry.register(new WorkspaceSearch());
        registry.register(new WorkspaceSha256());
        registry.register(new ArchiveList());
        registry.register(new HttpGet());
        if (sandbox != null) {
            registry.register(new SandboxCapabilities(sandbox));
        }
    }

    private abstract static class BaseTool implements ToolSpec {
        @Override
        public boolean needsWorkspace() {
            return false;
        }

        @Override
        public String source() {
            return ToolRegistry.SRC_BUILTIN;
        }

        @Override
        public List<String> required() {
            return new ArrayList<>();
        }

        @Override
        public Map<String, String> params() {
            return new LinkedHashMap<>();
        }
    }

    /** 显示当前真正注册并可见的工具，避免模型凭空猜工具名。 */
    private static final class ToolCatalog extends BaseTool {
        private final ToolRegistry registry;

        ToolCatalog(ToolRegistry registry) {
            this.registry = registry;
        }

        @Override
        public String name() {
            return "tool_catalog";
        }

        @Override
        public String description() {
            return "列出当前 Agent 实际可调用的工具、来源和简要说明。" +
                    "当不确定某项能力是否存在时先调用，不要猜测工具名。";
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            StringBuilder out = new StringBuilder();
            List<ToolSpec> tools = registry.visibleTools();
            out.append("当前可见工具（").append(tools.size()).append(" 个）:\n");
            for (ToolSpec t : tools) {
                out.append("- ").append(t.name())
                        .append(" [").append(t.source()).append("] ")
                        .append(t.description()).append('\n');
            }
            return ToolResult.ok(out.toString());
        }
    }

    /** 在工作区中按文件名和文本内容搜索。 */
    private static final class WorkspaceSearch extends BaseTool {
        @Override
        public String name() {
            return "workspace_search";
        }

        @Override
        public String description() {
            return "在当前工作区递归搜索文件名和常见文本文件内容，返回命中路径及上下文。" +
                    "拒绝越过工作区根目录；适合定位配置、源码、JSON、日志和补丁。";
        }

        @Override
        public Map<String, String> params() {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("query", "要搜索的字符串，区分大小写由工具按不区分大小写处理");
            p.put("path", "工作区内相对目录，留空表示整个工作区");
            p.put("maxResults", "最多返回多少个命中，默认 30，最大 100");
            return p;
        }

        @Override
        public List<String> required() {
            List<String> r = new ArrayList<>();
            r.add("query");
            return r;
        }

        @Override
        public boolean needsWorkspace() {
            return true;
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            String query = value(args, "query").trim();
            if (query.isEmpty()) return ToolResult.fail("query 不能为空");
            File root = workspaceRoot(ws, ctx);
            File base = safeResolve(root, value(args, "path"));
            if (base == null) return ToolResult.fail("路径越界或非法");
            if (!base.exists()) return ToolResult.fail("目录不存在: " + value(args, "path"));
            int limit = boundedInt(args, "maxResults", 30, 1, 100);
            String needle = query.toLowerCase();
            Deque<File> pending = new ArrayDeque<>();
            pending.push(base);
            StringBuilder out = new StringBuilder();
            int hits = 0;
            int scanned = 0;
            while (!pending.isEmpty() && hits < limit) {
                File f = pending.pop();
                if (f.isDirectory()) {
                    File[] children = f.listFiles();
                    if (children == null) continue;
                    Arrays.sort(children, Comparator.comparing(File::getName,
                            String.CASE_INSENSITIVE_ORDER));
                    for (int i = children.length - 1; i >= 0; i--) {
                        pending.push(children[i]);
                    }
                    continue;
                }
                if (!f.isFile()) continue;
                scanned++;
                String rel = relative(root, f);
                String lowerName = f.getName().toLowerCase();
                if (lowerName.contains(needle)) {
                    out.append("[文件名] ").append(rel).append('\n');
                    hits++;
                    continue;
                }
                if (!isLikelyText(f) || f.length() > 8L * 1024 * 1024) continue;
                String text = readUtf8(f, 2 * 1024 * 1024);
                int index = text.toLowerCase().indexOf(needle);
                if (index >= 0) {
                    int from = Math.max(0, index - 90);
                    int to = Math.min(text.length(), index + query.length() + 120);
                    String snippet = text.substring(from, to)
                            .replace('\n', ' ').replace('\r', ' ');
                    out.append("[内容] ").append(rel).append("\n  …")
                            .append(snippet).append("…\n");
                    hits++;
                }
            }
            out.append("\n扫描 ").append(scanned).append(" 个文件，命中 ")
                    .append(hits).append(" 个");
            if (hits >= limit) out.append("（已达到上限）");
            return ToolResult.ok(out.toString());
        }
    }

    /** 计算工作区文件 SHA-256，便于补丁和 APK 验证。 */
    private static final class WorkspaceSha256 extends BaseTool {
        @Override
        public String name() {
            return "workspace_sha256";
        }

        @Override
        public String description() {
            return "计算工作区内文件的 SHA-256 和大小，用于验证 APK、补丁或构建产物是否改变。";
        }

        @Override
        public Map<String, String> params() {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("path", "工作区内文件的相对路径");
            return p;
        }

        @Override
        public List<String> required() {
            List<String> r = new ArrayList<>();
            r.add("path");
            return r;
        }

        @Override
        public boolean needsWorkspace() {
            return true;
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            File root = workspaceRoot(ws, ctx);
            String path = value(args, "path");
            File file = safeResolve(root, path);
            if (file == null || !file.isFile()) return ToolResult.fail("文件不存在或路径越界: " + path);
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                    byte[] buffer = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
                }
                return ToolResult.ok("path=" + relative(root, file) +
                        "\nsize=" + file.length() + " bytes\nsha256=" + hex(digest.digest()));
            } catch (Throwable t) {
                return ToolResult.fail("计算 SHA-256 失败: " + t.getMessage());
            }
        }
    }

    /** 列出工作区内 ZIP/APK/JAR 的目录，不解压、不修改原文件。 */
    private static final class ArchiveList extends BaseTool {
        @Override
        public String name() {
            return "archive_list";
        }

        @Override
        public String description() {
            return "列出工作区内 ZIP/APK/JAR 文件的条目，支持按前缀筛选，不会解压或修改归档。";
        }

        @Override
        public Map<String, String> params() {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("path", "工作区内 ZIP/APK/JAR 文件的相对路径");
            p.put("prefix", "可选的条目前缀，例如 assets/ 或 lib/arm64-v8a/");
            p.put("maxResults", "最多返回多少条，默认 200，最大 1000");
            return p;
        }

        @Override
        public List<String> required() {
            List<String> r = new ArrayList<>();
            r.add("path");
            return r;
        }

        @Override
        public boolean needsWorkspace() {
            return true;
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            File root = workspaceRoot(ws, ctx);
            String path = value(args, "path");
            File archive = safeResolve(root, path);
            if (archive == null || !archive.isFile()) return ToolResult.fail("归档不存在或路径越界: " + path);
            String prefix = value(args, "prefix");
            int limit = boundedInt(args, "maxResults", 200, 1, 1000);
            StringBuilder out = new StringBuilder();
            int count = 0;
            try (ZipFile zip = new ZipFile(archive)) {
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (!prefix.isEmpty() && !e.getName().startsWith(prefix)) continue;
                    if (count++ >= limit) {
                        out.append("...（已截断）\n");
                        break;
                    }
                    out.append(e.isDirectory() ? "[D] " : "[F] ")
                            .append(e.getName());
                    if (!e.isDirectory()) out.append("  ").append(e.getSize()).append("B");
                    out.append('\n');
                }
                return ToolResult.ok("归档: " + relative(root, archive) +
                        "\n条目上限: " + limit + "\n" + out);
            } catch (Throwable t) {
                return ToolResult.fail("读取归档失败: " + t.getMessage());
            }
        }
    }

    /** 轻量 GET，用于查询公开接口和下载前探测；大文件不会全部灌进上下文。 */
    private static final class HttpGet extends BaseTool {
        @Override
        public String name() {
            return "http_get";
        }

        @Override
        public String description() {
            return "对 http/https 地址执行只读 GET 并返回响应摘要。适合查公开 API、版本信息和文本资源；" +
                    "有大小上限，不负责登录或提交表单。";
        }

        @Override
        public Map<String, String> params() {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("url", "完整的 http:// 或 https:// URL");
            p.put("maxChars", "最多返回字符数，默认 12000，最大 50000");
            return p;
        }

        @Override
        public List<String> required() {
            List<String> r = new ArrayList<>();
            r.add("url");
            return r;
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            String raw = value(args, "url").trim();
            int max = boundedInt(args, "maxChars", 12000, 256, 50000);
            HttpURLConnection connection = null;
            try {
                URL url = new URL(raw);
                String protocol = url.getProtocol();
                if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                    return ToolResult.fail("只允许 http/https URL");
                }
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("Accept", "text/plain, application/json, text/*, */*");
                int code = connection.getResponseCode();
                InputStream rawIn = code >= 200 && code < 400
                        ? connection.getInputStream() : connection.getErrorStream();
                String body = rawIn == null ? "" : readChars(rawIn, max);
                String result = "HTTP " + code + " " + connection.getResponseMessage() +
                        "\nContent-Type: " + connection.getContentType() + "\n\n" + body;
                return code >= 200 && code < 400 ? ToolResult.ok(result) : ToolResult.fail(result);
            } catch (Throwable t) {
                return ToolResult.fail("GET 失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }

    /** 给模型和用户提供当前 rootfs 的真实工具链，而不是静态猜测。 */
    private static final class SandboxCapabilities extends BaseTool {
        private final UbuntuSandbox sandbox;

        SandboxCapabilities(UbuntuSandbox sandbox) {
            this.sandbox = sandbox;
        }

        @Override
        public String name() {
            return "sandbox_capabilities";
        }

        @Override
        public String description() {
            return "在 Ubuntu 沙箱内实测工程工具链和磁盘余量，返回 java/pip3/git/zip/编译器等命令是否存在。" +
                    "安装软件前先调用，避免凭空假定镜像能力。";
        }

        @Override
        public boolean needsWorkspace() {
            return false;
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            if (!sandbox.isInstalled()) return ToolResult.fail("Ubuntu 沙箱尚未安装");
            String command = "printf 'rootfs=%s\\n' /; df -h /; " +
                    "for x in bash python3 pip3 git curl wget unzip zip tar xz java javac " +
                    "make cmake gcc g++ pkg-config; do printf '%-12s' \"$x\"; command -v \"$x\" || echo MISSING; done; " +
                    "python3 --version 2>&1; pip3 --version 2>&1; git --version 2>&1; java -version 2>&1 | head -1; " +
                    "du -sh /var/cache/apt/archives /var/lib/apt/lists 2>/dev/null || true";
            try {
                UbuntuSandbox.ExecResult result = sandbox.exec(command, 120000);
                return result.ok() ? ToolResult.ok(result.combined()) : ToolResult.fail(result.combined());
            } catch (Throwable t) {
                return ToolResult.fail("探测沙箱失败: " + t.getMessage());
            }
        }
    }

    private static File workspaceRoot(Workspace ws, Context ctx) {
        return ws == null || ctx == null ? null : ws.dir(ctx);
    }

    private static File safeResolve(File root, String rel) {
        if (root == null) return null;
        String clean = rel == null ? "" : rel.replace('\\', '/').trim();
        while (clean.startsWith("/")) clean = clean.substring(1);
        File target = new File(root, clean);
        try {
            String rootPath = root.getCanonicalPath();
            String targetPath = target.getCanonicalPath();
            if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
                return null;
            }
            return target;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String relative(File root, File file) {
        try {
            String a = root.getCanonicalPath();
            String b = file.getCanonicalPath();
            if (b.equals(a)) return ".";
            return b.startsWith(a + File.separator) ? b.substring(a.length() + 1) : b;
        } catch (Throwable t) {
            return file.getName();
        }
    }

    private static String value(Map<String, Object> args, String key) {
        if (args == null) return "";
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static int boundedInt(Map<String, Object> args, String key, int def, int min, int max) {
        try {
            int n = Integer.parseInt(value(args, key).trim());
            return Math.max(min, Math.min(max, n));
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean isLikelyText(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".json") || name.endsWith(".xml")
                || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".md")
                || name.endsWith(".properties") || name.endsWith(".ini") || name.endsWith(".csv")
                || name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".gradle")
                || name.endsWith(".kts") || name.endsWith(".smali") || name.endsWith(".sh")
                || name.endsWith(".log") || name.indexOf('.') < 0;
    }

    private static String readUtf8(File file, int maxBytes) {
        try {
            byte[] data = new byte[(int) Math.min(file.length(), maxBytes)];
            try (FileInputStream in = new FileInputStream(file)) {
                int offset = 0;
                int n;
                while (offset < data.length && (n = in.read(data, offset, data.length - offset)) > 0) {
                    offset += n;
                }
                return new String(data, 0, offset, "UTF-8");
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readChars(InputStream input, int maxChars) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, Charset.forName("UTF-8")));
        StringBuilder out = new StringBuilder(Math.min(maxChars, 8192));
        char[] buffer = new char[8192];
        int n;
        while (out.length() < maxChars && (n = reader.read(buffer)) > 0) {
            int remaining = maxChars - out.length();
            out.append(buffer, 0, Math.min(n, remaining));
        }
        reader.close();
        if (out.length() >= maxChars) out.append("\n…（响应已截断）");
        return out.toString();
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return out.toString();
    }
}
