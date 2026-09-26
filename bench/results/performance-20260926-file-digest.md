# Streamed project file digests — 2026-09-26

Relative to public `a9d0f7f3`, the project driver's file digests now stream
SHA-256 and force its 32-byte result before closing the input handle. Previously
a lazy hexadecimal result could retain a complete strict driver/helper/artifact
ByteString until a later consumer demanded the digest. The hexadecimal format,
hash algorithm, cache identities and validation rules are unchanged. This uses
the same bracketed hashing pattern already used by `NativeCache`.

## Memory screen

The published `InstalledBundleProbe.hs` diagnostic was compiled against frozen
baseline and candidate sources with identical Cabal package IDs. Each fresh
process primed a private cache, then performed two complete warm bundle
validations. Both used the same real two-module fixture, compiler, recipe and
immutable 96,895,760-byte helper. No shared cache or installed tool was changed.
Separate execve controls confirmed exactly one probe and zero hydration calls
per warm validation in both variants. Untraced process order was baseline,
candidate, candidate, baseline.

| Parent process statistic | Baseline | Candidate |
| --- | ---: | ---: |
| Allocated bytes | 3,035,710,104–3,035,710,168 | 3,044,850,256–3,044,851,416 |
| Maximum live bytes | 133,321,656–133,321,896 | 36,102,000 |
| RTS memory in use | 396 MiB | 84 MiB |
| RTS fragmentation | 126–128 MiB | 0–1 MiB |
| CPU seconds | 4.359, 5.117 | 4.451, 5.095 |

Maximum live data fell about 72.9%; allocation rose about 0.3%. The maximum
single-process RSS reported by GNU time fell from 459,648/460,224 KiB to
362,304/361,920 KiB. This statistic includes helper maxima, not simultaneous
process-tree memory, and should not be mistaken for parent RSS alone.

Warm validation seconds were 5.081/4.910 and 5.930/6.493 before, versus
5.883/6.190 and 5.699/6.526 after. These are shared-host screens with variable
load, not evidence of a throughput improvement. The measured benefit is bounded
file-digest retention and lower parent heap residency.

All six traced/untraced processes preserved the original module inventory
SHA-256 `ab32364e3a3d4a7c6dd237c40980c18368155f806c3090a3d4f58c702d5df1a5`.
Recorded helper and recipe hashes matched both variants and independent
`sha256sum` results. Driver hashes intentionally differed between binaries.

## Verification

The strict development build passes all nine focused driver subprocess controls.
Fresh interface fixture production passes 21 native rows and all complete/thin,
source, identity, way, foreign, exact-probe reuse, mutation, omitted-inventory,
corrupt-cache and failed-refresh-preservation controls. The 30 selected runtime
tests (`HandoffTest` and `InterfaceCoreNativeTest`, default and dense modes) all
pass, including strict admission, original native results, first-installed
AST/bytecode calls and retained code. No runtime assertions or compiler settings
were changed.
