package com.superaoh.core.tool.builtin;

import android.content.Context;

import com.superaoh.core.Workspace;
import com.superaoh.core.sandbox.UbuntuSandbox;
import com.superaoh.core.tool.ToolRegistry;
import com.superaoh.core.tool.ToolResult;
import com.superaoh.core.tool.ToolSpec;
import com.superaoh.core.toolchain.ToolchainManager;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 反编译工具链（apktool / baksmali / smali / jadx）。
 *
 * 设计约束全部来自真机实测，不是照抄文档：
 *
 *   1. jadx 对 255MB 的游戏包做全量反编译，跑满 30 分钟仍未收敛，
 *      并写出至少 1.5GB 中间产物。所以这里必须按「单个 dex」调用，
 *      并且强制设超时上限——不能让一次工具调用把设备磁盘写满。
 *      （单 dex 实测：9MB / 1863 个类，126 秒，产出 2413 个 java 文件）
 *
 *   2. apktool 对同一个包可以完整解包成功，多 dex、res、assets、lib 都在。
 *
 *   3. baksmali 对同一个包可以成功反汇编出 6056 个 smali 文件。
 *
 *   3 者都跑在 Ubuntu proot 沙箱里，因为它们是 JVM 程序，塞进 App 进程
 *   会和 ART 抢内存，且 NIO/临时文件容易被 SELinux 拦。
 *
 * 安全边界：
 *   · 所有输入路径都必须落在当前工作区内（safeResolve 校验，防越界）
 *   · 输出目录同样限制在工作区内
 *   · 不提供任意 CLI 参数透传：只接受白名单参数，其余一律由工具拼装
 */
public final class ReverseTools {

    private ReverseTools() {
    }

    public static void installAll(ToolRegistry registry, UbuntuSandbox sandbox, Context ctx) {
        if (registry == null || sandbox == null || ctx == null) return;
        ToolchainManager toolchain = new ToolchainManager(ctx, sandbox);
        registry.register(new ToolchainStatus(toolchain, sandbox));
        registry.register(new ToolchainDeploy(toolchain, sandbox));
        registry.register(new ApktoolDecode(toolchain, sandbox));
        registry.register(new ApktoolBuild(toolchain, sandbox));
        registry.register(new Baksmali(toolchain, sandbox));
        registry.register(new Smali(toolchain, sandbox));
        registry.register(new Jadx(toolchain, sandbox));
    }

    // ==================================================================
    // 公共基类
    // ==================================================================

    private abstract static class Base implements ToolSpec {
        final ToolchainManager toolchain;
        final UbuntuSandbox sandbox;

        Base(ToolchainManager toolchain, UbuntuSandbox sandbox) {
            this.toolchain = toolchain;
            this.sandbox = sandbox;
        }

        @Override public String source() { return ToolRegistry.SRC_BUILTIN; }
        @Override public boolean needsWorkspace() { return true; }

        /**
         * 默认无参工具。Baksmali 等需要参数的子类自行覆写。
         *
         * ToolSpec 把 required() 声明为抽象方法（不是 default），所以这里必须给出实现；
         * 漏掉的直接后果是「整类不是抽象类却没实现抽象方法」的编译错误。
         */
        @Override public List<String> required() { return new ArrayList<>(); }

        /** 默认空参数表。需要参数的子类自行覆写。 */
        @Override public Map<String, String> params() { return new LinkedHashMap<>(); }

        /** 统一前置检查：沙箱可用 + 工具链已部署，否则给出可操作提示。 */
        ToolResult preflight() {
            if (!UbuntuSandbox.isSupported()) {
                return ToolResult.fail("当前设备架构不支持 Ubuntu 沙箱（需 arm64-v8a）。");
            }
            if (!sandbox.isInstalled()) {
                return ToolResult.fail("Ubuntu 沙箱尚未安装。请先到「设置 → 沙箱」安装，"
                        + "首次约需 1~3 分钟。");
            }
            if (!toolchain.isReady()) {
                // 不要说「请调用 toolchain_deploy」——启动后工具链会自动部署，
                // 让模型去手动触发只会白跑一次。这里给出真实的当前状态。
                String why = toolchain.pendingReason();
                return ToolResult.fail(why != null ? why : "反编译工具链尚未就绪，请稍后重试。");
            }
            return null;
        }
    }

    // ==================================================================
    // toolchain_status / toolchain_deploy
    // ==================================================================

    static final class ToolchainStatus extends Base {
        ToolchainStatus(ToolchainManager t, UbuntuSandbox s) { super(t, s); }

        @Override public String name() { return "toolchain_status"; }

        @Override public String description() {
            return "查询内置反编译工具链（apktool / baksmali / smali / jadx）的部署状态与体积。"
                    + "在第一次使用反编译工具前先查，未部署则调用 toolchain_deploy。";
        }

        @Override public boolean needsWorkspace() { return false; }

        @Override public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            if (toolchain == null) return ToolResult.fail("未注入沙箱");
            return ToolResult.ok(toolchain.describe()
                    + "\n\nUbuntu 沙箱: " + (sandbox.isInstalled() ? "已安装" : "未安装"));
        }
    }

    static final class ToolchainDeploy extends Base {
        ToolchainDeploy(ToolchainManager t, UbuntuSandbox s) { super(t, s); }

        @Override public String name() { return "toolchain_deploy"; }

        @Override public String description() {
            return "部署内置反编译工具链到 Ubuntu 沙箱。首次约释放 161MB，耗时 1~2 分钟。"
                    + "已部署时直接返回。部署完成后 apktool/baksmali/smali/jadx 系列工具才可用。";
        }

        @Override public boolean needsWorkspace() { return false; }

        @Override public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            if (toolchain == null) return ToolResult.fail("未注入沙箱");
            if (!sandbox.isInstalled()) {
                return ToolResult.fail("Ubuntu 沙箱尚未安装，无法部署工具链。");
            }
            try {
                toolchain.deploy(null);
                return ToolResult.ok("工具链部署完成。\n" + toolchain.describe());
            } catch (Throwable t) {
                return ToolResult.fail("部署失败: " + t.getMessage());
            }
        }
    }

    // ==================================================================
    // apktool_decode / apktool_build
    // ==================================================================

    static final class ApktoolDecode extends Base {
        ApktoolDecode(ToolchainManager t, UbuntuSandbox s) { super(t, s); }

        @Override public String name() { return "apktool_decode"; }

        @Override public String description() {
            return "用 apktool 反编译工作区内的 APK：解出 AndroidManifest.xml、res、assets、smali 目录。"
                    + "实测对 255MB 的多 dex 游戏包可完整解包。"
                    + "不传 outputDir 时默认解码到 <apk所在目录>/<apk名>_apktool。";
        }

        @Override public Map<String, String> params() {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("apk", "工作区内 APK 的相对路径");
            p.put("outputDir", "可选，工作区内的输出目录相对路径");
            return p;
        }

        @Override public List<String> required() {
            List<String> r = new ArrayList<>();
            r.add("apk");
            return r;
        }

        @Override public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            ToolResult pre = preflight();
            if (pre != null) return pre;
            File root = ws.dir(ctx);
            File apk = safeResolve(root, str(args, "apk"));
            if (apk == null || !apk.isFile()) return ToolResult.fail("APK 不存在或路径越界");

            String outRel = str(args, "outputDir");
            File out;
            if (outRel.isEmpty()) {
                out = new File(apk.getParentFile(), stripExt(apk.getName()) + "_apktool");
            } else {
                out = safeResolve(root, outRel);
            }
            if (out == null) return ToolResult.fail("输出目录越界");

            List<String> cli = new ArrayList<>();
            cli.add("d");
            cli.add("-f");
            cli.add(apk.getAbsolutePath());
            cli.add("-o");
            cli.add(out.getAbsolutePath());

            String cmd = toolchain.buildCommand("apktool", cli, 1800);
            // apktool 解包大包可能超过 2 分钟，给 10 分钟上限
            return run(sandbox, cmd, 600000, "apktool 反编译", out);
        }
    }

    static final class ApktoolBuild extends Base {
        ApktoolBuild(ToolchainManager t, UbuntuSandbox s) { super(t, s); }

        @Override public String name() { return "apktool_build"; }

        @Override public String description() {
            return "用 apktool 把已反编译的目录重新打包成 APK。"
                    + "产物未签名，需要用 apksigner 或工具链里的 jarsigner 再签名后才能安装。";
        }

        @Override public Map<String, String> params() {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("projectDir", "工作区内 apktool 工程目录的相对路径");
            p.put("outputApk", "工作区内的输出 APK 相对路径");
            return p;
        }

        @Override public List<String> required() {
            List<String> r = new ArrayList<>();
            r.add("projectDir");
            r.add("outputApk");
            return r;
        }

        @Override public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            ToolResult pre = preflight();
            if (pre != null) return pre;
            File root = ws.dir(ctx);
            File dir = safeResolve(root, str(args, "projectDir"));
            if (dir == null || !dir.isDirectory()) return ToolResult.fail("工程目录不存在或越界");
            File out = safeResolve(root, str(args, "outputApk"));
            if (out == null) return ToolResult.fail("输出路径越界");
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();

            List<String> cli = new ArrayList<>();
            cli.add("b");
            cli.add("-f");
            cli.add(dir.getAbsolutePath());
            cli.add("-o");
            cli.add(out.getAbsolutePath());

            String cmd = toolchain.buildCommand("apktool", cli, 1800);
            return run(sandbox, cmd, 600000, "apktool 打包", out);
        }
    }

    // ==================================================================
    // baksmali / smali
    // ==================================================================

    static final class Baksmali extends Base {
        Baksmali(ToolchainManager t, UbuntuSandbox s) { super(t, s); }

        @Override public String name() { return "baksmali_disasm"; }

        @Override public String description() {
            return "用 baksmali 把 dex/APK 反汇编成 smali 文本。"
                    + "实测对 255MB 游戏包可产出 6056 个 smali 文件。"
                    + "适合定位类、改方法体、比对补丁。";
        }

        @Override public Map<String, String> params() {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("input", "工作区内的 dex 或 APK 相对路径");
            p.put("outputDir", "工作区内的输出目录相对路径");
            return p;
        }

        @Override public List<String> required() {
            List<String> r = new ArrayList<>();
            r.add("input");
            r.add("outputDir");
            return r;
        }

        @Override public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            ToolResult pre = preflight();
            if (pre != null) return pre;
            File root = ws.dir(ctx);
            File in = safeResolve(root, str(args, "input"));
            if (in == null || !in.isFile()) return ToolResult.fail("输入文件不存在或越界");
            File out = safeResolve(root, str(args, "outputDir"));
            if (out == null) return ToolResult.fail("输出目录越界");
            out.mkdirs();

            List<String> cli = new ArrayList<>();
            cli.add("d");
            cli.add(in.getAbsolutePath());
            cli.add("-o");
            cli.add(out.getAbsolutePath());

            String cmd = toolchain.buildCommand("baksmali", cli, 1500);
            return run(sandbox, cmd, 900000, "baksmali 反汇编", out);
        }
    }

    static final class Smali extends Base {
        Smali(ToolchainManager t, UbuntuSandbox s) { super(t, s); }

        @Override public String name() { return "smali_asm"; }

        @Override public String description() {
            return "用 smali 把 smali 目录重新汇编成 dex。"
                    + "通常用于：baksmali 反汇编 → 修改 smali → smali 汇编 → 放回 APK。";
        }

        @Override public Map<String, String> params() {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("inputDir", "工作区内的 smali 目录相对路径");
            p.put("outputDex", "工作区内的输出 dex 相对路径");
            return p;
        }

        @Override public List<String> required() {
            List<String> r = new ArrayList<>();
            r.add("inputDir");
            r.add("outputDex");
            return r;
        }

        @Override public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            ToolResult pre = preflight();
            if (pre != null) return pre;
            File root = ws.dir(ctx);
            File dir = safeResolve(root, str(args, "inputDir"));
            if (dir == null || !dir.isDirectory()) return ToolResult.fail("smali 目录不存在或越界");
            File out = safeResolve(root, str(args, "outputDex"));
            if (out == null) return ToolResult.fail("输出路径越界");
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();

            List<String> cli = new ArrayList<>();
            cli.add("a");
            cli.add(dir.getAbsolutePath());
            cli.add("-o");
            cli.add(out.getAbsolutePath());

            String cmd = toolchain.buildCommand("smali", cli, 1500);
            return run(sandbox, cmd, 600000, "smali 汇编", out);
        }
    }

    // ==================================================================
    // jadx（必须按单 dex 调用）
    // ==================================================================

    static final class Jadx extends Base {
        Jadx(ToolchainManager t, UbuntuSandbox s) { super(t, s); }

        @Override public String name() { return "jadx_decompile"; }

        @Override public String description() {
            return "用 jadx 把 dex/APK 反编译成 Java 源码，便于阅读业务逻辑。"
                    + "重要限制（真机实测）：只接受单个 dex 文件或小体积 APK；"
                    + "对 255MB 的多 dex 大包做全量反编译会跑满 30 分钟并写出 1.5GB，"
                    + "因此本工具强制 900 秒超时，超时会中止并保留已产出部分。"
                    + "建议先用 archive_list 或 apktool_decode 取出单个 classes*.dex 再调用。";
        }

        @Override public Map<String, String> params() {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("input", "工作区内的 dex 或 APK 相对路径（建议单个 dex）");
            p.put("outputDir", "工作区内的输出目录相对路径");
            p.put("classes", "可选，逗号分隔的类名白名单，例如 a.b.C；留空表示全部");
            return p;
        }

        @Override public List<String> required() {
            List<String> r = new ArrayList<>();
            r.add("input");
            r.add("outputDir");
            return r;
        }

        @Override public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            ToolResult pre = preflight();
            if (pre != null) return pre;
            File root = ws.dir(ctx);
            File in = safeResolve(root, str(args, "input"));
            if (in == null || !in.isFile()) return ToolResult.fail("输入文件不存在或越界");
            File out = safeResolve(root, str(args, "outputDir"));
            if (out == null) return ToolResult.fail("输出目录越界");
            out.mkdirs();

            long size = in.length();
            String warn = "";
            if (size > 64L * 1024 * 1024) {
                warn = "\n注意：输入 " + (size / (1024 * 1024)) + "MB 偏大，"
                        + "jadx 可能需要很长时间，超时会被中止。\n";
            }

            List<String> cli = new ArrayList<>();
            cli.add("-d");
            cli.add(out.getAbsolutePath());
            cli.add("--no-res");
            cli.add("--threads-count");
            cli.add("2");
            String classes = str(args, "classes").trim();
            if (!classes.isEmpty()) {
                for (String c : classes.split(",")) {
                    String t = c.trim();
                    if (t.isEmpty()) continue;
                    // 类名白名单：只允许常见标识符字符，防止拼出额外 CLI 参数
                    if (!t.matches("[A-Za-z0-9_$.]+")) {
                        return ToolResult.fail("非法类名: " + t);
                    }
                    cli.add("--single-class");
                    cli.add(t);
                }
            }
            cli.add(in.getAbsolutePath());

            String cmd = toolchain.buildCommand("jadx", cli, 1800);
            ToolResult r = run(sandbox, cmd, 900000, "jadx 反编译", out);
            if (warn.isEmpty()) return r;
            return r.ok ? ToolResult.ok(warn + r.text) : ToolResult.fail(warn + r.text);
        }
    }

    // ==================================================================
    // 执行与结果汇总
    // ==================================================================

    private static ToolResult run(UbuntuSandbox sandbox, String command,
                                  long timeoutMs, String label, File produce) {
        try {
            long started = System.currentTimeMillis();
            UbuntuSandbox.ExecResult result = sandbox.exec(command, (int) timeoutMs);
            long elapsed = System.currentTimeMillis() - started;

            StringBuilder sb = new StringBuilder();
            sb.append(label).append(" 完成，耗时 ")
                    .append(elapsed / 1000).append(" 秒\n");
            sb.append("退出码: ").append(result.exitCode)
                    .append(result.ok() ? "（成功）" : "（失败）").append('\n');
            sb.append("产出目录: ").append(produce.getAbsolutePath()).append('\n');
            sb.append("产出统计: ").append(describeProduce(produce)).append('\n');

            String output = result.combined();
            if (output != null && !output.trim().isEmpty()) {
                sb.append("\n──── 工具输出 ────\n");
                // 截断，避免把上下文挤爆
                if (output.length() > 6000) {
                    sb.append(output.substring(0, 6000))
                            .append("\n…（输出过长已截断，共 ")
                            .append(output.length()).append(" 字符）");
                } else {
                    sb.append(output);
                }
            }
            return result.ok() ? ToolResult.ok(sb.toString()) : ToolResult.fail(sb.toString());
        } catch (Throwable t) {
            return ToolResult.fail(label + " 执行异常: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** 统计产出目录里的文件数与体积，让模型知道到底产出了什么。 */
    private static String describeProduce(File dir) {
        if (dir == null || !dir.exists()) return "无";
        if (dir.isFile()) {
            return dir.getName() + " " + humanSize(dir.length());
        }
        int files = 0;
        long bytes = 0;
        java.util.ArrayDeque<File> pending = new java.util.ArrayDeque<>();
        pending.push(dir);
        int guard = 0;
        while (!pending.isEmpty() && guard++ < 200000) {
            File f = pending.pop();
            File[] kids = f.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (k.isDirectory()) pending.push(k);
                else {
                    files++;
                    bytes += k.length();
                }
            }
        }
        return files + " 个文件 / " + humanSize(bytes);
    }

    private static String humanSize(long n) {
        if (n < 1024) return n + "B";
        if (n < 1024 * 1024) return String.format(java.util.Locale.US, "%.1fKB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1fMB", n / (1024.0 * 1024));
        return String.format(java.util.Locale.US, "%.2fGB", n / (1024.0 * 1024 * 1024));
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static File safeResolve(File root, String rel) {
        if (root == null) return null;
        String clean = rel == null ? "" : rel.replace('\\', '/').trim();
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.isEmpty()) return null;
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

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static String str(Map<String, Object> args, String key) {
        if (args == null) return "";
        Object v = args.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }
}