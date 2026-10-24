#!/usr/bin/env bash
# ===========================================================================
# restore-binaries.sh —— 把仓库里分块的二进制资源还原成构建所需位置
#
# 为什么要分块：
#   GitHub 对单个文件有 100MB 硬限制（超过直接拒收，不是警告）。
#   app/src/main/assets/toolchain/jadx/jadx.jar 是 122MB，无法直接提交，
#   因此在 assets-binary/ 下拆成 3 块（每块 60MB）存放。
#
# 用法（在仓库根目录执行）：
#   ./tools/restore-binaries.sh          # 还原 jadx.jar
#   ./tools/restore-binaries.sh --check  # 只校验，不写文件
#
# 幂等：目标文件已存在且 SHA256 正确时直接跳过。
# ===========================================================================
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$ROOT/assets-binary"
JADX_DIR="$ROOT/app/src/main/assets/toolchain/jadx"
JADX_OUT="$JADX_DIR/jadx.jar"

# 原始 jadx.jar 的 SHA256（分块拼回后必须一致）
EXPECT_SHA="dc717e51c08d31a66357933c8d4896755d7c4939745e03aefd02427041f9aa53"

RED='\033[0;31m'; GRN='\033[0;32m'; YEL='\033[0;33m'; CYN='\033[0;36m'; NC='\033[0m'
log()  { echo -e "${CYN}[*]${NC} $*"; }
ok()   { echo -e "${GRN}[+]${NC} $*"; }
warn() { echo -e "${YEL}[!]${NC} $*"; }
die()  { echo -e "${RED}[x]${NC} $*" >&2; exit 1; }

CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

sha_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

# ---- 检查分块是否齐全 ----
[ -d "$BIN" ] || die "找不到目录：$BIN"

PARTS=$(ls "$BIN"/jadx.jar.part-* 2>/dev/null | sort)
[ -n "$PARTS" ] || die "找不到 jadx 分块文件：$BIN/jadx.jar.part-*"

PART_COUNT=$(echo "$PARTS" | wc -l | tr -d ' ')
log "找到 $PART_COUNT 个分块，总大小 $(du -ch $PARTS | tail -1 | cut -f1)"

# ---- 已还原且校验通过则跳过 ----
if [ -f "$JADX_OUT" ]; then
    CUR=$(sha_of "$JADX_OUT")
    if [ "$CUR" = "$EXPECT_SHA" ]; then
        ok "jadx.jar 已存在且校验通过，无需还原"
        ok "  $JADX_OUT"
        exit 0
    fi
    warn "jadx.jar 已存在但校验不符，将重新还原"
    warn "  当前: $CUR"
fi

if [ "$CHECK_ONLY" = "1" ]; then
    log "仅校验模式，未写入文件"
    exit 0
fi

# ---- 拼接分块 ----
mkdir -p "$JADX_DIR" || die "无法创建目录：$JADX_DIR"
TMP="$JADX_OUT.tmp"

log "拼接连分块 -> jadx.jar（约 122MB）..."
# 用 cat 顺序拼接；分块由 split 生成，命名 part-aa / part-ab / part-ac 保证字典序正确
# shellcheck disable=SC2086
cat $PARTS > "$TMP" || { rm -f "$TMP"; die "拼接失败"; }

# ---- 校验 ----
GOT=$(sha_of "$TMP")
if [ "$GOT" != "$EXPECT_SHA" ]; then
    rm -f "$TMP"
    die "SHA256 校验失败
  期望: $EXPECT_SHA
  实际: $GOT
分块可能损坏，请重新克隆仓库。"
fi

mv -f "$TMP" "$JADX_OUT" || die "无法移动到 $JADX_OUT"
ok "还原完成：$JADX_OUT"
ok "  大小: $(du -h "$JADX_OUT" | cut -f1)"
ok "  SHA256: $GOT"

# ---- 顺带检查其他构建必需资源 ----
echo
log "检查其他构建必需资源..."
MISSING=0
for f in \
    "sandbox/assets/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz" \
    "app/src/main/assets/toolchain/apktool.jar" \
    "app/src/main/assets/toolchain/smali.jar" \
    "app/src/main/assets/toolchain/baksmali.jar" \
    "build-keys/testkey.keystore" \
    "app/libs/gdx-game.jar"
do
    if [ -f "$ROOT/$f" ]; then
        ok "$f"
    else
        warn "缺失: $f"
        MISSING=$((MISSING + 1))
    fi
done

if [ "$MISSING" -gt 0 ]; then
    echo
    warn "有 $MISSING 个资源缺失，构建可能失败。参见 README「构建前置资源」。"
    exit 1
fi

echo
ok "全部构建必需资源就绪，可以执行 ./guild.sh build"
