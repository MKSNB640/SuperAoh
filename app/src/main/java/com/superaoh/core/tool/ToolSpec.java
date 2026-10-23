package com.superaoh.core.tool;

import android.content.Context;

import com.superaoh.core.Workspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个可调用工具。
 *
 * 实现类必须无状态或自行保证线程安全 —— Agent 在后台线程调用 execute。
 */
public interface ToolSpec {

    /** 工具名。MCP 工具的命名是 "mcp__服务器名__工具名"，避免与内置冲突。 */
    String name();

    /** 给模型看的描述。这段文字直接影响模型是否会正确使用该工具。 */
    String description();

    /**
     * 参数表：参数名 -> 说明。
     * 简单起见所有参数都按 string 传给模型（模型会自己转 JSON 字面量）。
     */
    Map<String, String> params();

    /** 必填参数名。 */
    List<String> required();

    /** 来源标签，用于 UI 分组展示：内置 / MCP / 插件 / Skill。 */
    String source();

    /**
     * 是否需要工作区。为 true 时，没有工作区会直接返回错误而不调用 execute。
     */
    boolean needsWorkspace();

    /**
     * 执行。
     *
     * @param args 已解析的参数表（可能为空 Map，不会为 null）
     * @param ws   当前工作区，可能为 null
     * @param ctx  应用上下文
     */
    ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx);

    /**
     * 自定义 JSON Schema。返回 null 表示用默认的「全部 string」生成。
     *
     * MCP 工具的 inputSchema 是完整 JSON Schema（含 integer/array/object），
     * 直接透传比压平成 string 更准确，模型给参数时更不容易出错。
     */
    default Map<String, Object> customInputSchema() {
        return null;
    }

    /** 转成 OpenAI tools 数组里的一项。 */
    default Map<String, Object> toOpenAiSchema() {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name());
        fn.put("description", description());

        Map<String, Object> schema = customInputSchema();
        if (schema == null) {
            Map<String, Object> props = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : params().entrySet()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("type", "string");
                p.put("description", e.getValue());
                props.put(e.getKey(), p);
            }
            schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            if (!required().isEmpty()) {
                schema.put("required", new java.util.ArrayList<Object>(required()));
            }
        }
        fn.put("parameters", schema);

        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put("type", "function");
        wrap.put("function", fn);
        return wrap;
    }
}