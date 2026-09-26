#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -euo pipefail
cd "$(dirname "$0")/../../.."
: "${THC_RUNTIME_LIB:?Set THC_RUNTIME_LIB to an existing THC runtime lib directory}"
: "${JAVA_HOME:?Set JAVA_HOME to GraalVM}"
test "$(uname -s)" = Linux
test "$(uname -m)" = x86_64
mkdir -p build/gmp-ownership/classes
"${THC_CLANG:-clang}" --target=x86_64-unknown-linux-gnu -O1 -g -fembed-bitcode -shared -fPIC \
  src/main/c/gmp-api.c -lgmp -o build/gmp-ownership/gmp-api.so \
  >build/gmp-ownership/compile-c.log 2>&1
readelf -Ws build/gmp-ownership/gmp-api.so >build/gmp-ownership/symbols.log 2>build/gmp-ownership/readelf-warnings.log
for target in add add_1 sub cmp; do rg "UND.*__gmpn_${target}$" build/gmp-ownership/symbols.log; done
"$JAVA_HOME/bin/javac" -cp "$THC_RUNTIME_LIB/*" \
  -d build/gmp-ownership/classes \
  bench/experiments/gmp-sulong/GmpOwnershipProbe.java >build/gmp-ownership/compile-java.log 2>&1 || {
    tail -70 build/gmp-ownership/compile-java.log; exit 1;
  }
"$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED \
  -cp "build/gmp-ownership/classes:$THC_RUNTIME_LIB/*" GmpOwnershipProbe build/gmp-ownership/gmp-api.so \
  >build/gmp-ownership/sulong.log 2>&1 || { tail -80 build/gmp-ownership/sulong.log; exit 1; }
tail -10 build/gmp-ownership/sulong.log
