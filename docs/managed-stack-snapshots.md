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
Internal application and nonlocal demand roots are real live guest frames. Their
Core identity and source metadata remain absent when they have no original
binding; they do not borrow a caller's identity or callsite.
These records are diagnostic only: they contain no resumable `AP_STACK`,
continuation, or `throwTo` unwinding/resumption state.

The exact original `stg_cloneMyStackzh` foreign declaration reaches this
capture path in both lowerings. It returns the detached snapshot through the
original State/snapshot tuple and unchanged Haskell constructor. Recognition
validates the static `ghc-internal` target, `prim`/`safe` convention, raw argument
and result representations, saturation, and unresolved foreign head; it does
not depend on the consuming binding's name.

Snapshots are diagnostic data: they do not attach automatically to exceptions,
capture remote threads or emulate native `StgStack` memory. They retain no guest
payloads. Context-owned captures carry an identity token rather than a reference
to the language state; standalone captures have no context token.

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

## Diagnostic frame traversal

The admitted traversal operations are `getSmallBitmapzh`,
`advanceStackFrameLocationzh` and `getStackFieldszh`, with the exact original
foreign signatures and checked target layout.

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

The complete original decoder remains outside this managed diagnostic contract.
To prepare the original consumer fixture, use
`cabal run exe:thc-fixtures -- original-stack`.
