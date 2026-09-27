#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -euo pipefail
[[ $# == 2 ]] || { echo 'Usage: JAVA_HOME=PINNED_JDK bash check.sh OUTPUT_DIR OVERLAY_JAR' >&2; exit 2; }
: "${JAVA_HOME:?Select GraalVM 25.3.4.1}"
recipe_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
mkdir -p "$1/classes"
output_dir=$(cd -- "$1" && pwd)
exports=()
# Only this standalone compiler-API test needs access to internal compiler APIs.
for module in jdk.graal.compiler jdk.graal.compiler.options jdk.internal.vm.ci com.oracle.graal.graal_enterprise; do
    while read -r package; do
        exports+=(--add-exports "$module/$package=ALL-UNNAMED")
    done < <("$JAVA_HOME/bin/java" --describe-module "$module" |
        awk '$1 == "contains" || $1 == "exports" {print $2} $1 == "qualified" && $2 == "exports" {print $3}')
done
modules=--add-modules=jdk.graal.compiler,jdk.internal.vm.ci,com.oracle.graal.graal_enterprise
classpath="$JAVA_HOME/lib/svm/builder/*"
"$JAVA_HOME/bin/javac" -J-Xmx1g -proc:none "$modules" "${exports[@]}" \
    -cp "$classpath" -d "$output_dir/classes" "$recipe_dir/SnippetProvidersTest.java"
"$JAVA_HOME/bin/java" -Xmx1g -ea -esa -XX:-UseJVMCICompiler "$modules" "${exports[@]}" \
    --add-opens jdk.graal.compiler/jdk.graal.compiler.replacements=ALL-UNNAMED \
    -cp "$output_dir/classes:$classpath" SnippetProvidersTest "$2"
