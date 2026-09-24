# Exact Float frame reconstruction, not a live-payload exemption

The corrected runtime's first reader check failed on virtual long[] node193
in all four AST-store graphs. It has only two Value uses: owner
VirtualObjectState slot3 (`indexedPrimitiveLocals`) and its own state object's
`object` input. The corresponding byte[] tags node194 belongs to the same
six-field FrameWithoutBoxing instance188. Slots11–14 have exact tag4 and four
f32 L2F entries; no Object slot values. All state mappings enter only the
ByteVector.intoArray deoptimization FrameState, never a return or call value.

For vector stores the owner/primitive/tag states are2540/2562/2563 and the
FrameState is1959. Scalar-store state IDs are2541/2563/2564. Node identities
are observations, not hard-coded whitelist entries in the recognizer.

Pinned compiler source was inspected directly from
`/home/ekmett/thc-benchmarks/2026-09-23-083914/toolchains/graalvm-25.3.4.1+1.1/lib/src.zip`:

- Archive SHA256: `42392fa5e3c02fa4d396ecf21d552896c23a33db7d70049efb0efaf95c6e6c1a`.
- `jdk.graal.compiler/jdk/graal/compiler/truffle/nodes/frame/NewFrameNode.java`,
  lines81–88: Float tag4. Whole member SHA256:
  `e231622e7988b8ef16b0cd61d8cd2abb9481064b8011bbf59a182375caf1d61f`.
- `jdk.graal.compiler/jdk/graal/compiler/truffle/nodes/frame/VirtualFrameSetNode.java`,
  lines84–101 and123–132: normal virtual frame writes retain the original
  value kind in primitive storage; forcing primitive values to long is specific
  to OSR/static access. Whole member SHA256:
  `cab656692ace0a63f2c69a3e40fe7f7683a33b83c475c08e1a6592b65b0afa6a`.

Reader commit `1dff41f3a7bf04009e5eff7d35c8f368e60b082d` admits tag4 only
with absent/default or exact f32 primitive entries and absent object entries.
Tag1 still requires i64; tags0/7 forbid primitive entries; other tags remain
rejected. Exact owner, sibling arrays, slot counts, unique per-frame mappings,
snapshot kinds and non-escape checks remain mandatory. Unknown metadata still
fails closed. The three added test methods cover positive/default Float values
and hostile width/tag/object/owner/slot/length/materialization/escape/snapshot
mutations. Original live AST enum-dispatch failures remain rejected.

`check.log` and exit1 retain this second, reader-only failure. `recheck.log`
and exit0 record the provenance-bound offline correction. The original reader
sources are kept as `capture-*.py`; evidence.json records original/corrected
hashes and requires every non-reader source, installed JAR, JDK file and native
input unchanged. No guest code was replayed for this correction. This is
distinct from the genuine runtime failure in `first-runtime-capture/`.
