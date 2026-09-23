# Independent native Int32X4 oracle

Generated on eak-quartus, Linux x86-64, with pinned GHC 9.14.1 from exact
source revision `090035f08b396502277d12739d766c431e69424e`. This was a fresh,
isolated worktree; no previously generated Core or native oracle was reused.
No source, exporter, or preparation-helper modifications were made.

```sh
. /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
python3 scripts/prepare-simd-audit.py --vector int32x4
```

The preparation completed successfully, producing genuine pre-Tidy and
post-Tidy Core plus **243 native rows**: 81 four-input cases each for
`vectorCase`, `subtractCase`, and `branchCase`. Every result matched the
independent signed-32-bit lane model. Inputs include signed lane boundaries,
both signed-64-bit extremes and out-of-range 64-bit carriers. Comparisons in
`branchCase` use the original signed-64-bit arguments, before lane narrowing.

Both exports retain exact `VecRep 4 Int32ElemRep` identity, distinct from tuple
identity, and all six pack/unpack/broadcast/add/subtract/negate primops. Native
compilation used GHC's x86-64 native code generator; no LLVM fallback was needed.

This is **native oracle and exporter evidence only**. No THC runtime, installed
guest-code, allocation-elimination, packed-LIR, or performance claim is made.
In particular, the native branch fixture does not establish support for vector
formal joins in THC.

`oracle.tsv`, `provenance.json`, and `prepare.log` are byte-for-byte copies of
the successful run outputs. Provenance retains the exact command sequence,
GHC binary hash/configuration, fixture/exporter/helper hashes, and both generated
Core hashes. Its artifact paths refer to the original worktree's `build/` tree;
the generated Core and native binary remain there rather than being committed.

Native executable SHA-256:
`d816fd18e91d4fc83826ef02fc63d6b62c5161a66090592d3ee6fbcddf65c9c4`.

The original worktree is
`/home/ekmett/ai/thc-simd-int32x4-native-01a0cdeb`.
Run `sha256sum -c artifacts.sha256` from this evidence directory to check the
three retained outputs. `validation.json` additionally records the independent
post-run source/artifact hash recheck and row counts.
