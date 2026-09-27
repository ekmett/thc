# JSON structural scanner

The scanner is a C transcription/adaptation of John Ky's **succinctly**, pinned
at `6ee3210413d1f180fd6a93ab30c5bc6aaad29b78` in `rust-works/succinctly`:

- `src/json/simple.rs`: three-state quote/backslash automaton and interest bits;
- `src/json/simd/avx2.rs`: 32-byte comparison classification;
- `src/json/simd/neon.rs`: nibble-table classification, paired 16-byte chunks,
  multiplication movemask, and trailing-zero skipping of string runs.

The original MIT copyright 2025 rust-works is retained in
[LICENSE.succinctly](LICENSE.succinctly). John Ky is credited for the source
implementation. THC owns the sidecar layout and source-lifetime contract.

The portable scalar implementation is always available. AVX2 is compiled only
inside a target-specific function and selected after runtime CPU/OS feature
checks; no project-wide AVX2 requirement is introduced. AArch64 little-endian
builds use NEON, which is part of that target's baseline. Other targets use the
scalar path. No vector load extends past the input bytes.

Windows x86 dispatch checks CPUID and OS-enabled XMM/YMM state directly.
This keeps the vanilla GHC plugin load independent of compiler-rt CPU-dispatch
symbols while retaining the [CPU and OS requirements for AVX](https://cdrdv2-public.intel.com/821612/248966-Optimization-Reference-Manual-V1-050.pdf).
The native executable test compares that selection with compiler-rt's detector;
the Windows export smoke also loads the actual static plugin and encoder.

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

## Sidecar v2 producer

`cabal run thc-json-index -- INPUT.json OUTPUT.idx` reads one immutable source
snapshot and writes a navigation sidecar. It does not change the input, parse
scalar values, certify JSON syntax, or admit Core. There is no default format
switch. Use `--backend scalar`, `avx2`, or `neon` before the paths to select an
explicit supported backend; the default is runtime dispatch.

Two safe native calls count structural events and fill the final sections. They
reuse 192 bytes of stack masks, resolve the ISA once per pass, and retain no
interest bitmap or per-quarter Haskell objects. A third source pass computes the
source SHA-256. Output uses the final section buffers directly; it does not copy
an assembled sidecar before writing. Peak producer memory includes the complete
immutable JSON snapshot plus its section buffers. This is not a streaming JSON
reader claim.

All integer fields are little-endian. The envelope is:

- 64-byte header: `THCJSIX1`, Word32 version 2, Word32 flags 0, Word64 source
  byte count U, Word64 interest count M, and 32-byte source SHA-256.
- Word64 absolute epoch counts: one per 2^32 source bytes.
- Eight-byte Poppy entries: one per 2048 source bytes, with Word32 epoch-relative
  prefix and Word32 independent quarter populations at shifts 0, 11, and 22.
- Two-bit lexer state before each 512-byte quarter: JSON=0, string=1, escaped=2;
  packed least-significant first and padded to eight bytes.
- Simple Cursor BP: two chronological bits per event, open=11, close=00,
  comma/colon=01, packed least-significant first and padded to eight bytes.
- 32-byte SHA-256 over header and sections. There are no trailing bytes.

M counts all outside-string `{ } [ ] , :` and may be odd. Empty input has zero
sections; deciding whether it is a JSON document belongs to the reader's syntax
checks. All unused padding is zero. Source and sidecar digests detect stale or
corrupt data and provide no execution authority. Readers must check version 2
before interpreting the section layout.

The Poppy directory representation follows Edward Kmett's public
[Everett rank.h](https://github.com/ekmett/everett/blob/eaa5ff3ccdb970cd684d8a01fe5fcea2d3bc23ca/include/everett/rank.h),
under the BSD-2-Clause choice retained in `LICENSE.everett-bsd`. It is a rank
directory, not a select directory. Readers regenerate at most one 512-byte mask
for a local rank/select query and account for that scan; JSON string/scalar
projection may require additional source scanning.

## Core export and packages

The plugin option `-fplugin-opt=THC.Plugin:json-index` writes each sidecar next to
its JSON output as `MODULE.json.idx`. It indexes the bytes of the finished file,
including the existing encoding and newline behavior. Without that option,
plugin output inventories remain JSON-only. The simple-package driver enables
it and removes stale `.json.idx` siblings when refreshing its Core directory.

Package acquisition generates indexes after native, clock and scalar-bitcode
linking has finished. A module record keeps its existing JSON `path` and
`sha256`, with an optional `index` object containing its own `path` and `sha256`.
ZIPs contain adjacent JSON/index pairs. Cache hits and projections preserve the
pair and check the declared digest, v2 envelope, source length and digest, exact
section lengths and trailer. These checks do not replace JSON/Core auditing or
the navigation reader's structural validation. Bundles without an `index` field
remain valid; malformed declared indexes are errors, not a legacy fallback.

`lib:core-json-index` supplies the same encoder to the plugin, driver and command
line tool. Its Haskell modules live under `json-index/`; the library has no GHC
API dependency. The upstream scanner and rank license notices are included in
the package's `license-files`.

Direct GHC plugin loads use the genuine dependency closure of the selected Cabal
plan, including the encoder's store dependencies. `compiler/plugin.py
--registry-only` prepares a private registry from those existing registrations
without requiring a shared plugin library; the Windows vanilla exporter uses
that same registry. It does not build or synthesize package registrations.
