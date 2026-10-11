#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
native_build=${THC_VM_NATIVE_BUILD:-$root/build-jam}
: "${JAM_BOOT_JDK:?Set JAM_BOOT_JDK to a JDK 24 or 25 installation}"
flavor=${JAM_BUILD_FLAVOR:-fastdebug}
case "$flavor" in release|fastdebug) ;; *) echo "Unsupported JAM_BUILD_FLAVOR: $flavor" >&2; exit 1;; esac
configure_flags=("--with-boot-jdk=$JAM_BOOT_JDK" --with-boot-jdk-jvmargs=-Xshare:off)
# HotSpot is the LabsJDK builder for the GraalVM distribution.
source_dir="$root/upstream/labsjdk25"
target=graal-builder-image
pins="$root/config/source-pins.json"
python_bin=${JAM_PYTHON:-python3}
case $(uname -s) in
  CYGWIN*) pins=$(cygpath -m "$pins"); python_bin=$(cygpath -u "$python_bin");;
esac
version=$("$python_bin" -c 'import json,sys; print(json.load(open(sys.argv[1]))["labsjdk25"]["version"])' "$pins")
version=${version%$'\r'}
configure_flags+=("--with-version-string=$version" --with-build-user=thc-vm --with-vendor-name='THC VM')
configure_flags+=("$@")
case $(uname -s) in
  CYGWIN*)
    export AUTOCONF=${JAM_AUTOCONF:-/usr/bin/autoconf}
    export M4=${JAM_M4:-/usr/bin/m4}
    make_bin=${JAM_MAKE:-/usr/bin/make}
    native_root=$(cygpath -m "$root")
    native_build=$(cygpath -m "$native_build")
    native_flags=("--with-extra-cxxflags=-I$native_root/src/adapter"
                  "--with-extra-ldflags=-libpath:$native_build thc-vm.lib")
    # The build runs its own newly linked java before assembling the images.
    export PATH="$(cygpath -u "$native_build"):$PATH"
    ;;
  *)
    export AUTOCONF=${JAM_AUTOCONF:-$root/.toolchains/autoconf-install/bin/autoconf}
    export M4=${JAM_M4:-$root/.toolchains/gnu/bin/m4}
    make_bin=${JAM_MAKE:-$root/.toolchains/gnu/bin/make}
    native_flags=("--with-extra-cxxflags=-I$root/src/adapter"
                  "--with-extra-ldflags=-L$native_build -lthc-vm -Wl,-rpath,$native_build")
    ;;
esac
cd "$source_dir"
bash configure "${configure_flags[@]}" \
  "--with-debug-level=$flavor" --with-jvm-variants=server --with-jvm-features=jamgc,epsilongc,serialgc \
  --disable-warnings-as-errors "${native_flags[@]}"
"$make_bin" "CONF=server-$flavor" "JOBS=${JAM_JOBS:-8}" "$target"
