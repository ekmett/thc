# DoubleX2 managed-memory checkpoint

Frozen source: `b6ba65f3ba54f937e2b88f61f04e7e4f425a1e5c`, based on Float
`3398db112fbfec5e21f84e8362363fdf14278f42`. Production Double family/backend
integration is `c552c27195cbe4e1c7284ea00255b0f8a9a65927`, with storage at
`74d58e32e240a5348970e1813bf784eefa56f827`.
Host: Linux x86-64, Intel i9-12900K; pinned GHC 9.14.1 and Graal distribution
25.3.4.1+1.1 / JDK25.0.4.1+1-LTS-jvmci-25.3-b22.

Exactly six local index/read/write operations are added, with a 16-byte access
and either 16-byte vector stride or 8-byte scalar stride. The existing immutable
DoubleVector carrier transfers raw bytes through ByteVector/LongVector views.
The sole new closed family requires `VecRep 2 DoubleElemRep`; immediate read
cases retain two logical fields, State and vector. No vector formal, capture,
join, heap, foreign/Addr, pinned-memory or transported tuple ABI is introduced.
No production compiler policy, graph budget, retry or settling change is made.

## Results

First focused 10 and native-focused 15 JVM tests pass. Full default and dense
handoff each pass 545 tests in 112 suites, with identical method sets and zero
failures/errors/skips.
The initial focused run retains its exact base, runtime diff and new-test hashes;
the full campaign's clean frozen revision is recorded separately.

All four memory families pass sixteen fresh captures each on their first
checks, with no reader correction: 64 accepted graph/LIR records and 3,840
installed-target comparisons on one frozen runtime. Double contributes 512
comparisons, sixteen live f64 weighted products and sixteen exact L2D store
inputs, with eight XMM VMOVDQU32 loads and eight XMM VMOVUPD stores. Float
contributes 1,024 comparisons, 32 f32 products and 32 store inputs, with eight
VMOVDQU32 loads and eight VMOVUPS stores. Signed and unsigned integer families
each contribute 1,152 comparisons and sixteen VMOVDQU32 accesses; all 32 signed
or zero-extended load lanes and 32 store inputs remain explicit per family.
Every invocation uses fresh caller storage, checks all 64 bytes and preserves
store result identity. Double graph evidence SHA-256:
`abd45fa1ff4de2d4ad5b039116053aa57aac7f47ca4db95da20a9f1b08f9add2`.

The portable native/model corpus contains 4,384 byte-identical rows across
twelve scalar wrappers. Sixteen raw encodings rotate through both lanes and
all safe offsets, observing every byte of each 64-byte backing array and alias
snapshots. This is not a full pattern-by-offset Cartesian product. Signed Int#
and Double scalar-array aliases observe bits without adding scalar bitcast
primops. Movement covers signed zero, subnormal/normal boundaries, finite
boundaries, infinities and selected signed quiet-NaN payloads. Raw store
witnesses XOR the input words and the selected byte; no overflowing floating
arithmetic is used as a raw-bit oracle.

The separate 576 signaling-NaN native observations match on this pinned host.
They are excluded from portable JVM rows and finite graph cases, and are not
a portable Java scalar copy/pack/unpack or arithmetic-payload guarantee.
Direct pure-vector carrier tests separately observe twelve selected host-only
signaling-NaN movements. Big-endian per-lane conversion is implemented but not
executed in this campaign.

Native provenance SHA-256:
`a4bffa1cb13dbb5a883601a0ca123cd04af9cc104ec140005be81154fa0628df`.
Twenty selected preparation-source hashes, nine artifacts and the actual GHC
ELF/launcher hashes verify. This is not a complete transitive runtime or GHC
package inventory. Each graph snapshot separately covers 75 runtime/probe/reader
sources, eleven installed JARs and four selected JDK files. Sources also match
the frozen Git blobs. Both original preparations
retain all nine artifact copies, including their separately hashed Core exports;
GHC local uniques differ between exports without semantic differences.

Each complete JVM mode checks 35,072 compiled calls and 104,704 exact guest
entries across pre/post Core, AST/bytecode and inlining on/off. Every row checks
active target identity, last-tier validity, exact entry deltas and empty handoff
pools. Traps and blackholes must remain zero. JVM tests independently reconstruct
the entire keyed native inventory and every expected byte, rather than trusting
the Python model. Both Core stages also reject 24 exact wrong-family mutations,
including the same-width, same-two-lane Int64 family, and seven ABI frontiers.

The 96 separate cold-transition cases use the existing isolated memory-test
pattern: suppress automatic compilation, explicitly compile a fresh guest,
exercise its first invalid access, check invalidation and no partial effect or
failed result publication, then recover without recompilation. The portable
corpus retains ordinary automatic settings. Invalid native offsets, which would
be undefined, are never used as an oracle.

Python checks pass without skips normally and under `-O`: 294 per mode across
14 programs. This includes 45 Double reader tests; reader tests and historical
graph rejection controls are not new compiler execution evidence. Earlier
pre-preparation auditor/vector checks had twelve missing-fixture skips, retained
as such; they are not counted as passing final checks.

## Failure attribution and limits

No native, JVM, compiler or actual-capture checker failure occurred in this
campaign. All eight serial validation stages returned zero.
The graph worker's initial inherited reader tests had two stale Float test
expectations (four lane products, and a SINGLE/VMOVUPS positive). Only test
inputs/expectations were corrected; the reader was unchanged. The initial tool
output is retained as an explicitly labeled reconstruction, not a raw redirected
log. Two supplemental reviewer-script comparisons also asserted on assumptions
of byte-identical regenerated Core and structured-only unique names. Read-only
diagnosis established bijective unique renaming and diagnostic-text suffix
changes; both original copies passed their own hash and semantic checks. The
independent review records these as reconstructed descriptions, not native
failures. No native artifacts or guest runs were replaced or replayed.

Graph instrumentation is off: captures prove unchanged installed targets,
not per-call compiled-entry counters. The separate correctness suite provides
the latter. Fresh caller arrays and public Long result boxes are intentional.
Accepted selected paths forbid live private carrier/vector/payload allocations,
fields and unexpected calls; exact reconstruction-only frame metadata is not
executable payload. This is not a throughput, no-spill, globally allocation-free,
big-endian or non-x86 claim. Interpreted fallbacks may allocate.

## Package layout

`captures/` retains four families independently. Each includes source/runtime
snapshots, native inputs, Core, graph/LIR JSON/text, commands, logs and statuses.
Compressed payloads decode to exactly the original hash-covered bytes. Raw
BGV/CFG originals remain in the local build directories, were rehashed and are
referenced, not duplicated here. Earlier feature packages are not rewritten.

`native/` retains current audits, provenance, native/model rows and both complete
preparation histories. `validation/` preserves exact commands, logs, statuses,
JUnit archives, initial failures and independent input review. `python-checks/`
contains every final Python command, log and status. The packaging/report
scripts are included for reproducibility; their absolute paths describe this
capture host, not a portable rerun environment.

`copy-records.json` maps retained files and archive members to original hashes.
`package-verification.json` records independently checked totals and chain
verification. `SHA256SUMS` seals every other package file; its hashes cover
compressed bytes, whereas provenance covers uncompressed originals. Neither is
a cryptographic signature.
