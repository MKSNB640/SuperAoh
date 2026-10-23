#!/bin/bash
# Operit AI 层可移植性实测
# 目标：判断哪些文件能脱离 Compose/Room/ObjectBox 独立编译
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
O=/storage/emulated/0/自建Claude逆向学习/Operit-main/app/src/main/java/com/ai/assistance/operit
KTC=/opt/kotlinc/bin/kotlinc
AJ=/usr/lib/android-sdk/platforms/android-23/android.jar
W=/tmp/oport
rm -rf $W; mkdir -p $W/src $W/out $W/libs

echo "===== 第1步：找出 Operit 里所有「零第三方依赖」的纯 Kotlin 文件 ====="
# 零依赖 = 只 import kotlin.* / java.* / 自己包内
ZERO=0
> $W/pure.txt
while IFS= read -r f; do
    deps=$(grep -E '^import ' "$f" 2>/dev/null | sed 's/^import //' | grep -vE '^(kotlin|kotlinx\.coroutines|java|javax)\.' | grep -vE '^com\.ai\.assistance\.operit' | head -1)
    if [ -z "$deps" ]; then
        echo "$f" >> $W/pure.txt
        ZERO=$((ZERO+1))
    fi
done < <(find "$O" -name '*.kt')
echo "零第三方依赖文件数：$ZERO"

echo
echo "===== 第2步：这些文件里，AI 相关的有多少 ====="
grep -E '/(api|core)/' $W/pure.txt 2>/dev/null | head -20
echo "AI 相关（api/core）零依赖文件数：$(grep -cE '/(api|core)/' $W/pure.txt 2>/dev/null || echo 0)"

echo
echo "===== 第3步：核心接口层依赖画像 ====="
for f in api/chat/llmprovider/AIService.kt core/chat/hooks/PromptTurn.kt core/chat/hooks/PromptHookRegistry.kt; do
    p="$O/$f"
    [ -f "$p" ] || continue
    echo "--- $f ($(wc -l < "$p") 行) ---"
    grep -E '^import ' "$p" 2>/dev/null | sed 's/^import /  /' | head -12
done

echo
echo "===== 第4步：OpenAIProvider 的真实依赖（实现层） ====="
grep -E '^import ' "$O/api/chat/llmprovider/OpenAIProvider.kt" 2>/dev/null | sed 's/^import //' | awk -F. '{print $1"."$2}' | sort | uniq -c | sort -rn | head -15

echo
echo "===== 第5步：判断 coroutines 是否必须 ====="
echo "引用 kotlinx.coroutines 的 AI 文件数：$(grep -rl 'kotlinx.coroutines' "$O/api" "$O/core" 2>/dev/null | wc -l)"
echo "引用 androidx.compose 的 AI 文件数：$(grep -rl 'androidx.compose' "$O/api" "$O/core" 2>/dev/null | wc -l)"
echo "引用 okhttp 的 AI 文件数：$(grep -rl 'okhttp3' "$O/api" "$O/core" 2>/dev/null | wc -l)"
echo "引用 org.json 的 AI 文件数：$(grep -rl 'org.json' "$O/api" "$O/core" 2>/dev/null | wc -l)"