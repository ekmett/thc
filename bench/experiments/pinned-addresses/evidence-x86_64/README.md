# Managed pinned-memory and closed MD5 checkpoint

This is Linux x86_64 evidence from `eak-quartus` (Intel i9-12900K), with GHC
9.14.1 and GraalVM 25.3.4.1/JDK 25.0.4.1. `source-identity.json` records the
tested source commit and tree; `source-ancestry.txt` lists the **complete**
feature range after base `9a7fef75cc4ce44cb2b1031c61c92aadffb78859`.
The last source commit alone does not contain the feature. This is not the
integration owner's newer union branch or a cross-platform CI result.

The slice covers six primops (`newPinnedByteArray#`,
`newAlignedPinnedByteArray#`, `byteArrayContents#`, `readWord8OffAddr#`,
`writeWord8OffAddr#`, `keepAlive#`) and three exact, structured MD5 foreign
contracts. See `docs/pinned-memory.md` for semantics and limits.

## Retained gates

`checks.json` summarizes completed JVM runs from their retained JUnit XML,
resource-gate exit records and exact compiled native-row markers. The focused
default and dense-handoff runs each have 32 passing tests, including all six
new suites. Full default and dense runs each pass **665 tests / 136 suites**,
with zero failures, errors or skips. Each covers pre/post-Tidy x AST/bytecode x inline/residual calls:
40 primitive markers, 51,096 reversed compiled rows, and 197,904 exact guest
entries using the source-proved three/four-call paths. These are checked guest
entries, not a claim that every MD5 round executes inside compiled guest code.
The MD5 kernel deliberately remains an explicit host boundary.

The Python receipts cover 169 tests normally and 169 under `python3 -O`,
with no skips. These include all 41 new tests and relevant existing auditor,
array, address, tuple and sum checks. `python/results.json` records each exact
command and exit status.

The separate genuine foreign-call reader checks retain both original export
stages, descriptor-free baselines and exact reader results: eight descriptors
and eight rejected foreign frontiers per stage, the accepted ordinary
same-named Haskell control, and 16 malformed-reader controls. The post-review
reader-only correction is tested separately from the JVM source snapshot.
Its root normal/optimized results and extended feature ancestry are under
`reader-root/`; the correction changes only the fixture reader expectation.

`native/` retains manifests, original outputs, genuine Core, referenced source
inputs and native executables. Pinned-memory native/model equality covers
7,269 rows: 6,387 supported primitive rows and 882 **native-only public Storable
frontier rows**. Both stages also reject 12 malformed-proof controls each.
The original C MD5 oracle covers 558 cases, 2,247 full memory snapshots,
540 independently checked digests, counter carries, padding and defined aliases.
`verified-native-hashes.json` records 76 independently rechecked source,
artifact and tool hashes. The original 18-file MD5 preparation attempt is
archived intact; all 17 artifact hashes in its original provenance still match.

## Preserved first failures

These development attempts predate the final tested source. Their logs are
historical evidence, not runs attributed retroactively to the final commit.

1. `first-focused-15-failure`: 14 passed; the direct AST test read an
   intentionally Object-widened frame slot using `getLong`. The test now uses
   the runtime's `FrameAccess.read`, with the value assertion unchanged.
   The console retains all 15 results; the saved XML is only the five-test
   `PinnedAddressTest` class containing that failure, not the other classes.
2. `first-head-guard-32-failure`: 31 passed; a malformed null foreign-call head
   reached generic input analysis and produced an NPE before the intended
   explicit rejection. Early `validateHeads` now rejects it before analysis.
3. `first-full-prepare-failure`: preparation stopped before any full JVM suite
   at `FileExistsError` because the focused native preparation already existed.
   The preparer now validates ownership and archives prior attempts intact;
   ten safety tests cover unexpected contents, symlinks and partial attempts.
4. `first-foreign-reader-failure`: the compiler fixture reader expected the old
   missing-global/aggregate report. It now additionally requires the exact
   main-unit MD5 contract rejection; no runtime/auditor rule was relaxed.

Intermediate 25-test and corrected 32-test runs are retained separately. No
settling retries, compiler-policy changes, heap/code-cache increases or weakened
compiled-entry assertions were used to obtain passing results. The unrelated
previous Short CI failure remains a separate unresolved integration issue;
passing this feature checkpoint does not establish its cause or fix it.

## Reproduction and interpretation

With the pinned toolchain on `PATH`, use the shared host build lease where
applicable, then run:

```sh
scripts/prepare-tests.sh
scripts/gradle.sh --offline --no-daemon --max-workers=4 test --rerun-tasks
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true \
  scripts/gradle.sh --offline --no-daemon --max-workers=4 test --rerun-tasks
```

The commands above describe the full two-mode reproduction. Consult each
retained run's `command.json`, `result.json` and JUnit archive for exactly what
was executed at this checkpoint. Toolchain installation and dependency cache
are prerequisites for `--offline`; `tool-identities.json` records this host.
Verify the sealed payload from this directory with `sha256sum -c SHA256SUMS`.
Archives use repository-relative member paths except the clearly labeled
external `HsFFI.h` input. Tool binaries are hashed, not bundled.

Pinning/alignment is logical managed storage, not physical JVM pinning or raw
native pointers. No address/integer conversions, general FFI, arbitrary
allocation/free, or replacement Haskell library bodies are supplied. Original
public Fingerprint/Typeable/error source composition belongs to the integration
owner and is **not claimed** by primitive fixtures or synthetic adapter tests.
The labeled `fingerprintByte` fixture checks layout, not a rewritten Storable
implementation. Synthetic descriptors remain labeled synthetic; main-unit
declarations are never relabeled as original `ghc-internal` calls.
