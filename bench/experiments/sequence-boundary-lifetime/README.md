# Sequence boundary-lifetime compiler control

This is an **inconclusive diagnostic**, not a production fix or a default-runtime
Sequence pass. Both unmodified and modified Java-hosted Graal compilers pass the
unchanged strict bytecode replay. The modified run still cold-flushes the
boundary stub. Earlier native-compiler failures remain open.

## Observations

Both runs use frozen THC JAR
`ce0b5621f91f91461cabb371a386123ce54fb2f4bc939241398cbc7c4b2f46de`
and original fingerprinted manifest
`b9a7e8cda32b448cb9d5e54af08bec112eab4c112b1a8a63a23b32b6dad4179a`.
Each exits 0 with 8,028 comparisons, 2,760 required positive compiled-entry
checks, 17 zero-trap/zero-blackhole diagnostics and 8 explicitly rejected
frontiers. No settling calls, retries or relaxed assertions were added.

| Java-Graal run | Boundary lifecycle | Strict replay |
| --- | --- | --- |
| Unmodified compiler | One installation at 1.836s; no recorded retirement or replacement through exit at 135.301s | Pass |
| G1 post-frame experiment | Install at 2.097s; cold-flush at 35.259s; reinstall at 91.349s | Pass |

The experimental flush records epoch 58, cold count 11 and 103408 KB free in
the relevant code-cache heap. It is not cache exhaustion. The control records
other nmethod retirements, so its lifecycle logging was active.

This is not evidence that the modification increases retirement: compiler host,
logging, timing, profiles and GC epochs affect these single runs. The control
omits the experimental run's class-load logging. Neither measures performance.

## Exact scope and selection

The installed JDK and every THC source/JAR remain unchanged. A build-only copy
of `AMD64TruffleCallBoundaryInstrumentationFactory.java` from the pinned JDK's
`src.zip` is compiled as a `jdk.graal.compiler` module overlay. Its only semantic
change adds `HotSpotGraalRuntime.HotSpotGC.G1` to the existing Z/Shenandoah
predicate that delays `emitEntryPoint` dispatch until after frame setup. G1 thus
uses the existing entry-barrier/frame-restoration path. Serial and Parallel
behavior is unchanged. An explanatory diagnostic comment is added.

The copied source retains its entire Oracle copyright and GPLv2 with Classpath
exception header. It remains local under `build/compiler-overlay/src/`, not
redistributed here or relicensed under THC's license. No compiler binaries,
replacement classes or JDK archive are committed. `evidence.json` records hashes.

Mechanical review found no new frame/register mismatch in that existing path.
It identified an existing uncompressed-oop assertion assuming a five-byte load
even after frame setup. This experiment does not validate uncompressed oops or
other collectors. A separate version-only probe with the same 6 GiB/G1 settings
reports compressed oops enabled; it is not a flag dump from the completed replay.

Both actual runs report Enterprise Graal loaded from class files and use
`-XX:-UseJVMCINativeLibrary`. That banner alone describes JVMCI, not necessarily
Truffle's separate compiler selection. The pinned selection chain closes this
gap: `LibGraal.isAvailable()` uses `HotSpotJVMCIRuntime.registerNativeMethods`,
which rejects native-library registration when that flag is false. Truffle
consequently selects `HotSpotTruffleCompilationSupport`, which creates
`HotSpotTruffleCompilerImpl` and installs the boundary stub through that compiler.
The experimental class log records that support class at 1.878s and both
replacement factory classes from the overlay at 2.091s, on the same thread as
the 2.097s stub installation. Replacement bytecode contains the G1 predicate.
This attributes compiler selection, not subsequent native stub execution.

## Reproduction

Use pinned GraalVM 25.3.4.1+1.1 / Java 25.0.4.1, the frozen runtime and original
manifest above. Copy the named factory source from `src.zip`, retain its header,
and make the single predicate change described above under the source path below.

```sh
. /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
"$JAVA_HOME/bin/javac" \
  --patch-module jdk.graal.compiler=build/compiler-overlay/src \
  --add-exports jdk.internal.vm.ci/jdk.vm.ci.amd64=jdk.graal.compiler \
  -d build/compiler-overlay/classes \
  build/compiler-overlay/src/jdk/graal/compiler/truffle/hotspot/amd64/AMD64TruffleCallBoundaryInstrumentationFactory.java
mkdir -p build/java-graal-patched
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS "$JAVA_HOME/bin/java" \
  -Xmx6g --enable-native-access=ALL-UNNAMED -Xss2m \
  -XX:+UseCompactObjectHeaders -XX:+UseG1GC \
  -XX:-UseJVMCINativeLibrary -Djdk.graal.ShowConfiguration=info \
  --patch-module jdk.graal.compiler=build/compiler-overlay/classes \
  -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation \
  -XX:LogFile=build/java-graal-patched/hotspot.xml \
  '-Xlog:gc=info,codecache*=trace,nmethod+install=debug:file=build/java-graal-patched/lifecycle.log:uptime,tid,tags:filecount=0' \
  '-Xlog:class+load=info:file=build/java-graal-patched/classes.log:uptime,tid,tags:filecount=0' \
  -cp '/home/ekmett/ai/thc-sequence-current-main-01a0cdeb/build/root-boundary-repair-validation/lib/*' \
  thc.LibraryCheckKt \
  /home/ekmett/ai/thc-sequence-current-main-01a0cdeb/build/libraries/cases.json bytecode \
  > build/java-graal-patched/check-bytecode.log 2>&1
```

The control command/full logs remain at the location in `evidence.json`.
It omits the module patch and class-load log. Compilation timeout stays
30 seconds, graph limit 100,000, and splitting unchanged.

## What remains unresolved

An entry barrier refreshes age only when execution reaches it. Loading/auditing
rejected frontiers can legitimately leave the boundary idle; no per-call
timestamps establish whether that happened at this flush. Moving dispatch after
the barrier cannot prevent genuine idle retirement or repair compiled caller
links already pointing at the interpreter adapter. Active fast-path aging is
therefore not a demonstrated cause of the observed retirement.

The next diagnostic distinguishes the public host-dispatch cache from the
host-to-guest route at the original failure. Production code, collector choice
and strict compiled-entry gates remain unchanged.
