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

The strict Core auditor admits that same exact symbol through its shared original
foreign-call validator. It checks the raw State/snapshot tuple and rejects a
State occurrence that relabels a stored scalar or boxed binding. The existing
auditor tests use the unchanged exported worker with an explicitly synthetic
scalar-result consumer. No stack getter, IPE, decoder, or remote-capture symbol
is admitted by this capability.

This does not attach snapshots to exceptions, emulate native `StgStack` memory,
capture remote threads, or freeze backtrace configuration. The remaining
GHC compatibility layer must preserve original primitive and foreign protocols:
GHC may have already inlined its decoder or formatter into a dependency. A managed
stack image must expose captured provenance to that unchanged Haskell code.
Stack annotations are separate work; this snapshot deliberately retains no guest
payloads. Context-owned captures carry an empty identity token, not a reference
to the language state or registry. Standalone diagnostic captures have no token.

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
or a complete library bridge. Strict package linking preserves the original
foreign head for its exact protocol validation at lowering.

## Original stack-info and IPE boundary

The bounded managed service implements `getStackInfoTableAddrzh`,
`getInfoTableAddrszh`, and `lookupIPE` through their unchanged original foreign
applications in both backends. Recognition requires the exact static
`ghc-internal` declaration, calling convention, saturation, raw representation
proofs and unshadowed foreign head. A stored operand cannot be relabeled by its
occurrence proof. A validated, typed, tables-next-to-code `TargetLayout` is
required; no host offsets or executable entry addresses are inferred.

The image is explicitly diagnostic: every captured live guest frame occupies
one virtual word and has a zero-payload `RET_SMALL` record. The stack itself has
a `STACK` info image. These immutable standard info-table bytes use target field
offsets and widths. A frame's readable standard table and its stable one-past
tables-next-to-code IPE key are distinct managed addresses. Frame offsets count
virtual words, not bytes. This representation is not a native frame-kind map,
an `AP_STACK`, or an asynchronous continuation.

Registrations belong to the capturing context. A foreign-context or standalone
snapshot is rejected, and a registered snapshot/key cannot change target layout.
Unknown IPE keys return zero without changing the destination. Known keys return
one only after a complete checked pointer-aware copy of `InfoProvEnt`; invalid
State carriers, ranges, immutable/raw-exposed storage and partial pointer cells
fail before any destination mutation. Field offsets include the output view's
base and embedded `InfoProv` base exactly once. `closure_desc` is a Word32 field,
not a pointer cell.

IPE strings retain actual copied binding and source provenance. Missing fields
stay empty; debug names do not establish module identity. The table name says
`THC managed diagnostic frame`, and the type description is empty. Strings and
info images remain immutable and usable after context disposal without retaining
guest frames or the context. The snapshot cache and allocation/offset index use
weak keys: discarded snapshots and unreferenced frame registrations are reclaimed
during a long-lived context. Any surviving address alias or copied output pointer
keeps its registration usable. Cached provenance excludes the info-table key to
avoid a weak-key/value retention cycle. Disposal clears all registrations.

`CoreStackInfoForeignTest`, `ManagedStackInfoImageTest`, and
`ManagedStackRuntimeTest` cover the raw contracts, target bytes and ownership/
transactional-copy rules. `OriginalStackInfoCallTest` places unchanged original
call excerpts in explicitly synthetic scalar-result consumers, exercising AST
and bytecode entries before and immediately after compilation, with inlining
enabled and disabled. This is protocol execution, not execution of the complete
original decoder or formatter. Remaining payload/bitmap/advance getters and
remote capture are still unsupported.
