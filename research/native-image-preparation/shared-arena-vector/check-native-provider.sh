#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -euo pipefail
[[ $# == 3 ]] || { echo 'Usage: JAVA_HOME=PINNED_JDK bash check-native-provider.sh FRESH_OUTPUT_DIR PROBE_DIR PROVIDER_CHECK_DIR' >&2; exit 2; }
: "${JAVA_HOME:?Select GraalVM 25.3.4.1}"
test ! -e "$1"
mkdir -p "$1"
output_dir=$(cd -- "$1" && pwd)
probe_dir=$(cd -- "$2" && pwd)
checks_dir=$(cd -- "$3" && pwd)
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
test "$(< "$probe_dir/build.status")" = 0
test "$(< "$probe_dir/run.status")" = 0
for component in wrappers-only phase-only; do
    mapfile -t args < "$probe_dir/build.args"
    for index in "${!args[@]}"; do
        if [[ ${args[index]} == -J--patch-module=org.graalvm.nativeimage.foreign=* ]]; then
            args[index]="-J--patch-module=org.graalvm.nativeimage.foreign=$checks_dir/$component.jar"
        elif [[ ${args[index]} == -H:DumpPath=* ]]; then
            args[index]="-H:DumpPath=$output_dir/$component-graphs"
        fi
    done
    args[${#args[@]}-1]="$output_dir/$component-image"
    printf '%s\n' "${args[@]}" > "$output_dir/$component.args"
    sha256sum "$checks_dir/$component.jar" > "$output_dir/$component.sha256"
    set +e
    timeout --kill-after=10s 30s "$JAVA_HOME/bin/native-image" "${args[@]}" > "$output_dir/$component.log" 2>&1
    status=$?
    set -e
    printf '%s\n' "$status" > "$output_dir/$component.status"
    case "$component" in
        wrappers-only) missing='com.oracle.svm.hosted.foreign.ForeignFunctionsFeature$SharedArenaSupportImpl' ;;
        phase-only) missing=com.oracle.svm.core.foreign.Target_jdk_internal_misc_ScopedMemoryAccess ;;
    esac
    if (( status != 1 )) ||
            ! rg -Fq 'Support for Arena.ofShared (which is part of the FFM API) is not available with Vector API support.' "$output_dir/$component.log" ||
            ! rg -Fq "Shared vector provider class digest mismatch: $missing" "$output_dir/$component.log"; then
        tail -n 40 "$output_dir/$component.log" | cut -c1-240
        echo "Expected fail-closed Native Image guard for $component, got $status" >&2
        exit 1
    fi
    test ! -e "$output_dir/$component-image"
    printf 'PASS real Native Image guard rejects %s\n' "$component"
done
