# Supported configurations

Use the patched JDK 25 or GraalVM 25.3.4.1 on macOS arm64, Linux x86_64 or
Windows x86_64. The `vm-2026.10.09-0e36293` GraalVM packages are qualified on
macOS 15.5, Ubuntu 22.04 with glibc 2.35, and Windows 11/Server 2022.
Older packages have different [platform requirements](distribution.md).
Select Jam with
`-XX:+UnlockExperimentalVMOptions -XX:+UseJamGC` and set equal initial and
maximum heap sizes. The [build guide](build.md) gives the toolchain and commands.
Native executables use `native-image --gc=jam` from the patched GraalVM; see
[Native Image](native-image.md) for its separate sizing and pinning rules.

## HotSpot heap and compiler

Jam collects both generations. New objects normally enter young; minor
collections retain young survivors or promote the whole live nursery when it
fits. Major collections compact both generations independently. Java soft,
weak, final and phantom references use the VM's reference processor alongside
the [generalized weak policy](weak-pointers.md).

The current heap uses fixed capacities and stop-the-world collection. It needs
ordinary object headers, eight-byte alignment, normal pages and nonzero-base,
shift-three compressed oops. Each generation, including its guard and copy
reserve, must fit within a 16 GiB domain. Class unloading and CDS heap loading
are disabled.

Interpreter, C1, C2 and the patched Graal compiler record exact old source
slots in Jam's remembered set. Minor collection reads those slots at the safepoint
and follows their current young targets.
The GraalVM build includes patched libgraal. The VM rejects a compiler that
does not recognize Jam before it can install Java code. Other operating
systems and instruction sets still need validation.

## Guest API

Use [jam.vm.Weak](thc-integration.md) to register weak associations and install
JVM runnables. Any caller can pump the shared queue. There is no automatic
finalizer thread or wakeup notification; the caller supplies the pump schedule.

Tokens are local to a JVM or Native Image isolate. Retired registry slots are
recycled; generation-tagged tokens reject stale handles. Storage follows the
concurrent high-water mark rather than lifetime registrations. Registration
failure is transactional and the Java boundary throws `OutOfMemoryError`.
This does not promise recovery from arbitrary VM exhaustion. See the
[registration contract](weak-pointers.md#claiming-a-finalizer).

Use the [packaged runtime](build.md#packaging) when moving an installation
out of its build checkout.

## Shipped scope and limits

Language-owned lifted weak handoffs and THC integration shipped in
[Jam #7](https://github.com/ekmett/jam/issues/7) and
[THC #1200](https://github.com/ekmett/thc/pull/1200), including claim ownership,
capture release, resurrection and one-shot finalization. The
[handoff measurement](lifted-weak-cost.md) records retention and latency costs.
These checks do not establish every platform/backend/application combination
or arbitrary exhausted-VM successor-installation recovery. The language owns
its shutdown cleanup and callback scheduling; Jam makes no automatic
finalize-everything-on-exit promise.

The optional `jam.vm.Candidate` API is included in the same three-platform
release, with focused HotSpot/GraalVM and Native Image qualification. It does
not capture stacks or supply a language scheduler; see
[suspended owners](thc-integration.md#suspended-owners).

HotSpot's GC management bean reports completed collections, elapsed collection
time and the latest collection's memory usage. Packages through
`vm-2026.10.09-0e36293` predate the [#43 accounting fix](https://github.com/ekmett/jam/issues/43)
and report zero counts; use GC logs when measuring those older runtimes.

Whole-nursery promotion is intentional. Selective promotion is a non-goal for
now. Indexed weak processing, adaptive capacities and parallel VM scanning are
possible future optimizations, not missing parts of the agreed weak contract.
