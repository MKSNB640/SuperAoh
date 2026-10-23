#!/bin/bash
# 实拷 Operit 的纯 Kotlin AI 文件，验证能否在本项目编译通过
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
O=/storage/emulated/0/自建Claude逆向学习/Operit-main/app/src/main/java/com/ai/assistance/operit
KTC=/opt/kotlinc/bin/kotlinc
AJ=/usr/lib/android-sdk/platforms/android-23/android.jar
W=/tmp/oport2
rm -rf $W; mkdir -p $W/cls
DEST=/storage/emulated/0/SuperAoh项目/app/src/main/java/com/ai/assistance/operit

# 一批真正有用的、零第三方依赖的 AI 核心文件
FILES="
core/chat/hooks/PromptTurn.kt
api/chat/llmprovider/LlmRetryPolicy.kt
api/chat/llmprovider/EndpointCompleter.kt
api/chat/llmprovider/MediaLinkParser.kt
api/chat/llmprovider/ApiKeyProvider.kt
api/chat/llmprovider/SlidingWindowRateLimiter.kt
core/chat/hooks/PromptHookRegistry.kt
"

echo "===== 复制源码 ====="
CNT=0
for f in $FILES; do
    [ -f "$O/$f" ] || { echo "  缺失: $f"; continue; }
    mkdir -p "$DEST/$(dirname $f)"
    cp "$O/$f" "$DEST/$f"
    echo "  已复制: $f ($(wc -l < "$O/$f") 行)"
    CNT=$((CNT+1))
done
echo "共复制 $CNT 个文件"

echo
echo "===== 独立编译验证 ====="
KT_FILES=$(find "$DEST" -name '*.kt')
echo "待编译文件："
echo "$KT_FILES" | sed 's|.*/operit/|  |'

"$KTC" -nowarn -jvm-target 1.8 -classpath "$AJ" -d $W/cls $KT_FILES 2>&1 | grep -E 'error|warning: unre' | head -25
RC=${PIPESTATUS[0]}
if [ -d $W/cls/com ] && [ -n "$(find $W/cls -name '*.class' 2>/dev/null)" ]; then
    echo "✅ 编译成功，class 数：$(find $W/cls -name '*.class' | wc -l)"
else
    echo "❌ 编译失败"
fi