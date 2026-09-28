#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# Experimental runtime-graph preparation; retain normal compiler checks.
# Not the supported pure-interpreter recipe or evidence of guest JIT/AOT.
set -euo pipefail
if (( $# < 1 || $# > 2 )); then
    echo 'Usage: JAVA_HOME=PINNED_JDK bash prepared-image.sh REPO [prepare-only|build]' >&2
    exit 2
fi
recipe_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$1" && pwd)
mode=${2:-build}
[[ "$mode" == prepare-only || "$mode" == build ]] || exit 2
: "${JAVA_HOME:?Select GraalVM 25.3.4.1}"
unset JAVA_TOOL_OPTIONS THC_BACKEND JAVA_OPTS THC_OPTS JDK_JAVA_OPTIONS GHC_PACKAGE_PATH GHC_ENVIRONMENT
[[ "$("$JAVA_HOME/bin/native-image" --version)" == *25.3.4.1* ]] || exit 2
cd "$repo_dir"
classpath=
for jar in build/install/thc/lib/*.jar; do
    case "${jar##*/}" in llvm-*|antlr4-*|truffle-nfi-*) continue ;; esac
    classpath="${classpath:+$classpath:}$repo_dir/$jar"
done
test -f build/install/thc/lib/thc-0.1-experiment.jar
probe_dir="$repo_dir/build/native-image/reproduction-probe"
inventory_dir="$repo_dir/build/native-image/reproduction-inventory"
mkdir -p "$probe_dir" "$inventory_dir"
"$JAVA_HOME/bin/javac" -d "$probe_dir" "$recipe_dir/ClassInitializationInventory.java"
for kind in stateless companions markers enums; do
    "$JAVA_HOME/bin/java" -Xmx512m -XX:-UseJVMCICompiler -cp "$probe_dir:$classpath" \
        ClassInitializationInventory build/install/thc/lib/thc-0.1-experiment.jar "$kind" \
        > "$inventory_dir/$kind.txt"
done
initialization=
while IFS= read -r prepared; do
    [[ -z "$prepared" || "$prepared" == \#* ]] && continue
    [[ "$prepared" =~ ^[a-zA-Z0-9_.$]+$ ]] || exit 2
    initialization="${initialization:+$initialization,}$prepared"
done < "$repo_dir/scripts/native-image/pure-initialization.txt"
for kind in stateless companions markers enums; do
    generated=$(<"$inventory_dir/$kind.txt")
    [[ -z "$generated" ]] && continue
    initialization="${initialization:+$initialization,}$generated"
done
while IFS= read -r prepared; do
    [[ -z "$prepared" || "$prepared" == \#* ]] && continue
    [[ "$prepared" =~ ^[a-zA-Z0-9_.$]+$ ]] || exit 2
    initialization="${initialization:+$initialization,}$prepared"
done < "$recipe_dir/prepared-initialization.txt"
# Native Image interprets an empty class/package entry as the whole hierarchy.
# Empty categories (for example Java-only companions) must not broaden policy.
[[ "$initialization" =~ ^[a-zA-Z0-9_.$]+(,[a-zA-Z0-9_.$]+)*$ ]] || {
    echo 'Initialization inventory must contain only nonempty class names' >&2
    exit 2
}
initialization_args="$inventory_dir/prepared-initialization.args"
printf '%s\n' "--initialize-at-build-time=$initialization" > "$initialization_args"
[[ "$mode" == prepare-only ]] && exit 0
builder_overlays=
if [[ -n "${THC_NATIVE_IMAGE_DEOPT_LOOP_STAMPS:-}" ]]; then
    [[ "$THC_NATIVE_IMAGE_DEOPT_LOOP_STAMPS" == 1 ]] || exit 2
    overlay_dir="$repo_dir/build/native-image/deopt-loop-stamps"
    bash "$recipe_dir/deopt-loop-stamps/prepare.sh" "$overlay_dir"
    bash "$recipe_dir/deopt-loop-stamps/check.sh" "$overlay_dir/checks" "$overlay_dir/thc-svm-deopt-loop-stamps.jar"
    builder_overlays="$overlay_dir/thc-svm-deopt-loop-stamps.jar"
fi
if [[ -n "${THC_NATIVE_IMAGE_RUNTIME_SNIPPETS:-}" ]]; then
    [[ "$THC_NATIVE_IMAGE_RUNTIME_SNIPPETS" == 1 ]] || exit 2
    overlay_dir="$repo_dir/build/native-image/runtime-snippet-providers"
    bash "$recipe_dir/runtime-snippet-providers/prepare.sh" "$overlay_dir"
    bash "$recipe_dir/runtime-snippet-providers/check.sh" "$overlay_dir/checks" "$overlay_dir/thc-svm-runtime-snippet-providers.jar"
    builder_overlays="${builder_overlays:+$builder_overlays:}$overlay_dir/thc-svm-runtime-snippet-providers.jar"
fi
if [[ -n "${THC_NATIVE_IMAGE_RUNTIME_SIMULATED_FOLDS:-}" ]]; then
    [[ "$THC_NATIVE_IMAGE_RUNTIME_SIMULATED_FOLDS" == 1 ]] || exit 2
    overlay_dir="$repo_dir/build/native-image/runtime-simulated-folds"
    bash "$recipe_dir/runtime-simulated-folds/prepare.sh" "$overlay_dir"
    bash "$recipe_dir/runtime-simulated-folds/check.sh" "$overlay_dir/checks" "$overlay_dir/thc-svm-runtime-simulated-folds.jar"
    builder_overlays="${builder_overlays:+$builder_overlays:}$overlay_dir/thc-svm-runtime-simulated-folds.jar"
fi
builder_patch=()
if [[ -n "$builder_overlays" ]]; then
    builder_patch=("-J--patch-module=org.graalvm.nativeimage.builder=$builder_overlays")
fi
diagnostics=()
if [[ -n "${THC_NATIVE_IMAGE_METHOD_FILTER:-}" ]]; then
    diagnostics=(-H:Dump=:2 -H:MethodFilter="$THC_NATIVE_IMAGE_METHOD_FILTER")
fi
exec "$JAVA_HOME/bin/native-image" -Ob -J-Xmx8g -J-XX:ActiveProcessorCount=2 --parallelism=2 \
    "${builder_patch[@]}" \
    --add-modules=jdk.incubator.vector \
    --enable-native-access=ALL-UNNAMED,org.graalvm.truffle \
    --add-exports=org.graalvm.truffle.runtime/com.oracle.truffle.runtime=ALL-UNNAMED \
    "@$initialization_args" \
    -H:+UnlockExperimentalVMOptions -H:+PrintCanonicalGraphStrings \
    -H:DumpPath="${THC_NATIVE_IMAGE_DUMP_PATH:-$repo_dir/build/native-image/graphs/reproduction}" \
    "${diagnostics[@]}" -H:-UnlockExperimentalVMOptions \
    -cp "$classpath" thc.Main "$repo_dir/build/native-image/thc-reproduced-prepared"
