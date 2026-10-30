# Personal memory

`@droyagent/memory` supplies Droy's local records, retrieval and DSH integration. Android controls source consent and capture; DSH retains conversation and task ownership. Runtime records live outside this source directory.

## Host API

[store.mjs](store.mjs) exposes `MemoryStore`; [schema.mjs](schema.mjs) defines accepted records. Open a host-owned absolute directory, using `initialize: true` only for verified first initialization. Existing identity, owner-lock and revision failures must not be recovered by silently recreating storage.

| Method group | Purpose |
| --- | --- |
| `status`, `record`, `get`, `query`, `aggregate` | Inspect and manage bounded records |
| `revise`, `forgetPlan`, `forget` | Conditional correction and source-aware forgetting |
| `ingest`, `getSourceCursor`, `maintenancePlan`, `maintain`, `changes` | Durable acquisition, expiry and change tracking |
| `backup`, `exportArchive`, `importArchive` | Host-selected snapshots and encrypted archives |

Records use opaque source identities and exact revision references. Idempotency keys prevent duplicate ingestion; unknown continuity breaks activity and location derivation. Android activity events describe observed windows, not proven attention or usage duration. Mock-location flags remain attached to their records.

Queries use literal Unicode matching, bounded pages and explicit coverage/truncation metadata. Corrections preserve existing protection categories. Forgetting invalidates historical source references and dependent material; expiry follows retention rules without claiming forensic deletion of exported copies or flash storage.

## DSH integration

[plugin.mjs](plugin.mjs) and [adapter.mjs](adapter.mjs) expose bounded model tools. The store itself is a trusted-host service and must not be exposed directly as a model tool.

Before disclosing private content, persist [session protection](../privacy/README.md) and validate the actual model endpoint, data categories and purpose. Personal memory uses ordinary Android App-private storage and works without Root. Collection requires a ready memory service and independent source consent. Disabling collection does not remove processing restrictions from existing conversations.

Scripts and plugins running under the App identity are trusted with that identity's data access. Memory activation does not place file, Shell or MCP operations in a separate Root sandbox or impose script-network isolation.

[INTEGRATION.md](INTEGRATION.md) identifies host entry points and activation requirements.

## Checks

```sh
node --test runtime/memory/tests/*.test.mjs runtime/privacy/tests/*.test.mjs runtime/execution/tests/*.test.mjs
node --expose-internals --test runtime/mobile/tests/memory-*.test.mjs
```
