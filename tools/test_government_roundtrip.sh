#!/usr/bin/env bash
# 政体数据链路 JVM 端到端测试：加载真实格式 → 改值 → 多轮保存 → 双解析器复读。
#
# 不依赖 Android 设备，也不需要真实游戏 APK：
# 默认使用 tools/fixtures/Governments.json（与游戏同格式的样本）。
#
# 用法: ./tools/test_government_roundtrip.sh [Governments.json]
set -u

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
GOV="${1:-$PROJ/tools/fixtures/Governments.json}"
JSON_SRC="$PROJ/app/src/main/java/com/superaoh/core/Json.java"
GAMEJSON_SRC="$PROJ/app/src/main/java/com/superaoh/ui/compose/GameJson.kt"
RULES_SRC="$PROJ/app/src/main/java/com/superaoh/ui/compose/EditorRules.kt"
CHECK_SRC="$PROJ/tools/GovernmentRoundTripCheck.kt"

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
STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"
[ -f "$STDLIB" ] || { echo "找不到 kotlin-stdlib.jar（$STDLIB）"; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

[ -f "$GOV" ] || { echo "找不到测试数据: $GOV"; exit 1; }

echo "[*] 编译..."
# Json.java 必须单独用 javac 编：kotlinc 只把它当类型来源，不产出 class。
javac -nowarn -encoding UTF-8 -d "$WORK/out" "$JSON_SRC" 2>&1 | sed 's/^/    /'
[ "${PIPESTATUS[0]}" -eq 0 ] || { echo "[x] javac 失败"; exit 1; }

"$KOTLINC" -nowarn -jvm-target 1.8 -classpath "$STDLIB:$WORK/out" \
    -d "$WORK/out" "$GAMEJSON_SRC" "$RULES_SRC" "$CHECK_SRC" 2>&1 | sed 's/^/    /' | tail -20
[ "${PIPESTATUS[0]}" -eq 0 ] || { echo "[x] 编译失败"; exit 1; }

echo "[*] 运行（数据: $GOV）"
java -cp "$WORK/out:$STDLIB" com.superaoh.ui.compose.GovernmentRoundTripCheckKt "$GOV"
