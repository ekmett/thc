# Managed guest-stack snapshots

`ManagedStackSnapshot.capture(currentNode)` captures the live local `GuestRoot`
frames through Truffle's stack iterator, newest first. The supplied node must
belong to the newest guest frame. Calls made outside a guest frame, with an
unadopted node, or with a node from a different active/inactive root fail; they do
not manufacture a successful empty snapshot.

Each frame contains a copied function label, a detached source location, its
location-selection kind, nested source sections, and available Core source
notes. Source-section end columns are inclusive; Core notes retain GHC's
original exclusive end coordinates. Missing source metadata remains explicit.
`renderLines()` gives a small source-only rendering for JVM diagnostics.
The returned frame, section, note and line lists are unmodifiable.

`coreIdentity` separately copies a root's explicit original binding identity:
`bindingId`, `unitId`, `moduleName`, and `occurrence`. It is null when the root has
no such identity, including anonymous roots. Neither a debug `functionName`
(often `lambda <args>`) nor a source path establishes an owner. Identity strings
are detached from the runtime metadata; debug rendering and source coordinates
are unchanged.

The current frame uses the explicit capture node. Older frames use their own
`FrameInstance.callNode`: that node belongs to the caller and invokes the next
newer frame. Moving it to the next frame would misattribute every callsite.
Bytecode callers use `BytecodeLocation.get(frameInstance)` and materialize source
information when necessary. A bytecode-adopted current node must pass the actual
operation frame to `capture(currentNode, currentBytecodeFrame)`; omitting it
fails. The documented `BytecodeNode.getBytecodeLocation(frame, node)` resolves
that location. `FrameInstance.getFrame` is deliberately not used: the Bytecode
DSL may execute a different frame, for example in a resumed continuation. Frame
access is temporary and read-only. Root-only source fallbacks are marked `ROOT`,
not as current/caller source locations. No frame, arguments, call target, node, bytecode location, `Source`,
or `SourceSection` is retained in the result.

AST nodes expose Core note IDs, labels and original coordinates. The current
bytecode lowering exposes nested `SourceSection`s, ordered most to least
concrete, but does not retain those original note IDs/labels/exclusive ends.
Bytecode snapshots preserve the sections without inventing the missing notes.
The runtime may elide tail frames; this API records the live guest frames the
Truffle iterator exposes, not a historical call log or a one-to-one GHC stack.
These records are diagnostic only: they contain no resumable `AP_STACK`,
continuation, or `throwTo` unwinding/resumption state.

This is capture infrastructure only. It does not enable a GHC primop or foreign
symbol, attach snapshots to exceptions, emulate `StgStack`/info-table/IPE memory,
capture remote threads, or freeze backtrace configuration. The GHC compatibility
layer must implement the original primitive and foreign-call protocols: GHC may
have already inlined its stack decoder or formatter into a dependency. A managed
stack image will expose captured provenance to that unchanged Haskell code.
Stack annotations and context ownership checks at that runtime boundary
are separate work; this snapshot deliberately retains no guest payloads.

`ManagedStackSnapshotTest` exercises synthetic Core through real AST and bytecode
loaders, with a test capture callback as the newest guest frame. It checks frame
order and caller source locations before and after explicit compilation with
inlining enabled/disabled, missing source metadata, root-fallback provenance,
original AST note coordinates, immutability after return and synchronous unwind
through both backends (including context close), and fail-closed invalid capture
sites. Explicit cross-unit binding identities are checked before and after
compilation/inlining and context close, independently of the debug names and
source paths. The current-frame bytecode operation path is not yet exercised. These are JVM
protocol tests, not native GHC snapshot comparisons or a complete library bridge.
