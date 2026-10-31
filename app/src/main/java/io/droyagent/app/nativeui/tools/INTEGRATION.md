# Native tools and content

`NativeToolsScreen(state, model, onBack, initialSessionId?, modifier?)` owns its
feature observers. Create `NativeToolsViewModel` with the existing Activity
ViewModel owner and collect `model.state`. `initialSessionId` may name the
workbench's current saved conversation; this feature never creates a conversation
just to open Files. Back navigation stays within the feature before calling
`onBack`.

The screen reuses `DroyTheme`, semantic color roles, existing icon actions and
Material components. It adds no alternate theme or animation system. Text and
actions use the paired `native_tools.xml` resources. The native terminal renderer
requires the `terminal-ui` module dependency.

## Host dispatch

Create `createNativeTools(ctx)` once, dispatch the exact `/droy/native/tools`
prefix only after the native loopback authentication check, and pass the existing
request/response cancellation signal to `handle(req, res, signal)`. Call
`dispose()` when the owning Host scope closes. No changes to the legacy browser
surface host or legacy Activity are required.

Direct dependencies, at the existing DSH version:

- `@deepseek-ai/dsh-client-connection` for its public `Peer`.
- `@deepseek-ai/dsh-api-workspace-files/typert`.
- `@deepseek-ai/dsh-api-terminal-controller/typert`.
- `@deepseek-ai/dsh-office-to-pdf/typert`.

Successful unary responses use `{ok:true,result}`; failures use the shared safe
`{ok:false,error:{code,message}}` envelope.

| Route suffix | Method and input | Result |
| --- | --- | --- |
| `/files/list`, `/files/stat` | POST `{sessionId,path}` | Original WorkspaceFiles result |
| `/files/text` | POST `{sessionId,path,range?}` | Original paged text result |
| `/files/bytes` | POST `{sessionId,path,options?}` | Original metadata with `data` as base64 and `encoding:"base64"`; complete returned window capped at 8 MiB, never truncated |
| `/files/pdf` | POST `{sessionId,path}` | Authorized foreground Office-to-PDF render; original source metadata plus complete PDF `data` base64, `encoding`, `pdfBytes`, `missingFonts` and `generation` |
| `/attachments/stat` | POST `{sessionId,seq,attachmentId}` | `{kind,attachmentId,name,mediaType,bytes}` from that durable message's actual reference |
| `/attachments/bytes` | POST same proof | Metadata plus complete base64 `data` and `encoding`; preview limit 8 MiB |
| `/attachments/pdf` | POST same proof | Foreground Office conversion after proof authorization; delayed source read rechecks that proof; complete PDF base64 and `pdfBytes` |
| `/attachments/export` | GET `?sessionId=...&seq=...&attachmentId=...` | Complete raw store stream up to 100 MiB; no Content-Length; EOF integrity failure terminates the transfer without a successful end |
| `/files/watch` | GET `?sessionId=...&path=...` | Original `ready` / `change` SSE frames |
| `/content` | POST `{sessionId,beforeSeq?,limit?}` | `{entries,hasMore,nextBefore}`; entries are exact `deliverables/presented` or `workspace/changes` events |
| `/changes/summary` | POST `{sessionId,seq}` | Recorded summary without Host `cwd` or snapshot tree identifiers |
| `/changes/diff` | POST `{sessionId,seq,index}` | Original text/binary/oversized comparison; unavailable history returns 404 |
| `/terminal/list` | POST `{sessionId}` | Original retained terminal list; does not activate an Agent |
| `/terminal/environment`, `/terminal/shells` | POST `{sessionId}` | Original public Remote result |
| `/terminal/create` | POST `{sessionId,request}` | Original idempotent create receipt; request uses the public TerminalCreateRequest |
| `/terminal/rename` | POST `{sessionId,id,title}` | Original result |
| `/terminal/close` | POST `{sessionId,id}` | Original result; only an explicit user action closes a process |
| `/terminal/attachment` | POST `{sessionId,id}` | `{attachmentId}`; the terminal must already exist |
| `/terminal/attachment/{attachmentId}` | GET once | SSE opening metadata, then original TerminalFrame values |
| `/terminal/attachment/{attachmentId}/input` | POST original TerminalInput | 202 `{accepted:true,sequence}` means carrier admission, not provider acceptance |
| `/terminal/attachment/{attachmentId}/detach` | POST `{}` | `{detached:true}`; does not close the process |

File scope is resolved by the public `workspaceFileScopeId` lookup from Session
headers. Listings and directory watches retain upstream workspace containment;
file reads retain the composed filesystem's existing read authority. The caller
cannot supply a root. No file mutation endpoint is exposed.

## Terminal attachment

The opening frame is
`{type:"native/attached",attachmentId,maxInputBytes,maxBufferedBytes,maxCols,maxRows}`.
It precedes the original `snapshot`, `output`, `state`, and `ack` frames. Input
sequence numbers start at one per attachment, shared by write and resize. The
carrier bounds encoded pending frames until the provider's original ACK. It
never attaches through `terminalController.attach` directly: the public Gateway
creates the real Remote invocation and its single-consumer uplink.

`NativeTerminalConnection` owns an epoch per physical stream. A snapshot rebuilds
the renderer, output must be contiguous after its sequence, and old epochs cannot
change the current screen or control. Input is UTF-8 validated, split at codepoint
boundaries and admitted atomically under the current window. Non-UTF-8 legacy
mouse input is refused without lossy conversion. Losing control discards unsent
commands. An uncertain HTTP input result detaches the stream; reconnection never
replays input. Leaving the page detaches only.
Backgrounding the Activity also detaches; returning opens a new snapshot without
replaying input. Renderer dimensions are checked through its public
`canRenderDimensions` contract. Unsupported dimensions are reported explicitly;
the client does not silently resize the remote PTY.

## Browser and previews

`NativeBrowserSurfaceHost` embeds the existing BrowserService SurfacePackage.
It shares real Agent tabs and the original URL/DNS policy, and publishes actual
surface visibility through BrowserUiProtection. Native controls provide tab
selection, address entry, back, forward, reload and explicit close. A retiring
native Activity cannot detach its successor's surface for the same tab.

Call `model.openAttachment(sessionId, seq, attachmentId)` before navigating to
Tools to open a saved workbench message attachment. Supply its real committed
event sequence and omit an unrelated `initialSessionId`. Stream-only references
without a durable sequence cannot be opened. The adapter verifies the exact
event's actual file/image reference through `sessionQuery.readEvent`; there is
no endpoint for reading a global attachment ID without that proof. Reads use
the public attachment store, not its private paths, and do not activate Agents.

Local HTML previews use a separate restricted WebView: JavaScript, network and
file/content access are disabled, a restrictive CSP is added, navigations are
blocked, and no JavaScript bridge is installed. Other previews are native text
pages, background-decoded bounded images and Android PdfRenderer pages. Office
conversion uses the shared public OfficeToPdf service; if conversion is
unavailable the original file can still be opened externally or saved. Long
diffs render through a LazyColumn. Binary/unsupported files and unavailable
comparisons have explicit states.

Only an explicit Open with or Save a copy action exports data. Workspace copies
use bounded byte windows, verifying source path/version/size on every window and
a final stat; attachments retain the store's complete EOF verification and
content digest. Copies are capped at 100 MiB independently of the 8 MiB preview
limit. Unfinished or changed transfers are never published. Verified snapshots
live only in the private `cache/native-tools-exports/` tree; cleanup touches only
this feature's old snapshots. Add this one FileProvider path to the existing
`.media-input-files` provider:

```xml
<cache-path name="native-tools-exports" path="native-tools-exports/" />
```

External viewers receive a FileProvider content URI with READ permission only,
never loopback URLs, Cookies or engine paths. Save a copy uses Android's document
picker and copies the verified private snapshot to the explicitly chosen
destination; failure attempts removal of that new incomplete document and
reports any remaining incomplete copy. Cancelled preparation does not launch a
viewer after leaving the feature. No workspace write-management API is added.

## Validation

Host contracts are covered by `runtime/mobile/tests/native-tools.test.mjs`,
including a real Typert registry/Gateway and Remote invocation fixture. Pure JVM
input, connection, source-copy consistency and private-cache tests are under
`app/src/test/java/io/droyagent/app/nativeui/tools/`.
