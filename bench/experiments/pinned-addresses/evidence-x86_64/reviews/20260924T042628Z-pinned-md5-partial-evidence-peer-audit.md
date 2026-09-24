# Partial pinned-memory / MD5 evidence audit

Read-only audit of `/home/ekmett/ai/thc-pinned-addresses-01a0cdeb/bench/experiments/pinned-addresses/evidence-x86_64`
at tested source `3e2c124e039926c165e907b034cc80bb9197b50b`, runtime
`2cbf1aa5f369658100fd7137a3725ba9dd85a639`. The package was explicitly **partial
and unsealed**; full suites were still running. No absence of full-suite receipts,
`checks.json`, or `SHA256SUMS` was treated as a defect. No build, preparation,
JVM, native executable, or guest was run. Archive contents were read in memory,
without extraction, and checked for duplicate, absolute, traversal or link members.

## Verdict

No numerical, hash, source-attribution or scope discrepancy found in the completed
evidence below. One retention limitation should remain explicit: the first
15-test failure's XML archive contains only the failing PinnedAddressTest suite
(five tests, one failure), not all three original suites. Its retained console
does preserve all 15 test identities, 14 passes and the one failure, so the
historical 15-test claim is supported by the console rather than complete XML.
This review does not sign off pending full-suite results or a final package seal.

## Independently verified inputs and native evidence

- All **76** `verified-native-hashes.json` records match: pinned-memory 22 source
  inputs + 24 artifacts + two GHC binary/launcher identities; MD5 eight source
  inputs + 18 artifacts + two compiler/tool identities. Absolute tool files were
  read only, not invoked. The external HsFFI.h archived copy also matches its hash.
- All **68** files in `tested-source-snapshot.tar.gz` equal the exact Git blobs
  at `3e2c124`; all 22 pinned manifest source hashes match those archived bytes.
- All **7,269** pinned native rows equal the archived expected rows. Independently
  reconstructed every result without importing the preparer/model: a mutable byte
  cell model for allocation/alias controls, modulo-256 increments for keepAlive,
  and big-endian mask/shift extraction for Fingerprint byte controls.
  Counts: pinnedBytes 1,859; alignedBytes 3,718; keepAliveWord8 13; keepAliveLazy 13;
  fingerprintByte 784; publicFingerprintByte 784; publicFingerprintRoundtrip 98.
- Both stages' five primitive roots have accepted, zero-issue, zero-missing audits.
  All **12 malformed controls per stage** have nonempty rejection evidence.
  The public Storable roots remain rejected: 11/12 missing globals respectively,
  four issues each, in both stages. Their **882 rows are native-only frontiers**,
  not part of the 6,387-row runtime-positive subset.
- MD5's original archived first attempt contains exactly **18 files**, with all
  **17 artifact hashes** matching its original provenance. The current native
  archive's eight sources and 18 artifacts also match their own manifest.
- Independently read **2,232 case snapshots** (558 cases × four phases) and
  **15 alias snapshots** (five cases × three phases), checking uniqueness,
  dimensions, sentinels, counter updates and context clearing. Reconstructed
  message bytes and checked all **540 ordinary final digests with hashlib**.
  This is an offline cross-check of archived output, not native execution or an
  independent reimplementation of the seeded-counter compression rounds.

## Focused JVM and Python receipts

Each completed focused mode has exactly **32 tests across six suites**, zero
failures/errors/skips, identical complete testcase identities, and an exit-zero
resource result. The dense log explicitly records
`JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true` for the Gradle and test JVMs.

Each mode's XML contains the exact, duplicate-free 40-element matrix:
five primitive entries × pre/post × AST/bytecode × inline/residual. Its rows sum
to **51,096 reversed compiled comparisons** and its source-proved three/four-call
weights sum to **197,904 checked guest entries**. These are per mode, not counts
of MD5 rounds or proof that the explicit host MD5 boundary is guest-inlined.
The seven independently authored CoreKeepAlive tests are present and passing in
both focused archives (unlike the earlier source-only delivery).

All 20 Python receipt commands have exit zero and matching clean `OK` logs:
**169 normal + 169 optimized tests**, no skips. The four new Python suites total
41 tests per mode; relevant existing auditor/array/address/tuple/sum tests make
up the remainder. No tests were rerun for this evidence audit.

## Failure and scope honesty

- The first focused console records 14 passes / one FrameSlotTypeException in the
  direct State/effect test. Its five-test XML identifies the same failure.
- The first head-guard archive has all 32 tests: 31 passes and one expected
  RuntimeFault-vs-actual-NPE assertion failure in the null foreign-head control.
- The first full-preparation log ends in the documented FileExistsError before
  any full JVM suite. The successful old MD5 output was retained, not overwritten.
- The first foreign-reader assertion failure is retained separately from the
  corrected reader's normal/optimized logs. Archived Core has zero descriptors
  before export changes and exactly eight afterward in each stage. The corrected
  reader logs report eight strict foreign frontiers, one ordinary Haskell control,
  and 16 malformed descriptor controls; this is not general FFI acceptance.

README/docs distinguish logical managed pinning from physical addresses, native
Storable frontiers from primitive layout controls, synthetic MD5 adapter tests
from original ghc-internal source composition, and host-boundary hashing from
fully inlined guest hashing. Historical failures are not attributed retroactively
to the final source. The pending reader-only integration and pending full gates
remain outside this completed-evidence verdict.

## Artifact identities at review (SHA-256)

| Artifact | SHA-256 |
|---|---|
| source-identity.json | b180bd002ffca2a429d83c45842e823d581c7a82d796cb32969a5758bed47703 |
| tested-source-snapshot.tar.gz | cc26a5092cf185be54db8b7ac3c09c1110e6fd93dcf256c0e1a83505dc6a8ac4 |
| verified-native-hashes.json | 43130ce951283feb618644ea1563b0ab13d8e85d05c0ae745e887b73a653be2e |
| python/results.json | b1f4b9a606b535efd909fec200c2c001e37fd54b0b91c1dccb1b8d6e19c212b6 |
| native/pinned-inputs-and-artifacts.tar.gz | cef7b40299bacace82b58b031762047e9698c96430891e8243a4a5df1a2af183 |
| native/managed-md5-inputs-and-artifacts.tar.gz | 69de7b85a8e0e8e570db4e4f2f55b7c5c0d5d1edfc9c99f2d22ee9df89c6ea47 |
| native/preserved-first-md5-attempt.tar.gz | 4bdb1d89e4626f4c4b8ebfe904aa2fbbe709008e8838d07dcc54b59777557f61 |
| native/foreign-call-before-after-exports.tar.gz | fe968a1998af0656aeb448bf18c56805f48aa9aa0bec1ec544afa554b043d8ff |
| junit/focused-default-32-pass.tar.gz | 2a8c270d99ca2c2dd3779da581dd7478c27726492b7da2c81eaee455ba46a095 |
| junit/focused-dense-32-pass.tar.gz | f29726835b3bbc8eb4d3011d64ae0c377fc5f5fc7f1bf789122965ea21922f9f |
| junit/first-focused-15-failure.tar.gz | 1a12fce5afc1776677fc90a81b251b68a1554612a844c7e27ebf1f0f2f6ee3b6 |
| junit/first-head-guard-32-failure.tar.gz | d72289325f22faadf242ee1db85f6a0a8cac6cfb41e7d89d038d9ea0b8f86748 |
