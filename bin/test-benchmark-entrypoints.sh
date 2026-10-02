#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Source and dry-run checks; never execute a timing window or prepare fixtures.
set -euo pipefail
cd "$(dirname "$0")/.."
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
. bin/benchmark-jvm-options.sh
[[ "${THC_BENCH_JVM_OPTIONS[*]}" == '-Djdk.graal.VectorizeLoops=false -XX:+UseCompactObjectHeaders -XX:+UseCompressedOops' ]]
for variable in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS; do
  for setting in Vectorization=true Vectorization=false VectorizeLoops=true VectorizeLoops=false; do
    export "$variable=-Djdk.graal.$setting -XX:-UseCompactObjectHeaders -XX:-UseCompressedOops"
    . bin/benchmark-jvm-options.sh
    [[ ${#THC_BENCH_JVM_OPTIONS[@]} == 0 ]]
  done
  export "$variable=@caller-options"
  . bin/benchmark-jvm-options.sh
  [[ ${#THC_BENCH_JVM_OPTIONS[@]} == 0 ]]
  unset "$variable"
done
JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75
. bin/benchmark-jvm-options.sh
[[ "${THC_BENCH_JVM_OPTIONS[*]}" == '-Djdk.graal.VectorizeLoops=false -XX:+UseCompactObjectHeaders' ]]
unset JAVA_TOOL_OPTIONS
kernel=$(make --no-print-directory -n -C bench kernels -o prepare-kernels)
map=$(make --no-print-directory -n -C bench map -o prepare-map)
[[ "$kernel" == *'build/core/THC.Prim.Test.cbd,build/core/Fixtures.cbd'* ]]
[[ "$kernel" == *'"main:Fixtures.$entry" --steady'* ]]
[[ "$kernel" == *'${result/main:Fixtures./}'* ]]
[[ "$map" == *'main:MapWorkload.mapAggregate --steady'* ]]
[[ "$map" == *'${row/main:MapWorkload./}'* ]]
for command in "$kernel" "$map"; do
  [[ "$command" == *'. bin/benchmark-jvm-options.sh'* ]]
  [[ "$command" == *'"${THC_BENCH_JVM_OPTIONS[@]}"'* ]]
  [[ "$command" != *'.json,'* ]]
done
grep -Fq 'inspect_cbd((core / "THC.InterfaceClosure.cbd").read_bytes())' bin/export-map.sh
grep -Fq -- '--build-dir "$root/build/map"' bin/export-map.sh
! grep -Eq 'core /.*\.json' bin/export-map.sh
grep -Fq -- '--entry main:MapWorkload.mapAggregate' bin/prepare-map.sh
grep -Fq 'Main.loadEntry(context, modules, "main:MapWorkload.mapAggregate")' src/diagnostics/java/thc/MapCheck.java
row=$'main:MapWorkload.mapAggregate\t1\t256\t100\t42\t1000'
[[ "${row/main:MapWorkload./}" == $'mapAggregate\t1\t256\t100\t42\t1000' ]]
bash -n bin/benchmark-jvm-options.sh bin/test-benchmark-entrypoints.sh bin/try-map.sh bin/prepare-map.sh
sh -n bin/export-map.sh
printf '%s\n' 'Benchmark CBD paths, qualified IDs, native labels and JVM caller defaults verified (no timings).'
