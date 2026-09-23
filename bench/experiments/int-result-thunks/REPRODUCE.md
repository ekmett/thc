# Reproducing the Int-result thunk experiment

Use Oracle GraalVM 25.3.4.1+1.1 (Java 25.0.4.1), Python 3, and the source on this branch. The base revision is `87e6c6d79d74c22ea95c371c4942d04e62e03e2a`. The measured host was macOS arm64; results on another host are a separate experiment.

All commands below start at the repository root. Set `JAVA_HOME` to the pinned GraalVM installation. The final Kotlin files are the exact tested sources; the additional build task only writes their runtime classpath.

```sh
THC_GRADLE_USER_HOME="$PWD/.gradle-intthunk" ./scripts/gradle.sh --no-daemon intThunkClasspath
python3 bench/experiments/int-result-thunks/run.py --help
python3 bench/experiments/int-result-thunks/run.py --jdk "$JAVA_HOME" --dry-run confirm
```

Build the separate read-only sizing agent before layout or threshold runs:

```sh
mkdir -p bench/experiments/int-result-thunks/tools/size-classes
"$JAVA_HOME/bin/javac" -d bench/experiments/int-result-thunks/tools/size-classes \
  bench/experiments/int-result-thunks/tools/IntThunkSizes.java
"$JAVA_HOME/bin/jar" --create \
  --file bench/experiments/int-result-thunks/tools/intthunk-sizes.jar \
  --manifest bench/experiments/int-result-thunks/tools/MANIFEST.MF \
  -C bench/experiments/int-result-thunks/tools/size-classes .
```

Run the phases serially on an otherwise quiet machine. Choose a new output directory for each complete experiment; the runner refuses to overwrite an existing log. It accepts explicit project, JDK, output and agent paths, so no original author's directory layout is required.

```sh
python3 bench/experiments/int-result-thunks/run.py --jdk "$JAVA_HOME" --output /tmp/intthunk-results check
python3 bench/experiments/int-result-thunks/run.py --jdk "$JAVA_HOME" --output /tmp/intthunk-results layout
python3 bench/experiments/int-result-thunks/run.py --jdk "$JAVA_HOME" --output /tmp/intthunk-results screen
python3 bench/experiments/int-result-thunks/run.py --jdk "$JAVA_HOME" --output /tmp/intthunk-results threshold
python3 bench/experiments/int-result-thunks/run.py --jdk "$JAVA_HOME" --output /tmp/intthunk-results confirm
python3 bench/experiments/int-result-thunks/tools/intthunk-audit.py /tmp/intthunk-results/confirm --json
```

`check` runs the 14 interpreted semantic groups with the real boxed-value cache both off and on. `layout` measures physical sizes and reachable guest objects outside timing. `screen` and `threshold` are exploratory allocation/control runs. `confirm` runs the recorded balanced matrix: three fresh JVMs per combination, five 2-second compiled warmup windows and five 2-second measured windows. It uses one array scan per read invocation. Never combine layout-agent or graph-dump runs with throughput measurements. The audit checks log hashes, counts, checksums, compiled entries, last-tier installation and measured Truffle compiler events; it does not rule out every host-JIT or GC event.

The report's original screening results use the earlier files preserved in `screen-version/`; merely running `screen` on the final source does not recreate that earlier source revision. To investigate that comparison, use a separate disposable checkout with those files, rebuild, and record the new source hashes. The earlier force-all screening/diagnostic run passed `READ_REPEATS=4`, while final confirmation used 1; force-all itself always performs one force pass.

## Separate graph diagnostics

After throughput measurements finish, capture a diagnostic caller, for example inline-copy:

```sh
mkdir -p /tmp/intthunk-graphs/inline-copy
"$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -Xss2m \
  -Xms512m -Xmx512m -XX:+UseCompactObjectHeaders -Dthc.traceCompilation=true \
  -Dpolyglot.engine.StaticObjectStorageStrategy=field-based \
  -Djdk.graal.Dump=Truffle:1 -Djdk.graal.PrintGraph=File \
  -Djdk.graal.PrintGraphWithSchedule=true -Djdk.graal.PrintBackendCFG=false \
  -Djdk.graal.TrackNodeSourcePosition=true \
  -Djdk.graal.DumpPath=/tmp/intthunk-graphs/inline-copy \
  -cp "$(cat build/intthunk-classpath.txt)" thc.runtime.IntThunkExperiment \
  bench inline-copy force-all 65536 1 1 1 100 \
  > /tmp/intthunk-graphs/inline-copy/run.log 2>&1
```

Repeat into distinct directories for ordinary/direct, half-forced copy, retained reads and published reads. These short diagnostic windows are not performance evidence. Compare allocated bytes as well as the committed allocations in the resulting graph.

`bench/experiments/int-result-thunks/tools/GraphInspect.java` parses BGV files using the pinned JDK's `jdk.graal.compiler` module. Compile and launch it with these module options:

```text
--add-modules jdk.graal.compiler
--add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED
--add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED
```

Its arguments are `INPUT.bgv OUTPUT_DIRECTORY PHASE_REGEX`. Use this phase regex:

```text
(?i)After PE Tier|After Inline|Before phase HighTierLowering|After mid tier|After low tier|Final Schedule
```

Then run `python3 bench/experiments/int-result-thunks/tools/intthunk-graph-summary.py GRAPH_DIRECTORY --output summary.json` and `python3 bench/experiments/int-result-thunks/tools/audit-call-packets.py GRAPH_DIRECTORY --output packets.json`. A virtual object alone is not an allocation: compare before-high committed objects and after-mid allocation nodes, and inspect any surviving producer calls. Generated class suffixes are JVM-local; identify `I#` using its primitive field and allocation source. See the graph findings for the recorded compilation and node IDs.

## Evidence boundaries

The tracked logs preserve historical absolute classpaths as provenance, not as paths required for reproduction. `graph-manifest.json` contains hashes of the raw BGV files retained in the original local artifact. No large binary graph dump is required to build or rerun this branch. The runtime remains an isolated mixed-Int adapter, with no generic DataValue/compiler integration; the documented 24-byte alternative has not been implemented or measured.
