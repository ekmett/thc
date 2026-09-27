# Experimental Native Image graph preparation

This directory preserves the reproducible preparation tools for investigating
guest runtime compilation in Native Image. It is not the working
[pure interpreter recipe](../../docs/native-image-feasibility.md), a shipping
distribution, or a demonstrated guest-JIT/AOT pipeline. Compiler assertions and
blocklist checks remain enabled; a failed build must remain a failure.

## Reproduction

Select GraalVM 25.3.4.1 / JDK 25 with `JAVA_HOME`, build `installDist` from the
source revision being investigated, and use that checkout's installed JARs:

```sh
./gradlew --max-workers=2 installDist
bash research/native-image-preparation/prepared-image.sh "$PWD" prepare-only
bash research/native-image-preparation/prepared-image.sh "$PWD" build
```

On a shared host, wrap each command in its existing build-directory lease; the
image build also needs the host's shared capture/build resource lease. The script
does not acquire a host-specific lock itself. Do not run duplicate image builders.

`prepare-only` compiles the included inventory tool and writes four sorted class
lists plus `build/native-image/reproduction-inventory/prepared-initialization.args`.
It does not build an image. `build` prepares the same inventory and invokes Native
Image with an 8 GiB heap and two compiler threads, writing the experimental
`build/native-image/thc-reproduced-prepared` executable if construction succeeds.
The argument-file format avoids the host's single-command-argument length limit.

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

## Inventory contract

The script combines the existing
[pure inventory](../../scripts/native-image/pure-initialization.txt), four
generated categories, and the explicit
[additional inventory](prepared-initialization.txt). It excludes LLVM/NFI JARs
just as the pure probe does. These additions remain separate from the public
pure recipe.

`ClassInitializationInventory.java` uses the pinned JDK ClassFile API, which is
why this diagnostic is Java rather than handwritten runtime Kotlin. It reads
class bytes without loading or executing THC initializers. The categories are:

- Classes whose inspected hierarchy has no static initializer.
- Holders that create only an exactly checked fieldless companion.
- Exactly checked fieldless marker or generated non-adoptable operation singletons.
- Restricted metadata enums and their compiler-generated switch tables.

The hierarchy check relies on the pinned Truffle package initialization contract;
the enum check also recognizes specific Kotlin metadata helpers and two inspected
THC enum dependencies. This is a toolchain-specific investigation, not a general
static-initializer safety verifier. Re-audit those assumptions when either the
toolchain or the matched source shapes change. The extra inventory is individually
reviewed metadata, not permission to initialize a package or new native owners.

The manual Windows additions are:

```diff
+thc.runtime.WindowsCodePages
+thc.runtime.WindowsLibdwFinalizers
```

The `WindowsCodePages` declaring-class initializer creates a fieldless companion and a private
`Object` ordering lock. `WindowsCodePages$Abi` and `$Api` contain native resources
and are not added. Context instances and their carrier-local error state remain
runtime-owned. Preparing the companion's class alone does not execute the
declaring holder's initializer; the holder is the relevant constant provenance.

`WindowsLibdwFinalizers` initializes its singleton and an unforced lazy lookup.
Preparing that metadata does not extract or load a DLL or acquire native handles;
those operations remain deferred until runtime.

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
