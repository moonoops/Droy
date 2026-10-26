#!/bin/sh
# Read-only capability checks use isolated synthetic files; no APK/settings changes.
set -eu
task_script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 - "$task_script_dir" "$@" <<'PY'
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys
import uuid

source_root = Path(sys.argv.pop(1))
parser = argparse.ArgumentParser(description='Probe Android ARM64 Landlock/seccomp in an installed debuggable Droy app UID. All inputs are synthetic; positive run-as results do not prove the production SELinux domain.')
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--ndk', type=Path, required=True)
parser.add_argument('--output-dir', type=Path, required=True, help='Must not exist; stores binary and JSON evidence')
args = parser.parse_args()
output = args.output_dir.resolve()
output.mkdir(mode=0o700)
source = source_root / 'fixtures/personal-memory/isolation-probe.c'
compilers = list(args.ndk.glob('toolchains/llvm/prebuilt/*/bin/aarch64-linux-android30-clang'))
if len(compilers) != 1:
    raise SystemExit('Expected exactly one ARM64 Android clang in the supplied NDK')
binary = output / 'isolation-probe'
subprocess.run([str(compilers[0]), '-Wall', '-Wextra', '-Werror', '-O2', '-fPIE', '-pie', str(source), '-o', str(binary)], check=True)

def shell(arguments, **kwargs):
    return subprocess.run([args.adb, '-s', args.serial, 'shell', shlex.join(arguments)], capture_output=True, timeout=30, **kwargs)

def checked(arguments, **kwargs):
    result = shell(arguments, **kwargs)
    if result.returncode:
        raise RuntimeError('Isolated probe setup failed: ' + str(result.stderr))
    return result

remote = 'cache/droy-isolation-probe-' + uuid.uuid4().hex
report = {
    'schemaVersion': 1,
    'syntheticDataOnly': True,
    'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
    'binarySha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
    'execution': {
        'rootUsed': False, 'appInstalled': False, 'settingsChanged': False,
        'productionSelinuxDomainVerified': False,
        'remoteDirectory': remote,
    },
}
created = False
try:
    report['execution']['identity'] = checked(['run-as', 'io.droyagent.app', 'id'], text=True).stdout.strip()
    report['execution']['model'] = checked(['getprop', 'ro.product.model'], text=True).stdout.strip()
    report['execution']['api'] = checked(['getprop', 'ro.build.version.sdk'], text=True).stdout.strip()
    checked(['run-as', 'io.droyagent.app', 'mkdir', '-m', '700', remote], text=True)
    created = True
    checked(['run-as', 'io.droyagent.app', 'sh', '-c', 'umask 077; set -C; cat > ' + shlex.quote(remote + '/probe')], input=binary.read_bytes())
    checked(['run-as', 'io.droyagent.app', 'chmod', '700', remote + '/probe'], text=True)
    result = shell(['run-as', 'io.droyagent.app', 'sh', '-c', 'cd ' + shlex.quote(remote) + ' && ./probe --files'], text=True)
    (output / 'stdout.jsonl').write_text(result.stdout)
    (output / 'stderr.txt').write_text(result.stderr)
    report['exitCode'] = result.returncode
    report['observations'] = [json.loads(line) for line in result.stdout.splitlines() if line]
    if result.returncode:
        raise RuntimeError('Probe execution failed; see captured output')
finally:
    if created:
        cleanup = shell(['run-as', 'io.droyagent.app', 'rm', '-r', remote], text=True)
        report['execution']['remoteDirectoryRemoved'] = cleanup.returncode == 0
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')

observed = {row['probe']: row for row in report['observations'] if row['probe'] != 'child_status'}
landlock = observed.get('landlock_abi', {})
network = observed.get('seccomp_network', {})
print(json.dumps({
    'report': str(output / 'report.json'),
    'landlockAbi': landlock.get('result'), 'landlockErrno': landlock.get('errno'),
    'seccompNetworkDenied': network.get('installed') == 0 and network.get('baseline_connect') == 0 and network.get('connect_errno') == 1 and network.get('socket_errno') == 1,
    'productionSandboxVerified': False,
}))
PY
