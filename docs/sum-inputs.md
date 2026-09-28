# Typed unboxed sum inputs and captures

Both backends accept ordinary guest sums with two or more alternatives through
direct and higher-order calls, partial applications, overapplication and tail transfers.
The supported payloads are the existing [sum result layouts](sum-results.md):
lowered integral carriers (including Int64/Word64), Float, Double, known
references, void and tuples of these. One sum is one logical argument,
independently of its physical width. Exact
alternatives and projections distinguish sums sharing the same physical fields.

The existing typed input packet carries the tag and concrete payload fields.
Entry copies them into callee locals and releases the incoming loan before guest
code continues. Durable PAP prefixes own separate fields; they never retain a
pooled input or a caller frame. Overapplication advances by logical argument
count, then uses the physical offsets appropriate to the next function.

Closure and thunk captures flatten a whole sum into ordinary exact capture
properties and restore a logical sum alias in the new frame. Long, Float and
Double remain primitive properties. Lifted leaves remain lazy, including a
bottom in an ignored alternative field. Inactive reference slots remain null
padding and do not appear as pointers in closure inspection. Constructors clear
inactive fields before publishing the tag; captures and PAPs copy that state.
There is no new boxed sum value or payload array.

Local joins accept the same exact sum layouts and capture enclosing sums through
their existing typed frame slots. A transfer first evaluates every operand into
scratch slots, then moves all tag/payload fields in parallel; recursive swaps
therefore preserve both operands. Completed transfers clear scratch references.
A closure escaping a join owns its captured sum fields independently of that
activation. Logical alternatives remain checked even when physical layouts match.

Tuples may recursively contain supported sums; physical tag/payload slots expand
at the sum's logical position without losing surrounding tuple boundaries or
zero-width components. Concrete nested sums, evaluated managed addresses and
supported exact vector species use the same derived typed storage as results;
the original GHC physical proof remains unchanged. Nonrecursive unlifted sum lets
evaluate once into typed frame locals. Unresolved sum payloads, recursive/lifted
sum lets, global aggregate storage and public host sum parameters/results remain excluded. Scalar host roots may use all the
supported sum operations internally. Unsaturated sum constructors remain excluded.

## Reproduce

With the pinned GHC 9.14.1 and Graal/JDK 25 toolchain:

```sh
cabal run exe:thc-fixtures --offline -fdevelopment -- sum-input
./gradlew sumInputFullCoreTest sumInputFullCoreDenseTest
cabal run exe:thc-fixtures --offline -fdevelopment -- sum-join-input
./gradlew sumJoinInputFullCoreTest sumJoinInputFullCoreDenseTest
./gradlew testDefault --tests thc.runtime.SumInputLayoutTest \
  testDense --tests thc.runtime.SumInputLayoutTest
```

The unchanged Haskell fixture yields 333 native observations across nine roots.
Genuine pre/post-Tidy exports retain ordinary sum formals, partial applications,
whole-sum captures and a two-argument function that is really overapplied.
Both backends compare every row before and immediately after compilation in
both handoff modes with inlining enabled and disabled, checking compiled target validity and released input/result
references. Reused escaped closures and PAPs outlive their creator frames;
separate controls check logical-layout mismatches, ownership and null padding.
Source, exporter, auditor, native and artifact hashes reject stale evidence.

The sum-join fixture adds 259 native observations across seven roots in both
export stages. It covers forwarding, recursive swaps, mutual recursion, lexical
and escaped captures, empty payloads, lazy bottom fields and changing tags.
Exact transfer counts include the nonrecursive wrappers retained by GHC around
the two recursive loops; structural checks confirm those wrappers in each export.
The same interpreted and first-compiled-call checks run with and without inlining.
