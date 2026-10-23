package com.superaoh.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share

import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString as UiAnnotatedString
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.text.style.TextOverflow

/**
 * SuperAoh 的聊天页。视觉方向：浅色工作台、清晰的工具步骤、低噪声会话抽屉。
 * 所有回调保持原有契约，重设计只改变呈现，不改变 Agent/会话业务逻辑。
 */
private val PageBackground = Color(0xFF212121)
private val SurfaceWhite = Color(0xFF2F2F2F)
private val SurfaceSoft = Color(0xFF3A3A3A)
private val SurfaceBlue = Color(0xFF343541)
private val TextStrong = Color(0xFFECECEC)
private val TextMuted = Color(0xFFC5C5C5)
private val TextQuiet = Color(0xFF9A9A9A)
private val PrimaryBlue = Color(0xFF10A37F)
private val PrimaryBlueDark = Color(0xFF72D6B4)
private val SuccessGreen = Color(0xFF35B67A)
private val ErrorRed = Color(0xFFE06A6A)
private val DividerBlue = Color(0xFF414141)
private val CodeBackground = Color(0xFF252525)

@Composable
fun ChatScreen(
    state: ChatState,
    sessions: List<SessionMeta>,
    modelLabel: String,
    models: List<ChatModelOption>,
    busyRound: Int,
    markdown: MarkdownRenderer,
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
    var drawerOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    Box(Modifier.fillMaxSize().background(PageBackground)) {
        Column(Modifier.fillMaxSize()) {
            ChatTopBar(
                title = state.title,
                modelLabel = modelLabel,
                models = models,
                busy = state.busy,
                busyRound = busyRound,
                effort = state.reasoningEffort,
                contextText = state.contextText,
                onMenu = { drawerOpen = true },
                onBack = onBack,
                onEffortChange = onEffortChange,
                onSelectModel = onSelectModel,
                onOpenSettings = onOpenSettings,
                onOpenTools = onOpenTools,
            )
            MessageList(
                messages = state.messages,
                markdown = markdown,
                onShare = onShare,
                onQuickPrompt = { state.draft = it },
                modifier = Modifier.weight(1f),
            )
            InputBar(
                busy = state.busy,
                pendingImages = state.pendingImages,
                pendingFiles = state.pendingFiles,
                onSend = onSend,
                onPickImage = onPickImage,
                onPickFile = onPickFile,
                onRemoveImage = onRemoveImage,
                onRemoveFile = onRemoveFile,
                onCancel = onCancel,
                draft = state.draft,
                onDraftChange = { state.draft = it },
            )
        }

        AnimatedVisibility(visible = drawerOpen) {
            SessionDrawer(
                sessions = sessions,
                onClose = { drawerOpen = false },
                onNew = {
                    drawerOpen = false
                    onNewSession()
                },
                onOpen = {
                    drawerOpen = false
                    onOpenSession(it)
                },
                onDelete = onDeleteSession,
            )
        }
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    modelLabel: String,
    models: List<ChatModelOption>,
    busy: Boolean,
    busyRound: Int,
    effort: String,
    contextText: String,
    onMenu: () -> Unit,
    onBack: () -> Unit,
    onEffortChange: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTools: () -> Unit,
) {
    Surface(color = PageBackground, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, "返回", tint = TextMuted)
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        title.ifBlank { "新对话" },
                        color = TextStrong,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(7.dp).clip(CircleShape)
                                .background(if (busy) PrimaryBlue else SuccessGreen),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (busy) "处理中${if (busyRound > 0) " · 第 $busyRound 轮" else ""}" else "在线 · 可开始任务",
                            color = if (busy) PrimaryBlue else TextMuted,
                            fontSize = 11.sp,
                        )
                        if (contextText.isNotBlank()) {
                            Text("  ·  $contextText", color = TextQuiet, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                IconButton(onClick = onOpenTools) {
                    Icon(Icons.Filled.Build, "工具与扩展", tint = TextMuted)
                }
                IconButton(onClick = onMenu) {
                    Icon(Icons.Filled.Menu, "会话列表", tint = TextMuted)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 60.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModelPicker(label = modelLabel, models = models, onSelect = onSelectModel)
                EffortPicker(
                    effort = effort,
                    model = models.firstOrNull { it.active }?.model.orEmpty(),
                    onChange = onEffortChange,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Settings, "模型设置", tint = TextMuted, modifier = Modifier.size(19.dp))
                }
            }
            HorizontalDivider(color = DividerBlue, thickness = 1.dp)
        }
    }
}

@Composable
private fun ModelPicker(label: String, models: List<ChatModelOption>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            color = SurfaceBlue,
        ) {
            Row(
                Modifier.widthIn(max = 210.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = PrimaryBlueDark, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(3.dp))
                Icon(Icons.Filled.KeyboardArrowDown, "切换模型", tint = PrimaryBlue, modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (models.isEmpty()) {
                DropdownMenuItem(text = { Text("未配置可用模型") }, onClick = { expanded = false })
            } else {
                models.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    (if (option.active) "✓  " else "") + option.title,
                                    fontSize = 13.sp,
                                    color = if (option.active) PrimaryBlueDark else TextStrong,
                                )
                                Text(option.model, fontSize = 10.sp, color = TextQuiet)
                            }
                        },
                        onClick = { onSelect(option.id); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun EffortPicker(effort: String, model: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val supportedEfforts = remember(model) { ReasoningEfforts.forModel(model) }
    Box {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(12.dp),
            color = if (effort.isBlank()) SurfaceSoft else SurfaceBlue,
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Tune, "思考等级", tint = PrimaryBlue, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    ReasoningEfforts.label(effort),
                    color = if (effort.isBlank()) TextMuted else PrimaryBlueDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("默认（不指定）", fontSize = 13.sp) },
                onClick = { onChange(ReasoningEfforts.NONE); open = false },
            )
            HorizontalDivider()
            supportedEfforts.forEach { value ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${ReasoningEfforts.label(value)}   $value",
                            fontSize = 13.sp,
                            color = if (value == effort) PrimaryBlue else Color.Unspecified,
                        )
                    },
                    onClick = { onChange(value); open = false },
                )
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    markdown: MarkdownRenderer,
    onShare: (String) -> Unit,
    onQuickPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.content, messages.lastOrNull()?.reasoning) {
        // 增量输出期间不要启动动画队列，否则高频 delta 会让动画一直追不上
        // 内容，用户看起来就像只能在最终结果时才看到更新。
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE)
        }
    }

    if (messages.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 22.dp),
            ) {
                Text(
                    "今天有什么想法？",
                    color = TextStrong,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(20.dp))
                val prompts = listOf(
                    "检查这个工作区的项目结构和关键文件",
                    "分析这段报错并给出修复建议：",
                    "规划一个安全、可验证的修改方案",
                    "总结当前项目可用的工具与运行环境",
                )
                prompts.forEach { prompt ->
                    Surface(
                        onClick = { onQuickPrompt(prompt) },
                        color = SurfaceSoft,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(prompt, color = TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ArrowBack, "使用此提示", tint = TextQuiet,
                                modifier = Modifier.size(16.dp).rotate(180f))
                        }
                    }
                }
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            when (message.role) {
                MsgRole.USER -> UserBubble(message)
                MsgRole.ASSISTANT -> AssistantBubble(message, markdown, onShare)
                MsgRole.TOOL -> ToolCard(message)
                MsgRole.NOTE -> NoteRow(message)
            }
        }
    }
}

@Composable
private fun UserBubble(m: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            if (m.images.isNotEmpty()) {
                ImageStrip(m.images, alignEnd = true)
                Spacer(Modifier.height(5.dp))
            }
            if (m.files.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp), horizontalAlignment = Alignment.End) {
                    m.files.forEach { AttachedFileChip(it.name, it.sizeText(), removable = false) }
                }
                Spacer(Modifier.height(5.dp))
            }
            if (m.content.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp),
                    color = SurfaceBlue,
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Text(
                        m.content,
                        color = Color.White,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(m: ChatMessage, markdown: MarkdownRenderer, onShare: (String) -> Unit) {
    // 流式阶段只画原始文本；Markwon 解析每个 token 会长时间占用主线程，
    // 直到生成结束才会出现“突然显示全部”的假流式效果。结束后再解析一次 Markdown。
    val rendered = remember(m.content, m.streaming) {
        if (m.content.isBlank() || m.streaming) null else markdown.render(
            md = m.content,
            textColor = TextStrong,
            mutedColor = TextMuted,
            linkColor = PrimaryBlue,
            baseFontSize = 14.sp,
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(Modifier.widthIn(max = 340.dp)) {
            Text("SuperAoh", color = PrimaryBlueDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(0.dp),
                color = PageBackground,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                    if (m.reasoning.isNotBlank()) {
                        ReasoningBlock(m.reasoning, m.streaming)
                        if (m.content.isNotBlank()) Spacer(Modifier.height(8.dp))
                    }
                    if (m.streaming) {
                        if (m.content.isBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), color = PrimaryBlue, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (m.reasoning.isNotBlank()) "正在思考…" else "正在组织答案…",
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                )
                            }
                        } else {
                            Text(
                                m.content,
                                color = TextStrong,
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                            )
                            Text("▍", color = PrimaryBlue, fontSize = 13.sp)
                        }
                    } else {
                        Text(
                            rendered ?: AnnotatedString(""),
                            color = TextStrong,
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                        )
                    }
                }
            }
            if (m.content.isNotBlank() && !m.streaming) {
                Row(
                    Modifier.padding(start = 4.dp, top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val clipboard = LocalClipboardManager.current
                    IconButton(onClick = { clipboard.setText(UiAnnotatedString(m.content)) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.ContentCopy, "复制回复", tint = TextQuiet, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = { onShare(m.content) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Share, "分享回复", tint = TextQuiet, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningBlock(text: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(streaming) }
    val scrollState = rememberScrollState()
    LaunchedEffect(streaming) {
        if (streaming) expanded = true
    }
    LaunchedEffect(text, expanded) {
        if (expanded) scrollState.scrollTo(scrollState.maxValue)
    }
    Column(
        Modifier.fillMaxWidth().border(1.dp, DividerBlue, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp)).background(SurfaceSoft),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "▾" else "▸", color = PrimaryBlue, fontSize = 13.sp)
            Spacer(Modifier.width(7.dp))
            Text("思考摘要", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(if (expanded) "收起" else "查看", color = TextQuiet, fontSize = 11.sp)
        }
        AnimatedVisibility(expanded) {
            Surface(color = CodeBackground, modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        text,
                        color = TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp).heightIn(max = 220.dp).verticalScroll(scrollState),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCard(m: ChatMessage) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.widthIn(max = 340.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(if (m.toolOk) SuccessGreen else ErrorRed))
                Spacer(Modifier.width(9.dp))
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(22.dp).clip(CircleShape).background(if (m.toolOk) Color(0xFFE4F5EC) else Color(0xFFFCE9E7)), contentAlignment = Alignment.Center) {
                                if (m.toolOk) {
                                    Icon(Icons.Filled.Check, "成功", tint = SuccessGreen, modifier = Modifier.size(14.dp))
                                } else {
                                    Text("!", color = ErrorRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.toolName.ifBlank { "工具步骤" }, color = TextStrong, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (m.toolOk) "执行完成" else "执行失败", color = if (m.toolOk) SuccessGreen else ErrorRed, fontSize = 10.sp)
                            }
                            Icon(Icons.Filled.MoreVert, if (expanded) "收起结果" else "展开结果", tint = TextQuiet, modifier = Modifier.size(17.dp))
                        }
                        if (m.toolArgs.isNotBlank()) {
                            Spacer(Modifier.height(7.dp))
                            Text(m.toolArgs, color = TextQuiet, fontSize = 11.sp, maxLines = 2)
                        }
                        AnimatedVisibility(expanded) {
                            Column {
                                Spacer(Modifier.height(8.dp))
                                Surface(color = CodeBackground, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    SelectionContainer {
                                        Text(
                                            m.toolResult.ifBlank { "（无输出）" },
                                            color = TextMuted,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(9.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(m: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(color = SurfaceSoft, shape = RoundedCornerShape(50)) {
            Text(m.content, color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun ImageStrip(paths: List<String>, alignEnd: Boolean) {
    LazyRow(
        modifier = Modifier.widthIn(max = 300.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        reverseLayout = alignEnd,
    ) {
        items(paths) { ImageThumb(it, 96.dp) }
    }
}
@Composable
private fun AttachedFileChip(
    name: String,
    sizeText: String,
    removable: Boolean,
    onRemove: () -> Unit = {},
) {
    Surface(
        color = SurfaceSoft,
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier.widthIn(max = 230.dp),
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = if (removable) 3.dp else 9.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.AttachFile, "文件", tint = PrimaryBlue, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(name, color = TextStrong, fontSize = 11.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(sizeText, color = TextQuiet, fontSize = 9.sp, maxLines = 1)
            }
            if (removable) {
                IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Filled.Close, "移除文件", tint = TextMuted, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun InputBar(

    busy: Boolean,
    pendingImages: List<String>,
    pendingFiles: List<ChatFile>,
    onSend: (String) -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onRemoveFile: (Int) -> Unit,
    onCancel: () -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
) {
    var attachmentMenu by remember { mutableStateOf(false) }

    fun doSend() {
        if (busy) return
        if (draft.isBlank() && pendingImages.isEmpty() && pendingFiles.isEmpty()) return
        onSend(draft.trim())
        onDraftChange("")
    }

    val hasAttachments = pendingImages.isNotEmpty() || pendingFiles.isNotEmpty()
    val canSend = draft.isNotBlank() || hasAttachments

    Surface(color = PageBackground, modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = DividerBlue, thickness = 1.dp)
            if (hasAttachments) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(pendingImages) { path ->
                        Box {
                            ImageThumb(path, 58.dp)
                            Surface(
                                shape = CircleShape,
                                color = TextStrong,
                                modifier = Modifier.size(19.dp).align(Alignment.TopEnd)
                                    .clickable { onRemoveImage(pendingImages.indexOf(path)) },
                            ) {
                                Icon(Icons.Filled.Close, "移除图片", tint = Color.White, modifier = Modifier.padding(3.dp))
                            }
                        }
                    }
                    items(pendingFiles) { file ->
                        AttachedFileChip(
                            name = file.name,
                            sizeText = file.sizeText(),
                            removable = true,
                            onRemove = { onRemoveFile(pendingFiles.indexOf(file)) },
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box {
                    IconButton(onClick = { attachmentMenu = true }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.AttachFile, "添加附件", tint = PrimaryBlue)
                    }
                    DropdownMenu(
                        expanded = attachmentMenu,
                        onDismissRequest = { attachmentMenu = false },
                    ) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.Image, "", tint = PrimaryBlue) },
                            text = { Text("上传图片") },
                            onClick = { attachmentMenu = false; onPickImage() },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.AttachFile, "", tint = PrimaryBlue) },
                            text = { Text("上传文件（不限格式）") },
                            onClick = { attachmentMenu = false; onPickFile() },
                        )
                    }
                }
                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (busy) "正在处理任务…" else "描述你要完成的任务…", color = TextQuiet, fontSize = 14.sp) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextStrong, fontSize = 14.sp),
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = SurfaceWhite,
                        unfocusedContainerColor = SurfaceWhite,
                        disabledContainerColor = SurfaceWhite,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = PrimaryBlue,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { doSend() }),
                )
                Spacer(Modifier.width(7.dp))
                Surface(
                    onClick = { if (busy) onCancel() else doSend() },
                    shape = CircleShape,
                    color = if (busy) ErrorRed else if (canSend) PrimaryBlue else SurfaceSoft,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (busy) Icons.Filled.Stop else Icons.Filled.Send,
                            if (busy) "停止" else "发送",
                            tint = if (busy || canSend) Color.White else TextQuiet,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Text(
                "Enter 发送 · 文件仅保存路径，AI 按需读取",
                color = TextQuiet,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.End).padding(end = 18.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun SessionDrawer(
    sessions: List<SessionMeta>,
    onClose: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    var query by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SessionMeta?>(null) }
    val filtered = remember(sessions, query) {
        if (query.isBlank()) sessions else sessions.filter {
            it.title.contains(query.trim(), ignoreCase = true)
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { onClose() }) {
        Surface(
            color = SurfaceBlue,
            modifier = Modifier.fillMaxWidth(0.88f).fillMaxSize().clickable { },
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 17.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("会话", color = TextStrong, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("历史对话", color = TextMuted, fontSize = 11.sp)
                    }
                    IconButton(onClick = onNew) { Icon(Icons.Filled.Add, "新建会话", tint = PrimaryBlue) }
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "关闭", tint = TextMuted) }
                }
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    placeholder = { Text("搜索会话…", fontSize = 13.sp, color = TextQuiet) },
                    leadingIcon = { Icon(Icons.Filled.Search, "搜索", tint = TextQuiet) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = SurfaceWhite,
                        unfocusedContainerColor = SurfaceWhite,
                        focusedTextColor = TextStrong,
                        unfocusedTextColor = TextStrong,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                HorizontalDivider(color = DividerBlue)
                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (sessions.isEmpty()) "还没有会话" else "没有匹配的会话", color = TextQuiet, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(filtered, key = { it.id }) { session ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpen(session.id) }
                                    .padding(start = 17.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Menu, "会话", tint = TextQuiet, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(session.title.ifBlank { "新对话" }, color = TextStrong, fontSize = 13.sp, maxLines = 1)
                                    Spacer(Modifier.height(3.dp))
                                    Text("${fmt.format(Date(session.updatedAt))}  ·  ${session.messageCount} 条消息", color = TextQuiet, fontSize = 10.sp)
                                }
                                IconButton(onClick = { deleteTarget = session }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Filled.Delete, "删除会话", tint = TextQuiet, modifier = Modifier.size(17.dp))
                                }
                            }
                            HorizontalDivider(color = DividerBlue, thickness = 0.7.dp, modifier = Modifier.padding(start = 47.dp))
                        }
                    }
                }
            }
        }
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话？") },
            text = { Text("“${target.title.ifBlank { "新对话" }}”及其本地对话记录将被删除。此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = { onDelete(target.id); deleteTarget = null }) {
                    Text("删除", color = ErrorRed)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}
