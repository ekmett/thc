#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -euo pipefail

# A bounded compatibility probe, not the shipping launcher or guest AOT.
# Build installDist first with the pinned toolchain. Keep a shared resource
# lease around this entire command when running on a shared development host.
if (( $# > 1 )); then
    echo "Usage: bash bin/native-image-pure.sh [OUTPUT]" >&2
    exit 2
fi
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
lib_dir="$repo_root/build/install/thc/lib"
output=${1:-$repo_root/build/native-image/thc-pure}
native_image=${JAVA_HOME:+$JAVA_HOME/bin/}native-image

if [[ ! -f "$lib_dir/thc-0.1-experiment.jar" ]]; then
    echo "Run ./gradlew installDist with the pinned toolchain first." >&2
    exit 2
fi
"$repo_root/gradlew" -p "$repo_root" --offline -q verifyJamToolchain

image_classpath=
for image_jar in "$lib_dir"/*.jar; do
    case "${image_jar##*/}" in llvm-*|thc-llvm-language-*|antlr4-*|truffle-nfi-*) continue ;; esac
    image_classpath="${image_classpath:+$image_classpath:}$image_jar"
done
initialization=
while IFS= read -r prepared_class; do
    [[ -z "$prepared_class" || "$prepared_class" == \#* ]] && continue
    if [[ ! "$prepared_class" =~ ^[a-zA-Z0-9_.$]+$ ]]; then
        echo "Invalid class in pure initialization inventory: $prepared_class" >&2
        exit 2
    fi
    initialization="${initialization:+$initialization,}$prepared_class"
done < "$repo_root/bin/native-image/pure-initialization.txt"
mkdir -p -- "$(dirname -- "$output")"
# Runtime graphs cannot encode simulated objects with no host backing. Reuse
# the prepared-image path's general field-folding eligibility correction.
overlay_dir="$(dirname -- "$output")/runtime-simulated-folds"
bash "$repo_root/research/native-image-preparation/runtime-simulated-folds/prepare.sh" "$overlay_dir"
exec "$native_image" --gc=jam \
    "-J--patch-module=org.graalvm.nativeimage.builder=$overlay_dir/thc-svm-runtime-simulated-folds.jar" -Ob -J-Xmx8g -J-XX:ActiveProcessorCount=2 --parallelism=2 \
    --add-modules=jdk.incubator.vector \
    --enable-native-access=ALL-UNNAMED,org.graalvm.truffle \
    --add-exports=org.graalvm.truffle.runtime/com.oracle.truffle.runtime=ALL-UNNAMED \
    --initialize-at-build-time="$initialization" \
    -cp "$image_classpath" thc.Main "$output"
