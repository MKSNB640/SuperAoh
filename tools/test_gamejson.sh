#!/usr/bin/env bash
# 验证 GameJson 宽松解析器能否读懂游戏真实的 Governments.json。
#
# 游戏文件是 libGDX 格式：键名不加引号、数值带 f 后缀、有尾逗号。
# 这个脚本把 GameJson.kt 单独抽出来用 kotlinc 编译成 JVM 程序，
# 直接跑真实数据，不需要 Android 设备。
#
# 用法: ./tools/test_gamejson.sh
set -u

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$PROJ/app/src/main/java/com/superaoh/ui/compose/GameJson.kt"
JSON_SRC="$PROJ/app/src/main/java/com/superaoh/core/Json.java"
# 默认优先用设备上的真实游戏数据，没有就退回仓库内的同格式样本
DEV_GOV="/sdcard/Claude的逆向学习/jadx_full_output/resources/assets/game/Governments.json"
FIXTURE_GOV="$PROJ/tools/fixtures/Governments.json"
if [ -n "${1:-}" ]; then
    GOV="$1"
elif [ -f "$DEV_GOV" ]; then
    GOV="$DEV_GOV"
else
    GOV="$FIXTURE_GOV"
fi

# kotlinc 位置因机器而异，逐个候选探测后取第一个可用的
pick_kotlinc() {
    for candidate in \
        "${KOTLINC_BIN:-}" \
        /opt/kotlinc/bin/kotlinc \
        /opt/kotlinc/kotlinc/bin/kotlinc \
        /tmp/kotlinc/kotlinc/bin/kotlinc; do
        [ -n "$candidate" ] && [ -x "$candidate" ] && { echo "$candidate"; return; }
    done
}

KOTLINC="$(pick_kotlinc)"
[ -n "$KOTLINC" ] || { echo "找不到 kotlinc，可用 KOTLINC_BIN=/path/to/kotlinc 指定"; exit 1; }
KOTLIN_HOME="$(cd "$(dirname "$KOTLINC")/.." && pwd)"
KOTLIN_STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"
[ -f "$KOTLIN_STDLIB" ] || { echo "找不到 kotlin-stdlib.jar（$KOTLIN_STDLIB）"; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

[ -f "$GOV" ] || { echo "找不到测试数据: $GOV"; exit 1; }

# 抽出 Json 依赖（GameJson.write 用到）
mkdir -p "$WORK/src/com/superaoh/core" "$WORK/src/com/superaoh/ui/compose"
cp "$JSON_SRC" "$WORK/src/com/superaoh/core/Json.java"
cp "$SRC" "$WORK/src/com/superaoh/ui/compose/GameJson.kt"

# 测试主程序
cat > "$WORK/src/com/superaoh/ui/compose/Check.kt" <<'KOT'
package com.superaoh.ui.compose

import java.io.File

fun main(args: Array<String>) {
    val text = File(args[0]).readText(Charsets.UTF_8)
    val root = GameJson.parse(text) as? Map<*, *>
        ?: error("根节点不是对象")
    val list = root["Government"] as? List<*> ?: error("没有 Government 数组")

    println("政体数量: ${list.size}")
    val first = list.first() as Map<*, *>
    println("第一条: ${first["Name"]}  (${first.size} 个字段)")

    // 抽查关键字段的类型与取值
    for (key in listOf("GOV_GROUP_ID", "ACCEPTABLE_TAXATION", "CIVILIZE_TECH_LEVEL", "REVOLUTIONARY", "AI_TYPE", "R")) {
        val v = first[key]
        println("  %-26s = %-14s %s".format(key, v, v?.javaClass?.simpleName))
    }

    // 逐条校验：每个政体都必须是 Map，且必须有 Name
    var bad = 0
    list.forEachIndexed { i, item ->
        if (item !is Map<*, *>) { println("  [!] 第 $i 条不是对象"); bad++ }
        else if (item["Name"] == null) { println("  [!] 第 $i 条没有 Name"); bad++ }
    }
    println(if (bad == 0) "结构检查: 全部通过" else "结构检查: $bad 处异常")

    // 往返：解析 → 标准 JSON 写出 → 再解析，数量与关键值必须一致
    val written = GameJson.write(root)
    val again = GameJson.parse(written) as? Map<*, *> ?: error("写出后无法再解析")
    val list2 = again["Government"] as? List<*> ?: error("写出后丢了 Government")
    check(list2.size == list.size) { "往返后数量变化: ${list.size} -> ${list2.size}" }
    val f2 = list2.first() as Map<*, *>
    check(f2["Name"] == first["Name"]) { "往返后 Name 变化" }
    check((f2["ACCEPTABLE_TAXATION"] as Number).toDouble() == (first["ACCEPTABLE_TAXATION"] as Number).toDouble()) {
        "往返后 ACCEPTABLE_TAXATION 变化"
    }
    println("往返检查: 通过（写出 ${written.length} 字节）")
}
KOT

echo "[*] 编译..."
# Json.java 必须单独用 javac 编：kotlinc 只把它当类型来源，不产出 class。
javac -nowarn -encoding UTF-8 -d "$WORK/out" \
    "$WORK/src/com/superaoh/core/Json.java" 2>&1 | sed 's/^/    /'
[ "${PIPESTATUS[0]}" -eq 0 ] || { echo "[x] javac 失败"; exit 1; }

"$KOTLINC" -nowarn -jvm-target 1.8 -classpath "$KOTLIN_STDLIB:$WORK/out" \
    -d "$WORK/out" \
    "$WORK/src/com/superaoh/core/Json.java" \
    "$WORK/src/com/superaoh/ui/compose/GameJson.kt" \
    "$WORK/src/com/superaoh/ui/compose/Check.kt" 2>&1 | sed 's/^/    /' | tail -20
[ "${PIPESTATUS[0]}" -eq 0 ] || { echo "[x] 编译失败"; exit 1; }

echo "[*] 运行（数据: $GOV）"
java -cp "$WORK/out:$KOTLIN_STDLIB" com.superaoh.ui.compose.CheckKt "$GOV"