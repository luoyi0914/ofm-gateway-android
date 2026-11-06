#!/usr/bin/env bash
# 发版前的机械校验：把「人工记得检查」变成「不通过就不能发」。
#
# 用法：scripts/verify-release.sh <apk> [--expect-version <semver>]
#
# 每一项失败都直接非零退出。校验的是产物本身（解包后读 ELF/清单），
# 不是构建日志——构建成功不等于产物可用。
set -euo pipefail

APK="${1:?用法: verify-release.sh <apk> [--expect-version <semver>]}"
shift || true
EXPECT_VERSION=""
while [ $# -gt 0 ]; do
  case "$1" in
    --expect-version) EXPECT_VERSION="${2:?}"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

[ -f "$APK" ] || { echo "找不到 APK: $APK" >&2; exit 1; }

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/root/android-sdk}}"
BUILD_TOOLS="${BUILD_TOOLS:-$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)}"
AAPT2="$BUILD_TOOLS/aapt2"
APKSIGNER="$BUILD_TOOLS/apksigner"
ZIPALIGN="$BUILD_TOOLS/zipalign"
for tool in "$AAPT2" "$APKSIGNER" "$ZIPALIGN"; do
  [ -x "$tool" ] || { echo "缺少构建工具: $tool" >&2; exit 1; }
done

# 正式发布证书指纹：换签名必须同步改这里，否则这条断言就失去意义。
EXPECT_CERT_SHA256="42f845230670529f6c06a606118e14e312d3f96c710b060996526cec54e6a236"
EXPECT_ABI="arm64-v8a"
EXPECT_LIBS=(
  libnode.so libmagpie.so libofm_gateway.so
  libcrypto.so libssl.so libz.so
  libicuuc.so libicui18n.so libicudata.so libsqlite3.so
  libcares.so libffi.so libc++_shared.so libandroid-support.so
  # bun：Magpie 装 @magpie-community 插件要 fork/exec 一个 bun，而它自己下载到
  # 应用私有目录再执行会 EACCES（Android 10+ noexec）。只能随包放在这里。
  libbun.so
)
# 体积预算：libbun.so 压缩后约 33 MiB（86.8 MB → 33.5 MiB），
# 0.9.4 的基线是 60.1 MiB，加上它约 93.5 MiB。留一点余量到 100 MiB。
# bun 可执行文件本体的 sha256（与 .cache/bun-android-arm64 及 Magpie 上游
# internal/plugin/bun.go 的 bunSums 口径一致）。换 Bun 版本时同步更新。
EXPECT_BUN_SHA256="f2970336bfccf36e4e8a408b25c627122f210e941672f184198c31f4dbd377a0"
MAX_BYTES=$((100 * 1024 * 1024))

fail=0
ok()   { printf '  ✓ %s\n' "$*"; }
bad()  { printf '  ✗ %s\n' "$*"; fail=1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "== 清单与版本 =="
BADGING="$("$AAPT2" dump badging "$APK" 2>/dev/null)"
VERSION_CODE="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionCode='\([0-9]*\)'.*/\1/p")"
VERSION_NAME="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p")"
MIN_SDK="$(printf '%s\n' "$BADGING" | sed -n "s/^minSdkVersion:'\([0-9]*\)'/\1/p")"
TARGET_SDK="$(printf '%s\n' "$BADGING" | sed -n "s/^targetSdkVersion:'\([0-9]*\)'/\1/p")"
[ -n "$VERSION_CODE" ] && ok "versionCode=$VERSION_CODE versionName=$VERSION_NAME" || bad "读不到 versionCode"
[ -n "$MIN_SDK" ] && ok "minSdk=$MIN_SDK targetSdk=$TARGET_SDK" || bad "读不到 minSdk"
for sdk in "$MIN_SDK" "$TARGET_SDK"; do
  case "$sdk" in ''|*[!0-9]*) bad "SDK 级别不是纯数字（编译占位值会污染产物）: '$sdk'" ;; esac
done

if [ -n "$EXPECT_VERSION" ]; then
  [ "$VERSION_NAME" = "$EXPECT_VERSION" ] && ok "版本与预期一致: $EXPECT_VERSION" \
    || bad "版本不匹配: 产物 $VERSION_NAME ≠ 预期 $EXPECT_VERSION"
fi

ABIS="$(printf '%s\n' "$BADGING" | sed -n "s/^native-code: \(.*\)/\1/p" | tr -d "'")"
[ "$ABIS" = "$EXPECT_ABI" ] && ok "ABI 纯净: $ABIS" || bad "ABI 不符合预期: '$ABIS'"

echo "== 签名 =="
CERT="$( ("$APKSIGNER" verify --print-certs "$APK" 2>&1 || true) | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)"
if [ -z "$CERT" ]; then
  bad "apksigner 读不到证书（v1-only 包要用 keytool，或用 apksigner 重签）"
elif [ "$CERT" = "$EXPECT_CERT_SHA256" ]; then
  ok "证书指纹匹配发布密钥"
else
  bad "证书指纹不匹配: $CERT"
fi
VERIFY_OUT="$("$APKSIGNER" verify --verbose "$APK" 2>&1 || true)"
for scheme in v2 v3; do
  if printf '%s\n' "$VERIFY_OUT" | grep -q "Verified using $scheme scheme (APK Signature Scheme $scheme): true"; then
    ok "已使用 $scheme 签名"
  else
    bad "缺少 $scheme 签名（Android 9+ 校验更严，且不支持轮换密钥）"
  fi
done
if printf '%s\n' "$BADGING" | grep -q 'application-debuggable'; then
  bad "release 包被标记为 debuggable"
else
  ok "非 debuggable"
fi

echo "== 体积 =="
BYTES="$(stat -c%s "$APK")"
if [ "$BYTES" -le "$MAX_BYTES" ]; then
  ok "$((BYTES / 1024 / 1024)) MiB（预算 $((MAX_BYTES / 1024 / 1024)) MiB）"
else
  bad "超出体积预算: $((BYTES / 1024 / 1024)) MiB > $((MAX_BYTES / 1024 / 1024)) MiB"
fi

echo "== zip 对齐 =="
if "$ZIPALIGN" -c -P 16 4 "$APK" >/dev/null 2>&1; then
  ok "未压缩条目 16KB 对齐"
else
  bad "存在未按 16KB 对齐的条目（Android 15+ 安装会失败）"
fi

echo "== 原生库 =="
unzip -qq -o "$APK" "lib/$EXPECT_ABI/*" -d "$TMP"
for lib in "${EXPECT_LIBS[@]}"; do
  [ -f "$TMP/lib/$EXPECT_ABI/$lib" ] && ok "$lib 已打包" || bad "缺少 $lib"
done

# bun 是以 libbun.so 的名义随包的普通可执行文件。AGP 默认会对 jniLibs 跑
# stripReleaseDebugSymbols，把符号表裁掉（实测 86,800,440 → 86,739,592 B），
# 于是「我们校验过的二进制」和「用户装到的二进制」不是同一份。build.gradle.kts
# 里用 keepDebugSymbols 关掉了这个行为，这里再加一道字节级断言兜底。
if [ -f "$TMP/lib/$EXPECT_ABI/libbun.so" ]; then
  BUN_SHA=$(sha256sum "$TMP/lib/$EXPECT_ABI/libbun.so" | cut -d' ' -f1)
  if [ "$BUN_SHA" = "$EXPECT_BUN_SHA256" ]; then
    ok "libbun.so 与验收过的 Bun 逐字节一致"
  else
    bad "libbun.so 被改写过（$BUN_SHA，期望 $EXPECT_BUN_SHA256）——检查 keepDebugSymbols 是否失效"
  fi
fi

python3 - "$TMP/lib/$EXPECT_ABI" <<'PY'
import glob, os, struct, sys

lib_dir = sys.argv[1]
present = {os.path.basename(p) for p in glob.glob(os.path.join(lib_dir, "*.so"))}
problems = []

for path in sorted(glob.glob(os.path.join(lib_dir, "*.so"))):
    name = os.path.basename(path)
    with open(path, "rb") as fh:
        head = fh.read(0x40)
        if head[:4] != b"\x7fELF" or head[4] != 2 or head[5] != 1:
            problems.append(f"{name}: 不是 AArch64 ELF")
            continue
        e_phoff, = struct.unpack_from("<Q", head, 0x20)
        e_phentsize, = struct.unpack_from("<H", head, 0x36)
        e_phnum, = struct.unpack_from("<H", head, 0x38)
        e_shoff, = struct.unpack_from("<Q", head, 0x28)
        e_shentsize, = struct.unpack_from("<H", head, 0x3A)
        e_shnum, = struct.unpack_from("<H", head, 0x3C)
        fh.seek(e_phoff)
        ph = fh.read(e_phentsize * e_phnum)
        # Android 15+ 要求每个 PT_LOAD 的 p_align ≥ 16KB 且为 16KB 整数倍。
        for i in range(e_phnum):
            base = i * e_phentsize
            p_type, = struct.unpack_from("<I", ph, base)
            if p_type != 1:
                continue
            p_align, = struct.unpack_from("<Q", ph, base + 0x30)
            if p_align < 0x4000 or p_align % 0x4000 != 0:
                problems.append(f"{name}: PT_LOAD p_align={p_align:#x} < 16KB")
                break
        fh.seek(e_shoff)
        sh = fh.read(e_shentsize * e_shnum)
        sections = [struct.unpack_from("<IIQQQQIIQQ", sh, i * e_shentsize) for i in range(e_shnum)]
        dyn = [s for s in sections if s[1] == 6]
        if len(dyn) != 1:
            continue
        _, _, _, _, dyn_off, dyn_size, dyn_link, _, _, dyn_entsize = dyn[0]
        str_off, str_size = sections[dyn_link][4], sections[dyn_link][5]
        fh.seek(dyn_off)
        dyn_raw = fh.read(dyn_size)
        fh.seek(str_off)
        strtab = fh.read(str_size)
        for i in range(dyn_size // dyn_entsize):
            d_tag, d_val = struct.unpack_from("<qQ", dyn_raw, i * dyn_entsize)
            if d_tag != 1:  # DT_NEEDED
                continue
            end = strtab.find(b"\x00", d_val)
            needed = strtab[d_val:end].decode("utf-8", "replace") if end > d_val else ""
            if not needed:
                problems.append(f"{name}: DT_NEEDED 索引越界")
                continue
            # 带版本名的 soname 不会被 Gradle 打包，真机上会直接 link 失败。
            if needed not in present and not needed.startswith(("libc.so", "libm.so", "libdl.so", "liblog.so")):
                problems.append(f"{name}: DT_NEEDED {needed} 未随包提供")

for p in problems:
    print("  ✗ " + p)
if not problems:
    print("  ✓ 全部原生库为 16KB 对齐的 AArch64 ELF，且 DT_NEEDED 可在包内解析")
sys.exit(1 if problems else 0)
PY
[ $? -eq 0 ] || fail=1

echo "== assets =="
unzip -l "$APK" "assets/app/packages/standalone/*.mjs" | grep -c "standalone/.*\.mjs" >/dev/null 2>&1 \
  && ok "运行时 assets 已打包" || bad "assets 里没有 standalone 运行时"

echo
if [ "$fail" -ne 0 ]; then
  echo "校验未通过，禁止发布：$APK" >&2
  exit 1
fi
echo "全部校验通过：$APK"
