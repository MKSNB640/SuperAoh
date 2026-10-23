package com.superaoh.ai

/**
 * 重试策略 —— 借鉴 Operit 的 LlmRetryPolicy。
 *
 * 差异：Operit 依赖 kotlinx.coroutines 的 delay，这里用 Thread.sleep，
 * 保证零协程依赖（项目当前是传统线程模型）。
 *
 * 支持：
 *   - 指数退避 + 抖动
 *   - 仅对可重试错误生效（429 限流、5xx、网络超时）
 *   - 上限封顶，避免无限等待
 */
object RetryPolicy {

    /** 默认最大尝试次数（含首次）。 */
    const val DEFAULT_MAX_ATTEMPTS = 3

    /** 基础退避毫秒。 */
    private const val BASE_DELAY_MS = 800L

    /** 单次最大退避毫秒。 */
    private const val MAX_DELAY_MS = 8000L

    /** 每次尝试的决策结果。 */
    class Decision(
        @JvmField var retry: Boolean,
        @JvmField var delayMs: Long,
        @JvmField var reason: String
    )

    /**
     * 判断是否应当重试。
     *
     * @param attempt  已进行的尝试次数（从 1 开始）
     * @param maxAttempts 最大尝试次数
     * @param retryable 本次错误是否可重试
     * @param serverHintMs 服务端建议的等待（如 Retry-After），0 表示无
     */
    @JvmStatic
    fun decide(attempt: Int, maxAttempts: Int, retryable: Boolean, serverHintMs: Long): Decision {
        if (!retryable) {
            return Decision(false, 0, "不可重试的错误")
        }
        if (attempt >= maxAttempts) {
            return Decision(false, 0, "已达最大尝试次数 " + maxAttempts)
        }
        if (serverHintMs > 0) {
            return Decision(true, Math.min(serverHintMs, MAX_DELAY_MS), "服务端要求等待")
        }
        // 指数退避：800, 1600, 3200...
        var d = BASE_DELAY_MS shl (attempt - 1)
        if (d > MAX_DELAY_MS) {
            d = MAX_DELAY_MS
        }
        // 抖动 ±25%，避免多请求同时重试
        val jitter = (d * (Math.random() * 0.5 - 0.25)).toLong()
        var delay = d + jitter
        if (delay < 200) {
            delay = 200
        }
        return Decision(true, delay, "第 " + attempt + " 次失败，退避 " + delay + "ms")
    }

    /** 执行退避等待。可被中断（中断时立即返回 false）。 */
    @JvmStatic
    fun waitFor(delayMs: Long): Boolean {
        if (delayMs <= 0) {
            return true
        }
        try {
            Thread.sleep(delayMs)
            return true
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    }
}