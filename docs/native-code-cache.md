# Experimental selected-Core native code cache

This opt-in workflow compiles a selected exported Core entry **before the run
process starts**, using THC's existing AST lowerer and the pinned Truffle auxiliary
cache provider. It produces two artifacts: a native launcher and a matching code
cache. It is not the ordinary `thc run` path or a general Haskell AOT distribution.

The current admission is synchronous, constructor- and foreign-free AST code:
explicit machine-word input proofs, integer literals, globals, and `+#`, `-#`,
`*#` arithmetic. Reachable CAF code is prepared without evaluating the CAF;
each load creates a fresh Program, CAF cells and metrics. Unselected definitions
stay unprepared. Unused GHC module/constructor descriptors do not prevent scalar
selection; reachable constructor code remains rejected and no constructor layout
is retained in the prepared code. Unsupported code fails admission rather than falling back to
runtime lowering. Bytecode, general function application, typed aggregates,
async delivery, IO and FFI are not admitted by this workflow.

## Build and select a program

Requirements: **Linux AMD64, GraalVM 25.3.4.1 / JDK 25 with its auxiliary-engine
cache provider**, and the ordinary pinned GHC/exporter toolchain. The image uses
the repository's experimental Truffle and three Native Image preparation overlays;
this is not a claim about stock Truffle. Start with the README's submodule setup.

```sh
export JAVA_HOME=/path/to/graalvm-25.3.4.1
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --max-workers=2 installDist
bin/native-cache build

# Existing exporter, real Haskell source, no guest training calls:
THC_CORE_OUT="$PWD/build/cached-scalar-core" \
  bin/export-core.sh src/examples/THC/CachedScalar.hs
bin/native-cache store build/affine.cache \
  build/cached-scalar-core/THC.CachedScalar.json affine
bin/native-cache run build/affine.cache 6 7 5
# 47
bin/native-cache run build/affine.cache -3 9 2
# -25
```

Use the actual JSON path emitted by the exporter. Multiple self-contained Core
JSON files may be supplied as one comma-separated argument. Entry selection uses
the existing Core linker. Inputs to `run` are signed 64-bit integers, with the
entry's existing host ABI validating arity/carriers; arithmetic uses ordinary
machine-word overflow. The selected entry is fixed in the cache; arguments are
not compiled in. Deferred package and CBD loading remain outside the prepared
code admission; this command does not pretend that packaging Core alone is AOT.

The build command expects the current checkout's `installDist`; it does not
silently reuse another revision's runtime. It reserves an 8 GiB builder heap and
two compiler threads. On a shared host, use the existing build-directory resource
gate. The output is `build/native-image/thc-native-cache`. Set
`THC_NATIVE_CACHE_IMAGE=/absolute/path/to/thc-native-cache` to run another matching
copy, without rebuilding. Existing cache files are never overwritten.

## Load contract and limits

`store` parses and lowers the selected program but does not execute its factory
or guest bodies. It closes the preparation context, then asks the provider to
prepare/compile the targets and persist them. The run wrapper supplies
`-XX:AuxiliaryImagePath=...` at isolate startup, before `Engine.CacheLoad`.
`run` requires exactly one cached THC source with its content-derived name,
disables guest compilation and rejects cache misses, runtime parsing/lowering,
missing installed targets and interpreter guest entry. The actual cached factory
checks its own target and its prepared dependency targets before creating a fresh
instance. No private polyglot `Source` access or copied polyglot module is used.
Results go to stdout; cache identity/status goes to stderr.

The auxiliary cache contains machine code and is **trusted executable input**, not
a sandboxed interchange format. Keep the launcher and cache together; the
provider's build, CPU-feature and compatibility checks remain enabled. A missing,
incompatible or invalid cache fails closed. There is no portable-cache promise
across toolchains, runtime revisions or unsupported CPUs. Each CLI run uses a
fresh process and context; the provider permits only one auxiliary image per
process. Failed stores may leave an incomplete file: preserve or explicitly
remove that file before a new store, rather than reusing it.

On this exact AMD64 toolchain the recipe retains the `x86-64-v3` instruction
baseline and **adds** `-H:CPUFeatures=HT`. HT is a topology bit, not an instruction
requirement; the option normalizes its baseline across heterogeneous cores.
It does not remove any required instruction feature or change the installer's
compatibility comparison. Other platforms/toolchain versions are rejected by
this experimental recipe rather than receiving an AMD64-only option.

An exception may deoptimize compiled code under stock exception behavior. This
workflow does not patch exception retention or promise later compiled reuse after
a throw. The separate prepared-code ownership tests cover shared/cached failure
identity and untouched lazy definitions; a successful image build alone does not
establish selected-program cache execution.
