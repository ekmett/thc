#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -euo pipefail
cd "$(dirname "$0")/../../.."
: "${THC_RUNTIME_LIB:?Set THC_RUNTIME_LIB to an existing THC runtime lib directory}"
: "${JAVA_HOME:?Set JAVA_HOME to GraalVM}"
test -d "$THC_RUNTIME_LIB"
test "$(uname -s)" = Linux
test "$(uname -m)" = x86_64
probe_clang="${THC_CLANG:-clang}"
test "$("$probe_clang" --target=x86_64-unknown-linux-gnu -dumpmachine)" = x86_64-unknown-linux-gnu
mkdir -p build/gmp-transport
"$probe_clang" --target=x86_64-unknown-linux-gnu -O1 -g -fembed-bitcode -shared -fPIC \
  bench/experiments/gmp-sulong/transport.c -lgmp -o build/gmp-transport/transport.so \
  >build/gmp-transport/compile.log 2>&1
readelf -S build/gmp-transport/transport.so >build/gmp-transport/sections.log 2>build/gmp-transport/readelf-warnings.log
readelf -d build/gmp-transport/transport.so >build/gmp-transport/dependencies.log 2>>build/gmp-transport/readelf-warnings.log
readelf -Ws build/gmp-transport/transport.so >build/gmp-transport/symbols.log 2>>build/gmp-transport/readelf-warnings.log
rg 'llvmbc' build/gmp-transport/sections.log
rg 'libgmp' build/gmp-transport/dependencies.log
rg 'UND.*__gmpn_add_1' build/gmp-transport/symbols.log
"$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -cp "$THC_RUNTIME_LIB/*" \
  bench/experiments/gmp-sulong/GmpTransportProbe.java build/gmp-transport/transport.so \
  >build/gmp-transport/sulong.log 2>&1 || { tail -80 build/gmp-transport/sulong.log; exit 1; }
tail -10 build/gmp-transport/sulong.log
