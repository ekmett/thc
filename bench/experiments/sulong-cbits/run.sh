#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -eu
thc_root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
thc_probe="$thc_root/bench/experiments/sulong-cbits"
thc_output="$thc_root/build/sulong-cbits"
thc_clang=${THC_CLANG:-clang}
mkdir -p "$thc_output"
make -C "$thc_root" check-java >/dev/null

"$thc_clang" -std=c11 -Wall -Wextra -Werror -O1 -g -emit-llvm -c \
    "$thc_probe/cbits.c" -o "$thc_output/cbits.bc"
"$thc_clang" -std=c11 -Wall -Wextra -Werror -O1 -DTHC_NATIVE_ORACLE \
    "$thc_probe/cbits.c" -o "$thc_output/native"
"$thc_output/native" > "$thc_output/native.tsv"
for thc_mode in interpreted compiled; do
    "$thc_root/gradlew" -p "$thc_probe" run -PprobeMode="$thc_mode" --console=plain
done
