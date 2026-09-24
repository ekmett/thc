# Public Show Int from original GHC source

`ShowIntAudit` uses the ordinary public `show :: Int -> String`. Its two consumers
compute an order-sensitive wrapping checksum and inspect each character and the
end of the string. The implementation is GHC's actual `GHC.Internal.Show` source,
not a JVM formatting builtin.

The pinned GHC 9.14.1 interface exposes `itos`, but its recursive digit worker
`ghc-internal:GHC.Internal.Show.$fShowCallStack_itos'` has no executable unfolding.
`compiler/export-boot.py --frontier show` compiles the complete, unmodified,
SHA256-verified source after Tidy and before CorePrep. This preserves the actual
installed worker identity; no local pre-Tidy binding is renamed to impersonate it.

Preparation keeps both the public pre-Tidy and post-Tidy exports. Their complete
interface closure contains only Show definitions also present in the complete
source module. The source module replaces that whole redundant interface file,
with exact identity coverage checked. No individual body or cold branch is
removed. The public-only module set remains an explicit strict-audit rejection
for the exact missing worker; the full source sets must pass strict audit for
each entry and retain the worker as reachable.

The fresh native oracle covers 510 signed machine integers and 6,763 observations:
zero, both signs, `minBound`/`maxBound`, powers of ten and neighboring values,
every bit position and neighbors, internal zeros, repeated digits and reordered
digits. The independent model uses Python decimal formatting. Every output
character, its position and the termination boundary are checked separately, so
a matching checksum cannot conceal missing, reordered or extra digits.

`ShowIntTest` checks both export stages on AST and bytecode, with normal inlining
and disabled inlining. After warmup it explicitly installs actual adopted guest
and host targets, checks every native result and positive compiled guest entry,
and requires the original entry, original Show worker and active targets to stay
valid with unchanged identities. Argument/result pools must release all loans
and references; warmed result storage must be reused. Default and forced dense
handoff runs exercise the same controls. No timing or allocation-elimination
claim is made.

```sh
python3 scripts/prepare-show-int.py
python3 scripts/prepare-show-int.py --check-only
python3 scripts/test-show-int-model.py
./gradlew test --tests thc.runtime.ShowIntTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew test --rerun --tests thc.runtime.ShowIntTest
```

Preparation is included in `scripts/prepare-tests.sh`; CI preserves the native
oracle, full source/public exports, strict audits and source/artifact provenance.
No runtime or primitive capability is added. This export supplies Sequence's
missing Show digit worker, but does not change the declared Sequence/Set exception
frontiers. Real ErrorCall construction still reaches Typeable fingerprinting,
pinned-address operations and MD5 foreign calls; backtrace collection has further
stack/foreign dependencies. Those bodies are not stubbed or pruned.
