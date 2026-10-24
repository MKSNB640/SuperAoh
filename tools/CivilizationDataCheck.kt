package com.superaoh.ui.compose

import java.io.File
import java.util.zip.ZipFile

/**
 * 文明数据链路的 JVM 端到端测试（不需要 Android 设备，也不需要真实游戏 APK）。
 *
 * 用 tools/build_civilization_fixture.py 生成的合成 APK 跑通真正的解析逻辑：
 *
 *   1. 索引能定位，分号切分正确，首部分号风格被识别
 *   2. 名称从 Bundle_han.properties 读，回退 Bundle.properties
 *   3. 索引里的悬空 tag（APK 内无数据文件）被跳过而不是整体失败
 *   4. 保存时按原风格生成索引，且只替换目标名称行、行数不变、其他条目不动
 *   5. 新文明名称追加到语言包末尾，重复保存不会重复追加
 *
 * 与 CivilizationDataRepository 的区别：这里不加载游戏类（DexClassLoader 只在设备上可用），
 * 只验证文件层逻辑，也就是真机上最容易出问题、又最容易在没有设备时验证的部分。
 */
fun main(args: Array<String>) {
    var failures = 0
    fun expect(ok: Boolean, message: String) {
        println((if (ok) "  [ok]   " else "  [FAIL] ") + message)
        if (!ok) failures++
    }

    val apk = File(args.getOrNull(0) ?: "build/fixtures/civ.apk")
    expect(apk.isFile, "测试 APK 存在: ${apk.path}")
    if (!apk.isFile) {
        println("\n$failures 项失败")
        kotlin.system.exitProcess(1)
    }

    ZipFile(apk).use { zip ->
        // ---------- 1. 定位与索引格式 ----------
        println("== 索引")
        val index = zip.entries().asSequence().firstOrNull {
            !it.isDirectory && it.name.startsWith("assets/") &&
                    it.name.substringAfterLast('/') == "Age_of_Civilizations"
        }
        expect(index != null, "找到 Age_of_Civilizations")
        if (index == null) return@use
        val civDir = index.name.substringBeforeLast('/')
        expect(civDir == "assets/game/civilizations", "索引所在目录正确（$civDir）")

        val indexText = zip.getInputStream(index).bufferedReader(Charsets.UTF_8).use { it.readText() }
        expect(indexText.startsWith(";"), "原版风格：开头带分号")
        val tags = indexText.split(';').map(String::trim).filter(String::isNotEmpty)
        expect(tags == listOf("aaa", "bbb", "ghost"), "切出全部 tag（$tags）")

        val missing = tags.filter { zip.getEntry("$civDir/$it") == null }
        expect(missing == listOf("ghost"), "悬空引用只报告不失败（$missing）")

        // ---------- 2. 名称包 ----------
        println("== 名称包")
        val han = zip.getEntry("assets/game/languages/civilizations/Bundle_han.properties")
        expect(han != null, "优先找到 Bundle_han.properties")
        val text = zip.getInputStream(han!!).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val names = parseNames(text)
        expect(names["aaa"] == "测试", "aaa 的中文名是「测试」")
        expect(names["bbb"] == "乙", "bbb 的中文名是「乙」")

        // ---------- 3. 保存索引 ----------
        println("== 保存索引")
        val savedTags = listOf("aaa", "bbb", "ccc")
        expect(buildIndex(savedTags, leading = true) == ";aaa;bbb;ccc;", "带首分号风格写回")
        expect(buildIndex(savedTags, leading = false) == "aaa;bbb;ccc;", "无首分号风格写回")
        val roundTrip = buildIndex(tags, leading = indexText.startsWith(";"))
        expect(roundTrip == indexText.trim(), "原样重建与源文件一致")

        // ---------- 4. 名称行替换 ----------
        println("== 名称行替换")
        val renamed = replaceNames(text, mapOf("aaa" to "改名测试"), append = emptyList())
        expect(parseNames(renamed)["aaa"] == "改名测试", "目标行已替换")
        expect(renderedLineCount(renamed) == renderedLineCount(text), "行数不变")
        expect(parseNames(renamed)["bbb"] == "乙", "其他条目未被改动")
        expect(renamed.contains("文明名称（中文）"), "非注释行原样保留")

        // ---------- 5. 新文明追加且幂等 ----------
        println("== 新文明名称")
        val appended = replaceNames(text, mapOf("aaa" to "测试"), append = listOf("ccc" to "新文明"))
        expect(parseNames(appended)["ccc"] == "新文明", "新条目追加到末尾")
        val twice = replaceNames(appended, mapOf("aaa" to "测试"), append = listOf("ccc" to "新文明"))
        expect(parseNames(twice)["ccc"] == "新文明", "再次保存仍是新名称")
        expect(twice.split("ccc = ").size - 1 == 1, "重复保存不会重复追加同一 tag")
    }

    println()
    println(if (failures == 0) "全部通过" else "$failures 项失败")
    if (failures != 0) kotlin.system.exitProcess(1)
}

/** 与 CivilizationDataRepository.readNames 相同的解析规则。 */
private fun parseNames(text: String): Map<String, String> {
    val map = HashMap<String, String>()
    text.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return@forEach
        val eq = trimmed.indexOf('=')
        if (eq <= 0) return@forEach
        map[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
    }
    return map
}

/** 与 CivilizationDataRepository.buildIndex 相同的规则。 */
private fun buildIndex(tags: List<String>, leading: Boolean): String {
    val body = tags.joinToString(";")
    return if (leading) ";$body;" else "$body;"
}

/**
 * 与 CivilizationDataRepository.saveNames 相同的逐行替换逻辑。
 *
 * 这里按行切分而不是用 lineSequence：后者会吃掉末尾换行，行数就对不上。
 */
private fun replaceNames(source: String, rename: Map<String, String>, append: List<Pair<String, String>>): String {
    val written = HashSet<String>()
    val out = StringBuilder()
    val lines = source.split('\n')
    lines.forEachIndexed { i, line ->
        val trimmed = line.trim()
        val eq = trimmed.indexOf('=')
        val key = if (eq > 0 && !trimmed.startsWith("#") && !trimmed.startsWith("!")) {
            trimmed.substring(0, eq).trim()
        } else null
        if (key != null && rename.containsKey(key)) {
            out.append(key).append(" = ").append(rename.getValue(key))
            written.add(key)
        } else {
            out.append(line)
        }
        if (i != lines.lastIndex) out.append('\n')
    }
    val pending = append.filter { (tag, _) -> tag !in written && !source.lineSequence().any { it.trim().startsWith("$tag =") } }
    if (pending.isNotEmpty()) {
        if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
        pending.forEach { (tag, name) -> out.append(tag).append(" = ").append(name).append('\n') }
    }
    return out.toString()
}

private fun renderedLineCount(text: String): Int = text.split('\n').size
