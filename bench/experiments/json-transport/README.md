# Core transport allocation check

`CoreModules.request` parses all supplied module files, then serializes the request
before the language parser selects reachable bindings. The former JSON writer
returned a string for every subtree and concatenated object keys, separators and
values. The append-only writer uses one document buffer; request contents and
loader policy are unchanged.

This is an allocation diagnostic, not a guest execution or throughput benchmark.
The recorded input is the genuine GHC-exported IntMap module bundle from the native
library suite. At a 1 GiB heap bound, both writers produce the same 128,530,070
characters and SHA-256. Per-thread serialization allocations fall from
18,125,631,744 to 881,026,808 bytes (95.1%). Maximum process RSS in these runs falls
from 1,281,835,008 to 1,202,601,984 bytes. RSS includes the VM and parsed input; it is
not a measurement of live serializer objects alone.

At the same smaller 896 MiB heap bound the former writer fails with heap exhaustion
and the new writer succeeds. Both fail at 768 MiB: retaining the parsed bundle,
document buffer and final String still requires substantial memory. This does not
prove that every large module request fits a particular CI runner or diagnose
context retention. No production heap or compiler limits changed.

A second control uses equivalent genuine Sequence exports from GHC 9.14.1,
containers 0.8, post-Tidy with source notes. These are not the original CI exports.
The 11-module `sequenceBuild` request has 266,271,307 characters. At a fixed 2 GiB
heap the former writer fails during StringBuilder growth; the new writer succeeds.
A separate 3 GiB old-writer baseline produces exactly the same SHA-256 as the new
2 GiB run. Serialization allocations are 46,950,688,008 and 1,765,976,880 bytes
respectively (96.2% less). The different successful-run heap bounds are deliberate;
the equal-bound old-failure/new-success comparison is recorded separately.
Both backends also pass the new request through the language parser and retain the
expected strict rejection of `sequenceBuild` for its aggregate formal argument.
A complete fresh CI job is still required to establish that all later steps pass.

To reproduce, prepare the native library corpus once with
`scripts/prepare-library-tests.py`, then write the `intmap` group's ordered
`modules` paths from `build/libraries/cases.json` to `paths.txt`. Build `installDist`
for baseline commit `d45e330` and the changed writer in separate checkouts. Compile
this probe against either distribution and launch a fresh process for each one:

```sh
javac -cp "$THC_DIST/lib/*" -d probe-classes \
  bench/experiments/json-transport/JsonAllocationProbe.java
java -XX:-UseJVMCICompiler -XX:+UseCompactObjectHeaders -Xmx1024m \
  -cp "probe-classes:$THC_DIST/lib/*" JsonAllocationProbe paths.txt
```

Repeat with `-Xmx896m` for the bounded-heap control. To regenerate the Sequence
bundle, use a separate checkout of published branch
[`codex/sequence-boundary-lifetime`](https://github.com/ekmett/thc/tree/codex/sequence-boundary-lifetime)
at exact revision
[`ffdbca6f091b6ee48cd9baed2a8f61e3a6eb5a3d`](https://github.com/ekmett/thc/commit/ffdbca6f091b6ee48cd9baed2a8f61e3a6eb5a3d),
run `scripts/prepare-library-tests.py`, and extract the `sequence` group's ordered
`modules` paths from its `build/libraries/cases.json`. That revision's Sequence
workload, preparation script and independent model are unchanged from the failing
CI revision `a52df35207318982705cb3ba5efb449e30783367`. The baseline main revision's
preparer has only five groups and does not generate Sequence. These regenerated
inputs remain equivalent genuine exports, not the original CI artifact.

Invoke `JsonAllocationProbe sequence-paths.txt request sequenceBuild`, using
`-Xmx2048m` (and `-Xmx3072m` only for the successful old-writer baseline).
`RequestLoadProbe` additionally runs the actual `CoreModules.request` and
`Context.eval` path: compile it against the distribution, then pass the Sequence
paths file and `ast` or `bytecode`. It requires the exact
`Unsupported Core aggregate representation: unboxed-tuple (formal argument)`
marker; a different unsupported frontier fails the control.

Measure process peak RSS using
an OS process-memory tool if desired. `allocated` counts current-thread allocated
bytes only during `Json.stringify`; parsing, the explicit pre-measurement GC, and
the final streaming SHA-256 are outside that interval. The probe's
`sumPoolPeaks` field sums individual heap-pool maxima, which need not occur
simultaneously; it is deliberately **not** used as a peak heap measurement.

The evidence records source/class/toolchain hashes and each input hash. The normal
JVM suite tests exact encoding against the previous writer, all control escapes,
Unicode, nested documents, arrays, one-shot iterables and invalid values. Native
library validation exercises the unchanged accepted entries and Set rejection on
both backends, with the existing per-row compiled-entry assertions.
