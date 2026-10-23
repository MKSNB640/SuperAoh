package com.superaoh.core;

import android.content.Context;

import com.superaoh.core.tool.ToolProvider;
import com.superaoh.core.tool.ToolResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Agent 工具注册表与执行器。
 *
 * 设计原则：
 *   1. 每个工具都有严格的参数校验，绝不因为模型给错参数就崩溃或误删文件。
 *   2. 所有文件操作都被限制在工作区目录内（防目录穿越）。
 *   3. 工具返回纯文本，便于直接回灌给模型当作 tool 消息。
 *
 * 工具清单（MVP）：
 *   list_workspace     列出工作区根目录
 *   list_dir           列出某个子目录
 *   read_text          读文本文件（限长度）
 *   write_text         写文本文件
 *   delete_path        删除文件或目录
 *   apk_info           读 APK 结构（assets 顶层目录 / dex / so）
 *   apk_list_assets    列出 APK 内某个 assets 子目录
 *   apk_extract        从 APK 提取文件到工作区
 *   search_in_apk      在 APK 的文本类资源里搜索（限 json/txt）
 *   dex_list           列出工作区已部署的 DEX 与补丁
 *   runtime_status     当前游戏运行状态（反射读 CFG）
 */
public final class AgentTools implements ToolProvider {

    /** 工具执行结果。 */
    public static final class Result {
        public final boolean ok;
        public final String text;

        Result(boolean ok, String text) {
            this.ok = ok;
            this.text = text;
        }

        public static Result ok(String text) {
            return new Result(true, text);
        }

        public static Result fail(String text) {
            return new Result(false, text);
        }
    }

    /** 单个工具的定义。 */
    public static final class Tool {
        public final String name;
        public final String description;
        public final Map<String, String> params; // 参数名 -> 说明
        public final List<String> required;

        public Tool(String name, String description, Map<String, String> params,
                    List<String> required) {
            this.name = name;
            this.description = description;
            this.params = params;
            this.required = required;
        }

        /** 转成 OpenAI tools 数组里的一项。 */
        public Map<String, Object> toOpenAiSchema() {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", name);
            fn.put("description", description);
            Map<String, Object> props = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : params.entrySet()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("type", "string");
                p.put("description", e.getValue());
                props.put(e.getKey(), p);
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            if (!required.isEmpty()) {
                schema.put("required", new ArrayList<Object>(required));
            }
            fn.put("parameters", schema);

            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("type", "function");
            wrap.put("function", fn);
            return wrap;
        }
    }

    // ============================================================
    // 注册表
    // ============================================================

    private final List<Tool> tools = new ArrayList<>();

    public AgentTools() {
        reg("list_workspace", "列出当前工作区根目录下的文件和文件夹",
                null, null);

        Map<String, String> p1 = new LinkedHashMap<>();
        p1.put("path", "相对工作区根目录的路径，留空表示根目录");
        reg("list_dir", "列出工作区里某个子目录的内容", p1, null);

        Map<String, String> p2 = new LinkedHashMap<>();
        p2.put("path", "相对路径，例如 data/aoh2/save.ser");
        p2.put("maxChars", "最多返回多少字符，默认 20000");
        reg("read_text", "读取工作区里的文本文件内容", p2, one("path"));

        Map<String, String> p3 = new LinkedHashMap<>();
        p3.put("path", "相对路径");
        p3.put("content", "要写入的完整文本内容");
        reg("write_text", "把文本写入工作区里的文件（覆盖已有内容）", p3, one("path", "content"));

        Map<String, String> p4 = new LinkedHashMap<>();
        p4.put("path", "相对路径");
        reg("delete_path", "删除工作区里的文件或目录（目录会递归删除）", p4, one("path"));

        reg("apk_info", "读取游戏 APK 的结构：assets 顶层目录、dex 列表、so 列表",
                null, null);

        Map<String, String> p5 = new LinkedHashMap<>();
        p5.put("path", "assets 内的相对路径，例如 game 或 map/HoH4，留空表示 assets 根");
        p5.put("limit", "最多返回多少条，默认 300");
        reg("apk_list_assets", "列出游戏 APK 内 assets 目录下的文件", p5, null);

        Map<String, String> p6 = new LinkedHashMap<>();
        p6.put("assetPath", "APK 内路径，例如 assets/game/Governments.json");
        p6.put("outPath", "保存到工作区的相对路径");
        reg("apk_extract", "从游戏 APK 里提取一个文件到工作区", p6, one("assetPath", "outPath"));

        Map<String, String> p7 = new LinkedHashMap<>();
        p7.put("keyword", "要搜索的关键词");
        p7.put("assetDir", "限定在 assets 下哪个子目录，例如 game，留空为全部");
        p7.put("limit", "最多返回多少条，默认 30");
        reg("search_in_apk", "在游戏 APK 的 json/txt 资源里搜索关键词", p7, one("keyword"));

        reg("dex_list", "列出工作区里已部署的游戏 DEX 和补丁 DEX", null, null);

        reg("runtime_status", "读取当前游戏运行时的内部状态（CFG 字段、viewID）",
                null, null);
    }

    private static List<String> one(String... names) {
        List<String> l = new ArrayList<>();
        for (String n : names) {
            l.add(n);
        }
        return l;
    }

    private void reg(String name, String desc, Map<String, String> params,
                     List<String> required) {
        tools.add(new Tool(name, desc,
                params == null ? new LinkedHashMap<String, String>() : params,
                required == null ? new ArrayList<String>() : required));
    }

    public List<Tool> tools() {
        return tools;
    }

    /** 组装成请求里的 tools 字段。 */
    public List<Object> toOpenAiTools() {
        List<Object> out = new ArrayList<>();
        for (Tool t : tools) {
            out.add(t.toOpenAiSchema());
        }
        return out;
    }

    // ============================================================
    // 执行
    // ============================================================

    /**
     * 执行一次工具调用。
     *
     * @param name 工具名
     * @param args 参数（已从 JSON 解析成 Map）
     * @param ws   当前工作区，null 表示没有工作区
     */
    public Result execLegacy(String name, Map<String, Object> args,
                          Workspace ws, Context ctx) {
        try {
            if (ws == null && needsWorkspace(name)) {
                return Result.fail("当前没有工作区，请先在主页导入游戏 APK。");
            }
            if ("list_workspace".equals(name)) {
                return doListWorkspace(ws, ctx);
            }
            if ("list_dir".equals(name)) {
                return doListDir(ws, ctx, Json.str(args, "path"));
            }
            if ("read_text".equals(name)) {
                return doReadText(ws, ctx, Json.str(args, "path"),
                        Json.intVal(args, "maxChars", 20000));
            }
            if ("write_text".equals(name)) {
                return doWriteText(ws, ctx, Json.str(args, "path"),
                        Json.str(args, "content"));
            }
            if ("delete_path".equals(name)) {
                return doDelete(ws, ctx, Json.str(args, "path"));
            }
            if ("apk_info".equals(name)) {
                return doApkInfo(ws, ctx);
            }
            if ("apk_list_assets".equals(name)) {
                return doApkListAssets(ws, ctx, Json.str(args, "path"),
                        Json.intVal(args, "limit", 300));
            }
            if ("apk_extract".equals(name)) {
                return doApkExtract(ws, ctx, Json.str(args, "assetPath"),
                        Json.str(args, "outPath"));
            }
            if ("search_in_apk".equals(name)) {
                return doSearchInApk(ws, ctx, Json.str(args, "keyword"),
                        Json.str(args, "assetDir"), Json.intVal(args, "limit", 30));
            }
            if ("dex_list".equals(name)) {
                return doDexList(ctx);
            }
            if ("runtime_status".equals(name)) {
                return Result.ok(com.superaoh.container.GameRuntime.probeLine()
                        + "\n" + com.superaoh.container.GameRuntime.diagnose());
            }
            return Result.fail("未知工具: " + name);
        } catch (Throwable t) {
            return Result.fail("工具执行异常: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    private boolean needsWorkspace(String name) {
        return needsWorkspaceStatic(name);
    }

    /**
     * 静态版判断，给 ToolRegistry 侧的内置工具适配器复用。
     *
     * 只读常量判断，不需要实例，所以做成静态的。改这里的规则时，
     * AgentTools 内部的前置校验和注册中心的前置校验会同时生效，
     * 不会出现「注册中心说不需要工作区但工具内部说需要」的错位。
     */
    public static boolean needsWorkspaceStatic(String name) {
        return "apk_info".equals(name) || "apk_list_assets".equals(name)
                || "apk_extract".equals(name) || "search_in_apk".equals(name)
                || "list_workspace".equals(name);
    }

    // ---------------- 路径安全 ----------------

    /**
     * 把相对路径解析到根目录下，并拒绝任何越界。
     * 这是防止模型写出 ../../ 把文件写到别处去的关键。
     */
    private static File safeResolve(File root, String rel) {
        if (root == null) {
            return null;
        }
        String clean = rel == null ? "" : rel.replace('\\', '/').trim();
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        File target = new File(root, clean);
        try {
            String rootPath = root.getCanonicalPath();
            String targetPath = target.getCanonicalPath();
            if (!targetPath.equals(rootPath)
                    && !targetPath.startsWith(rootPath + File.separator)) {
                return null;
            }
            return target;
        } catch (Throwable t) {
            return null;
        }
    }

    private static File rootOf(Workspace ws, Context ctx) {
        if (ws == null || ctx == null) {
            return null;
        }
        return ws.dir(ctx);
    }

    // ---------------- 文件工具实现 ----------------

    private Result doListWorkspace(Workspace ws, Context ctx) {
        File root = rootOf(ws, ctx);
        if (root == null || !root.isDirectory()) {
            return Result.fail("工作区目录不存在");
        }
        return Result.ok(describeDir(root, 200));
    }

    private Result doListDir(Workspace ws, Context ctx, String rel) {
        File root = rootOf(ws, ctx);
        File dir = safeResolve(root, rel);
        if (dir == null) {
            return Result.fail("路径越界或非法: " + rel);
        }
        if (!dir.exists()) {
            return Result.fail("目录不存在: " + rel);
        }
        if (!dir.isDirectory()) {
            return Result.fail("不是目录: " + rel);
        }
        return Result.ok(describeDir(dir, 300));
    }

    private static String describeDir(File dir, int limit) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return "（无法读取目录）";
        }
        List<String> dirs = new ArrayList<>();
        List<String> files = new ArrayList<>();
        for (File f : kids) {
            String line = f.getName() + (f.isDirectory() ? "/" : "  " + f.length() + "B");
            if (f.isDirectory()) {
                dirs.add(line);
            } else {
                files.add(line);
            }
        }
        java.util.Collections.sort(dirs);
        java.util.Collections.sort(files);
        StringBuilder sb = new StringBuilder();
        sb.append(dir.getName()).append("/  (")
                .append(dirs.size()).append(" 目录, ")
                .append(files.size()).append(" 文件)\n");
        int n = 0;
        for (String s : dirs) {
            if (n++ >= limit) {
                sb.append("... (已截断)\n");
                break;
            }
            sb.append("[D] ").append(s).append('\n');
        }
        for (String s : files) {
            if (n++ >= limit) {
                sb.append("... (已截断)\n");
                break;
            }
            sb.append("[F] ").append(s).append('\n');
        }
        return sb.toString();
    }

    private Result doReadText(Workspace ws, Context ctx, String rel, int maxChars) {
        File root = rootOf(ws, ctx);
        File f = safeResolve(root, rel);
        if (f == null) {
            return Result.fail("路径越界或非法: " + rel);
        }
        if (!f.isFile()) {
            return Result.fail("文件不存在: " + rel);
        }
        if (f.length() > 8L * 1024 * 1024) {
            return Result.fail("文件过大（" + f.length() + "B），请用其他方式处理");
        }
        try {
            byte[] data = readAll(new java.io.FileInputStream(f));
            String text = new String(data, "UTF-8");
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars) + "\n... (已截断，共 "
                        + text.length() + " 字符)";
            }
            return Result.ok(text);
        } catch (Throwable t) {
            return Result.fail("读取失败: " + t.getMessage());
        }
    }

    private Result doWriteText(Workspace ws, Context ctx, String rel, String content) {
        File root = rootOf(ws, ctx);
        File f = safeResolve(root, rel);
        if (f == null) {
            return Result.fail("路径越界或非法: " + rel);
        }
        if (content == null) {
            content = "";
        }
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(content.getBytes("UTF-8"));
                out.flush();
            }
            return Result.ok("已写入 " + f.length() + " 字节到 " + rel);
        } catch (Throwable t) {
            return Result.fail("写入失败: " + t.getMessage());
        }
    }

    private Result doDelete(Workspace ws, Context ctx, String rel) {
        if (rel == null || rel.trim().isEmpty() || "/".equals(rel.trim())) {
            return Result.fail("拒绝删除工作区根目录");
        }
        File root = rootOf(ws, ctx);
        File f = safeResolve(root, rel);
        if (f == null) {
            return Result.fail("路径越界或非法: " + rel);
        }
        if (!f.exists()) {
            return Result.fail("不存在: " + rel);
        }
        Workspace.deleteRecursive(f);
        return Result.ok("已删除 " + rel);
    }

    // ---------------- APK 工具实现 ----------------

    private File apkOf(Workspace ws, Context ctx) {
        if (ws == null || ctx == null) {
            return null;
        }
        File apk = ws.apk(ctx);
        return apk.isFile() ? apk : null;
    }

    private Result doApkInfo(Workspace ws, Context ctx) {
        File apk = apkOf(ws, ctx);
        if (apk == null) {
            return Result.fail("游戏包不存在");
        }
        StringBuilder sb = new StringBuilder();
        try (ZipFile zip = new ZipFile(apk)) {
            sb.append("APK: ").append(apk.getName())
                    .append(" (").append(apk.length()).append("B)\n\n");
            java.util.TreeSet<String> topAssets = new java.util.TreeSet<>();
            List<String> dex = new ArrayList<>();
            List<String> so = new ArrayList<>();
            java.util.Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                String n = e.getName();
                if (n.startsWith("assets/") && n.length() > 7) {
                    String rest = n.substring(7);
                    int slash = rest.indexOf('/');
                    topAssets.add(slash < 0 ? rest : rest.substring(0, slash));
                }
                if (n.indexOf('/') < 0 && n.startsWith("classes") && n.endsWith(".dex")) {
                    dex.add(n + "  " + e.getSize() + "B");
                }
                if (n.startsWith("lib/") && n.endsWith(".so")) {
                    so.add(n);
                }
            }
            sb.append("assets 顶层（").append(topAssets.size()).append("）:\n");
            for (String s : topAssets) {
                sb.append("  ").append(s).append('\n');
            }
            sb.append("\nDEX（").append(dex.size()).append("）:\n");
            for (String s : dex) {
                sb.append("  ").append(s).append('\n');
            }
            sb.append("\nSO（").append(so.size()).append("）:\n");
            for (String s : so) {
                sb.append("  ").append(s).append('\n');
            }
        } catch (Throwable t) {
            return Result.fail("读取 APK 失败: " + t.getMessage());
        }
        return Result.ok(sb.toString());
    }

    private Result doApkListAssets(Workspace ws, Context ctx, String rel, int limit) {
        File apk = apkOf(ws, ctx);
        if (apk == null) {
            return Result.fail("游戏包不存在");
        }
        String prefix = "assets/";
        String clean = rel == null ? "" : rel.replace('\\', '/').trim();
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        if (!clean.isEmpty()) {
            prefix = prefix + clean + (clean.endsWith("/") ? "" : "/");
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        try (ZipFile zip = new ZipFile(apk)) {
            java.util.TreeSet<String> names = new java.util.TreeSet<>();
            java.util.Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                String n = e.getName();
                if (!n.startsWith(prefix) || n.length() == prefix.length()) {
                    continue;
                }
                String rest = n.substring(prefix.length());
                int slash = rest.indexOf('/');
                names.add(slash < 0 ? rest : rest.substring(0, slash) + "/");
            }
            sb.append(prefix).append(" 下共 ").append(names.size()).append(" 项:\n");
            for (String s : names) {
                if (count++ >= limit) {
                    sb.append("... (已截断)\n");
                    break;
                }
                sb.append("  ").append(s).append('\n');
            }
        } catch (Throwable t) {
            return Result.fail("读取 APK 失败: " + t.getMessage());
        }
        return Result.ok(sb.toString());
    }

    private Result doApkExtract(Workspace ws, Context ctx, String assetPath, String outPath) {
        File apk = apkOf(ws, ctx);
        if (apk == null) {
            return Result.fail("游戏包不存在");
        }
        if (assetPath == null || outPath == null) {
            return Result.fail("缺少参数");
        }
        File root = rootOf(ws, ctx);
        File out = safeResolve(root, outPath);
        if (out == null) {
            return Result.fail("输出路径越界: " + outPath);
        }
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry e = zip.getEntry(assetPath);
            if (e == null) {
                return Result.fail("APK 内不存在: " + assetPath);
            }
            File parent = out.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (InputStream in = zip.getInputStream(e);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
            }
            return Result.ok("已提取 " + assetPath + " -> " + outPath
                    + " (" + out.length() + "B)");
        } catch (Throwable t) {
            return Result.fail("提取失败: " + t.getMessage());
        }
    }

    private Result doSearchInApk(Workspace ws, Context ctx, String keyword, String assetDir, int limit) {
        File apk = apkOf(ws, ctx);
        if (apk == null) {
            return Result.fail("游戏包不存在");
        }
        if (keyword == null || keyword.isEmpty()) {
            return Result.fail("缺少关键词");
        }
        String prefix = "assets/";
        if (assetDir != null && !assetDir.trim().isEmpty()) {
            prefix = prefix + assetDir.replace('\\', '/').trim() + "/";
        }
        StringBuilder sb = new StringBuilder();
        int hits = 0;
        int scanned = 0;
        try (ZipFile zip = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements() && hits < limit) {
                ZipEntry e = it.nextElement();
                String n = e.getName();
                if (!n.startsWith(prefix) || e.isDirectory()) {
                    continue;
                }
                if (!(n.endsWith(".json") || n.endsWith(".txt")
                        || n.endsWith(".ini") || n.endsWith(".csv"))) {
                    continue;
                }
                if (e.getSize() > 2L * 1024 * 1024) {
                    continue;
                }
                scanned++;
                String text;
                try (InputStream in = zip.getInputStream(e)) {
                    text = new String(readAll(in), "UTF-8");
                } catch (Throwable skip) {
                    continue;
                }
                int idx = text.indexOf(keyword);
                if (idx < 0) {
                    continue;
                }
                hits++;
                int from = Math.max(0, idx - 60);
                int to = Math.min(text.length(), idx + keyword.length() + 60);
                String snippet = text.substring(from, to).replace('\n', ' ');
                sb.append(n).append("\n    ...").append(snippet).append("...\n");
            }
            sb.append("\n扫描 ").append(scanned).append(" 个文本资源，命中 ")
                    .append(hits).append(" 处\n");
        } catch (Throwable t) {
            return Result.fail("搜索失败: " + t.getMessage());
        }
        return Result.ok(sb.toString());
    }

    private Result doDexList(Context ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx != null) {
            File dexDir = new File(ctx.getCodeCacheDir(), "game_dex");
            if (dexDir.isDirectory()) {
                File[] files = dexDir.listFiles();
                if (files != null) {
                    java.util.Arrays.sort(files);
                    sb.append("游戏 DEX（").append(files.length).append("）:\n");
                    for (File f : files) {
                        sb.append("  ").append(f.getName())
                                .append("  ").append(f.length()).append("B")
                                .append(f.canWrite() ? " 可写" : " 只读")
                                .append('\n');
                    }
                }
            }
        }
        if (workspacePatches != null && workspacePatches.isDirectory()) {
            File[] pats = workspacePatches.listFiles();
            if (pats != null) {
                sb.append("\n补丁 DEX（").append(pats.length).append("）:\n");
                for (File f : pats) {
                    sb.append("  ").append(f.getName())
                            .append("  ").append(f.length()).append("B\n");
                }
            }
        }
        if (sb.length() == 0) {
            sb.append("尚未部署任何 DEX。");
        }
        return Result.ok(sb.toString());
    }

    // ============================================================
    // ToolProvider 适配层
    //
    // 旧实现返回 AgentTools.Result，新契约要求 ToolResult。两者结构相同，
    // 这里做一层薄转换，让 11 个内置工具无需改动就能挂到新注册中心上。
    // ============================================================

    @Override
    public ToolResult execute(String name, Map<String, Object> args,
                             Workspace ws, Context ctx) {
        Result r = execLegacy(name, args, ws, ctx);
        return r.ok ? ToolResult.ok(r.text) : ToolResult.fail(r.text);
    }

    /** 由外部注入当前工作区补丁目录，便于 dex_list 展示。 */
    private volatile File workspacePatches;
    public void setPatchesDir(File dir) {
        this.workspacePatches = dir;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}