package com.superaoh.ui.compose

import android.content.Context
import com.superaoh.core.Workspace
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 文明数据读写。
 *
 * 游戏里一个文明由**两个文件**组成，这一点很容易搞错：
 *
 * ```
 * assets/game/civilizations/{tag}                      Java 序列化的 Civilization_GameData3
 * assets/game/civilizations/Age_of_Civilizations       索引，分号分隔的 tag 列表
 * assets/game/languages/civilizations/Bundle*.properties   名称，格式 "tag = 显示名"
 * ```
 *
 * 名称**不在** civilizations 目录里（早期版本曾用过 `{tag}_NM` 文件，
 * 但当前版本把名称统一收进了语言包 Bundle）。所以要改名字，
 * 必须去改 `languages/civilizations/Bundle.properties`。
 *
 * 读：优先工作区覆盖层，其次 APK assets。
 * 写：只写工作区覆盖层，APK 永不改动。
 */
internal class CivilizationDataRepository(
    private val context: Context,
    private val workspace: Workspace,
) {
    /** 徽章的一枚颜色，0..255。 */
    data class RibbonColor(val red: Int, val green: Int, val blue: Int)

    data class Record(
        val tag: String,
        val name: String,
        val red: Int,
        val green: Int,
        val blue: Int,
        val ribbonTag: String,
        val ribbonColors: List<RibbonColor>,
        internal val raw: Any,
    )

    /** civilizations 目录在 assets 里的相对路径，例如 "game/civilizations"。 */
    private var civDir = "game/civilizations"

    /** 名称包所在目录，例如 "game/languages/civilizations"。 */
    private var langDir = "game/languages/civilizations"

    /** 实际使用的名称文件名（Bundle.properties 或 Bundle_han.properties）。 */
    private var nameFileName = "Bundle.properties"

    // ---------------- 读取 ----------------

    fun load(): List<Record> {
        ZipFile(workspace.apk(context)).use { zip ->
            val indexEntry = findEntry(zip, "Age_of_Civilizations")
                ?: throw IllegalStateException("游戏包内没有 Age_of_Civilizations，可能不是文明时代 2 的包")
            civDir = indexEntry.name.substringBeforeLast('/').removePrefix("assets/")

            // 名称包：优先中文包，没有就退回默认包
            val nameEntry = findLangEntry(zip, "Bundle_han.properties")
                ?: findLangEntry(zip, "Bundle.properties")
            if (nameEntry != null) {
                langDir = nameEntry.name.substringBeforeLast('/').removePrefix("assets/")
                nameFileName = nameEntry.name.substringAfterLast('/')
            }

            val overlayCiv = File(workspace.overlayAssetsDir(context), civDir)
            val indexText = readOverlayFirst(zip, overlayCiv, "Age_of_Civilizations", indexEntry)
            // 记录原始风格：有的 mod 索引开头没有分号，写回时要保持原样
            indexHasLeadingSemicolon = indexText.startsWith(";")
            val tags = indexText.split(';').map(String::trim).filter(String::isNotEmpty)

            val names = readNames(zip, nameEntry)
            val loader = GameDataAccess.loader(context, workspace)

            // 收集失败原因：如果全部失败，直接把第一个异常抛给界面，
            // 否则用户只会看到「0 个文明」而完全不知道为什么。
            val failures = ArrayList<String>()

            val result = tags.mapNotNull { tag ->
                val raw = try {
                    val overlayData = File(overlayCiv, tag)
                    if (overlayData.isFile) {
                        overlayData.inputStream().use { GameDataAccess.readGameObject(it, loader) }
                    } else {
                        val entry = zip.getEntry("$civDir/$tag")
                        if (entry == null) {
                            failures.add("$tag: APK 内没有这个文件")
                            return@mapNotNull null
                        }
                        zip.getInputStream(entry).use { GameDataAccess.readGameObject(it, loader) }
                    }
                } catch (t: Throwable) {
                    if (failures.size < 5) {
                        failures.add("$tag: ${t.javaClass.simpleName}: ${t.message}")
                    } else if (failures.size == 5) {
                        failures.add("……（更多失败已省略）")
                    }
                    return@mapNotNull null
                }

                val ribbon = GameDataAccess.objectGetter(raw, "getSr_GameData")
                Record(
                    tag = tag,
                    // 名称缺省时退回 tag，界面上不会出现空白行
                    name = names[tag] ?: tag,
                    red = GameDataAccess.intGetter(raw, "getIR"),
                    green = GameDataAccess.intGetter(raw, "getIG"),
                    blue = GameDataAccess.intGetter(raw, "getIB"),
                    ribbonTag = ribbon?.let { GameDataAccess.stringGetter(it, "getSSRTAG") } ?: "",
                    ribbonColors = readRibbonColors(ribbon),
                    raw = raw,
                )
            }

            if (result.isEmpty() && failures.isNotEmpty()) {
                throw IllegalStateException(
                    "读取文明数据失败（${failures.size} 条）：\n" + failures.joinToString("\n")
                )
            }
            return result
        }
    }

    /** 徽章颜色在文件里是 0f..1f 的浮点，界面用 0..255。 */
    private fun readRibbonColors(ribbon: Any?): List<RibbonColor> {
        if (ribbon == null) return emptyList()
        val list = GameDataAccess.objectGetter(ribbon, "getLColors") as? List<*> ?: return emptyList()
        return list.filterNotNull().map { color ->
            RibbonColor(
                (GameDataAccess.floatGetter(color, "getFR") * 255f).toInt().coerceIn(0, 255),
                (GameDataAccess.floatGetter(color, "getFG") * 255f).toInt().coerceIn(0, 255),
                (GameDataAccess.floatGetter(color, "getFB") * 255f).toInt().coerceIn(0, 255),
            )
        }
    }

    /** 解析 `tag = 名称` 形式的 properties；注释与空行跳过。 */
    private fun readNames(zip: ZipFile, entry: ZipEntry?): Map<String, String> {
        if (entry == null) return emptyMap()
        val overlayLang = File(workspace.overlayAssetsDir(context), langDir)
        val text = readOverlayFirst(zip, overlayLang, nameFileName, entry)
        if (text.isEmpty()) return emptyMap()

        val map = HashMap<String, String>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return@forEach
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@forEach
            val key = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (key.isNotEmpty()) map[key] = value
        }
        return map
    }

    private fun readOverlayFirst(
        zip: ZipFile,
        overlayDir: File,
        fileName: String,
        entry: ZipEntry,
    ): String {
        val overlay = File(overlayDir, fileName)
        return if (overlay.isFile) overlay.readText(Charsets.UTF_8)
        else zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** 先按已知路径找，再全包扫一遍兜底（不同版本目录名可能不同）。 */
    private fun findEntry(zip: ZipFile, fileName: String) =
        zip.getEntry("assets/game/civilizations/$fileName") ?: zip.entries().asSequence().firstOrNull {
            !it.isDirectory && it.name.startsWith("assets/") &&
                    it.name.substringAfterLast('/') == fileName
        }

    private fun findLangEntry(zip: ZipFile, fileName: String) =
        zip.getEntry("assets/game/languages/civilizations/$fileName")
            ?: zip.entries().asSequence().firstOrNull {
                !it.isDirectory && it.name.startsWith("assets/") &&
                        it.name.substringAfterLast('/') == fileName &&
                        it.name.contains("languages")
            }

    // ---------------- 新建 ----------------

    /** 新建文明：沿用游戏默认值，只改 tag 与名称。 */
    fun create(records: List<Record>): Record {
        val loader = GameDataAccess.loader(context, workspace)
        val type = loader.loadClass("age.of.civilizations2.jakowski.lukasz.Civilization_GameData3")
        val raw = GameDataAccess.newInstance(type)

        // tag 必须唯一：时间戳 + 6 位随机，和游戏的生成规则一致
        var tag: String
        do {
            tag = System.currentTimeMillis().toString() + (1..6)
                .map { "abcdefghijklmnopqrstuvwxyz0123456789"[kotlin.random.Random.nextInt(36)] }
                .joinToString("")
        } while (records.any { it.tag == tag })

        GameDataAccess.set(raw, "setSCivTag", tag)
        GameDataAccess.set(raw, "setIR", 128)
        GameDataAccess.set(raw, "setIG", 128)
        GameDataAccess.set(raw, "setIB", 128)
        return Record(tag, "新文明", 128, 128, 128, "", emptyList(), raw)
    }

    // ---------------- 修改 ----------------

    /** 只改名称、颜色、徽章；tag 与其余字段原样保留。 */
    fun update(
        record: Record,
        name: String,
        red: Int,
        green: Int,
        blue: Int,
        ribbonTag: String,
        ribbonColors: List<RibbonColor>,
    ): Record {
        val r = red.coerceIn(0, 255)
        val g = green.coerceIn(0, 255)
        val b = blue.coerceIn(0, 255)
        GameDataAccess.set(record.raw, "setIR", r)
        GameDataAccess.set(record.raw, "setIG", g)
        GameDataAccess.set(record.raw, "setIB", b)
        applyRibbon(record.raw, ribbonTag, ribbonColors)
        return record.copy(
            name = name.trim().ifEmpty { record.tag },
            red = r, green = g, blue = b,
            ribbonTag = ribbonTag,
            ribbonColors = ribbonColors,
        )
    }

    /**
     * 写徽章。
     *
     * 游戏里 sr_GameData 是可空字段：标签和颜色都为空时写 null（和游戏自身一致），
     * 否则建一个对象把值塞进去。这样不会在文明上挂一个空壳徽章。
     */
    private fun applyRibbon(raw: Any, ribbonTag: String, colors: List<RibbonColor>) {
        try {
            val trimmedTag = ribbonTag.trim()
            if (trimmedTag.isEmpty() && colors.isEmpty()) {
                GameDataAccess.set(raw, "setSr_GameData", null)
                return
            }

            var ribbon = GameDataAccess.objectGetter(raw, "getSr_GameData")
            if (ribbon == null) {
                val ribbonType = raw.javaClass.classLoader
                    ?.loadClass("age.of.civilizations2.jakowski.lukasz.Civilization_ServiceRibbon_GameData")
                    ?: throw IllegalStateException("找不到徽章类")
                ribbon = GameDataAccess.newInstance(ribbonType)
                GameDataAccess.set(raw, "setSr_GameData", ribbon)
            }
            GameDataAccess.set(ribbon, "setSSRTAG", trimmedTag)

            val colorType = raw.javaClass.classLoader
                ?.loadClass("age.of.civilizations2.jakowski.lukasz.Color_GameData")
                ?: throw IllegalStateException("找不到颜色类")
            val list = ArrayList<Any>()
            for (c in colors) {
                val item = GameDataAccess.newInstance(colorType)
                // 必须用 / 255f（浮点除法）。写成 / 255 会退化成整数除法，
                // 除了纯白以外全部变成 0，徽章颜色会整片变黑。
                GameDataAccess.set(item, "setFR", c.red.coerceIn(0, 255) / 255f)
                GameDataAccess.set(item, "setFG", c.green.coerceIn(0, 255) / 255f)
                GameDataAccess.set(item, "setFB", c.blue.coerceIn(0, 255) / 255f)
                list.add(item)
            }
            GameDataAccess.set(ribbon, "setLColors", list)
        } catch (t: Throwable) {
            throw IllegalStateException("写入徽章失败：${t.message}", t)
        }
    }

    // ---------------- 保存 ----------------

    /** 保存到工作区覆盖层：索引、每条数据、名称包。 */
    fun save(records: List<Record>) {
        if (records.map { it.tag }.toSet().size != records.size) {
            throw IllegalArgumentException("文明 tag 重复，未保存")
        }

        val overlayCiv = File(workspace.overlayAssetsDir(context), civDir)
        if (!overlayCiv.exists() && !overlayCiv.mkdirs()) {
            throw IllegalStateException("无法创建文明覆盖目录")
        }

        for (record in records) {
            GameDataAccess.atomicWrite(File(overlayCiv, record.tag), GameDataAccess.serialize(record.raw))
        }
        GameDataAccess.atomicWrite(
            File(overlayCiv, "Age_of_Civilizations"),
            buildIndex(records.map { it.tag }).toByteArray(Charsets.UTF_8),
        )

        saveNames(records)
    }

    /**
     * 生成索引文件。
     *
     * 格式在真实 mod 里并不统一，两种都见过：
     *   原版：  ";abk;afg;aib;"   ← 首尾都有分号
     *   某 mod："abk;afg;aib;"     ← 只有末尾有分号
     * 游戏读取时按 `;` 切分再 trim、过滤空串，所以两种都能读。
     * 这里沿用**原来文件的首部风格**，避免覆盖后与原 mod 的格式不一致
     * （有些第三方工具对开头分号比较敏感）。
     */
    private fun buildIndex(tags: List<String>): String {
        val body = tags.joinToString(separator = ";")
        return if (indexHasLeadingSemicolon) ";$body;" else "$body;"
    }

    /** 原索引是否以分号开头，在 [load] 时记录。 */
    private var indexHasLeadingSemicolon = true

    /**
     * 把名称写回语言包。
     *
     * 只替换本次编辑涉及的行，其余内容（注释、其他条目、格式）原样保留 ——
     * 语言包有 2600+ 行，整体重建会丢掉注释和顺序，也不好排查。
     */
    private fun saveNames(records: List<Record>) {
        val apk = workspace.apk(context)
        val fromApk = runCatching {
            ZipFile(apk).use { zip ->
                val entry = findLangEntry(zip, nameFileName) ?: return@runCatching ""
                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        }.getOrDefault("")

        // 覆盖层已有的内容优先（上次保存的结果）
        val overlayLang = File(workspace.overlayAssetsDir(context), langDir)
        val overlayFile = File(overlayLang, nameFileName)
        val base = if (overlayFile.isFile) overlayFile.readText(Charsets.UTF_8) else fromApk

        val names = records.associate { it.tag to it.name }
        val written = HashSet<String>()
        val out = StringBuilder()

        // 逐行替换。用 split('\n') 而不是 lineSequence()：
        // lineSequence() 会把末尾换行吃掉，重组时行数就会对不上。
        val lines = base.split('\n')
        lines.forEachIndexed { i, line ->
            val trimmed = line.trim()
            val eq = trimmed.indexOf('=')
            val key = if (eq > 0 && !trimmed.startsWith("#") && !trimmed.startsWith("!")) {
                trimmed.substring(0, eq).trim()
            } else null

            if (key != null && names.containsKey(key)) {
                out.append(key).append(" = ").append(names.getValue(key))
                written.add(key)
            } else {
                out.append(line)
            }
            if (i != lines.lastIndex) out.append('\n')
        }

        // 新文明（以及语言包里原本没有的 tag）追加到末尾
        val missing = records.filter { it.tag !in written }
        if (missing.isNotEmpty()) {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
            missing.forEach { out.append(it.tag).append(" = ").append(it.name).append('\n') }
        }

        GameDataAccess.atomicWrite(overlayFile, out.toString().toByteArray(Charsets.UTF_8))
    }
}