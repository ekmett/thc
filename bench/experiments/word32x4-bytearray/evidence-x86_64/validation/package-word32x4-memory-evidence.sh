#!/usr/bin/env bash
set -euo pipefail
cd /home/ekmett/ai/thc-word32x4-bytearray-01a0cdeb
capture=build/word32x4-bytearray-runtime
retain=bench/experiments/word32x4-bytearray/evidence-x86_64
test ! -e "$retain"
test -f "$capture/evidence.json"
test -f build/word32x4-memory-final-suite-report.json
mkdir -p "$retain/native" "$retain/validation" "$retain/reader-checks"
cp "$capture"/{evidence.json,input-provenance.json,runtime-snapshot.json,oracle.tsv,graph-cases.json,java-version.txt,jdk-release.txt,architecture.txt,stages.txt,capture-runtime-audit.py,capture-test-runtime-audit.py} "$retain/"
cp "$capture"/{prepare,javac-probe,javac-reader,check}.{command.txt,log,exit-status.txt} "$retain/"
for stage in pre post; do
  gzip -n -c "build/simd-word32x4-bytearray/$stage-core/SimdWord32X4ByteArray.json" > "$retain/$stage-core.json.gz"
  for backend in ast bytecode; do
    for entry in vectorIndexWorker scalarIndexWorker vectorStoreGraph scalarStoreGraph; do
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
for file in provenance.json expected.tsv oracle.tsv pre-audit.json post-audit.json; do
  gzip -n -c "build/simd-word32x4-bytearray/$file" > "$retain/native/$file.gz"
done
cp -a build/simd-word32x4-bytearray/prepare-run-* "$retain/native/"
cp -a build/word32x4-memory-python-canonical "$retain/python-checks"
cp build/check-word32x4-memory-python-canonical.sh "$retain/python-checks/"
cp build/{word32x4-memory-first-suite-report.json,word32x4-memory-final-suite-report.json,word32x4-memory-suite-report.py,validate-word32x4-memory.sh,validate-word32x4-memory-canonical.sh,capture-word32x4-memory.sh,reproduce-direct-vector-memory-proof.sh,package-word32x4-memory-evidence.sh,seal-word32x4-memory-evidence.py} "$retain/validation/"
cp build/word32x4-memory-validation-canonical/{revision.txt,git-status.txt} "$retain/validation/"
for mode in direct-proof-green focused-default full-default full-dense direct-proof-red; do
  source="build/word32x4-memory-validation-canonical/$mode"
  if [[ "$mode" == direct-proof-red ]]; then source=build/word32x4-memory-direct-proof-red; fi
  destination="$retain/validation/$mode"
  mkdir "$destination"
  cp "$source"/{command.txt,output.log,exit-status.txt} "$destination/"
  if [[ "$mode" == direct-proof-red ]]; then cp "$source"/{revision.txt,git-status.txt} "$destination/"; fi
  tar -czf "$destination/junit.tar.gz" --exclude='./binary' -C "$source/test-results" .
done
mkdir "$retain/validation/first"
cp build/word32x4-memory-validation/{revision.txt,git-status.txt} "$retain/validation/first/"
for mode in direct-proof-green focused-default full-default full-dense; do
  source="build/word32x4-memory-validation/$mode"
  destination="$retain/validation/first/$mode"
  mkdir "$destination"
  cp "$source"/{command.txt,output.log,exit-status.txt} "$destination/"
  tar -czf "$destination/junit.tar.gz" --exclude='./binary' -C "$source/test-results" .
done
mkdir "$retain/native/full-prepare" "$retain/native/hardened-prepare"
cp build/word32x4-memory-fresh-preparation/{revision.txt,git-status.txt,command.txt,output.log,exit-status.txt} "$retain/native/full-prepare/"
cp build/word32x4-memory-hardened-preparation/{revision.txt,git-status.txt,int32x4.command.txt,int32x4.output.log,int32x4.exit-status.txt,word32x4.command.txt,word32x4.output.log,word32x4.exit-status.txt} "$retain/native/hardened-prepare/"
mkdir "$retain/native/canonical-prepare"
cp build/word32x4-memory-canonical-preparation/{revision.txt,git-status.txt,int32x4.command.txt,int32x4.output.log,int32x4.exit-status.txt,word32x4.command.txt,word32x4.output.log,word32x4.exit-status.txt} "$retain/native/canonical-prepare/"
for file in input-provenance.json oracle.tsv graph-cases.json; do gzip -n "$retain/$file"; done
for file in "$retain"/native/prepare-run-*/5.log; do if [[ -f "$file" ]]; then gzip -n "$file"; fi; done
for file in "$retain"/native/prepare-run-*/provenance.json; do gzip -n "$file"; done
