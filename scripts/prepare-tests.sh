#!/bin/sh
# Generate the real GHC inputs required by all JVM tests, from a fresh checkout.
set -eu
cd "$(dirname "$0")/.."
compiler/build.sh
compiler/export.sh examples/THC/Fixtures.hs compiler/test-fixtures/StrictFields.hs compiler/test-fixtures/SpeculationAudit.hs
python3 scripts/check-speculation-metadata.py
python3 compiler/export-boot.py
mkdir -p build/native
scripts/native-oracle.sh > build/native/oracle.tsv
