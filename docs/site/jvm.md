# Module THC

THC executes exported GHC Core on Truffle and Graal. This reference covers the
Kotlin and Java implementation. Host applications should start with
`thc.executionContext` and `thc.loadEntry`, and read the site's embedding guide
for authority and lifetimes.

There are two namespaces. `thc` loads and checks programs and provides the host
entrypoints. `thc.runtime` contains their execution machinery. Within the runtime,
distinguish **values**, **executable nodes**, and **layout metadata**: a Haskell
closure, the node that calls it, and the description of its captured fields have
different jobs and lifetimes.

This is an implementation reference, not a stable SDK. Java public visibility
and Kotlin public declarations do not promise stable runtime carriers, node
layouts, frame slots, calling conventions, or generated Truffle APIs. Generated
DSL and SIMD sources and test fixtures are intentionally excluded.

# Package thc

This namespace is the boundary between an exported program, its host, and the
runtime. Start with `executionContext` and `loadEntry` when embedding THC.

- **Loading and admission.** `CoreModules`, package manifests and foreign-artifact
  validation assemble exported Core and check the reachable program. Reading a
  module successfully does not establish that all its operations can execute.
- **Language and context.** [Language][thc.Language] supplies the Truffle language
  entrypoint. Its context owns guest threads, files, native resources, layout
  interning and other mutable services. These are context state, not executable
  nodes or Haskell data constructors.
- **Host entrypoints and diagnostics.** The launcher selects a backend and an
  entry contract. Scalar calls and executable `IO ()` have distinct contracts;
  public runtime classes are not a substitute for the checked host boundary.

# Package thc.runtime

This namespace contains both the objects a Haskell program computes with and
the interpreter that computes them. They are deliberately different kinds of
object, even where their names sound similar.

## Values, nodes and descriptions

| Role | Examples | What an instance represents |
| --- | --- | --- |
| Guest values | [DataValue][thc.runtime.DataValue], internal `Closure` and `Thunk`, primitive scalars, SIMD carriers such as [FloatX4][thc.runtime.FloatX4] | A constructor, function, delayed computation or computed result. A value can be shared by several calls. |
| Value storage | [CapturedFrame][thc.runtime.CapturedFrame], [HandoffStorage][thc.runtime.HandoffStorage], managed arrays and addresses | Fields holding values, with a specific ownership and lifetime. Storage is not executable code. |
| Executable Truffle nodes | [GuestRoot][thc.runtime.GuestRoot], [BytecodeRoot][thc.runtime.BytecodeRoot], internal `Expr`, `Force` and call nodes | Instructions and control flow, with children, specialization state and call-site caches. Nodes execute against invocation frames and runtime values. |
| Layout and lowering metadata | [DataLayout][thc.runtime.DataLayout], [CaptureLayout][thc.runtime.CaptureLayout], [HandoffLayout][thc.runtime.HandoffLayout], internal `CoreRepresentation` | How to interpret or allocate fields and which execution path is justified. A layout describes storage; it does not contain a particular value's fields. |
| Program construction and linkage | [Program][thc.runtime.Program], [BytecodeProgram][thc.runtime.BytecodeProgram], [ExecutableProgram][thc.runtime.ExecutableProgram] | Lowering, linked bindings, root call targets and host entrypoints. These are not themselves expression nodes or guest closures. |
| Context services | `Language.State` and its thread, file and native-resource registries | Resources and mutable state belonging to one polyglot context. |

For example, a `Closure` holds a target, an optional `CapturedFrame` and any
already supplied arguments. A call node invokes that target. `CaptureLayout`
describes the captured fields. The closure and its environment can outlive the
invocation that created them; a Truffle `VirtualFrame` cannot simply escape with
them. `CapturedFrame` is selective heap storage, not a saved `VirtualFrame`.

Some runtime classes are public because generated Truffle code must construct
or access them. Public visibility does not make them a supported embedding ABI.
Internal node types are named here to explain the implementation; they are not
all included in this public API index.

## From Core to compiled code

The loader supplies optimized Core together with representation, demand and
source information. `Program` lowers it to an AST; `BytecodeProgram` lowers it
to Truffle Bytecode DSL instructions, locals and labels. Both use the runtime's
value representations, lazy evaluation protocol and application machinery.

Truffle specializes execution using the node structure, observed types and call
targets. Graal partially evaluates that interpreter and builds its own compiler
graph before producing machine code. The Truffle nodes documented here are
**not Graal IR nodes**. A constructor allocation or call visible in interpreter
source may disappear after partial evaluation, inlining and escape analysis;
that is an optimization outcome, not a different source-level value contract.

## Values and representation

`DataValue` stores an ordinary algebraic constructor, including lifted tuples.
Its `DataLayout` fixes the constructor identity and field representations.
Truffle StaticShape supplies typed fields. Where a generated storage class has a
unique layout owner, that class can identify the layout; a shared-carrier
fallback keeps an explicit layout reference.

A thunk is a separate updateable value. Forcing it may execute a target, wait
for another evaluator, return an existing answer or propagate a guest failure.
Successful update releases the original body and environment. A node that
forces thunks has its own execution state and target caches; it is not the
thunk's shared evaluation state.

Unboxed tuples are different from lifted tuple constructors. Their components
can occupy several physical slots; a `State#` component occupies none. Logical
argument count therefore differs from physical field count. Representation
proofs determine which primitive lanes can be used, rather than a Haskell type
name alone. Unsupported representations are rejected at admission.

Typed execution uses primitive frame slots and specialized operations where
the available proof permits them. An adaptive path can widen when its
assumptions fail. A fixed representation proof is stronger than an observed
profile: it justifies a concrete representation without guessing from the
first value seen.

## Calls, joins and loops

The application machinery handles exact, partial and excess application.
Partially supplied arguments are runtime data owned by the resulting closure;
direct-call caches belong to executable call nodes. Call-target identity and
argument layout metadata let the compiler specialize a call without making
the closure itself part of the Truffle node tree.

Core join points lower to local control flow where their checked shape permits
it. Recursive and tail calls use loop and dispatch machinery to avoid repeatedly
growing the Java stack. The AST backend expresses this with expression, call
and loop nodes; the bytecode backend also has explicit labels and branches.
These are the control-flow portions of the interpreter, distinct from the
constructor and closure objects they manipulate.

Dense handoff storage is a calling-convention choice. Reusable incoming loans
must be consumed into durable locals before guest execution can re-enter the
runtime. Escaping closure captures and partial applications must own their
retained data. A `HandoffStorage` object is therefore transport storage, not a
Haskell tuple or an invocation's `VirtualFrame`; its ownership matters as much
as its field types.

## Exceptions, continuations and stack inspection

[GuestException][thc.runtime.GuestException] transports a Haskell exception
payload through Truffle. [RuntimeFault][thc.runtime.RuntimeFault] reports a
runtime or contract failure. Neither should be confused with the internal
control transfers used for tail calls and suspension.

A suspended computation needs executable resumption state as well as retained
values. Internal continuation segments serve that purpose on supported paths.
[ManagedStackSnapshot][thc.runtime.ManagedStackSnapshot] instead stores detached
binding names and source coordinates for inspection. It retains no live frames,
nodes or call targets and cannot resume execution. A printable stack is not an
`AP_STACK` equivalent.

Asynchronous delivery and foreign-call boundaries have additional admission and
ownership constraints. The presence of continuation support in an internal
backend does not imply that every public call boundary supports it; consult the
backend and entrypoint contracts when adding a new path.
