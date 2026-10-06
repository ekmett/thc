#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# Experimental runtime-graph and selected-code cache preparation.
# Retain normal compiler checks; separate from the pure-interpreter recipe.
set -euo pipefail
if (( $# < 1 || $# > 3 )); then
    echo 'Usage: JAVA_HOME=PINNED_JDK bash prepared-image.sh REPO [prepare-only|build|cache|cache-prepare-only|executable|executable-prepare-only [BUILD_DIR]]' >&2
    exit 2
fi
recipe_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$1" && pwd)
mode=${2:-build}
case "$mode" in prepare-only|build|cache|cache-prepare-only|executable|executable-prepare-only) ;; *) exit 2 ;; esac
# Automatic vectorization is a process-wide compiler policy, independent of
# explicit Vector API intrinsics. Apply it to image code and the runtime JIT.
case "${THC_NATIVE_IMAGE_AUTOVECTORIZE:-false}" in
    false) vector_options=(-H:-Vectorization -R:-Vectorization) ;;
    true) vector_options=(-H:+Vectorization -R:+Vectorization) ;;
    *) echo 'THC_NATIVE_IMAGE_AUTOVECTORIZE must be true or false' >&2; exit 2 ;;
esac
vector_profile=${THC_NATIVE_IMAGE_VECTOR_PROFILE:-intrinsics}
case "$vector_profile" in
    intrinsics)
        # Vectorization=false also clears these two keys in the pinned compiler.
        # Restore direct Vector API lowering without enabling automatic loops.
        vector_options+=(-H:+OptimizeVectorAPI -H:+TargetVectorLowering
            -R:+OptimizeVectorAPI -R:+TargetVectorLowering -H:+SharedArenaSupport) ;;
    resource-copy)
        # This pinned JDK cannot combine Vector API intrinsics with shared
        # arenas. Keep Vector API semantics using its array fallback and THC's
        # scalar bulk-copy memory boundary, without changing resource lifetime.
        vector_options+=(-H:-VectorAPISupport -H:+SharedArenaSupport -Dthc.nativeImage.resourceCopies=true) ;;
    *) echo 'THC_NATIVE_IMAGE_VECTOR_PROFILE must be intrinsics or resource-copy' >&2; exit 2 ;;
esac
: "${JAVA_HOME:?Select GraalVM 25.3.4.1}"
unset JAVA_TOOL_OPTIONS THC_BACKEND JAVA_OPTS THC_OPTS JDK_JAVA_OPTIONS GHC_PACKAGE_PATH GHC_ENVIRONMENT
[[ "$("$JAVA_HOME/bin/native-image" --version)" == *25.3.4.1* ]] || exit 2
# Resolve caller-relative output paths before entering the read-only input tree.
# Each target owns its build directory; repeated builds may reuse its contents.
build_dir=${3:-$repo_dir/build/native-image}
mkdir -p -- "$build_dir"
build_dir=$(cd -- "$build_dir" && pwd)
cd "$repo_dir"
classpath=
for jar in build/install/thc/lib/*.jar; do
    # Cached package FFI uses the distribution's existing Sulong/NFI providers
    # at load time; preparation itself does not open or execute a guest library.
    if [[ "$mode" != cache* && "$mode" != executable* ]]; then
        case "${jar##*/}" in llvm-*|thc-llvm-language-*|antlr4-*|truffle-nfi-*) continue ;; esac
    fi
    classpath="${classpath:+$classpath:}$repo_dir/$jar"
done
test -f build/install/thc/lib/thc-0.1-experiment.jar
probe_dir="$build_dir/reproduction-probe"
inventory_dir="$build_dir/reproduction-inventory"
mkdir -p "$probe_dir" "$inventory_dir"
"$JAVA_HOME/bin/javac" -d "$probe_dir" "$recipe_dir/ClassInitializationInventory.java"
for kind in stateless markers enums; do
    "$JAVA_HOME/bin/java" -Xmx512m -XX:-UseJVMCICompiler -cp "$probe_dir:$classpath" \
        ClassInitializationInventory build/install/thc/lib/thc-0.1-experiment.jar "$kind" \
        > "$inventory_dir/$kind.txt"
done
initialization=
while IFS= read -r prepared; do
    [[ -z "$prepared" || "$prepared" == \#* ]] && continue
    [[ "$prepared" =~ ^[a-zA-Z0-9_.$]+$ ]] || exit 2
    initialization="${initialization:+$initialization,}$prepared"
done < "$repo_dir/bin/native-image/pure-initialization.txt"
for kind in stateless markers enums; do
    generated=$(<"$inventory_dir/$kind.txt")
    [[ -z "$generated" ]] && continue
    initialization="${initialization:+$initialization,}$generated"
done
while IFS= read -r prepared; do
    [[ -z "$prepared" || "$prepared" == \#* ]] && continue
    [[ "$prepared" =~ ^[a-zA-Z0-9_.$]+$ ]] || exit 2
    initialization="${initialization:+$initialization,}$prepared"
done < "$recipe_dir/prepared-initialization.txt"
cache_options=()
main_class=thc.Main
image_path="$build_dir/thc-reproduced-prepared"
if [[ "$mode" == cache* || "$mode" == executable* ]]; then
    # This configuration is qualified only for the pinned Linux AMD64 provider.
    [[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || {
        echo 'Experimental cached code currently requires Linux AMD64' >&2; exit 2;
    }
    while IFS= read -r prepared; do
        [[ -z "$prepared" || "$prepared" == \#* ]] && continue
        [[ "$prepared" =~ ^[a-zA-Z0-9_.$]+$ ]] || exit 2
        initialization="${initialization:+$initialization,}$prepared"
    done < "$recipe_dir/cache-initialization.txt"
    # CPUFeatures ADDS to -march on this pinned toolchain. HT is a topology bit;
    # normalize it without dropping any instruction feature or installer check.
    cache_options=(-march=x86-64-v3 -H:CPUFeatures=HT -H:+AuxiliaryEngineCache)
    main_class=thc.NativeCache
    image_path="$build_dir/thc-native-cache"
fi
[[ "$vector_profile" != resource-copy ]] || image_path+=-resource-copy
executable_options=()
builder_heap=8g
if [[ "$mode" == executable* ]]; then
    # Capture selected Core, then lower synchronous AST during Truffle preinitialization.
    # Runtime uses the saved factory and opens captured native providers on demand.
    : "${THC_NATIVE_IMAGE_EXECUTABLE_CONFIG:?Supply a fixed Main executable argument JSON array}"
    : "${THC_NATIVE_IMAGE_EXECUTABLE_NAME:?Supply the ELF output basename}"
    [[ "$THC_NATIVE_IMAGE_EXECUTABLE_NAME" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]*$ ]] || exit 2
    test -f "$THC_NATIVE_IMAGE_EXECUTABLE_CONFIG"
    binding_dir=$(mktemp -d "$build_dir/executable.XXXXXX")
    cp -- "$THC_NATIVE_IMAGE_EXECUTABLE_CONFIG" "$binding_dir/thc-native-executable.json"
    "$JAVA_HOME/bin/jar" --create --file "$binding_dir/binding.jar" -C "$binding_dir" thc-native-executable.json
    classpath="$classpath:$binding_dir/binding.jar"
    # Dedicated guest executables must not consume GHC's -D/-X options as VM
    # arguments. Keep VM bounds in the image, separate from opaque guest argv.
    # Original GHC owns HUP/INT/QUIT/TERM in this standalone process. The runtime
    # checks the effective option; do not substitute a trusted-looking property.
    executable_options=(-J-Dthc.nativeImage.executable=true -H:-ParseRuntimeOptions -R:-EnableSignalHandling
        -H:MaxHeapSize=17179869184 -H:ActiveProcessorCount=2)
    # The full ordinary-loader image needs room for frame metadata after codegen.
    builder_heap=16g
    cache_options=(-march=x86-64-v3 -H:CPUFeatures=HT)
    main_class=thc.NativeExecutable
    image_path="$build_dir/$THC_NATIVE_IMAGE_EXECUTABLE_NAME"
fi
# Builder memory is separate from the produced executable's runtime limits.
# Keep overrides within the two resource budgets qualified by this recipe.
builder_heap=${THC_NATIVE_IMAGE_BUILDER_HEAP:-$builder_heap}
case "$builder_heap" in
    8g|16g) ;;
    *) echo 'THC_NATIVE_IMAGE_BUILDER_HEAP must be 8g or 16g' >&2; exit 2 ;;
esac
printf '%s\n' "-J-Xmx$builder_heap" > "$inventory_dir/builder-heap.args"
# Switch tables depend only on enums ALREADY selected above. This final category
# proves the complete synthetic initializer; it never adds an enum dependency.
# Keep the exact inventory out of a single OS argument (Linux caps one argument
# independently of the total command-line size).
approved_initialization_args="$inventory_dir/approved-initialization.args"
printf '%s\n%s\n%s\n"%s"\n' ClassInitializationInventory build/install/thc/lib/thc-0.1-experiment.jar switches \
    "$initialization" > "$approved_initialization_args"
"$JAVA_HOME/bin/java" -Xmx512m -XX:-UseJVMCICompiler -cp "$probe_dir:$classpath" \
    "@$approved_initialization_args" \
    > "$inventory_dir/switches.txt"
generated=$(<"$inventory_dir/switches.txt")
[[ -z "$generated" ]] || initialization="${initialization:+$initialization,}$generated"
# Native Image interprets an empty class/package entry as the whole hierarchy.
# Empty generated categories must not broaden policy.
[[ "$initialization" =~ ^[a-zA-Z0-9_.$]+(,[a-zA-Z0-9_.$]+)*$ ]] || {
    echo 'Initialization inventory must contain only nonempty class names' >&2
    exit 2
}
initialization_args="$inventory_dir/prepared-initialization.args"
printf '%s\n' "--initialize-at-build-time=$initialization" > "$initialization_args"
vector_args="$inventory_dir/vector-profile.args"
: > "$vector_args"
if (( ${#vector_options[@]} )); then printf '%s\n' "${vector_options[@]}" > "$vector_args"; fi
foreign_args="$inventory_dir/foreign.args"
: > "$foreign_args"
foreign_configuration=
if [[ -n "${THC_NATIVE_IMAGE_PROCESS_IDENTITY:-}" ]]; then
    [[ "$THC_NATIVE_IMAGE_PROCESS_IDENTITY" == 1 ]] || exit 2
    test -f "$recipe_dir/process-identity/reachability-metadata.json"
    foreign_configuration="$recipe_dir/process-identity"
fi
if [[ "$mode" == executable* ]]; then
    foreign_configuration="${foreign_configuration:+$foreign_configuration,}$recipe_dir/native-io"
fi
if [[ -n "$foreign_configuration" ]]; then
    printf '"-H:ConfigurationFileDirectories=%s"\n' "$foreign_configuration" > "$foreign_args"
fi
[[ "$mode" == *prepare-only ]] && exit 0
# CLI eager initialization can create LanguageCache entries before Truffle's
# optional resource registry is populated. Apply the SAME finite class policy
# during setup, after all features' registration hooks have completed.
"$JAVA_HOME/bin/javac" -cp "$classpath" -d "$probe_dir" "$recipe_dir/PreparedInitializationFeature.java" "$recipe_dir/NativeLibraryCapture.java"
classpath="$probe_dir:$classpath"
builder_overlays=
foreign_patch=()
if [[ "$vector_profile" == intrinsics ]]; then
    overlay_dir="$build_dir/shared-arena-vector"
    bash "$repo_dir/nih/native-image/shared-arena-vector/prepare.sh" "$overlay_dir"
    bash "$recipe_dir/shared-arena-vector/check.sh" "$overlay_dir/checks" "$overlay_dir/thc-svm-shared-arena-vector-foreign.jar"
    bash "$recipe_dir/shared-arena-vector/check-provider.sh" "$overlay_dir/provider-checks" "$overlay_dir"
    builder_overlays="$overlay_dir/thc-svm-shared-arena-vector-builder.jar"
    foreign_patch=("-J--patch-module=org.graalvm.nativeimage.foreign=$overlay_dir/thc-svm-shared-arena-vector-foreign.jar"
        -J--add-exports=java.base/jdk.internal.foreign=org.graalvm.nativeimage.foreign
        -J--add-exports=java.base/jdk.internal.misc=org.graalvm.nativeimage.foreign
        -J--add-exports=java.base/jdk.internal.vm.vector=org.graalvm.nativeimage.foreign)
fi
if [[ -n "${THC_NATIVE_IMAGE_DEOPT_LOOP_STAMPS:-}" ]]; then
    [[ "$THC_NATIVE_IMAGE_DEOPT_LOOP_STAMPS" == 1 ]] || exit 2
    overlay_dir="$build_dir/deopt-loop-stamps"
    bash "$recipe_dir/deopt-loop-stamps/prepare.sh" "$overlay_dir"
    bash "$recipe_dir/deopt-loop-stamps/check.sh" "$overlay_dir/checks" "$overlay_dir/thc-svm-deopt-loop-stamps.jar"
    builder_overlays="${builder_overlays:+$builder_overlays:}$overlay_dir/thc-svm-deopt-loop-stamps.jar"
fi
if [[ -n "${THC_NATIVE_IMAGE_RUNTIME_SNIPPETS:-}" ]]; then
    [[ "$THC_NATIVE_IMAGE_RUNTIME_SNIPPETS" == 1 ]] || exit 2
    overlay_dir="$build_dir/runtime-snippet-providers"
    bash "$recipe_dir/runtime-snippet-providers/prepare.sh" "$overlay_dir"
    bash "$recipe_dir/runtime-snippet-providers/check.sh" "$overlay_dir/checks" "$overlay_dir/thc-svm-runtime-snippet-providers.jar"
    builder_overlays="${builder_overlays:+$builder_overlays:}$overlay_dir/thc-svm-runtime-snippet-providers.jar"
fi
if [[ -n "${THC_NATIVE_IMAGE_RUNTIME_SIMULATED_FOLDS:-}" ]]; then
    [[ "$THC_NATIVE_IMAGE_RUNTIME_SIMULATED_FOLDS" == 1 ]] || exit 2
    overlay_dir="$build_dir/runtime-simulated-folds"
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
exec "$JAVA_HOME/bin/native-image" -Ob "-J-Xmx$builder_heap" -J-XX:ActiveProcessorCount=2 --parallelism=2 \
    "${builder_patch[@]}" "${foreign_patch[@]}" \
    --add-modules=jdk.incubator.vector \
    --enable-native-access=ALL-UNNAMED,org.graalvm.truffle \
    --add-exports=org.graalvm.truffle.runtime/com.oracle.truffle.runtime=ALL-UNNAMED \
    --features=PreparedInitializationFeature "-J-Dthc.nativeImage.initialization=$initialization_args" "@$foreign_args" \
    -H:+UnlockExperimentalVMOptions "@$vector_args" "${cache_options[@]}" "${executable_options[@]}" -H:+PrintCanonicalGraphStrings \
    -H:DumpPath="${THC_NATIVE_IMAGE_DUMP_PATH:-$build_dir/graphs/reproduction}" \
    "${diagnostics[@]}" -H:-UnlockExperimentalVMOptions \
    -cp "$classpath" "$main_class" "$image_path"
