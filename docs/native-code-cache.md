# Experimental selected-Core native code cache

This opt-in workflow compiles a selected exported Core entry **before the run
process starts**, using THC's existing AST lowerer and the pinned Truffle auxiliary
cache provider. It produces two artifacts: a native launcher and a matching code
cache. It is not the ordinary `thc run` path or a general Haskell AOT distribution.

The current admission is synchronous, foreign-free AST code:
exact numeric scalar input proofs, numeric literals, globals, ordinary function
applications, nested closures, `let` bindings, integral literal/default cases, local
joins, saturated boxed constructors and constructor cases. Numeric carriers include
machine words, signed/unsigned 8/16/32-bit integers, `Float#` and `Double#`.
The admitted arithmetic includes `+#`, `-#`, `*#`, existing narrow scalar operations,
floating arithmetic/comparisons and integer/floating conversions. Floating cases
may use defaults, not floating literal alternatives (which GHC Core disallows).
Internal function formals and case binders/results require exact numeric, data or
closure proofs; join results remain numeric. Constructor fields support numeric
carriers, boxed data and ordinary guest closures with exact field descriptors,
including strict function fields, lazy function thunks and lazy data tails.
Recursive local joins use the existing local-loop lowering;
ordinary self recursion uses the existing function loop, prepared before publication.
Ordinary higher-order calls preserve each closure's captured program owner,
including partial applications and lazy function values. Constructor functions
must still be saturated; field values retain their own captured program owners.
Reachable CAF code is prepared without evaluating the CAF;
each load creates a fresh Program, CAF cells and metrics. Unselected definitions
stay unprepared. Unused GHC module/constructor descriptors do not prevent scalar
selection. Prepared code retains only immutable constructor storage descriptors;
each load receives fresh allocation keys, nullary values and optional boxed-value
caches. Constructor matches authenticate that exact load's layout, not just its
name, tag or shared carrier class. Unsupported code fails admission rather than falling back to
runtime lowering. Bytecode, typed aggregates,
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
the existing Core linker. Plain inputs to `run` are signed 64-bit integers; prefix
floating inputs with `f:` for Float or `d:` for Double. The entry's existing host
ABI validates arity, carriers and narrow integer ranges; signed and unsigned results
keep that ABI's widening rules. Arithmetic uses the primitive's ordinary wrapping
or floating-point behavior. The selected entry is fixed in the cache; arguments are
not compiled in. `src/examples/THC/CachedCalls.hs` exercises ordinary out-of-line
guest functions through the same export/store/run commands and argument contract.

`src/examples/THC/CachedWordLoop.hs` adds a word case and recursive local join:

```sh
THC_CORE_OUT="$PWD/build/cached-word-core" \
  bin/export-core.sh src/examples/THC/CachedWordLoop.hs
bin/native-cache store build/word-loop.cache \
  build/cached-word-core/THC.CachedWordLoop.json sumFrom
bin/native-cache run build/word-loop.cache 0 47
# 47
bin/native-cache run build/word-loop.cache 5 -40
# -25
```

This example requires a nonnegative count and adds `1 + ... + count` to its
dynamic seed. Prepared cases use their actual predicates without observed branch
profiles; local joins use predeclared frame carriers and the invoking instance's
metrics. Preparation does not execute branches or loop iterations.
The same module's `countDown` entry exercises ordinary global self recursion:
store it as a separate cache and run it with a nonnegative count. It returns zero
without requiring a base-case training call; loop counters belong to the invoking
program instance, not the preparation context.

`src/examples/THC/CachedList.hs` adds ordinary recursive data and a shared lazy
CAF through the same workflow:

```sh
THC_CORE_OUT="$PWD/build/cached-list-core" \
  bin/export-core.sh src/examples/THC/CachedList.hs
bin/native-cache store build/list.cache \
  build/cached-list-core/THC.CachedList.json sumFrom
bin/native-cache run build/list.cache 5 26
# 47
bin/native-cache run build/list.cache 0 -31
# -25
```

The nonnegative count selects a fresh descending list. Its fold adds to the
dynamic seed and a shared list's sum. Constructor storage metadata is shared
code, while each load owns its list values, CAF cells and lazy tails. No list
elements or CAF bodies are evaluated during preparation.

`src/examples/THC/CachedNumeric.hs` combines an `Int16#`, `Float#` and `Double#`
through a boxed constructor and a shared floating CAF:

```sh
THC_CORE_OUT="$PWD/build/cached-numeric-core" \
  bin/export-core.sh src/examples/THC/CachedNumeric.hs
bin/native-cache store build/numeric.cache \
  build/cached-numeric-core/THC.CachedNumeric.json calculate
bin/native-cache run build/numeric.cache 2 f:1.5 d:21.0
# 47.0
bin/native-cache run build/numeric.cache 32767 f:-0.5 d:16371.5
# -25.0
```

The narrow addition wraps before widening, and the Float addition rounds before
conversion to Double. These are dynamic arguments, not store-time training inputs.

`src/examples/THC/CachedHigherOrder.hs` passes an ordinary guest function into a
recursive fold. Its dynamic offset is retained by a partial application, and the
fold also demands a per-load shared list CAF:

```sh
THC_CORE_OUT="$PWD/build/cached-higher-order-core" \
  bin/export-core.sh -fplugin-opt=THC.Plugin:post-tidy \
  -fplugin-opt=THC.Plugin:unit-qualified src/examples/THC/CachedHigherOrder.hs
bin/native-cache store build/higher-order.cache \
  build/cached-higher-order-core/units/u-main/THC.CachedHigherOrder.json \
  main:THC.CachedHigherOrder.calculate
bin/native-cache run build/higher-order.cache 4 7 0
# 47
bin/native-cache run build/higher-order.cache 3 -10 -10
# -25
```

The first argument is a nonnegative list length. Function arguments are ordinary
THC closures, not foreign callbacks; the CLI entry itself still takes and returns
numeric scalars. Shared code does not share captured values or CAF state between
loads, and calling a retained closure in another context is rejected.

`src/examples/THC/CachedFunctionFields.hs` stores strict and lazy guest functions
inside an ordinary boxed constructor. It folds both functions and leaves a
bottom-valued neighbouring field untouched:

```sh
THC_CORE_OUT="$PWD/build/cached-function-fields-core" \
  bin/export-core.sh -fplugin-opt=THC.Plugin:post-tidy \
  -fplugin-opt=THC.Plugin:unit-qualified src/examples/THC/CachedFunctionFields.hs
bin/native-cache store build/function-fields.cache \
  build/cached-function-fields-core/units/u-main/THC.CachedFunctionFields.json \
  main:THC.CachedFunctionFields.calculate
bin/native-cache run build/function-fields.cache 4 7 0
# 47
bin/native-cache run build/function-fields.cache 3 -10 -10
# -25
```

Strict function fields hold evaluated closures; lazy fields can retain thunks
until demanded. Neither field storage nor shared code transfers a closure's
captured values or CAF ownership to the program invoking it. Constructor matching
still authenticates the exact load's layout.

An existing package manifest may be supplied as `@PACKAGES.json`, with an exact
qualified binding such as `main:THC.CachedCalls.affine`. JSON unit directories and
CBD module entries use the existing lazy readers. Store preparation reads the
selected transitive code dependencies, not unrelated module files or cold bodies,
then closes those readers. The persisted public source contains detached selected
Core: no package capability, input-file path, mapping, debug-reader resource or
runtime demand-loader is retained. Optional compact debug origins are omitted.
Runtime loads therefore need neither the original manifest nor its CBD files.
This route currently takes one package directory, not mixed loose consumers.

Artifact verification is opt-in: append `--verify-artifacts` to `store` to request
the existing manifest/artifact hash and complete touched-module checks. The default
does not add full-file hash scans or decode cold definitions. Structural admission,
strict selected linking and the persisted source's content-derived identity remain
mandatory. See the [CBD format](compact-core-format.md) for conversion and package
metadata; supplying a CBD container alone is not a compiled-code cache.

The build command consumes the checkout's existing `installDist`; it does not
validate that distribution against the source revision. Rerun `installDist`
after source changes and before building the launcher. It reserves an 8 GiB builder heap and
two compiler threads. On a shared host, use the existing build-directory resource
gate. The output is `build/native-image/thc-native-cache`. Set
`THC_NATIVE_CACHE_IMAGE=/absolute/path/to/thc-native-cache` to run another matching
copy, without rebuilding. Existing cache files are never overwritten.

## Load contract and limits

`store` parses and lowers the selected program but does not execute its factory
or guest bodies. It closes the preparation context, then asks the provider to
prepare/compile the targets and persist them. The run wrapper supplies
an `AuxiliaryImageBytes` address-space reservation (cache size plus 1 MiB) at
isolate startup; `Engine.CacheLoad` then performs the single actual load.
Do not substitute `AuxiliaryImagePath`, which would eagerly load it twice.
`run` requires exactly one cached THC source with its content-derived name,
disables guest compilation and rejects cache misses, runtime parsing/lowering,
missing installed targets and interpreter guest entry. The actual cached factory
checks its own target and every prepared dependency/nested target before creating a fresh
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
