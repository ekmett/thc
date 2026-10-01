#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -euo pipefail
[[ $# == 2 ]] || { echo 'Usage: JAVA_HOME=PINNED_JDK bash check-provider.sh OUTPUT_DIR OVERLAY_DIR' >&2; exit 2; }
: "${JAVA_HOME:?Select GraalVM 25.3.4.1}"
recipe_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
mkdir -p "$1/classes"
output_dir=$(cd -- "$1" && pwd)
overlay_dir=$(cd -- "$2" && pwd)
"$JAVA_HOME/bin/javac" -J-Xmx512m -d "$output_dir/classes" "$recipe_dir/ProviderGateTest.java"
builder="$overlay_dir/thc-svm-shared-arena-vector-builder.jar"
foreign="$overlay_dir/thc-svm-shared-arena-vector-foreign.jar"
# A marker without its matching implementation must not open the option guard.
mkdir -p "$output_dir/mismatch"
(
    cd "$output_dir/mismatch"
    "$JAVA_HOME/bin/jar" --extract --file "$foreign" com/oracle/svm/core/foreign/SharedArenaVectorSupport.class
)
"$JAVA_HOME/bin/jar" --create --file "$output_dir/mismatch.jar" --date=2026-01-01T00:00:00Z -C "$output_dir/mismatch" .
for component in wrappers-only phase-only; do
    mkdir -p "$output_dir/$component"
    case "$component" in
        wrappers-only) entry=com/oracle/svm/core/foreign/Target_jdk_internal_misc_ScopedMemoryAccess.class ;;
        phase-only) entry='com/oracle/svm/hosted/foreign/ForeignFunctionsFeature$SharedArenaSupportImpl.class' ;;
    esac
    (
        cd "$output_dir/$component"
        "$JAVA_HOME/bin/jar" --extract --file "$foreign" "$entry" com/oracle/svm/core/foreign/SharedArenaVectorSupport.class
    )
    "$JAVA_HOME/bin/jar" --create --file "$output_dir/$component.jar" --date=2026-01-01T00:00:00Z -C "$output_dir/$component" .
done
for combination in original builder-only foreign-only mismatched wrappers-only phase-only paired; do
    patches=()
    expected=false
    case "$combination" in
        builder-only) patches=(--patch-module "org.graalvm.nativeimage.builder=$builder") ;;
        foreign-only) patches=(--patch-module "org.graalvm.nativeimage.foreign=$foreign") ;;
        mismatched) patches=(--patch-module "org.graalvm.nativeimage.builder=$builder"
            --patch-module "org.graalvm.nativeimage.foreign=$output_dir/mismatch.jar") ;;
        wrappers-only|phase-only) patches=(--patch-module "org.graalvm.nativeimage.builder=$builder"
            --patch-module "org.graalvm.nativeimage.foreign=$output_dir/$combination.jar") ;;
        paired) patches=(--patch-module "org.graalvm.nativeimage.builder=$builder"
            --patch-module "org.graalvm.nativeimage.foreign=$foreign"); expected=true ;;
    esac
    printf '%s: ' "$combination"
    "$JAVA_HOME/bin/java" -Xmx512m -ea -XX:-UseJVMCICompiler \
        --module-path "$JAVA_HOME/lib/svm/builder" --add-modules org.graalvm.nativeimage.foreign \
        --add-opens org.graalvm.nativeimage.builder/com.oracle.svm.core=ALL-UNNAMED \
        --add-exports java.base/jdk.internal.misc=org.graalvm.nativeimage.builder \
        "${patches[@]}" -cp "$output_dir/classes" ProviderGateTest "$expected"
done
"$JAVA_HOME/bin/java" -Xmx512m --add-modules jdk.graal.compiler \
    --add-exports jdk.graal.compiler/jdk.graal.compiler.core.common=ALL-UNNAMED \
    --add-exports jdk.graal.compiler.options/jdk.graal.compiler.options=ALL-UNNAMED \
    --add-exports jdk.graal.compiler/jdk.graal.compiler.vector.replacements=ALL-UNNAMED \
    --add-exports jdk.graal.compiler/jdk.graal.compiler.vector.replacements.vectorapi=ALL-UNNAMED \
    --add-exports org.graalvm.collections/org.graalvm.collections=ALL-UNNAMED \
    "$recipe_dir/VectorOptionsTest.java"
