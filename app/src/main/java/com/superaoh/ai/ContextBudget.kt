package com.superaoh.ai

import com.superaoh.core.Agent
import java.util.Locale

/**
 * 上下文预算器 —— 精确控制送给模型的历史体积。
 *
 * 为什么需要它：
 *   原来 Agent.trimmedHistory() 用「字符数 / 1.7」估 token，
 *   对中文（1 字 ≈ 0.6~1 token）和代码/JSON（1 token ≈ 3~4 字符）
 *   偏差极大，容易在长对话里直接 400 报错，或者浪费一半预算。
 *
 * 这里的做法：
 *   - 按字符类别分别计价：CJK / 拉丁 / 数字 / 标点 / 空白
 *   - 逐条消息算成本，并按「必须整组保留」的约束做贪心选择
 *   - tool 消息永远和它的 assistant(tool_calls) 捆绑，不拆散
 */
object ContextBudget {

    /** 各类字符的 token 权重（保守估计）。 */
    private const val W_CJK = 1.0      // 汉字：约 1 字 1 token
    private const val W_LATIN = 0.28   // 英文：约 3.6 字符 1 token
    private const val W_DIGIT = 0.34   // 数字：比英文略贵
    private const val W_PUNCT = 0.5    // 标点
    private const val W_SPACE = 0.14   // 空白

    /** 单条消息的固定开销（role、分隔符等）。 */
    private const val PER_MSG_OVERHEAD = 4

    /** 每张图片按 low detail 计价的固定 token。 */
    private const val PER_IMAGE_TOKENS = 85

    /**
     * 估算一段文本的 token 数。
     * 该函数是纯函数，可安全用于 UI 线程的小文本。
     */
    @JvmStatic
    fun estimate(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var cjk = 0
        var latin = 0
        var digit = 0
        var punct = 0
        var space = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val cp = text.codePointAt(i)
            when {
                cp == ' '.code || cp == '\n'.code || cp == '\t'.code || cp == '\r'.code -> space++
                cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF ||
                    cp in 0x3000..0x303F || cp in 0xFF00..0xFFEF -> cjk++
                cp in 0x30..0x39 -> digit++
                cp in 0x41..0x5A || cp in 0x61..0x7A -> latin++
                else -> punct++
            }
            i += Character.charCount(cp)
        }
        val tokens = cjk * W_CJK + latin * W_LATIN + digit * W_DIGIT +
            punct * W_PUNCT + space * W_SPACE
        return Math.max(1, Math.ceil(tokens).toInt())
    }

    /** 估算一条消息的 token 成本（含固定开销与图片）。 */
    @JvmStatic
    fun estimateMessage(msg: Agent.Message?): Int {
        if (msg == null) return 0
        // 常量已改为 Int，直接相加
        var t: Int = estimate(msg.content) + PER_MSG_OVERHEAD
        val imgs = msg.imageDataUrls
        if (imgs != null && imgs.isNotEmpty()) {
            t += imgs.size * PER_IMAGE_TOKENS
            // data URL 本身不上行给模型，但要算 base64 元数据开销
            t += imgs.size * 12
        }
        if (msg.toolCalls != null && msg.toolCalls.isNotEmpty()) {
            for (tc in msg.toolCalls) {
                t += estimate(tc.name) + estimate(tc.argumentsRaw) + 8
            }
        }
        return t
    }

    /**
     * 从历史里挑选要发送的消息。
     *
     * @param history  完整历史（按时间正序）
     * @param budget   可用 token 预算
     * @return 选中的下标区间 [from, size)，保证不拆散 tool 组
     */
    @JvmStatic
    fun selectFrom(history: List<Agent.Message>, budget: Int): Int {
        if (history.isEmpty()) return 0
        if (budget <= 0) {
            // 预算非法：退化为只保留最近 20 条
            return Math.max(0, history.size - 20)
        }
        var used = 0
        var start = history.size
        while (start > 0) {
            val idx = start - 1
            val cost = estimateMessage(history[idx])
            if (used + cost > budget && start < history.size) {
                break
            }
            used += cost
            start = idx
        }
        // 修正：切点若落在 tool 消息上，回退到对应的 assistant(tool_calls)
        while (start > 0 && start < history.size && isTool(history[start])) {
            start--
        }
        // 修正：开头若残留孤立的 tool，继续回退
        while (start < history.size && isTool(history[start])) {
            start++
        }
        return start
    }

    private fun isTool(m: Agent.Message): Boolean = "tool".equals(m.role)

    /** 统计一条历史的总 token（用于 UI 显示占用率）。 */
    @JvmStatic
    fun totalTokens(history: List<Agent.Message>): Int {
        var sum = 0
        for (m in history) sum += estimateMessage(m)
        return sum
    }

    /** 人类可读的体积描述，如 "12.3K / 128K (9%)"。 */
    @JvmStatic
    fun describeUsage(used: Int, window: Int): String {
        if (window <= 0) {
            return String.format(Locale.US, "%s tokens（未设置窗口）", human(used))
        }
        val pct = Math.min(100, Math.round(used * 100.0 / window).toInt())
        return String.format(Locale.US, "%s / %s（%d%%）", human(used), human(window), pct)
    }

    private fun human(n: Int): String =
        if (n >= 1000) String.format(Locale.US, "%.1fK", n / 1000.0) else "$n"
}
