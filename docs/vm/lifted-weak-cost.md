# Lifted weak handoff costs

A language-owned weak handoff pays for a fresh registration and, in this fixture,
another collection before the next callback can run. It reuses the backing value;
it does not copy that value for each hop. Long chains and infrequent pumping can
keep otherwise obsolete captures around considerably longer.

This is the bounded measurement for [#26](https://github.com/ekmett/jam/issues/26),
using the handoff pattern from `WeakBridgeSmoke`. It measures the existing design;
it is not a release gate or a promise about collection counts.

## Results

Each sample installs 64 independent associations, then drops all strong user
references to their keys. A key is either terminal or a pre-resolved chain of
one or four thunks. Each node owns a distinct 4 KiB capture, and each association
has a shared 4 KiB backing value carried through every handoff. The callback
registers the next node or runs the real finalizer at the terminal node.

Times below are medians of seven interleaved samples after three warmup cycles,
for the entire batch. Timing starts after initial registration. Reclaim time ends
when every tracked backing/capture array is phantom-unreachable.

| Handoffs | Registrations (extra) | Finalizer time, ms (range) | Reclaim time, ms | Major GCs to finalizer / reclaim |
|---:|---:|---:|---:|---:|
| 0 | 64 (0) | 3.537 (3.018–3.658) | 7.043 | 1 / 2 |
| 1 | 128 (64) | 7.190 (7.098–7.264) | 10.709 | 2 / 3 |
| 4 | 320 (256) | 18.648 (18.524–19.834) | 22.418 | 5 / 6 |

Pumping only after every third collection keeps queued callbacks rooted through
two additional collections at each step:

| Handoffs | Finalizer time, ms (range) | Reclaim time, ms | Major GCs to finalizer / reclaim |
|---:|---:|---:|---:|
| 0 | 10.628 (10.451–10.758) | 14.177 | 3 / 4 |
| 1 | 21.772 (21.452–26.965) | 25.462 | 6 / 7 |
| 4 | 55.651 (54.565–56.718) | 59.240 | 15 / 16 |

Peak tracked backing payload is 256 KiB in every case. Peak node-capture payload
is 256 KiB, 512 KiB, and 1.25 MiB respectively: the longer input chains already
contain more captures. These are known array payload bytes, not measurements of
Java object headers, registry metadata, or total heap use. At the last real
finalizer, 256 KiB of backing and 256 KiB of terminal captures remain
phantom-reachable in every case. The next collection clears all of them. Earlier
captures have already become reclaimable; repeated handoffs did not leave an
accumulating retained tail in this experiment.

The practical cost here is an additional GC/pump cycle for each unresolved
handoff that the language chooses to register. A host that leaves callbacks
queued extends their retention. This fixture deliberately registers one step
at a time; it does not measure a language resolver that collapses an already
resolved chain before registration, a live independently rooted answer, minor-GC
scheduling, or arbitrary application workloads.

## Runtime and method

Measured on an Apple M2 Max running macOS 26.6.2, with the release-flavor
[vm-2026.10.09-0e36293](https://github.com/ekmett/jam/releases/tag/vm-2026.10.09-0e36293)
GraalVM runtime. The Java launcher, libjvm, bridge library, and API jar hashes
match the published package inventory. The heap is 128 MiB: 96 MiB old and
32 MiB young, one NEON copy worker and the serial VM scanner. Every measured
collection is an explicit `System.gc()` major collection. Graal/libgraal and
`-Xbatch` are enabled. This measures end-to-end GC and callback latency, not the
isolated cost of `Weak.create`.

Phantom probes use `refersTo(null)` to avoid counting ReferenceHandler queue
scheduling as retention. Ordinary Java weak references would be misleading here:
they can clear before finalizer-held captures become reclaimable. The probes
and bookkeeping themselves remain live and add measurement overhead.

The runtime's GC MXBean counters reported zero despite actual collections;
therefore collection counts above come from explicit calls, cross-checked
against all 350 major collections in the GC log (including warmups and cleanup).
The raw CSV retains the MXBean observations rather than presenting them as
valid counts. Exact round counts are observations of this fixture, not API laws.

## Reproduce

Use the packaged Jam GraalVM as `JAVA_HOME`, from the repository root:

```sh
mkdir -p /tmp/jam-lifted-weak-bench
"$JAVA_HOME/bin/javac" -cp "$JAVA_HOME/lib/jam/jam-vm.jar" \
  -d /tmp/jam-lifted-weak-bench bench/vm/LiftedWeakBench.java
"$JAVA_HOME/bin/java" -Xshare:off -Xms128m -Xmx128m \
  -XX:+UnlockExperimentalVMOptions -XX:+UseJamGC \
  -XX:+EnableJVMCI -XX:+UseJVMCICompiler -XX:+UseJVMCINativeLibrary -Xbatch \
  -Xlog:gc:file=/tmp/jam-lifted-weak-bench/gc.log \
  --enable-native-access=ALL-UNNAMED \
  "-Djava.library.path=$JAVA_HOME/lib/jam" \
  -cp "/tmp/jam-lifted-weak-bench:$JAVA_HOME/lib/jam/jam-vm.jar" \
  LiftedWeakBench 64 7 > /tmp/jam-lifted-weak-bench/results.csv
```

The [benchmark source](https://github.com/ekmett/jam/blob/main/bench/vm/LiftedWeakBench.java),
[raw samples](https://github.com/ekmett/jam/blob/main/bench/vm/results/lifted-weak-20261010/results.csv),
[GC log](https://github.com/ekmett/jam/blob/main/bench/vm/results/lifted-weak-20261010/gc.log),
and [runtime identity](https://github.com/ekmett/jam/blob/main/bench/vm/results/lifted-weak-20261010/identity.json)
are retained together. The benchmark is intentionally outside CI.
