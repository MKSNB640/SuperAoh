package com.superaoh.ui.compose

import java.io.File

/**
 * 对真实游戏数据做往返验证。
 *
 * 覆盖三件事：
 *   1. 宽松解析：键名不带引号、数值带 f 后缀的 libGDX 文件能不能读懂
 *   2. 结构完整：每条政体都是对象且带 Name，字段数不丢
 *   3. 往返一致：解析 → 标准 JSON 写出 → 再解析，数量与关键值必须相同
 *
 * 同时验证类型保留逻辑：整数键写回后仍是整数，比例键写回后仍是小数。
 */
fun main(args: Array<String>) {
    var failures = 0
    fun expect(ok: Boolean, message: String) {
        if (ok) {
            println("  [ok]   $message")
        } else {
            println("  [FAIL] $message")
            failures++
        }
    }

    // ---------- 1. 宽松解析真实数据 ----------
    val govPath = args.getOrNull(0) ?: "/sdcard/Claude的逆向学习/jadx_full_output/resources/assets/game/Governments.json"
    println("== 宽松解析: $govPath")
    val govFile = File(govPath)
    expect(govFile.isFile, "测试数据存在")
    if (!govFile.isFile) {
        println("跳过后续测试")
        return
    }

    val root = GameJson.parse(govFile.readText(Charsets.UTF_8)) as? Map<*, *>
    expect(root != null, "根节点解析为对象")
    val list = root?.get("Government") as? List<*>
    expect(list != null, "找到 Government 数组")
    expect((list?.size ?: 0) > 0, "政体数量 > 0（实际 ${list?.size}）")

    val first = list?.firstOrNull() as? Map<*, *>
    expect(first != null, "第一条是对象")
    expect(first?.get("Name") is String, "Name 是字符串 → ${first?.get("Name")}")
    expect(first?.get("GOV_GROUP_ID") is Number, "GOV_GROUP_ID 是数字")
    expect(first?.get("ACCEPTABLE_TAXATION") is Number, "ACCEPTABLE_TAXATION 是数字（0..1 比例）")
    expect(first?.get("CIVILIZE_TECH_LEVEL") is Number, "CIVILIZE_TECH_LEVEL 能剥掉 f 后缀")
    expect(first?.get("REVOLUTIONARY") is Boolean, "REVOLUTIONARY 是布尔")
    expect(first?.get("AI_TYPE") is String, "AI_TYPE 是字符串")

    // 关键：如果按百分比理解，0.40 会被读成 0.4% 之外的东西；这里必须精确等于 0.4
    val tax = (first?.get("ACCEPTABLE_TAXATION") as? Number)?.toDouble()
    expect(tax != null && kotlin.math.abs(tax - 0.4) < 1e-9, "可接受税率精确为 0.4（不是 40）")

    // ---------- 2. 结构完整 ----------
    println("== 结构检查")
    var badRows = 0
    list?.forEachIndexed { i, item ->
        if (item !is Map<*, *> || item["Name"] == null) badRows++
    }
    expect(badRows == 0, "所有 ${list?.size} 条政体都合法（异常 $badRows 条）")

    val fieldCount = first?.size ?: 0
    expect(fieldCount >= 20, "字段数合理（$fieldCount 个）")

    // ---------- 3. 往返一致 ----------
    println("== 往返检查（解析 → 写出 → 再解析）")
    val written = GameJson.write(root)
    expect(written.isNotBlank(), "写出非空（${written.length} 字节）")

    val again = GameJson.parse(written) as? Map<*, *>
    expect(again != null, "写出的内容能被再解析")
    val list2 = again?.get("Government") as? List<*>
    expect(list2?.size == list?.size, "政体数量不变（${list?.size} → ${list2?.size}）")

    val f2 = list2?.firstOrNull() as? Map<*, *>
    expect(f2?.get("Name") == first?.get("Name"), "Name 不变")
    val tax2 = (f2?.get("ACCEPTABLE_TAXATION") as? Number)?.toDouble()
    expect(tax2 != null && kotlin.math.abs(tax2 - 0.4) < 1e-9, "可接受税率往返后仍是 0.4")
    expect(f2?.get("REVOLUTIONARY") == first?.get("REVOLUTIONARY"), "REVOLUTIONARY 不变")
    expect(f2?.get("AI_TYPE") == first?.get("AI_TYPE"), "AI_TYPE 不变")
    expect(f2?.size == fieldCount, "字段数不变（$fieldCount → ${f2?.size}）")

    // ---------- 4. 类型保留 ----------
    println("== 类型保留（setNumber 不应把整数变小数、把小数变整数）")
    val repo = FakeRepo()
    val intKey = mutableMapOf<String, Any?>("COST_OF_RECRUIT" to 7)
    repo.setNumber(intKey, "COST_OF_RECRUIT", 12.0)
    expect(intKey["COST_OF_RECRUIT"] is Int && intKey["COST_OF_RECRUIT"] == 12, "整数键写回仍是 Int")

    val ratioKey = mutableMapOf<String, Any?>("ACCEPTABLE_TAXATION" to 0.4)
    repo.setNumber(ratioKey, "ACCEPTABLE_TAXATION", 0.75)
    expect(ratioKey["ACCEPTABLE_TAXATION"] is Double, "比例键写回仍是 Double")

    val negKey = mutableMapOf<String, Any?>("CAN_BECOME_CIVILIZED" to -1)
    repo.setNumber(negKey, "CAN_BECOME_CIVILIZED", -1.0)
    expect(negKey["CAN_BECOME_CIVILIZED"] == -1, "-1 特殊值能保住")

    println()
    if (failures == 0) {
        println("全部通过")
    } else {
        println("$failures 项失败")
        kotlin.system.exitProcess(1)
    }
}

/**
 * 只借用 GovernmentDataRepository.setNumber 的类型保留规则。
 *
 * 不直接实例化它：它的构造器要 Android Context，JVM 上跑不了；
 * 这里复刻同一份判断逻辑，规则改了两边一起改。
 */
private class FakeRepo {
    fun setNumber(record: MutableMap<String, Any?>, key: String, value: Double) {
        val old = record[key]
        record[key] = when (old) {
            is Int -> value.toInt()
            is Long -> value.toLong()
            is Float -> value.toFloat()
            is Boolean -> value != 0.0
            else -> if (value == Math.floor(value) && !value.isInfinite()) value.toInt() else value
        }
    }
}