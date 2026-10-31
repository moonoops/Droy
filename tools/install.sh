#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
apk_path="$project_dir/app/build/outputs/apk/debug/app-debug.apk"
device_serial=${ANDROID_SERIAL:-}
launch=false
grant_permissions=false
package_name=io.droyagent.app.dev

usage() {
    cat <<'EOF'
Usage: tools/install.sh [--serial DEVICE] [--apk FILE] [--package PACKAGE] [--launch] [--grant-permissions]

Install the Droy development app with adb install -r, preserving existing app data.
The APK package must match --package before installation; the official app is
only selected explicitly with --package io.droyagent.app.
When more than one device is attached, --serial or ANDROID_SERIAL is required.
An offline/unauthorized device is not silently ignored when choosing a target.
This script never uninstalls an app or clears data. Each installation grants and
verifies the permissions needed for accessibility recovery while preserving
the existing accessibility switch and app choices. It does not enable the
service or open the app unless requested. --grant-permissions explicitly enables
accessibility and verifies its connection, preserving other services; root-manager
and file-picker approvals remain once-only choices on the phone when used.

  --serial DEVICE  Explicit ADB target, including a wireless serial if appropriate
  --apk FILE       APK to install (default: app/build/outputs/apk/debug/app-debug.apk)
  --package PACKAGE  Target io.droyagent.app.dev (default) or io.droyagent.app
  --launch         Open DroyAgent after installation; previously authorized accessibility can recover
  --grant-permissions  Open DroyAgent, enable and verify all ADB-configurable permissions
                       (preserves other accessibility services)

Environment: DROY_ADB (adb executable), ANDROID_HOME/ANDROID_SDK_ROOT, ANDROID_SERIAL,
DROY_PYTHON (Python 3.8+ executable; required for every installation).
EOF
}

fail() { printf 'Install failed: %s\n' "$*" >&2; exit 1; }
while [ "$#" -gt 0 ]; do
    case "$1" in
        --serial|--apk|--package)
            option=$1
            [ "$#" -ge 2 ] && [ -n "$2" ] || fail "$option requires a value"
            case "$2" in --*) fail "$option requires a value before the next option" ;; esac
            case "$option" in
                --serial) device_serial=$2 ;;
                --apk) apk_path=$2 ;;
                --package) package_name=$2 ;;
            esac
            shift 2 ;;
        --launch) launch=true; shift ;;
        --grant-permissions) grant_permissions=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done
case "$package_name" in
    io.droyagent.app.dev|io.droyagent.app) ;;
    *) fail "Unsupported Droy package: $package_name" ;;
esac
[ -f "$apk_path" ] || fail "APK not found: $apk_path; run tools/build.sh first"
apk_path=$(CDPATH= cd -- "$(dirname -- "$apk_path")" && printf '%s/%s' "$(pwd)" "$(basename -- "$apk_path")")

adb_command=${DROY_ADB:-}
if [ -z "$adb_command" ]; then
    sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
    if [ -n "$sdk_dir" ] && [ -x "$sdk_dir/platform-tools/adb" ]; then
        adb_command="$sdk_dir/platform-tools/adb"
    else
        adb_command=$(command -v adb) || fail 'adb is required; set DROY_ADB or install Android platform-tools'
    fi
fi
command -v "$adb_command" >/dev/null 2>&1 || fail 'DROY_ADB does not identify an executable'

if [ -z "$device_serial" ]; then
    devices=$("$adb_command" devices) || fail 'Unable to list ADB devices'
    candidates=$(printf '%s\n' "$devices" | awk 'NR > 1 && NF >= 2 {print $1}')
    count=$(printf '%s\n' "$candidates" | awk 'NF {n++} END {print n+0}')
    case "$count" in
        0) fail 'No ADB device found; connect and authorize a phone' ;;
        1) device_serial=$candidates ;;
        *) fail 'Multiple ADB devices found; select one with --serial DEVICE' ;;
    esac
fi
state=$("$adb_command" -s "$device_serial" get-state) || fail 'Selected device is unavailable or unauthorized'
[ "$state" = device ] || fail 'Selected device is not ready'
api_level=$("$adb_command" -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')
case "$api_level" in ''|*[!0-9]*) fail 'Cannot read Android API level' ;; esac
[ "$api_level" -ge 30 ] || fail 'DroyAgent requires Android 11 / API 30 or newer'
abis=$("$adb_command" -s "$device_serial" shell getprop ro.product.cpu.abilist | tr -d '\r')
case ",$abis," in *,arm64-v8a,*) ;; *) fail 'This APK requires an ARM64 Android device' ;; esac

python_command=${DROY_PYTHON:-}
if [ -z "$python_command" ]; then
    python_command=$(command -v python3 || command -v python) || fail 'Python 3.8 or newer is required to configure accessibility recovery; install it or set DROY_PYTHON'
fi
command -v "$python_command" >/dev/null 2>&1 || fail 'Python 3.8 or newer is required; DROY_PYTHON does not identify an executable'
"$python_command" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' || \
    fail 'Python 3.8 or newer is required to configure accessibility recovery; install it or set DROY_PYTHON'

"$python_command" -c '
import sys
sys.path.insert(0, sys.argv[1])
from setup import apk_info, SetupError
try:
    apk_info(sys.argv[2], sys.argv[3])
except SetupError as error:
    print(str(error), file=sys.stderr)
    sys.exit(1)
' \
    "$project_dir/tools" "$apk_path" "$package_name" || \
    fail "APK validation failed for $package_name. Nothing was installed; select the matching APK and --package."

printf 'Installing on %s (Android API %s), preserving app data...\n' "$device_serial" "$api_level"
if ! "$adb_command" -s "$device_serial" install -r "$apk_path"; then
    fail 'ADB rejected installation. No data was cleared. For signature mismatch, rebuild with the original signing key; this script will not uninstall the existing app.'
fi
if [ "$grant_permissions" = true ]; then
    if ! "$python_command" "$project_dir/tools/enable_permissions.py" --adb "$adb_command" --serial "$device_serial" --package "$package_name"; then
        fail "APK installed, but one or more permissions were not verified. Verified changes are kept; rerun tools/enable_permissions.py --serial DEVICE --package $package_name after resolving the reported issue (use the same ADB target)."
    fi
else
    if ! "$python_command" "$project_dir/tools/enable_permissions.py" --adb "$adb_command" --serial "$device_serial" --package "$package_name" --preserve-accessibility; then
        fail "APK installed, but accessibility recovery permissions were not verified. Verified changes are kept; rerun tools/enable_permissions.py --preserve-accessibility --serial DEVICE --package $package_name after resolving the reported issue (use the same ADB target)."
    fi
fi
if [ "$launch" = true ]; then
    if launch_output=$("$adb_command" -s "$device_serial" shell am start -W -n "$package_name/io.droyagent.app.NativeAppActivity" 2>&1); then
        printf '%s\n' "$launch_output"
    else
        printf '%s\n' "$launch_output" >&2
        fail 'APK installed, but the launch command failed'
    fi
    # Android can report an activity-start error while am still exits with 0.
    printf '%s\n' "$launch_output" | tr -d '\r' | grep -q '^Status: ok$' || \
        fail 'APK installed, but Android did not confirm launch; open DroyAgent on the phone'
fi
printf 'Installed. Configure the model in DroyAgent; capability switches can be turned off there.\n'
