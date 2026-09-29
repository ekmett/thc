# Shared arenas with direct Vector API memory access

This candidate hash-pinned provider overlay implements four `ScopedMemoryAccess` vector
segment wrappers for GraalVM 25.3.4.1. It is not an alternative memory profile.
It retains `VectorSupport.load/loadMasked/store/storeMasked`, native/mapped
addresses and the original scalar shared-arena compiler/close machinery.

Each wrapper acquires the segment's existing `MemorySessionImpl`, performs the
direct operation, and releases in `finally` with a reachability fence. A shared
session's acquire counter prevents `justClose` from winning while an access is
pinned, including an exceptional or suspended fallback. A concurrent close may
throw `IllegalStateException`, as allowed by `Arena.close`; closing after the
release remains deterministic. Closed sessions and confined wrong-thread accesses
still fail. No arena substitution, heap copy or implicit storage promotion is
introduced.

`prepare.sh` verifies both builder JARs and the supplied builder source ZIP. The
JDK 25 ClassFile API replaces only the two public store methods and adds the two
public load methods to the original substitution class. Existing mapping,
private fail-closed scoped-vector bodies and `closeScope0` are retained. Generated
JARs include source/provenance and the pinned toolchain license, and belong in
private build output, not Git.

The separate `SubstrateOptions` patch preserves the original shared/vector
rejection unless the loaded foreign substitution class has the exact generated
implementation digest. Verification uses the loaded class's code origin:
Native Image's module-resource view can return the unpatched original JAR.
Both the wrapper and phase-support class digests are required. The phase-support
patch replaces only its incompatibility predicate; both original guarantees and
all scalar phase construction/configuration remain. Missing, single-component
and mismatched controls are part of qualification. The small native probe checks
real substitutions, races and exceptional release. Its graph test requires real
fixed SIMD memory operations and retained volatile session-count pins before and
after the scalar optimizer, through late lowering and final scheduling. On the
pinned x86-64-v3 target, masked operations can still use the JDK's supported
fallback; the wrappers preserve both intrinsic and fallback lifetime boundaries.
Full application-image and persisted guest AOT qualification remain pending.

The pinned compiler's global `Vectorization=false` option also clears
`OptimizeVectorAPI` and `TargetVectorLowering`. The `intrinsics` recipe therefore
explicitly restores those two keys for hosted and runtime compilation after the
global automatic-vectorization policy. It does not turn that global switch on.
The normal prepared-image recipe installs the paired provider for this profile;
the stock provider still rejects the combination.

Build with `JAVA_HOME` pointing to the pinned toolchain:

```sh
bash nih/native-image/shared-arena-vector/prepare.sh build/shared-arena-vector
bash research/native-image-preparation/shared-arena-vector/check.sh \
  build/shared-arena-vector/checks build/shared-arena-vector/thc-svm-shared-arena-vector-foreign.jar
bash research/native-image-preparation/shared-arena-vector/check-provider.sh \
  build/shared-arena-vector/provider-checks build/shared-arena-vector
bash research/native-image-preparation/shared-arena-vector/probe.sh \
  build/shared-arena-vector/native-probe build/shared-arena-vector
bash research/native-image-preparation/shared-arena-vector/check-native-provider.sh \
  build/shared-arena-vector/native-controls build/shared-arena-vector/native-probe \
  build/shared-arena-vector/provider-checks
```

Run preparation/probes under the checkout's normal build-directory lease. The
native probe requires a fresh output directory and bounds construction to 180
seconds with a 2 GiB builder heap. It keeps `-O2`, actual direct Vector API lowering
and compiler assertions. Only species metadata is hosted-initialized; vector
values and memory accesses execute in the native executable. Its
separate fallback cases use an abstract vector class, not a process-wide
intrinsics-disable option.

The JVM tests inspect and execute the emitted wrappers; the native probe uses
the actual public vector/scoped APIs. Neither establishes a persisted guest AOT
cache. That requires the existing workload's actual store, then a fresh process
load with `engine.Compilation=false`, installed cached-code entry evidence and
zero runtime lowering/compilation submissions.

The pinned provider separately rejects shared sessions in runtime-compiled
scalar methods. This overlay does not change that restriction or claim to solve
all shared-memory guest JIT cases. It changes neither THC's closure checks nor
the installed-code guards and does not supply missing package providers.
