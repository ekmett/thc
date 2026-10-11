#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
set -euo pipefail

mode=${1:-native}
[[ $mode == native || $mode == hotspot || $mode == graal ]] || { echo 'Expected native, hotspot or graal' >&2; exit 1; }
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'Requires Linux x86_64' >&2; exit 1; }
: "${JAM_CI_TOOLS:?Set JAM_CI_TOOLS to the dependency installation directory}"
mkdir -p "$JAM_CI_TOOLS"
prefix=$(cd "$JAM_CI_TOOLS" && pwd)
scratch=$(mktemp -d "${TMPDIR:-/tmp}/jam-ci.XXXXXX")
trap 'rm -rf "$scratch"' EXIT

packages=(build-essential ca-certificates curl zstd ninja-build patchelf binutils autoconf m4 unzip zip zlib1g-dev)
if [[ $mode != native ]]; then
  packages+=(libx11-dev libxext-dev libxrender-dev libxrandr-dev libxtst-dev libxt-dev
             libcups2-dev libfontconfig1-dev libasound2-dev libfreetype-dev libnuma-dev)
fi
sudo apt-get update
sudo apt-get install --yes --no-install-recommends "${packages[@]}"

download() {
  local name=$1 digest=$2 url=$3
  curl --fail --location --retry 3 --connect-timeout 20 --max-time 600 \
    --silent --show-error --output "$scratch/$name" "$url"
  echo "$digest  $scratch/$name" | sha256sum --check
}

if [[ ! -f $prefix/native-ready || ! -f $prefix/llvm23/lib/x86_64-unknown-linux-gnu/libc++.a ]]; then
  # Official LLVM and CMake release asset digests.
  download llvm.tar.zst 6382de1c1a210ce5a5cc49d18bc8444d137742e7cbf9b19f4ae602bb1ab52534 \
    https://github.com/llvm/llvm-project/releases/download/llvmorg-23.1.2/LLVM-23.1.2-Linux-X64.tar.zst
  mkdir -p "$prefix/llvm23"
  archive=LLVM-23.1.2-Linux-X64
  # Keep the compiler, module scanner, archive tools, builtin runtime and both
  # libc++ header trees. Shared runtimes retain their SONAME symlink chains.
  zstd --long=30 -dc "$scratch/llvm.tar.zst" | tar -xf - -C "$prefix/llvm23" \
    --strip-components=1 --wildcards \
    "$archive/bin/clang" "$archive/bin/clang++" "$archive/bin/clang-23" \
    "$archive/bin/clang-scan-deps" "$archive/bin/llvm-ar" \
    "$archive/bin/llvm-ranlib" "$archive/bin/llvm-nm" \
    "$archive/lib/clang/23" "$archive/include/c++/v1" \
    "$archive/include/x86_64-unknown-linux-gnu/c++/v1" \
    "$archive/lib/x86_64-unknown-linux-gnu/libc++*.so*" \
    "$archive/lib/x86_64-unknown-linux-gnu/libunwind.so*" \
    "$archive/lib/x86_64-unknown-linux-gnu/libc++*.a" \
    "$archive/lib/x86_64-unknown-linux-gnu/libunwind.a"
  rm "$scratch/llvm.tar.zst"

  # The binary archive omits the runtime licenses. Pin the corresponding
  # llvmorg-23.1.2 source commit and preserve every component's complete text.
  license_commit=85ac560262434c9ccfc0c183ec22d4138ed647fb
  mkdir -p "$prefix/llvm23/licenses"
  : > "$prefix/llvm23/LICENSE.TXT"
  while read -r component digest; do
    download "$component-LICENSE.TXT" "$digest" \
      "https://raw.githubusercontent.com/llvm/llvm-project/$license_commit/$component/LICENSE.TXT"
    cp "$scratch/$component-LICENSE.TXT" "$prefix/llvm23/licenses/$component-LICENSE.TXT"
    {
      printf 'LLVM 23.1.2 - %s/LICENSE.TXT\n\n' "$component"
      cat "$scratch/$component-LICENSE.TXT"
      printf '\n\n'
    } >> "$prefix/llvm23/LICENSE.TXT"
  done <<'LICENSES'
libcxx 539dd7aed86e8a4f12cbdd0e6c50c189c7d74847e4fecc64ce2c6ee3a01da38b
libcxxabi e2b35be49f7284a45b7baca8fc7b3ab7440e7902392b2528a457816b5bb2a15c
libunwind b5efebcaca80879234098e52d1725e6d9eb8fb96a19fce625d39184b705f7b6d
LICENSES

  download cmake.tar.gz d6c83076c575bc00b823522ac974bda66d0af05d6ddc30e739c12385cf32c6cc \
    https://github.com/Kitware/CMake/releases/download/v4.4.3/cmake-4.4.3-linux-x86_64.tar.gz
  mkdir -p "$prefix/cmake"
  tar -xzf "$scratch/cmake.tar.gz" -C "$prefix/cmake" --strip-components=1
  rm "$scratch/cmake.tar.gz"

  # Both the release API digest and the published SHA-256 file identify this archive.
  download jdk.tar.gz 987387933b64b9833846dee373b640440d3e1fd48a04804ec01a6dbf718e8ab8 \
    https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.2%2B10/OpenJDK25U-jdk_x64_linux_hotspot_25.0.2_10.tar.gz
  mkdir -p "$prefix/jdk25"
  tar -xzf "$scratch/jdk.tar.gz" -C "$prefix/jdk25" --strip-components=1
  rm "$scratch/jdk.tar.gz"
  touch "$prefix/native-ready"
fi

if [[ $mode == graal && ! -f $prefix/graal-ready ]]; then
  # Unmodified matching libgraal checks the VM's unsupported-compiler admission.
  download graalvm.tar.gz b2bc38d0c4141426eb44d0eefa3cc172c96faf92727d703b61541699128b6fc7 \
    https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.3.4.1/graalvm-community-jdk-25i3-25.0.4.1_linux-x64_bin.tar.gz
  mkdir -p "$prefix/graal25"
  tar -xzf "$scratch/graalvm.tar.gz" -C "$prefix/graal25" --strip-components=1
  rm "$scratch/graalvm.tar.gz"
  touch "$prefix/graal-ready"
fi

# LLVM is selected explicitly for Jam; GCC remains the JDK and C compiler.
{
  printf 'export JAM_CXX=%q\n' "$prefix/llvm23/bin/clang++"
  printf 'export JAM_LIBCXX_PREFIX=%q\n' "$prefix/llvm23"
  printf 'export JAM_CMAKE=%q\n' "$prefix/cmake/bin/cmake"
  printf 'export JAM_NINJA=%q\n' /usr/bin/ninja
  printf 'export JAM_BOOT_JDK=%q\n' "$prefix/jdk25"
  printf 'export JAM_AUTOCONF=%q\n' /usr/bin/autoconf
  printf 'export JAM_M4=%q\n' /usr/bin/m4
  printf 'export JAM_MAKE=%q\n' /usr/bin/make
  printf 'export CC=%q\n' /usr/bin/gcc
  printf 'export CXX=%q\n' /usr/bin/g++
  if [[ $mode == graal ]]; then
    printf 'export JAM_STOCK_GRAAL_HOME=%q\n' "$prefix/graal25"
  fi
} > "$prefix/env.sh"
# shellcheck source=/dev/null
source "$prefix/env.sh"
"$JAM_CXX" --version
"$JAM_CMAKE" --version
"$JAM_NINJA" --version
"$JAM_BOOT_JDK/bin/java" -version
df -h "$prefix" .
