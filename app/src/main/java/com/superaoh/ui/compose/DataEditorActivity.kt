package com.superaoh.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superaoh.core.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// 与主页同一套深色 token：近黑底 + 抬升面板 + 黄绿强调
private val Ink = Color(0xFF0A0D12)
private val Panel = Color(0xFF121821)
private val PanelRaised = Color(0xFF19222D)
private val Hairline = Color(0xFF27313D)
private val InkText = Color(0xFFF0F3F7)
private val InkMuted = Color(0xFF9AA8B8)
private val Accent = Color(0xFFB7D65C)
private val Cyan = Color(0xFF7AB7D8)
private val Danger = Color(0xFFFF9B91)

/**
 * 独立数据编辑器。
 *
 * 不启动游戏、不进入 GL 场景：直接读工作区 APK 里的数据文件，
 * 编辑后写回工作区 overlay，游戏下次启动时自然生效。
 */
class DataEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_WORKSPACE_ID) ?: Workspace.getActiveId(this)
        val workspace = id?.let { Workspace.load(this, it) }
        if (workspace == null) {
            finish()
            return
        }
        Workspace.setActiveId(this, workspace.id)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Ink) {
                    DataEditorScreen(workspace = workspace, onBack = { finish() })
                }
            }
        }
    }

    companion object {
        const val EXTRA_WORKSPACE_ID = "dataEditorWorkspaceId"
    }
}

private enum class Page { HOME, CIVILIZATIONS, GOVERNMENTS, GOVERNMENT_EDIT }

@Composable
private fun DataEditorScreen(workspace: Workspace, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.HOME) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val civRepo = remember { CivilizationDataRepository(context, workspace) }
    val govRepo = remember { GovernmentDataRepository(context, workspace) }
    val civRecords = remember { mutableStateListOf<CivilizationDataRepository.Record>() }
    val govRecords = remember { mutableStateListOf<MutableMap<String, Any?>>() }
    var govFileName by remember { mutableStateOf("GovernmentsV2.json") }
    var editingCiv by remember { mutableStateOf<CivilizationDataRepository.Record?>(null) }
    var editingGov by remember { mutableStateOf<MutableMap<String, Any?>?>(null) }
    var govDocument by remember { mutableStateOf<GovernmentDataRepository.Document?>(null) }

    fun run(block: suspend () -> String) {
        busy = true
        error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { notice = it }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        EditorTopBar(
            title = when (page) {
                Page.HOME -> "数据编辑器"
                Page.CIVILIZATIONS -> "文明编辑器"
                Page.GOVERNMENTS -> "政体列表"
                Page.GOVERNMENT_EDIT -> "编辑政体"
            },
            subtitle = workspace.name,
            onBack = { if (page == Page.HOME) onBack() else page = Page.HOME },
            action = when (page) {
                Page.CIVILIZATIONS -> "保存" to {
                    run { civRepo.save(civRecords.toList()); "文明已保存，下次启动游戏生效" }
                }
                Page.GOVERNMENTS, Page.GOVERNMENT_EDIT -> "保存" to {
                    val doc = govDocument ?: return@to
                    run { govRepo.save(doc); "政体已保存，下次启动游戏生效" }
                }
                else -> null
            },
            busy = busy,
        )

        if (busy) Box(Modifier.fillMaxWidth().height(2.dp).background(Accent))

        error?.let { Banner(it, Danger) { error = null } }
        notice?.let { Banner(it, Accent) { notice = null } }

        when (page) {
            Page.HOME -> HomePage(
                onCivilizations = {
                    run {
                        val loaded = civRepo.load()
                        withContext(Dispatchers.Main) {
                            civRecords.clear()
                            civRecords.addAll(loaded)
                            page = Page.CIVILIZATIONS
                        }
                        "已读取 ${loaded.size} 个文明"
                    }
                },
                onGovernments = {
                    run {
                        val doc = govRepo.loadFromWorkspace()
                        withContext(Dispatchers.Main) {
                            govDocument = doc
                            govFileName = doc.fileName
                            govRecords.clear()
                            govRecords.addAll(doc.records)
                            page = Page.GOVERNMENTS
                        }
                        "已读取 ${doc.records.size} 个政体"
                    }
                },
            )

            Page.CIVILIZATIONS -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${civRecords.size} 个文明", color = InkMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        FilledTonalButton(onClick = {
                            runCatching { civRepo.create(civRecords) }
                                .onSuccess { civRecords.add(it); editingCiv = it }
                                .onFailure { error = it.message }
                        }) { Text("新建") }
                    }
                }
                items(civRecords.toList(), key = { it.tag }) { record ->
                    CivRow(
                        record = record,
                        onClick = { editingCiv = record },
                        onDelete = { civRecords.remove(record) },
                    )
                }
            }

            Page.GOVERNMENTS -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${govRecords.size} 个政体", color = InkText, fontWeight = FontWeight.SemiBold)
                            Text(govFileName, color = InkMuted, fontSize = 11.sp)
                        }
                        FilledTonalButton(onClick = {
                            govDocument?.let { doc ->
                                runCatching { govRepo.add(doc) }
                                    .onSuccess { govRecords.add(it); editingGov = it; page = Page.GOVERNMENT_EDIT }
                                    .onFailure { error = it.message }
                            }
                        }) { Text("新建") }
                    }
                }
                items(govRecords.toList()) { record ->
                    GovRow(
                        name = govRepo.name(record, govRecords.indexOf(record)),
                        detail = "${record.size} 个字段",
                        onClick = { editingGov = record; page = Page.GOVERNMENT_EDIT },
                        onDelete = { govRecords.remove(record) },
                    )
                }
            }

            Page.GOVERNMENT_EDIT -> {
                val record = editingGov
                if (record == null) {
                    page = Page.GOVERNMENTS
                } else {
                    GovernmentForm(record = record, repo = govRepo, onDone = { page = Page.GOVERNMENTS })
                }
            }
        }
    }

    editingCiv?.let { current ->
        CivilizationDialog(
            record = current,
            onDismiss = { editingCiv = null },
            onSave = { name, r, g, b, ribbonTag, colors ->
                runCatching { civRepo.update(current, name, r, g, b, ribbonTag, colors) }
                    .onSuccess { updated ->
                        val index = civRecords.indexOfFirst { it.tag == current.tag }
                        if (index >= 0) civRecords[index] = updated
                        editingCiv = null
                    }
                    .onFailure { error = it.message ?: "保存失败" }
            },
        )
    }
}

// ---------------- 顶部栏 ----------------

@Composable
private fun EditorTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    action: Pair<String, () -> Unit>?,
    busy: Boolean,
) {
    Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(PanelRaised, RoundedCornerShape(12.dp)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("←", color = InkText, fontSize = 18.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = InkText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = InkMuted, fontSize = 11.sp, maxLines = 1)
            }
            if (action != null) {
                TextButton(onClick = action.second, enabled = !busy) {
                    Text(action.first, color = if (busy) InkMuted else Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
}

@Composable
private fun Banner(text: String, tint: Color, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = tint, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("关闭", color = tint, fontSize = 11.sp)
    }
}

// ---------------- 首页 ----------------

@Composable
private fun HomePage(onCivilizations: () -> Unit, onGovernments: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("直接改数据，不启动游戏。", color = InkText, fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "编辑结果写入工作区覆盖层，原始游戏包不动。下次进入游戏或编辑器时自动生效。",
            color = InkMuted, fontSize = 13.sp, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(22.dp))
        FeatureCard(
            title = "文明编辑器",
            detail = "名称、国家颜色、服务徽章",
            tag = "CIVILIZATIONS",
            tint = Accent,
            onClick = onCivilizations,
        )
        Spacer(Modifier.height(12.dp))
        FeatureCard(
            title = "政体编辑器",
            detail = "经济 / 军事 / 行政 / 文明化参数",
            tag = "GOVERNMENTS",
            tint = Cyan,
            onClick = onGovernments,
        )
    }
}

@Composable
private fun FeatureCard(title: String, detail: String, tag: String, tint: Color, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Panel, RoundedCornerShape(20.dp))
            .border(1.dp, Hairline, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Text(title, color = InkText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(detail, color = InkMuted, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tag, color = tint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("打开 →", color = tint, fontSize = 12.sp)
        }
    }
}

// ---------------- 列表行 ----------------

@Composable
private fun CivRow(
    record: CivilizationDataRepository.Record,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(Color(record.red, record.green, record.blue), RoundedCornerShape(12.dp))
                .border(1.dp, Hairline, RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(record.name, color = InkText, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                "RGB ${record.red}, ${record.green}, ${record.blue}" +
                        if (record.ribbonColors.isEmpty()) "" else " · 徽章 ${record.ribbonColors.size} 色",
                color = InkMuted, fontSize = 11.sp, maxLines = 1,
            )
        }
        TextButton(onClick = onDelete) { Text("删除", color = Danger, fontSize = 12.sp) }
    }
}

@Composable
private fun GovRow(name: String, detail: String, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = InkText, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(detail, color = InkMuted, fontSize = 11.sp)
        }
        TextButton(onClick = onDelete) { Text("删除", color = Danger, fontSize = 12.sp) }
    }
}

// ---------------- 文明编辑对话框 ----------------

@Composable
private fun CivilizationDialog(
    record: CivilizationDataRepository.Record,
    onDismiss: () -> Unit,
    onSave: (String, Int, Int, Int, String, List<CivilizationDataRepository.RibbonColor>) -> Unit,
) {
    var name by remember(record.tag) { mutableStateOf(record.name) }
    var red by remember(record.tag) { mutableStateOf(record.red) }
    var green by remember(record.tag) { mutableStateOf(record.green) }
    var blue by remember(record.tag) { mutableStateOf(record.blue) }
    var ribbonTag by remember(record.tag) { mutableStateOf(record.ribbonTag) }
    val colors = remember(record.tag) {
        mutableStateListOf<CivilizationDataRepository.RibbonColor>().also { it.addAll(record.ribbonColors) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("编辑文明", color = InkText, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.height(440.dp).verticalScroll(rememberScrollState())) {
                Text("基本信息", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("TAG: ${record.tag}", color = InkMuted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("文明名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(18.dp))
                Text("文明颜色", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(52.dp)
                            .background(Color(red, green, blue), RoundedCornerShape(14.dp))
                            .border(1.dp, Hairline, RoundedCornerShape(14.dp)),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("RGB $red, $green, $blue", color = InkText, fontSize = 13.sp)
                        Text("国家在地图上的填充色", color = InkMuted, fontSize = 11.sp)
                    }
                }
                ColorSlider("R", red) { red = it }
                ColorSlider("G", green) { green = it }
                ColorSlider("B", blue) { blue = it }

                Spacer(Modifier.height(18.dp))
                Text("服务徽章（Service Ribbon）", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ribbonTag, onValueChange = { ribbonTag = it },
                    label = { Text("徽章标签（SR Tag）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("徽章颜色（${colors.size} 个）", color = InkMuted, fontSize = 11.sp)
                colors.forEachIndexed { index, color ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(28.dp)
                                .background(Color(color.red, color.green, color.blue), RoundedCornerShape(8.dp))
                                .border(1.dp, Hairline, RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${color.red}, ${color.green}, ${color.blue}",
                            color = InkText, fontSize = 12.sp, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { colors.removeAt(index) }) {
                            Text("移除", color = Danger, fontSize = 11.sp)
                        }
                    }
                }
                TextButton(onClick = { colors.add(CivilizationDataRepository.RibbonColor(red, green, blue)) }) {
                    Text("+ 添加当前颜色", color = Accent, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, red, green, blue, ribbonTag, colors.toList()) }) {
                Text("完成", color = Accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = InkMuted) } },
    )
}

@Composable
private fun ColorSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = InkMuted, fontSize = 12.sp, modifier = Modifier.width(18.dp))
        Slider(
            value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..255f, modifier = Modifier.weight(1f),
        )
        Text(value.toString(), color = InkText, fontSize = 12.sp, modifier = Modifier.width(34.dp))
    }
}

// ---------------- 政体表单 ----------------

@Composable
private fun GovernmentForm(
    record: MutableMap<String, Any?>,
    repo: GovernmentDataRepository,
    onDone: () -> Unit,
) {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            FormGroup("基本信息") {
                TextInputRow("政体名称", repo.text(record, "Name")) { repo.setText(record, "Name", it) }
                TextInputRow("Extra_Tag（可空）", repo.text(record, "Extra_Tag")) { repo.setText(record, "Extra_Tag", it) }
                if (repo.has(record, "GOV_GROUP_ID")) {
                    ChoiceRow(
                        label = "政体组（GOV_GROUP_ID）",
                        value = repo.number(record, "GOV_GROUP_ID").toInt(),
                        choices = repo.groupChoices,
                    ) { repo.setNumber(record, "GOV_GROUP_ID", it.toDouble()) }
                }
                if (repo.has(record, "AI_TYPE")) {
                    TextInputRow("AI 类型（AI_TYPE）", repo.text(record, "AI_TYPE")) { repo.setText(record, "AI_TYPE", it) }
                }
                if (repo.has(record, "REVOLUTIONARY")) {
                    SwitchRow("革命政体（REVOLUTIONARY）", repo.flag(record, "REVOLUTIONARY")) {
                        repo.setFlag(record, "REVOLUTIONARY", it)
                    }
                }
                ColorRow(
                    "政体颜色",
                    repo.color(record, "R"), repo.color(record, "G"), repo.color(record, "B"),
                ) { r, g, b ->
                    repo.setNumber(record, "R", r.toDouble())
                    repo.setNumber(record, "G", g.toDouble())
                    repo.setNumber(record, "B", b.toDouble())
                }
            }
        }
        item { FieldGroup("经济参数", repo.economyFields, record, repo) }
        item { FieldGroup("军事参数", repo.militaryFields, record, repo) }
        item { FieldGroup("行政参数", repo.administrationFields, record, repo) }
        item { FieldGroup("文明化参数", repo.civilizationFields, record, repo) }
        item {
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("返回政体列表", color = Accent)
            }
        }
    }
}

@Composable
private fun FormGroup(title: String, content: ColumnContent) {
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp))
            .border(1.dp, Hairline, RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        Text(title, color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

private typealias ColumnContent = @Composable () -> Unit

@Composable
private fun FieldGroup(
    title: String,
    fields: List<GovernmentDataRepository.Field>,
    record: MutableMap<String, Any?>,
    repo: GovernmentDataRepository,
) {
    val visible = fields.filter { repo.has(record, it.key) }
    if (visible.isEmpty()) return
    FormGroup(title) {
        visible.forEach { field ->
            when {
                field.choices.isNotEmpty() ->
                    ChoiceRow(field.label, repo.number(record, field.key).toInt(), field.choices) {
                        repo.setNumber(record, field.key, it.toDouble())
                    }
                record[field.key] is Boolean ->
                    SwitchRow(field.label, repo.flag(record, field.key)) { repo.setFlag(record, field.key, it) }
                else ->
                    NumberRow(field, repo.number(record, field.key)) { repo.setNumber(record, field.key, it) }
            }
        }
    }
}

@Composable
private fun TextInputRow(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun NumberRow(field: GovernmentDataRepository.Field, value: Double, onChange: (Double) -> Unit) {
    val decimals = if (field.step >= 1.0) 0 else if (field.step >= 0.1) 1 else 2
    val display = if (field.percent) {
        String.format("%.${decimals}f%%", value * 100.0)
    } else {
        String.format("%.${decimals}f", value)
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${field.label}（${field.key}）", color = InkMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(display, color = InkText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.toFloat().coerceIn(field.min.toFloat(), field.max.toFloat()),
            onValueChange = { raw ->
                // 对齐到 step 网格，并消除浮点误差（0.1 累加会变成 0.30000000000000004）
                val snapped = Math.round(raw / field.step) * field.step
                onChange(Math.round(snapped * 1e6) / 1e6)
            },
            valueRange = field.min.toFloat()..field.max.toFloat(),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = InkText, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    value: Int,
    choices: List<Pair<Int, String>>,
    onChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = InkMuted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Box {
            Row(
                Modifier.fillMaxWidth().background(PanelRaised, RoundedCornerShape(12.dp))
                    .border(1.dp, Hairline, RoundedCornerShape(12.dp))
                    .clickable { expanded = true }.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    choices.firstOrNull { it.first == value }?.second ?: value.toString(),
                    color = InkText, fontSize = 13.sp, modifier = Modifier.weight(1f),
                )
                Text("▾", color = InkMuted, fontSize = 12.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (id, text) ->
                    DropdownMenuItem(
                        text = { Text(text, fontSize = 13.sp) },
                        onClick = { onChange(id); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorRow(label: String, red: Int, green: Int, blue: Int, onChange: (Int, Int, Int) -> Unit) {
    var r by remember(label) { mutableStateOf(red) }
    var g by remember(label) { mutableStateOf(green) }
    var b by remember(label) { mutableStateOf(blue) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).background(Color(r, g, b), RoundedCornerShape(10.dp))
                    .border(1.dp, Hairline, RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, color = InkText, fontSize = 13.sp)
                Text("RGB $r, $g, $b", color = InkMuted, fontSize = 11.sp)
            }
        }
        ColorSlider("R", r) { r = it; onChange(r, g, b) }
        ColorSlider("G", g) { g = it; onChange(r, g, b) }
        ColorSlider("B", b) { b = it; onChange(r, g, b) }
    }
}