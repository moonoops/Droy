#!/bin/sh
set -eu
execution_project=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
execution_workspace=$(dirname "$execution_project")
execution_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$execution_workspace/artifacts/toolchains/android-sdk"}}
execution_ndk=${DROY_NDK_DIR:-"$execution_sdk/ndk/28.2.13676358"}
execution_output=${DROY_NATIVE_OUTPUT_DIR:-"$execution_project/runtime/generated/jniLibs/arm64-v8a"}
case "$(uname -s)" in
  Darwin) execution_host=darwin-x86_64 ;;
  Linux) execution_host=linux-x86_64 ;;
  *) printf 'Execution build requires macOS or Linux.\n' >&2; exit 1 ;;
esac
execution_compiler="$execution_ndk/toolchains/llvm/prebuilt/$execution_host/bin/aarch64-linux-android30-clang++"
[ -x "$execution_compiler" ] || { printf 'Android NDK compiler is unavailable.\n' >&2; exit 1; }
mkdir -p "$execution_output"
"$execution_compiler" -std=c++17 -O2 -Wall -Wextra -Werror -fPIE -pie \
  -fstack-protector-strong -static-libstdc++ -Wl,-z,relro,-z,now,-z,max-page-size=16384 \
  "$execution_project/runtime/execution/launcher.cpp" -o "$execution_output/libdroy_exec.so"
printf 'Built %s\n' "$execution_output/libdroy_exec.so"
