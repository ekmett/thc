#!/usr/bin/env bash
set -euo pipefail
cd /home/ekmett/ai/thc-int32x4-bytearray-01a0cdeb
capture=build/int32x4-bytearray-runtime-bounds-deopt
first=build/int32x4-bytearray-runtime-frozen
retain=bench/experiments/int32x4-bytearray/evidence-x86_64
test ! -e "$retain"
test -f "$capture/evidence.json"
test -f build/int32x4-memory-final-suite-report.json
mkdir -p "$retain/first-capture" "$retain/native" "$retain/validation"
cp "$capture"/{evidence.json,input-provenance.json,runtime-snapshot.json,oracle.tsv,graph-cases.json,java-version.txt,jdk-release.txt,architecture.txt,stages.txt,capture-runtime-audit.py,capture-test-runtime-audit.py} "$retain/"
cp "$capture"/{prepare,javac-probe,javac-reader,check}.{command.txt,log,exit-status.txt} "$retain/"
cp "$capture"/recheck.{command.txt,log,exit-status.txt} "$retain/"
cp "$first"/{input-provenance.json,runtime-snapshot.json,graph-cases.json,java-version.txt,jdk-release.txt,architecture.txt,stages.txt,capture-runtime-audit.py,capture-test-runtime-audit.py,first-capture-frontier.json} "$retain/first-capture/"
cp "$first"/{prepare,javac-probe,javac-reader,check}.{command.txt,log,exit-status.txt} "$retain/first-capture/"
for stage in pre post; do
  gzip -n -c "build/simd-int32x4-bytearray/$stage-core/SimdInt32X4ByteArray.json" > "$retain/$stage-core.json.gz"
  for backend in ast bytecode; do
    for entry in vectorIndexWorker scalarIndexWorker vectorStoreGraph scalarStoreGraph; do
      label="$stage-$backend-$entry"
      mkdir "$retain/$label" "$retain/first-capture/$label"
      cp "$capture/$label"/{final-lir.txt,run.command.txt,run.log,run.exit-status.txt,parse.command.txt,parse.log,parse.exit-status.txt} "$retain/$label/"
      graphs=("$capture/$label"/parsed/graph-*.json)
      test "${#graphs[@]}" = 1
      gzip -n -c "${graphs[0]}" > "$retain/$label/graph.json.gz"
      cp "$first/$label"/{run.command.txt,run.log,run.exit-status.txt,parse.command.txt,parse.log,parse.exit-status.txt} "$retain/first-capture/$label/"
      graphs=("$first/$label"/parsed/graph-*.json)
      test "${#graphs[@]}" = 1
      gzip -n -c "${graphs[0]}" > "$retain/first-capture/$label/graph.json.gz"
      cp "build/int32x4-memory-reader-checks/first-lir/$label.txt" "$retain/first-capture/$label/final-lir-corrected-reader.txt"
    done
  done
done
cp build/simd-int32x4-bytearray/{provenance.json,expected.tsv,oracle.tsv,pre-audit.json,post-audit.json} "$retain/native/"
cp -a build/simd-int32x4-bytearray/prepare-run-* "$retain/native/"
cp -a build/int32x4-memory-python "$retain/python-checks"
cp -a build/int32x4-memory-reader-checks "$retain/reader-checks"
cp -a build/int32x4-memory-reader-correction "$retain/reader-correction"
cp build/recheck-int32x4-memory-capture.sh "$retain/reader-correction/"
cp build/{check-int32x4-memory-reader.sh,check-int32x4-memory-retained-failure.py,extract-int32x4-memory-first-lir.py} "$retain/reader-checks/"
cp build/check-int32x4-memory-python.sh "$retain/python-checks/"
cp build/{int32x4-memory-initial-suite-report.json,int32x4-memory-hardened-suite-report.json,int32x4-memory-final-suite-report.json,int32x4-memory-suite-report.py,validate-int32x4-memory.sh,validate-int32x4-memory-hardened.sh,validate-int32x4-memory-deopt-transition.sh,report-int32x4-memory-first-capture.py,package-int32x4-memory-evidence.sh,seal-int32x4-memory-evidence.py} "$retain/validation/"
for label in initial hardened deopt-transition; do
  source=build/int32x4-memory-validation
  if [[ "$label" != initial ]]; then source="$source-$label"; fi
  mkdir "$retain/validation/$label"
  cp "$source"/{revision.txt,git-status.txt} "$retain/validation/$label/"
  for mode in focused-default full-default full-dense; do
    destination="$retain/validation/$label/$mode"
    mkdir "$destination"
    cp "$source/$mode"/{command.txt,output.log,exit-status.txt} "$destination/"
    tar -czf "$destination/junit.tar.gz" --exclude='./binary' -C "$source/$mode/test-results" .
  done
done
cp -a build/int32x4-memory-integration-first "$retain/validation/storage-first"
mkdir "$retain/native/full-prepare"
cp /home/ekmett/.codex/worktrees/98d0/cult/build-agent-logs/20260923-201123-ee_mtzv8/{command.json,output.log,result.json} "$retain/native/full-prepare/"
cp build/compact-int32x4-memory-evidence.sh "$retain/validation/"
bash build/compact-int32x4-memory-evidence.sh
