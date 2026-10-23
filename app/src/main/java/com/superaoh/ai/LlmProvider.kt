package com.superaoh.ai

/**
 * LLM 服务抽象 —— 借鉴 Operit 的 AIService 设计，但零第三方依赖。
 *
 * Operit 原版 AIService 依赖 ModelParameter / ModelOption / ToolPrompt /
 * Stream 等 6 个内部类型，且其实现层（OpenAIProvider 2542 行）依赖
 * OkHttp + kotlinx.coroutines + 6 个 Operit 内部模块。整块照搬会把
 * 36 万行工程拖进来。
 *
 * 这里保留 Operit 的**架构思想**：
 *   - 用接口隔离不同厂商协议
 *   - 用工厂选择实现
 *   - 流式回调而不是一次性返回
 *   - 统一的错误语义（可重试 / 不可重试）
 * 但用项目已有的 Http + Agent 实现落地，依赖为零。
 */

/** 一次请求的配置。 */
class LlmRequest(
    /** 完整 URL，例如 https://api.x.com/v1/chat/completions */
    @JvmField var url: String? = null,
    @JvmField var apiKey: String? = null,
    /** 模型名，例如 gpt-4o */
    @JvmField var model: String? = null,
    @JvmField var temperature: Double = 0.7,
    @JvmField var maxTokens: Int = 4096,
    @JvmField var topP: Double = 1.0,
    /** 是否请求流式（SSE） */
    @JvmField var stream: Boolean = true,
    /** 额外请求头 */
    @JvmField var headers: MutableMap<String, String> = LinkedHashMap()
)

/** 流式事件。 */
interface LlmStreamListener {
    /** 正文增量。 */
    fun onContent(delta: String)

    /** 思维链增量（DeepSeek-R1 / o1 等）。默认忽略。 */
    fun onReasoning(delta: String) {}

    /** 工具调用增量拼装完成后的完整调用。 */
    fun onToolCall(id: String, name: String, argumentsJson: String)

    /** 收到 usage 统计。 */
    fun onUsage(promptTokens: Int, completionTokens: Int) {}

    /**
     * 收到带明细的 usage（缓存命中、推理 token）。
     *
     * 单独开一个默认方法而不是改 onUsage 签名：onUsage 已被
     * OpenAiCompatProvider 的两种解析路径调用，改签名会牵动太多；
     * 这里给默认空实现，只有 provider 能拿到明细时才回调。
     */
    fun onUsageDetail(cachedTokens: Int, reasoningTokens: Int) {}

    /** 结束（正常或被取消）。 */
    fun onEnd(finishReason: String)

    /** 出错。 */
    fun onError(message: String, retryable: Boolean)
}

/** 一次请求的结果摘要。 */
class LlmResult(
    @JvmField var content: String? = null,
    @JvmField var reasoning: String? = null,
    @JvmField var finishReason: String? = null,
    @JvmField var promptTokens: Int = 0,
    @JvmField var completionTokens: Int = 0
)

/**
 * Provider 能力描述。
 *
 * Operit 用枚举 ApiProviderType 区分 20+ 厂商，这里只保留
 * 对协议行为有影响的差异点，避免无意义的枚举膨胀。
 */
class LlmCapabilities(
    /** 是否支持 tools / function calling */
    @JvmField var supportsTools: Boolean = true,
    /** 是否支持图片输入（多模态） */
    @JvmField var supportsVision: Boolean = true,
    /** 是否支持流式 */
    @JvmField var supportsStream: Boolean = true,
    /** reasoning 内容放在哪个字段 */
    @JvmField var reasoningField: String? = "reasoning_content",
    /** 图片用 image_url 还是 inline_data */
    @JvmField var visionStyle: String = VISION_OPENAI,
    /** 工具调用结果的 role 名 */
    @JvmField var toolRole: String = "tool"
) {
    companion object {
        const val VISION_OPENAI = "openai"
        const val VISION_GEMINI = "gemini"
    }
}

/** 所有 Provider 的统一接口。 */
interface LlmProvider {
    /** 厂商标识，如 openai / claude / gemini / ollama */
    fun id(): String

    /** 能力。 */
    fun capabilities(): LlmCapabilities

    /**
     * 发起一次流式请求。阻塞执行，须在后台线程调用。
     * 返回是否成功（错误通过 listener 上报）。
     */
    fun stream(req: LlmRequest, messagesJson: String, listener: LlmStreamListener): Boolean
}