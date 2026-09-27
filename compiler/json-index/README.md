# JSON structural scanner

The scanner is a C transcription/adaptation of John Ky's **succinctly**, pinned
at `6ee3210413d1f180fd6a93ab30c5bc6aaad29b78` in `rust-works/succinctly`:

- `src/json/simple.rs`: three-state quote/backslash automaton and interest bits;
- `src/json/simd/avx2.rs`: 32-byte comparison classification;
- `src/json/simd/neon.rs`: nibble-table classification, paired 16-byte chunks,
  multiplication movemask, and trailing-zero skipping of string runs.

The original MIT copyright 2025 rust-works is retained in
[LICENSE.succinctly](LICENSE.succinctly). John Ky is credited for the source
implementation. The changed encoding is THC's responsibility.

The portable scalar implementation is always available. AVX2 is compiled only
inside a target-specific function and selected after runtime CPU/OS feature
checks; no project-wide AVX2 requirement is introduced. AArch64 little-endian
builds use NEON, which is part of that target's baseline. Other targets use the
scalar path. No vector load extends past the input bytes.

`thc_json_scan_block` regenerates at most 512 bytes of Simple Cursor interest
bits from an explicit incoming quote/escape state. It returns the outgoing
state. Open and close masks are disjoint subsets; the remaining interest bits
are commas/colons. They can generate the original two-bit Simple Cursor BP
encoding or a container-only one-bit encoding. The common mask traversal also
skips non-structural runs outside strings. It does not validate JSON grammar,
UTF-8, numeric values, bracket matching, or single-root/trailing-value rules.

Standalone parity check (no Haskell/JVM build):

```sh
cc -std=c11 -O2 -Wall -Wextra -Werror -Icompiler/json-index \
  compiler/json-index/json_index.c test/json-index/native.c -o /tmp/thc-json-scan
/tmp/thc-json-scan
```

The test compares all supported backends with an independent scalar model,
including every byte class, initial state, short tail, unaligned input, and
carry across 512-byte boundaries. Explicit unavailable backends are rejected.
