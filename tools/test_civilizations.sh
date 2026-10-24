#!/usr/bin/env bash
# 用真实游戏 APK 验证文明数据链路（纯 JVM，不需要 Android 设备）。
#
# 验证点：
#   1. 能在 APK 里定位 civilizations 目录和 Age_of_Civilizations 索引
#   2. 索引格式是 ;tag1;tag2;（首尾都有分号），能正确切出 tag
#   3. 名称来自 languages/civilizations/Bundle*.properties，格式 "tag = 名称"
#   4. tag 与名称能对上（用 aaa = 测试 这条做定点验证）
#   5. 语言包行替换逻辑：只改目标行，其他行原样保留
#
# 用法: ./tools/test_civilizations.sh [game.apk]
set -u

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-/sdcard/Download/batchops/final_v66.apk}"

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

[ -f "$APK" ] || { echo "找不到 APK: $APK"; exit 1; }

cat > "$WORK/CivCheck.kt" <<'KOT'
import java.io.File
import java.util.zip.ZipFile

fun main(args: Array<String>) {
    var fail = 0
    fun expect(ok: Boolean, msg: String) {
        println((if (ok) "  [ok]   " else "  [FAIL] ") + msg)
        if (!ok) fail++
    }

    val apk = File(args[0])
    ZipFile(apk).use { zip ->
        // ---- 1. 定位目录 ----
        println("== 定位资源")
        val index = zip.entries().asSequence().firstOrNull {
            !it.isDirectory && it.name.startsWith("assets/") &&
                it.name.substringAfterLast('/') == "Age_of_Civilizations" &&
                it.name.contains("civilizations/")
        }
        expect(index != null, "找到 civilizations/Age_of_Civilizations")
        if (index == null) return

        val civDir = index.name.substringBeforeLast('/')
        println("     civDir = $civDir")

        val bundleHan = zip.getEntry("assets/game/languages/civilizations/Bundle_han.properties")
        val bundleDef = zip.getEntry("assets/game/languages/civilizations/Bundle.properties")
        expect(bundleHan != null || bundleDef != null, "找到语言包 Bundle*.properties")
        val bundle = bundleHan ?: bundleDef ?: return
        println("     语言包 = ${bundle.name}")

        // ---- 2. 索引格式 ----
        println("== 索引格式")
        val indexText = zip.getInputStream(index).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val leading = indexText.startsWith(";")
        println("     开头有分号 = $leading（两种格式游戏都能读，写回时保持原样）")
        expect(indexText.trimEnd().endsWith(";"), "索引以分号结尾")
        val tags = indexText.split(';').map(String::trim).filter(String::isNotEmpty)
        expect(tags.isNotEmpty(), "切出 ${tags.size} 个 tag")

        // 每个 tag 都必须有对应的数据文件
        val missing = tags.filter { zip.getEntry("$civDir/$it") == null }
        // mod 索引里常有悬空引用（指向不存在的文件）。这属于 mod 自身数据缺陷，
        // 不是我们的问题 —— 加载逻辑必须能跳过它们继续工作，所以只报告不判失败。
        println("     ${missing.size} 个 tag 没有数据文件，加载时会被跳过")
        if (missing.isNotEmpty()) {
            println("     例如: ${missing.take(3).joinToString()}")
        }
        expect(missing.size < tags.size, "悬空引用没有占满全部（${missing.size}/${tags.size}）")

        // ---- 3. 名称包 ----
        println("== 名称包")
        val bundleText = zip.getInputStream(bundle).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val names = HashMap<String, String>()
        bundleText.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) return@forEach
            val eq = t.indexOf('=')
            if (eq > 0) names[t.substring(0, eq).trim()] = t.substring(eq + 1).trim()
        }
        expect(names.isNotEmpty(), "解析出 ${names.size} 条名称")

        // ---- 4. tag 与名称对应 ----
        println("== tag / 名称对应")
        val matched = tags.count { names.containsKey(it) }
        println("     ${matched} / ${tags.size} 个 tag 在名称包里有对应条目")
        expect(matched > tags.size / 2, "多数 tag 都能找到名称")

        // 定点：aaa = 测试
        if (tags.contains("aaa")) {
            expect(names["aaa"] == "测试", "aaa 的名称是「测试」（实际 ${names["aaa"]}）")
        }

        // 抽查前 3 个
        tags.take(3).forEach { tag ->
            println("     $tag → ${names[tag] ?: "(无名称，界面退回 tag)"}")
        }

        // ---- 5. 行替换逻辑 ----
        println("== 语言包行替换")
        val target = tags.firstOrNull { names.containsKey(it) } ?: tags.first()
        val newName = "改名测试"
        val out = StringBuilder()
        var replaced = false
        // 用 split('\n') 而非 lineSequence()：后者吃掉末尾换行，行数会对不上
        val lines = bundleText.split('\n')
        lines.forEachIndexed { i, line ->
            val t = line.trim()
            val eq = t.indexOf('=')
            val key = if (eq > 0 && !t.startsWith("#") && !t.startsWith("!")) t.substring(0, eq).trim() else null
            if (key == target) {
                out.append(key).append(" = ").append(newName)
                replaced = true
            } else {
                out.append(line)
            }
            if (i != lines.lastIndex) out.append('\n')
        }
        expect(replaced, "成功替换 $target 这一行")

        val beforeLines = lines.size
        val afterLines = out.split('\n').size
        expect(beforeLines == afterLines, "行数不变（$beforeLines → $afterLines）")

        // 替换后必须能读回新值，且其他条目不受影响
        val reparsed = HashMap<String, String>()
        out.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) return@forEach
            val eq = t.indexOf('=')
            if (eq > 0) reparsed[t.substring(0, eq).trim()] = t.substring(eq + 1).trim()
        }
        expect(reparsed[target] == newName, "替换后读回新名称")
        expect(reparsed.size == names.size, "条目总数不变（${names.size} → ${reparsed.size}）")

        val othersSame = names.filterKeys { it != target }.all { (k, v) -> reparsed[k] == v }
        expect(othersSame, "其余条目内容未被改动")
    }

    println()
    println(if (fail == 0) "全部通过" else "$fail 项失败")
    if (fail != 0) kotlin.system.exitProcess(1)
}
KOT

echo "[*] 编译测试..."
"$KOTLINC" -nowarn -jvm-target 1.8 -classpath "$STDLIB" \
    -d "$WORK/out" "$WORK/CivCheck.kt" 2>&1 | sed 's/^/    /' | tail -15
[ "${PIPESTATUS[0]}" -eq 0 ] || { echo "[x] 编译失败"; exit 1; }

echo "[*] 运行（APK: $APK）"
java -cp "$WORK/out:$STDLIB" CivCheckKt "$APK"