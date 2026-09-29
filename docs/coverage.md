# Core compatibility checks

THC compares exported Haskell programs with native GHC on both backends.
The [primop checklist](primops.md) inventories implemented operations; the
[behavior reference](primop-behavior.md) describes restrictions and target choices.
A supported primitive does not supply a missing Haskell dependency, and a passing
example does not establish support for every use of its library.

## Run the corpus

With the [pinned tools](contributing.md#build-and-test) selected, run:

```sh
bin/try.sh
bin/try-libraries.sh
```

The first prepares and checks the Core corpus, including lists, productive
streams, shared thunks, captured functions, partial and excess application,
trees, and numeric representations. The second checks ordinary library
consumers; see [library examples](library-coverage.md). The entry/input inventory
is in [coverage.json](../src/examples/coverage.json).

Preparation exports Core, audits each complete reachable dependency closure,
and runs a native GHC oracle. A strict audit includes cold alternatives and lazy
right-hand sides. Missing definitions and unsupported operations fail preparation.
Diagnostic mode is a separate debugging option and does not turn a rejected
closure into supported execution.

The runtime checks native results before compilation, then observes installed
guest-code execution on warm and held-out inputs. A cold branch may deoptimize;
subsequent compilation checks the broader input set. Sharing controls also count
evaluations: equal results alone would not detect replayed work. Input and
artifact fingerprints reject stale prepared output under `build/corpus/`.

## Primitive inventory

```sh
cabal run exe:thc-primops -- coverage
cabal run exe:thc-primops -- coverage --check
cabal run exe:thc-primops -- scalars
```

These tools use the pinned GHC API, including generated vector primops. The
coverage report records signatures and declared implementation status; it is
not exhaustive input-domain testing. When changing capability declarations,
regenerate the checklist with `coverage --write-checklist` rather than editing
[primops.md](primops.md) by hand.

## Representation boundaries

[Core evidence](core-evidence.md) governs entry demands, constructor fields,
local joins and `dataToTag#` families. [Aggregate layouts](aggregate-layout.md)
retain logical tuple/sum shape independently of physical width.
[Narrow integer carriers](narrow-integer-carriers.md) retain signedness and width;
[SIMD](simd.md) retains exact vector species. These distinctions also apply to
[host calls](site/embedding.md#load-a-core-entry).

`reallyUnsafePtrEquality#` compares the current object references without
forcing either operand, comparing fields or following an updated thunk. Code
using it needs a value-based fallback when references differ. Native GHC and
THC may share or allocate differently; pointer identity is not portable across
runtimes.
