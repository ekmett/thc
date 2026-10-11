#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
cmake_bin=${JAM_CMAKE:-$root/.toolchains/build-tools/bin/cmake}
ninja_bin=${JAM_NINJA:-$root/.toolchains/build-tools/bin/ninja}
compiler=${JAM_CXX:-$root/.toolchains/llvm23/bin/clang++}
library=${JAM_LIBCXX_PREFIX:-/opt/homebrew/opt/llvm@22}
args=(-S "$root/.." -B build-jam -DJAM_BUILD_VM=ON -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja_bin"
      "-DCMAKE_CXX_COMPILER=$compiler" -DCMAKE_BUILD_TYPE=Release)
if [[ $(uname) == Darwin ]]; then
  args+=("-DCMAKE_OSX_SYSROOT=$(xcrun --show-sdk-path)"
         "-DCMAKE_OSX_DEPLOYMENT_TARGET=${MACOSX_DEPLOYMENT_TARGET:-15.5}"
         "-DCMAKE_CXX_FLAGS=-nostdinc++ -isystem $library/include/c++/v1"
         "-DCMAKE_EXE_LINKER_FLAGS=-L$library/lib/c++ -Wl,-rpath,$library/lib/c++"
         "-DCMAKE_SHARED_LINKER_FLAGS=-L$library/lib/c++ -Wl,-rpath,$library/lib/c++"
         "-DJAM_VM_RUNTIME=$library/lib/c++")
elif [[ -n ${JAM_LIBCXX_PREFIX:-} ]]; then
  triple=$("$compiler" -print-target-triple)
  runtime="$library/lib/$triple"
  if [[ ! -f $runtime/libc++.so ]]; then runtime="$library/lib"; fi
  if [[ ! -f $runtime/libc++.so ]]; then
    echo "Cannot find libc++.so under $library" >&2
    exit 1
  fi
  # The build tree may use an external libc++; packaging gives each DSO its
  # own relative RUNPATH. RPATH here also resolves libc++'s indirect libraries.
  args+=("-DCMAKE_CXX_FLAGS=-stdlib=libc++ -isystem $library/include/$triple/c++/v1 -isystem $library/include/c++/v1"
         "-DCMAKE_EXE_LINKER_FLAGS=-L$runtime -Wl,-rpath,$runtime -Wl,--disable-new-dtags"
         "-DCMAKE_SHARED_LINKER_FLAGS=-L$runtime -Wl,-rpath,$runtime -Wl,--disable-new-dtags")
fi
"$cmake_bin" "${args[@]}"
"$cmake_bin" --build build-jam -j "${JAM_JOBS:-8}"
"$(dirname "$cmake_bin")/ctest" --test-dir build-jam --output-on-failure
