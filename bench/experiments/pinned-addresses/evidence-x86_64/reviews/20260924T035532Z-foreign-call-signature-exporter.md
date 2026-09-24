# Structured foreign-call signature exporter checkpoint

Sender `/root/int8x16_fixtures_xhigh`, recipient `/root`, host eak-quartus.
Isolated clone `/home/ekmett/ai/thc-foreign-call-signatures-01a0cdeb`, branch
`codex/foreign-call-signatures`, base
`9a7fef75cc4ce44cb2b1031c61c92aadffb78859`.
Tested commit: `89caee4c78c935bd2c2216d09e6957fbc7ef6008`.

Only four compiler files changed: `compiler/Thc/Plugin.hs`, `compiler/README.md`,
`compiler/test-fixtures/ForeignCallAudit.hs`, and its dedicated
`check-foreign-call-metadata.py` reader. No runtime, auditor policy, source-boot
driver, prior frozen clone, JVM execution or native foreign invocation changed.
IRC remained unavailable; coordination used native messages and this handoff.

## Agreed descriptor contract

The existing application metadata object gains `app[6].foreignCall`. It adds no
executable node kind and does not alter `varKey`, function variable IDs or ordinary
unknown-foreign frontiers. The parent approved this contract before consumers.

```json
{
  "schema": 1,
  "target": {"kind": "static", "symbol": "__hsbase_MD5Init", "unit": "main", "isFunction": true},
  "convention": "ccall", "safety": "unsafe",
  "arity": 2, "suppliedArity": 2,
  "argumentReps": [
    {"primReps": ["AddrRep"], "kind": "address", "evaluated": false},
    {"primReps": [], "kind": "void", "evaluated": false}
  ],
  "resultRep": {
    "primReps": [], "kind": "unknown", "evaluated": false,
    "aggregate": "unboxed-tuple",
    "components": [{"primReps": [], "kind": "void", "evaluated": true}]
  }
}
```

Pinned GHC 9.14.1 APIs were queried directly:
`isFCallId_maybe`, `ForeignCall = CCall CCallSpec`,
`CCallSpec CCallTarget CCallConv Safety`, and
`StaticTarget SourceText CLabelString (Maybe Unit) Bool | DynamicTarget`.
The descriptor reads these constructors, exact `unpackFS` label and `unitString`;
it never parses the synthetic foreign ID or a printed type. `unit:null` means
GHC supplied no target unit. The fixture unit is genuinely `main`; it is never
substituted for `ghc-internal` from original Fingerprint source.

A dynamic target is exactly `{"kind":"dynamic"}`. Convention alternatives are
ccall/capi/stdcall/prim/javascript; safety alternatives are unsafe/safe/interruptible.
Declared signature types come from GHC after actual leading type instantiation.
Arity counts all value/coercion slots, including State. Supplied arity separately
counts actual retained arguments. Top-level declared evaluation flags are false;
nested known-unlifted components retain the existing type-layout rule. These are
not new execution certificates. A consumer must validate the complete descriptor,
actual arguments/result and saturation against its own closed whitelist.

Bare variables, cast/tick function heads, still-polymorphic or interleaved-type
applications, and uncertified export-only erased/wired contexts gain no descriptor
and remain conservative frontiers. No general FFI execution is introduced.

Both real fixture exports prove the following MD5 machine signatures:

| Symbol | Declared argument PrimReps, including State | Arity |
| --- | --- | --- |
| `__hsbase_MD5Init` | AddrRep, [] | 2 |
| `__hsbase_MD5Update` | AddrRep, AddrRep, Int32Rep, [] | 4 |
| `__hsbase_MD5Final` | AddrRep, AddrRep, [] | 3 |

All three return a logical singleton-State unboxed tuple, physically `[]`, not a
scalar State result. In particular CInt is Int32Rep, not machine IntRep. Fixture
declarations use pointer-erased equivalent machine signatures; they do not replace
the original Haskell Fingerprint bodies or claim public Fingerprint execution.

## Actual verification and first failures

All compiler work used the pinned environment and shared resource gate. The
following directories contain actual gate command/output/status records under
`/home/ekmett/.codex/worktrees/98d0/cult/build-agent-logs/`:

- `20260923-234958-kxydjzuc`: installed GHC API query, passed.
- `20260923-235123-b5s4cq2v`: unmodified-exporter baseline build and pre/post fixture
  exports, passed first attempt.
- `20260923-235217-xd8_irzf`: modified-exporter build and pre/post fixture exports,
  passed first attempt.
- `20260923-235423-lgwdxkzt`: dedicated reader normal and Python `-O`, both passed.

Each stage has eight exact descriptors: the three MD5 declarations, same-symbol
safe/interruptible/wrong-signature controls, a dynamic call and an unknown symbol.
An ordinary Haskell identifier spelled `__hsbase_MD5Init` gains no descriptor and
remains accepted. All eight foreign roots retain one missing global plus the
unchanged unboxed-tuple host-result frontier. Sixteen independent malformed-reader
controls reject altered numeric JSON types, arities, target/unit/function flag,
convention/safety and argument/result representations. These are exporter-reader
checks, not a runtime FFI validator.

Two initial generic `scripts/compare-executable-core.py` comparisons reported
differences, one per stage. This paragraph is a reconstructed description from
the tool transcript, **not raw logs**. The existing general comparator treats
unresolved foreign variable IDs as stable external names; their preserved GHC
unique suffixes changed (for example `_d1md` to `_d1me`). Diagnosis did not trigger
a compiler rerun or change `varKey`. The dedicated comparison checks all existing
expression/proof fields after removing only the added descriptor and source-note
metadata, permitting a consistent bijective module-local unique-only rename.
It passed for both stages (61 pre-Tidy and 59 post-Tidy renamed IDs) normally and
under `-O`. No runtime capability/frontier was relaxed to make it pass.

## Retained Core identities

Paths are under the isolated clone's `build/foreign-call-signatures/`:

- `before-pre/ForeignCallAudit.json`:
  `d2f40f09535ed05e216e93040b220199373b12f17614f3e14601676912f7c895`.
- `before-post/ForeignCallAudit.json`:
  `14731cb33eccaad857b97425db99e75843534bffd309657058b3991f667bc9cd`.
- `after-pre/ForeignCallAudit.json`:
  `f3b981479ac4bfae0b16f8ec2bf0dfa2f66092456c7093ef78ce53139a52e34c`.
- `after-post/ForeignCallAudit.json`:
  `e3ef6c6120ba6e995f660f018de522231579161d33e1fd682ba91e6743f70e05`.

Integration next: cherry-pick the tested compiler commit; parent/root supply the
closed original-unit MD5 signature whitelist and actual managed-memory implementation.
Regenerate original source exports with this exporter before consuming the metadata.
