#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -euo pipefail
[[ $# == 2 ]] || { echo 'Usage: JAVA_HOME=PINNED_JDK bash check.sh OUTPUT_DIR FOREIGN_OVERLAY' >&2; exit 2; }
: "${JAVA_HOME:?Select GraalVM 25.3.4.1}"
recipe_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
mkdir -p "$1/classes"
output_dir=$(cd -- "$1" && pwd)
exports=(--add-exports java.base/jdk.internal.foreign=ALL-UNNAMED
    --add-exports java.base/jdk.internal.vm.vector=ALL-UNNAMED)
"$JAVA_HOME/bin/javac" -J-Xmx512m --add-modules jdk.incubator.vector "${exports[@]}" \
    -d "$output_dir/classes" "$recipe_dir/VectorAccessTest.java"
# The interpreter deliberately forces the supplied Java fallbacks, including
# suspended and throwing callbacks. Provider intrinsic options are not changed.
"$JAVA_HOME/bin/java" -Xmx512m -Xint -ea --add-modules jdk.incubator.vector,org.graalvm.nativeimage.foreign \
    --module-path "$JAVA_HOME/lib/svm/builder" \
    --patch-module "org.graalvm.nativeimage.foreign=$2" \
    --add-exports org.graalvm.nativeimage.foreign/com.oracle.svm.core.foreign=ALL-UNNAMED \
    --add-exports java.base/jdk.internal.foreign=org.graalvm.nativeimage.foreign \
    --add-exports java.base/jdk.internal.misc=org.graalvm.nativeimage.foreign \
    --add-exports java.base/jdk.internal.vm.vector=org.graalvm.nativeimage.foreign \
    --enable-native-access=ALL-UNNAMED "${exports[@]}" \
    -cp "$output_dir/classes" VectorAccessTest
