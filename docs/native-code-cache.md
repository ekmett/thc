# Experimental native code cache

Compile a selected Core entry ahead of time, then run it in a fresh process
with guest JIT compilation disabled. This produces a native launcher and matching
code-cache file. Ordinary `thc run` does not use this workflow.

Image builds disable automatic vectorization for both image code and the runtime
compiler by default, while retaining explicit Vector API intrinsics. Set
`THC_NATIVE_IMAGE_AUTOVECTORIZE=true` when building the native launcher to enable
automatic vectorization in both compilers. Use matching settings when comparing
JVM and native-code-cache runs.

Reusable AST preparation uses ordinary lowering and fresh per-load heap state,
globals and CAFs. Immutable initializer code and declarations can be shared;
execution resolves the invoking instance's constructor, native, receiver and
exception-bridge authority. See [reusable code](reusable-code.md) for the owning
contracts and ordinary runtime limitations. The bytecode backend has no prepared
code provider in this workflow.

Persisted Native Image execution is qualified only for the existing checked
inputs and pinned provider. JVM preparation or execution of another runtime
operation does not establish a successful provider store and fresh native load.

The command line accepts numeric arguments and results. Functions may use typed
aggregates internally, but direct tuple/sum/vector host arguments require the
[JVM embedding API](site/embedding.md), not this CLI.

The experimental `--io-main`/`--shutdown-entry` CLI path is not yet qualified by
a successful provider store and fresh load. JavaScript and Polyglot calls use
invocation-owned receivers and exception bridges in prepared AST execution;
persisting their foreign-language/provider state requires separate qualification.
Exception-text inspection uses the invoking instance's genuine Haskell bridge
when a foreign metadata accessor itself fails.

## Build and select a program

Requirements: **Linux AMD64, GraalVM 25.3.4.1 / JDK 25 with its auxiliary-engine
cache provider**, and the ordinary pinned GHC/exporter toolchain. The image uses
the repository's experimental Truffle and Native Image preparation overlays;
this is not a claim about stock Truffle. Start with the README's submodule setup.

The default `intrinsics` image profile explicitly restores direct Vector API
lowering after the automatic-vectorization policy and installs the
[hash-pinned shared-arena provider](../nih/native-image/shared-arena-vector/README.md).
The stock pinned toolchain rejects deterministic shared arenas with Vector API
support. The paired provider preserves session pins, original scalar scope
optimization and deterministic close. The `run` wrapper reserves auxiliary-image
address space before `Engine.CacheLoad`. Embedding launchers must also set
`-XX:AuxiliaryImageBytes` at startup; the wrapper reserves the cache size plus 1 MiB.
`THC_NATIVE_IMAGE_VECTOR_PROFILE=resource-copy` selects an experimental
separate `thc-native-cache-resource-copy` image: shared arenas remain enabled,
vector memory and bit reinterpretation use typed primitive-array bulk copies,
and arithmetic uses the JDK Vector API's non-intrinsic fallback. Use the same
environment setting for `build`, `store` and `run`. This has additional copies and
allocations and does not promise SIMD performance. It does not change ordinary
JVM execution or replace shared arenas with automatic/confined lifetimes.
Full provider/cache qualification of this profile is still in progress; it is
not a claim that arbitrary newer raw-vector operations or IO programs are ready.

For example, save this pure numeric entry as `Affine.hs`:

```haskell
{-# LANGUAGE MagicHash #-}
module Affine (affine) where
import GHC.Exts (Int#, (+#), (*#))

affine :: Int# -> Int# -> Int# -> Int#
affine x scale offset = x *# scale +# offset
```

```sh
export JAVA_HOME=/path/to/graalvm-25.3.4.1
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --max-workers=2 installDist
bin/native-cache build

THC_CORE_OUT="$PWD/build/affine-core" \
  bin/export-core.sh Affine.hs
bin/native-cache store build/affine.cache \
  build/affine-core/Affine.json affine
bin/native-cache run build/affine.cache 6 7 5
# 47
bin/native-cache run build/affine.cache -3 9 2
# -25
```

Use the JSON path actually emitted by the exporter. Several self-contained Core
files may be supplied as one comma-separated argument. Alternatively supply one
package manifest as `@PACKAGES.json` and select its exact qualified entry, such as
`main:Affine.affine`. The package route does not accept extra loose consumer files.
Provide complete selected dependencies; interface fragments cannot replace a
missing module. The stored cache no longer needs those source files at run time.

Plain inputs are signed 64-bit integers. Prefix Float inputs with `f:` and Double
inputs with `d:`. The selected entry checks arity and numeric carrier ranges;
arithmetic retains its ordinary width and floating-point behavior. Results go to
stdout; cache status and errors go to stderr.

Append `--verify-artifacts` to `store` to request manifest/artifact hash verification
and complete touched-module checks. Supported representation and linkage checks
always apply. A [compact Core file](compact-core-format.md) is source input, not
itself a compiled-code cache.

## Rebuild, compatibility and errors

### Application-bound prepared image

The same experimental image recipe also accepts `executable`. Set
`THC_NATIVE_IMAGE_EXECUTABLE_CONFIG` to a JSON array containing the ordinary
`Main` prefix `["--run-executable", "MODULES", "ENTRY", "SHUTDOWN_ENTRY", "--",
"PROGRAM_NAME"]`, optionally followed by default guest arguments. Set
`THC_NATIVE_IMAGE_EXECUTABLE_NAME` to the desired ELF basename. The binding is
consumed during image construction; incoming arguments are appended as opaque
guest argv. The ordinary launcher owns argument initialization, IO, shutdown and
exit status.
An object with `arguments` containing that array and `properties` containing
string-valued JVM system properties can also bind the THC execution profile.
Native Image runtime-option parsing is disabled in this mode: guest `-D` and
`-X` options are not VM arguments. This experimental recipe fixes a 16 GiB
runtime heap ceiling and two available processors at image build time.
Its builder uses 16 GiB and two compiler threads; compiled-cache mode retains
its separate 8 GiB builder limit.

The hosted feature captures the reachable entry and shutdown Core and prepares
its synchronous AST factory through Truffle context preinitialization. Preparation
uses one worker, evaluates no guest body, and releases the Core after lowering.
At startup the same language receives fresh runtime state and the saved factory
creates fresh guest values. A missing factory or different language is an error;
this mode does not fall back to runtime Core loading or accept external sources.

The isolated JVM regression executes after deleting its CBD/manifest inputs with
runtime lowering disabled. Linux images also execute checked IO/PAP and Unicode
`Text.reverse` applications with the bound CBD directory unavailable. The Text
image retains ordinary Sulong/NFI and native resources. These Linux executable
checks use `THC_NATIVE_IMAGE_VECTOR_PROFILE=resource-copy`; select that profile
explicitly when reproducing them. The default `intrinsics` profile requires the
separately hash-checked Vector API overlay and has different prerequisites. See the
[exact qualification boundary](native-image-feasibility.md#preinitialized-runtime-state).
Build inputs use a compact package manifest selected with `@packages.json`, not
loose CBD paths. The saved factory still executes through the AST runtime.

For Linux x86-64 executables, the hosted feature also captures the transitive
shared-library dependencies of retained package companions. The image host's glibc
loader selects providers; pinned `llvm-readobj` (or `THC_LLVM_READOBJ`) checks ELF
metadata. This records image-build selection, not the original acquisition link.
Provider names, paths and SHA-256 hashes, inspection tools and the system ABI
libraries appear in `reproduction-inventory/native-libraries.json`. The recipe
adds provider bytes to detached metadata without rewriting CBD archives.

At runtime the existing context-owned NFI loader extracts these providers and
loads them in dependency order before the package companion. Constructors run
there; image preparation retains no native handles. Same-name providers must have
identical bytes. The glibc ABI family remains supplied by the deployment system;
GMP is captured even when installed under `/lib`. Missing providers, inconsistent
SONAMEs, cyclic non-system dependencies, path-bearing `DT_NEEDED`, loader
indirections and companions requiring their original `$ORIGIN` directory are
rejected. Providers that load additional files themselves still need those files.
This is shared-library embedding, not static linking or a general clean-machine
deployment guarantee; the image's own system dependencies remain external.

### Compiled-cache compatibility

`bin/native-cache build` uses the existing `installDist` output, so rerun
`installDist` after source changes. The build uses an 8 GiB heap and two compiler
threads. Its output is `build/native-image/thc-native-cache`; set
`THC_NATIVE_CACHE_IMAGE=/absolute/path/to/thc-native-cache` to select another
matching launcher.

Keep the launcher and cache together. The cache is **trusted machine code**, not
a sandboxed interchange format. The provider checks its build and CPU compatibility;
the current recipe targets Linux AMD64 with the `x86-64-v3` instruction baseline.
Caches are not portable across arbitrary runtime/toolchain revisions or CPUs.
Each CLI run starts a fresh process and permits one cache per process.

| Failure | Action |
| --- | --- |
| Unsupported entry or dependency | Check the ordinary lowering/runtime limitation or missing proof reported for this entry. Preparation does not silently substitute another backend. |
| Missing Core binding | Reacquire the complete selected dependency closure. |
| Cache miss, incompatible image or missing compiled target | Rebuild the launcher and cache with the same supported toolchain and inputs. `run` does not fall back to JIT compilation. |
| Existing cache output | Choose a new path. Files are never overwritten; a failed store may leave an incomplete file to inspect or explicitly remove. |
| Wrong argument/result carrier | Match the selected entry's numeric signature; aggregate CLI values are unsupported. |
