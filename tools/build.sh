#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
workspace_dir=$(dirname "$project_dir")
skip_dependencies=false
skip_tests=false
app_name=''
app_icon=''

usage() {
    cat <<'EOF'
Usage: tools/build.sh [--skip-deps] [--skip-tests] [--app-name NAME] [--app-icon PNG]

Prepare locked Android Node + DSH, compile the N-API addon, run host tests,
and build the debug APK and device-test APK. This command never installs an APK.

  --skip-deps   Reuse runtime/dsh/node_modules; intended for local iteration.
               The DSH version and patched source hashes are still verified.
  --skip-tests  Skip host/unit tests, but still build the APKs.
  --app-name    Set the installed app and system share-target name (default: Droy).
  --app-icon    Use a custom PNG launcher icon.

Environment:
  JAVA_HOME              JDK 17 directory (or Java 17 on PATH)
  ANDROID_HOME           Android SDK directory (ANDROID_SDK_ROOT also accepted)
  DROY_NDK_DIR           Android NDK 28.2.13676358 directory
  DROY_CMAKE             CMake 3.22+ executable for the Qwen3-TTS runtime
  DROY_NODE_RUNTIME_DIR  Generated Node payload; default ../artifacts/runtime-node
  DROY_PATCHELF          patchelf 0.19.2 executable (otherwise found on PATH)
  GRADLE_USER_HOME       Optional Gradle cache directory
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --skip-deps) skip_dependencies=true ;;
        --skip-tests) skip_tests=true ;;
        --app-name|--app-icon)
            build_option=$1
            [ "$#" -ge 2 ] && [ -n "$2" ] || { printf '%s requires a value\n' "$build_option" >&2; exit 2; }
            case "$2" in --*) printf '%s requires a value\n' "$build_option" >&2; exit 2 ;; esac
            if [ "$build_option" = --app-name ]; then app_name=$2; else app_icon=$2; fi
            shift ;;
        -h|--help) usage; exit 0 ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
    shift
done

fail() { printf 'Build failed: %s\n' "$*" >&2; exit 1; }
for command_name in python3 node npm; do
    command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required on PATH"
done
python3 -c 'import sys; assert sys.version_info >= (3, 9), "Python 3.9+ required"'
node -e 'if (![22, 24].includes(Number(process.versions.node.split(".")[0]))) { console.error("Host Node.js 22 or 24 required"); process.exit(1); }'

if [ -n "${JAVA_HOME:-}" ]; then
    [ -x "$JAVA_HOME/bin/java" ] || fail 'JAVA_HOME does not contain bin/java'
    PATH="$JAVA_HOME/bin:$PATH"
    export PATH
fi
java_version=$(java -version 2>&1) || fail 'JDK 17 is required; set JAVA_HOME'
case "$java_version" in
    *'version "17.'*) ;;
    *) fail 'Use JDK 17 for the pinned Gradle/Android build' ;;
esac

sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk_dir" ]; then
    for candidate in "$workspace_dir/artifacts/toolchains/android-sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [ -d "$candidate/platforms/android-37.0" ]; then sdk_dir=$candidate; break; fi
    done
fi
[ -n "$sdk_dir" ] && [ -d "$sdk_dir/platforms/android-37.0" ] || fail 'Set ANDROID_HOME to an SDK with platforms;android-37.0 installed'
sdk_dir=$(CDPATH= cd -- "$sdk_dir" && pwd)
ANDROID_HOME=$sdk_dir
ANDROID_SDK_ROOT=$sdk_dir
export ANDROID_HOME ANDROID_SDK_ROOT
ndk_dir=${DROY_NDK_DIR:-"$sdk_dir/ndk/28.2.13676358"}
[ -f "$ndk_dir/source.properties" ] || fail 'Install ndk;28.2.13676358 or set DROY_NDK_DIR'
ndk_dir=$(CDPATH= cd -- "$ndk_dir" && pwd)
runtime_dir=${DROY_NODE_RUNTIME_DIR:-"$workspace_dir/artifacts/runtime-node"}
runtime_dir=$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).expanduser().resolve())' "$runtime_dir")

cd "$project_dir"
if [ "$skip_tests" = false ]; then
    PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/tests -p 'test_*.py'
fi
printf 'Preparing locked Android Node payload...\n'
if [ -n "${DROY_PATCHELF:-}" ]; then
    python3 tools/prepare_android_node.py --output "$runtime_dir" --patchelf "$DROY_PATCHELF"
else
    python3 tools/prepare_android_node.py --output "$runtime_dir"
fi
if [ "$skip_dependencies" = false ]; then
    npm ci --ignore-scripts --no-audit --no-fund --prefix "$project_dir/runtime/dsh"
else
    [ -f runtime/dsh/node_modules/@deepseek-ai/dsh/package.json ] || fail '--skip-deps requires an existing npm installation'
fi
node tools/prepare_dsh.mjs
python3 tools/prepare_audio.py
python3 tools/prepare_voice_models.py
if [ -n "${DROY_CMAKE:-}" ]; then
    python3 tools/prepare_qwen_tts.py --ndk "$ndk_dir" --cmake "$DROY_CMAKE"
    python3 tools/prepare_embedding.py --ndk "$ndk_dir" --cmake "$DROY_CMAKE"
else
    python3 tools/prepare_qwen_tts.py --ndk "$ndk_dir"
    python3 tools/prepare_embedding.py --ndk "$ndk_dir"
fi
DROY_NDK_DIR="$ndk_dir" DROY_NODE_HEADERS="$runtime_dir/headers/node" tools/build_native.sh

set -- --no-daemon --console=plain "-PdroyNodeRuntimeDir=$runtime_dir"
[ -z "$app_name" ] || set -- "$@" "-PdroyAppName=$app_name"
[ -z "$app_icon" ] || set -- "$@" "-PdroyAppIcon=$app_icon"
if [ "$skip_tests" = false ]; then
    node --expose-internals --test runtime/mobile/tests/*.test.mjs
    node --test runtime/media-input/tests/*.test.mjs
    node --test runtime/voice/tests/*.test.mjs
    node --test runtime/execution/tests/*.test.mjs
    node --test runtime/memory/tests/*.test.mjs
    node --test runtime/privacy/tests/*.test.mjs
    node --expose-internals runtime/mobile/tests/profile-smoke.mjs
    node --expose-internals runtime/mobile/tests/profile-memory-active-smoke.mjs
    ./gradlew "$@" testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
else
    ./gradlew "$@" :app:assembleDebug :app:assembleDebugAndroidTest
fi
printf '\nAPK: %s\nDevice-test APK: %s\n' \
    "$project_dir/app/build/outputs/apk/debug/app-debug.apk" \
    "$project_dir/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
