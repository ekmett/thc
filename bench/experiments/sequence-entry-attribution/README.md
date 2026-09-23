# Sequence first-miss root attribution

This is **diagnostic failure evidence, not a runtime fix or production pass**.
On Linux x86-64 / GraalVM 25.3.4.1, the build-only overlay reproduced the first
`sequenceBuildViews(1)` compiled-entry miss at tracked base
`85dff0b83d90c07accd7f3567a89b3f9741be284`. The unchanged assertion terminated
with exit 1 after 7,610 successful comparisons and 2,596 earlier required
compiled calls. No extra guest invocation, settling, retry or relaxed gate was
used. The original unmodified failure is preserved separately, not relabeled.

The bounded ring captured 15 events without overflow:

- The stable `THC host entry/1` root entered in interpreter mode.
- The selected `lambda raw` guest and actual entered guest were the same root
  and target. That guest also entered in interpreter mode.
- All 12 subsequent nested guest-root entries were interpreted.
- At the failure dump, the host and selected guest both had valid last-tier
  targets with nonzero code addresses.

Execution mode is sampled at entry, **before** the opaque recorder call.
Validity/code addresses are inspected only after failure; they are not
entry-time observations. This localizes the first observed bypass to entry into
the stable host bridge, rather than solely a deeper guest or a wrong clone. It
does not establish which Java caller link or boundary stub caused the bypass.

## Scope and perturbation

`overlay.patch` edits copies under `build/`, not tracked runtime files.
`overlay.gradle` substitutes those copies only for this diagnostic build.
`RootEntryTrace.kt` is a preallocated 4,096-event ring: root references and mode
booleans only while armed, with no formatting/reflection. The checker arms it
after explicit Sequence compilation, before its existing measured call site.
It dumps only inside the original failed compiled-entry assertion.

Every root calls the opaque recorder; its armed check is inside that method so
arming does not introduce a newly taken capture branch into installed guest
code. Tracing still changes code, timing and Java profiles. This run preserved
the failure, but separate observation/reprofile controls changed the outcome
merely by observing it; their differential was inconclusive. Do not generalize
this diagnostic to an uninstrumented execution guarantee.

## Reproduce

Use the pinned environment and a source tree whose runtime matches the base.
An existing fingerprinted library manifest can be reused; otherwise prepare one
with `scripts/prepare-tests.sh` and `python3 scripts/prepare-library-tests.py`
before the diagnostic overlay. The recorded run reused the original manifest
identified in `evidence.json`; it did not regenerate or rewrite its hashes.

From the repository root:

```sh
. /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
experiment=bench/experiments/sequence-entry-attribution
git diff --exit-code 85dff0b83d90c07accd7f3567a89b3f9741be284 -- src/main build.gradle.kts
sha256sum -c "$experiment/SHA256SUMS"
mkdir -p build/root-trace-src/kotlin build/root-trace-src/java
cp src/main/kotlin/thc/runtime/Program.kt src/main/kotlin/thc/LibraryCheck.kt build/root-trace-src/kotlin/
cp src/main/java/thc/runtime/BytecodeRoot.java build/root-trace-src/java/
cp "$experiment/RootEntryTrace.kt" build/root-trace-src/kotlin/
git apply --check "$experiment/overlay.patch"
git apply "$experiment/overlay.patch"
scripts/gradle.sh --offline --no-daemon --max-workers=4 -I "$experiment/overlay.gradle" installDist
sequence_cases=/absolute/path/to/fingerprinted/build/libraries/cases.json
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS "$JAVA_HOME/bin/java" \
  -Xmx6g --enable-native-access=ALL-UNNAMED -Xss2m -XX:+UseCompactObjectHeaders \
  -cp 'build/install/thc/lib/*' thc.LibraryCheckKt "$sequence_cases" bytecode
```

The original run used the same init-script bytes at `build/root-trace.init.gradle`.
Applying the tracked patch to fresh base-source copies was checked to reproduce
all three tested overlays byte-for-byte; helper and init-script bytes also match.
No compilation timeout/graph limit was raised. No AST replay was needed for this
bounded attribution after bytecode reproduced the failure.

`failed-log-excerpt.txt` retains the complete ring and terminating assertion.
`evidence.json` records exact input, runtime and full-log hashes plus the retained
host-local artifact paths. Generated Java, JARs, full logs and native artifacts
are intentionally not committed. `SHA256SUMS` covers this compact evidence set
and the three required base-source files.

The helper and modifications use the repository's
[UPL-1.0 AND BSD-3-Clause license](../../../LICENSE) and
[retained notices](../../../LICENSE.txt). The diffs apply to existing THC source
without removing its licenses or notices. No external runtime implementation
was copied into this experiment.
