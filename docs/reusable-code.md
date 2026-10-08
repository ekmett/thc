# Experimental reusable AST code

`Program.prepareCode(language, module, entries)` uses the existing AST lowerer to
prepare selected closed roots and their global dependencies without evaluating
guest bodies. `PreparedCode.newInstance(language)` creates a fresh ordinary
`Program`, global cells, thunks, closures and metrics without lowering those roots
again. Definitions outside the selected dependency set stay unprepared; asking
for one fails rather than lowering it during execution.

Preparation uses up to four workers once dependency discovery exposes parallel
work. Set `-Dthc.prepareCodeJobs=N` (1–64), or use the explicit `prepareCode`
overload, to change that budget. Each binding is claimed once before its
dependencies are queued, so shared references and cycles do not duplicate work
or wait on a topological ordering. Workers own their source and operand scratch
state; constructor layouts, native-call slots and the completed target inventory
have shared synchronization. A failed request joins its workers and publishes no
prepared program. A dependency chain with only one ready binding stays on the
calling thread.

This pool lowers the selected detached Core into Truffle roots. CBD selection
and decoding still precede it; Graal's subsequent machine-code compilation owns
its own scheduling. Preparation neither evaluates CAF bodies nor trains guest
profiles, and each later program instance still owns fresh CAF cells.

Admission covers exact numeric scalars, data and closures, unboxed tuples and
sums, supported vectors, and State#/Void# values. Ordinary calls, partial
applications, nested closures and thunks, cases, nonrecursive unlifted aggregate
lets, and local joins reuse their existing lowering and transport. Tuple inputs
remain one logical argument while their physical leaves flatten; sums retain
their exact alternatives and projections, vectors remain atomic, and nested
void/empty components erase. A scalar State#/Void# still carries `Unit.INSTANCE`.
Empty arguments retain logical saturation even when they occupy no packet field.

Each closure or thunk carries its program instance in the existing
`CapturedFrame`. Even capture-free reusable roots retain an owned empty
environment. Entry checks the capture layout, prepared-code identity and current
context owner; global reads then use fixed binding indices in that instance.
Captured typed values and durable PAP prefixes keep these ownership checks.
Saved prepared continuations check that captured program identity and context
before claiming the activation, so a rejected foreign-context resume cannot
consume the owner's continuation. Resumed thunk and call-segment drivers retain
the invoking instance through tail transfers and delimited completion; shared
nodes do not recover this state from the preparation context.
Constructor storage descriptors may be shared, but each load owns its allocation
keys, nullary values and optional boxed-value caches. Constructor matches
authenticate that load's layout.

Admitted roots implement context-independent `prepareForAOT` from their declared
physical carriers. Existing tuple/sum destinations and projection mappings are
bound before publication; frame slots use their physical leaf kinds, including
Int scratch for narrow sum payloads and Long sum storage. Cases and calls need no
guest execution or observed profiles to prepare. The environment's concrete
StaticShape subclass remains unspecified in the execution signature, with its
layout and owner checks intact. Shared code uses the invoking instance's metrics.
Fresh contexts keep their own handoff pools; imported prepared layouts are
distinguished by identity even when their numeric layout IDs collide with local
layouts. Logical shape, vector species, ownership, generation and loan-release
checks remain in place. Destinations authenticate the final callee's logical proof
and read through that producer's exact storage descriptor, including forced
CAFs/aliases/PAPs, overapplication, and tail bounces. Independently prepared and
ordinary functions can therefore compose in one fresh context. The consumer keeps
its own fixed frame slots; cross-descriptor pool release crosses a boundary.

Cold generic calls use the public location-aware `CallTarget.call(Node, ...)`
entry without a cached indirect-node exception profile. Successful AOT preparation
also initializes the pinned runtime overlay's root exception profile to generic.
This is an explicit overlay dependency: stock Truffle still invalidates on the
first escaping exception. Ordinary non-AOT exception profiling remains unchanged;
no guest calls or throws train the prepared code.

Prepared code can be instantiated only with its original language in that
language's currently entered context. Its preparation context may already be
closed; another context's program instance still cannot enter the code.
An explicit `prepareCode: true` load request lowers admitted AST code during
parsing and caches only prepared code and signature metadata. Executing that
factory creates fresh program cells and resources. Ordinary cached sources
continue to construct fresh AST or bytecode programs on every load.

Reusable lowering uses the ordinary initializer and operation contracts. Preparation
stores inert typed initializer code; each instance initializes strict values at its
ordinary load boundary, after native linkage when required. Lazy initializers
publish fresh closures and CAF thunks without entering their bodies. Guest
initializer execution uses the existing resumable call ownership protocol outside
the lowering monitor, retaining sharing and failures without replaying effects.
Native data/function labels, callback helpers and foreign-language receivers resolve
against the invoking owner. Implicit exception globals and constructor layouts use
the same per-instance indexes as explicit references. Shared code retains no
resolved native or JavaScript receiver.

Ordinary malformed proof, ABI, aggregate-storage and unsupported runtime checks
still apply. Bytecode and diagnostic execution remain outside this prepared AST
API. Preparation does not prove persisted Native Image execution.

Narrow scalar address indexing (`indexWord8OffAddr#`, `indexInt8OffAddr#`,
`indexWord16OffAddr#`, `indexInt16OffAddr#`) uses the invoking address and preserves
its ordinary bounds, context and lifetime checks, including rejection after free.

Prepared requests retain the Boolean `asyncExceptions` policy in their shared code
and fresh instances. `true` enables ordinary polling immediately; absent or `false`
uses adaptive admission, just as ordinary AST programs do.

Reusable AST roots are capture-capable from first lowering. AOT preparation
declares materializable frames and polymorphic completion before any guest
execution, preserving owned typed transport and unfinished caller operands.
Default-off ordinary polls use the context's irreversible volatile guest-admission
bit instead of an invalidatable assumption, so admitting a fork or another
public caller does not retire prepared installed targets. Prepared instantiation
also remains available after an ordinary origin transition. The transition still
precedes guest effects, and nested nonresumable activations remain nonresumable.
These contracts have JVM cold AOT controls; persisted Native Image cache execution
requires separate pinned-provider verification, without interpreter fallback.

The public host ABI accepts logical tuple arrays, tagged sum payloads, exact
vector species and null State#/Void# values. The
[native-cache CLI](native-code-cache.md) currently parses and prints numeric
scalars only; that presentation limit does not narrow the reusable runtime ABI.
Its `-Dthc.requireCachedCode=true` guard rejects cache misses and ordinary lowering;
persisted-cache execution additionally requires matching cached source, installed
targets and disabled guest compilation.

Shared executable roots retain no context compilation-owner token. Their
compiler events are outside per-context JIT telemetry; ordinary roots and clones
retain explicit ownership. This does not change per-instance guest metrics.

The [native-cache workflow](native-code-cache.md) persists selected compiled
code and loads it in fresh processes with guest JIT compilation disabled. Its
numeric CLI can call functions using typed internal transport. Direct typed
public-host calls use the JVM embedding API; the CLI does not parse those values.
