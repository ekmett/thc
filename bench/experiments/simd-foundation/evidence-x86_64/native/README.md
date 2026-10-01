# Native GHC Int64X2 oracle on x86-64

Validated on eak-quartus, 2026-09-23, using the same host recorded in the parent
evidence directory. Fixture/exporter source is SIMD foundation commit
`9dba732` (merged without changes into this evidence branch).

```sh
. /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
python3 scripts/prepare-simd-audit.py
```

The command exited 0 with pinned GHC 9.14.1, native x86-64 code generation
(no LLVM backend flag). Both pre-Tidy and post-Tidy exports retain exact
`VecRep 2 Int64ElemRep` logical shapes and all six requested vector primitives:
pack, unpack, broadcast, addition, subtraction and negation.

The native executable produced 147 unique rows: 49 independently varied input
pairs for each of `vectorCase`, `subtractCase`, and `branchCase`. Every row
matched the independent Python signed-64-bit wrapping model, including machine
extremes and both branch outcomes. `oracle.tsv` contains all results;
`provenance.json` retains commands, source hashes and both exported-Core hashes.
`ghc-info.txt` records the compiler target/backend configuration.

The full generated Core remains under `build/simd/{pre,post}-core/` in the local
`/home/ekmett/ai/thc-simd-x86-evidence-01a0cdeb` worktree. This is native GHC
oracle and vector-export evidence, not a THC runtime SIMD execution claim.
