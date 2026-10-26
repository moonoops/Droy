#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
workspace_dir=$(dirname "$project_dir")
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$workspace_dir/artifacts/toolchains/android-sdk"}}
ndk_dir=${DROY_NDK_DIR:-"$sdk_dir/ndk/28.2.13676358"}
headers_dir=${DROY_NODE_HEADERS:-"$workspace_dir/artifacts/runtime-node/headers/node"}
output_dir=${DROY_NATIVE_OUTPUT_DIR:-"$project_dir/runtime/generated/jniLibs/arm64-v8a"}
if [ ! -f "$ndk_dir/source.properties" ] || ! grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*28\.2\.13676358[[:space:]]*$' "$ndk_dir/source.properties"; then
  printf 'Expected Android NDK 28.2.13676358; set DROY_NDK_DIR.\n' >&2
  exit 1
fi
case "$(uname -s)" in
  Darwin) host_tag=darwin-x86_64 ;;
  Linux)
    case "$(uname -m)" in
      x86_64) host_tag=linux-x86_64 ;;
      *) printf 'The pinned Android NDK supports x86_64 Linux hosts.\n' >&2; exit 1 ;;
    esac ;;
  *) printf 'Native build requires macOS or x86_64 Linux.\n' >&2; exit 1 ;;
esac
compiler="$ndk_dir/toolchains/llvm/prebuilt/$host_tag/bin/aarch64-linux-android30-clang"
cpp_compiler="$ndk_dir/toolchains/llvm/prebuilt/$host_tag/bin/aarch64-linux-android30-clang++"
[ -x "$compiler" ] || { printf 'NDK compiler is missing: %s\n' "$compiler" >&2; exit 1; }
[ -f "$headers_dir/node_api.h" ] || { printf 'Prepare the Android Node headers first.\n' >&2; exit 1; }
node --input-type=module - "$project_dir" <<'JS'
import {readFileSync} from 'node:fs';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
const root = process.argv[2];
const lock = JSON.parse(readFileSync(join(root, 'runtime/native/node-pty.lock.json'), 'utf8'));
const modules = join(root, 'runtime/dsh/node_modules');
const pty = JSON.parse(readFileSync(join(modules, 'node-pty/package.json'), 'utf8'));
const api = JSON.parse(readFileSync(join(modules, 'node-addon-api/package.json'), 'utf8'));
const hash = createHash('sha256').update(readFileSync(join(modules, 'node-pty', lock.source))).digest('hex');
if (pty.version !== lock.version || api.version !== lock.nodeAddonApiVersion || hash !== lock.sha256) {
  throw new Error('node-pty source differs from reviewed Android build inputs; update runtime/native/node-pty.lock.json after validation');
}
JS
mkdir -p "$output_dir"
# Keep compiler-generated filenames independent of the developer checkout.
set -- "-ffile-prefix-map=$project_dir=." \
  "-ffile-prefix-map=$headers_dir=node-headers" \
  "-ffile-prefix-map=$ndk_dir=android-ndk"
"$compiler" "$@" \
  -shared -fPIC -O2 -Wall -Wextra -Werror -DNODE_GYP_MODULE_NAME=droy_system \
  -I "$headers_dir" -Wl,-z,max-page-size=16384 \
  "$project_dir/runtime/native/system.c" -o "$output_dir/libdroy_system.so"
printf 'Built %s\n' "$output_dir/libdroy_system.so"

# Android's libc provides forkpty/openpty; no libutil or Termux execution wrapper
# is needed. Keep libc++ static so this add-on does not add a second shared C++ ABI.
"$cpp_compiler" "$@" \
  -shared -fPIC -std=c++17 -O2 -Wall -Wextra -fexceptions -fvisibility=hidden \
  -fstack-protector-strong -DNAPI_VERSION=8 -DNAPI_CPP_EXCEPTIONS -DNODE_GYP_MODULE_NAME=pty \
  -static-libstdc++ -I "$headers_dir" -I "$project_dir/runtime/dsh/node_modules/node-addon-api" \
  -Wl,-z,max-page-size=16384 \
  "$project_dir/runtime/dsh/node_modules/node-pty/src/unix/pty.cc" -o "$output_dir/libdroy_pty.so"
printf 'Built %s\n' "$output_dir/libdroy_pty.so"

# An executable with a .so name is installed in the APK's executable native directory.
"$cpp_compiler" "$@" \
  -std=c++17 -O2 -Wall -Wextra -Werror -fPIE -pie -fstack-protector-strong \
  -static-libstdc++ -Wl,-z,max-page-size=16384 \
  "$project_dir/android-use/src/main/cpp/process_memory.cpp" -o "$output_dir/libdroy_memory.so"
printf 'Built %s\n' "$output_dir/libdroy_memory.so"

DROY_NDK_DIR="$ndk_dir" DROY_NATIVE_OUTPUT_DIR="$output_dir" sh "$project_dir/tools/build_execution.sh"

