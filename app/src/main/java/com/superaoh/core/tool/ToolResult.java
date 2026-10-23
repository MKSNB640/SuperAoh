package com.superaoh.core.tool;

// ---------------------------------------------------------------------------
// 工具执行结果
//
// 只有「成功/失败 + 文本」两个字段。刻意不引入结构化返回值：
// 模型读的是文本，任何结构化数据最终都要被序列化进上下文，
// 多一层包装只会增加出错面。
//
// 失败也用 ToolResult 表达而不是抛异常，因为模型需要看到错误信息
// 才能自我纠正（比如路径写错了，看到「文件不存在」就会换个路径重试）。
// ---------------------------------------------------------------------------

public final class ToolResult {

    public final boolean ok;
    public final String text;

    private ToolResult(boolean ok, String text) {
        this.ok = ok;
        this.text = text == null ? "" : text;
    }

    public static ToolResult ok(String text) {
        return new ToolResult(true, text);
    }

    public static ToolResult fail(String text) {
        return new ToolResult(false, text);
    }
}