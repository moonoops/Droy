# Root execution protocol

`libdroy_exec.so` is the fixed APK-owned launcher. Stdout carries binary frames; stderr uses bounded diagnostic codes. Private request, command and environment values must not be logged.

## Framing

Protocol version is `1`. Each frame is a big-endian u32 length, one type byte and payload. Length includes the type byte and is 1–1,048,576 bytes. Strings are u32 byte length plus UTF-8, without NUL. Unknown frames, malformed requests and unsupported operations reject the launch.

START (type `1`) supplies, in order: version, host UID, timeout, flags, PTY columns/rows, host proxy port, App files directory, workspace ID, cwd, executable, argv and environment. Integer fields are u32; argv and environment each have a u32 count. The schema and bounds are implemented by [launcher.cpp](launcher.cpp) and [protocol.hpp](protocol.hpp).

The host UID, files directory and workspace identity come from the trusted host. The native launcher leases an unprivileged UID and establishes the [execution boundary](README.md) before resolving the executable. It rejects inherited bridge credentials and does not grant workloads Root identity.

## Stream frames

| Input type | Meaning |
| --- | --- |
| `2` / `3` | stdin bytes / close stdin |
| `4` | Cancel the workload and its descendants |
| `5` | Resize PTY with u32 columns and rows |
| `6` / `7` | Write / half-close workload FD 7 |
| `8` / `9` | Signal / inspect the actual PTY foreground group |

| Output type | Meaning |
| --- | --- |
| `16` | READY with verified isolation facts and workload identity |
| `17` / `18` / `19` | stdout or PTY / stderr / FD 7 bytes |
| `20` | EXIT after descendant cleanup, including cancellation and timeout state |
| `21` | ERROR with a stable code |
| `22` / `23` | Foreground-group / signal receipt |
| `24` | Fixed host-cleanup receipt |

The host validates READY's complete mode contract, not only `verified`. Pipe closure cancels the workload. Cancellation receipts require confirmed descendant cleanup; an unknown process or missing receipt cannot be reported as cleaned up.

## Host lifecycle

Before protection activation, the host cancels tracked jobs and invokes only the fixed `--cleanup` command. Its type `32` request binds App UID and actual process identities with start times. The host checks cleanup and process-exit receipts before advancing. It never kills unrelated Root processes or silently clears an unknown UID lease.

A nonzero host proxy port enables only the guest loopback relay; the host proxy owns destination and data policy. There is no direct host-network fallback. The fixed `--guest-check` entry is used only inside an isolated workload to verify the supported boundary.
