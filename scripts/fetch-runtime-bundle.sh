#!/usr/bin/env bash
# 拉取运行时制品（Node + Magpie），校验哈希后落到仓库 .cache/ 供 prepare-runtime-libs.sh 使用。
#
# 为什么不在仓库里放这 98MB 二进制：clean clone 要能一键构建，但二进制不该进 git。
# 因此制品发布到固定 Release（runtime-1），CI 按下面的 URL + SHA256SUMS 拉取。
#
# 用法：
#   scripts/fetch-runtime-bundle.sh                  # 从 OFM_RUNTIME_URL 拉取并校验
#   OFM_RUNTIME_URL=file:///x.tar.zst ...            # 允许 file:// / http(s)://
#   OFM_RUNTIME_FROM_CACHE=1 scripts/...             # 本地已有 .cache 产物时跳过下载
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # android-app/
# 缓存目录必须与 prepare-runtime-libs.sh 读的位置一致（仓库根 .cache），
# 否则脚本各自落一份，构建时又报「missing node-runtime-arm64.tgz」。
REPO="$(cd "$ROOT/.." && pwd)"
CACHE_DIR="$REPO/.cache"
mkdir -p "$CACHE_DIR"

NODE_TGZ="node-runtime-arm64.tgz"
MAGPIE_BIN="magpie-android-arm64"

# 期望哈希：与仓库里已验收的制品一致。上游换了运行时就必须同步改这两行，
# 否则 CI 会在校验阶段失败——这正是我们要的（防止静默换入未验证的 Node）。
EXPECT_NODE_SHA256="230397a17157304710e7d897463f685cb1ea866b507113d6e451578e4ed3e4bc"
# Magpie：NDK 29 重编、16KB LOAD 对齐版（旧 4KB 版 fa5efbb9… 不要再用）。
EXPECT_MAGPIE_SHA256="864e77fe5a0af08eed0bc8e183993423369d025b15d300d575332a0217d7f29f"

DEFAULT_URL="https://github.com/luoyi0914/ofm-gateway-android/releases/download/runtime-1/runtime-bundle-v1.tar.zst"
URL="${OFM_RUNTIME_URL:-$DEFAULT_URL}"

sha256_of() { sha256sum "$1" | cut -d' ' -f1; }

if [ "${OFM_RUNTIME_FROM_CACHE:-0}" = "1" ]; then
  echo "使用本地 .cache 产物（OFM_RUNTIME_FROM_CACHE=1）"
  for f in "$NODE_TGZ" "$MAGPIE_BIN"; do
    [ -f "$CACHE_DIR/$f" ] || { echo "本地缺少 $CACHE_DIR/$f" >&2; exit 1; }
  done
else
  echo "拉取运行时制品：$URL"
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  # 必须按远端文件名落地：SHA256SUMS 里写的是制品名，改名会让 sha256sum -c 找不到条目。
  BUNDLE_NAME="$(basename "${URL%%\?*}")"
  case "$URL" in
    file://*|http://*|https://*) curl -fsSL --retry 3 -o "$TMP/$BUNDLE_NAME" "$URL" ;;
    *) echo "不支持的 URL 方案: $URL" >&2; exit 2 ;;
  esac

  # 同目录的 SHA256SUMS 是校验依据；拿不到就拒绝继续（不能「没校验也装上」）。
  SUMS_URL="$(dirname "$URL")/SHA256SUMS"
  curl -fsSL --retry 3 -o "$TMP/SHA256SUMS" "$SUMS_URL" \
    || { echo "拉不到 SHA256SUMS：$SUMS_URL" >&2; exit 1; }
  ( cd "$TMP" && sha256sum -c --ignore-missing SHA256SUMS ) \
    || { echo "运行时制品校验失败" >&2; exit 1; }

  command -v zstd >/dev/null 2>&1 || { echo "需要安装 zstd（apt-get install -y zstd）" >&2; exit 1; }
  zstd -d -c "$TMP/$BUNDLE_NAME" | tar -x -C "$CACHE_DIR"
fi

echo "校验运行时文件哈希"
[ "$(sha256_of "$CACHE_DIR/$NODE_TGZ")" = "$EXPECT_NODE_SHA256" ] \
  || { echo "$NODE_TGZ 哈希不匹配" >&2; exit 1; }
[ "$(sha256_of "$CACHE_DIR/$MAGPIE_BIN")" = "$EXPECT_MAGPIE_SHA256" ] \
  || { echo "$MAGPIE_BIN 哈希不匹配" >&2; exit 1; }
echo "运行时制品就绪：$CACHE_DIR"
