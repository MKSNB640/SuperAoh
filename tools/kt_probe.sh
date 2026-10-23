#!/bin/bash
# Kotlin 能否接入现有 Android 构建链（javac + D8）的实测
set -o pipefail
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
KTC=/tmp/kotlinc/kotlinc/bin/kotlinc
AJ=/usr/lib/android-sdk/platforms/android-23/android.jar
R8=/opt/superaoh/tools/r8-8.5.35.jar
PROJ=/storage/emulated/0/SuperAoh项目
W=/tmp/ktandroid
rm -rf $W; mkdir -p $W/src/com/superaoh/kt $W/cls $W/out

cat > $W/src/com/superaoh/kt/KtUtil.kt <<'KT'
package com.superaoh.kt

import android.content.Context
import java.io.File

/** Kotlin 侧工具：统计工作区文件数与总字节。 */
object KtUtil {

    data class Stat(val files: Int, val bytes: Long)

    fun scan(dir: File): Stat {
        var n = 0
        var b = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) { n++; b += f.length() }
        }
        return Stat(n, b)
    }

    /** 用 Android API 读文件名，验证 android.jar 真的生效。 */
    fun describe(ctx: Context, dir: File): String {
        val s = scan(dir)
        return "${ctx.packageName}: ${s.files} files, ${s.bytes} bytes"
    }
}
KT

echo "=== [1/3] kotlinc 编译（含 android.jar）==="
START=$(date +%s)
"$KTC" -nowarn -jvm-target 1.8 \
    -classpath "$AJ" \
    -d $W/cls \
    $W/src/com/superaoh/kt/KtUtil.kt 2>&1 | tail -8
RC=$?
END=$(date +%s)
echo "kotlinc exit=$RC  耗时=$((END-START))s"
echo "生成 class："
find $W/cls -name '*.class' | sed "s|$W/cls/||"
[ $RC -ne 0 ] && { echo "RESULT: KOTLINC_FAIL"; exit 1; }

echo
echo "=== [2/3] D8 处理 Kotlin 产物 ==="
java -cp "$R8" com.android.tools.r8.D8 --min-api 24 --lib "$AJ" \
    --output $W/out $(find $W/cls -name '*.class') 2>&1 | head -10
if [ -f $W/out/classes.dex ]; then
    echo "dex 大小：$(du -h $W/out/classes.dex | cut -f1)"
else
    echo "RESULT: D8_FAIL"; exit 1
fi

echo
echo "=== [3/3] 与 Java 混合编译（Kotlin 调 Java，Java 调 Kotlin）==="
mkdir -p $W/mix
cat > $W/mix/JBridge.java <<'JAVA'
package com.superaoh.kt;
public class JBridge {
    // Java 调 Kotlin object
    public static String callKt() {
        return KtUtil.INSTANCE.scan(new java.io.File(".")).toString();
    }
}
JAVA
"$KTC" -nowarn -jvm-target 1.8 -classpath "$AJ:$W/cls" -d $W/cls $W/mix/JBridge.java 2>/dev/null
javac --release 8 -nowarn -cp "$AJ:$W/cls" -d $W/cls $W/mix/JBridge.java 2>&1 | head -5
echo "混合编译 exit=$?"
java -cp "$R8" com.android.tools.r8.D8 --min-api 24 --lib "$AJ" \
    --output $W/out2 $(find $W/cls -name '*.class') 2>&1 | head -6
if [ -f $W/out2/classes.dex ]; then
    echo "RESULT: KOTLIN_ANDROID_OK  dex=$(du -h $W/out2/classes.dex|cut -f1)"
else
    echo "RESULT: MIX_FAIL"
fi