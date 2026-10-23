#!/bin/bash
# 关键验证：裸 kotlinc + Compose 编译器插件 + D8，能否真正跑通 Compose
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
KTC=/opt/kotlinc/bin/kotlinc
STD=/opt/kotlinc/lib/kotlin-stdlib.jar
C=/tmp/cpl
AJ=/usr/lib/android-sdk/platforms/android-23/android.jar
W=/tmp/cptest
rm -rf $W; mkdir -p $W/src $W/cls $W/out

cat > $W/src/Demo.kt <<'KT'
package com.superaoh.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class Msg(val role: String, val text: String)

@Composable
fun ChatBubble(msg: Msg) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (isUser) Color(0xFF5B8CFF) else Color(0xFF1B2130),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = msg.text,
                color = Color(0xFFEDEFFF),
                fontSize = 14.sp,
                modifier = Modifier.padding(10.dp)
            )
        }
    }
}

@Composable
fun ChatScreen(messages: List<Msg>) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0E14))) {
        LazyColumn(Modifier.weight(1f)) {
            items(messages) { m -> ChatBubble(m) }
        }
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息") }
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { input = "" }) {
                Text("发送", fontWeight = FontWeight.Medium)
            }
        }
    }
}
KT

LIB_CP="$STD:$AJ"
for j in $C/runtime-android-1.7.6.jar $C/ui-android-1.7.6.jar \
         $C/foundation-android-1.7.6.jar $C/material3-android-1.3.1.jar; do
    LIB_CP="$LIB_CP:$j"
done

echo "===== 用 Compose 编译器插件编译 ====="
START=$(date +%s)
"$KTC" -nowarn -jvm-target 1.8 \
    -Xplugin=/opt/kotlinc/lib/compose-compiler.jar \
    -classpath "$LIB_CP" \
    -d $W/cls \
    $W/src/Demo.kt 2>&1 | head -20
END=$(date +%s)
N=$(find $W/cls -name '*.class' 2>/dev/null | wc -l)
echo "耗时 $((END-START))s，生成 class：$N"

if [ "$N" -gt 0 ]; then
    echo "RESULT: COMPOSE_COMPILE_OK ($N classes)"
else
    echo "RESULT: COMPOSE_COMPILE_FAIL"
    exit 1
fi

echo
echo "===== D8 转换 ====="
java -cp /opt/superaoh/tools/r8-8.5.35.jar com.android.tools.r8.D8 \
    --min-api 24 --lib "$AJ" --output $W/out \
    $(find $W/cls -name '*.class') \
    $C/runtime-android-1.7.6.jar $C/ui-android-1.7.6.jar \
    $C/foundation-android-1.7.6.jar $C/material3-android-1.3.1.jar 2>&1 | grep -vE '^Info' | head -10

if [ -f $W/out/classes.dex ]; then
    echo "RESULT: D8_OK  dex=$(du -h $W/out/classes.dex | cut -f1)"
else
    echo "RESULT: D8_FAIL"
fi