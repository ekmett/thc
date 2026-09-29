#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -euo pipefail
[[ $# == 2 ]] || { echo 'Usage: JAVA_HOME=PINNED_JDK bash probe.sh FRESH_OUTPUT_DIR OVERLAY_DIR' >&2; exit 2; }
: "${JAVA_HOME:?Select GraalVM 25.3.4.1}"
recipe_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
test ! -e "$1"
mkdir -p "$1/classes"
output_dir=$(cd -- "$1" && pwd)
overlay_dir=$(cd -- "$2" && pwd)
cp "$recipe_dir/NativeProbe.java" "$recipe_dir/GraphPinTest.java" "$recipe_dir/probe.sh" "$output_dir/"
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
exports=(--add-exports=java.base/jdk.internal.foreign=ALL-UNNAMED
    --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
    --add-exports=java.base/jdk.internal.vm.vector=ALL-UNNAMED)
"$JAVA_HOME/bin/javac" --add-modules jdk.incubator.vector "${exports[@]}" \
    -d "$output_dir/classes" "$recipe_dir/NativeProbe.java"
sha256sum "$JAVA_HOME/lib/svm/builder/svm.jar" "$JAVA_HOME/lib/svm/builder/svm-foreign.jar" \
    "$overlay_dir/thc-svm-shared-arena-vector-builder.jar" "$overlay_dir/thc-svm-shared-arena-vector-foreign.jar" \
    "$recipe_dir/NativeProbe.java" "$recipe_dir/GraphPinTest.java" > "$output_dir/inputs.sha256"
args=(-O2 -J-Xmx2g -J-XX:ActiveProcessorCount=2 -J-ea -J-esa -ea --parallelism=2
    "-J--patch-module=org.graalvm.nativeimage.builder=$overlay_dir/thc-svm-shared-arena-vector-builder.jar"
    "-J--patch-module=org.graalvm.nativeimage.foreign=$overlay_dir/thc-svm-shared-arena-vector-foreign.jar"
    -J--add-exports=java.base/jdk.internal.foreign=org.graalvm.nativeimage.foreign
    -J--add-exports=java.base/jdk.internal.misc=org.graalvm.nativeimage.foreign
    -J--add-exports=java.base/jdk.internal.vm.vector=org.graalvm.nativeimage.foreign
    --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED
    '--initialize-at-build-time=NativeProbe$Shape'
    "${exports[@]}"
    -H:+UnlockExperimentalVMOptions -H:-Vectorization -R:-Vectorization
    -H:+OptimizeVectorAPI -H:+TargetVectorLowering -R:+OptimizeVectorAPI -R:+TargetVectorLowering
    -H:+SharedArenaSupport -H:+PrintCanonicalGraphStrings -H:Dump=:2 -H:MethodFilter=NativeProbe.fixedAccess
    -H:CompilationWatchDogStartDelay=15
    "-H:DumpPath=$output_dir/graphs" -H:-UnlockExperimentalVMOptions
    -cp "$output_dir/classes" NativeProbe "$output_dir/native-probe")
printf '%s\n' "${args[@]}" > "$output_dir/build.args"
set +e
timeout --kill-after=10s 180s "$JAVA_HOME/bin/native-image" "${args[@]}" > "$output_dir/build.log" 2>&1
status=$?
set -e
printf '%s\n' "$status" > "$output_dir/build.status"
if (( status != 0 )); then tail -n 70 "$output_dir/build.log" | cut -c1-240; exit "$status"; fi
sha256sum "$output_dir/native-probe" > "$output_dir/image.sha256"
set +e
timeout --kill-after=10s 30s "$output_dir/native-probe" > "$output_dir/run.log" 2>&1
status=$?
set -e
printf '%s\n' "$status" > "$output_dir/run.status"
cat "$output_dir/run.log"
(( status == 0 )) || exit "$status"
graphs=("$output_dir/graphs/"SubstrateHostedCompilation-*\[NativeProbe.fixedAccess\(MemorySegment\)int\].bgv)
(( ${#graphs[@]} == 1 )) && test -f "${graphs[0]}"
set +e
timeout --kill-after=10s 30s "$JAVA_HOME/bin/java" -Xmx1g --add-modules jdk.graal.compiler \
    --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED \
    --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED \
    "$output_dir/GraphPinTest.java" "${graphs[0]}" > "$output_dir/graph-pin-test.log" 2>&1
status=$?
set -e
printf '%s\n' "$status" > "$output_dir/graph.status"
cat "$output_dir/graph-pin-test.log"
exit "$status"
