# Remote native terminal rendering

This Android library renders a remote terminal with the fixed Termux `v0.118.3`
emulator and canvas view. Droy owns the remote attachment, authorization, input
credit, acknowledgements and reconnection. The library owns parsing, rendering,
keyboard/IME composition and explicit user text selection.

## Integration

Include `:terminal-ui` in the project and add
`implementation project(':terminal-ui')` to the app module.

Create `io.droyagent.terminal.RemoteTerminalView` from the localized UI context
inside `AndroidView`. Set its listener before applying a snapshot. Recreate the
view when the UI language changes so its selection menu uses that language.

```java
view.setListener(new RemoteTerminalView.Listener() {
    public void onInput(long epoch, byte[] data) { /* Admit through the attachment input queue. */ }
    public void onResize(long epoch, int cols, int rows, int cellWidth, int cellHeight) { /* Request remote resize. */ }
    public void onInteractionError(String code) { /* Show the product's localized error. */ }
});
view.setTerminalColors(foreground, surface, primary);
view.resetSnapshot(epoch, snapshotUtf8, info.cols, info.rows);
view.setWritable(epoch, writable);
view.appendOutput(epoch, outputUtf8);
view.resizeRemote(epoch, info.cols, info.rows);
```

All view methods run on the Android main thread. A snapshot creates a new
emulator and accepts a strictly newer attachment epoch. The stream owner checks
sequence numbers before forwarding output. Recomposition must not reapply a
previous output chunk. Actual remote size changes go through `resizeRemote`;
viewport changes only propose a resize until the remote state confirms it.

Each IME commit and bracketed paste produces one immutable input chunk. Input
is never echoed locally. Unacknowledged input is owned by the remote connection
and must not be replayed on reconnect. Legacy X10 mouse reports can contain raw
non-UTF-8 bytes; a string-only remote protocol must reject that input explicitly
rather than replacing bytes. Modern SGR mouse reports use ASCII.

IME connections are tied to their attachment epoch, control generation and
focused visible view. Replacement clears uncommitted composition. Retired
connections cannot commit into a newer attachment or regained control.

Use `stopUiWork()` when temporarily hiding the view, and `release()` from
`AndroidView.onRelease` or page disposal. Release stops cursor/scroll callbacks
and selection popups, then drops the callback listener. Remote process lifetime
remains with the product service. `release()` is permanent for that view.

The parser supports dimensions 2–512 in each direction, 2,048 transcript rows,
snapshots up to 8 MiB and individual input/output chunks up to 1 MiB. Invalid
sizes and oversized frames fail explicitly. Product input queues apply their
own smaller limits before sending.

The remote output parser ignores OSC 52 clipboard requests. User-triggered
copy/paste uses the native selection menu and current Android clipboard grant.
Input and ANSI/OSC content do not enter application logs. The imported local
process session, JNI, native PTY and byte queue are excluded.

## Source and license

Upstream: [termux/termux-app, fixed commit](https://github.com/termux/termux-app/tree/5b657c6adf4304e5198951ce815fe0205dcac29c).
Only the selected `terminal-emulator` and `terminal-view` files are imported.
The upstream [LICENSE exceptions](https://github.com/termux/termux-app/blob/5b657c6adf4304e5198951ce815fe0205dcac29c/LICENSE.md)
identify that terminal code as Apache-2.0; the main application and
`termux-shared` are outside this import.

The Apache text, upstream license statement, notice and per-file original/local
hashes ship in `src/main/assets/third-party/termux-terminal/`.
`UPSTREAM.patch` records zero-context semantic changes against that fixed source
(trailing whitespace is normalized):

- A presentation-only session interface and emulator-only client callbacks.
- Atomic IME commit batches, epoch-bound input connections, synchronous IME key
  dispatch, permanent input-log suppression and explicit UI timer/popup cleanup.
- Localized selection labels, read-only paste state and theme-colored handles.
- A quiet test client for host JVM tests. The upstream Android-only clipboard
  test remains disabled; the Android device suite covers clipboard isolation.

Run `python3 terminal-ui/tools/check_vendor.py` to check source hashes, exclusions
and paired resources. Relevant Gradle test targets are
`:terminal-ui:testDebugUnitTest` and `:terminal-ui:assembleDebugAndroidTest`.
The device suite requires the explicit `nativeTerminalFixture=true` argument on
a dedicated emulator. It has a separate test Activity and no DSH runtime or
device-capability permissions. Light/dark evidence uses actual screen captures:
the expected surface and both text rows must remain visible for 300 ms within
a 10-second deadline. A timeout saves the failing screen instead of accepting
an idle hierarchy or parser colors as evidence of drawing. Full application build/install follows the
repository's `BUILDING.md` and `INSTALLATION.md`.
