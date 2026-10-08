# Architecture

THC uses GHC 9.14.1 as its Haskell frontend and executes optimized Core with
Truffle on the JVM. GHC supplies parsing, typechecking, desugaring and Core
optimization. THC supplies linking, representation-aware lowering, lazy
execution and runtime services. Graal specializes the interpreter into machine
code for hot guest functions.

## From a Cabal project to a running program

1. **Build and acquire.** Cabal resolves the selected component and builds its
   native dependencies, including Setup programs, preprocessors and Template
   Haskell. THC exports post-Tidy Core using the same unit identities and
   component configuration. Complete installed interfaces or the selected
   pinned-source provider supply boot-library Core.
2. **Link and load.** A package manifest identifies the Core and native products.
   The loader checks ownership, reachable references, calling conventions and
   representations. Eligible cold bindings are decoded and lowered on demand.
   `--verify-artifacts` adds file-hash verification and a pre-launch execution
   audit; ordinary runtime checks still apply without it.
3. **Execute.** The selected Truffle backend runs the entry with context-owned
   state. The entry contract determines arguments, results and whether to start
   an IO action. Haskell code runs from Core, not native GHC objects.

`thc acquire` stops after publishing the package manifest. `thc run` also
launches the GHC-selected `IO a` action; acquisition alone does not establish runtime
support. See [build and run](driver.md), [Cabal integration](cabal.md), and
[GHC library Core](ghc-core.md) for setup and limits.

The compiler emits [CBD containers](compact-core-format.md) directly and the
driver publishes them through [package manifests](core-package-manifest.md).
There is no runtime JSON Core input or automatic conversion fallback. Typed
records retain executable representation facts separately from optional source and
pretty-printing data. A readable Core dump is not an executable interchange
format. The [exporter reference](compiler.md) describes the retained GHC facts.

## Backends and values

Bytecode is the default backend. `BytecodeProgram` lowers Core to Truffle's
Bytecode DSL; `Program` lowers it to AST nodes. Both use the same closures,
constructors, thunks, storage and application contracts. Their continuation
machinery differs; select a backend with `THC_BACKEND=bytecode` or `ast`.
The separate [GHC BCO interpreter](ghc-bco.md) handles admitted bytecode objects
created by guest code; it is not THC's bytecode backend.

A value, its executable node and its layout have different roles:

| Object | Responsibility |
| --- | --- |
| Closure | Function target, captured values and any partially supplied arguments |
| Thunk | Shared evaluation, update, failure and waiting state |
| Constructor | Typed fields, including lazy references |
| Executable node | Operations, control flow and call-site specialization |
| Layout | Immutable field types, offsets and logical shape |

Logical arity is separate from physical width. State tokens occupy no payload;
unboxed tuples and sums can occupy several slots. Typed frames, heap fields and
handoff storage preserve the checked shape and exact vector species. Missing
representation evidence is an error, not permission to guess a layout.

Calls handle exact, partial and excess application. Local joins become control
flow and tail transfers use loops. With resumable execution enabled, nested
calls and thunk forcing can save their pending work and unwind to a driver,
then resume without repeating completed effects. Captured data owns its storage;
it cannot retain a borrowed argument or result buffer. See the
[async contract](async-exceptions.md), [aggregate layouts](aggregate-layout.md)
and [JVM implementation reference](site/jvm.md) for details.

## Contexts and effects

Each polyglot context owns its Haskell heap state, CAFs, logical guest threads,
files and native resource registries. Immutable source data may be shared;
mutable guest values and executable program state are not interchangeable
between contexts. Guest thread identities are independent of Java carrier IDs.

Ordinary Haskell implements library algorithms and IO's state-passing structure.
Primops and native/runtime providers supply underlying effects. Supported
threads, STM, exceptions, files and foreign calls have explicit platform and
entrypoint limits, collected in [primop behavior](primop-behavior.md).
THC does not implement the complete native GHC RTS.

Package C/C++ uses declared [native linkage](interface-foreign.md). Special
adapters are needed where an operation acts on THC-owned state, such as its
scheduler or managed descriptor table. A native library requiring GHC heap
pointers or RTS closures cannot use Java objects as substitutes.

The [embedding API](site/embedding.md) supports logical scalar, tuple, sum,
vector, reference and function values, executable IO entries, and separately
checked managed exports. The [`thc:runtime` library](runtime-services.md) exposes
typed Haskell services with explicit availability and authority. Internal Java
classes are not a stable host API.

## Compilation and packaging

On the pinned GraalVM, partial evaluation and inlining can remove interpreter
machinery. Typed storage alone does not prove allocation removal or a speedup;
use [compiler graphs](graph-inspection.md) and matched measurements when those
properties matter.

[Reusable AST code](reusable-code.md) separates prepared code from each load's
heap state. The experimental [Native Image code cache](native-code-cache.md)
uses it to store selected synchronous, foreign-free AST code and load it with
guest compilation disabled. The separate [pure Native Image recipe](native-image-feasibility.md)
loads Core into an interpreter. Neither provides a general IO/FFI executable
distribution.

Unsupported operations fail admission or execution at their checked boundary.
Diagnostic mode can leave explicit traps in unsupported paths for investigation;
avoiding a trap during one run does not establish complete program support.
