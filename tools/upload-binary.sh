#!/usr/bin/env bash
# ===========================================================================
# upload-binary.sh —— 通过 GitHub Git Data API 上传单个大文件
#
# 为什么需要这个脚本：
#   本机网络下 github.com:443 被阻断（curl 到 github.com 直接超时），
#   但 api.github.com 可以正常访问。所以 git push 用不了，
#   只能改用 GitHub 的 Git Data API（全走 api.github.com）：
#     1) POST /git/blobs          创建 blob（内容 base64）
#     2) 取当前 ref 的 tree
#     3) POST /git/trees          创建新 tree（追加单个文件）
#     4) POST /git/commits        创建 commit
#     5) PATCH /git/refs/heads/main  移动分支
#
# 用法：
#   ./upload-binary.sh <仓库内路径> <本地文件>
#
# 注意：本脚本把 token 从环境变量 GITHUB_TOKEN 读取，不再硬编码。
# ===========================================================================
set -u

REPO="MKSNB640/SuperAoh"
BRANCH="main"
API="https://api.github.com/repos/$REPO"

TOKEN="${GITHUB_TOKEN:-}"
if [ -z "$TOKEN" ]; then
    echo "[x] 请先设置 GITHUB_TOKEN 环境变量" >&2
    exit 1
fi

REMOTE_PATH="$1"
LOCAL_FILE="$2"
COMMIT_MSG="${3:-chore: 上传 $REMOTE_PATH}"

[ -f "$LOCAL_FILE" ] || { echo "[x] 文件不存在: $LOCAL_FILE" >&2; exit 1; }

SIZE=$(stat -c%s "$LOCAL_FILE")
echo "[*] 上传 $LOCAL_FILE ($(numfmt --to=iec $SIZE 2>/dev/null || echo $SIZE)) -> $REMOTE_PATH"

api() {
    local method="$1" url="$2" datafile="${3:-}"
    if [ -n "$datafile" ]; then
        curl -sS -m 300 -X "$method" \
            -H "Authorization: token $TOKEN" \
            -H "Accept: application/vnd.github+json" \
            -H "Content-Type: application/json" \
            --data-binary "@$datafile" "$url"
    else
        curl -sS -m 300 -X "$method" \
            -H "Authorization: token $TOKEN" \
            -H "Accept: application/vnd.github+json" "$url"
    fi
}

json_field() {
    # 从 JSON 里粗取一个字符串字段（避免依赖 jq）
    grep -o "\"$1\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -1 | sed 's/.*:[[:space:]]*"//;s/"$//'
}

# ---- 1. 当前 ref 的 commit / tree ----
echo "[*] 读取当前 ref..."
REF_JSON=$(api GET "$API/git/ref/heads/$BRANCH")
HEAD_SHA=$(echo "$REF_JSON" | json_field sha)
[ -n "$HEAD_SHA" ] || { echo "[x] 读取 ref 失败: $REF_JSON" >&2; exit 1; }
echo "    head = $HEAD_SHA"

COMMIT_JSON=$(api GET "$API/git/commits/$HEAD_SHA")
# commit 对象里 tree 是嵌套对象：先压平成一行，再取 "tree": { ... "sha": "xxx" }
BASE_TREE=$(echo "$COMMIT_JSON" | tr -d '\n' | tr -s ' ' | \
    grep -o '"tree": *{[^}]*}' | head -1 | \
    grep -o '"sha": *"[^"]*"' | head -1 | sed 's/.*"sha": *"//;s/"$//')
[ -n "$BASE_TREE" ] || { echo "[x] 读取 tree 失败" >&2; echo "$COMMIT_JSON" | head -20 >&2; exit 1; }
echo "    base tree = $BASE_TREE"

# ---- 2. 上传 blob（base64） ----
echo "[*] 上传 blob（base64 编码，约 $((SIZE * 4 / 3 / 1024 / 1024))MB 请求体）..."
B64="$LOCAL_FILE.b64"
base64 -w 0 "$LOCAL_FILE" > "$B64"
B64_SIZE=$(stat -c%s "$B64")
echo "    base64 大小 = $(numfmt --to=iec $B64_SIZE 2>/dev/null || echo $B64_SIZE)"

printf '{"content":"' > /tmp/blob_req.json
cat "$B64" >> /tmp/blob_req.json
printf '","encoding":"base64"}' >> /tmp/blob_req.json
rm -f "$B64"

BLOB_JSON=$(api POST "$API/git/blobs" /tmp/blob_req.json)
rm -f /tmp/blob_req.json
BLOB_SHA=$(echo "$BLOB_JSON" | json_field sha)
if [ -z "$BLOB_SHA" ]; then
    echo "[x] 创建 blob 失败:" >&2
    echo "$BLOB_JSON" | head -20 >&2
    exit 1
fi
echo "    blob sha = $BLOB_SHA"

# ---- 3. 创建新 tree ----
echo "[*] 创建 tree..."
TREE_REQ=$(mktemp)
printf '{"base_tree":"%s","tree":[{"path":"%s","mode":"100644","type":"blob","sha":"%s"}]}' \
    "$BASE_TREE" "$REMOTE_PATH" "$BLOB_SHA" > "$TREE_REQ"
TREE_JSON=$(api POST "$API/git/trees" "$TREE_REQ")
rm -f "$TREE_REQ"
NEW_TREE=$(echo "$TREE_JSON" | json_field sha)
[ -n "$NEW_TREE" ] || { echo "[x] 创建 tree 失败: $TREE_JSON" >&2; exit 1; }
echo "    new tree = $NEW_TREE"

# ---- 4. 创建 commit ----
echo "[*] 创建 commit..."
REQ=$(mktemp)
# 用 python 生成合法 JSON（消息里可能有引号/换行）
python3 - "$COMMIT_MSG" "$NEW_TREE" "$HEAD_SHA" > "$REQ" <<'PY'
import json, sys
msg, tree, parent = sys.argv[1], sys.argv[2], sys.argv[3]
print(json.dumps({
    "message": msg,
    "tree": tree,
    "parents": [parent],
}, ensure_ascii=False))
PY
COMMIT_JSON=$(api POST "$API/git/commits" "$REQ")
rm -f "$REQ"
NEW_COMMIT=$(echo "$COMMIT_JSON" | json_field sha)
[ -n "$NEW_COMMIT" ] || { echo "[x] 创建 commit 失败: $COMMIT_JSON" >&2; exit 1; }
echo "    new commit = $NEW_COMMIT"

# ---- 5. 移动分支 ----
echo "[*] 更新 $BRANCH 分支..."
PATCH_JSON=$(api PATCH "$API/git/refs/heads/$BRANCH" <(printf '{"sha":"%s","force":false}' "$NEW_COMMIT"))
NEW_REF=$(echo "$PATCH_JSON" | json_field sha)
[ -n "$NEW_REF" ] || { echo "[x] 更新 ref 失败: $PATCH_JSON" >&2; exit 1; }

echo "[+] 上传完成：$REMOTE_PATH"
echo "    $BRANCH -> $NEW_REF"