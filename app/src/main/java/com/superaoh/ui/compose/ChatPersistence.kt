package com.superaoh.ui.compose

import android.content.Context
import com.superaoh.core.Agent
import com.superaoh.core.ChatStore

// ---------------------------------------------------------------------------
// 会话持久化桥
//
// 职责：ChatStore（磁盘 JSON） <-> ChatState（Compose 状态） <-> Agent（LLM 上下文）
//
// 为什么需要这一层：
//   ChatStore.StoredMessage 是可变的、字段名面向存储；
//   ChatMessage 是不可变的、面向渲染。
//   两边的字段并不一一对应（比如 StoredMessage 用 toolCallsJson 存整串），
//   所以转换逻辑集中在这里，避免 UI 层到处拼装。
// ---------------------------------------------------------------------------

/** 会话持久化。 */
class ChatPersistence(
    private val ctx: Context,
    private val wsId: String?,
) {

    /** 列出全部会话，按最近更新倒序。 */
    fun listSessions(): List<SessionMeta> =
        ChatStore.listSessions(ctx, wsId).map {
            SessionMeta(
                id = it.id,
                title = it.title ?: "未命名",
                updatedAt = it.updatedAt,
                messageCount = it.messageCount,
            )
        }

    /** 新建一个空会话并落盘，返回其 id。 */
    fun createSession(): String {
        val s = ChatStore.newSession(wsId)
        ChatStore.save(ctx, wsId, s)
        return s.id
    }

    /** 删除会话。 */
    fun deleteSession(id: String) {
        ChatStore.delete(ctx, wsId, id)
    }

    /**
     * 载入会话，返回消息列表与标题。
     * 会话不存在时返回 null。
     */
    fun load(id: String): Pair<List<ChatMessage>, String>? {
        val s = ChatStore.load(ctx, wsId, id) ?: return null
        val list = ArrayList<ChatMessage>(s.messages.size)
        var seq = 1L
        for (m in s.messages) {
            // 工具消息：还原成可展开的卡片
            if ("tool".equals(m.role)) {
                list.add(
                    ChatMessage(
                        id = seq++,
                        role = MsgRole.TOOL,
                        content = m.content ?: "",
                        toolName = m.toolName ?: "",
                        toolArgs = m.toolArgs ?: "",
                        toolResult = m.content ?: "",
                        toolOk = m.toolOk,
                        time = m.time,
                    )
                )
                continue
            }
            // 普通消息
            val role = when (m.role) {
                "user" -> MsgRole.USER
                "assistant" -> MsgRole.ASSISTANT
                else -> MsgRole.NOTE
            }
            list.add(
                ChatMessage(
                    id = seq++,
                    role = role,
                    content = m.content ?: "",
                    reasoning = m.reasoning ?: "",
                    images = m.images(),
                    files = m.files().map { f ->
                        ChatFile(path = f.path ?: "", name = f.name ?: "", size = f.size, mime = f.mime ?: "")
                    }.filter { it.path.isNotBlank() },
                    // token 用量也恢复：否则重开应用后气泡尾部的
                    // 「↑输入 ↓输出」会消失，用户会以为是统计坏了。
                    promptTokens = m.promptTokens,
                    completionTokens = m.completionTokens,
                    cachedTokens = m.cachedTokens,
                    reasoningTokens = m.reasoningTokens,
                    durationMs = m.durationMs,
                    firstTokenMs = m.firstTokenMs,
                    time = m.time,
                )
            )
        }
        return list to (s.title ?: "未命名")
    }

    /**
     * 把当前消息列表落盘。
     *
     * 每次发送/收到回复后调用。写入是同步的（文件很小，几十 KB），
     * 放后台线程会引入“用户切走时还没写完”的竞态，得不偿失。
     */
    fun save(sessionId: String, title: String, messages: List<ChatMessage>) {
        val s = ChatStore.load(ctx, wsId, sessionId) ?: ChatStore.newSession(wsId).also {
            it.id = sessionId
        }
        s.title = title
        s.updatedAt = System.currentTimeMillis()
        s.messages.clear()
        for (m in messages) {
            // 流式中的临时消息不落盘，避免存进半截内容
            if (m.streaming) continue
            when (m.role) {
                MsgRole.TOOL -> {
                    val sm = ChatStore.StoredMessage("tool", m.content)
                    sm.toolName = m.toolName
                    sm.toolArgs = m.toolArgs
                    sm.toolOk = m.toolOk
                    sm.time = if (m.time > 0) m.time else System.currentTimeMillis()
                    s.messages.add(sm)
                }
                MsgRole.NOTE -> {
                    val sm = ChatStore.StoredMessage("note", m.content)
                    sm.time = if (m.time > 0) m.time else System.currentTimeMillis()
                    s.messages.add(sm)
                }
                MsgRole.USER -> {
                    val sm = ChatStore.StoredMessage("user", m.content)
                    sm.reasoning = null
                    sm.setImages(m.images)
                    sm.setFiles(encodeFiles(m.files))
                    sm.time = if (m.time > 0) m.time else System.currentTimeMillis()
                    s.messages.add(sm)
                }
                MsgRole.ASSISTANT -> {
                    val sm = ChatStore.StoredMessage("assistant", m.content)
                    sm.reasoning = m.reasoning.ifBlank { null }
                    sm.promptTokens = m.promptTokens
                    sm.completionTokens = m.completionTokens
                    sm.cachedTokens = m.cachedTokens
                    sm.reasoningTokens = m.reasoningTokens
                    sm.durationMs = m.durationMs
                    sm.firstTokenMs = m.firstTokenMs
                    sm.time = if (m.time > 0) m.time else System.currentTimeMillis()
                    s.messages.add(sm)
                }
            }
        }
        ChatStore.save(ctx, wsId, s)
    }

    /**
     * 把 ChatFile 列表转成 ChatStore 的存储形态。
     *
     * 两个数据类故意分开：ChatFile 是 UI 层不可变值对象，
     * ChatStore.FileItem 是可变存储记录，混用会让 Compose 误判状态变更。
     */
    private fun encodeFiles(files: List<ChatFile>): List<ChatStore.FileItem>? {
        if (files.isEmpty()) return null
        return files.map { f ->
            ChatStore.FileItem().apply {
                path = f.path
                name = f.name
                size = f.size
                mime = f.mime
            }
        }
    }

    /**
     * 把历史灌回 Agent，让模型恢复记忆。
     *
     * 只灌 user / assistant 的正文，不灌工具调用记录 —— 工具结果是上一轮
     * 的临时产物，重放会污染上下文（而且很多工具已经不可重放）。
     */
    fun restoreAgentHistory(agent: Agent, messages: List<ChatMessage>) {
        agent.clearHistory()
        for (m in messages) {
            when (m.role) {
                MsgRole.USER -> {
                    if (m.content.isNotBlank()) {
                        agent.addMessage(Agent.Message("user", m.content))
                    }
                }
                MsgRole.ASSISTANT -> {
                    if (m.content.isNotBlank()) {
                        agent.addMessage(Agent.Message("assistant", m.content))
                    }
                }
                else -> {
                    // 工具/系统提示不进历史
                }
            }
        }
    }
}