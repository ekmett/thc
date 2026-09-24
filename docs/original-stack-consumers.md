<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Original stack consumer evidence

`compiler/test-fixtures/OriginalStackAudit.hs` imports the original
`cloneMyStack`, `decodeStackWithIpe`, and `prettyStackFrameWithIpe`. It also
exposes small original `peekItbl`, `lookupIPE`, and `peekInfoProv . ipeProv`
consumers. It introduces no foreign declarations and does not rewrite a
formatter. Exact exported IDs select entries: pre-Tidy worker bindings can
share a public function's occurrence name.

`StackFixtures.prepareOriginalStack` belongs to the existing Haskell
`thc-fixtures` framework and uses `FixtureSupport` for process logging and
artifact hashes. Its CLI registration is an integration responsibility.
Preparation exports fresh pre/post consumer Core with the existing compiler
workflow, runs the native driver, verifies the tracked portable proof resource,
then publishes `build/original-stack/manifest.json`. No private export directory
or environment variable is required.
Every attempt has a separate `run-N` directory; a preceding manifest is archived
before work begins, and a failed attempt cannot leave a success manifest.

The manifest's `inputHashes` and `artifactHashes` use the shared native-cache
schema. Required inputs include the producer/shared helpers, Main/Cabal
registration, compiler workflow, original pinned sources/license and portable
resource—not an audit result or expected guest values. GHC is version-gated at
9.14.1; its executable/installation is not hashed. Kotlin verifies the exact
input set and canonical path containment, including symlink resolution.
Run preparation and tests through the host resource gate, with the JVM lease
for Gradle, as for other fixtures.

The native driver decodes the *same* original snapshot twice. It requires a
nonempty stack, stable frame count/provenance/rendering, and sensible rendering
counts; it forces rendered text to expose failures. Its tab-separated output is
`native-shape`, frame count, provenance count, rendered-line count, character
count. These counts are not a cross-platform oracle and must not be compared
with a JVM stack. Zero provenance is legitimate when relevant native info-table
maps are absent.

`OriginalStackConsumerProofTest` checks provenance and derives fresh closure
evidence with the production Core linker. It records missing definitions
separately from declared foreign calls in `build/reports/original-stack/proof.json`,
outside the native fixture receipt directory. This is a structural frontier
report, not a passing runtime decoder audit.

`compiler/test-fixtures/OriginalStackProof.json` is a reviewed, approximately
197 KiB excerpt of the unchanged post-Tidy source exports of
`GHC.Internal.Stack.CloneStack`, `GHC.Internal.Stack.Decode`,
`GHC.Internal.InfoProv.Types`, and `GHC.Internal.Heap.InfoTable` from THC
`62e3400c5b889d3971cb4047709c408fd270255f`, GHC source
`902339d332fb4ce2b3c87dcac1ee6495d41ad886`. It retains all 28 complete foreign-app
subexpressions, not whole modules or synthesized binding bodies. Each occurrence
has its original owner and zero-based JSON path, with shortest original global
reference chains from public roots. Owner hashes use UTF-8 compact JSON preserving
original object-key order. Full-export hashes, original GHC source paths/hashes,
exporter revision and boundary remain explicit. Formatting the resource does not
alter any copied expression value. Both producer and JVM proof pin its file hash.

These are licensed retained excerpts, not a fresh export, executable Core module,
full dependency closure, or replacement for missing installed unfoldings. The
original source carries the GHC BSD-3-Clause copyright/license; the notice is
retained in the resource and `compiler/pinned-ghc-internal/LICENSE`. No full
`peekItbl` binding or decoder body is duplicated. Fresh `peekItbl`/InfoProv
consumer frontiers are still reported, without a retained substitute graph.

Normal proof checks always run. For an optional additional source comparison,
set `THC_STACK_RETAINED_ROOT` to a preserved `source-exports` directory. The JVM
then checks the exact four full-export hashes, each owner hash and JSON-path
expression, and uses the production linker to compare the complete reachable
foreign occurrence multiset. Absence of that optional archive does not skip the
portable provenance, descriptor, inventory, corruption or native-shape checks.

The proof requires all 15 original foreign symbols: clone, lookupIPE, and all
13 stack getters, including cold BCO/RET_FUN/bitmap/closure/underflow branches.
Their declarations, result components/flat representations and call flags must
remain exact. No runtime support is inferred from recognizing their contracts.
Corruption tests cover missing/duplicate occurrences, altered declarations/flags,
broken reference chains, forged freshness/source provenance, missing required
inputs, symlink escapes and malformed native output.

No stack-layout image, IPE registry, pointer-cell transport, annotation protocol,
runtime fallback or high-level source adapter is implemented here. Full original
Decode execution still requires truthful implementations for every reachable
operation and the missing source dependencies recorded in the report.
