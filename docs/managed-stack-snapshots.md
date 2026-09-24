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
original decoder or formatter. Remote capture is still unsupported.

## Diagnostic frame traversal

The remaining original getter declarations are checked with the same exact raw
contracts, including the lifted `Any` result of `getStackClosurezh`, the Word32
result of `getStackFieldszh`, and all three ordered components of
`advanceStackFrameLocationzh`. Recognition and lowering do not by themselves
admit these calls through the capability auditor or prove complete Decode.
Only `getSmallBitmapzh`, `advanceStackFrameLocationzh` and `getStackFieldszh`
are newly admitted after unchanged original-call proofs pass in both backends,
both handoff modes and immediately after explicit compilation. The eight cold
getter capabilities remain disabled.

The managed image has no unused stack capacity: it is a zero-slack sequence of
one-word, zero-payload `RET_SMALL` records. `getStackFieldszh` reports that virtual
capacity; it does not estimate the native stack's allocated capacity.
`getSmallBitmapzh` returns bitmap zero and payload size zero. Advancing a valid
nonterminal word offset returns the same snapshot, the next offset and one.
Advancing the final frame returns a null snapshot carrier, zero offset and zero,
matching the terminal convention of the original `Stack.cmm`; the null carrier
is not a valid input snapshot. Bounds, context ownership, immutable target layout
and one-word nonprofiling header geometry are checked before returning results.

The eight payload/large-bitmap/BCO/RET_FUN/underflow getters have exact protocol
boundaries but reject these incompatible managed frames. No heap closure, raw
pointer word, native bitmap, chunk link or native frame kind is fabricated.
These restrictions are a diagnostic representation boundary, not native stack
introspection or resumable `AP_STACK` support. Complete unchanged decoder
execution remains a separate proof requirement.

`OriginalStackDecoderCallTest` retains all eleven remaining original getter
applications with exact original source tables. Its explicitly synthetic scalar
consumers inspect every hot result component (including the terminal null),
exercise one- and two-frame snapshots, and check cold failures and invalid
offsets without replacing the original GHC applications. It is a protocol proof,
not complete decoder execution.

## Original formatter execution

The shared Haskell fixture command `original-stack-formatter` exports the pinned
original source closure and calls unchanged `prettyStackEntry` with six explicit
`StackEntry` inputs. Native GHC supplies 606 code-point observations, including
empty fields, Unicode, punctuation and embedded NUL characters. Both pre- and
post-Tidy consumers pass strict audits. `OriginalStackFormatterTest` checks those
observations through both backends, before and immediately after explicit
compilation, with inlining enabled and disabled.

The fixture records exactly 84 source inputs and 84 artifacts from one export
attempt. Provenance tests independently pin the upstream source catalog and
reject omissions, changed pins and escaped paths. Focused preparation and cache
receipts reuse this exact inventory; installed-interface symlink overlays are
neither hashed nor cached. This executes the original formatter on constructed
entries, not the full original decoder on captured snapshots.

The source overlay also includes unchanged `GHC.Internal.IO.Unsafe` from GHC
commit `902339d332fb4ce2b3c87dcac1ee6495d41ad886`, covered by the pinned source
license and hash catalog. Fresh export supplies the exact
`ghc-internal:GHC.Internal.IO.Unsafe.unsafeDupableInterleaveIO1` binding needed
by original callers, without a worker-name alias. A separate test uses explicitly
synthetic scalar consumers and confirms both original `ExecutionStack.Internal.stackFrames`
references name that exact worker. The scalar consumers wrap the unchanged
binding: strict linking rejects its removal, discarding its delayed result does
not run the action, and demanding
the result runs it, through both backends and explicit compilation. This is not
a native oracle for those synthetic consumers, nor full decoder support. The
worker itself still has an unboxed-tuple result and is not a scalar host entry;
the libdw-based `stackFrames` closure remains unsupported.

The same overlay includes unchanged `GHC.Internal.Heap.InfoTable.Types.hsc`
from that pinned revision, compiled before `Heap.InfoTable` using the target's
real `hsc2hs`. Fresh export supplies the exact
`ghc-internal:GHC.Internal.Heap.InfoTable.Types.$w$cshowsPrec` body, referenced
36 times by three original `Heap.Closures` workers. The proof checks its formal
representations and the generated `HalfWord` width against the target layout;
it does not replace or alias the worker. The unchanged original `Ptr`,
`Data.Either`, and `Word` sources now supply `Ptr.$fShowFunPtr`,
`Data.Either.$fShowEither`, `Word.$fShowWord32`, and `Word.$fShowWord8`
under the `ghc-internal:GHC.Internal` prefix. Availability of those exact
bindings does not establish full `Show StgInfoTable`, ErrorCall, or decoder
support. Strict traversal now exposes missing `Bignum.Integer.integerFromWord#`,
`Numeric.showHex1`, `Numeric.showIntAtBase`, and `Real.$fIntegralInteger`, as
well as the separate encoding/libdw frontier and unsupported `addr2Int#`.
Those dependencies and cold decoder branches are not admitted by this source
addition.
