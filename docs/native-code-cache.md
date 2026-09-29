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

## Build and select a program

Requirements: **Linux AMD64, GraalVM 25.3.4.1 / JDK 25 with its auxiliary-engine
cache provider**, and the ordinary pinned GHC/exporter toolchain. The image uses
the repository's experimental Truffle and Native Image preparation overlays;
this is not a claim about stock Truffle. Start with the README's submodule setup.

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
