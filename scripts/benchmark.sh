#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Steady-state host-invoked kernels. Run scripts/try.sh first.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
make --no-print-directory -s -C "$ROOT" check-java
THC_JAVA="$JAVA_HOME/bin/java"
OUT="${1:-$ROOT/bench/results/steady}"
WARM_SECONDS="${THC_BENCH_WARM_SECONDS:-10}"
SAMPLE_SECONDS="${THC_BENCH_SAMPLE_SECONDS:-2}"
SAMPLES="${THC_BENCH_SAMPLES:-5}"
FORKS="${THC_BENCH_FORKS:-3}"
mkdir -p "$OUT"
printf 'engine\tfork\tentry\tsample\trepetitions\tinputBase\tchecksum\telapsedNs\n' > "$OUT/timings.tsv"
for spec in 'sumLoop 10000' 'fib 12' 'under 100' 'caseList 100'; do
    read -r entry inputBase <<< "$spec"
    for ((fork=1; fork<=FORKS; fork++)); do
        printf 'Running %s fork %s: JVM warmup %ss, %s windows of %ss per engine\n' "$entry" "$fork" "$WARM_SECONDS" "$SAMPLES" "$SAMPLE_SECONDS"
        "$THC_JAVA" --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xss2m -XX:+UseCompactObjectHeaders -Dthc.traceCompilation=true \
            -cp 'build/install/thc/lib/*:build/diagnostics/thc-tools.jar' thc.ProbeKt \
            build/core/THC.Prim.json,build/core/THC.Fixtures.json \
            "$entry" --steady "$WARM_SECONDS" "$SAMPLE_SECONDS" "$SAMPLES" "$inputBase" \
            > "$OUT/$entry-$fork-jvm.tsv" 2> "$OUT/$entry-$fork.log"
        while IFS= read -r result; do printf 'thc-graal\t%s\t%s\n' "$fork" "$result" >> "$OUT/timings.tsv"; done < "$OUT/$entry-$fork-jvm.tsv"
        build/native/native-oracle --bench-steady "$entry" 1 "$SAMPLE_SECONDS" "$SAMPLES" "$inputBase" \
            > "$OUT/$entry-$fork-native.tsv"
        while IFS= read -r result; do printf 'native-ghc\t%s\t%s\n' "$fork" "$result" >> "$OUT/timings.tsv"; done < "$OUT/$entry-$fork-native.tsv"
    done
done
python3 scripts/summarize-benchmark.py "$OUT"
