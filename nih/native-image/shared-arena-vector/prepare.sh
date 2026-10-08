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
check_hash "$builder_dir/svm.src.zip" d9df54ffb53ebe5b10e976926eeab2a78c04d416a6dcd92a26afbb5bb6f51499
check_hash "$builder_dir/svm.jar" e00661ab2ba4282496a8d6c5394237b709f92502c5f05fe34399c302c364ce02
check_hash "$builder_dir/svm-foreign.jar" 4640624c79eb590d67f3183cdfcb2bd7f5e55b0ea4eb9e916ba2bedee8073fd9
work_dir=$(mktemp -d "$output_dir/work.XXXXXX")
mkdir -p "$work_dir/templates" "$work_dir/foreign" "$work_dir/builder" "$work_dir/builder-compiled" "$work_dir/tools"
foreign_module=org.graalvm.nativeimage.foreign
"$JAVA_HOME/bin/javac" -J-Xmx512m -proc:none -Xmaxerrs 10 \
    --module-path "$builder_dir" --add-modules "$foreign_module" \
    --patch-module "$foreign_module=$recipe_dir" \
    --add-exports "java.base/jdk.internal.foreign=$foreign_module" \
    --add-exports "java.base/jdk.internal.misc=$foreign_module" \
    --add-exports "java.base/jdk.internal.vm.vector=$foreign_module" \
    -d "$work_dir/templates" "$recipe_dir/VectorAccess.java" "$recipe_dir/SharedArenaVectorSupport.java"
"$JAVA_HOME/bin/javac" -J-Xmx512m -d "$work_dir/tools" "$recipe_dir/BuildOverlay.java"
"$JAVA_HOME/bin/java" -Xmx512m --module-path "$builder_dir" --add-modules "$foreign_module" \
    -cp "$work_dir/tools" BuildOverlay \
    "$builder_dir/svm-foreign.jar" "$work_dir/templates" "$work_dir/foreign"
source_file=com/oracle/svm/core/SubstrateOptions.java
mkdir -p "$work_dir/source/com/oracle/svm/core"
unzip -p "$builder_dir/svm.src.zip" "$source_file" > "$work_dir/source/$source_file"
(
    cd "$work_dir/source"
    GIT_CEILING_DIRECTORIES="$work_dir" git apply --no-index "$recipe_dir/shared-vector-capability.patch"
)
"$JAVA_HOME/bin/javac" -J-Xmx1g -proc:none -Xmaxerrs 10 \
    --module-path "$builder_dir" --add-modules org.graalvm.nativeimage.builder \
    --patch-module "org.graalvm.nativeimage.builder=$work_dir/source" \
    --add-exports java.base/jdk.internal.misc=org.graalvm.nativeimage.builder \
    --add-exports jdk.internal.vm.ci/jdk.vm.ci.amd64=org.graalvm.nativeimage.builder \
    --add-exports jdk.internal.vm.ci/jdk.vm.ci.code=org.graalvm.nativeimage.builder \
    --add-exports jdk.internal.vm.ci/jdk.vm.ci.meta=org.graalvm.nativeimage.builder \
    -d "$work_dir/builder-compiled" "$work_dir/source/$source_file"
mkdir -p "$work_dir/builder/META-INF/source/com/oracle/svm/core" "$work_dir/foreign/META-INF/source"
mkdir -p "$work_dir/builder/com/oracle/svm/core"
cp "$work_dir/builder-compiled/com/oracle/svm/core/SubstrateOptions.class" "$work_dir/builder/com/oracle/svm/core/"
cp "$work_dir/source/$source_file" "$work_dir/builder/META-INF/source/$source_file"
cp "$recipe_dir/VectorAccess.java" "$recipe_dir/SharedArenaVectorSupport.java" "$recipe_dir/BuildOverlay.java" "$work_dir/foreign/META-INF/source/"
for component in builder foreign; do
    cp "$JAVA_HOME/LICENSE.txt" "$work_dir/$component/META-INF/upstream-toolchain-LICENSE.txt"
    "$JAVA_HOME/bin/jar" --create --file "$work_dir/thc-svm-shared-arena-vector-$component.jar" \
        --date=2026-01-01T00:00:00Z -C "$work_dir/$component" .
    cp "$work_dir/thc-svm-shared-arena-vector-$component.jar" "$output_dir/"
done
sha256sum "$builder_dir/svm.src.zip" "$builder_dir/svm.jar" "$builder_dir/svm-foreign.jar" \
    "$JAVA_HOME/LICENSE.txt" "$recipe_dir/VectorAccess.java" "$recipe_dir/SharedArenaVectorSupport.java" \
    "$recipe_dir/BuildOverlay.java" "$recipe_dir/shared-vector-capability.patch" \
    "$output_dir/thc-svm-shared-arena-vector-builder.jar" "$output_dir/thc-svm-shared-arena-vector-foreign.jar" \
    > "$output_dir/provenance.sha256"
printf 'Prepared owned builder/foreign-module overlays in %s\n' "$output_dir"
