# JAM runtime integration

THC is migrating to JAM-patched GraalVM for JVM execution and JAM-patched
SubstrateVM for native executables. The purpose is general `System.Mem.Weak`
semantics, including keys held by Java code and finalizers that can resurrect
their keys. Selecting the collector alone does not implement THC weak pointers.
The runtime uses real `jam.vm.Weak` associations and guest finalizer
carriers. Linux qualification and remaining platform limits are recorded below. THC implements
[language-level lifted weak handoff](https://github.com/ekmett/jam/issues/7)
using ordinary weak registrations and bootstrap finalizers. The collector needs
no language resolver callback or additional interface for this design.

The ordinary `jam.vm.Lifted` value protocol is supplied by the unchanged source
from Jam commit `92a0cbcda6b9413dc2df3b2a6e327f06d90ea94b` in
[`nih/pinned/jam-lifted`](../nih/pinned/jam-lifted/README.md), compiled with THC.
The Linux provider includes this interface; the macOS and Windows pins predate
it. This supplemental compile input adds no native API and does not replace
`jam.vm.Weak` or its bridge. It can be removed when all pinned providers include it. See
[nonforcing value resolution](thunk-updates.md#nonforcing-lifted-values).

## Toolchain ownership

The VM, Graal compiler, SubstrateVM, `jam.vm.Weak` API and native collector
libraries form one versioned dependency. `etc/jam-graalvm.json` records the
producer revision, upstream source pins, transport digest and unpacked package
identities for each platform. A release/version string alone does not establish compatibility.

The upstream owner is now [ekmett/jam](https://github.com/ekmett/jam), including
its managed-runtime sources under `vm/`. Linux consumes the complete release-flavor
JVM and Native Image toolchain from `vm-2026.10.08-3cb04509`. macOS and Windows
retain the fastdebug preview `vm-2026.10.07-a7ebfc52`. Source, archive and
installation identities move together within each platform. Native adapter filenames use `jam-vm`; runtime library manifests
determine deployment membership. The release assets preserve the qualified
producer bytes beyond temporary CI artifact retention.
Ordinary builds consume a prebuilt package through `JAVA_HOME`; they do not
build a JDK or compiler. Toolchain caching depends on platform and package
identity, independently of THC sources and Cabal's project file.

CI selects the host platform from this pin and checks its recorded OS/libc floor
before running Java. It restores only the exact platform, archive and installation
digests, verifies the downloaded archive, then runs `verifyJamToolchain` before
saving the installed cache. A `github-release-asset` transport uses a versioned
GitHub release URL and `tarSha256`; an optional `zipSha256` preserves a qualified
Actions ZIP containing that tar archive. Temporary `github-actions-artifact`
transport also requires an unexpired artifact and a `GH_TOKEN` with read access
to the producer's Actions artifacts. It supplies qualification while release
publication is pending; expired downloads fail without choosing another JVM.

`verifyJamToolchain` checks the selected package. The JVM application and test
forks select `UseJamGC`, ordinary object headers, disabled CDS and equal initial
and maximum heap capacities. Existing THC Truffle API/runtime/Sulong patches
remain part of the shared-host contract. The API loads from the host classloader,
with the matching native directory selected from the running package.

The pinned Linux package requires glibc 2.38. Matching macOS and Windows
packages have separate recorded identities. Windows has the bounded JVM
weak-bootstrap evidence below; macOS THC execution remains unqualified.
The macOS package requires macOS 26 and cannot run on macOS 15.
Consumer CI uses the retained release assets and the matching platform floors.

Native Image builds select `--gc=jam`. Both image recipes also reuse the
[two-class builder correction](../research/native-image-preparation/runtime-simulated-folds/README.md)
that keeps simulated objects without host backing out of runtime-compiled
graphs. The field load remains when its constant cannot be encoded; ordinary
image/deoptimization compilation is unchanged. This affects every language
in that image build, not other processes using the installed JDK. Preparation
checks source identities and compiles the overlay; its behavioral checks run
separately. Builder memory is separate from the
produced executable's fixed per-isolate heap. JAM currently defaults to a
128 MiB total heap and a 32 MiB nursery; its old generation must fit below the
image heap's 1 GiB offset. Do not carry the former 16 GiB executable heap default
into this layout. Larger application heaps require collector support and actual
workload qualification.

On Linux, the produced executable requires its adjacent `.jam` directory.
The package's `runtime-libraries.txt` names the libraries; its legal directory
supplies the notices. The driver must validate these inputs and emitted outputs,
include them in the deployment inventory, and preserve them when removing build
staging. Relocation must succeed without the builder installation. This
sidecar-dependent result is not a single-file executable.

## Reachability and identity

For each association `K => (V, F)`, independent reachability of `K` retains the
original lazy `V` and `F`. References from `V` or `F` back to `K` do not establish
independent reachability. The collector resolves dependent associations to a
fixed point. Ordinary Java roots count; a separate walk of only the Haskell heap
cannot satisfy this contract.

Weak handles contain a registration token and its context identity, without
strong references to `K`, `V` or `F`. Dropping a handle does not cancel the
registration. Dead associations are classified before finalizer rescue.
Resurrecting a key does not revive its retired registration. Dead registration
metadata must be reclaimable without making old tokens valid again; collection
and idle polling costs must not grow with all registrations since process startup.
The Linux release reuses retired slots with generation tokens and keeps active
scans separate from historical capacity. The older macOS and Windows adapters
still retain and scan dead records. Registration
metadata failure returns to Java as an allocation failure without publishing a
partial association. This does not cover arbitrary collector allocation failure.
Finalizer state retains its actual captures, without an invented reference to the
entire value payload after death.

THC's completed thunk is an indirection to its exact WHNF. Both backends can
replace a local thunk alias with that value. A weak registration created before
evaluation must therefore follow its logical key when the original wrapper dies.

The language inspects the key without forcing it. An already resolved key uses
an ordinary association against its result. An unresolved thunk uses an ordinary
association whose bootstrap finalizer captures the thunk, conditional value and
real finalizer. When pumped in normal mutator execution, the bootstrap inspects
resolution again. A replacement gets a successor association; an unresolved key
reaches real finalization. Available chains use the same rule, without endlessly
reinstalling a self-reference or cycle.

The outer handle retains tokens and lifecycle state, not an active strong path
to the key, value or callbacks. Install and publish the successor before releasing
the old captures. Retiring a bootstrap token is not logical death: dereference
and callback attachment must settle queued/running handoff, including when called
from a pump thread or nested finalizer. Explicit finalization races with the same
claim protocol and runs the real finalizer at most once. Successor-installation
`OutOfMemoryError` preserves the resolved successor and cleanup obligation. The
existing drainer retries installation with capped backoff, including after the
public handle is dropped. Publication restores that same handle before releasing
failed captures. Explicit finalization or shutdown cancels retries; real cleanup
and non-allocation failures are not retried. Continued allocation failure may
retain the captures; recovery from actual heap exhaustion remains unqualified.

Bootstrap captures can retain the key, answer and backing value until another
collection. This may delay finalization and affect other weak associations;
there is no fixed collection-count or collection-identical GHC guarantee. Before
real guest finalization, release the bootstrap's obsolete key/value captures so a
blocked finalizer does not retain the conditional value unnecessarily.

These costs apply to weak registration and handoff. Ordinary strong references,
constructor/array/frame layouts, CBD decoding and thunk evaluation remain
unchanged. Collector-time selector contraction is separate work, not a dependency
of this strategy. `jam::vm` provides ordinary weak associations and pump lifetime;
core Jam needs no new pointer type, descriptor, scanner rule or entry compiler.

## Finalizer execution

JAM owns conditional reachability, retirement and atomic claims. THC owns guest
execution and context lifetime. Explicit finalization runs attached C callbacks
once, newest first, and returns the exact reusable Haskell action. Automatic
execution calls `THC.Internal.Weak.runWeakFinalizer`, whose only adaptation is
from the raw tuple-state action ABI to GHC's original `runFinalizerBatch`.
The original GHC code reads the current exception handler and handles handler
exceptions. THC must not duplicate that behavior with a Java catch-and-discard
path.

The draft uses `GuestThreads` and its ordinary call, masking and continuation
machinery. A JVM-wide drainer takes claims and dispatches them independently
through their owning contexts, so a suspended action does not block other
claims. The claim remains rooted until its actual guest carrier terminates or
shutdown proves abandonment. The runnable exposed to JAM's public `pump()`
also waits for that real termination; submission alone is not completion.
Automatic Haskell actions and C callbacks require guest thread permission.

Context shutdown fences admission before stopping and joining guest carriers.
It then releases claims cancelled before their runnable entered, including
in-flight thread construction. Only after this may native providers be closed.
C callback captures and native borrows remain valid through their real use.
The draft consumes callback captures in order and clears the remainder before
the Haskell action can remain suspended.

## Acceptance evidence

Use the existing weak and runtime suites with explicit fixture dependencies.
Intentional representatives cover arbitrary keys, independent Java roots,
value/finalizer cycles, association closure, later thunk evaluation, dropped
handles, resurrection, explicit/automatic arbitration, the current GHC exception
handler, suspension and shutdown before entry. Native GHC supplies independent
Haskell expectations. Preserve both backends, both handoff modes and the first
compiled call where relevant.

On Linux, 22 selected JVM checks pass on the retained ordinary Jam package,
with no failures or skips. They cover both handoff modes, both backends where
relevant, first compiled calls, the original GHC automatic-finalizer law,
completed-thunk aliases, cooperative pending handoff, capture release, callback
arbitration and context shutdown. The existing declared fixture producers and
native oracle were reused with unchanged inputs and outputs.

The failure-ownership follow-up passes six selected methods in each Linux
handoff mode: 12 cases, including repeated lifetime controls, with no failures
or skips. One fixture-free regression covers failed pre-start and guest-entry
setup, a failure after the real carrier starts, and no replay after a callback
begins. The original pre-start regression failed on the preceding runtime in
both modes. These injected setup faults do not prove recovery from arbitrary
JVM allocation failures or native allocation inside `noexcept`.

On native Windows, five fixture-free `ManagedWeakTest` methods pass in each
handoff mode: 10 actual cases, zero failures or skips. They cover completed-thunk
aliases and cooperative pending handoff on AST/bytecode, explicit finalization
without forcing opaque states, obsolete capture release before a real finalizer
blocks on platform/Loom hosting, and actual context-close invalidation. The
ordinary pinned package passes complete `verifyJamToolchain` verification.
These Java models do not qualify compiled execution or native GHC fixtures.

The existing `NativeWeak` IO executable matches its complete native-GHC oracle
on the Linux JVM and in a relocated Native Image. Its eight explicit weak rows
and automatic-finalizer result exercise the ordinary IO entry and shutdown path,
including a dropped weak handle and key backreferences in the value and finalizer.
The image runs with only its declared executable, Jam libraries and test input;
it has no source, CBD, GHC or JDK dependency at execution time. The original GHC
finalizer runner is included by the shared Core dependency selector. This check
does not extend the JVM first-compiled-call evidence to Native Image.

The pinned Linux release includes the supplier's root repair, pinning, reusable
weak-registration metadata and SubstrateVM operation-queue lifetime repair.
Twenty-three focused weak/finalizer and Loom lifecycle checks pass across both
handoff modes on this package. Terminal MVar requests release their cell while
preserving repeatable committed results; completed finalizer work releases its
captures after the carrier has joined. Pending operations and failed setup
retain the state they still own.

The unchanged `WeakThreads` program also matches its complete six-line native-GHC
oracle in redirected, relocated Native Image execution. It exercises completed
thread collection, resurrection and a fresh weak lifetime, with each finalizer
running once. The build uses the tracked resource-copy recipe and the normal
published Jam package. No diagnostic queue overlay is required. Execution needs
only the executable and its declared `.jam` libraries and notices, without
source, CBD, GHC or JDK runtime mounts. This qualifies the resource-copy profile;
it does not establish default-intrinsics execution.

Recovery from actual heap exhaustion, macOS execution and remaining Windows
coverage are still open. The older combined Native Image component candidate
passed the unchanged `NativeWeak` executable; its evidence and the newer
`WeakThreads` application pass do not establish all-platform qualification.
Supplier release qualification is
tracked in [Jam #10](https://github.com/ekmett/jam/issues/10).

The ordinary executable runs reuse verified producer inputs, artifact hashes and
Core audits. A separate optional `--verify-artifacts` run exceeded its time bound
while traversing complete Core modules before guest entry. That verification
performance failure remains unresolved; it is not a weak-finalizer deadlock or
a passing verification result.

The automatic law requests one major collection and waits on its finalizer
signal. Its native-GHC oracle, CBDs and audits come from the declared producer.
Use that behavior check when qualifying the actual provider; do not accept a
narrow key representation or assume pending handoff is death merely because a
diagnostic case passed.

A package startup check proves startup. A native image build proves construction.
Neither proves general weak semantics, guest compilation or another platform.
Qualify the actual JVM and relocated native executable separately, reuse
unchanged evidence, and keep heavy image construction out of ordinary commit CI.
