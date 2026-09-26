# Executable Haskell fixtures

The broader [coverage corpus](../docs/coverage.md) is described by
[`coverage.json`](coverage.json), with separate ordinary list/function/tree and
numeric modules. `scripts/prepare-corpus.py` generates its native driver and
strict Core bundles; `scripts/try.sh` runs both backends through interpreted,
compiled, cold-input and recompiled checks. The fixtures below remain as
smaller tests of individual runtime mechanisms.

## Floating decomposition

The separate [`THC.InverseHyperbolic`](THC/InverseHyperbolic.hs) example
evaluates `asinhDouble#` from raw binary64 input bits and returns raw result
bits:

```sh
cabal run exe:thc-fixtures --offline -- floating-remainder
build/install/thc/bin/thc build/floating-remainder/post-core/THC.InverseHyperbolic.json asinhExample 1 --compile
THC_BACKEND=ast build/install/thc/bin/thc build/floating-remainder/post-core/THC.InverseHyperbolic.json asinhExample 1 --compile
```

Both commands return `1`: the smallest positive subnormal is unchanged at
binary64 precision. See the [floating guide](../docs/floating-primitives.md)
for the inverse functions, min/max operand rules and four-field decomposition.

[`THC.FloatDecode`](THC/FloatDecode.hs) exposes the ordinary `Float` and `Double`
`exponent` methods through integer-bit-pattern inputs, matching THC's scalar CLI.
The [floating guide](../docs/floating-primitives.md#integer-decomposition-and-public-exponent)
describes the native-backed corpus, decomposition primops and exact scope.

```sh
make runtime
cabal run exe:thc-fixtures --offline -- float-decode
build/install/thc/bin/thc build/float-decode/post-core/THC.FloatDecode.json,build/float-decode/original/GHC.Internal.Bignum.Integer.json doubleExampleExponent 1 --compile
THC_BACKEND=ast build/install/thc/bin/thc build/float-decode/post-core/THC.FloatDecode.json,build/float-decode/original/GHC.Internal.Bignum.Integer.json floatExampleExponent 1 --compile
```

Input `1` is the smallest positive subnormal bit pattern: these commands print
`-1073` and `-148` respectively. Both signed zeros have exponent zero. The
original Integer module is required by the real `Double` implementation; omitting
it is a strict loading error, not a request to replace the library method.

These are real Haskell modules compiled by GHC 9.14.1 at `-O2`. The THC
prototype exports `THC.Prim` and `THC.Fixtures`; `NativeOracle.hs` is only the
native GHC oracle and benchmark driver. The prototype does not need to execute
that driver's `base` IO code to execute the exported fixtures.

`THC.Prim` supplies ordinary Haskell functions and constructors from a separate
module. Its `Box`, `Pair`, `List`, `Function`, and `Unary` types are deliberately small.
Only `Int#` arithmetic and comparisons are primitive runtime services; addition
of two `Box` values, list construction/traversal, application helpers, and
recursive functions are compiled Haskell. `NoImplicitPrelude` keeps the initial
end-to-end dependency graph tractable. This is a constrained first milestone,
not evidence of broad `base`, IO, or FFI compatibility.

Every terminating public entry has type `Int# -> Int#`, allowing the host to
supply and read machine integers while its implementation exercises lifted
values. Lifted arguments and fields must remain lazy.

| Entry | Result | Main purpose |
|---|---|---|
| `sumLoop` | Sum of 1 through n, or zero for n <= 0 | Strict primitive tail recursion |
| `fib` | Recursive Fibonacci; returns n for n <= 1 | Non-tail calls and arithmetic |
| `captured` | n + 7 | Closure capturing a primitive input |
| `exact` | n + 7 | Exact application of an arity-two function |
| `under` | n + 7 | Underapplication passed through an unknown callee |
| `over` | 13 for n <= 0; otherwise 42 | Arity-one chooser applied to three arguments |
| `unknown` | Same as `over` | Chosen function and partial application through `applyBox` |
| `shared` | 2 * (n * n + 11) | One local thunk referenced twice |
| `lazyArgument` | n | Unused divergent argument |
| `lazyField` | n | Unused divergent constructor field |
| `recursiveCaf` | max(n, 0) | Productive cyclic CAF and constructor cases |
| `caseList` | Same as `sumLoop` | Lazy recursive list construction and traversal |
| `multiModule` | n + 29 | Separately compiled Haskell import |
| `cacheSaturation` | n + selected offset 1 through 5 | Five function targets through one unknown-call site |
| `mutualTail` | max(n, 0) | Two mutually recursive functions with tail calls |
| `selfMutualTail` | max(n, 0) | Mixed self and mutual tail calls preserve ancestor masks |
| `capturedChangingEnv` | 17 + max(n, 0) | Same lambda body tail-called with changing primitive captures |
| `localMutualClosures` | 2*n + 40 | Escaping mutually recursive closures retain cells and lexical values |
| `nestedCaptureThunk` | n*n + 23 | Thunks retain captures after their creating frames return |

All integer results use machine-`Int#` wraparound. GHC native `Int#` and JVM
`long` are both 64 bits on the tested AArch64 host. `blackhole` is an additional
nonterminating entry excluded from the native numerical matrix: forcing its
self-referential CAF should make THC report a blackhole. `fibonacciBox` is an
additional lifted recursive function for later experiments.

## Native differential oracle

From the project root:

```sh
mkdir -p build/native
scripts/native-oracle.sh > build/native/oracle.tsv
build/native/native-oracle under 20
```

The script builds with `-O2 -dcore-lint -dstg-lint`, saves the native executable
and simplified Core dumps in `build/native/`, and writes only TSV result rows
to standard output. Build output goes to standard error. Each row is
`entry<TAB>input<TAB>expected`; the default matrix contains all 19 entries at
inputs -3, 0, 1, 2, 7, 10, and 20, for 133 rows. A fresh checkout needs
`mkdir -p build/native` before shell redirection to that directory; without
redirection the script creates it itself.

Native compilation and all 133 evaluations completed on GHC 9.14.1. The runtime regression suite checks all 133 rows both before and after guest compilation. Numerical
agreement cannot by itself prove single evaluation of a shared thunk; runtime
instrumentation or an independent evaluation-count check is needed for that.

## What survived GHC optimization

The inspected `build/native/THC/*.dump-simpl` files contain:

- A captured lambda inside `captured`, with n free in its body.
- `applyBox (addBox (Box n)) ...` in `under`: an actual arity-two function
  supplied one argument.
- `chooseFunction = \n -> case pickFunction n of { Function f -> f }` and
  `chooseFunction n ... ...` in `over`: arity one followed by two extra
  arguments. The initial direct conditional chooser was eta-expanded to arity
  three. The opaque wrapped-function producer preserves the intended test
  without disabling GHC optimization passes.
- `let x = costlyBox (Box n) in ... addBox x x` in `shared`.
- References to `diverge` in both laziness tests, and `diverge = diverge`.
- `ones = Cons ... ones`, including its back edge, and recursive `buildList`.
- A genuine cross-module call/alias for `multiModule`.
- Five separate unary increment functions selected at thresholds 0, 1, 2, and 7
  for `cacheSaturation`, so the default inputs cover all five targets.
- A two-member recursive group for `mutualEven`/`mutualOdd`, each tail-calling
  the other; the native `mutualTail 100000` result is 100000.
- The adversarial `selfMutualA`/`selfMutualB` group keeps both self calls and
  cross calls. Each function self-calls once before calling its peer, exercising
  preservation of ancestor masks across self loops; native 100000 returns 100000.

`OPAQUE` annotations intentionally stop GHC from replacing the application and
laziness experiments with simpler arithmetic. These are semantic/runtime
fixtures, not representative application-wide optimization benchmarks. The
strict sum loop is floated to a recursive top-level helper by GHC; it does not
exercise a surviving Core join point. There is no fusion claim here.

## Exploratory native timing

```sh
build/native/native-oracle --bench sumLoop 1000 100
build/native/native-oracle --bench captured 1000000 100
build/native/native-oracle --bench fib 32 1
```

`--bench ENTRY REPETITIONS INPUTBASE` resolves the entry and forces parsed
parameters before timing. For i from zero to repetitions minus one, it calls
the selected function with `INPUTBASE + (i .&. 15)`, strictly accumulating the
results by wrapping machine-integer addition. It uses `GHC.Clock` monotonic
nanoseconds and prints TSV:

```text
entry    repetitions    inputBase    checksum    elapsedNs
```

There is no header in the actual output. The three commands above produced
checksums 5839044, 114500000, and 5166 respectively. The inspected native Core
contains a strict counter/accumulator loop, a call through the selected function,
and clock reads enclosing that computation. The driver excludes build, process
startup, argument parsing, and result printing from its reported time. These
small runs are smoke comparisons; JVM warmup, proven Graal compilation,
allocation, repeated independent runs, and representative applications remain
necessary before making performance claims.

## Opaque pointers and wide characters

[`StableWideCells.hs`](StableWideCells.hs) stores a caller-owned StablePtr and a
supplementary-plane character in aligned array slots. See the
[storage contract and native/compiled checks](../docs/aligned-scalar-memory.md).
