# Experimental Native Image graph preparation

The opt-in [selected-Core native code cache](../../docs/native-code-cache.md)
adds a usable `bin/native-cache build/store/run` workflow on the existing AST
preparation path. Its `cache` recipe mode is distinct from the `thc.Main` runtime
JIT image below; it has explicit platform/toolchain and admitted-Core limits.

This directory preserves the reproducible preparation tools for investigating
guest runtime compilation in Native Image. It is separate from the working
[pure interpreter recipe](../../docs/native-image-feasibility.md) and is not a
shipping distribution. Experimental scalar guest JIT was demonstrated at source
`0e8f7f4da00fbe8b679bf443bbdb1ab2472a6efd` with the three overlays below and the
CLI's declared 40 precompile calls, including immediate postcompile target
identity and validity checks. Other source/toolchain compositions require their
own validation. This is not guest AOT: the launcher still lowers and compiles
guest code after launch. Compiler assertions and blocklist checks remain enabled;
a failed build must remain a failure.

The separate `executable` mode now prepares a captured application's AST factory
during Truffle context preinitialization and releases the selected Core bodies.
Its JVM ownership/absence-of-runtime-lowering checks pass. Linux images execute
checked IO/PAP and Unicode Text applications with their bound CBD inputs unavailable;
the Text image retains ordinary Sulong/NFI and native resources. Executable capture
also selects and embeds non-glibc package shared libraries using the image host's
loader and pinned `llvm-readobj`. Runtime NFI owns their loading and constructors.
These saved factories execute through the AST runtime, not compiled guest machine
code. See the
[application-bound recipe](../../docs/native-code-cache.md#application-bound-prepared-image)
and [qualification limits](../../docs/native-image-feasibility.md#preinitialized-runtime-state).

## Reproduction

The supported experimental entry point is `bin/native-runtime`. After building
`installDist` with the pinned toolchain, it selects the checked compiler overlays
and the `resource-copy` vector profile, then invokes the recipe below:

```sh
bin/native-runtime build
THC_BACKEND=ast bin/native-runtime check build/native-image/check-ast \
  "$CBD_MODULES" "$ENTRY" "$INPUT" "$EXPECTED"
```

Use an existing fixture's CBD modules or compact package manifest and independent
expected integer result. `check` requires a fresh output directory and retains
the executable hash, command, status, stdout and stderr. It runs `Main --compile`,
whose setup precedes installation and whose first installed call must enter
compiled code with the same valid targets; there is no post-install retry.
`run` forwards ordinary Main arguments unchanged. `THC_BACKEND=bytecode` selects
the other backend in the same image. This small image excludes LLVM/NFI package
FFI. The default profile preserves shared arenas and full vector semantics using
the documented copying vector-memory fallback, not generic FFI copying. Explicit
`THC_NATIVE_IMAGE_VECTOR_PROFILE=intrinsics` selects a separate image profile
with the [paired shared-arena/vector provider](../../nih/native-image/shared-arena-vector/README.md).
It restores direct API lowering after the global automatic-vectorization policy
and retains the original scalar optimizer and close machinery. The runtime
wrapper's resource-copy default is unchanged.

The wrapper's argument/result/status plumbing has a builder-free regression:

```sh
java research/native-image-preparation/NativeRuntimeTest.java "$PWD" "$(mktemp -d)"
```

That regression uses a stub process and is not native execution evidence. The
`check` command must also be run against the actual built native executable.

Select GraalVM 25.3.4.1 / JDK 25 with `JAVA_HOME`, build `installDist` from the
source revision being investigated, and use that checkout's installed JARs:

```sh
./gradlew --max-workers=2 installDist
bash research/native-image-preparation/prepared-image.sh "$PWD" prepare-only
THC_NATIVE_IMAGE_DEOPT_LOOP_STAMPS=1 \
THC_NATIVE_IMAGE_RUNTIME_SNIPPETS=1 \
THC_NATIVE_IMAGE_RUNTIME_SIMULATED_FOLDS=1 \
  bash research/native-image-preparation/prepared-image.sh "$PWD" build
```

These are the overlay choices used by the demonstrated preparation, not a
guarantee that another revision builds or compiles guest code. Omitting an opt-in
retains its original pinned builder behavior for independent diagnostics.

On a shared host, wrap each command in its existing build-directory lease and
check available host capacity. The script does not acquire a host-specific lock
itself. Do not run duplicate image builders.

`THC_NATIVE_IMAGE_BUILDER_HEAP=16g` selects the qualified larger builder budget
when the default generic/cache 8 GiB is insufficient. Only `8g` and `16g` are
accepted. Bound-executable mode defaults to 16 GiB. The choice is recorded in
`reproduction-inventory/builder-heap.args` and changes only builder `-J-Xmx`,
not the produced executable's runtime heap, compilation limits or two compiler
threads. Check host headroom before selecting the larger budget.

`prepare-only` compiles the included inventory tool and writes four sorted class
lists plus `build/native-image/reproduction-inventory/prepared-initialization.args`.
It does not build an image. `build` prepares the same inventory and invokes Native
Image with an 8 GiB heap and two compiler threads, writing the experimental
`build/native-image/thc-reproduced-prepared` executable if construction succeeds.
The argument-file format avoids the host's single-command-argument length limit.
Empty generated categories are skipped. The combined list must contain only
nonempty class names: Native Image treats an empty class/package entry as a
request to initialize the whole hierarchy. Named entries keep their order.

The argument-composition regression uses isolated Java metadata fixtures and the
real `prepare-only` path, without invoking the image builder:

```sh
java research/native-image-preparation/InitializationArgumentsTest.java \
  research/native-image-preparation "$(mktemp -d)"
```

The classpath-selection regression executes both recipes' actual selection
loops against fixture JAR names, without an image builder. It checks that stock
and patched Sulong artifacts remain excluded while ordinary Java JARs remain:

```sh
java research/native-image-preparation/ClasspathSelectionTest.java \
  "$PWD" "$(mktemp -d)"
```

Retain the source revision, installed-JAR hashes, generated inventories, exact
argument file, command, output and exit status for each attempt. Reusing a JAR
from an older revision is not a test of current source. Rebuilt inventory files
must be compared byte-for-byte when checking recipe parity. Keep run evidence
outside tracked source; this directory contains tools, not benchmark results.

Optional `THC_NATIVE_IMAGE_METHOD_FILTER` enables a filtered Graal graph dump;
`THC_NATIVE_IMAGE_DUMP_PATH` selects its directory. Leave the filter unset for a
normal build. Neither option changes compilation acceptance checks.

The optional [deoptimization loop-stamp overlay](deopt-loop-stamps/README.md)
is enabled only with `THC_NATIVE_IMAGE_DEOPT_LOOP_STAMPS=1`. It recompiles one
hash-pinned builder source family into an isolated module patch and runs its
focused controls before image construction; the installed toolchain remains
unchanged. This experiment is separate from initialization inventory preparation.

The independent [runtime snippet-provider overlay](runtime-snippet-providers/README.md)
uses `THC_NATIVE_IMAGE_RUNTIME_SNIPPETS=1` to bind runtime parsing to its existing
runtime replacement cache. It preserves complete option sets and other providers.
The [simulated-field folding overlay](runtime-simulated-folds/README.md) uses
`THC_NATIVE_IMAGE_RUNTIME_SIMULATED_FOLDS=1` to retain a field load when runtime
graph encoding cannot represent its simulated constant without a hosted object.
These independent opt-ins can be combined; none changes the pure-interpreter recipe.

`THC_NATIVE_IMAGE_PROCESS_IDENTITY=1` additionally registers the single FFM
downcall signature `jint()` used by the existing POSIX `getpid`/`geteuid` path.
It does not register arbitrary foreign calls, enable LLVM/NFI, or initialize
libc handles during image construction. `ProcessIdentity.Libc` still resolves
its handles at runtime after the existing native-access and host-ABI checks.
`prepare-only` records the selected option in `reproduction-inventory/foreign.args`;
the default writes an empty file, including after a previous opt-in. The normal
argument-composition test also checks this selection and rejection of malformed
opt-in values. This finite registration is not a claim of general native FFI
or Windows support.

## Inventory contract

The script combines the existing
[pure inventory](../../bin/native-image/pure-initialization.txt), four
generated categories, and the explicit
[additional inventory](prepared-initialization.txt). It excludes LLVM/NFI JARs
just as the pure probe does. These additions remain separate from the public
pure recipe.

`ClassInitializationInventory.java` uses the pinned JDK ClassFile API to read
class bytes without loading or executing THC initializers. It recognizes
classes without static initializers, exactly checked fieldless markers and
singletons, restricted enum metadata, and synthetic enum-switch holders.

The switch-holder pass runs last and depends only on enums already approved by
the preceding inventories. It proves the entire javac initializer shape: only
its own final synthetic `int[]` fields, array allocations sized by an enum's
checked `values()` clone, positive-literal stores indexed by genuine enum
constants' ordinals, and exact forward `NoSuchFieldError` handlers. Additional
calls, foreign writes, changed handlers and unapproved enum dependencies fail
closed. It neither executes initializers nor adds enum dependencies. Existing
explicit entries remain unchanged; the generated list adds only missing holders.
New compiler shapes still need review rather than broader automatic admission.

These checks depend on the pinned Truffle initialization contract and inspected
bytecode shapes. Re-audit them when the toolchain or matched source changes.
The explicit additional inventory contains individually reviewed metadata;
it does not authorize initialization of whole packages, native resources or
context-owned state.

`PreparedInitializationFeature` applies that unchanged finite inventory through
Native Image's `RuntimeClassInitialization` API during setup, after Truffle's
resource-provider registration. Applying the inventory eagerly on the command
line can populate a language cache before optional resources are registered;
the preinitialized engine then retains the incomplete cache. The feature resolves
each actual class without initializing it first, so package names cannot expand
the policy. It does not clear or modify Truffle caches or construct guest contexts.

The `HostArrayGen` and `HostReferenceGen` export families are explicit generated
host-ABI metadata. Their declaring initializers resolve the existing dynamic
dispatch library and register literal receiver/library class descriptors.
`HostReference`'s export and library class initializers only record assertion
status; its cached `HostDispatch` remains lazily created by ordinary execution.
`HostArray`'s export additionally creates two fieldless, unadoptable library
singletons. Their constructors only delegate to `InteropLibrary`. These eight
entries do not construct a host receiver, guest value, context or dispatch node.
They follow the existing explicit export-family preparation contract, without
adding a general generated-class recognizer.

The `InteropFailureGen` and `KindGen` families follow the same explicit contract:
their declaring initializers resolve the dynamic-dispatch factory and register
literal receiver/library descriptors. Each export creates only fieldless,
unadoptable cached/uncached libraries whose constructors delegate to
`InteropLibrary`; the remaining initializer state is assertion flags. These
eight entries do not construct an `InteropFailure`, its original protocol
exception, a `Kind` receiver, a context or a native handle. The receiver classes
remain covered by the existing stateless inventory, with no new receiver policy.

`SavedGuestContinuations` and `TupleDestination` each initialize only a private
array of literal production carrier classes. They construct no carrier, frame,
context or thread. Resolving these class literals does not run their initializers.
The explicit tuple-family entries also retain its eight previously stateless
subclasses, which have no own initializer, static state or interfaces. The
generated stateless-hierarchy rule deliberately rejects them once their parent
has an initializer; it is not weakened to admit arbitrary class-array holders.
Preparing nested destination classes does not initialize their enclosing roots
or STM implementation. Ordinary per-instance execution and ownership stay unchanged.

`TargetLayout` retains the validated GHC ABI metadata used by prepared package
stack operations. Its initializer creates only literal field/source-name
collections and closure tags; target checks remain in layout parsing. The lazy
Windows source-catalog cache starts null. Initialization performs no host probe,
resource read, native allocation or context creation.

The manual Windows additions are:

```diff
+thc.runtime.WindowsCodePages
```

The Java `WindowsCodePages` declaring-class initializer creates a private `Object`
ordering lock and an empty `AbiValues` holder. The holder's constructor does not
read its ABI resource; that lookup remains lazy. `WindowsCodePages$Api` contains
native resources and is not added. Context instances and their carrier-local
error state remain runtime-owned. The declaring holder is the relevant constant
provenance.

### Finite Java metadata compatibility

The explicit inventory also restores individually inspected metadata owners
whose Java collection calls lie outside the existing restricted enum recognizer.
This is a finite list, not admission of arbitrary Java helpers or packages.

| Owners in `thc.runtime` | Initialization closure |
| --- | --- |
| `ArrayOp`, `ByteArrayOp`, `CompactImageOp`, `MVarOp`, `MutVarOp`, `SmallArrayOp`, `WeakOp` | Literal operation/role/result strings, primitive flags and `List.of` descriptors; constructors only store metadata. |
| `EnvironmentOp`, `ManagedFileOp`, `NativeAllocationOp`, `OriginalStackInfoOp`, `PolyglotOp`, `ProcessOp`, `ProcessSignalOp`, `RtsArgumentsOp`, `RtsShutdownOp`, `STMOp`, `StringRtsOp` | Literal declaration strings/nulls and fresh argument arrays, using `Arrays.asList`, `Collections.singletonList` or `Collections.unmodifiableList`; no foreign operation or runtime owner is created. Lists are not uniformly claimed immutable. |
| `GcForeignOp`, `RtsDiagnosticOp`, `RtsEventForeignOp`, `RuntimeServiceCall` | Literal foreign/reserved-call descriptors and private argument lists. Collection, clock, thread, native-service and reporting effects occur only in ordinary operation methods, not initialization. |
| `NarrowInteger` | Six literal width/sign/representation descriptors and primitive `Byte.TYPE`, `Short.TYPE`, `Integer.TYPE` mirrors. `fromRep` is only a literal-string selection among those values. |
| `OriginalStdioOp` | Literal foreign declarations and private argument arrays wrapped unmodifiable, cached `NarrowInteger.fromRep` results, and four fixed ASCII regular expressions compiled without flags. No host ABI, resource or function lookup is executed. |
| `PinnedMemoryOp` | Literal descriptor lists plus previously audited `ManagedAddressRead` enum constants. That dependency selects native-target byte order and narrow-carrier metadata, not an address or native resource. |
| `CompactOp` | Seven literal descriptors and a three-string array naming existing exception declarations. Initialization neither creates a compact region nor enters an exception value. The array is ordinary mutable metadata. |

The exact javac switch-table names are listed individually in
`prepared-initialization.txt`. Each audited holder has only static final int-array
fields, its class initializer, enum `values()`/`ordinal()` calls, assignments and
`NoSuchFieldError` compatibility handlers. Their enum dependencies are either
in the restricted generated inventory or in the audited table above. Initializing
one of these separate classes does not initialize its enclosing runtime class.
The arrays remain ordinary mutable ordinal tables; they are not runtime profiles.
The current Java lowering source emits `BytecodeProgram$3` as its ordinal-table
holder; `$2` is a different anonymous class. The table also references the pinned
Truffle `FrameSlotKind` enum's values and ordinals, without creating frames.

Names and initializer/constructor closures must be re-audited when source or
compiler shapes change. Do not substitute a wildcard, general helper recognizer,
or missing-constant fallback if a synthetic class disappears. Initializing this
metadata does not initialize contexts, carrier threads, native libraries or
service state. Existing byte-order/VarHandle entries remain tied to the build
target platform, not a cross-target preparation claim.

## Limits and interpretation

Successful inventory generation proves neither image construction nor guest
compilation. A linked interpreter still needs an explicit guest compilation,
valid installed targets, and an immediate first installed call with the normal
checks before claiming guest JIT. Packaging Core does not establish guest AOT.
Sulong, native-resource lifecycle and full executable startup/shutdown remain
separate work.

An analysis error's final constructor/assertion report is not necessarily its
original cause: the pinned error callback checks runtime compilation constraints
before describing the original analysis throwable. An `Unknown` call trace alone
does not justify changing node constructors or relaxing compiler assertions.
