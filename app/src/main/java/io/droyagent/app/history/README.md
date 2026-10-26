# Native history acquisition

This package adapts Android events for Droy personal memory. `HistorySpool` is a bounded, expiring delivery queue, not a second memory database. Its files stay in App-private storage.

## Lifecycle

The trusted host creates `HistoryCapture` with verified execution-privacy readiness. Source preferences default off; location has an independent switch, and pause stops every source. `MemoryRuntime` ties eligibility to product authorization and the current qualified Node host.

`configure`, `start`, `stop` and `refreshAuthorization` invalidate stale callback tickets. `readBatch` returns a durable batch until `acknowledge(batchId)` succeeds. A lost reply may be retried; unknown acknowledgements do not establish deletion.

A durable `continuityId` changes on pause, stop, overflow or restart. Consumers break activity and location derivation when it changes or is missing. Expiry is reported as coverage loss. Callback handlers do not call a model or wait on the network.

## Source controls

- Activity records describe observed windows, not attention or user action. Events predating current consent are rejected.
- Page capture excludes Droy, system permission pages, keyboards and browser content. Sensitive, editable or incompletely inspected trees suppress the page; projected text is bounded and deduplicated.
- Location requires its own Android permissions and visible foreground-service notification. The source retains Android's mock-location marker and does not call an external geocoder.
- Stopping or losing the qualified runtime stops capture. Disabling sources restores the normal runtime idle policy.

First protection activation uses the APK-owned probe and protected restart. The durable protection latch remains after memory is disabled; missing private stores cannot be silently reinitialized. See [memory integration](../../../../../../../../runtime/memory/INTEGRATION.md) for host boundaries.
