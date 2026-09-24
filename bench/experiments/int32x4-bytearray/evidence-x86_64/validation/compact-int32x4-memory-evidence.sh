#!/usr/bin/env bash
set -euo pipefail
cd /home/ekmett/ai/thc-int32x4-bytearray-01a0cdeb
retain=bench/experiments/int32x4-bytearray/evidence-x86_64
test ! -e "$retain/SHA256SUMS"
for file in input-provenance.json oracle.tsv graph-cases.json \
  first-capture/input-provenance.json first-capture/graph-cases.json \
  native/provenance.json native/expected.tsv native/oracle.tsv native/pre-audit.json native/post-audit.json; do
  gzip -n "$retain/$file"
done
for file in "$retain"/native/prepare-run-*/5.log; do gzip -n "$file"; done
for stage in pre post; do
  for backend in ast bytecode; do
    for entry in vectorIndexWorker scalarIndexWorker vectorStoreGraph scalarStoreGraph; do
      label="$stage-$backend-$entry"
      gzip -n "$retain/$label/final-lir.txt" \
        "$retain/first-capture/$label/final-lir-corrected-reader.txt" \
        "$retain/reader-checks/first-lir/$label.txt"
    done
  done
done
