# Personal memory integration

The mobile profile composes the Android host adapter, `@droyagent/privacy`, `@droyagent/execution` and `@droyagent/memory`. Personal records and processing grants use separate private stores; DSH owns conversations, tasks and model settings.

| Component | Entry points |
| --- | --- |
| Records, provenance and archives | [store.mjs](store.mjs), [schema.mjs](schema.mjs) |
| Tools and settings | [plugin.mjs](plugin.mjs), [adapter.mjs](adapter.mjs), [client.js](client.js) |
| Conversation and endpoint policy | [privacy plugin](../privacy/dsh-plugin.mjs), [privacy store](../privacy/store.mjs) |
| File, Shell, PTC and MCP integration | [execution plugin](../execution/dsh-plugin.mjs) |
| Android activation and archive transport | [memory-host.mjs](../mobile/memory-host.mjs), [memory-archive.mjs](../mobile/memory-archive.mjs) |
| Source processing | [memory-extraction.mjs](../mobile/memory-extraction.mjs), [maintenance.mjs](maintenance.mjs) |
| Native consent and capture | [MemoryRuntime.java](../../app/src/main/java/io/droyagent/app/MemoryRuntime.java), [HistoryCapture.java](../../app/src/main/java/io/droyagent/app/history/HistoryCapture.java) |

## Activation

Collection is off by default. Activation initializes the App-private memory and privacy stores and establishes the current host's readiness. It does not request Root or qualify a Root execution sandbox. `host-ready` is accepted only after both stores and processing guards are ready. Existing stores, source settings and endpoint grants remain in place when upgrading from the earlier Root-dependent lifecycle.

Activity, page, device and location sources require independent consent. Activity and page capture use accessibility access; location uses Android location permissions and foreground-service rules. Missing source permission affects that source rather than disabling all memory. Missing memory/privacy services or a changed runtime identity still stop private processing. Turning memory off stops collection and extraction while existing session processing restrictions remain active.

## Dispatch and lifecycle

- Authorization and model dispatch share the same immutable endpoint snapshot, including URL, purpose and configuration identity.
- New-session state and fork inheritance are persisted before queued input can process copied content; unknown sessions are not inferred clean.
- Protect private results before they enter DSH history or model requests. Titles, compaction, extraction and speech have their own processing checks.
- Native batches are acknowledged only after durable ingestion. Changed or missing continuity markers stop interval derivation across gaps.
- A model endpoint grant authorizes processing for the selected purpose and categories, not publishing private content.

File, Shell, PTC and MCP operations use the ordinary App execution model. Scripts and plugins running under the App identity are trusted with its data access; memory activation does not guarantee process or network isolation from them. Root-only Android capabilities, such as another application's process-memory access, retain their own permission checks. Native capture behavior is summarized in [history acquisition](../../app/src/main/java/io/droyagent/app/history/README.md).

## Work files from earlier Root execution

Earlier protected sessions stored their work files under the App files directory at
`droy-execution/workspaces/<SHA-256 of session ID>/default` (the DSH workspace) or
`startup` (the startup workspace). These directories are retained during upgrade.
They are not merged into the shared workspace: local file and Shell tools now use
the conversation's ordinary working directory. To recover an earlier artifact,
inspect the matching retained directory and explicitly copy the selected files to
the current workspace. Access follows Android file permissions; files restricted
by their creator may still require that creator's privileges to export.
