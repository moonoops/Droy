#!/usr/bin/env python3
"""Opt-in synthetic native wire tests; complements, not replaces, App-UID qualification.

The standard/unknown-uid cases require the dedicated-uid-probe test binary at
/data/local/tmp/droy-dedicated-uid-probe. The preclone case does not. No
activation, collectors, model or user project is used.
"""
import argparse
import hashlib
import json
import queue
import re
import shlex
import struct
import subprocess
import threading
import time
import uuid


def u32(value):
    return struct.pack('>I', value)


def string(value):
    value = value.encode()
    return u32(len(value)) + value


def strings(values):
    return u32(len(values)) + b''.join(map(string, values))


class Device:
    def __init__(self, adb, serial):
        self.adb = [adb, '-s', serial]
        package = self.call('shell', 'dumpsys', 'package', 'io.droyagent.app')
        self.native = re.search(r'legacyNativeLibraryDir=(\S+)', package).group(1) + '/arm64'
        self.uid = int(re.search(r'appId=(\d+)', package).group(1))
        self.files = '/data/user/{}/io.droyagent.app/files'.format(self.uid // 100000)
        self.helper = self.native + '/libdroy_exec.so'
        self.node = self.native + '/libdroy_node.so'
        self.workspaces = []

    def call(self, *args):
        return subprocess.check_output(self.adb + list(args), text=True, timeout=20)

    def root(self, command):
        return self.call('shell', 'su -c ' + shlex.quote(command))

    def app(self, *args):
        return self.call('shell', ' '.join(shlex.quote(x) for x in ['run-as', 'io.droyagent.app'] + list(args)))

    def workspace(self):
        identity = hashlib.sha256(uuid.uuid4().bytes).hexdigest()
        path = self.files + '/droy-execution/workspaces/' + identity
        self.app('mkdir', path)
        self.app('mkdir', path + '/startup', path + '/default')
        self.workspaces.append(path)
        return identity

    def lease_metadata(self):
        first = self.uid // 100000 * 100000 + 80000
        leases = self.files + '/droy-execution/leases'
        command = 'for u in ' + ' '.join(map(str, range(first, first + 8))) + '; do '
        command += 'f=' + shlex.quote(leases) + '/"$u"; '
        command += 'if test -e "$f"; then test -f "$f" && test ! -L "$f" || exit 74; n=$(stat -c %s "$f") || exit 74; else n=0; fi; '
        command += 'printf "lease %s %s\\n" "$u" "$n"; done; '
        command += 'for f in /proc/[0-9]*/status; do while read -r key uid rest; do if test "$key" = Uid:; then '
        command += 'if test "$uid" -ge ' + str(first) + ' && test "$uid" -lt ' + str(first + 8) + '; then '
        command += 'p=$(basename "$(dirname "$f")"); printf "live %s %s\\n" "$uid" "$p"; fi; break; fi; done < "$f" 2>/dev/null; done'
        rows = [line.split() for line in self.root(command).splitlines()]
        assert all(len(row) == 3 and row[0] in ('lease', 'live') and row[1].isdigit() and row[2].isdigit() for row in rows)
        sizes = {int(uid): int(size) for kind, uid, size in rows if kind == 'lease'}
        assert set(sizes) == set(range(first, first + 8)) and len([row for row in rows if row[0] == 'lease']) == 8
        return {'leaseBytes': sizes,
                'live': [{'uid': int(uid), 'pid': int(pid)} for kind, uid, pid in rows if kind == 'live']}


class Job:
    def __init__(self, device, workspace, code, pty=False):
        self.device = device
        self.frames = queue.Queue()
        self.output = b''
        self.ready = None
        self.exit = None
        self.errors = []
        self.eof = False
        self.process = subprocess.Popen(device.adb + ['shell', '-T', 'su -c ' + shlex.quote('exec ' + shlex.quote(device.helper))],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        threading.Thread(target=self.reader, daemon=True).start()
        body = b''.join(map(u32, [1, device.uid, 30000, int(pty), 80, 24, 0]))
        body += b''.join(map(string, [device.files, workspace, device.files + '/workspace', device.node]))
        body += strings([device.node, '-e', code]) + strings(['PATH=/usr/bin:/system/bin'])
        self.send(1, body)

    def reader(self):
        def exact(length):
            result = b''
            while len(result) < length:
                part = self.process.stdout.read(length - len(result))
                if not part:
                    raise EOFError()
                result += part
            return result
        try:
            while True:
                length = struct.unpack('>I', exact(4))[0]
                if not 1 <= length <= 1048576:
                    raise ValueError('invalid frame length')
                value = exact(length)
                self.frames.put((value[0], value[1:]))
        except EOFError:
            self.frames.put((0, b''))
        except Exception as error:
            self.frames.put((-1, str(error).encode()))

    def send(self, kind, data=b''):
        self.process.stdin.write(u32(len(data) + 1) + bytes([kind]) + data)
        self.process.stdin.flush()

    def event(self, timeout=15):
        kind, body = self.frames.get(timeout=timeout)
        if kind == 0:
            self.eof = True
        elif kind == 16:
            self.ready = json.loads(body)
            assert self.ready['verified'] and self.ready['processIsolation'] == 'dedicated-uid'
            assert self.ready['uid'] // 100000 == self.device.uid // 100000
            assert 80000 <= self.ready['uid'] % 100000 < 80008
        elif kind in (17, 18):
            self.output += body
        elif kind == 20:
            self.exit = json.loads(body)
            assert self.exit['descendantsReaped'] is True
        elif kind in (-1, 21):
            self.errors.append(body.decode(errors='replace'))
            raise AssertionError(self.errors[-1])
        return kind, body

    def started(self, marker=None):
        end = time.monotonic() + 20
        while self.ready is None or (marker and marker not in self.output):
            assert time.monotonic() < end and not self.eof and self.exit is None, 'startup did not produce expected marker'
            self.event()
        return self.ready

    def finished(self, cancelled=False):
        while not self.eof:
            self.event()
        assert self.exit is not None and self.exit['cancelled'] is cancelled
        if not cancelled:
            assert self.exit['exitCode'] == 0, self.exit
        assert self.process.wait(timeout=5) == 0
        return self.exit

    def cancel(self):
        if not self.eof:
            self.process.stdin.close()
            return self.finished(cancelled=True)


def main():
    args = argparse.ArgumentParser()
    args.add_argument('--adb', required=True)
    args.add_argument('--serial', required=True)
    args.add_argument('--allow-synthetic-root-tests', action='store_true', required=True)
    args.add_argument('--case', choices=['standard', 'unknown-uid', 'preclone'], default='standard')
    value = args.parse_args()
    device = Device(value.adb, value.serial)
    jobs = []
    results = []
    def passed(name, **facts):
        result = {'step': name, 'ok': True, **facts}
        results.append(result)
        print(json.dumps(result), flush=True)
    try:
        if value.case == 'preclone':
            before = device.lease_metadata()
            assert not before['live'] and all(size == 0 for size in before['leaseBytes'].values()), 'A pre-existing lease is busy; no workload was started'
            identity = device.workspace()
            path = device.workspaces[-1] + '/startup/'
            device.app('touch', path + 'preclone.synthetic')
            # Android blocks hard-link creation from the run-as SELinux domain.
            # Root only links the empty file in this test's fresh UUID branch.
            device.root('ln ' + shlex.quote(path + 'preclone.synthetic') + ' ' + shlex.quote(path + 'preclone.alias'))
            failed = Job(device, identity, "console.log('MUST_NOT_EXECUTE')")
            jobs.append(failed)
            while not failed.eof:
                try:
                    failed.event()
                except AssertionError:
                    assert failed.errors and json.loads(failed.errors[-1])['code'] == 'workspace_special_or_linked_file'
            assert failed.ready is None and failed.output == b''
            assert failed.exit['namespaceCreated'] is False and failed.exit['descendantsReaped'] is True
            assert failed.exit['exitCode'] == 125 and failed.process.wait(timeout=5) == 125
            after_failure = device.lease_metadata()
            # Always recover the deliberately rejected fixture with an ordinary
            # successful launch before asserting the regression, including on an
            # old binary. Never truncate a lease or kill a process in the test.
            device.app('rm', path + 'preclone.alias')
            recovered = Job(device, identity, "console.log('PRECLONE_RECOVERED')")
            jobs.append(recovered)
            recovered.started()
            recovered.finished()
            assert b'PRECLONE_RECOVERED' in recovered.output
            after_recovery = device.lease_metadata()
            assert not after_recovery['live'] and all(size == 0 for size in after_recovery['leaseBytes'].values())
            released = not after_failure['live'] and all(size == 0 for size in after_failure['leaseBytes'].values())
            print(json.dumps({'step': 'preclone-rejected-workspace-releases-lease', 'ok': released,
                              'noReadyOrExecution': True, 'namespaceCreated': False,
                              'failedLeaseBytes': list(after_failure['leaseBytes'].values()),
                              'failedLiveUidCount': len(after_failure['live']),
                              'subsequentLaunchSucceeded': True, 'finalLeasesEmpty': True,
                              'leaseCleanupConfirmed': True}), flush=True)
            assert released, 'Pre-clone rejection left a stale lease despite no guest process'
            return
        if value.case == 'unknown-uid':
            assert device.uid // 100000 == 0, 'The bounded unleased-UID fixture is for Android user 0 only'
            device.root('test ! -s ' + shlex.quote(device.files + '/droy-execution/leases/80007'))
            identity = device.workspace()
            command = '/data/local/tmp/droy-dedicated-uid-probe --hold-unleased-uid 80007'
            holder = subprocess.Popen(device.adb + ['shell', '-T', 'su -c ' + shlex.quote(command)],
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                unknown = json.loads(holder.stdout.readline())
                assert unknown['uid'] == 80007
                for index in range(7):
                    job = Job(device, identity, "console.log('SLOT_READY');setInterval(()=>{},1000)")
                    jobs.append(job)
                    assert job.started(b'SLOT_READY')['uid'] != 80007
                refused = Job(device, identity, "process.exit(0)")
                jobs.append(refused)
                while not refused.eof:
                    try:
                        refused.event()
                    except AssertionError:
                        assert refused.errors and json.loads(refused.errors[-1])['code'] == 'execution_concurrency_or_uid_unavailable'
                assert refused.ready is None and refused.exit['namespaceCreated'] is False and refused.exit['descendantsReaped'] is True
                assert refused.process.wait(timeout=5) == 125
                status = device.root('cat ' + shlex.quote('/proc/' + str(unknown['pid']) + '/status'))
                assert re.search(r'^Uid:\s*80007\s', status, re.M), 'The foreign identity was killed or changed'
                stat = device.root('cat ' + shlex.quote('/proc/' + str(unknown['pid']) + '/stat'))
                assert int(stat[stat.rindex(')') + 2:].split()[19]) == unknown['startTicks']
                for job in jobs:
                    if not job.eof:
                        job.cancel()
                passed('unleased-live-uid-never-reaped-or-reused', allOtherSlotsOccupied=True, exactForeignPidSurvived=True, startupRefused=True)
            finally:
                holder.stdin.close()
                released = json.loads(holder.stdout.readline())
                assert released['released'] and released['pidfdConfirmed'] and holder.wait(timeout=5) == 0
            print(json.dumps({'ok': True, 'syntheticOnly': True, 'path': 'native-adb-root-wire-complement', 'steps': len(results)}), flush=True)
            return
        first_id, second_id = device.workspace(), device.workspace()
        first = Job(device, first_id, "require('fs').writeFileSync('peer.synthetic','PRIVATE_A');console.log('A_READY');setInterval(()=>{},1000)")
        jobs.append(first)
        a = first.started(b'A_READY')
        denied = [device.files + '/droy-execution/workspaces/' + first_id + '/startup/peer.synthetic']
        denied += ['/proc/{}/{}'.format(a['workloadPid'], part) for part in ['cmdline', 'environ', 'fd/0', 'mem']]
        code = "const fs=require('fs');for(const p of " + json.dumps(denied) + "){let denied=false;try{fs.readFileSync(p)}catch{denied=true};if(!denied)process.exit(73)}"
        code += "let denied=false;try{process.kill(" + str(a['workloadPid']) + ",'SIGCONT')}catch{denied=true};if(!denied)process.exit(74);console.log('PEER_DENIED')"
        second = Job(device, second_id, code)
        jobs.append(second)
        b = second.started()
        second.finished()
        assert a['uid'] != b['uid'] and b'PEER_DENIED' in second.output
        first.cancel()
        passed('parallel-protected-workspaces', distinctUids=True, foreignProcDenied=True, foreignFilesDenied=True, foreignSigcontDenied=True)

        pty = Job(device, first_id, "console.log('PTY_READY');process.on('SIGWINCH',()=>console.log('COLUMNS:'+process.stdout.columns));process.on('SIGINT',()=>{console.log('PTY_SIGINT');process.exit(0)});setInterval(()=>{},1000)", pty=True)
        jobs.append(pty)
        pty.started(b'PTY_READY')
        pty.send(9, u32(101))
        while True:
            kind, body = pty.event()
            if kind == 22:
                foreground = json.loads(body)
                assert foreground['requestId'] == 101 and foreground['found'] and foreground['scope'] == 'host_pid_dedicated_uid'
                break
        pty.send(5, u32(104) + u32(31))
        while b'COLUMNS:104' not in pty.output:
            pty.event()
        pty.send(8, u32(102) + u32(2))
        while True:
            kind, body = pty.event()
            if kind == 23:
                assert json.loads(body)['ok'] is True
                break
        pty.finished()
        assert b'PTY_SIGINT' in pty.output
        passed('pty-foreground-resize-signal', actualForegroundGroup=True)

        marker = device.files + '/droy-execution/workspaces/' + first_id + '/startup/crash.synthetic'
        child = "process.on('SIGTERM',()=>{});setInterval(()=>require('fs').appendFileSync('crash.synthetic','x'),20)"
        code = "const cp=require('child_process'),fs=require('fs');const p=cp.spawn(process.execPath,['-e'," + json.dumps(child) + "],{detached:true,stdio:'ignore'});p.unref();setTimeout(()=>console.log('CRASH_READY'),200);setInterval(()=>{},1000)"
        crashed = Job(device, first_id, code)
        jobs.append(crashed)
        identity = crashed.started(b'CRASH_READY')
        status = device.root('cat ' + shlex.quote('/proc/' + str(identity['topLevelPid']) + '/status'))
        supervisor = int(re.search(r'^PPid:\s*(\d+)$', status, re.M).group(1))
        stat = device.root('cat ' + shlex.quote('/proc/' + str(supervisor) + '/stat'))
        start_ticks = stat[stat.rindex(')') + 2:].split()[19]
        device.root(' '.join(map(shlex.quote, ['/data/local/tmp/droy-dedicated-uid-probe', '--kill-owned-supervisor', str(supervisor), start_ticks, device.helper])))
        while not crashed.eof:
            crashed.event()
        assert crashed.exit is None, 'crashed supervisor must not report confirmed cleanup'
        recovered = Job(device, second_id, "console.log('RECOVERED')")
        jobs.append(recovered)
        recovery = recovered.started()
        recovered.finished()
        assert recovery['uid'] == identity['uid'], 'lease recovery did not reuse the verified cleaned slot'
        before = device.app('cat', marker)
        time.sleep(.3)
        assert device.app('cat', marker) == before, 'orphan kept writing after lease recovery'
        passed('supervisor-crash-recovery', unconfirmedCrash=True, staleLeaseReapedBeforeReuse=True, orphanStopped=True)
        print(json.dumps({'ok': True, 'syntheticOnly': True, 'path': 'native-adb-root-wire-complement', 'steps': len(results)}), flush=True)
    finally:
        cleanup_errors = []
        for job in jobs:
            if not job.eof:
                try:
                    job.cancel()
                except Exception as error:
                    cleanup_errors.append(type(error).__name__)
        if cleanup_errors:
            print(json.dumps({'ok': False, 'cleanupConfirmed': False, 'errors': cleanup_errors, 'fixturesPreserved': True}), flush=True)
            raise RuntimeError('synthetic process cleanup was not confirmed')
        # Only UUID-named fixture directories created by this process are removed.
        for path in device.workspaces:
            device.app('rm', '-rf', path)


if __name__ == '__main__':
    main()
