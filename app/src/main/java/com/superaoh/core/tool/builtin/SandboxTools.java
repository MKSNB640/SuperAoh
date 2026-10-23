package com.superaoh.core.tool.builtin;

import android.content.Context;

import com.superaoh.core.Workspace;
import com.superaoh.core.sandbox.UbuntuSandbox;
import com.superaoh.core.tool.ToolRegistry;
import com.superaoh.core.tool.ToolResult;
import com.superaoh.core.tool.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ---------------------------------------------------------------------------
// 沙箱终端工具
//
// 对应 Operit 的 super_admin:terminal。区别在于：
//   Operit 的终端是「常驻会话 + PTY」，能持续交互；
//   这里每次调用起一个 proot 进程，进程退出即结束。
//
// 为什么先不做常驻会话：
//   常驻 PTY 要维护 fd、处理信号、防止进程泄漏，复杂度高。
//   而 Agent 的实际用法是「跑一条命令看结果」，一次性执行完全够用。
//   需要交互的场景（比如 vim）在 Agent 里本来就用不上。
//
// 但保留了 workdir 参数：模型经常需要「先 cd 再跑」，不保留的话它得
// 每次都写 cd xxx && ...，容易出错。
// ---------------------------------------------------------------------------

public final class SandboxTools {

    private SandboxTools() {
    }

    /** 统一装配。 */
    public static void installAll(ToolRegistry reg, UbuntuSandbox sandbox) {
        if (reg == null || sandbox == null) return;
        reg.register(new Terminal(sandbox, false));
        reg.register(new Terminal(sandbox, true));
        reg.register(new SandboxStatus(sandbox));
    }

    // ------------------------------------------------------------------
    // terminal / terminal_sudo
    // ------------------------------------------------------------------

    /**
     * 在 Ubuntu 沙箱里执行命令。
     *
     * 注册两份：
     *   terminal       —— 普通执行
     *   terminal_root  —— 语义上「以 root 跑」，实际 proot 已 -0 伪造 root，
     *                     两者权限相同。保留两份是为了让模型对 operit 的
     *                     terminal/shell 双工具习惯有对应物，减少它反复试错。
     */
    static final class Terminal implements ToolSpec {

        private final UbuntuSandbox sandbox;
        private final boolean rootAlias;

        Terminal(UbuntuSandbox sandbox, boolean rootAlias) {
            this.sandbox = sandbox;
            this.rootAlias = rootAlias;
        }

        @Override
        public String name() {
            return rootAlias ? "terminal_root" : "terminal";
        }

        @Override
        public String description() {
            String base = "在 Ubuntu 24.04 沙箱里执行 shell 命令。"
                    + "具体可用命令以当前 rootfs 实测为准；先调用 sandbox_capabilities 查询工具链与磁盘空间，"
                    + "不要假定未探测的程序已安装。可通过 apt/pip 联网安装（受设备磁盘空间与网络限制）。"
                    + "工作目录默认 /root，/sdcard 指向手机存储。"
                    + "适合：跑脚本、处理文件、编译代码、抓网页。";
            if (rootAlias) {
                base += "（此工具与 terminal 等价，沙箱内已是 root 身份）";
            }
            return base;
        }

        @Override
        public Map<String, String> params() {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("command", "要执行的 shell 命令。多条命令用 && 或 ; 连接。");
            p.put("timeoutMs", "超时毫秒数，默认 120000（2 分钟）。编译等长任务可加大。");
            return p;
        }

        @Override
        public List<String> required() {
            List<String> r = new ArrayList<>();
            r.add("command");
            return r;
        }

        @Override
        public String source() {
            return ToolRegistry.SRC_BUILTIN;
        }

        @Override
        public boolean needsWorkspace() {
            return false;
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            String cmd = str(args, "command");
            if (cmd.trim().isEmpty()) {
                return ToolResult.fail("command 不能为空");
            }

            if (!UbuntuSandbox.isSupported()) {
                return ToolResult.fail("当前设备架构不支持 Ubuntu 沙箱（仅 arm64-v8a）。");
            }
            if (!sandbox.isInstalled()) {
                return ToolResult.fail("沙箱尚未安装。请到「设置 → 沙箱」点安装，"
                        + "首次约需 1~3 分钟（解压 300MB 根文件系统）。");
            }

            long timeout = num(args, "timeoutMs", 120000L);
            // 上限 20 分钟，防止模型传个天文数字把线程挂死
            if (timeout < 3000) timeout = 3000;
            if (timeout > 20 * 60 * 1000L) timeout = 20 * 60 * 1000L;

            try {
                UbuntuSandbox.ExecResult r = sandbox.exec(cmd, timeout);
                String text = r.combined();
                // 输出过长会挤爆上下文，截断并提示
                if (text.length() > 24000) {
                    text = text.substring(0, 24000) + "\n…（输出过长已截断，共 "
                            + text.length() + " 字符）";
                }
                return r.ok() ? ToolResult.ok(text) : ToolResult.fail(text);
            } catch (Throwable t) {
                return ToolResult.fail("沙箱执行失败: " + t.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // sandbox_status
    // ------------------------------------------------------------------

    /** 报告沙箱状态，模型在不确定环境时可以先问一句。 */
    static final class SandboxStatus implements ToolSpec {

        private final UbuntuSandbox sandbox;

        SandboxStatus(UbuntuSandbox sandbox) {
            this.sandbox = sandbox;
        }

        @Override
        public String name() {
            return "sandbox_status";
        }

        @Override
        public String description() {
            return "查询 Ubuntu 沙箱是否可用、占用多少空间、设备是否支持。"
                    + "在调用 terminal 之前不确定环境时可以先查。";
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
            return ToolRegistry.SRC_BUILTIN;
        }

        @Override
        public boolean needsWorkspace() {
            return false;
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            StringBuilder sb = new StringBuilder();
            sb.append("设备支持: ").append(UbuntuSandbox.isSupported() ? "是" : "否（需 arm64-v8a）");
            sb.append('\n');
            sb.append("已安装: ").append(sandbox.isInstalled() ? "是" : "否");
            sb.append('\n');
            if (sandbox.isInstalled()) {
                sb.append("占用空间: ").append(UbuntuSandbox.human(sandbox.diskUsage()));
                sb.append('\n');
                sb.append("根目录: ").append(sandbox.rootfs().getAbsolutePath());
                sb.append('\n');
                sb.append("/sdcard 指向: ").append(sandbox.sharedStorageState());
            }
            return ToolResult.ok(sb.toString());
        }
    }

    // ------------------------------------------------------------------
    // 参数读取
    // ------------------------------------------------------------------

    private static String str(Map<String, Object> a, String key) {
        Object v = a.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static long num(Map<String, Object> a, String key, long def) {
        Object v = a.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Throwable t) {
            return def;
        }
    }
}