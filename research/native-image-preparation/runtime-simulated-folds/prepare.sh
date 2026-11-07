#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -euo pipefail
[[ $# == 1 ]] || { echo 'Usage: JAVA_HOME=PINNED_JDK bash prepare.sh OUTPUT_DIR' >&2; exit 2; }
: "${JAVA_HOME:?Select pinned JAM GraalVM 25.3.4.1}"
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
# JAM 0e36293 release Linux-x86_64 package, Graal source 7b025988a922a73286d1326e1eddc1ca39d3f569.
# The two patched sources are unchanged from that upstream revision.
check_hash "$JAVA_HOME/release" d24492fa17446fdc4dec79b4856658d19f53459107e8ef133cf54a0fdcf2e004
check_hash "$builder_dir/svm.src.zip" 0606695bf6003fc53b56226dc58ce7e5843aaf306accd492ca34439e5b54bf03
check_hash "$builder_dir/svm.jar" b9222eeddfcc6271eb7d3243deeb72cf47552f3fd00f04107ce4023facd1b39d
work_dir=$(mktemp -d "$output_dir/work.XXXXXX")
source_file=com/oracle/svm/hosted/phases/InlineBeforeAnalysisGraphDecoderImpl.java
late_source=com/oracle/svm/graal/hosted/runtimecompilation/RuntimeCompiledMethodSupport.java
late_class='com/oracle/svm/graal/hosted/runtimecompilation/RuntimeCompiledMethodSupport$RuntimeCompilationReflectionProvider.class'
mkdir -p "$work_dir/source/$(dirname "$source_file")" "$work_dir/source/$(dirname "$late_source")" \
    "$work_dir/compiled" "$work_dir/classes"
(
    cd "$work_dir/source"
    "$JAVA_HOME/bin/jar" --extract --file "$builder_dir/svm.src.zip" "$source_file" "$late_source"
)
check_hash "$work_dir/source/$source_file" b07ac55986c4884da329d2472e5778056f7c69010355f3770a8379d51ca9eab5
check_hash "$work_dir/source/$late_source" a15d74a6c497dc979512a2024c4b32cbe330616f7b87dc14382ac4202db1215f
(
    cd "$work_dir/source"
    GIT_CEILING_DIRECTORIES="$work_dir" git apply --no-index "$recipe_dir/hosted-constant-eligibility.patch"
)
"$JAVA_HOME/bin/javac" -J-Xmx1g -proc:none -Xmaxerrs 10 \
    --module-path "$builder_dir" --add-modules org.graalvm.nativeimage.builder \
    --patch-module "org.graalvm.nativeimage.builder=$work_dir/source" \
    --add-exports jdk.internal.vm.ci/jdk.vm.ci.meta=org.graalvm.nativeimage.builder,org.graalvm.nativeimage.pointsto,org.graalvm.nativeimage.base \
    --add-exports jdk.internal.vm.ci/jdk.vm.ci.meta.annotation=org.graalvm.nativeimage.builder,org.graalvm.nativeimage.pointsto,org.graalvm.nativeimage.base \
    --add-exports jdk.internal.vm.ci/jdk.vm.ci.code=org.graalvm.nativeimage.builder,org.graalvm.nativeimage.pointsto,org.graalvm.nativeimage.base \
    -d "$work_dir/compiled" "$work_dir/source/$source_file" "$work_dir/source/$late_source"
# Only the two modified classes are installed. The enclosing support class and
# its other nested classes retain their original pinned builder implementations.
mkdir -p "$work_dir/classes/$(dirname "$source_file")" "$work_dir/classes/$(dirname "$late_class")"
cp "$work_dir/compiled/${source_file%.java}.class" "$work_dir/classes/${source_file%.java}.class"
cp "$work_dir/compiled/$late_class" "$work_dir/classes/$late_class"
for file in "$source_file" "$late_source"; do
    mkdir -p "$work_dir/classes/META-INF/source/$(dirname "$file")"
    cp "$work_dir/source/$file" "$work_dir/classes/META-INF/source/$file"
done
cp "$JAVA_HOME/LICENSE.txt" "$work_dir/classes/META-INF/upstream-toolchain-LICENSE.txt"
"$JAVA_HOME/bin/jar" --create --file "$work_dir/thc-svm-runtime-simulated-folds.jar" \
    --date=2026-01-01T00:00:00Z -C "$work_dir/classes" .
cp "$work_dir/thc-svm-runtime-simulated-folds.jar" "$output_dir/thc-svm-runtime-simulated-folds.jar"
sha256sum "$JAVA_HOME/release" "$builder_dir/svm.src.zip" "$builder_dir/svm.jar" "$JAVA_HOME/LICENSE.txt" \
    "$recipe_dir/hosted-constant-eligibility.patch" "$work_dir/source/$source_file" "$work_dir/source/$late_source" \
    "$output_dir/thc-svm-runtime-simulated-folds.jar" > "$output_dir/provenance.sha256"
printf 'Prepared isolated builder overlay: %s\n' "$output_dir/thc-svm-runtime-simulated-folds.jar"
