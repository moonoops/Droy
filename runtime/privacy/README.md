# Personal-data protection

`@droyagent/privacy` stores host-owned session protection and endpoint processing grants. It is composed with [personal memory](../memory/README.md). The mobile host uses the ordinary Android application sandbox and does not require Root. This store enforces model-processing grants and session provenance; it does not isolate scripts, plugins, files or network access within the app. App-hosted code and plugins are trusted.

## Host API

Open `PrivacyStore` with absolute host-owned `directory` and `markerPath`; keep the marker outside the database directory. `initialize: true` is only for verified first initialization. Missing metadata, identity mismatch and confirmed-revision rollback reject recovery rather than creating an empty store.

- `registerCleanSession` registers a known-new clean conversation.
- `protectSession` persists source identities and categories before returning private content.
- `assertAllowed` checks the actual processing endpoint at dispatch.
- `inheritSession` carries protection into forks and child agents.
- `forgetSources` invalidates existing and late references to forgotten sources.

Endpoint grants bind URL, purpose, category set and configuration digest. Model names alone are not endpoint identities. Grants and revocation come from user-authorized host settings, never model tools. Recheck queued work at dispatch; an earlier success is not a reusable authorization token.

The trusted setup service uses `openSetupTestSession()` for its fixed greeting and echo prompts. The random identity follows normal clean-session registration and dispatch checks, without creating an Agent, conversation history or memory access. Closing the scope removes pending registration; already registered provenance metadata follows the store's normal retention policy.

Categories are `memory`, `activity`, `location` and `derived`. The store retains opaque source identities rather than private text or credentials. Unknown and forgotten sessions remain restricted. Disabling memory collection does not remove existing conversation protection.

The worker requires the trusted runtime; Node permission-mode hosts are rejected instead of clearing execution flags. Source-limit, backpressure and worker errors reject the operation without dropping source identities.

## Checks

```sh
node --test runtime/privacy/tests/*.test.mjs runtime/execution/tests/*.test.mjs
```
