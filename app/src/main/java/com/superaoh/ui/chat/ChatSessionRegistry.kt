package com.superaoh.ui.chat

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.superaoh.core.Agent
import com.superaoh.core.AiConfig
import com.superaoh.core.ChatStore
import com.superaoh.core.ImageUtil
import com.superaoh.core.ToolRuntime
import com.superaoh.core.Workspace
import com.superaoh.ui.compose.ChatFile
import com.superaoh.ui.compose.ChatMessage
import com.superaoh.ui.compose.ChatPersistence
import com.superaoh.ui.compose.ChatState
import com.superaoh.ui.compose.MsgRole
import com.superaoh.ui.compose.ReasoningEfforts
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 进程级聊天会话，供全屏聊天与游戏内覆盖面板共用。
 * UI 仅通过 listener 接收快照/增量；模型调用、工具和存储仍由原 Agent/ChatStore 负责。
 */
object ChatSessionRegistry {
    private val sessions = HashMap<String, ChatSessionController>()

    @JvmStatic
    @Synchronized
    fun get(context: Context, workspaceId: String?): ChatSessionController {
        val key = workspaceId ?: "_default"
        return sessions[key] ?: ChatSessionController(context.applicationContext, workspaceId).also {
            sessions[key] = it
        }
    }
}

class ChatSessionController internal constructor(
    context: Context,
    private val workspaceId: String?,
) {
    interface Observer {
        fun onChatEvent(type: String, payload: Map<String, Any?>)
    }

    private val app = context.applicationContext
    private val ui = Handler(Looper.getMainLooper())
    val state = ChatState()
    private val observers = CopyOnWriteArraySet<Observer>()
    private val workspace: Workspace? = workspaceId?.let { Workspace.load(app, it) }
    private val persistence = ChatPersistence(app, workspaceId)
    private var agent: Agent? = null
    private var sessionId: String = ""
    private var streamId: Long = -1L
    private val streamReasoning = StringBuilder()
    private var lastProfileKey = ""
    private var initialized = false

    // 网络线程可能以每个 token 一次的频率回调。逐个 post 到主线程会让
    // Looper 一直处理队列，Compose 没有机会在生成中间绘制帧。
    // 在短窗口内合并增量，既保留打字机效果，也避免主线程被洪峰堵住。
    private val deltaLock = Any()
    private val pendingContent = StringBuilder()
    private val pendingReasoning = StringBuilder()
    private var deltaFlushPosted = false
    private val deltaFlushDelayMs = 36L
    private val deltaFlushRunnable = Runnable { flushDeltas() }

    @Synchronized
    private fun initialize() {
        if (initialized) return
        initialized = true
        ToolRuntime.get(app).bindWorkspace(workspace)
        val latest = persistence.listSessions().firstOrNull()
        if (latest != null) openSessionInternal(latest.id) else startNewSessionInternal()
        Thread({
            try { ToolRuntime.get(app).ensureMcpConnected() } catch (_: Throwable) {}
        }, "chat-mcp-connect").start()
    }

    fun addObserver(observer: Observer) {
        initialize()
        observers.add(observer)
        emit(observer, "snapshot", snapshot())
    }

    fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    fun onHostResumed() {
        initialize()
        val profile = AiConfig.getActive(app)
        val key = profileKey(profile)
        if (key != lastProfileKey && !state.busy) {
            val allowedEfforts = ReasoningEfforts.forModel(profile?.model)
            if (state.reasoningEffort.isNotBlank() && state.reasoningEffort !in allowedEfforts) {
                state.reasoningEffort = ReasoningEfforts.NONE
            }
            buildAgent()
            persistence.restoreAgentHistory(agent!!, state.messages)
            notifySnapshot()
        }
    }

    fun currentSessionId(): String {
        initialize()
        return sessionId
    }

    /** Compose 聊天页使用的会话列表。 */
    fun listSessions(): List<com.superaoh.ui.compose.SessionMeta> {
        initialize()
        return persistence.listSessions()
    }

    /** 切换当前请求的思考等级；设置只影响后续请求。 */
    fun setReasoningEffort(effort: String) {
        initialize()
        if (state.busy) {
            notifyEvent("notice", mapOf("text" to "请先停止当前任务，再切换思考等级"))
            return
        }
        val selected = effort.trim()
        state.reasoningEffort = selected.takeIf { it in ReasoningEfforts.forModel(AiConfig.getActive(app)?.model) }
            ?: ReasoningEfforts.NONE
        agent?.setReasoningEffort(state.reasoningEffort)
        notifySnapshot()
    }

    /** 切换模型配置档，并重建 Agent；正在生成时拒绝切换，避免串流落到错误模型。 */
    fun selectProfile(id: String) {
        initialize()
        if (state.busy) {
            notifyEvent("notice", mapOf("text" to "请先停止当前任务，再切换模型"))
            return
        }
        val profile = AiConfig.listProfiles(app).firstOrNull { it.id == id } ?: return
        if (!profile.usable()) {
            notifyEvent("notice", mapOf("text" to "该模型配置不完整"))
            return
        }
        AiConfig.setActive(app, id)
        onHostResumed()
        notifySnapshot()
    }

    fun send(text: String) {
        initialize()
        val a = agent ?: return
        val imgs = state.pendingImages.toList()
        val docs = state.pendingFiles.toList()
        if (text.isBlank() && imgs.isEmpty() && docs.isEmpty()) return
        if (state.title == "新对话") {
            val titleSource = text.trim().replace(Regex("\\s+"), " ").ifBlank {
                when {
                    docs.isNotEmpty() -> docs.first().name
                    imgs.isNotEmpty() -> "图片对话"
                    else -> "新对话"
                }
            }
            state.title = if (titleSource.length > 24) titleSource.take(24) + "…" else titleSource
        }
        state.add(ChatMessage(id = state.nextId(), role = MsgRole.USER, content = text,
            images = imgs, files = docs, time = System.currentTimeMillis()))
        state.pendingImages = emptyList()
        state.pendingFiles = emptyList()
        state.busy = true
        state.busyRound = 0
        streamId = -1L
        streamReasoning.setLength(0)
        roundUsageBegin()
        persistNow()
        notifySnapshot()
        val dataUrls = ArrayList<String>(imgs.size)
        for (p in imgs) ImageUtil.fileToDataUrl(File(p))?.let(dataUrls::add)
        // 文件不进请求体，只把清单拼在正文前面。
        // 这样 1GB 的包也不会撑爆请求，模型需要内容时自己用工具读。
        val payload = buildString {
            if (docs.isNotEmpty()) {
                append("【已附加文件】\n")
                for (d in docs) {
                    append("- ").append(d.name).append("（").append(d.sizeText()).append("）")
                    append("\n  本地路径：").append(d.path)
                    append("\n  提示：需要内容时用 read_text 或终端工具读取该路径。\n")
                }
                append('\n')
            }
            append(if (text.isBlank()) (if (imgs.isNotEmpty()) "请看这张图片" else "请看附加内容") else text)
        }
        Thread({
            try {
                a.run(payload, dataUrls)
            } catch (t: Throwable) {
                ui.post {
                    flushDeltasNow()
                    finishStream()
                    state.busy = false
                    state.busyRound = 0
                    state.add(ChatMessage(id = state.nextId(), role = MsgRole.NOTE,
                        content = "请求失败：${t.message ?: t.javaClass.simpleName}", time = System.currentTimeMillis()))
                    persistNow()
                    notifySnapshot()
                }
            }
        }, "SuperAoh-Agent").start()
    }

    fun cancel() {
        agent?.cancel()
        flushDeltasNow()
        state.busy = false
        state.busyRound = 0
        notifySnapshot()
    }

    fun newSession() {
        initialize()
        if (state.busy) {
            notifyEvent("notice", mapOf("text" to "请先停止当前任务，再新建会话"))
            return
        }
        persistNow()
        startNewSessionInternal()
        notifySnapshot()
    }

    fun openSession(id: String) {
        initialize()
        if (state.busy) {
            notifyEvent("notice", mapOf("text" to "请先停止当前任务，再切换会话"))
            return
        }
        persistNow()
        if (openSessionInternal(id)) notifySnapshot()
    }

    fun renameSession(id: String, title: String) {
        initialize()
        if (state.busy) {
            notifyEvent("notice", mapOf("text" to "请先停止当前任务，再重命名会话"))
            return
        }
        val clean = title.trim().take(80).ifBlank { return }
        val loaded = persistence.load(id) ?: return
        persistence.save(id, clean, loaded.first)
        if (id == sessionId) state.title = clean
        notifySnapshot()
    }

    fun deleteSession(id: String) {
        initialize()
        if (state.busy) {
            notifyEvent("notice", mapOf("text" to "请先停止当前任务，再删除会话"))
            return
        }
        persistence.deleteSession(id)
        if (id == sessionId) {
            val next = persistence.listSessions().firstOrNull()
            if (next != null) openSessionInternal(next.id) else startNewSessionInternal()
        }
        notifySnapshot()
    }

    fun attachImage(uri: Uri) {
        initialize()
        Thread({
            try {
                val dir = ChatStore.imageDir(app, workspaceId, sessionId)
                val name = "img_${System.currentTimeMillis()}"
                val prepared = ImageUtil.prepare(app, uri, dir, name)
                    ?: throw IllegalStateException("图片无法解码")
                ui.post {
                    state.pendingImages = state.pendingImages + prepared.file.absolutePath
                    notifySnapshot()
                    notifyEvent("notice", mapOf("text" to "图片已添加 ${prepared.sizeText()}"))
                }
            } catch (t: Throwable) {
                ui.post { notifyEvent("notice", mapOf("text" to "图片处理失败：${t.message}")) }
            }
        }, "chat-image-prepare").start()
    }

    fun removePendingImage(index: Int) {
        val current = state.pendingImages
        if (index in current.indices) {
            state.pendingImages = current.filterIndexed { i, _ -> i != index }
            notifySnapshot()
        }
    }

    // ------------------------------------------------------------------
    // 普通文件附件（不限格式、不限大小）
    //
    // 与图片完全分开的一条通道，原因：
    //   1. 图片要 base64 塞进 chat 请求体，必须压到几 MB 以内；
    //      用户明确要求文件「不限大小」，走那条路必然失败。
    //   2. 文件大小动辄几百 MB，一旦读进内存就会 OOM。
    //
    // 做法：只在会话目录里留一份文件，把「路径 + 名称 + 大小」告诉模型，
    // 模型需要内容时用 read_file / apktool 之类的工具自己去读。
    // 这样 1GB 的 APK 也能直接拖进来。
    // ------------------------------------------------------------------

    /**
     * 把一个 content:// 或 file:// 的 URI 复制进会话附件目录。
     *
     * 全程流式复制，不做整体 ByteArray 缓冲——
     * 之前 addImageData 用 8MB 上限挡住的正是这个内存问题。
     */
    fun attachFile(uri: Uri) {
        initialize()
        Thread({
            try {
                val dir = ChatStore.fileDir(app, workspaceId, sessionId)
                val name = displayName(uri)
                val safe = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").take(120)
                    .ifBlank { "file_${System.currentTimeMillis()}" }
                val target = File(dir, "${System.currentTimeMillis()}_$safe")
                var copied = 0L
                app.contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw IllegalStateException("无法打开文件")
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            copied += n
                        }
                        out.flush()
                    }
                }
                if (copied <= 0) {
                    target.delete()
                    throw IllegalStateException("文件是空的")
                }
                val mime = app.contentResolver.getType(uri) ?: guessMime(safe)
                ui.post {
                    state.pendingFiles = state.pendingFiles + ChatFile(
                        path = target.absolutePath, name = safe, size = copied, mime = mime
                    )
                    notifySnapshot()
                    notifyEvent("notice", mapOf("text" to "已添加 $safe（${ChatFile(target.absolutePath, safe, copied).sizeText()}）"))
                }
            } catch (t: Throwable) {
                ui.post { notifyEvent("notice", mapOf("text" to "文件添加失败：${t.message ?: t.javaClass.simpleName}")) }
            }
        }, "chat-file-attach").start()
    }

    fun removePendingFile(index: Int) {
        val current = state.pendingFiles
        if (index in current.indices) {
            state.pendingFiles = current.filterIndexed { i, _ -> i != index }
            notifySnapshot()
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val v = c.getString(idx)
                    if (!v.isNullOrBlank()) return v
                }
            }
        } catch (_: Throwable) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log", "md", "json", "xml", "java", "kt", "js", "css", "html", "smali" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "jar" -> "application/java-archive"
        "dex" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    fun addExternalImage(file: File) {
        initialize()
        if (!file.exists() || file.length() > 6L * 1024 * 1024) return
        ui.post {
            state.pendingImages = state.pendingImages + file.absolutePath
            notifySnapshot()
        }
    }

    fun saveExternalImage(bytes: ByteArray, name: String): Boolean {
        initialize()
        if (bytes.isEmpty() || bytes.size > 6 * 1024 * 1024) return false
        return try {
            val dir = ChatStore.imageDir(app, workspaceId, sessionId)
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48).ifBlank { "image.jpg" }
            val file = File(dir, "web_${System.currentTimeMillis()}_$safeName")
            file.writeBytes(bytes)
            ui.post {
                state.pendingImages = state.pendingImages + file.absolutePath
                notifySnapshot()
            }
            true
        } catch (_: Throwable) {
            ui.post { notifyEvent("notice", mapOf("text" to "图片保存失败")) }
            false
        }
    }

    fun persist() {
        if (initialized) persistNow()
    }

    private fun startNewSessionInternal() {
        sessionId = persistence.createSession()
        state.reset()
        state.title = "新对话"
        buildAgent()
    }

    private fun openSessionInternal(id: String): Boolean {
        val loaded = persistence.load(id) ?: return false
        sessionId = id
        state.reset()
        state.messages = loaded.first
        state.title = loaded.second
        state.ensureSeqAbove(loaded.first.maxOfOrNull { it.id } ?: 0L)
        buildAgent()
        persistence.restoreAgentHistory(agent!!, loaded.first)
        updateContextMeter()
        return true
    }

    private fun buildAgent() {
        try { agent?.cancel() } catch (_: Throwable) {}
        streamId = -1L
        streamReasoning.setLength(0)
        val profile = AiConfig.getActive(app)
        lastProfileKey = profileKey(profile)
        val sys = AiConfig.getSystemPrompt(app) + ToolRuntime.get(app).capabilityBlock(null)
        agent = Agent(app, workspace, profile, sys, ToolRuntime.get(app).provider(), listener)
        agent?.setReasoningEffort(state.reasoningEffort)
    }

    private val listener = object : Agent.Listener {
        override fun onThinking(round: Int) {
            ui.post {
                state.busy = true
                state.busyRound = round
                notifySnapshot()
            }
        }

        override fun onContentDelta(delta: String?) {
            if (delta.isNullOrEmpty()) return
            enqueueDelta(content = delta, reasoning = null)
        }

        override fun onReasoningDelta(delta: String?) {
            if (delta.isNullOrEmpty()) return
            enqueueDelta(content = null, reasoning = delta)
        }

        override fun onToolCall(toolName: String?, argsSummary: String?, ok: Boolean, resultPreview: String?) {
            ui.post {
                flushDeltasNow()
                accToolCalls++
                finishStream()
                state.add(ChatMessage(id = state.nextId(), role = MsgRole.TOOL,
                    content = resultPreview ?: "", toolName = toolName ?: "",
                    toolArgs = argsSummary ?: "", toolResult = resultPreview ?: "",
                    toolOk = ok, time = System.currentTimeMillis()))
                persistNow()
                notifySnapshot()
            }
        }

        override fun onUsage(promptTokens: Int, completionTokens: Int,
                             cachedTokens: Int, reasoningTokens: Int) {
            // 一轮里每次 LLM 调用都会回调一次，这里累加；
            // 全 0 说明服务端根本没给，不做任何估算。
            if (promptTokens <= 0 && completionTokens <= 0) return
            ui.post {
                accPrompt += promptTokens
                accCompletion += completionTokens
                accCached += cachedTokens
                accReasoning += reasoningTokens
                // 用量先落到消息上，让用户在长回答过程中也能看到
                roundUsageApply()
            }
        }

        override fun onAnswer(answer: String?) {
            ui.post {
                flushDeltasNow()
                roundUsageApply()
                finishStream()
                state.busy = false
                state.busyRound = 0
                persistNow()
                updateContextMeter()
                notifySnapshot()
            }
        }

        override fun onError(message: String?) {
            ui.post {
                flushDeltasNow()
                finishStream()
                state.busy = false
                state.busyRound = 0
                state.add(ChatMessage(id = state.nextId(), role = MsgRole.NOTE,
                    content = "⚠ ${message ?: "未知错误"}", time = System.currentTimeMillis()))
                persistNow()
                notifySnapshot()
            }
        }

        override fun onCancelled() {
            ui.post {
                flushDeltasNow()
                finishStream()
                state.busy = false
                state.busyRound = 0
                state.add(ChatMessage(id = state.nextId(), role = MsgRole.NOTE,
                    content = "已中止", time = System.currentTimeMillis()))
                persistNow()
                notifySnapshot()
            }
        }

        override fun onMessagesChanged(messages: List<Agent.Message>?) {}
    }

    private fun enqueueDelta(content: String?, reasoning: String?) {
        synchronized(deltaLock) {
            if (!content.isNullOrEmpty()) pendingContent.append(content)
            if (!reasoning.isNullOrEmpty()) pendingReasoning.append(reasoning)
            if (deltaFlushPosted) return
            deltaFlushPosted = true
            ui.postDelayed(deltaFlushRunnable, deltaFlushDelayMs)
        }
    }

    /** 把短时间累积的 SSE token 合并成一次 Compose 状态更新。 */
    private fun flushDeltas() {
        val content: String
        val reasoning: String
        synchronized(deltaLock) {
            content = pendingContent.toString()
            reasoning = pendingReasoning.toString()
            pendingContent.setLength(0)
            pendingReasoning.setLength(0)
            deltaFlushPosted = false
        }
        if (reasoning.isNotEmpty()) appendReasoning(reasoning)
        if (content.isNotEmpty()) appendContent(content)
    }

    /** 结束/工具/错误回调前同步刷新，避免最后一个批次被终态更新抢在前面。 */
    private fun flushDeltasNow() {
        val content: String
        val reasoning: String
        synchronized(deltaLock) {
            // 在同一把锁内取消并重置标记，避免并发新 delta 被取消后永远留在缓冲区。
            ui.removeCallbacks(deltaFlushRunnable)
            content = pendingContent.toString()
            reasoning = pendingReasoning.toString()
            pendingContent.setLength(0)
            pendingReasoning.setLength(0)
            deltaFlushPosted = false
        }
        if (reasoning.isNotEmpty()) appendReasoning(reasoning)
        if (content.isNotEmpty()) appendContent(content)
    }

    private fun appendContent(delta: String) {
        if (streamId < 0) {
            val id = state.nextId()
            streamId = id
            if (accFirstTokenMs < 0 && accStartedAt > 0) {
                accFirstTokenMs = System.currentTimeMillis() - accStartedAt
            }
            state.add(ChatMessage(id = id, role = MsgRole.ASSISTANT, content = delta,
                reasoning = streamReasoning.toString(), streaming = true, time = System.currentTimeMillis()))
            notifySnapshot()
        } else {
            state.update(streamId) { it.copy(content = it.content + delta) }
            notifyEvent("delta", mapOf("id" to streamId.toString(), "kind" to "content", "delta" to delta))
        }
    }

    private fun appendReasoning(delta: String) {
        streamReasoning.append(delta)
        if (streamId < 0) {
            val id = state.nextId()
            streamId = id
            state.add(ChatMessage(id = id, role = MsgRole.ASSISTANT, reasoning = streamReasoning.toString(),
                streaming = true, time = System.currentTimeMillis()))
            notifySnapshot()
        } else {
            state.update(streamId) { it.copy(reasoning = streamReasoning.toString()) }
            notifyEvent("delta", mapOf("id" to streamId.toString(), "kind" to "reasoning", "delta" to delta))
        }
    }

    private fun finishStream() {
        val id = streamId
        if (id >= 0) state.update(id) { it.copy(streaming = false) }
        streamId = -1L
        streamReasoning.setLength(0)
    }

    // ------------------------------------------------------------------
    // 真实 token 用量
    //
    // 服务端在流末尾（有时也在中间的 usage-only 包）给出 usage。
    // 一轮对话可能包含多次 LLM 调用（每次工具调用后都会再请求一次），
    // 所以这里按「轮」累加，最后一次性挂到该轮的助手消息上。
    // ------------------------------------------------------------------

    /** 本轮累计。一轮 = 用户发一条消息到最终回答结束。 */
    private var accPrompt = 0
    private var accCompletion = 0
    private var accCached = 0
    private var accReasoning = 0
    private var accFirstTokenMs = -1L
    private var accStartedAt = 0L
    /** 本轮工具调用次数。 */
    private var accToolCalls = 0

    private fun roundUsageReset() {
        accPrompt = 0
        accCompletion = 0
        accCached = 0
        accReasoning = 0
        accFirstTokenMs = -1L
        accStartedAt = 0L
        accToolCalls = 0
    }

    private fun roundUsageBegin() {
        roundUsageReset()
        accStartedAt = System.currentTimeMillis()
    }

    /**
     * 把本轮累计挂到助手消息上。
     *
     * 挂在 streamId 上；如果这轮只有工具调用没有正文，则挂到最后一条助手消息。
     * 全为 0 时不写，避免 UI 显示一排 0 误导用户。
     */
    private fun roundUsageApply() {
        if (accPrompt <= 0 && accCompletion <= 0) return
        val id = if (streamId >= 0) streamId else {
            state.messages.lastOrNull { it.role == MsgRole.ASSISTANT }?.id ?: return
        }
        val elapsed = if (accStartedAt > 0) System.currentTimeMillis() - accStartedAt else -1L
        state.update(id) {
            it.copy(
                promptTokens = accPrompt,
                completionTokens = accCompletion,
                cachedTokens = accCached,
                reasoningTokens = accReasoning,
                firstTokenMs = accFirstTokenMs,
                durationMs = elapsed,
                toolCallCount = accToolCalls
            )
        }
        notifySnapshot()
    }

    private fun persistNow() {
        if (sessionId.isNotBlank()) persistence.save(sessionId, state.title, state.messages)
    }

    private fun updateContextMeter() {
        val chars = state.messages.sumOf { it.content.length + it.reasoning.length }
        state.contextText = if (chars > 0) "≈${chars / 4} tok" else ""
    }

    private fun profileKey(p: AiConfig.Profile?): String = if (p == null) "" else listOf(
        p.id, p.baseUrl, p.model, p.apiKey?.hashCode()?.toString() ?: "", p.temperature.toString(),
        p.maxTokens.toString(), p.contextWindow.toString(), p.reasoningEffortField
    ).joinToString("\u0000")

    fun snapshotForUi(): Map<String, Any?> {
        initialize()
        return snapshot()
    }

    private fun snapshot(): Map<String, Any?> {
        val out = ArrayList<Map<String, Any?>>()
        for (m in state.messages) {
            when (m.role) {
                MsgRole.TOOL -> {
                    val tool = linkedMapOf<String, Any?>(
                        "id" to m.id.toString(), "name" to m.toolName,
                        "argsPreview" to m.toolArgs, "arguments" to m.toolArgs,
                        "result" to m.toolResult, "status" to if (m.toolOk) "done" else "error"
                    )
                    val previous = out.indexOfLast { it["role"] == "assistant" }
                    if (previous >= 0) {
                        val old = out[previous].toMutableMap()
                        val calls = ((old["toolCalls"] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()).toMutableList()
                        calls.add(tool)
                        old["toolCalls"] = calls
                        out[previous] = old
                    } else {
                        out.add(linkedMapOf("id" to "toolmsg-${m.id}", "role" to "assistant", "content" to "",
                            "toolCalls" to listOf(tool), "status" to "done"))
                    }
                }
                else -> {
                    val role = when (m.role) {
                        MsgRole.USER -> "user"
                        MsgRole.NOTE -> "system"
                        else -> "assistant"
                    }
                    val row = linkedMapOf<String, Any?>(
                        "id" to m.id.toString(), "role" to role, "content" to m.content,
                        "reasoning" to m.reasoning,
                        "reasoningStatus" to if (m.streaming && m.reasoning.isNotBlank()) "streaming" else "done",
                        "status" to if (m.streaming) "streaming" else "done",
                        "createdAt" to m.time
                    )
                    // token 用量：只在真有数据时才放进 JSON，
                    // 前端按「字段不存在」判断是否渲染，避免出现一排 0。
                    //
                    // 字段名严格对齐 zen-ai-chat-ui 的 MessageMeta 组件：
                    // 它读 message.meta.usage.{prompt,completion,total,cached,reasoning}
                    // 与 message.meta.{durationMs,firstTokenMs}，自己排版成
                    // 「↑输入 ↓输出 · 首字 1.2s · 3.4s」并附带中文 tooltip。
                    // 不要改用自定义字段名，否则组件不会渲染。
                    if (m.promptTokens > 0 || m.completionTokens > 0
                        || m.durationMs > 0 || m.firstTokenMs >= 0) {
                        val meta = linkedMapOf<String, Any?>()
                        if (m.promptTokens > 0 || m.completionTokens > 0) {
                            val usage = linkedMapOf<String, Any?>(
                                "prompt" to m.promptTokens,
                                "completion" to m.completionTokens,
                                "total" to (m.promptTokens + m.completionTokens)
                            )
                            // 这两个为 0 时不要写：组件见到数字就会显示「思考 0」。
                            if (m.cachedTokens > 0) usage["cached"] = m.cachedTokens
                            if (m.reasoningTokens > 0) usage["reasoning"] = m.reasoningTokens
                            meta["usage"] = usage
                        }
                        if (m.durationMs > 0) meta["durationMs"] = m.durationMs
                        if (m.firstTokenMs >= 0) meta["firstTokenMs"] = m.firstTokenMs
                        // 工具调用次数作为附加信息，跟随 meta 一起显示。
                        // 必须在 row["meta"] 赋值前放进 map，否则不会被序列化。
                        if (m.toolCallCount > 0) {
                            meta["extra"] = listOf(linkedMapOf<String, Any?>(
                                "label" to "工具", "value" to "${m.toolCallCount} 次",
                                "title" to "本轮调用的工具次数"
                            ))
                        }
                        row["meta"] = meta
                    }
                    if (m.images.isNotEmpty()) {
                        val at = ArrayList<Map<String, Any?>>()
                        m.images.forEachIndexed { i, path ->
                            val file = File(path)
                            val url = ImageUtil.fileToDataUrl(file)
                            if (url != null) at.add(mapOf("id" to "${m.id}-$i", "name" to file.name,
                                "size" to file.length(), "type" to "image/jpeg", "preview" to url))
                        }
                        if (at.isNotEmpty()) row["attachments"] = at
                    }
                    // 普通文件：没有 preview，组件会按 type 不是 image/* 渲染成文件标签。
                    if (m.files.isNotEmpty()) {
                        val at = (row["attachments"] as? MutableList<Map<String, Any?>>)
                            ?: ArrayList<Map<String, Any?>>()
                        m.files.forEachIndexed { i, f ->
                            at.add(linkedMapOf<String, Any?>(
                                "id" to "${m.id}-f$i", "name" to f.name,
                                "size" to f.size, "type" to f.mime.ifBlank { "application/octet-stream" }
                            ))
                        }
                        row["attachments"] = at
                    }
                    out.add(row)
                }
            }
        }
        val pending = state.pendingImages.mapIndexedNotNull { i, path ->
            val file = File(path)
            val url = ImageUtil.fileToDataUrl(file) ?: return@mapIndexedNotNull null
            mapOf("id" to i, "name" to file.name, "preview" to url)
        }
        val pendingDocs = state.pendingFiles.mapIndexed { i, f ->
            mapOf("id" to i, "name" to f.name, "size" to f.size, "sizeText" to f.sizeText())
        }
        val profile = AiConfig.getActive(app)
        val sessions: List<Map<String, Any?>> = persistence.listSessions().map { s ->
            mapOf("id" to s.id, "title" to (s.title ?: "未命名"),
                "meta" to "${s.messageCount} 条 · ${android.text.format.DateFormat.format("MM-dd HH:mm", s.updatedAt)}",
                "searchText" to "${s.title} ${profile?.model ?: ""}")
        }
        return linkedMapOf(
            "messages" to out, "sessions" to sessions, "sessionId" to sessionId,
            "title" to state.title, "busy" to state.busy, "busyRound" to state.busyRound,
            "model" to (profile?.let { "${it.name} · ${it.model}" } ?: "未配置模型"),
            "pendingImages" to pending, "contextText" to state.contextText,
            "pendingFiles" to pendingDocs,
            "workspaceId" to workspaceId
        )
    }

    private fun notifySnapshot() = notifyEvent("snapshot", snapshot())

    private fun notifyEvent(type: String, payload: Map<String, Any?>) {
        for (o in observers) emit(o, type, payload)
    }

    private fun emit(observer: Observer, type: String, payload: Map<String, Any?>) {
        try { observer.onChatEvent(type, payload) } catch (_: Throwable) {}
    }
}
