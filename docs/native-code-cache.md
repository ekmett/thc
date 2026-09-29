# Experimental native code cache

Compile a selected pure Core entry ahead of time, then run it in a fresh process
with guest JIT compilation disabled. This produces a native launcher and matching
code-cache file. Ordinary `thc run` does not use this workflow.

The cache supports the synchronous AST backend: numeric computation, ordinary
functions and partial applications, lazy data, tuples, sums, vectors and admitted
managed byte-array operations. Each load gets fresh heap state and CAFs. IO,
foreign calls, asynchronous exceptions and the bytecode backend are unsupported.
Pinned/native memory, atomic memory operations and unresolved layouts are also
outside this workflow. See [reusable code](reusable-code.md) for exact admission
limits; supplying a complete library does not imply every operation it uses is
admitted.

The command line accepts numeric arguments and results. Functions may use typed
aggregates internally, but direct tuple/sum/vector host arguments require the
[JVM embedding API](site/embedding.md), not this CLI.

Native IO/package-FFI preparation is under development: the source loader retains
immutable declarations and resolves native functions and exception projectors for
each invoking instance. The experimental `--io-main`/`--shutdown-entry` CLI path
is not yet qualified by a successful provider store and fresh load. JavaScript,
Polyglot and explicit interop calls are rejected during reusable preparation until
their receivers and exception paths are instance-aware; ordinary runtime support
is unchanged. Exception-text inspection uses the invoking instance's genuine
Haskell bridge when the foreign metadata accessor itself fails.

## Build and select a program

Requirements: **Linux AMD64, GraalVM 25.3.4.1 / JDK 25 with its auxiliary-engine
cache provider**, and the ordinary pinned GHC/exporter toolchain. The image uses
the repository's experimental Truffle and Native Image preparation overlays;
this is not a claim about stock Truffle. Start with the README's submodule setup.

The default `intrinsics` image profile retains the vector-intrinsic configuration.
The pinned toolchain cannot combine those intrinsics with deterministic shared
arenas. `THC_NATIVE_IMAGE_VECTOR_PROFILE=resource-copy` selects an experimental
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

### Application-bound runtime image (not a compiled guest cache)

The same experimental image recipe also accepts `executable`. Set
`THC_NATIVE_IMAGE_EXECUTABLE_CONFIG` to a JSON array containing the ordinary
`Main` prefix `["--run-executable", "MODULES", "ENTRY", "SHUTDOWN_ENTRY", "--",
"PROGRAM_NAME"]`, optionally followed by default guest arguments. Set
`THC_NATIVE_IMAGE_EXECUTABLE_NAME` to the desired ELF basename. The binding is
embedded in the image; incoming arguments are appended as opaque guest argv.
The original loader owns argument initialization, IO, shutdown and exit status.
An object with `arguments` containing that array and `properties` containing
string-valued JVM system properties can also bind the THC execution profile.
Native Image runtime-option parsing is disabled in this mode: guest `-D` and
`-X` options are not VM arguments. This experimental recipe fixes a 16 GiB
runtime heap ceiling and two available processors at image build time.
Its builder uses 16 GiB and two compiler threads; compiled-cache mode retains
its separate 8 GiB builder limit.

This produces an application-bound JVM-free runtime, but **lowers guest Core at
runtime**. It does not imply that the guest was AOT-compiled or persisted in a
code cache. Referenced Core/package files and native dependencies remain required
external resources. Use the existing compact encoder for retained loose Core
captures. Native Image
provider/resource-profile requirements still apply.

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
| Unsupported entry or dependency | Use an admitted pure AST entry, or run the program through the ordinary runtime. Preparation does not silently substitute another backend. |
| Missing Core binding | Reacquire the complete selected dependency closure. |
| Cache miss, incompatible image or missing compiled target | Rebuild the launcher and cache with the same supported toolchain and inputs. `run` does not fall back to JIT compilation. |
| Existing cache output | Choose a new path. Files are never overwritten; a failed store may leave an incomplete file to inspect or explicitly remove. |
| Wrong argument/result carrier | Match the selected entry's numeric signature; aggregate CLI values are unsupported. |
