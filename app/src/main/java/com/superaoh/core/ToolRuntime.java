package com.superaoh.core;

import android.content.Context;
import android.util.Log;

import com.superaoh.core.mcp.McpManager;
import com.superaoh.core.plugin.PluginManager;
import com.superaoh.core.sandbox.UbuntuSandbox;
import com.superaoh.core.skill.SkillManager;
import com.superaoh.core.toolchain.ToolchainManager;
import com.superaoh.core.tool.BuiltinToolAdapter;
import com.superaoh.core.tool.ToolRegistry;
import com.superaoh.core.tool.builtin.EngineeringTools;
import com.superaoh.core.tool.builtin.GameTools;
import com.superaoh.core.tool.builtin.ReverseTools;
import com.superaoh.core.tool.builtin.SandboxTools;

// ---------------------------------------------------------------------------
// 工具运行时（进程级单例）
//
// 把四类工具的装配集中到一处，避免「Activity 里 new 一个、Service 里又
// new 一个」导致注册中心分裂。Agent 拿到的永远是同一份工具表。
//
// 装配顺序有讲究：
//   1. 内置（AgentTools 适配器）—— 必须最先，MCP/插件可能覆盖同名工具，
//      但内置的工具名都用了领域前缀（apk_/dex_/runtime_），实际不会撞。
//   2. 沙箱工具 —— terminal 等
//   3. 插件 —— 本地声明式，加载很快，失败不影响别的
//   4. Skill —— 可能注册工具，也可能只注入提示词
//   5. MCP —— 最后，因为有网络 IO，可能慢甚至失败，不能挡住前面几步
//
// 单例的生命周期跟随进程。App 被杀就重来，所以不要在 initialize 里做
// 重活（比如装沙箱），那些应该是用户显式触发的。
// ---------------------------------------------------------------------------

public final class ToolRuntime {

    private static final String TAG = "ToolRuntime";

    private static volatile ToolRuntime instance;

    private final Context appCtx;
    private final ToolRegistry registry = new ToolRegistry();
    private final AgentTools builtin;
    private final UbuntuSandbox sandbox;
    private final ToolchainManager toolchain;
    private final McpManager mcp;
    private final PluginManager plugins;
    private final SkillManager skills;

    /** 是否已经连接过 MCP（避免每次启动重复联网）。 */
    private volatile boolean mcpConnectedOnce;

    /** 是否已经尝试过自动部署（避免重复触发）。 */
    private volatile boolean autoDeployStarted;

    private ToolRuntime(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.builtin = new AgentTools();
        this.sandbox = new UbuntuSandbox(appCtx);
        this.toolchain = new ToolchainManager(appCtx, sandbox);
        this.mcp = new McpManager(appCtx, registry);
        this.plugins = new PluginManager(appCtx, registry);
        this.skills = new SkillManager(appCtx, registry);

        // 1. 内置
        BuiltinToolAdapter.installAll(registry, builtin);
        // 2. 沙箱
        SandboxTools.installAll(registry, sandbox);
        // 3. 工程工具：工作区检索、归档检查、网络只读探测和真实能力报告
        EngineeringTools.installAll(registry, sandbox);
        // 仅允许白名单、用户授权的游戏输入操作。
        GameTools.installAll(registry);
        // 3.5 反编译工具链：apktool / baksmali / smali / jadx
        //     放在内置之后、插件之前，保证同名工具不会被插件悄悄替换。
        ReverseTools.installAll(registry, sandbox, appCtx);
        // 4. 插件（同步加载，本地 IO，很快）
        plugins.loadAll();
        // 5. 技能
        skills.loadAll();

        Log.i(TAG, "工具装配完成：" + registry.summary());

        // 工具链开箱即用：不要求用户/模型先手动调 toolchain_deploy。
        //
        // 为什么放后台线程：复制 161MB 会花 1~2 分钟，绝不能阻塞
        // ToolRuntime 的构造（那是 UI 首次打开聊天的关键路径）。
        // 失败也不影响其他工具，下次冷启动会重试（版本戳没写上就说明没成功）。
        startAutoDeploy();
    }

    /**
     * 后台自动部署反编译工具链。
     *
     * 幂等：已部署时 isReady() 直接返回，deploy() 内部立刻退出。
     * 只跑一次，避免每次 bindWorkspace 都触发。
     */
    private void startAutoDeploy() {
        if (autoDeployStarted) return;
        autoDeployStarted = true;
        Thread t = new Thread(() -> {
            try {
                if (!UbuntuSandbox.isSupported()) {
                    Log.i(TAG, "工具链跳过：设备架构不支持 Ubuntu 沙箱");
                    return;
                }
                if (toolchain.isReady()) {
                    Log.i(TAG, "工具链已就绪，无需部署");
                    return;
                }
                if (!sandbox.isInstalled()) {
                    // 沙箱没装时无法运行 jar，但文件可以先放好；
                    // 不过沙箱没装时 getExternalFilesDir 也可用，这里仍部署，
                    // 这样用户装完沙箱就能立刻用。
                    Log.i(TAG, "工具链部署：Ubuntu 沙箱未安装，先释放文件");
                }
                toolchain.deploy((stage, percent) ->
                        Log.i(TAG, "工具链部署 " + percent + "%：" + stage));
                Log.i(TAG, "工具链自动部署完成");
            } catch (Throwable e) {
                Log.w(TAG, "工具链自动部署失败（不影响其他工具）：" + e.getMessage());
            }
        }, "toolchain-autodeploy");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** 反编译工具链管理器（供工具与设置页查询状态）。 */
    public ToolchainManager toolchain() {
        return toolchain;
    }

    public static ToolRuntime get(Context ctx) {
        ToolRuntime local = instance;
        if (local == null) {
            synchronized (ToolRuntime.class) {
                local = instance;
                if (local == null) {
                    local = new ToolRuntime(ctx);
                    instance = local;
                }
            }
        }
        return local;
    }

    // ------------------------------------------------------------------
    // 访问器
    // ------------------------------------------------------------------

    public ToolRegistry registry() {
        return registry;
    }

    public UbuntuSandbox sandbox() {
        return sandbox;
    }

    public McpManager mcp() {
        return mcp;
    }

    public PluginManager plugins() {
        return plugins;
    }

    public SkillManager skills() {
        return skills;
    }

    /** 旧的 AgentTools 实例，仍需要 setPatchesDir。 */
    public AgentTools builtinTools() {
        return builtin;
    }

    /**
     * Agent 用的工具供给者。
     *
     * 直接返回注册中心 —— 它已实现 ToolProvider，能提供四项来源的全部工具。
     */
    public ToolRegistry provider() {
        return registry;
    }

    /** 绑定工作区（内置工具需要补丁目录）。 */
    public void bindWorkspace(Workspace ws) {
        if (ws == null) return;
        builtin.setPatchesDir(ws.patchesDir(appCtx));
    }

    // ------------------------------------------------------------------
    // 热重载
    // ------------------------------------------------------------------

    /** 只重载插件。用户改完插件文件后调用，不用重启 App。 */
    public int reloadPlugins() {
        return plugins.loadAll();
    }

    /** 只重载技能。 */
    public int reloadSkills() {
        return skills.loadAll();
    }

    /**
     * 连接全部 MCP 服务器。
     *
     * 必须放后台线程 —— 内部是同步 HTTP。首次调用才联网，
     * 之后要刷新得显式调 mcp().connectAllEnabled()。
     */
    public void ensureMcpConnected() {
        if (mcpConnectedOnce) return;
        mcpConnectedOnce = true;
        try {
            int ok = mcp.connectAllEnabled();
            Log.i(TAG, "MCP 连接完成，成功 " + ok + " 台");
        } catch (Throwable t) {
            Log.w(TAG, "MCP 连接失败: " + t.getMessage());
        }
    }

    /** 系统提示词里的工具摘要。 */
    public String summary() {
        return registry.summary();
    }

    /** 组装系统提示词里的能力说明块。 */
    public String capabilityBlock(String userText) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【工具能力】\n").append(registry.summary());

        // 沙箱可用性：明确告诉模型，避免它假装跑了命令
        if (sandbox.isInstalled()) {
            sb.append("\nUbuntu 沙箱：可用。terminal 工具可执行任意 shell 命令，")
                    .append("支持 apt/pip 联网安装。");
        } else {
            sb.append("\nUbuntu 沙箱：未安装。terminal 工具会报错，")
                    .append("请提示用户去设置里安装。");
        }

        // 触发到的技能
        String skillBlock = skills.buildPromptBlock(userText);
        if (!skillBlock.isEmpty()) {
            sb.append(skillBlock);
        }
        return sb.toString();
    }
}