#!/usr/bin/env bash
# 从 .cache 的受控产物还原 APK 所需的原生运行时库到 jniLibs。
#
# 背景：Android Gradle 只打包文件名以 .so 结尾的库，而 Node 的 DT_NEEDED 引用带版本号的
# soname（libcrypto.so.3 / libssl.so.3 / libicu*.so.78 / libz.so.1 / libsqlite3.so.0）。
# 因此这里做两件事：
#   1) 用脚本精确改写各 ELF 的 DT_NEEDED 字符串为无版本名（原位覆盖，变短用 NUL 补齐，
#      不改变文件布局，因此不破坏 16 KB LOAD 对齐）；
#   2) 以无版本名（libcrypto.so 等）复制进 jniLibs/arm64-v8a。
#
# 依赖输入（仓库根 .cache，均可从基线 Release 或构建脚本再生成）：
#   .cache/node-runtime-arm64.tgz   （Node 运行时 + 依赖库）
#   .cache/magpie-android-arm64     （经 NDK 29 16KB 重编的 Magpie 引擎）
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"       # android-app/
REPO="$(cd "$HERE/.." && pwd)"                                 # repo root
CACHE="$REPO/.cache"
JNI="$HERE/app/src/main/jniLibs/arm64-v8a"
NODE_TGZ="$CACHE/node-runtime-arm64.tgz"
MAGPIE="$CACHE/magpie-android-arm64"

[ -f "$NODE_TGZ" ] || { echo "missing $NODE_TGZ" >&2; exit 1; }
[ -f "$MAGPIE" ]   || { echo "missing $MAGPIE" >&2; exit 1; }

# 只查存在性会让「残留的旧运行库」静默混进包里，打出看起来成功、真机一启动就崩的包。
# 因此输入必须按哈希固定：这两个值与 scripts/fetch-runtime-bundle.sh 的期望值同源。
EXPECT_NODE_SHA256=230397a17157304710e7d897463f685cb1ea866b507113d6e451578e4ed3e4bc
EXPECT_MAGPIE_SHA256=864e77fe5a0af08eed0bc8e183993423369d025b15d300d575332a0217d7f29f
if [ "${OFM_SKIP_RUNTIME_SHA:-0}" != "1" ]; then
  echo "$EXPECT_NODE_SHA256  $NODE_TGZ"   | sha256sum -c --status - || {
    echo "node 运行时哈希不符：$NODE_TGZ" >&2; exit 1; }
  echo "$EXPECT_MAGPIE_SHA256  $MAGPIE"   | sha256sum -c --status - || {
    echo "Magpie 哈希不符：$MAGPIE（需 NDK 29 的 16KB 对齐构建）" >&2; exit 1; }
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
tar -xzf "$NODE_TGZ" -C "$TMP"

python3 - "$TMP/usr/bin/node" "$TMP"/usr/lib/* <<'PY'
import struct, sys, os
MAP = {
    'libcrypto.so.3': 'libcrypto.so', 'libssl.so.3': 'libssl.so',
    'libicuuc.so.78': 'libicuuc.so', 'libicui18n.so.78': 'libicui18n.so',
    'libicudata.so.78': 'libicudata.so', 'libz.so.1': 'libz.so',
    'libsqlite3.so.0': 'libsqlite3.so',
}
def patch(path):
    data = bytearray(open(path, 'rb').read())
    if data[:4] != b'\x7fELF' or data[4] != 2 or data[5] != 1:
        return
    e_shoff, = struct.unpack_from('<Q', data, 0x28)
    e_shentsize, = struct.unpack_from('<H', data, 0x3a)
    e_shnum, = struct.unpack_from('<H', data, 0x3c)
    secs = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        secs.append(struct.unpack_from('<IIQQQQIIQQ', data, off))
    dyn = [s for s in secs if s[1] == 6]  # SHT_DYNAMIC
    if len(dyn) != 1:
        return
    _, _, _, _, dyn_off, dyn_size, dyn_link, _, _, dyn_entsize = dyn[0]
    str_off = secs[dyn_link][4]
    for i in range(dyn_size // dyn_entsize):
        base = dyn_off + i * dyn_entsize
        d_tag, d_val = struct.unpack_from('<qQ', data, base)
        # DT_NEEDED(1): 把引用的带版本 soname 改为无版本名；
        # DT_SONAME(14): 把库自身的 soname 改为无版本名（否则 bionic 用 soname 匹配
        #   verneed 的 vn_file 会对不上，报 cannot find "libcrypto.so" from verneed[0]）。
        if d_tag not in (1, 14):
            continue
        s_off = str_off + d_val
        end = data.index(b'\0', s_off)
        cur = bytes(data[s_off:end]).decode()
        if cur in MAP:
            new = MAP[cur].encode()
            if len(new) > len(cur):
                raise SystemExit(f'{path}: replacement longer than original ({cur})')
            data[s_off:s_off + len(cur)] = new + b'\0' * (len(cur) - len(new))
            kind = 'NEEDED' if d_tag == 1 else 'SONAME'
            print(f'patched {os.path.basename(path)} {kind}: {cur} -> {MAP[cur]}')
    open(path, 'wb').write(data)

for p in sys.argv[1:]:
    if os.path.isfile(p):
        patch(p)
PY

mkdir -p "$JNI"
cp -f "$TMP/usr/bin/node"            "$JNI/libnode.so"
cp -f "$TMP/usr/lib/libcrypto.so.3"  "$JNI/libcrypto.so"
cp -f "$TMP/usr/lib/libssl.so.3"     "$JNI/libssl.so"
cp -f "$TMP/usr/lib/libicuuc.so.78"  "$JNI/libicuuc.so"
cp -f "$TMP/usr/lib/libicui18n.so.78" "$JNI/libicui18n.so"
cp -f "$TMP/usr/lib/libicudata.so.78" "$JNI/libicudata.so"
cp -f "$TMP/usr/lib/libz.so.1"       "$JNI/libz.so"
cp -f "$TMP/usr/lib/libsqlite3.so"   "$JNI/libsqlite3.so"
cp -f "$TMP/usr/lib/libcares.so"     "$JNI/libcares.so"
cp -f "$TMP/usr/lib/libc++_shared.so" "$JNI/libc++_shared.so"
cp -f "$TMP/usr/lib/libffi.so"       "$JNI/libffi.so"
cp -f "$TMP/usr/lib/libandroid-support.so" "$JNI/libandroid-support.so"
cp -f "$MAGPIE"                      "$JNI/libmagpie.so"
chmod 644 "$JNI"/*.so
echo "runtime libs ready in $JNI"
