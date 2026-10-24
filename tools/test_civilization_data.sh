#!/usr/bin/env bash
# 文明数据链路 JVM 端到端测试（不需要 Android 设备，也不需要真实游戏 APK）。
#
# 先用 build_civilization_fixture.py 生成一个只有目录结构的小型 APK，
# 再验证：索引定位与格式、名称包读取、悬空引用跳过、保存风格保持、行替换与追加幂等。
#
# 真实 APK 验证仍用 tools/test_civilizations.sh（需要设备上的游戏包）。
#
# 用法: ./tools/test_civilization_data.sh
set -u

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/civ_fixture_$$"
FIXTURE="$WORK/civ.apk"
CHECK_SRC="$PROJ/tools/CivilizationDataCheck.kt"

trap 'rm -rf "$WORK"' EXIT

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

mkdir -p "$WORK"
python3 "$PROJ/tools/build_civilization_fixture.py" "$FIXTURE" > /dev/null || { echo "[x] 生成测试 APK 失败"; exit 1; }

echo "[*] 编译测试..."
"$KOTLINC" -nowarn -jvm-target 1.8 -classpath "$STDLIB" \
    -d "$WORK/out" "$CHECK_SRC" 2>&1 | sed 's/^/    /' | tail -15
[ "${PIPESTATUS[0]}" -eq 0 ] || { echo "[x] 编译失败"; exit 1; }

echo "[*] 运行（APK: $FIXTURE）"
java -cp "$WORK/out:$STDLIB" com.superaoh.ui.compose.CivilizationDataCheckKt "$FIXTURE"
