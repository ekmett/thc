# JAM runtime integration

THC is migrating to JAM-patched GraalVM for JVM execution and JAM-patched
SubstrateVM for native executables. The purpose is general `System.Mem.Weak`
semantics, including keys held by Java code and finalizers that can resurrect
their keys. Selecting the collector alone does not implement THC weak pointers.
The current draft uses real `jam.vm.Weak` associations and guest finalizer
carriers. General weak support cannot yet be advertised: completed-thunk key
retargeting fails, public Haskell automatic execution is pending, and upstream
dead-record reclamation remains unresolved.

## Toolchain ownership

The VM, Graal compiler, SubstrateVM, `jam.vm.Weak` API and native collector
libraries form one versioned dependency. `etc/jam-graalvm.json` records the
producer revision, upstream source pins, transport digest and unpacked package
identities. A release/version string alone does not establish compatibility.

The upstream owner is now [ekmett/jam](https://github.com/ekmett/jam), including
its managed-runtime sources under `vm/`. Its GraalVM CI archive contains the
complete JVM and Native Image toolchain. THC retains its currently recorded
package until the consolidated producer and matching runtime checks pass; then
repository, archive and installation identities move together. The new native
adapter filenames use `jam-vm` rather than `jam_vm`; runtime library manifests
continue to determine deployment membership. Durable distribution must preserve
the qualified package bytes beyond temporary CI artifact retention.
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

The initial Linux CI artifact requires glibc 2.38. The older Linux system on
which another JAM package was built does not establish that artifact's minimum
version. Matching macOS and Windows packages have separate recorded identities; runtime
qualification is pending. Both macOS JAM libraries declare macOS 26 as their
minimum deployment version. They cannot support a macOS 15 installation. Short-lived CI artifact URLs are qualification inputs; consumer
CI needs retained, immutable package distribution before migration is published.

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
The currently pinned JAM adapter retains and scans those dead records, so this
lifetime behavior also needs an upstream correction. Finalizer state
retains its actual captures, without an invented reference to the entire value
payload after death.

THC's completed thunk is an indirection to its exact WHNF. Both backends can
replace a local thunk alias with that value. Therefore a registration created
before evaluation must continue to follow its logical key when the wrapper is
no longer strongly referenced. The collector must resolve and retarget this
slot before reclaiming the wrapper. Resolution must not mark the representative,
force guest code, allocate Java roots, or acquire a stopped mutator's monitor.
All other objects and non-success thunk states retain identity semantics.
The agreed upstream API shape is
`registerIndirection(Thunk.class, "state", 2, "value")`: an exact registered
class, volatile int state and Object representative field. This API is
unimplemented. It is a prerequisite for both HotSpot and SubstrateVM; a forcing
shim or create-time unwrapping cannot replace it. The real regression currently
returns dead flag 0 despite the independently live WHNF alias in both handoff
modes.

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

Linux JVM qualification includes real first-compiled calls on both THC backends
and handoff modes. The native publication boundary has also passed its focused
Linux tests. The focused weak integration run passed six registry checks and
retained the failing completed-thunk regression in both handoff modes. The new
public `System.Mem.Weak` automatic-finalizer law and its independent native GHC
oracle have not yet executed. General weak qualification is incomplete; Native
Image runtime qualification remains failed and parked.

A package startup check proves startup. A native image build proves construction.
Neither proves general weak semantics, guest compilation or another platform.
Qualify the actual JVM and relocated native executable separately, reuse
unchanged evidence, and keep heavy image construction out of ordinary commit CI.
