#!/usr/bin/env bash
# 拉取运行时制品（Node + Magpie + Bun），校验哈希后落到仓库 .cache/ 供 prepare-runtime-libs.sh 使用。
#
# 为什么不在仓库里放这 98MB 二进制：clean clone 要能一键构建，但二进制不该进 git。
# 因此制品发布到固定 Release（runtime-1），CI 按下面的 URL + SHA256SUMS 拉取。
#
# Bun 单独从上游官方地址取（不走 runtime-bundle）：它是 UnJS/oven-sh 的官方产物，
# 有公开的 sha256 可核对，没必要为了一个文件重发 100MB 的 runtime-bundle。
# 为什么要 Bun：Magpie 装 @magpie-community 插件时要 `bun add`。Android 10+ 的
# 应用私有目录不允许 exec，Magpie 自己下载到 files/…/cache/magpie/bun/1.4.2/
# magpie-bun 再 fork/exec 会 EACCES；只有 nativeLibraryDir 这种只读目录才可执行，
# 所以 bun 必须以 libbun.so 的形式随包分发，再由引擎的 MAGPIE_BUN 指过去。
#
# 用法：
#   scripts/fetch-runtime-bundle.sh                  # 从 OFM_RUNTIME_URL 拉取并校验
#   OFM_RUNTIME_URL=file:///x.tar.zst ...            # 允许 file:// / http(s)://
#   OFM_RUNTIME_FROM_CACHE=1 scripts/...             # 本地已有 .cache 产物时跳过下载
#   OFM_BUN_URL=... scripts/...                      # 覆盖 Bun 下载地址（镜像/离线）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # android-app/
# 缓存目录必须与 prepare-runtime-libs.sh 读的位置一致（仓库根 .cache），
# 否则脚本各自落一份，构建时又报「missing node-runtime-arm64.tgz」。
REPO="$(cd "$ROOT/.." && pwd)"
CACHE_DIR="$REPO/.cache"
mkdir -p "$CACHE_DIR"

NODE_TGZ="node-runtime-arm64.tgz"
MAGPIE_BIN="magpie-android-arm64"
BUN_BIN="bun-android-arm64"

# 期望哈希：与仓库里已验收的制品一致。上游换了运行时就必须同步改这两行，
# 否则 CI 会在校验阶段失败——这正是我们要的（防止静默换入未验证的 Node）。
EXPECT_NODE_SHA256="230397a17157304710e7d897463f685cb1ea866b507113d6e451578e4ed3e4bc"
# Magpie：NDK 29 重编、16KB LOAD 对齐版（旧 4KB 版 fa5efbb9… 不要再用）。
EXPECT_MAGPIE_SHA256="864e77fe5a0af08eed0bc8e183993423369d025b15d300d575332a0217d7f29f"
# Bun：解包后的 bun 可执行文件本体（不是 zip）。来源 zip 的 sha256 与 Magpie 上游
# internal/plugin/bun.go 的 bunSums["bun-linux-aarch64-android.zip"] 一致：
#   a1c7e2983f1bb65146beb256a4d72449f23042412bc2cf278aa6397ba27e0274
# 换 Bun 版本（1.4.2）时必须同步改这里，否则构建会在校验阶段停下。
EXPECT_BUN_SHA256="f2970336bfccf36e4e8a408b25c627122f210e941672f184198c31f4dbd377a0"
BUN_VERSION="1.4.2"
BUN_ZIP_NAME="bun-linux-aarch64-android.zip"
BUN_ZIP_SHA256="a1c7e2983f1bb65146beb256a4d72449f23042412bc2cf278aa6397ba27e0274"
DEFAULT_BUN_URL="https://github.com/oven-sh/bun/releases/download/bun-v$BUN_VERSION/$BUN_ZIP_NAME"
BUN_URL="${OFM_BUN_URL:-$DEFAULT_BUN_URL}"

DEFAULT_URL="https://github.com/luoyi0914/ofm-gateway-android/releases/download/runtime-1/runtime-bundle-v1.tar.zst"
URL="${OFM_RUNTIME_URL:-$DEFAULT_URL}"

sha256_of() { sha256sum "$1" | cut -d' ' -f1; }

if [ "${OFM_RUNTIME_FROM_CACHE:-0}" = "1" ]; then
  echo "使用本地 .cache 产物（OFM_RUNTIME_FROM_CACHE=1）"
  for f in "$NODE_TGZ" "$MAGPIE_BIN" "$BUN_BIN"; do
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

  # Bun：官方 zip 里就一个 bun 可执行文件，解出来按 BUN_BIN 的名字落盘。
  echo "拉取 Bun $BUN_VERSION：$BUN_URL"
  curl -fsSL --retry 3 -o "$TMP/$BUN_ZIP_NAME" "$BUN_URL" \
    || { echo "拉不到 Bun：$BUN_URL" >&2; exit 1; }
  [ "$(sha256_of "$TMP/$BUN_ZIP_NAME")" = "$BUN_ZIP_SHA256" ] \
    || { echo "Bun zip 哈希不匹配（上游换版本了？同步改 BUN_VERSION/BUN_ZIP_SHA256/EXPECT_BUN_SHA256）" >&2; exit 1; }
  command -v unzip >/dev/null 2>&1 || { echo "需要安装 unzip" >&2; exit 1; }
  unzip -qq -o "$TMP/$BUN_ZIP_NAME" -d "$TMP/bun" \
    || { echo "Bun zip 解压失败" >&2; exit 1; }
  mkdir -p "$CACHE_DIR"
  cp -f "$TMP/bun/bun-linux-aarch64-android/bun" "$CACHE_DIR/$BUN_BIN"
  chmod 0755 "$CACHE_DIR/$BUN_BIN"
fi

echo "校验运行时文件哈希"
[ "$(sha256_of "$CACHE_DIR/$NODE_TGZ")" = "$EXPECT_NODE_SHA256" ] \
  || { echo "$NODE_TGZ 哈希不匹配" >&2; exit 1; }
[ "$(sha256_of "$CACHE_DIR/$MAGPIE_BIN")" = "$EXPECT_MAGPIE_SHA256" ] \
  || { echo "$MAGPIE_BIN 哈希不匹配" >&2; exit 1; }
[ "$(sha256_of "$CACHE_DIR/$BUN_BIN")" = "$EXPECT_BUN_SHA256" ] \
  || { echo "$BUN_BIN 哈希不匹配" >&2; exit 1; }
echo "运行时制品就绪：$CACHE_DIR"
