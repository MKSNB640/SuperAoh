package com.superaoh.ui.compose

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import com.superaoh.core.AiConfig
import com.superaoh.core.Workspace
import com.superaoh.ui.AiSettingsActivity
import com.superaoh.ui.chat.ChatSessionController
import com.superaoh.ui.chat.ChatSessionRegistry

/**
 * 全屏原生 Compose 聊天页。
 *
 * 这里不再创建 ChatWebView：WebView 仍保留给游戏内覆盖面板使用，
 * 主页「开始对话」和工作区 AI 入口都走这一页，因而可以真正使用
 * ChatScreen 的原生 Compose UI，同时复用同一个会话控制器和持久化数据。
 */
class ChatComposeActivity : ComponentActivity(), ChatSessionController.Observer {
    companion object {
        const val EXTRA_WORKSPACE_ID = "workspaceId"
        private const val REQUEST_IMAGE = 7361
        private const val REQUEST_FILE = 7362
    }

    private lateinit var controller: ChatSessionController
    private val sessions = mutableStateOf<List<SessionMeta>>(emptyList())
    private val models = mutableStateOf<List<ChatModelOption>>(emptyList())
    private val modelLabel = mutableStateOf("未配置模型")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val wsId = intent.getStringExtra(EXTRA_WORKSPACE_ID)
            ?: Workspace.getActiveId(this)
            ?: Workspace.listAll(this).firstOrNull()?.id
        controller = ChatSessionRegistry.get(this, wsId)
        refreshLists()
        controller.addObserver(this)

        setContent {
            ChatComposeRoot(
                controller = controller,
                sessions = sessions.value,
                models = models.value,
                modelLabel = modelLabel.value,
                onSend = controller::send,
                onShare = ::shareText,
                onPickImage = { pickDocument("image/*", REQUEST_IMAGE) },
                onPickFile = { pickDocument("*/*", REQUEST_FILE) },
                onRemoveImage = controller::removePendingImage,
                onRemoveFile = controller::removePendingFile,
                onCancel = controller::cancel,
                onNewSession = {
                    controller.newSession()
                    refreshSessionList()
                },
                onOpenSession = {
                    controller.openSession(it)
                    refreshSessionList()
                },
                onDeleteSession = {
                    controller.deleteSession(it)
                    refreshSessionList()
                },
                onEffortChange = controller::setReasoningEffort,
                onSelectModel = {
                    controller.selectProfile(it)
                    refreshModelList()
                    refreshSessionList()
                },
                onOpenSettings = {
                    startActivity(Intent(this, AiSettingsActivity::class.java))
                },
                onOpenTools = {
                    startActivity(Intent(this, ToolsActivity::class.java))
                },
                onBack = { finish() },
            )
        }
    }

    @Composable
    private fun ChatComposeRoot(
        controller: ChatSessionController,
        sessions: List<SessionMeta>,
        models: List<ChatModelOption>,
        modelLabel: String,
        onSend: (String) -> Unit,
        onShare: (String) -> Unit,
        onPickImage: () -> Unit,
        onPickFile: () -> Unit,
        onRemoveImage: (Int) -> Unit,
        onRemoveFile: (Int) -> Unit,
        onCancel: () -> Unit,
        onNewSession: () -> Unit,
        onOpenSession: (String) -> Unit,
        onDeleteSession: (String) -> Unit,
        onEffortChange: (String) -> Unit,
        onSelectModel: (String) -> Unit,
        onOpenSettings: () -> Unit,
        onOpenTools: () -> Unit,
        onBack: () -> Unit,
    ) {
        val density = LocalDensity.current.density
        val markdown = androidx.compose.runtime.remember(this, density) {
            MarkdownRenderer(this, density)
        }
        MaterialTheme {
            ChatScreen(
                state = controller.state,
                sessions = sessions,
                modelLabel = modelLabel,
                models = models,
                busyRound = controller.state.busyRound,
                markdown = markdown,
                onSend = onSend,
                onShare = onShare,
                onPickImage = onPickImage,
                onPickFile = onPickFile,
                onRemoveImage = onRemoveImage,
                onRemoveFile = onRemoveFile,
                onCancel = onCancel,
                onNewSession = onNewSession,
                onOpenSession = onOpenSession,
                onDeleteSession = onDeleteSession,
                onEffortChange = onEffortChange,
                onSelectModel = onSelectModel,
                onOpenSettings = onOpenSettings,
                onOpenTools = onOpenTools,
                onBack = onBack,
            )
        }
    }

    private fun refreshLists() {
        refreshModelList()
        refreshSessionList()
    }

    private fun refreshModelList() {
        if (!::controller.isInitialized) return
        val profileList = AiConfig.listProfiles(this)
        val active = AiConfig.getActive(this)
        models.value = profileList.filter { it.usable() }.map {
            ChatModelOption(
                id = it.id ?: "",
                title = it.name?.ifBlank { "未命名配置" } ?: "未命名配置",
                model = it.model ?: "未填写模型",
                active = it.id == active?.id,
            )
        }.filter { it.id.isNotBlank() }
        modelLabel.value = active?.displayLine() ?: "未配置模型"
    }

    private fun refreshSessionList() {
        if (!::controller.isInitialized) return
        sessions.value = controller.listSessions()
    }

    private fun pickDocument(type: String, requestCode: Int) {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                setType(type)
            }, requestCode)
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开文件选择器：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            REQUEST_IMAGE -> controller.attachImage(uri)
            REQUEST_FILE -> controller.attachFile(uri)
        }
    }

    private fun shareText(text: String) {
        if (text.isBlank()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "分享回复"))
    }

    override fun onChatEvent(type: String, payload: Map<String, Any?>) {
        // Compose 直接订阅 ChatState；snapshot/delta 无需再通过 Activity 排队。
        // 这里只保留控制器发送的一次性提示。
        if (type != "notice") return
        val text = payload["text"] as? String ?: return
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) {
            controller.onHostResumed()
            refreshLists()
        }
    }

    override fun onPause() {
        if (::controller.isInitialized) controller.persist()
        super.onPause()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.removeObserver(this)
        super.onDestroy()
    }
}
