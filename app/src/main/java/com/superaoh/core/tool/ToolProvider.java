package com.superaoh.core.tool;

import android.content.Context;

import com.superaoh.core.Workspace;

import java.util.List;
import java.util.Map;

// ---------------------------------------------------------------------------
// 工具供给者
//
// Agent 只需要两件事：「现在有哪些工具」和「执行这个名字」。
// 至于工具来自内置 switch、注册中心、还是别的东西，Agent 不该知道。
//
// 这个接口存在的唯一目的就是让 AgentTools（旧的硬编码实现）和
// ToolRegistry（新的注册中心）能互换，迁移期间两者并存不冲突。
// ---------------------------------------------------------------------------

public interface ToolProvider {

    /** 组装成请求体里的 tools 数组。 */
    List<Object> toOpenAiTools();

    /** 执行一次工具调用。实现方负责兜住所有异常。 */
    ToolResult execute(String name, Map<String, Object> args, Workspace ws, Context ctx);
}
