#!/bin/bash
# 决定性验证：能否用本项目的 kotlinc 编译 mobile-agent 的 agent-core
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
KTC=/opt/kotlinc/bin/kotlinc
STD=/opt/kotlinc/lib/kotlin-stdlib.jar
D=/tmp/ma/mobile-agent-main
L=/tmp/libs2
W=/tmp/macore
rm -rf $W; mkdir -p $W/cls

LIB_CP="$STD:$L/coroutines.jar:$L/serjson.jar:$L/sercore.jar"

echo "===== agent-core 源码 ====="
find "$D/agent-core/src/main" -name '*.kt' | sed "s|$D/agent-core/src/main/kotlin/||"
echo "文件数：$(find "$D/agent-core/src/main" -name '*.kt' | wc -l)"
echo "总行数：$(find "$D/agent-core/src/main" -name '*.kt' | xargs wc -l 2>/dev/null | tail -1)"

echo
echo "===== 编译 ====="
START=$(date +%s)
"$KTC" -nowarn -jvm-target 1.8 \
    -classpath "$LIB_CP" \
    -d $W/cls \
    $(find "$D/agent-core/src/main" -name '*.kt') 2>&1 | grep -E 'error:' | head -25
END=$(date +%s)
N=$(find $W/cls -name '*.class' 2>/dev/null | wc -l)
echo "耗时 $((END-START))s，生成 class：$N"

if [ "$N" -gt 0 ]; then
    echo "RESULT: COMPILE_OK ($N classes)"
else
    echo "RESULT: COMPILE_FAIL"
fi