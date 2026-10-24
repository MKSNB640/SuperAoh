package com.superaoh.ui.compose

import com.superaoh.core.Json
import java.io.File

/**
 * 政体数据链路的 JVM 端到端测试（不需要 Android 设备）。
 *
 * 覆盖的是编辑器真正会走的路径，而不只是解析器：
 *
 *   1. 真实格式数据能被加载成政体列表（字段数、类型）
 *   2. 表单写值时的类型保留：整数键仍写整数、比例键仍写小数、-1 特殊值不被截断
 *   3. 反复「读取 → 写值 → 保存 → 再读取」多轮后，未编辑的字段一个都不丢
 *      （编辑器只改用户碰过的键，其余键必须原样带走，包括本版本没有的键）
 *   4. 写出结果能被宽松解析器和标准 JSON 解析器同时接受
 *
 * 用法: 见 tools 目录下的 test_government_roundtrip.sh
 *
 * 写值规则直接复用 EditorRules（与 GovernmentDataRepository.setNumber 同一实现），
 * 界面和测试跑同一份代码，规则改动不会悄悄分叉。
 */
fun main(args: Array<String>) {
    var failures = 0
    fun expect(ok: Boolean, message: String) {
        println((if (ok) "  [ok]   " else "  [FAIL] ") + message)
        if (!ok) failures++
    }

    val path = args.getOrNull(0) ?: "tools/fixtures/Governments.json"
    val file = File(path)
    expect(file.isFile, "测试数据存在: $path")
    if (!file.isFile) {
        println("\n$failures 项失败")
        kotlin.system.exitProcess(1)
    }

    // ---------- 1. 加载 ----------
    println("== 加载")
    val root = GameJson.parse(file.readText(Charsets.UTF_8)) as? Map<*, *>
    expect(root != null, "根节点是对象")
    val list = root?.get("Government") as? List<*>
    expect(!list.isNullOrEmpty(), "Government 数组非空（${list?.size} 条）")

    @Suppress("UNCHECKED_CAST")
    val document = LinkedHashMap(root as Map<String, Any?>)
    val records = list!!.map { LinkedHashMap(it as Map<String, Any?>) }.toMutableList()
    document["Government"] = records

    val first = records.first()
    expect(first["Name"] is String, "Name 是字符串")
    expect(first["GOV_GROUP_ID"] is Number, "GOV_GROUP_ID 是数字")
    expect(first["REVOLUTIONARY"] is Boolean, "REVOLUTIONARY 是布尔")
    expect((first["CIVILIZE_TECH_LEVEL"] as? Number)?.toDouble() == 2.0, "2.0f 后缀被剥掉")
    expect((first["ACCEPTABLE_TAXATION"] as? Number)?.toDouble() == 0.4, "比例为 0.4 而不是 40")

    val tribal = records.firstOrNull { it["CAN_BECOME_CIVILIZED"] == -1.0 }
    expect(tribal != null, "存在 CAN_BECOME_CIVILIZED = -1 的政体")

    // ---------- 2. 写值类型保留 ----------
    println("== 写值类型保留")
    EditorRules.number(first, "GOV_GROUP_ID", 2.0)
    expect((first["GOV_GROUP_ID"] as Number).toDouble() == 2.0, "整数键写入 2.0 后数值不变")
    expect(!Json.write(first).contains("\"GOV_GROUP_ID\":2."), "整数键写出时没有小数点")
    EditorRules.number(first, "ACCEPTABLE_TAXATION", 0.55)
    expect((first["ACCEPTABLE_TAXATION"] as Number).toDouble() == 0.55, "比例键写入 0.55")
    EditorRules.number(tribal!!, "CAN_BECOME_CIVILIZED", -1.0)
    expect((tribal["CAN_BECOME_CIVILIZED"] as Number).toInt() == -1, "-1 特殊值保持为 -1")
    EditorRules.number(first, "R", 300.0)
    expect((first["R"] as Number).toInt() == 300, "颜色按数值写入（越界由界面裁剪）")

    // 滑块网格对齐：0.1 累加会得到 0.30000000000000004，必须被消掉
    EditorRules.number(first, "MIN_GOODS", EditorRules.snap(0.1 + 0.2, 0.01))
    expect((first["MIN_GOODS"] as Number).toDouble() == 0.3, "滑块对齐后无浮点噪声（0.3）")

    // ---------- 3. 多轮保存不丢字段 ----------
    println("== 多轮保存（未编辑字段必须保留）")
    records.first()["FUTURE_ONLY_FIELD"] = 42.0
    records.first()["NestedColor"] = linkedMapOf<String, Any?>("R" to 1, "G" to 2)
    val knownKeys = first.keys.toSet()

    var text = Json.write(document)
    repeat(3) {
        val again = GameJson.parse(text) as? Map<*, *> ?: error("第 ${it + 1} 轮写出后无法解析")
        val againList = (again["Government"] as List<*>).map { LinkedHashMap(it as Map<String, Any?>) }
        expect((again["Government"] as List<*>).size == records.size, "第 ${it + 1} 轮条数不变")
        expect(againList.first().keys.containsAll(knownKeys), "第 ${it + 1} 轮已知字段一个不少")
        expect(againList.first()["FUTURE_ONLY_FIELD"] == 42.0, "第 ${it + 1} 轮未知字段保留")
        val nested = againList.first()["NestedColor"] as? Map<*, *>
        expect(nested?.get("R") == 1.0, "第 ${it + 1} 轮嵌套对象保留")
        text = Json.write(linkedMapOf("Government" to againList))
    }
    val stable = Json.write(linkedMapOf("Government" to (GameJson.parse(text) as Map<*, *>)["Government"]))
    expect(stable == text, "多轮后输出稳定（幂等）")

    // ---------- 4. 双解析器兼容 ----------
    println("== 写出的文件两种解析器都要能读")
    val loose = runCatching { GameJson.parse(text) }.isSuccess
    val strict = runCatching { Json.parse(text) }.isSuccess
    expect(loose, "宽松解析器可读")
    expect(strict, "标准 JSON 解析器可读（不依赖宽松语法）")

    println()
    println(if (failures == 0) "全部通过" else "$failures 项失败")
    if (failures != 0) kotlin.system.exitProcess(1)
}

