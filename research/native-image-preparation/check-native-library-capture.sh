#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -euo pipefail
[[ $# == 1 ]] || { echo 'Usage: JAVA_HOME=PINNED_JDK bash check-native-library-capture.sh FRESH_OUTPUT_DIR' >&2; exit 2; }
: "${JAVA_HOME:?Select GraalVM 25.3.4.1}"
recipe_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$recipe_dir/../.." && pwd)
mkdir -p "$1/classes"
output_dir=$(cd -- "$1" && pwd)
"$JAVA_HOME/bin/javac" -J-Xmx512m -proc:none -d "$output_dir/classes" \
    "$repo_dir/src/main/java/thc/Json.java" "$recipe_dir/NativeLibraryCapture.java" "$recipe_dir/NativeLibraryCaptureTest.java"
"$JAVA_HOME/bin/java" -Xmx512m -XX:-UseJVMCICompiler -cp "$output_dir/classes" NativeLibraryCaptureTest "$output_dir"
