# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

SHELL := /bin/bash
.DEFAULT_GOAL := help
ROOT := $(abspath ..)

.PHONY: help kernels map prepare-kernels prepare-map
help:
	@printf '%s\n' 'make -C bench kernels|map [OUT=/path/to/results]'

prepare-kernels:
	cd "$(ROOT)" && bin/try.sh

prepare-map:
	cd "$(ROOT)" && bin/try-map.sh

kernels: OUT ?= $(ROOT)/build/bench/kernels
map: OUT ?= $(ROOT)/build/bench/map

kernels: prepare-kernels
	set -euo pipefail; \
	cd "$(ROOT)"; \
	OUT="$(OUT)"; \
	THC_JAVA="$$JAVA_HOME/bin/java"; \
	WARM_SECONDS="$${THC_BENCH_WARM_SECONDS:-10}"; \
	SAMPLE_SECONDS="$${THC_BENCH_SAMPLE_SECONDS:-2}"; \
	SAMPLES="$${THC_BENCH_SAMPLES:-5}"; \
	FORKS="$${THC_BENCH_FORKS:-3}"; \
	mkdir -p "$$OUT"; \
	printf 'engine\tfork\tentry\tsample\trepetitions\tinputBase\tchecksum\telapsedNs\n' > "$$OUT/timings.tsv"; \
	for spec in 'sumLoop 10000' 'fib 12' 'under 100' 'caseList 100'; do \
	    read -r entry inputBase <<< "$$spec"; \
	    for ((fork=1; fork<=FORKS; fork++)); do \
	        printf 'Running %s fork %s: JVM warmup %ss, %s windows of %ss per engine\n' "$$entry" "$$fork" "$$WARM_SECONDS" "$$SAMPLES" "$$SAMPLE_SECONDS"; \
	        "$$THC_JAVA" --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xss2m -XX:+UseCompactObjectHeaders -Dthc.traceCompilation=true \
	            -cp 'build/install/thc/lib/*:build/diagnostics/thc-tools.jar' thc.Probe \
	            build/core/THC.ExamplePrim.json,build/core/THC.Fixtures.json \
	            "$$entry" --steady "$$WARM_SECONDS" "$$SAMPLE_SECONDS" "$$SAMPLES" "$$inputBase" \
	            > "$$OUT/$$entry-$$fork-jvm.tsv" 2> "$$OUT/$$entry-$$fork.log"; \
	        while IFS= read -r result; do printf 'thc-graal\t%s\t%s\n' "$$fork" "$$result" >> "$$OUT/timings.tsv"; done < "$$OUT/$$entry-$$fork-jvm.tsv"; \
	        build/native/native-oracle --bench-steady "$$entry" 1 "$$SAMPLE_SECONDS" "$$SAMPLES" "$$inputBase" \
	            > "$$OUT/$$entry-$$fork-native.tsv"; \
	        while IFS= read -r result; do printf 'native-ghc\t%s\t%s\n' "$$fork" "$$result" >> "$$OUT/timings.tsv"; done < "$$OUT/$$entry-$$fork-native.tsv"; \
	    done; \
	done; \
	python3 bin/summarize-benchmark.py "$$OUT"

map: prepare-map
	set -euo pipefail; \
	cd "$(ROOT)"; \
	OUT="$(OUT)"; \
	THC_JAVA="$$JAVA_HOME/bin/java"; \
	BASE="$${THC_MAP_INPUT:-10000}"; \
	WARM_SECONDS="$${THC_BENCH_WARM_SECONDS:-15}"; \
	SAMPLE_SECONDS="$${THC_BENCH_SAMPLE_SECONDS:-2}"; \
	SAMPLES="$${THC_BENCH_SAMPLES:-5}"; \
	FORKS="$${THC_BENCH_FORKS:-3}"; \
	DIAGNOSTIC="$${THC_DIAGNOSTIC_UNSUPPORTED:-false}"; \
	BACKEND="$${THC_BACKEND:-bytecode}"; \
	case "$$BACKEND" in ast|bytecode) ;; *) echo 'THC_BACKEND must be ast or bytecode' >&2; exit 2;; esac; \
	case "$$DIAGNOSTIC" in true|false) ;; *) echo 'THC_DIAGNOSTIC_UNSUPPORTED must be true or false' >&2; exit 2;; esac; \
	MODULES="$$(paste -sd, build/map/modules.txt)"; \
	mkdir -p "$$OUT"; \
	printf '{"backend":"%s","diagnosticUnsupported":%s,"inputBase":%s,"warmSeconds":%s,"minimumWarmCalls":12000,"sampleSeconds":%s,"samples":%s,"forks":%s}\n' \
	  "$$BACKEND" "$$DIAGNOSTIC" "$$BASE" "$$WARM_SECONDS" "$$SAMPLE_SECONDS" "$$SAMPLES" "$$FORKS" > "$$OUT/run-config.json"; \
	cp build/map/audit.json "$$OUT/capability-audit.json"; \
	printf 'engine\tfork\tentry\tsample\trepetitions\tinputBase\tchecksum\telapsedNs\n' > "$$OUT/timings.tsv"; \
	run_jvm() { \
	  "$$THC_JAVA" --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xss2m -XX:+UseCompactObjectHeaders -Dthc.traceCompilation=true \
	    "-Dthc.backend=$$BACKEND" "-Dthc.diagnosticUnsupported=$$DIAGNOSTIC" -Dthc.minimumWarmCalls=12000 -cp 'build/install/thc/lib/*:build/diagnostics/thc-tools.jar' thc.Probe \
	    "$$MODULES" mapAggregate --steady "$$WARM_SECONDS" "$$SAMPLE_SECONDS" "$$SAMPLES" "$$BASE" \
	    > "$$OUT/mapAggregate-$$fork-jvm.tsv" 2> "$$OUT/mapAggregate-$$fork.log"; \
	  while IFS= read -r row; do printf 'thc-graal\t%s\t%s\n' "$$fork" "$$row" >> "$$OUT/timings.tsv"; done < "$$OUT/mapAggregate-$$fork-jvm.tsv"; \
	}; \
	run_native() { \
	  build/map/native/native-oracle --bench-steady mapAggregate 1 "$$SAMPLE_SECONDS" "$$SAMPLES" "$$BASE" \
	    > "$$OUT/mapAggregate-$$fork-native.tsv"; \
	  while IFS= read -r row; do printf 'native-ghc\t%s\t%s\n' "$$fork" "$$row" >> "$$OUT/timings.tsv"; done < "$$OUT/mapAggregate-$$fork-native.tsv"; \
	}; \
	for ((fork=1; fork<=FORKS; fork++)); do \
	  printf 'Map workload fork %s: input %s, JVM warmup at least 12000 calls and %ss, %s windows of %ss\n' "$$fork" "$$BASE" "$$WARM_SECONDS" "$$SAMPLES" "$$SAMPLE_SECONDS"; \
	  if ((fork % 2)); then run_jvm; run_native; else run_native; run_jvm; fi; \
	done; \
	python3 bin/summarize-benchmark.py "$$OUT"
