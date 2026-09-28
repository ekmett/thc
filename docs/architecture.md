# Architecture

THC uses GHC 9.14.1 as its Haskell frontend and executes exported optimized
Core with Truffle on the JVM. GHC supplies parsing, typechecking, desugaring
and Core optimization; THC supplies linking, representation-aware lowering,
lazy evaluation and runtime services. Graal can compile hot guest code by
partially evaluating the interpreter. Haskell execution uses the exported Core,
not its native GHC objects or an STG interpreter.

This guide describes the implemented system and its boundaries. The
[open design questions](../research/open-questions.md) cover unresolved
design decisions and their background. Build commands and toolchain requirements are in
the [README](../README.md); detailed contracts are in the
[documentation index](README.md).

## Frontend, acquisition and admission

The pipeline has three distinct boundaries:

1. **Native build and Core acquisition.** Cabal resolves component dependencies
   and builds the native products needed by Setup programs, preprocessors and
   Template Haskell. The Haskell driver uses resolved unit identities and
   component build information to export the selected program and its
   dependencies. These native build products are not the guest executable.
2. **Linking and admission.** Package manifests identify Core artifacts by
   unit, module and hash. Strict linking checks reachable references and foreign
   requirements; the separate execution audit checks the program's supported
   operations. Runtime representation and operation checks apply as bindings are
   admitted and lowered, including when an indexed binding is first demanded.
   A complete artifact is not by itself evidence that its code can execute.
3. **Guest execution.** An accepted entry is lowered to the selected Truffle
   backend and runs with context-owned services. Host entry contracts decide
   how to supply arguments, start an IO action and expose its result.

`thc acquire` performs acquisition and stops before the execution audit;
`thc run` also audits and runs an accepted `Main.main :: IO ()`. Neither command
promises support for arbitrary Cabal packages. The
[Cabal guide](cabal.md) and [driver guide](driver.md) describe target selection,
caching, installed-library providers and platform limits.

The [exporter](../docs/compiler.md) serializes executable trees directly from
the pinned GHC API, not from pretty-printed Core. Source fixtures can use the
optimized pre-Tidy boundary; package manifests require post-Tidy Core before
CorePrep. Both retain representation and evaluation evidence needed by lowering.
Readable source Core and diagnostic type/demand strings are inspection metadata,
not a lossless round-trip format or an executable proof by themselves.

Installed libraries need complete executable bodies, which ordinary interfaces
do not necessarily retain. The driver distinguishes its limited pinned-source
provider from the required complete-interface provider. Foreign declarations,
native products and lifecycle obligations have their own
[provenance and admission contract](interface-foreign.md). Re-exporting Core does
not make a GHC RTS-dependent native library compatible with THC.

## Two backends, shared value contracts

`Program` lowers Core to an AST. `BytecodeProgram` lowers it to Truffle Bytecode
DSL instructions; bytecode is the default backend. Both use shared constructor,
closure, thunk and storage representations. Their control-flow machinery and
continuation coverage are not interchangeable merely because values are shared.
See the [bytecode guide](bytecode.md) and
[JVM implementation reference introduction](site/jvm.md).

[In-memory JSON navigation](core-package-manifest.md#json-navigation-and-lazy-loading)
lets either backend project fields from retained JSON bytes and prepare eligible
top-level functions and thunks on demand. File verification is opt-in; source
snapshotting, header indexing and dependency discovery still do eager work. The selected entry is
prepared when loaded; cold callees need not have executable roots yet. Immutable
source projections may be shared, but each Context owns its executable program.

Opt-in [compact containers](compact-core-format.md) provide another demand-loading
route. Both backends search a fixed fingerprint table and decode only the selected
typed binding and its required metadata. Immutable file mappings are shared;
decoded bindings, CAFs and executable roots belong to each Context. Source maps
and original-name tables are consulted only for explicit diagnostic requests.

A guest value is distinct from the executable node that manipulates it:

- A closure owns its call target, captured values and any partially supplied
  arguments. Call nodes own dispatch caches, not the closure's data.
- A thunk owns shared evaluation and update state. A force node enters that
  state; forcing a constructor does not recursively force its lazy fields.
- A constructor has a typed layout and stored fields. Layout metadata describes
  storage; it is not a second copy of the value or an executable node.

Logical arity and physical storage width are separate. State tokens have no
payload, and an unboxed tuple or supported sum can occupy several slots.
Typed frame slots, heap fields and handoff storage use checked representation
evidence; vector values retain their exact species. Supported
[aggregate constructor fields](aggregate-heap-fields.md) preserve logical shape
and lazy lifted payloads. Missing evidence and unsupported shapes remain errors,
not permission to guess a reference layout.

Calls support exact application, partial application and overapplication within
the admitted representation contracts. Local joins lower to control flow;
tail transfers use loops and dispatch. Non-tail stack behavior depends on the
backend and continuation mode. In particular, async-enabled AST execution uses
saved continuations for nested calls and thunk forcing, but deep evaluation
inside an active STM transaction remains unsupported. Consult the
[async contract](async-exceptions.md), not the presence of a tail-call loop,
when assessing a new entry path's stack safety.

## Contexts, effects and foreign boundaries

Mutable runtime services belong to the polyglot context: guest thread state,
files, native resources and other registries are not global Haskell values.
Guest thread IDs identify context-owned logical lifetimes independently of
Java carrier IDs. Distinct callback guest lifetimes can share a carrier.
Logical capabilities are a separate scheduling interface, not a Java pool size;
see [thread inventory](thread-inventory.md) and
[RTS capabilities](rts-event-capabilities.md).

Ordinary exported Haskell implements library algorithms and much of IO's
state-passing structure. Primops and checked runtime/foreign providers supply
the underlying effects. An IO value is an action, not an instruction to run
whenever its closure is forced. Supported threads, STM, exceptions, files and
foreign calls have explicit platform and entrypoint limits; the
[behavior register](primop-behavior.md) records concrete differences and gaps.
The implementation count is not a claim of a complete GHC RTS or boot library.

The host has distinct [entry contracts](site/embedding.md): integer kernels,
executable `IO ()`, and declared managed scalar exports. Internal runtime
classes and their public JVM visibility do not define a stable embedding ABI.
The Haskell [`thc:runtime` library](runtime-services.md) exposes typed services
with explicit availability and authority; it does not make every JVM or native
facility available to every context.

Strict admission and diagnostic execution serve different purposes. Diagnostic
mode can leave explicit traps in unsupported paths for development. Successfully
avoiding those traps does not establish whole-program support or authorize a
broader public entry contract.

## Compilation and packaging

On the pinned optimizing JVM, Graal specializes Truffle execution into machine
code. Interpreter nodes are not Graal IR nodes, and typed storage alone does not
prove that an allocation, dispatch or boxed call packet disappears. Compiler
inspection and tests must establish those optimization outcomes separately.

The [Native Image probe](native-image-feasibility.md) is a different packaging
path: a native executable interpreting accepted pure Core. It does not establish
guest JIT or guest AOT compilation, a shipping distribution, or a complete native
FFI/resource lifecycle. JVM guest compilation must not be presented as evidence
for those Native Image capabilities.

## Planned work

The direction is ordinary Cabal programs with complete supported dependency
closures, including their error paths. Remaining work includes broader
boot-library and FFI coverage, general file/Handle behavior, and platform
compatibility within the same provenance and admission boundaries.

`thc build` and `thc repl` are planned commands. The REPL design reuses GHC's
frontend and THC's package environment; source breakpoints, stepping and live
frame inspection need debugger instrumentation beyond retained source notes.
See [Cabal and REPL direction](cabal.md). These plans do not change the contracts
of the implemented acquisition, execution and embedding entrypoints.
