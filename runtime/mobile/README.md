# Droy mobile runtime

This package integrates the pinned DSH runtime with Android. DSH owns conversations, model calls, agent execution and extensions. Droy supplies the mobile interface, product authorization, Android tools and native runtime adapters.

## Build

From the repository root after installing the locked `runtime/dsh` dependencies:

```sh
node tools/prepare_dsh.mjs
node --expose-internals --test runtime/mobile/tests/*.test.mjs
node --expose-internals runtime/mobile/tests/profile-smoke.mjs
```

Preparation produces `runtime/generated/assets/dsh-runtime.zip` and its manifest. `upstream-patches.lock.json` pins patch inputs. Full preparation uses the unmodified installed dependencies; `--refresh-mobile` is for local iteration. Versions are bound by [release.json](../release.json).

## Host contract

Keep the installed runtime separate from persistent `DSH_HOME`. The native host supplies loopback ports, independent random `DROY_WEB_TOKEN` and `DROY_BRIDGE_TOKEN`, native-library paths and bundled shell environment. Tokens and launch URLs must stay out of logs.

Run `bootstrap.mjs`, then start the mobile CLI on `127.0.0.1`. WebView requests require the authenticated Droy cookie; product bridge requests use the separate Bearer token. A loopback listener alone is not an authentication boundary.

| Endpoint | Contract |
| --- | --- |
| `POST /tool` | `{action,args,taskId,callId}`; identity comes from the live engine call |
| `POST /cancel` | `{taskId,callId}`; awaited on cancellation |
| `POST /task/end` | `{taskId}`; releases task-scoped state |
| `GET /droy/ready` | Authenticated readiness of required runtime services |
| `POST /droy/shutdown` | Cancels work and flushes sessions before process exit |

Bridge replies are `{ok:true,result}` or `{ok:false,error:{code,message}}`. Unknown Android actions reject. Permission and stop checks run in the native host, and tools cannot grant their own permissions.

The [capability catalogue](assets/droy-capabilities.json) owns phone tool schemas and required capability groups. Normal requests expose a compact tool set; `droy_load_tools` expands a group without granting permission.

## Integration boundaries

- User plugins and configuration persist outside the replaceable runtime. Package management uses the bundled pnpm and the profile's own dependency directory.
- DSH's saved language and theme settings drive both WebView and native presentation.
- Android Use pause controls phone tools; stopping the entire engine also stops independent DSH work.
- Shell and third-party extensions retain their own DSH permission checks. Activated personal-data protection routes reviewed operations through [protected execution](../execution/README.md).
- Browser pages use a separate WebView process and storage. The compute tool runs in its own restricted Android process.
- Schedules depend on runtime availability. Third-party native modules must be compatible with Android.

Feature usage: [Android Use](../../android-use/README.md), [voice](../voice/README.md), [media input](../media-input/README.md), [memory](../memory/README.md), [notifications](NOTIFICATIONS.md), [companions](PETS.md).
