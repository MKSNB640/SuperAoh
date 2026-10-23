package com.superaoh.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// ---------------------------------------------------------------------------
// Compose 聊天页的数据模型
//
// 设计要点：
//   1. UI 模型与 ChatStore.StoredMessage 解耦，避免 Compose 重组时被可变字段影响
//   2. 思考等级、思考过程、图片、工具调用都作为一等公民建模
//   3. 会话切换 = 整体替换 message 列表
// ---------------------------------------------------------------------------

/** 消息角色。 */
enum class MsgRole { USER, ASSISTANT, TOOL, NOTE }

/**
 * 一条聊天消息的不可变快照。
 *
 * @param id        稳定 id，供 LazyColumn 做 key，避免重组时错位
 * @param role      角色
 * @param content   正文（Markdown）
 * @param reasoning 思考过程（推理模型的思维链），空串表示无
 * @param images    图片本地路径列表
 * @param streaming 是否仍在流式输出中（显示光标/进度）
 * @param toolOk    工具调用是否成功（仅 TOOL 角色有意义）
 * @param toolName  工具名（仅 TOOL 角色）
 * @param toolArgs  工具参数摘要（仅 TOOL 角色）
 * @param toolResult 工具完整输出（点击卡片展开时用）
 */
data class ChatMessage(
    val id: Long,
    val role: MsgRole,
    val content: String = "",
    val reasoning: String = "",
    val images: List<String> = emptyList(),
    /**
     * 用户消息附带的普通文件（不限格式）。
     *
     * 与 images 分开存：图片要编码成 data URL 直接塞进请求体，
     * 文件动辄几百 MB，只能存路径，让模型用工具按需读取。
     */
    val files: List<ChatFile> = emptyList(),
    val streaming: Boolean = false,
    val toolOk: Boolean = true,
    val toolName: String = "",
    val toolArgs: String = "",
    val toolResult: String = "",
    val time: Long = 0L,
    /**
     * 真实 token 用量，来自服务端 SSE 的 usage 字段。
     *
     * prompt 是输入（含历史与工具定义），completion 是输出，
     * cached 是其中命中缓存的输入量。全为 0 表示服务端没给（部分中转站不返回）。
     * 不要用字符数估算去填，那会误导用户。
     */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    /** 推理（思考）消耗的 token，OpenAI 兼容接口放在 completion_tokens_details。 */
    val reasoningTokens: Int = 0,
    /** 首 token 延迟与总耗时，毫秒。 */
    val firstTokenMs: Long = -1L,
    val durationMs: Long = -1L,
    /** 本轮工具调用次数，用于在气泡尾部展示。 */
    val toolCallCount: Int = 0,
)

/** 一个用户附加的普通文件。 */
data class ChatFile(
    /** 本地绝对路径（已复制到会话目录，退出后仍在）。 */
    val path: String,
    val name: String,
    val size: Long,
    /** MIME 类型，可能为空串。 */
    val mime: String = "",
) {
    /** 展示用的大小文本。 */
    fun sizeText(): String {
        if (size <= 0) return "0B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = size.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (i == 0) "${size}B" else String.format("%.1f%s", v, units[i])
    }
}

/** 不含密钥的模型展示项，供聊天页切换使用。 */
data class ChatModelOption(
    val id: String,
    val title: String,
    val model: String,
    val active: Boolean = false,
)

/** 一个会话的元信息（列表页用）。 */
data class SessionMeta(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

/**
 * 思考等级定义。
 *
 * 这些档位来自 mobile-agent 的 DEFAULT_REASONING_EFFORTS，
 * 并按其 ChatSettings 里的厂商特例做了细化。
 */
object ReasoningEfforts {
    /** 通用的五档。 */
    val DEFAULT = listOf("low", "medium", "high", "xhigh", "max")

    /** 完全不指定，交给服务端默认。 */
    const val NONE = ""

    /**
     * 根据模型名推断可用档位。
     *
     * 目前只处理已知特例，其余一律用 DEFAULT。
     * 判断依据是 mobile-agent ChatSettings.kt:2678 的实测结论。
     */
    fun forModel(model: String?): List<String> {
        val m = model?.uppercase() ?: return DEFAULT
        return when {
            // GLM-5.3 只支持三档
            m.contains("GLM-5.3") -> listOf("low", "high", "max")
            // GLM-5.2 支持六档（含 minimal）
            m.contains("GLM-5.2") -> listOf("minimal", "low", "medium", "high", "xhigh", "max")
            // o1 / o3 / o4 系列是 OpenAI 的三档
            m.contains("O1") || m.contains("O3") || m.contains("O4") ->
                listOf("low", "medium", "high")
            else -> DEFAULT
        }
    }

    /** 人类可读的档位名。 */
    fun label(effort: String): String = when (effort) {
        NONE -> "默认"
        "minimal" -> "极低"
        "low" -> "低"
        "medium" -> "中"
        "high" -> "高"
        "xhigh" -> "很高"
        "max" -> "最高"
        else -> effort
    }
}

// ---------------------------------------------------------------------------
// 会话状态容器
// ---------------------------------------------------------------------------

/**
 * 聊天页的全部可变状态。
 *
 * 放在一个类里而不是散在 Activity 字段中，是为了：
 *   - 便于在配置变更（旋转屏幕）时保留
 *   - 让 Compose 只观察少数几个 state，减少无效重组
 */
class ChatState {
    /** 当前消息列表。整体替换会触发 LazyColumn 重组。 */
    var messages by mutableStateOf<List<ChatMessage>>(emptyList())

    /** 是否正在生成回复。 */
    var busy by mutableStateOf(false)

    /** Agent 当前请求轮次（包含工具调用后模型的继续请求）。 */
    var busyRound by mutableStateOf(0)

    /** 输入草稿，放在状态容器里以支持快捷提示与重组。 */
    var draft by mutableStateOf("")

    /** 当前会话标题。 */
    var title by mutableStateOf("新对话")

    /** 当前思考等级，空串 = 不指定。 */
    var reasoningEffort by mutableStateOf(ReasoningEfforts.NONE)

    /** 待发送的图片（本地路径）。 */
    var pendingImages by mutableStateOf<List<String>>(emptyList())

    /** 待发送的普通文件（不限格式）。 */
    var pendingFiles by mutableStateOf<List<ChatFile>>(emptyList())

    /** 上下文用量估算文本。 */
    var contextText by mutableStateOf("")

    /** 一次性提示（Snackbar 用）。 */
    var notice by mutableStateOf<String?>(null)

    /** 自增 id 生成器。 */
    private var seq = 1L

    fun nextId(): Long = seq++

    /**
     * 载入历史后，把自增计数器抬到已有最大 id 之上。
     *
     * 不加这一步，LazyColumn 的 key 会撞车，表现为消息错位/闪烁。
     */
    fun ensureSeqAbove(maxId: Long) {
        if (maxId >= seq) seq = maxId + 1
    }

    /** 重置为新会话。 */
    fun reset() {
        messages = emptyList()
        busy = false
        busyRound = 0
        draft = ""
        title = "新对话"
        pendingImages = emptyList()
        pendingFiles = emptyList()
        seq = 1L
    }

    /** 追加一条消息。 */
    fun add(m: ChatMessage) {
        messages = messages + m
    }

    /** 替换指定 id 的消息。找不到则忽略。 */
    fun update(id: Long, transform: (ChatMessage) -> ChatMessage) {
        messages = messages.map { if (it.id == id) transform(it) else it }
    }

    /** 删除指定 id。 */
    fun remove(id: Long) {
        messages = messages.filterNot { it.id == id }
    }
}
