# Ordinary task control

The authenticated mobile endpoint `POST /droy/pet/action` accepts
`{taskId, action: "pause" | "resume" | "stop", expectedRevision}`.
`taskId` is the real SDK root controller. A child's question/answer identity stays
its own `taskId`; progress carries `controlTaskId` for task controls. An owned
child cannot establish a separate controller. Legacy `cancel` remains supported
and stops the current root family.

`expectedRevision` is a nonnegative safe integer, initially `0`. A successful
command returns HTTP 200 `{ok:true, accepted:true, control}` immediately after
the durable transition. This acknowledges acceptance, not physical quiescence.
CAS conflicts return HTTP 409 with the current `control`. Progress publishes
`pausing`, `paused`, `resuming`, and `stopping`; `stopped` is inactive. Every
controlled or ordinary progress payload carries its controller and revision.
Revisions remain monotonic across later runs in the same session.

Pause persists the product fence before aborting the current SDK turn with its
inbox retained. The SDK adapter parks waking sends, rejects new steps and tool
dispatch, disarms goal continuation without changing the objective, and checks
the real descendant graph. `paused` requires idle local drivers, settled owned
jobs with a positive producer receipt, stopped external descendants, durable
session writes, and native `quiescent:true` with no pending execution.
Unconfirmed work remains visible as a transition with an error; a timeout is
never evidence that an operation stopped.

Resume keeps the same sessions and context. Continuing live members with prior
native use each perform a fresh `android_observe` through the existing tool,
authorization, privacy, and approval pipeline before any member receives write
authority. Then each live continuation receives a new round containing the
resume instruction and its observation. Interrupted operations are not replayed
by the service. Pure model tasks require no phone access. Ended or cold children
are not guessed or rebuilt; the root can use the SDK's real continuation tools.
An explicitly changed goal is never overwritten by a saved goal reference.

Native authority uses `{taskId, operationId, epoch}` captured at each bridge call's
start, including preflight, execution, cancellation, audio, and task release.
This epoch is independent of the UI control revision. Native `resume` opens only
an observing epoch; explicit `activate` opens the subsequent write epoch.
`/task/end` preserves a pause fence. Old run/epoch requests cannot release a new
run. Input dictation's non-Agent owner retains its separate audio contract.

Product controls and native scope receipts are stored privately in
`DSH_HOME/droy-task-control/task-control.sqlite`; prompts and queued messages
stay in the existing session store. A paused inbox survives normal SDK disposal
as well as process recovery, without waking the model. Completed controlled
tasks retain inactive tombstones rather than reappearing as paused.
If a full Android app process restart loses a scope, restoration requires a different
`nativeInstanceId` and explicit native `scopeRestorationAllowed:true` proving no
unconfirmed old root execution. Reconstruction happens while the JS fence stays
closed and forces observation. Unknown prior outcomes stay unknown.
Unconfirmed privileged child leases survive app process death. A positively
confirmed exit or a known increase in Android's boot counter retires them;
restarting only the app does not prove that a privileged child exited.

Stop supersedes in-flight pause/resume, ends continuation and clears the current
inbox. Automatic messages remain unable to wake a stopped run. A later explicit
user task clears old parked messages and opens a new native operation scope;
it does not permanently disable the session.

Host regression commands (local scripted models and native fixtures only):

```sh
node --expose-internals --test runtime/mobile/tests/task-control.test.mjs runtime/mobile/tests/task-control-engine.test.mjs runtime/mobile/tests/execution-progress.test.mjs runtime/mobile/tests/pet-interactions-locale.test.mjs runtime/mobile/tests/native-api.test.mjs runtime/mobile/tests/mobile.test.mjs runtime/mobile/tests/agent-bridge.test.mjs
```
