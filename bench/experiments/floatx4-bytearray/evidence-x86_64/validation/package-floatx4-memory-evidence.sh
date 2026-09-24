#!/usr/bin/env bash
set -euo pipefail
capture=build/floatx4-bytearray-runtime-family-dispatch
validation=build/floatx4-memory-validation-family-dispatch
retain=bench/experiments/floatx4-bytearray/evidence-x86_64
test ! -e "$retain"
test -f "$capture/evidence.json"
test -f build/floatx4-memory-final-suite-report.json
mkdir -p "$retain/native" "$retain/validation"
cp "$capture"/{evidence.json,input-provenance.json,runtime-snapshot.json,oracle.tsv,graph-cases.json,java-version.txt,jdk-release.txt,architecture.txt,stages.txt,capture-runtime-audit.py,capture-test-runtime-audit.py} "$retain/"
cp "$capture"/{prepare,javac-probe,javac-reader,check}.{command.txt,log,exit-status.txt} "$retain/"
cp "$capture"/recheck.{command.txt,log,exit-status.txt} "$retain/"
for stage in pre post; do
  gzip -n -c "build/simd-floatx4-bytearray/$stage-core/SimdFloatX4ByteArray.json" > "$retain/$stage-core.json.gz"
  for backend in ast bytecode; do
    for entry in vectorIndexGraph scalarIndexGraph vectorStoreGraph scalarStoreGraph; do
      label="$stage-$backend-$entry"
      mkdir "$retain/$label"
      cp "$capture/$label"/{run.command.txt,run.log,run.exit-status.txt,parse.command.txt,parse.log,parse.exit-status.txt} "$retain/$label/"
      gzip -n -c "$capture/$label/final-lir.txt" > "$retain/$label/final-lir.txt.gz"
      graphs=("$capture/$label"/parsed/graph-*.json)
      test "${#graphs[@]}" = 1
      gzip -n -c "${graphs[0]}" > "$retain/$label/graph.json.gz"
    done
  done
done
for file in provenance.json expected.tsv oracle.tsv snan-expected.tsv snan-oracle.tsv pre-audit.json post-audit.json; do
  gzip -n -c "build/simd-floatx4-bytearray/$file" > "$retain/native/$file.gz"
done
for run in build/simd-floatx4-bytearray/prepare-run-*; do
  tar -czf "$retain/native/$(basename "$run").tar.gz" -C "$run" .
done
cp -a build/floatx4-memory-python-final-reader "$retain/python-checks"
cp build/check-floatx4-memory-python-reader.sh "$retain/python-checks/"
cp build/{floatx4-memory-final-suite-report.json,floatx4-memory-suite-report.py,validate-floatx4-memory.sh,validate-floatx4-memory-family-dispatch.sh,capture-floatx4-memory.sh,capture-floatx4-integer-family-regressions.sh,package-floatx4-memory-evidence.sh,seal-floatx4-memory-evidence.py,preserve-floatx4-first-capture.py} "$retain/validation/"
cp "$validation"/{revision.txt,git-status.txt} "$retain/validation/"
cp -a "$validation/fresh-native" "$retain/native/dispatch-fix-preparation"
for mode in focused-default full-default full-dense; do
  source="$validation/$mode"
  destination="$retain/validation/$mode"
  mkdir "$destination"
  cp "$source"/{command.txt,output.log,exit-status.txt} "$destination/"
  tar -czf "$destination/junit.tar.gz" --exclude='./binary' -C "$source/test-results" .
done
mkdir "$retain/validation/before-family-dispatch"
cp -a build/floatx4-memory-python "$retain/validation/before-family-dispatch/python-checks"
cp build/check-floatx4-memory-python.sh "$retain/validation/before-family-dispatch/python-checks/"
cp build/floatx4-memory-validation/{revision.txt,git-status.txt} "$retain/validation/before-family-dispatch/"
for mode in focused-default full-default full-dense; do
  source="build/floatx4-memory-validation/$mode"
  destination="$retain/validation/before-family-dispatch/$mode"
  mkdir "$destination"
  cp "$source"/{command.txt,output.log,exit-status.txt} "$destination/"
  tar -czf "$destination/junit.tar.gz" --exclude='./binary' -C "$source/test-results" .
done
cp -a build/floatx4-memory-first-runtime-capture "$retain/first-runtime-capture"
mkdir "$retain/validation/first-focused"
cp build/floatx4-memory-focused-first-check/{source-revision.txt,command.txt,output.log,exit-status.txt} "$retain/validation/first-focused/"
tar -czf "$retain/validation/first-focused/junit.tar.gz" --exclude='./binary' -C build/floatx4-memory-focused-first-check/test-results .
cp -a build/floatx4-storage-first-check "$retain/validation/sandbox-bootstrap-failure"
cp -a build/floatx4-memory-first-proof-check "$retain/validation/first-proof-check"
cp -a build/floatx4-memory-fresh-preparation "$retain/native/full-prepare"
cp build/{prepare-floatx4-memory-all.sh,check-floatx4-storage.sh,check-floatx4-memory-focused.sh} "$retain/validation/"
cp build/recheck-floatx4-frame-metadata.sh "$retain/validation/"
for file in input-provenance.json oracle.tsv graph-cases.json; do gzip -n "$retain/$file"; done
for family in int32x4 word32x4; do
  capture="build/$family-bytearray-float-family-regression"
  destination="$retain/regression-$family"
  test -f "$capture/evidence.json"
  mkdir "$destination"
  cp "$capture"/{evidence.json,runtime-snapshot.json,java-version.txt,jdk-release.txt,architecture.txt,stages.txt,capture-runtime-audit.py,capture-test-runtime-audit.py} "$destination/"
  cp "$capture"/{prepare,javac-probe,javac-reader,check}.{command.txt,log,exit-status.txt} "$destination/"
  for file in input-provenance.json oracle.tsv graph-cases.json; do
    gzip -n -c "$capture/$file" > "$destination/$file.gz"
  done
  for stage in pre post; do
    if test "$family" = int32x4; then module=SimdInt32X4ByteArray; else module=SimdWord32X4ByteArray; fi
    gzip -n -c "build/simd-$family-bytearray/$stage-core/$module.json" > "$destination/$stage-core.json.gz"
    for backend in ast bytecode; do
      for entry in vectorIndexWorker scalarIndexWorker vectorStoreGraph scalarStoreGraph; do
        label="$stage-$backend-$entry"
        mkdir "$destination/$label"
        cp "$capture/$label"/{run.command.txt,run.log,run.exit-status.txt,parse.command.txt,parse.log,parse.exit-status.txt} "$destination/$label/"
        gzip -n -c "$capture/$label/final-lir.txt" > "$destination/$label/final-lir.txt.gz"
        graphs=("$capture/$label"/parsed/graph-*.json)
        test "${#graphs[@]}" = 1
        gzip -n -c "${graphs[0]}" > "$destination/$label/graph.json.gz"
      done
    done
  done
done
