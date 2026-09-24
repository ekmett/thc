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
artifact hashes. Its CLI registration is an integration responsibility. Set
`THC_STACK_RETAINED_ROOT` to the preserved `source-exports` directory containing
the four pinned module JSONs below. Preparation exports fresh pre/post consumer
Core with the existing compiler workflow, runs the native driver, verifies and
copies the retained modules, then publishes `build/original-stack/manifest.json`.
Every attempt has a separate `run-N` directory; a preceding manifest is archived
before work begins, and a failed attempt cannot leave a success manifest.

The manifest contains source/artifact hashes, command exits, native output and
explicit retained-export provenance—not an audit result or expected guest
values. GHC is version-gated at 9.14.1; its executable/installation is not hashed.
Run preparation and tests through the host resource gate, with the JVM lease
for Gradle, as for other fixtures.

The native driver decodes the *same* original snapshot twice. It requires a
nonempty stack, stable frame count/provenance/rendering, and sensible rendering
counts; it forces rendered text to expose failures. Its tab-separated output is
`native-shape`, frame count, provenance count, rendered-line count, character
count. These counts are not a cross-platform oracle and must not be compared
with a JVM stack. Zero provenance is legitimate when relevant native info-table
maps are absent.

`OriginalStackConsumerProofTest` checks provenance and derives closure evidence
with the production Core linker. Fresh consumer/interface exports and the
four-module retained source subset are separate graphs: one is never silently
substituted for the other. It records missing definitions separately from
declared foreign calls in `build/original-stack/proof.json`. This is a structural
frontier report, not a passing runtime decoder audit.

The retained subset is the unchanged post-Tidy source export of
`GHC.Internal.Stack.CloneStack`, `GHC.Internal.Stack.Decode`,
`GHC.Internal.InfoProv.Types`, and `GHC.Internal.Heap.InfoTable` from THC
`62e3400c5b889d3971cb4047709c408fd270255f`, GHC source
`902339d332fb4ce2b3c87dcac1ee6495d41ad886`. Exact module hashes are checked by
both producer and JVM proof. This is not a fresh export or a full dependency
closure. The original exported source carries its original GHC licensing;
the corresponding notice is in `compiler/pinned-ghc-internal/LICENSE`.

The proof requires all 15 original foreign symbols: clone, lookupIPE, and all
13 stack getters, including cold BCO/RET_FUN/bitmap/closure/underflow branches.
Their declarations, result components/flat representations and call flags must
remain exact. No runtime support is inferred from recognizing their contracts.
Corruption tests cover missing cold owners, altered declarations/flags, forged
freshness/source provenance and malformed native output.

No stack-layout image, IPE registry, pointer-cell transport, annotation protocol,
runtime fallback or high-level source adapter is implemented here. Full original
Decode execution still requires truthful implementations for every reachable
operation and the missing source dependencies recorded in the report.
