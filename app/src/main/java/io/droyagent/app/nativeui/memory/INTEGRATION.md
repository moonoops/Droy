# Native memory

`NativeMemoryScreen(state, model, onBack, modifier, onOpenPermissions?)` renders
the existing memory settings and the reachable record manager.
`onOpenPermissions` is optional navigation to the app permission page; capture
permissions continue through the existing authenticated product service.

Use `NativeMemoryViewModel.state` and call `startObserving()` / `stopObserving()`
with the visible screen lifecycle. Starting observation refreshes actual state
after returning from system permission pages. `startRuntime()` is an explicit
action and retains the shared Host stop policy.

The Host dispatcher mounts `createNativeMemory(ctx).handle(req, res, signal)`
from `runtime/mobile/native-memory.mjs` at `/droy/native/memory`. Its `GET` and
`POST { operation, args }` return the existing manager's `ok/result` envelopes.
The adapter repeats `droyMemoryHost.authorizeUi` before delegating to the same
manager handler; it does not open storage or model routes itself.

The UI reuses `DroyTheme`, `DroyIconAction`, Material components and global
`MaterialTheme.motionScheme` specs. Existing shared arrow, add, edit, delete and
refresh vectors are reused; no dependencies or Gradle changes are required.
Product strings and accessibility text live in the paired `native_memory.xml`.

Record corrections and forgetting use exact revisions and stable operation IDs.
An uncertain record write keeps its original parameters for an explicit retry
after a read; no mutation is retried automatically. Conflicts retain edits until
the user refreshes the version. Refreshing a version retains only the fields
the user changed and adopts current values for the other fields.

Endpoint permissions remain separate from external sharing. Revocation,
disabling and pausing remain accessible when the memory service is unavailable. The
record manager hides saved content after an observed management gate failure;
it retains an unsaved draft for recovery. Source references, attribution,
inference, retention and location qualification remain visible in details.

Archive import/export is not enabled by this screen. Personal memory works
without Root and uses the ordinary App security model. Source permissions
and endpoint consent remain separate. Unit and loopback tests use fictional
temporary data.
