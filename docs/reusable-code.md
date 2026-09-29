# Experimental reusable AST code

`Program.prepareCode(language, module, entries)` uses the existing AST lowerer to
prepare selected closed roots and their global dependencies without evaluating
guest bodies. `PreparedCode.newInstance(language)` creates a fresh ordinary
`Program`, global cells, thunks, closures and metrics without lowering those roots
again. Definitions outside the selected dependency set stay unprepared; asking
for one fails rather than lowering it during execution.

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

Reusable boxed constructors accept exact scalar, tuple, sum and vector field
proofs, with fresh per-load allocation ownership. Immutable static byte literals
and admitted managed byte-array operations are available to pure code. Nonliteral
strict globals cannot execute during preparation. Global aggregate storage and
recursive or lifted aggregate lets retain their ordinary rejection. Bytecode and
diagnostic execution remain outside reusable admission; selected IO, foreign and
managed-export forms retain their existing explicit admission requirements.

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
