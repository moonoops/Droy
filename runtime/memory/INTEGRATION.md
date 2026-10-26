# Personal memory integration

The mobile profile composes the Android host adapter, `@droyagent/privacy`, `@droyagent/execution` and `@droyagent/memory`. Personal records and processing grants use separate private stores; DSH owns conversations, tasks and model settings.

| Component | Entry points |
| --- | --- |
| Records, provenance and archives | [store.mjs](store.mjs), [schema.mjs](schema.mjs) |
| Tools and settings | [plugin.mjs](plugin.mjs), [adapter.mjs](adapter.mjs), [client.js](client.js) |
| Conversation and endpoint policy | [privacy plugin](../privacy/dsh-plugin.mjs), [privacy store](../privacy/store.mjs) |
| Isolated file, Shell, PTC and MCP operations | [execution plugin](../execution/dsh-plugin.mjs), [native launcher](../execution/launcher.cpp) |
| Android activation and archive transport | [memory-host.mjs](../mobile/memory-host.mjs), [memory-archive.mjs](../mobile/memory-archive.mjs) |
| Source processing | [memory-extraction.mjs](../mobile/memory-extraction.mjs), [maintenance.mjs](maintenance.mjs) |
| Native consent and capture | [MemoryRuntime.java](../../app/src/main/java/io/droyagent/app/MemoryRuntime.java), [HistoryCapture.java](../../app/src/main/java/io/droyagent/app/history/HistoryCapture.java) |

## Activation

Collection is off by default. First activation checks product and system Root grants, performs native qualification, stops older host processes and restarts into a verified runtime before private-store initialization. `host-ready` is accepted only after both stores and protection guards are ready.

Activity, page, device and location sources require independent consent. Missing Root, protection plugins or verified runtime identity make private operations unavailable. Turning memory off stops collection and extraction while existing session protection remains active.

## Dispatch and lifecycle

- Authorization and model dispatch share the same immutable endpoint snapshot, including URL, purpose and configuration identity.
- New-session state and fork inheritance are persisted before queued input can process copied content; unknown sessions are not inferred clean.
- Protect private results before they enter DSH history or model requests. Titles, compaction, extraction and speech have their own processing checks.
- Native batches are acknowledged only after durable ingestion. Changed or missing continuity markers stop interval derivation across gaps.
- A model endpoint grant does not authorize publishing private content or unrestricted script networking.

The [execution protocol](../execution/PROTOCOL.md) defines workload isolation and cleanup receipts. Native capture behavior is summarized in [history acquisition](../../app/src/main/java/io/droyagent/app/history/README.md).
