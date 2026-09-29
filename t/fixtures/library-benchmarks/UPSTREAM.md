# Upstream library workloads

These adapters retain the named upstream library operations. They run through
the existing NativeTiming steady driver and THC Probe; they do not replace the
libraries with primitive approximations.

| Entry | Original benchmark | Input |
| --- | --- | --- |
| bytestringReadInt | bytestring 0.12.2.0, bench/BenchReadInt.hs, Strict/ReadInt | 256 mixed-sign machine integers near the signed boundary |
| textDecodeUtf8 | text 2.1.3, Benchmarks/DecodeUtf8.hs, StrictLength | Complete russian.txt, from text-test-data |
| textSearch | text 2.1.3, Benchmarks/Search.hs, Text | Same full corpus and original needle принимая |
| aesonDecodeValue | aeson 2.3.2.0, CompareWithJSON.hs, compare-json/decode/nf/aeson/strict | Shipped twitter100.json |

The bytestring loop is adapted from Viktor Dukhovni's 2021 benchmark.
Text is copyright Tom Harper and contributors. Aeson is copyright MailRank,
Inc. and Aeson project contributors. Original license texts are in licenses/.

Sources:
- https://github.com/haskell/bytestring/tree/0.12.2.0/bench
- https://github.com/haskell/text/tree/2.1.3/benchmarks/haskell/Benchmarks
- https://github.com/haskell/text-test-data/tree/609d3a34d2de71875372223d174b616f9dafa1d2
- https://github.com/haskell/aeson/tree/682162c66d26a770fbfb6271c797e646bc5c4f2e/benchmarks

## Corpus setup and execution

Acquire the referenced upstream sources. Decompress russian.txt.bz2 into
corpus/russian.txt; copy aeson's benchmarks/json-data/twitter100.json into
corpus/twitter100.json. Run from the parent directory of corpus. Corpora are
not redistributed here.

Build this directory's Cabal project with GHC 9.14.1 at optimization level 2.
The native executables accept the existing oracle options, for example:

    text-native --bench-steady textDecodeUtf8 10 2 5 10000

Inputs cycle through 16 variants selected dynamically by the low four bits.
Bytestring shifts the original generator's boundary by the selector. Text and
aeson prepend 0 through 15 ASCII spaces. Corpus reading and raw input
construction are shared setup outside measurement, completed by warmup.
Search also shares its decoded input, as upstream does. UTF-8 and JSON
decoding remain inside their dynamically selected entry invocation.
Aeson's result is forced to normal form before observing the root size.

NativeTiming keeps the original opaque batch boundary so repeated checksums
cannot be floated outside timed batches. JVM runs require the existing first
installed-code qualification, sufficient guest and host wrapper warmup,
passive final target checks, and no compilation/invalidation/deoptimization
events in measurement. Record actual warm calls and elapsed time; reject
failed forks rather than incorporating their samples.

Oracle values for selector n: bytestring -128 - 256*n; UTF-8 length
2771535+n; search count 1398; JSON root size 11 after full evaluation.
These are tied to the exact referenced corpora; changing data requires a new
independent native oracle. Performance results remain private.
