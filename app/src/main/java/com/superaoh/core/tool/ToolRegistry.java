package com.superaoh.core.tool;

import android.content.Context;

import com.superaoh.core.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ---------------------------------------------------------------------------
// 工具注册中心
//
// 所有工具来源最终都往这里注册，Agent 只认识这个中心。四种来源：
//   1. 内置（BuiltinTools）—— 文件、APK、DEX、Shell、HTTP 等
//   2. MCP（McpManager）—— 从远程 MCP 服务器拉来的工具，运行时动态出现
//   3. 插件（PluginManager）—— 本地声明式插件提供的工具
//   4. Skill（SkillManager）—— 技能包，本质是「注入提示词 + 附带工具」
//
// 为什么用注册中心而不是让 Agent 直接持有各 manager：
//   Agent 的主循环要保持简单。它只需要问「现在有哪些工具」和「执行这个名字」，
//   不该关心 MCP 连没连上、插件加载了几个。
// ---------------------------------------------------------------------------

public final class ToolRegistry implements ToolProvider {

    /** 来源常量，同时用作 UI 分组标签。 */
    public static final String SRC_BUILTIN = "内置";
    public static final String SRC_MCP = "MCP";
    public static final String SRC_PLUGIN = "插件";
    public static final String SRC_SKILL = "Skill";

    /** 名字 -> 工具。用 LinkedHashMap 保持注册顺序，模型看到的工具列表稳定。 */
    private final Map<String, ToolSpec> tools = new LinkedHashMap<>();

    /** 动态来源的启用开关。关闭后该来源的工具不进入模型上下文。 */
    private boolean mcpEnabled = true;
    private boolean pluginsEnabled = true;
    private boolean skillsEnabled = true;

    // ------------------------------------------------------------------
    // 注册 / 注销
    // ------------------------------------------------------------------

    /**
     * 注册单个工具。同名会覆盖 —— 这让 MCP 服务器重连后能刷新工具定义。
     *
     * @return 是否新增（false 表示覆盖了已有同名工具）
     */
    public synchronized boolean register(ToolSpec tool) {
        if (tool == null || tool.name() == null || tool.name().isEmpty()) {
            return false;
        }
        return tools.put(tool.name(), tool) == null;
    }

    /** 批量注册。 */
    public synchronized void registerAll(List<? extends ToolSpec> list) {
        if (list == null) return;
        for (ToolSpec t : list) {
            register(t);
        }
    }

    /** 按名前缀移除。MCP 服务器断开时用 "mcp__<server>__" 前缀一次性清掉。 */
    public synchronized int unregisterByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return 0;
        List<String> hit = new ArrayList<>();
        for (String name : tools.keySet()) {
            if (name.startsWith(prefix)) {
                hit.add(name);
            }
        }
        for (String name : hit) {
            tools.remove(name);
        }
        return hit.size();
    }

    /** 按来源移除。 */
    public synchronized int unregisterBySource(String source) {
        List<String> hit = new ArrayList<>();
        for (Map.Entry<String, ToolSpec> e : tools.entrySet()) {
            if (source != null && source.equals(e.getValue().source())) {
                hit.add(e.getKey());
            }
        }
        for (String name : hit) {
            tools.remove(name);
        }
        return hit.size();
    }

    public synchronized void clear() {
        tools.clear();
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public synchronized int size() {
        return tools.size();
    }

    public synchronized ToolSpec get(String name) {
        return tools.get(name);
    }

    public synchronized boolean contains(String name) {
        return tools.containsKey(name);
    }

    /** 当前对模型可见的全部工具（受来源开关影响）。 */
    public synchronized List<ToolSpec> visibleTools() {
        List<ToolSpec> out = new ArrayList<>(tools.size());
        for (ToolSpec t : tools.values()) {
            if (isVisible(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** 全部工具，无视开关（设置页展示用）。 */
    public synchronized List<ToolSpec> allTools() {
        return new ArrayList<>(tools.values());
    }

    /** 按来源分组，设置页用。 */
    public synchronized Map<String, List<ToolSpec>> grouped() {
        Map<String, List<ToolSpec>> out = new LinkedHashMap<>();
        for (ToolSpec t : tools.values()) {
            String src = t.source() == null ? SRC_BUILTIN : t.source();
            List<ToolSpec> bucket = out.get(src);
            if (bucket == null) {
                bucket = new ArrayList<>();
                out.put(src, bucket);
            }
            bucket.add(t);
        }
        return out;
    }

    /** 转成 OpenAI tools 数组。 */
    public synchronized List<Object> toOpenAiTools() {
        List<Object> out = new ArrayList<>();
        for (ToolSpec t : tools.values()) {
            if (isVisible(t)) {
                out.add(t.toOpenAiSchema());
            }
        }
        return out;
    }

    private boolean isVisible(ToolSpec t) {
        String src = t.source();
        if (SRC_MCP.equals(src)) return mcpEnabled;
        if (SRC_PLUGIN.equals(src)) return pluginsEnabled;
        if (SRC_SKILL.equals(src)) return skillsEnabled;
        return true; // 内置始终可见
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    /**
     * 执行工具。
     *
     * 这里是唯一的执行入口。所有来源的工具都经过同样的前置检查：
     *   - 名字必须存在
     *   - 需要工作区但当前没有 → 给出可操作的提示，而不是让工具内部崩
     *
     * @param args 工具参数，null 视为空
     */
    public ToolResult execute(String name, Map<String, Object> args,
                             Workspace ws, Context ctx) {
        ToolSpec tool;
        synchronized (this) {
            tool = tools.get(name);
        }
        if (tool == null) {
            // 模型偶尔会幻觉出不存在的工具名。给出当前可用列表，帮它自我纠正。
            return ToolResult.fail("未知工具: " + name
                    + "\n当前可用工具: " + String.join(", ", nameList()));
        }

        if (tool.needsWorkspace() && ws == null) {
            return ToolResult.fail("当前没有工作区，请先在主页导入游戏 APK。");
        }

        try {
            return tool.execute(args == null ? new LinkedHashMap<>() : args, ws, ctx);
        } catch (Throwable t) {
            // 任何工具抛异常都不该让整个对话崩掉
            String msg = t.getMessage();
            return ToolResult.fail("工具执行异常: " + t.getClass().getSimpleName()
                    + (msg == null ? "" : ": " + msg));
        }
    }

    private synchronized List<String> nameList() {
        List<String> out = new ArrayList<>();
        for (ToolSpec t : tools.values()) {
            if (isVisible(t)) out.add(t.name());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    public synchronized void setMcpEnabled(boolean on) {
        mcpEnabled = on;
    }

    public synchronized void setPluginsEnabled(boolean on) {
        pluginsEnabled = on;
    }

    public synchronized void setSkillsEnabled(boolean on) {
        skillsEnabled = on;
    }

    public synchronized boolean isMcpEnabled() {
        return mcpEnabled;
    }

    public synchronized boolean isPluginsEnabled() {
        return pluginsEnabled;
    }

    public synchronized boolean isSkillsEnabled() {
        return skillsEnabled;
    }

    /** 给系统提示词用的一句话摘要。 */
    public synchronized String summary() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ToolSpec t : tools.values()) {
            if (!isVisible(t)) continue;
            String src = t.source() == null ? SRC_BUILTIN : t.source();
            Integer c = counts.get(src);
            counts.put(src, c == null ? 1 : c + 1);
        }
        if (counts.isEmpty()) return "当前无可用工具。";
        StringBuilder sb = new StringBuilder("已加载 ");
        boolean first = true;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!first) sb.append("，");
            sb.append(e.getValue()).append(" 个").append(e.getKey()).append("工具");
            first = false;
        }
        return sb.append("。").toString();
    }
}