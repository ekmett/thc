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

The exact original `stg_cloneMyStackzh` foreign declaration now reaches this
capture path in both lowerings. It returns the detached snapshot through the
original State/snapshot tuple and unchanged Haskell constructor. Recognition
validates the static `ghc-internal` target, `prim`/`safe` convention, raw argument
and result representations, saturation, and unresolved foreign head; it does
not depend on the consuming binding's name.

This does not attach snapshots to exceptions, emulate `StgStack`/info-table/IPE
memory, capture remote threads, or freeze backtrace configuration. The remaining
GHC compatibility layer must preserve original primitive and foreign protocols:
GHC may have already inlined its decoder or formatter into a dependency. A managed
stack image must expose captured provenance to that unchanged Haskell code.
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
source paths.

`OriginalStackCloneTest` additionally executes a provenance-recorded projection
of the unchanged exported `cloneMyStack1` worker. This reaches the actual newest
bytecode operation frame, checks its original source coordinates and binding
identity, and verifies first post-installation compiled calls with inlining
enabled/disabled. Its wrapper and moved-body consumer control are explicitly
synthetic, not fresh GHC inline exports. Raw contract and state-relabel controls
fail closed. These are JVM protocol tests, not native GHC snapshot comparisons
or a complete library bridge. Strict package linking currently still treats
unresolved foreign heads as missing Haskell globals before lowering; this
separate linker limitation is not bypassed by the clone adapter.
