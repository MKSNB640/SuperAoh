package com.superaoh.ui.compose

import android.content.Context
import com.superaoh.core.Workspace
import java.io.File
import java.util.zip.ZipFile

/**
 * 政体数据读写（Governments.json / GovernmentsV2.json）。
 *
 * 文件格式见 [GameJson]：游戏用的是 libGDX 宽松 JSON，键名不带引号、
 * 数值可能带 `f` 后缀，所以必须走宽松解析器。
 *
 * 结构是固定的一组政体对象：
 * ```
 * { Government: [ { Name: "Monarchy", ACCEPTABLE_TAXATION: 0.40, ... }, ... ] }
 * ```
 *
 * 编辑策略：只改动用户碰过的键，其余键（包括本版本没有的、或未来版本新增的）
 * 原样保留在 [Document.root] / 记录 map 里，写回时整体输出，不会丢字段。
 */
internal class GovernmentDataRepository(
    private val context: Context,
    private val workspace: Workspace,
) {
    data class Document(
        val root: MutableMap<String, Any?>,
        val listKey: String,
        val records: MutableList<MutableMap<String, Any?>>,
        val fileName: String,
        val assetPath: String,
    ) {
        fun encode(): String {
            root[listKey] = records
            return GameJson.write(root)
        }
    }

    /**
     * 表单里的一行。
     *
     * [min] / [max] / [step] 都按游戏真实数据的量级设定：
     * 比例类字段在文件里是 0..1 的小数（如 ACCEPTABLE_TAXATION: 0.40），
     * 不是 0..100 的百分数，滑块必须按 0..1 映射，否则写回去就是 40 倍。
     */
    data class Field(
        val key: String,
        val label: String,
        val min: Double = 0.0,
        val max: Double = 1.0,
        val step: Double = 0.01,
        val percent: Boolean = false,
        val choices: List<Pair<Int, String>> = emptyList(),
    )

    // ---------------- 读取 ----------------

    fun loadFromWorkspace(): Document {
        ZipFile(workspace.apk(context)).use { zip ->
            val entry = findEntry(zip, "GovernmentsV2.json") ?: findEntry(zip, "Governments.json")
                ?: throw IllegalStateException("游戏包内没有 GovernmentsV2.json 或 Governments.json")
            val fileName = entry.name.substringAfterLast('/')
            val assetPath = entry.name.substringBeforeLast('/').removePrefix("assets/")
            val overlay = File(workspace.overlayAssetsDir(context), "$assetPath/$fileName")
            val text = if (overlay.isFile) overlay.readText(Charsets.UTF_8)
            else zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(text, fileName, assetPath)
        }
    }

    private fun findEntry(zip: ZipFile, fileName: String) =
        zip.getEntry("assets/game/$fileName") ?: zip.entries().asSequence().firstOrNull {
            !it.isDirectory && it.name.startsWith("assets/") && it.name.endsWith("/$fileName")
        }

    fun save(document: Document) {
        val target = File(workspace.overlayAssetsDir(context), "${document.assetPath}/${document.fileName}")
        GameDataAccess.atomicWrite(target, document.encode().toByteArray(Charsets.UTF_8))
    }

    // ---------------- 取值 ----------------

    fun name(record: Map<String, Any?>, index: Int): String =
        (record["Name"] ?: record["name"])?.toString()?.takeIf(String::isNotBlank) ?: "政体 ${index + 1}"

    fun number(record: Map<String, Any?>, key: String): Double =
        (record[key] as? Number)?.toDouble() ?: record[key]?.toString()?.toDoubleOrNull() ?: 0.0

    fun text(record: Map<String, Any?>, key: String): String =
        record[key]?.toString().orEmpty()

    fun flag(record: Map<String, Any?>, key: String): Boolean =
        record[key] as? Boolean ?: record[key]?.toString()?.equals("true", true) ?: false

    fun color(record: Map<String, Any?>, key: String): Int =
        number(record, key).toInt().coerceIn(0, 255)

    // ---------------- 写值 ----------------

    /**
     * 写数值，并保留原字段的数值类型。
     *
     * 游戏侧用 `getInt` / `getFloat` 之类的强类型读取，把原本的 `7` 写成 `7.0`
     * 通常没事，但把 `2.0f` 写成字符串或把整数写成 `true` 就会崩。
     * 因此按原值类型回写：整数键保持整数，比例键保持小数。
     */
    fun setNumber(record: MutableMap<String, Any?>, key: String, value: Double) {
        val old = record[key]
        record[key] = when (old) {
            is Int -> value.toInt()
            is Long -> value.toLong()
            is Float -> value.toFloat()
            is Boolean -> value != 0.0
            // 原值不存在或不是数字：按是否为整数自动选 Int / Double
            else -> if (value == Math.floor(value) && !value.isInfinite()) value.toInt() else value
        }
    }

    fun setText(record: MutableMap<String, Any?>, key: String, value: String) {
        record[key] = value
    }

    fun setFlag(record: MutableMap<String, Any?>, key: String, value: Boolean) {
        record[key] = value
    }

    /** 复制最后一条作为模板，保证新条目字段齐全（不会漏掉任何键）。 */
    fun add(document: Document): MutableMap<String, Any?> {
        val record = LinkedHashMap<String, Any?>()
        document.records.lastOrNull()?.let { record.putAll(it) }
        record["Name"] = "新政体"
        document.records += record
        return record
    }

    fun has(record: Map<String, Any?>, key: String): Boolean = record.containsKey(key)

    // ---------------- 表单定义 ----------------

    /**
     * 字段表。范围取自游戏自带的 Governments.json 实际取值：
     * 比例字段 0..1，点数类字段 0..20，颜色 0..255。
     * 文件里没有的键会在界面上自动隐藏（[has]），所以旧版/新版都能用。
     */
    val economyFields = listOf(
        Field("ACCEPTABLE_TAXATION", "可接受税率", 0.0, 1.0, 0.01, true),
        Field("MIN_GOODS", "最小商品", 0.0, 1.0, 0.01, true),
        Field("MIN_INVESTMENTS", "最小投资", 0.0, 1.0, 0.01, true),
        Field("RESEARCH_COST", "研究成本", 0.0, 3.0, 0.01),
        Field("INCOME_TAXATION", "税收收入", 0.0, 2.0, 0.01),
        Field("INCOME_PRODUCTION", "生产收入", 0.0, 2.0, 0.01),
    )

    val militaryFields = listOf(
        Field("MILITARY_UPKEEP", "军事维护", 0.0, 2.0, 0.01),
        Field("COST_OF_MOVE", "移动消耗", 0.0, 30.0, 1.0),
        Field("COST_OF_MOVE_TO_THE_SAME_PROV", "同省移动消耗", 0.0, 30.0, 1.0),
        Field("COST_OF_MOVE_OWN_PROV", "己省移动消耗", 0.0, 30.0, 1.0),
        Field("COST_OF_RECRUIT", "招募消耗", 0.0, 30.0, 1.0),
        Field("COST_OF_DISBAND", "解散消耗", 0.0, 30.0, 1.0),
        Field("COST_OF_PLUNDER", "掠夺消耗", 0.0, 30.0, 1.0),
        Field("DEFENSE_BONUS", "防御加成", 0.0, 50.0, 1.0),
    )

    val administrationFields = listOf(
        Field("ADMINISTRATION_COST", "行政成本", 0.0, 2.0, 0.01),
        Field("ADMINISTRATION_COST_DISTANCE", "行政距离成本", 0.0, 3.0, 0.01),
        Field("ADMINISTRATION_COST_CAPITAL", "首都行政成本", 0.0, 2.0, 0.01),
    )

    val civilizationFields = listOf(
        // -1 表示该政体永远无法文明化，这是游戏里的特殊值，范围必须包含负数
        Field("CAN_BECOME_CIVILIZED", "可否文明化（-1 = 不可）", -1.0, 10.0, 1.0),
        Field("CIVILIZE_TECH_LEVEL", "文明化科技等级", 0.0, 10.0, 0.5),
        Field("AVAILABLE_SINCE_AGE_ID", "可用起始时代", 0.0, 20.0, 1.0),
    )

    /** 政体组：真实数据里出现过 0..4。 */
    val groupChoices = listOf(
        0 to "0 · 常规（君主 / 共和 / 民主）",
        1 to "1 · 部落 / 奴隶",
        2 to "2 · 城邦 / 特殊",
        3 to "3 · 其他",
        4 to "4 · 其他",
    )

    private fun parse(text: String, fileName: String, assetPath: String): Document {
        val parsed = GameJson.parse(text) as? Map<*, *>
            ?: throw IllegalArgumentException("$fileName 的根节点不是对象")
        @Suppress("UNCHECKED_CAST")
        val root = LinkedHashMap(parsed as Map<String, Any?>)
        val listKey = listOf("Government", "government", "Governments", "governments")
            .firstOrNull { root[it] is List<*> }
            ?: root.entries.firstOrNull { (_, v) -> v is List<*> && v.all { it is Map<*, *> } }?.key
            ?: throw IllegalArgumentException("$fileName 里没有找到政体数组")
        val raw = root[listKey] as List<*>
        val records = ArrayList<MutableMap<String, Any?>>(raw.size)
        for (item in raw) {
            val map = item as? Map<*, *> ?: throw IllegalArgumentException("政体数组里出现了非对象元素")
            @Suppress("UNCHECKED_CAST")
            records += LinkedHashMap(map as Map<String, Any?>)
        }
        return Document(root, listKey, records, fileName, assetPath)
    }
}