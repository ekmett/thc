# MD5 callee/closure follow-up — scoped approval

Read-only review of `/home/ekmett/ai/thc-pinned-addresses-01a0cdeb` at
`3e2c124e039926c165e907b034cc80bb9197b50b`, runtime checkpoint
`2cbf1aa5f369658100fd7137a3725ba9dd85a639`. The six relevant production/test
files are unchanged between these commits; no tracked changes were present.
The untracked evidence directory belongs to the root's active validation.

The finding in `20260924T040432Z-md5-dispatch-port-review.md` is closed:

- `CoreMd5Foreign.validateHead` requires exactly a three-element variable node,
  nonempty string ID, and exact evaluated lifted-closure proof. Missing, extra,
  aggregate, unevaluated or wrong-kind proofs cannot select the adapter.
- Both loaders run `validateHeads(bindings)` before aggregate/input-call and
  function/capture analysis. Recognized MD5 targets with malformed heads now
  raise the intended `RuntimeFault`, including null IDs previously reaching an
  unchecked cast. This pass also runs in diagnostic mode.
- At dispatch, AST checks local/tuple, join and global bindings; bytecode checks
  local, tuple, join and global bindings. Captured ordinary locals retain their
  identity. Existing join validation rejects escaped joins. A resolved Haskell
  callee cannot be silently replaced by an MD5 effect.
- The auditor requires the same exact head/proof and an ID absent from lexical
  and global bindings. Rejection records `foreign-call-representation` and
  resumes ordinary dependency traversal. Missing/unknown descriptors retain the
  unresolved-foreign frontier; ordinary same-named Haskell functions are not
  intercepted. No synthetic-name parsing fallback was introduced.

Regression coverage inspected: JVM tests exercise all three symbols in both
loaders, including null/empty/numeric IDs, formal/global collisions and four bad
head proofs (54 targeted head rejections), plus main-unit/missing-descriptor
controls. Auditor tests additionally demonstrate ordinary global/local/formal
calls accepted without a descriptor and rejected with one, exact head proof
mutations, capability gating and unknown/same-name frontier preservation.
Join/tuple collision and diagnostic-mode head cases are not separately
parameterized JVM regressions; their guards are present in source. This is not
an exhaustive malformed-Core validation claim.

Read existing saved XML only: `Md5ForeignCallTest` has 2 tests and zero
failures/errors/skips in both `build/pinned-address-focused-32-pass` and
`build/pinned-address-focused-dense-32-pass`; the dense log records
`-Dthc.handoffSlabs=true`. The retained first-head-guard failure remains present
and explicitly records the earlier null-ID `CoreInputCalls` NPE, not a passing
attempt. XML SHA256 values, respectively:

```
7531da841d6967bd6b3a15e7b4ca43c7f874c1aaa9d8f0775cbc1284d02527bf
c770a7f63f3c732980c918b2e6dedc89561a8b301780018917d7fc120af032cb
cbb76d1dc12e76f850018ff4a534c873e7f75f472d832466151944eae30f4480
```

No new actionable production finding. No build, fixture preparation, JVM,
guest execution, source edits, or test reruns performed for this review. This
does not certify the still-running canonical suite or original public
Fingerprint execution. The separately requested exporter-reader compatibility
check is follow-up work and is not included in this approval.
