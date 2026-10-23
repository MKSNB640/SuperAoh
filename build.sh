#!/bin/bash
# ============================================================
# SuperAoh 构建脚本（arm64 设备端原生，无需 Gradle / Android Studio）
#
# 工具链（均为 arm64 原生，已实测可用）：
#   javac --release 8   （D8 4.0.48 不支持 Java 21 字节码，必须锁 8）
#   aapt2               （apt install aapt，Debian/Ubuntu 官方 arm64 版）
#   android.jar         （apt install android-sdk-platform-23）
#   d8                  （R8 内置，/opt/superaoh/tools/r8.jar）
#   zipalign            （/usr/local/bin/zipalign）
#   apksigner           （apt install apksigner，支持 --key/--cert 直读 PEM+PK8）
#
# 用法：
#   ./build.sh              # 全量构建 release
#   ./build.sh clean        # 清理
#   ./build.sh install      # 构建后 adb install
# ============================================================

set -u

PROJ="$(cd "$(dirname "$0")" && pwd)"
APP="$PROJ/app"
OUT="$PROJ/build"

# ---------- 可配置 ----------
PKG="com.superaoh"
VERSION_CODE=1
VERSION_NAME="0.1.0"
# minSdk 24（Android 7.0）：
#   D8 min-api<24 时若要 desugar java.util.function.* 会缺库。
#   24 起系统自带 java.util.function，D8 不再需要 desugar，产物干净。
MIN_SDK=24
TARGET_SDK=33

# ---------- 工具路径 ----------
ANDROID_JAR="/usr/lib/android-sdk/platforms/android-23/android.jar"
# D8 版本说明（重要）：
#   R8 4.0.48 存在版本级致命 bug：遇到 “implements 泛型接口<具体类型>”
#   （如 Comparator<Meta>、Comparable<X>、自定义 Handler<T>）时必抛
#   NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null
#   该 bug 与匿名类/嵌套类/访问修饰符无关，改代码无法规避。
#   8.5.35 已实测修复。切勿降级回 4.0.48。
R8_JAR="/opt/superaoh/tools/r8-8.5.35.jar"
[ -f "$R8_JAR" ] || R8_JAR="/opt/superaoh/tools/r8.jar"
[ -f "$R8_JAR" ] || R8_JAR="/tmp/lib/r8-4.0.48.jar"

# Kotlin 编译器（可选）。存在即启用 Java+Kotlin 混合编译，
# 不存在则退化为纯 Java 构建，不影响任何现有功能。
KOTLINC="/tmp/kotlinc/kotlinc/bin/kotlinc"
[ -x "$KOTLINC" ] || KOTLINC="/opt/kotlinc/kotlinc/bin/kotlinc"
# Kotlin 标准库：存在 .kt 时必须打进 dex，否则运行时 NoClassDefFoundError
KOTLIN_STDLIB="/opt/kotlinc/lib/kotlin-stdlib.jar"
[ -f "$KOTLIN_STDLIB" ] || KOTLIN_STDLIB="/tmp/kotlinc/kotlinc/lib/kotlin-stdlib.jar"
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
AAPT2="$(command -v aapt2 || echo /usr/bin/aapt2)"
ZIPALIGN="$(command -v zipalign || echo /usr/bin/zipalign)"
# /usr/local/bin/zipalign 可能是 0 字节空壳，优先用 /usr/bin/zipalign
if [ -x /usr/bin/zipalign ] && [ -s /usr/bin/zipalign ]; then
    ZIPALIGN=/usr/bin/zipalign
fi
APKSIGNER="$(command -v apksigner || echo /usr/bin/apksigner)"

# ---------- 签名密钥 ----------
KEY_PK8="$PROJ/build-keys/testkey.pk8"
KEY_PEM="$PROJ/build-keys/testkey.x509.pem"

# ---------- 输出 ----------
APK_UNSIGNED="$OUT/superaoh-unsigned.apk"
APK_ALIGNED="$OUT/superaoh-aligned.apk"
APK_FINAL="$OUT/SuperAoh-$VERSION_NAME.apk"

RED='\033[0;31m'; GRN='\033[0;32m'; YEL='\033[0;33m'; CYN='\033[0;36m'; NC='\033[0m'
log()  { echo -e "${CYN}[*]${NC} $*"; }
ok()   { echo -e "${GRN}[+]${NC} $*"; }
warn() { echo -e "${YEL}[!]${NC} $*"; }
die()  { echo -e "${RED}[x]${NC} $*"; exit 1; }

if [ "${1:-}" = "clean" ]; then
    rm -rf "$OUT"
    ok "已清理 $OUT"
    exit 0
fi

# ============================================================
# 0. 环境检查
# ============================================================
log "检查工具链..."
[ -f "$ANDROID_JAR" ] || die "缺少 android.jar：apt install android-sdk-platform-23"
[ -f "$R8_JAR" ]      || die "缺少 r8.jar，请放到 /opt/superaoh/tools/r8.jar"
[ -x "$AAPT2" ]       || die "缺少 aapt2：apt install aapt"
[ -f "$KEY_PK8" ]     || die "缺少签名密钥 $KEY_PK8"
[ -f "$KEY_PEM" ]     || die "缺少签名证书 $KEY_PEM"
mkdir -p "$OUT"

SRC_COUNT=$(find "$APP/src/main/java" -name '*.java' | wc -l)
[ "$SRC_COUNT" -gt 0 ] || die "没有找到 Java 源文件"
log "源文件：$SRC_COUNT 个"

# ============================================================
# 1. javac --release 8
# ============================================================
log "编译 Java（--release 8）..."
rm -rf "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/classes" "$OUT/dex"

# ============================================================
# 1. 编译（Java + Kotlin 混合，可选）
#    策略：
#      a) 先 kotlinc 编译所有 .kt（此时 .java 作为源码可被 Kotlin 看到）
#      b) 再 javac 编译所有 .java，classpath 带上 kotlinc 产物
#      c) 最后 kotlinc 再跑一遍，把只被 Kotlin 引用但首轮未生成的
#         class 补上（双向引用场景）
#    没有 .kt 时完全跳过，行为与纯 Java 构建一致。
# ============================================================
find "$APP/src/main/java" -name '*.java' > "$OUT/sources.txt"
find "$APP/src/main/java" -name '*.kt'   > "$OUT/ktsources.txt"
JAVA_COUNT=$(wc -l < "$OUT/sources.txt" | tr -d ' ')
KT_COUNT=$(wc -l < "$OUT/ktsources.txt" | tr -d ' ')

CP="$APP/libs/gdx-game.jar:$ANDROID_JAR"
# 自动发现 app/libs 下的其他 jar（Markwon / commonmark / coroutines / serialization 等）
# 新增第三方库只要把 jar 丢进 app/libs 即可，无需再改本脚本。
THIRD_PARTY_JARS=""
for jar in "$APP"/libs/*.jar; do
    [ -f "$jar" ] || continue
    case "$jar" in
        */gdx-game.jar) continue ;;
    esac
    THIRD_PARTY_JARS="$THIRD_PARTY_JARS $jar"
    CP="$CP:$jar"
done
[ -n "$THIRD_PARTY_JARS" ] && log "第三方库：$(echo $THIRD_PARTY_JARS | tr ' ' '\n' | xargs -n1 basename | tr '\n' ' ')"

if [ "$KT_COUNT" -gt 0 ] && [ -x "$KOTLINC" ]; then
    log "Kotlin 编译（$KT_COUNT 个 .kt）..."
    # 关键：把 Java 源文件一并作为输入传给 kotlinc。
    # kotlinc 会解析它们以获得类型信息（不产出 Java 的 class），
    # 否则 Kotlin 侧无法解析 Java 声明的类，报 unresolved reference。
    "$KOTLINC" -nowarn -jvm-target 1.8 \
        -classpath "$CP" \
        -d "$OUT/classes" \
        @"$OUT/ktsources.txt" \
        @"$OUT/sources.txt" 2>&1 | sed 's/^/    /' | tail -30
    [ "${PIPESTATUS[0]}" -eq 0 ] || die "kotlinc 编译失败"
    ok "Kotlin 首轮完成"
elif [ "$KT_COUNT" -gt 0 ]; then
    warn "发现 $KT_COUNT 个 .kt 但未找到 kotlinc（$KOTLINC），已跳过"
fi

log "Java 编译（$JAVA_COUNT 个 .java）..."
javac --release 8 -encoding UTF-8 -nowarn \
    -cp "$CP:$OUT/classes" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt" 2>&1 | sed 's/^/    /'
[ "${PIPESTATUS[0]}" -eq 0 ] || die "javac 编译失败"

# 次轮：Java 产物就绪后，再让 Kotlin 补齐对 Java 的反向引用
if [ "$KT_COUNT" -gt 0 ] && [ -x "$KOTLINC" ]; then
    "$KOTLINC" -nowarn -jvm-target 1.8 \
        -classpath "$CP:$OUT/classes" \
        -d "$OUT/classes" \
        @"$OUT/ktsources.txt" 2>&1 | sed 's/^/    /' | tail -15
fi

CLS_COUNT=$(find "$OUT/classes" -name '*.class' | wc -l)
ok "编译完成：$CLS_COUNT 个 class（java $JAVA_COUNT / kt $KT_COUNT）"

# ============================================================
# 2. D8 -> classes.dex
#    必须把 gdx-game.jar 一并打进去：GameContainerActivity extends
#    AndroidApplication，系统启动 Activity 时就要从本 App 的 ClassLoader
#    解析父类，此时游戏 APK 尚未加载，所以 libGDX 必须进自有 dex。
# ============================================================
log "D8 转 dex（libGDX 一并合并）..."
find "$OUT/classes" -name '*.class' > "$OUT/classlist.txt"

# Kotlin 运行时：只要存在 .kt 就必须一并打进 dex，
# 否则 ContextBudget 等类一被调用就 NoClassDefFoundError: kotlin/jvm/internal/Intrinsics
DEX_EXTRA=""
if [ "$KT_COUNT" -gt 0 ]; then
    if [ -f "$KOTLIN_STDLIB" ]; then
        DEX_EXTRA="$KOTLIN_STDLIB"
        log "打包 Kotlin 运行时：$(du -h "$KOTLIN_STDLIB" | cut -f1)"
    else
        die "发现 .kt 但缺少 kotlin-stdlib：$KOTLIN_STDLIB"
    fi
fi

# 第三方运行时库：必须打进 dex，否则运行时 NoClassDefFoundError
for lib in $THIRD_PARTY_JARS; do
    [ -f "$lib" ] && DEX_EXTRA="$DEX_EXTRA $lib"
done
if [ -n "$DEX_EXTRA" ]; then
    log "额外 dex 库：$(echo $DEX_EXTRA | tr ' ' '\n' | xargs -n1 basename | tr '\n' ' ')"
fi

java -cp "$R8_JAR" com.android.tools.r8.D8 \
    --min-api $MIN_SDK \
    --lib "$ANDROID_JAR" \
    --output "$OUT/dex" \
    @"$OUT/classlist.txt" \
    "$APP/libs/gdx-game.jar" \
    $DEX_EXTRA 2>&1 | sed 's/^/    /' | tail -20

[ -f "$OUT/dex/classes.dex" ] || die "D8 未生成 classes.dex"
ok "classes.dex 生成：$(du -h "$OUT/dex/classes.dex" | cut -f1)"

# ============================================================
# 3. aapt2 link -> 二进制 manifest + resources.arsc
# ============================================================
log "aapt2 link 生成资源与二进制 manifest..."
rm -f "$OUT/base.apk"
"$AAPT2" link \
    -o "$OUT/base.apk" \
    --manifest "$APP/src/main/AndroidManifest.xml" \
    --min-sdk-version $MIN_SDK \
    --target-sdk-version $TARGET_SDK \
    --version-code $VERSION_CODE \
    --version-name "$VERSION_NAME" \
    -I "$ANDROID_JAR" \
    --auto-add-overlay 2>&1 | sed 's/^/    /'
[ -f "$OUT/base.apk" ] || die "aapt2 link 失败"
ok "资源 base.apk 生成"

# ============================================================
# 4. 组包：base.apk + classes.dex + jniLibs
# ============================================================
log "组装 APK..."
rm -rf "$OUT/stage"
mkdir -p "$OUT/stage"
(cd "$OUT/stage" && unzip -q -o "$OUT/base.apk")

cp "$OUT/dex/classes.dex" "$OUT/stage/classes.dex"

if [ -d "$APP/src/main/jniLibs" ]; then
    for abi in "$APP"/src/main/jniLibs/*/; do
        [ -d "$abi" ] || continue
        abi_name=$(basename "$abi")
        mkdir -p "$OUT/stage/lib/$abi_name"
        for so in "$abi"*.so; do
            [ -f "$so" ] || continue
            cp "$so" "$OUT/stage/lib/$abi_name/"
        done
    done
    log "native 库：$(find "$OUT/stage/lib" -name '*.so' 2>/dev/null | wc -l) 个"
else
    warn "jniLibs 不存在，APK 将不含 native 库"
fi

rm -f "$APK_UNSIGNED"
(cd "$OUT/stage" && zip -q -r -9 "$APK_UNSIGNED" . -x 'lib/*' 'resources.arsc')
if [ -d "$OUT/stage/lib" ]; then
    (cd "$OUT/stage" && zip -q -0 -r "$APK_UNSIGNED" lib)
fi
(cd "$OUT/stage" && zip -q -0 "$APK_UNSIGNED" resources.arsc)

[ -f "$APK_UNSIGNED" ] || die "组包失败"
ok "未签名 APK：$(du -h "$APK_UNSIGNED" | cut -f1)"

# ============================================================
# 5. zipalign
# ============================================================
log "zipalign..."
rm -f "$APK_ALIGNED"
"$ZIPALIGN" -f -p 4 "$APK_UNSIGNED" "$APK_ALIGNED" 2>&1 | sed 's/^/    /'
[ -f "$APK_ALIGNED" ] || die "zipalign 失败"
ok "对齐完成"

# ============================================================
# 6. apksigner
# ============================================================
log "签名..."
rm -f "$APK_FINAL"
"$APKSIGNER" sign \
    --key "$KEY_PK8" \
    --cert "$KEY_PEM" \
    --min-sdk-version $MIN_SDK \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$APK_FINAL" \
    "$APK_ALIGNED" 2>&1 | sed 's/^/    /'
[ -f "$APK_FINAL" ] || die "签名失败"

"$APKSIGNER" verify --min-sdk-version $MIN_SDK "$APK_FINAL" >/dev/null 2>&1 \
    && ok "签名校验通过" || warn "签名校验有警告（通常仍可安装）"

echo
echo "============================================================"
echo -e " ${GRN}构建成功${NC}"
echo "============================================================"
echo " 产物   : $APK_FINAL"
echo " 大小   : $(du -h "$APK_FINAL" | cut -f1)"
echo " 版本   : $VERSION_NAME ($VERSION_CODE)"
echo " 包名   : $PKG"
echo " minSdk : $MIN_SDK"
echo " 源文件 : $SRC_COUNT java / $CLS_COUNT class"
echo "============================================================"
echo

if [ "${1:-}" = "install" ]; then
    log "安装到设备..."
    adb install -r "$APK_FINAL" || die "adb install 失败"
    ok "安装完成"
fi

exit 0