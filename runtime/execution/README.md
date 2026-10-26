# Protected execution

`@droyagent/execution` supplies the filesystem, subprocess, PTY and reviewed MCP adapters used when personal-data protection is active. The Android host starts the APK's fixed `libdroy_exec.so` through an explicitly authorized Root channel.

Each workload uses private mount, network and UTS namespaces, a dedicated unprivileged UID and filtered read-only procfs. The selected workspace is shared with the App group; personal records, privacy state and other sessions are not mounted. The supervisor cancels and reaps descendants before confirming exit.

Protected jobs have no arbitrary network access. Clean jobs may use the host's per-task public HTTP/CONNECT proxy; clients ignoring that proxy have no host-network fallback. Missing Root, unavailable isolation primitives or changed runtime identity reject protected execution.

## Build

From the repository root:

```sh
DROY_NDK_DIR=/absolute/android-ndk sh tools/build_execution.sh
node --test runtime/execution/tests/*.test.mjs
```

The NDK and output selection are handled by the build script. Packaging installs the ARM64 executable in the APK native-library directory.

## Integration

The trusted host chooses workspace identity and session protection before launch. Model endpoint consent is separate from script networking. Unreviewed host-code tools are unavailable after protection activation.

Transport frames and lifecycle receipts are defined in [PROTOCOL.md](PROTOCOL.md). Android qualification must use the normal product service path; a host test or Root-shell experiment does not establish App-path isolation.
