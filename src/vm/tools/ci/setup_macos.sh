#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
set -euo pipefail

mode=${1:-native}
[[ $mode == native || $mode == hotspot || $mode == graal ]] || { echo 'Expected native, hotspot or graal' >&2; exit 1; }
[[ $(uname -s) == Darwin && $(uname -m) == arm64 ]] || { echo 'Requires macOS arm64' >&2; exit 1; }
: "${JAM_CI_TOOLS:?Set JAM_CI_TOOLS to the dependency installation directory}"
mkdir -p "$JAM_CI_TOOLS"
prefix=$(cd "$JAM_CI_TOOLS" && pwd)
scratch=$(mktemp -d "${TMPDIR:-/tmp}/jam-ci.XXXXXX")
trap 'rm -rf "$scratch"' EXIT

download() {
  local name=$1 digest=$2 url=$3
  shift 3
  curl --fail --location --retry 3 --connect-timeout 20 --max-time 600 \
    --silent --show-error "$@" --output "$scratch/$name" "$url"
  echo "$digest  $scratch/$name" | shasum -a 256 --check
}

if [[ ! -f $prefix/native-ready ]]; then
  # Release asset digests: github.com/llvm/llvm-project/releases/tag/llvmorg-23.1.2
  download llvm.tar.zst 3da0e91b5dfe3a5ec795ad2be79b3f5e6f28c8b23edcd3847fad7742b25e0507 \
    https://github.com/llvm/llvm-project/releases/download/llvmorg-23.1.2/LLVM-23.1.2-macOS-ARM64.tar.zst
  mkdir -p "$prefix/llvm23"
  # The release uses a 1 GiB zstd window. Keep only compiler, module scanner,
  # archive tools and builtin headers; the complete distribution exceeds 7 GiB.
  zstd --long=30 -dc "$scratch/llvm.tar.zst" | tar -xf - -C "$prefix/llvm23" --strip-components=1 \
    LLVM-23.1.2-macOS-ARM64/bin/clang LLVM-23.1.2-macOS-ARM64/bin/clang++ \
    LLVM-23.1.2-macOS-ARM64/bin/clang-23 LLVM-23.1.2-macOS-ARM64/bin/clang-scan-deps \
    LLVM-23.1.2-macOS-ARM64/bin/llvm-ar LLVM-23.1.2-macOS-ARM64/bin/llvm-ranlib \
    LLVM-23.1.2-macOS-ARM64/bin/llvm-nm LLVM-23.1.2-macOS-ARM64/lib/clang/23
  rm "$scratch/llvm.tar.zst"

  # Homebrew's immutable LLVM 22.1.8 arm64_sequoia bottle supplies the working
  # libc++/libc++abi/libunwind combination (formulae.brew.sh/api/formula/llvm@22.json).
  download libcxx.tar.gz 9705fde2a45b982f91bdfe60d1023fabf12266e67d24ba7bacc963c2950174d1 \
    https://ghcr.io/v2/homebrew/core/llvm/22/blobs/sha256:9705fde2a45b982f91bdfe60d1023fabf12266e67d24ba7bacc963c2950174d1 \
    --header 'Authorization: Bearer QQ=='
  mkdir -p "$prefix/libcxx22"
  tar -xzf "$scratch/libcxx.tar.gz" -C "$prefix/libcxx22" --strip-components=2 \
    'llvm@22/22.1.8/include/c++' 'llvm@22/22.1.8/lib/c++' 'llvm@22/22.1.8/lib/unwind' \
    'llvm@22/22.1.8/LICENSE.TXT'
  for entry in c++/libc++ c++/libc++abi unwind/libunwind; do
    library="$prefix/libcxx22/lib/$entry.1.0.dylib"
    chmod u+w "$library"
    install_name_tool -id "@rpath/${entry##*/}.1.dylib" "$library"
    codesign --force --sign - "$library"
  done
  rm "$scratch/libcxx.tar.gz"

  download cmake.tar.gz 0c5d65251c14cc884bfa16bdbed3c263ce5bffe2e21c0d0d00962cb0610464fa \
    https://github.com/Kitware/CMake/releases/download/v4.4.3/cmake-4.4.3-macos-universal.tar.gz
  mkdir -p "$prefix/cmake"
  tar -xzf "$scratch/cmake.tar.gz" -C "$prefix/cmake" --strip-components=1
  download ninja.zip c99048673aa765960a99cf10c6ddb9f1fad506099ff0a0e137ad8960a88f321b \
    https://github.com/ninja-build/ninja/releases/download/v1.13.2/ninja-mac.zip
  mkdir -p "$prefix/ninja"
  unzip -q -o "$scratch/ninja.zip" -d "$prefix/ninja"

  download jdk.tar.gz 74ff6e892924a49767c35eb61251b1969c03213819d51065d9b8f9e0238c4f97 \
    https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.2%2B10/OpenJDK25U-jdk_aarch64_mac_hotspot_25.0.2_10.tar.gz
  mkdir -p "$prefix/jdk25"
  tar -xzf "$scratch/jdk.tar.gz" -C "$prefix/jdk25" --strip-components=1
  touch "$prefix/native-ready"
fi

if [[ $mode != native && ! -f $prefix/hotspot-ready ]]; then
  # Build the small GNU tools with Apple's compiler; do not change PATH to LLVM.
  download m4.tar.gz 6ac4fc31ce440debe63987c2ebbf9d7b6634e67a7c3279257dc7361de8bdb3ef \
    https://mirrors.kernel.org/gnu/m4/m4-1.4.20.tar.gz
  download autoconf.tar.gz afb181a76e1ee72832f6581c0eddf8df032b83e2e0239ef79ebedc4467d92d6e \
    https://mirrors.kernel.org/gnu/autoconf/autoconf-2.72.tar.gz
  download make.tar.gz dd16fb1d67bfab79a72f5e8390735c49e3e8e70b4945a15ab1f81ddb78658fb3 \
    https://mirrors.kernel.org/gnu/make/make-4.4.1.tar.gz
  for package in m4 autoconf make; do
    mkdir "$scratch/$package"
    tar -xzf "$scratch/$package.tar.gz" -C "$scratch/$package" --strip-components=1
    (
      cd "$scratch/$package"
      if [[ $package == autoconf ]]; then export M4="$prefix/gnu/bin/m4"; fi
      ./configure --prefix="$prefix/gnu"
      /usr/bin/make -j "${JAM_JOBS:-3}"
      /usr/bin/make install
    )
  done
  touch "$prefix/hotspot-ready"
fi

if [[ $mode == graal && ! -f $prefix/graal-ready ]]; then
  # The unmodified matching compiler is also used to check Jam's admission guard.
  download graalvm.tar.gz ebfab1d74420f355a459076162012d6835fa6068bd9d2f230f1fcaf7ee0dd923 \
    https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.3.4.1/graalvm-community-jdk-25i3-25.0.4.1_macos-aarch64_bin.tar.gz
  mkdir -p "$prefix/graal25"
  tar -xzf "$scratch/graalvm.tar.gz" -C "$prefix/graal25" --strip-components=1
  touch "$prefix/graal-ready"
fi

# This file can also be sourced when reproducing a CI job locally.
{
  printf 'export MACOSX_DEPLOYMENT_TARGET=%q\n' '15.5'
  printf 'export JAM_CXX=%q\n' "$prefix/llvm23/bin/clang++"
  printf 'export JAM_LIBCXX_PREFIX=%q\n' "$prefix/libcxx22"
  printf 'export JAM_CMAKE=%q\n' "$prefix/cmake/CMake.app/Contents/bin/cmake"
  printf 'export JAM_NINJA=%q\n' "$prefix/ninja/ninja"
  printf 'export JAM_BOOT_JDK=%q\n' "$prefix/jdk25/Contents/Home"
  if [[ $mode == graal ]]; then
    printf 'export JAM_STOCK_GRAAL_HOME=%q\n' "$prefix/graal25/Contents/Home"
  fi
  printf 'export JAM_AUTOCONF=%q\n' "$prefix/gnu/bin/autoconf"
  printf 'export JAM_M4=%q\n' "$prefix/gnu/bin/m4"
  printf 'export JAM_MAKE=%q\n' "$prefix/gnu/bin/make"
} > "$prefix/env.sh"
# shellcheck source=/dev/null
source "$prefix/env.sh"
"$JAM_CXX" --version
"$JAM_CMAKE" --version
"$JAM_NINJA" --version
"$JAM_BOOT_JDK/bin/java" -version
