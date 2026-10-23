package com.superaoh.core.tool;

import android.content.Context;

import com.superaoh.core.AgentTools;
import com.superaoh.core.Workspace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ---------------------------------------------------------------------------
// 内置工具适配器
//
// AgentTools 是旧版的硬编码实现，11 个工具已经稳定工作，没必要推倒重写。
// 但注册中心需要的是 ToolSpec，所以这里做一层包装：
//   - 复用 AgentTools 已经写好的 description / params / required
//   - execute 直接转调 AgentTools
//
// 这样一来，「把内置工具迁到注册中心」变成零风险操作：
// 行为完全不变，只是多了一层壳。等将来逐个重写内置工具时，
// 替换某个包装器的实现即可，模型侧完全无感。
// ---------------------------------------------------------------------------

public final class BuiltinToolAdapter implements ToolSpec {

    private final AgentTools.Tool meta;
    private final AgentTools impl;

    public BuiltinToolAdapter(AgentTools impl, AgentTools.Tool meta) {
        this.impl = impl;
        this.meta = meta;
    }

    @Override
    public String name() {
        return meta.name;
    }

    @Override
    public String description() {
        return meta.description;
    }

    @Override
    public Map<String, String> params() {
        return meta.params == null ? Collections.<String, String>emptyMap() : meta.params;
    }

    @Override
    public List<String> required() {
        return meta.required == null ? Collections.<String>emptyList() : meta.required;
    }

    @Override
    public String source() {
        return ToolRegistry.SRC_BUILTIN;
    }

    /**
     * 与旧版 needsWorkspace 保持一致。
     *
     * 这里不把判断逻辑搬过来，而是问一次 AgentTools —— 它在 execute 里
     * 也会再判一次，重复判断无害，但能保证注册中心的前置校验和实际行为
     * 永远不会漂移。
     */
    @Override
    public boolean needsWorkspace() {
        return AgentTools.needsWorkspaceStatic(meta.name);
    }

    @Override
    public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
        return impl.execute(name(), args, ws, ctx);
    }

    // ------------------------------------------------------------------
    // 批量装配
    // ------------------------------------------------------------------

    /**
     * 把 AgentTools 里的全部工具包成适配器，装进注册中心。
     */
    public static void installAll(ToolRegistry reg, AgentTools impl) {
        if (reg == null || impl == null) return;
        for (AgentTools.Tool t : impl.tools()) {
            reg.register(new BuiltinToolAdapter(impl, t));
        }
    }

    /** 工具清单快照，调试用。 */
    public static Map<String, String> describe(AgentTools impl) {
        Map<String, String> out = new LinkedHashMap<>();
        if (impl == null) return out;
        for (AgentTools.Tool t : impl.tools()) {
            out.put(t.name, t.description);
        }
        return out;
    }
}