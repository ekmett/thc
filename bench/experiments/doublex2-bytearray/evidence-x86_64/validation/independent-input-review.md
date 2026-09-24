# DoubleX2 ByteArray input evidence: independent read-only review

Reviewed root: `/home/ekmett/ai/thc-doublex2-bytearray-01a0cdeb` at clean
`b6ba65f3ba54f937e2b88f61f04e7e4f425a1e5c`.
Reviewer: `/root/int8x16_fixtures_xhigh`.
Result: scoped approval; no production or retained-input gap found.

This is a durable summary of completed checks, not raw command logs. Creating
this handoff did not rerun any check. The review performed no builds, native/JVM
execution, source edits or commits. It makes no full-suite or captured-graph claim.

## Exact provenance identities

All root paths below are relative to `build/simd-doublex2-bytearray/`.

- Current `provenance.json`, identical to
  `prepare-run-1z9e8vr8/provenance.json`:
  `a4bffa1cb13dbb5a883601a0ca123cd04af9cc104ec140005be81154fa0628df`.
- Earlier retained `prepare-run-hb97juwc/provenance.json`:
  `8e43d898ceff6cf31455715cfae293bcf9da7a2dc3d69cbc657be4d2147857e1`.
- Separate worker provenance at
  `/home/ekmett/ai/thc-doublex2-bytearray-fixtures-01a0cdeb/build/simd-doublex2-bytearray/provenance.json`:
  `71b0030b5d901b6450ba07d19a765ac7f5860173f4f7d299b447e0d6325815cf`.

Root and worker provenance are not interchangeable. Their only differing selected
source is `scripts/prepare-doublex2-bytearray-audit.py`: root additionally checks
the same-two-lane `Int64ElemRep` wrong-family controls.

## Verified scope and counts

Both retained root preparation directories have twenty selected source hashes,
nine artifact hashes and complete internal artifact copies with copy manifests.
Every source matches the current file and committed HEAD blob. Every artifact and
retained copy matches its own recorded hash. This selected source inventory is
not a complete transitive runtime inventory. Each preparation retains six command
records, stdout/stderr files and six zero exit statuses.

Schema 1 names the family `doublex2-bytearray`, stages `pre` and `post`, and
little-endian native/model order. Twelve scalar entries contain 4,384 unique
portable rows. Per offset family, Unit/Index/Read/Write/GraphIndex/GraphStore have
64/32/32/1024/16/1024 cases and arities 4/4/4/4/3/4. All signed-Int64 input/result
tokens, raw64 movement, alias snapshots and all-byte store observations were
independently reconstructed with integer bytes and IEEE conversion, without
importing the fixture model. Both native TSV copies exactly match their expected
TSVs and the independent reconstruction.

- Portable `oracle.tsv`, 4,384 rows:
  `1388dfcd827295881532d961de7c599b460758408ffd7f63a61c946442ba4173`.
- Separate `snan-oracle.tsv`, 576 native-only selected signaling-NaN rows:
  `c0ac42f16a1b51d04521ff51ab6b7c5ecdff9d26d6eaec04b063e37af9729eea`.

The signaling-NaN observations match on this pinned native host; they are not
portable JVM rows or a general payload-preservation promise.

Fresh offline checks against both actual retained Core copies reproduced sixteen
positive roots with no issues/missing globals, seven exact frontier rejections,
and twenty-four exact wrong-family controls per stage. Actual retained guest
counts are Unit 2, raw Index 4, other wrappers 3; each of the four graph roots has
one guest root. Eight configurations imply 35,072 calls and 104,704 guest entries
per ABI; this review did not execute those guest calls.

Each stage retains six local read cases, 208 Word32 literal sites, six Double
literal sites and 42 exact vector proofs. The four graph entries each contain
sixteen finite cases; index/store arities are 2/5, strides 16/8 cover all safe
offsets in 64-byte arrays. The independent graph harness `validate_inputs()`
accepted the current proof offline; no graph capture was executed here.

The pinned compiler identities were rehashed from disk, including ELF/shebang
format checks:

- Actual GHC ELF SHA256:
  `0959be71d5d591f141194ddc0bffb03064f4b156ac05ae8f0d6418dafa1ee001`.
- GHC launcher SHA256:
  `9fb9365cc68780907349303deb23ce360de33527a272b06385e27dc7bfe5d6ed`.

## Supplemental reviewer-script assumption failures

The following are reconstructed descriptions from this review's tool transcript,
not retained raw log files and not compiler/native/preparation failures:

1. A supplemental comparison assumed the two preparation provenances would differ
   only in command records. It asserted because their pre/post Core artifact
   hashes also differed. Each original artifact already matched its own recorded
   hash; the assumption of byte-identical regeneration was incorrect.
2. A follow-up comparison assumed every differing JSON leaf was a structured
   module-local identifier. It asserted on the pre-Tidy `sourceCore` diagnostic
   text, which also contains generated module identifiers.

Read-only diagnosis established exact bijective local-unique renaming only:
327 identifiers across 1,671 structured occurrences in pre-Tidy Core and 325
across 1,659 in post-Tidy Core. The pre-Tidy diagnostic text differed only in four
`$trModule` unique suffixes; post-Tidy diagnostic text did not differ. Apart from
command records and these two Core artifact hashes, both root provenances agree.
All saved audits, structure/counts and twenty-four mutation reports per stage
were recomputed successfully against both actual copies. No artifact was edited,
no compiler/native command was rerun, and no evidence gate was weakened.
