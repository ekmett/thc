#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -euo pipefail
[[ $# == 1 ]] || { echo 'Usage: JAVA_HOME=PINNED_JDK bash prepare.sh OUTPUT_DIR' >&2; exit 2; }
: "${JAVA_HOME:?Select GraalVM 25.3.4.1}"
recipe_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
mkdir -p "$1"
output_dir=$(cd -- "$1" && pwd)
builder_dir="$JAVA_HOME/lib/svm/builder"
[[ "$("$JAVA_HOME/bin/native-image" --version)" == *25.3.4.1* ]] || exit 2
check_hash() {
    local actual
    actual=$(sha256sum "$1")
    [[ "${actual%% *}" == "$2" ]] || { echo "Pinned builder input hash mismatch: $1" >&2; exit 1; }
}
# JAM 0e36293 release Linux-x86_64 package; authenticated by etc/jam-graalvm.json.
check_hash "$JAVA_HOME/release" d24492fa17446fdc4dec79b4856658d19f53459107e8ef133cf54a0fdcf2e004
check_hash "$builder_dir/svm.src.zip" 0606695bf6003fc53b56226dc58ce7e5843aaf306accd492ca34439e5b54bf03
check_hash "$builder_dir/svm.jar" b9222eeddfcc6271eb7d3243deeb72cf47552f3fd00f04107ce4023facd1b39d
work_dir=$(mktemp -d "$output_dir/work.XXXXXX")
mkdir -p "$work_dir/source/com/oracle/svm/graal/hosted/runtimecompilation" "$work_dir/classes"
source_file=com/oracle/svm/graal/hosted/runtimecompilation/RuntimeCompilationFeature.java
unzip -p "$builder_dir/svm.src.zip" "$source_file" > "$work_dir/source/$source_file"
check_hash "$work_dir/source/$source_file" a47dd7b98141e6ac75b9e2795f278ecab6fa993c70606ca4435bc09cb8ade0f2
(
    cd "$work_dir/source"
    GIT_CEILING_DIRECTORIES="$work_dir" git apply --no-index "$recipe_dir/runtime-replacements.patch"
)
"$JAVA_HOME/bin/javac" -J-Xmx1g -proc:none -Xmaxerrs 10 \
    --module-path "$builder_dir" --add-modules org.graalvm.nativeimage.builder \
    --patch-module "org.graalvm.nativeimage.builder=$work_dir/source" \
    --add-exports java.base/jdk.internal.access=org.graalvm.nativeimage.builder \
    --add-exports java.base/jdk.internal.foreign=org.graalvm.nativeimage.builder \
    --add-exports jdk.internal.vm.ci/jdk.vm.ci.meta=org.graalvm.nativeimage.builder,org.graalvm.nativeimage.pointsto,org.graalvm.nativeimage.base,org.graalvm.nativeimage.guest.staging \
    --add-exports jdk.internal.vm.ci/jdk.vm.ci.meta.annotation=org.graalvm.nativeimage.builder,org.graalvm.nativeimage.pointsto,org.graalvm.nativeimage.base \
    --add-exports jdk.internal.vm.ci/jdk.vm.ci.code=org.graalvm.nativeimage.builder,org.graalvm.nativeimage.pointsto,org.graalvm.nativeimage.base \
    -d "$work_dir/classes" "$work_dir/source/$source_file"
mkdir -p "$work_dir/classes/META-INF/source/com/oracle/svm/graal/hosted/runtimecompilation"
# Preserve the complete modified source and its original copyright/license header.
cp "$work_dir/source/$source_file" "$work_dir/classes/META-INF/source/$source_file"
cp "$JAVA_HOME/LICENSE.txt" "$work_dir/classes/META-INF/upstream-toolchain-LICENSE.txt"
"$JAVA_HOME/bin/jar" --create --file "$work_dir/thc-svm-runtime-snippet-providers.jar" \
    --date=2026-01-01T00:00:00Z -C "$work_dir/classes" .
cp "$work_dir/thc-svm-runtime-snippet-providers.jar" "$output_dir/thc-svm-runtime-snippet-providers.jar"
sha256sum "$builder_dir/svm.src.zip" "$builder_dir/svm.jar" "$JAVA_HOME/LICENSE.txt" "$recipe_dir/runtime-replacements.patch" \
    "$work_dir/source/$source_file" "$output_dir/thc-svm-runtime-snippet-providers.jar" > "$output_dir/provenance.sha256"
printf 'Prepared isolated builder overlay: %s\n' "$output_dir/thc-svm-runtime-snippet-providers.jar"
