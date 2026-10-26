#!/bin/sh
set -eu

fixture_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$fixture_dir/../../.." && pwd)
workspace_dir=$(dirname "$project_dir")
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$workspace_dir/artifacts/toolchains/android-sdk"}}
ndk_dir=${DROY_NDK_DIR:-"$sdk_dir/ndk/28.2.13676358"}
output_dir=${DROY_MEMORY_FIXTURE_OUTPUT_DIR:-"$workspace_dir/artifacts/process-memory-fixture"}
build_tools="$sdk_dir/build-tools/36.0.0"
android_jar="$sdk_dir/platforms/android-36/android.jar"
case "$(uname -s)" in
    Darwin) ndk_host=darwin-x86_64 ;;
    Linux) ndk_host=linux-x86_64 ;;
    *) printf 'Unsupported NDK build host.\n' >&2; exit 1 ;;
esac
if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; export PATH; fi
for executable in javac jar keytool; do command -v "$executable" >/dev/null; done
[ -f "$android_jar" ] || { printf 'Android SDK platform 36 is required.\n' >&2; exit 1; }
compiler="$ndk_dir/toolchains/llvm/prebuilt/$ndk_host/bin/aarch64-linux-android30-clang"
[ -x "$compiler" ] || { printf 'Android NDK 28.2.13676358 is required.\n' >&2; exit 1; }
mkdir -p "$output_dir/classes" "$output_dir/dex" "$output_dir/lib/arm64-v8a"
"$compiler" -std=c11 -D_GNU_SOURCE -shared -fPIC -O2 -Wall -Wextra -Werror \
    -Wl,-z,max-page-size=16384 "$fixture_dir/native/fixture.c" \
    -o "$output_dir/lib/arm64-v8a/libmemoryfixture.so"
javac --release 8 -classpath "$android_jar" -d "$output_dir/classes" \
    "$fixture_dir/src/io/droyagent/memoryfixture/FixtureMemory.java" \
    "$fixture_dir/src/io/droyagent/memoryfixture/FixtureProvider.java" \
    "$fixture_dir/src/io/droyagent/memoryfixture/MainActivity.java"
jar cf "$output_dir/classes.jar" -C "$output_dir/classes" .
"$build_tools/d8" --min-api 30 --lib "$android_jar" --output "$output_dir/dex" "$output_dir/classes.jar"
"$build_tools/aapt2" link --manifest "$fixture_dir/AndroidManifest.xml" -I "$android_jar" \
    --min-sdk-version 30 --target-sdk-version 36 --version-code 1 --version-name 1.0 \
    -o "$output_dir/fixture-unsigned.apk"
python3 - "$output_dir" <<'PY'
from pathlib import Path
import sys, zipfile
root=Path(sys.argv[1])
with zipfile.ZipFile(root/'fixture-unsigned.apk', 'a') as archive:
    archive.write(root/'dex/classes.dex', 'classes.dex')
    archive.write(root/'lib/arm64-v8a/libmemoryfixture.so', 'lib/arm64-v8a/libmemoryfixture.so')
PY
"$build_tools/zipalign" -f -P 16 4 "$output_dir/fixture-unsigned.apk" "$output_dir/fixture-aligned.apk"
keystore="$output_dir/fixture-debug.keystore"
if [ ! -f "$keystore" ]; then
    keytool -genkeypair -keystore "$keystore" -alias fixture -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass android -keypass android -dname 'CN=Droy Memory Test Fixture' >/dev/null 2>&1
fi
"$build_tools/apksigner" sign --ks "$keystore" --ks-key-alias fixture \
    --ks-pass pass:android --key-pass pass:android --out "$output_dir/process-memory-fixture.apk" \
    "$output_dir/fixture-aligned.apk"
"$build_tools/apksigner" verify "$output_dir/process-memory-fixture.apk"
printf '%s\n' "$output_dir/process-memory-fixture.apk"
