#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# SuperAoh Gradle 构建脚本
#
# 用途：构建含 Compose UI 的 APK。
# 为什么需要它：Compose 编译器插件只能通过 Gradle（KGP）加载，裸 kotlinc 无法编译，
#              因此一旦要改 Compose 界面就必须走本脚本。
#
# 用法：
#   ./guild.sh            构建 debug APK
#   ./guild.sh install    构建并安装到设备
#   ./guild.sh clean      清理产物
#   ./guild.sh check      只做 Kotlin 编译（快速排错）
# ---------------------------------------------------------------------------
set -u

PROJ="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJ" || exit 1

# ---- 工具链定位 ----
GRADLE_BIN="${GRADLE_BIN:-/tmp/g8/gradle-8.9/bin/gradle}"
ANDROID_SDK="${ANDROID_HOME:-/opt/android-sdk}"
AAPT2_ARM="$(command -v aapt2 || echo /usr/bin/aapt2)"

# JDK：Gradle 8.9 + AGP 8.5.2 需要 17+，本机是 21
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
export ANDROID_HOME="$ANDROID_SDK"
export ANDROID_SDK_ROOT="$ANDROID_SDK"

# ---------------------------------------------------------------------------
# 关键环境修补（这两项是 aarch64 环境能构建的前提，勿删）
#
# 1) aapt2：AGP 会去下载 x86_64 版 aapt2，本机是 aarch64 无法执行，
#    报 "AAPT2 ... Daemon startup failed"。用 gradle.properties 的
#    android.aapt2FromMavenOverride 指向系统 Debian arm64 版。
# 2) 本地 jar：libs/coroutines.jar 不能进依赖，Compose 自带 kotlinx-coroutines，
#    否则 Duplicate class _COROUTINE.*。
# ---------------------------------------------------------------------------
if [ ! -x "$AAPT2_ARM" ]; then
    echo "[warn] 未找到 arm64 版 aapt2：apt install aapt" >&2
fi
if ! grep -q 'aapt2FromMavenOverride' gradle.properties 2>/dev/null; then
    echo "[warn] gradle.properties 缺少 aapt2FromMavenOverride，aarch64 下会构建失败" >&2
fi

if [ ! -x "$GRADLE_BIN" ]; then
    echo "[error] 找不到 Gradle 8.9：$GRADLE_BIN" >&2
    echo "        可用 GRADLE_BIN=/path/to/gradle 覆盖" >&2
    exit 1
fi

CMD="${1:-build}"
case "$CMD" in
    build)
        TASKS=":app:assembleDebug"
        ;;
    install)
        TASKS=":app:assembleDebug"
        ;;
    check)
        TASKS=":app:compileDebugKotlin"
        ;;
    clean)
        TASKS="clean"
        ;;
    *)
        echo "用法: $0 [build|install|check|clean]"
        exit 1
        ;;
esac

echo "==> Gradle: $GRADLE_BIN"
echo "==> SDK   : $ANDROID_SDK"
echo "==> aapt2 : $AAPT2_ARM"
echo "==> 任务  : $TASKS"

"$GRADLE_BIN" $TASKS --no-daemon --console=plain
RC=$?

if [ $RC -ne 0 ]; then
    echo "==> 构建失败 (exit $RC)" >&2
    exit $RC
fi

APK="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
if [ "$CMD" = "build" ] && [ -f "$APK" ]; then
    echo "==> 产物: $APK ($(stat -c%s "$APK") 字节)"
fi

# 安装：必须经 /data/local/tmp 中转，否则 system_server 读不到 /storage（scoped storage）
if [ "$CMD" = "install" ] && [ -f "$APK" ]; then
    echo "==> 安装到设备..."
    # 需在有 adb/shell 权限的环境执行；此处给出提示
    echo "    请手动执行："
    echo "    cp '$APK' /data/local/tmp/superaoh.apk && pm install -r -t /data/local/tmp/superaoh.apk"
fi