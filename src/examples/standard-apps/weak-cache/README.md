# Weak document-analysis cache

An editor can retain analyses for the document revisions it is still using.
Each cached report can refer back to its original document, for example to show
source text alongside a diagnostic. A weak association lets the cache release
both objects when the last user of that revision goes away.

This example uses ordinary `System.Mem.Weak.mkWeak` to memoize a report with lazy
line and word counts. Its small general cache stores weak `(key, value)` pairs
and removes dead entries during lookup or pruning. The cache remains alive
throughout the demonstration:

- Two requests for the live document reuse one analysis, including across GC
  after the first caller has released its report.
- The report retains its source document and can still display its first line.
- After the document and reports leave scope, their weak association dies and
  the cache removes it, despite the report's reference back to the document.

The sample prints:

```text
First request: (2,5,"red blue red")
Second request: (2,5,"red blue red")
Two requests reused one analysis.
The retained cache released the document and its report.
```

From the THC root with the [pinned toolchain](../../../../README.md), run native
GHC independently:

```sh
cabal run exe:weak-cache --project-dir src/examples/standard-apps/weak-cache
```

Run the same application through THC:

```sh
THC_ROOT="$PWD"
THC_DRIVER=$(cabal list-bin exe:thc)
"$THC_DRIVER" run exe:weak-cache \
  --project-dir "$THC_ROOT/src/examples/standard-apps/weak-cache" \
  --thc-root "$THC_ROOT" --dist-dir "$THC_ROOT/build/weak-cache"
```

The example needs only `base` and the normal [foreign-code setup](../../../../docs/interface-foreign.md).
The cache uses a linear list and serves one caller at a time; it does not
coordinate concurrent builders. Document revisions are immutable. A changed
revision should receive a fresh key so an earlier report cannot become stale.
Collection timing is unspecified: the example requests collections while
waiting for the cache to empty and assumes no fixed number of them.

Checked with native GHC 9.14.1 and the Jam JVM using bytecode/default and
AST/dense execution. Both THC runs match the native output, and the strict
Core dependency audit accepts the exported application.
