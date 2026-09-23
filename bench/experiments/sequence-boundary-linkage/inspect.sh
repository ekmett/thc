#!/usr/bin/env bash
# Read-only pinned VM/Truffle inspection; never executes a guest workload.
set -euo pipefail
if [[ $# != 2 ]]; then
  echo "Usage: JAVA_HOME=/pinned/jdk bash $0 /frozen/runtime/lib /output/directory" >&2
  exit 2
fi
: "${JAVA_HOME:?Set JAVA_HOME to the pinned GraalVM distribution}"
runtime_lib=$(cd "$1" && pwd)
inspection_output=$2
experiment_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

check_hash() {
  local expected=$1 path=$2 actual
  actual=$(sha256sum "$path")
  actual=${actual%% *}
  if [[ "$actual" != "$expected" ]]; then
    echo "Pinned artifact mismatch: $path" >&2
    exit 1
  fi
}

# Native address ranges below are meaningful only for this exact binary.
check_hash 4d61a40cebc89f0d7dcfb276d7567395e5c158e3d73da858e1350c28afb6e743 "$JAVA_HOME/lib/server/libjvm.so"
check_hash 42392fa5e3c02fa4d396ecf21d552896c23a33db7d70049efb0efaf95c6e6c1a "$JAVA_HOME/lib/src.zip"
check_hash d74ff0b8eb9a5fec4a571f78bc21750ee41dfd454877b8cce8b33641efd90ae3 "$runtime_lib/truffle-runtime-25.3.4.1.jar"
check_hash f9efa18b1cb6c4e39862f5ac83a88ee4c9e31252c52c2ee5192c2a707cd9b27f "$runtime_lib/truffle-api-25.3.4.1.jar"

mkdir -p "$inspection_output/classes"
inspection_output=$(cd "$inspection_output" && pwd)
for class in OptimizedCallTarget hotspot.HotSpotTruffleRuntime hotspot.HotSpotOptimizedCallTarget; do
  "$JAVA_HOME/bin/javap" -classpath "$runtime_lib/*" -p -c \
    "com.oracle.truffle.runtime.$class" > "$inspection_output/$class.javap"
done
"$JAVA_HOME/bin/javap" --module jdk.internal.vm.ci -p -c \
  jdk.vm.ci.hotspot.HotSpotNmethod jdk.vm.ci.code.InstalledCode \
  > "$inspection_output/installed-code.javap"
unzip -p "$JAVA_HOME/lib/src.zip" \
  jdk.graal.compiler/jdk/graal/compiler/truffle/hotspot/HotSpotTruffleCompilerImpl.java | \
  sed -n '350,390p' > "$inspection_output/compiler-installation.txt"
objdump -d -C --start-address=0xf69490 --stop-address=0xf69fe0 \
  "$JAVA_HOME/lib/server/libjvm.so" > "$inspection_output/adapter-generator.txt"
objdump -d -C --start-address=0xf537c0 --stop-address=0xf53a50 \
  "$JAVA_HOME/lib/server/libjvm.so" > "$inspection_output/caller-fixup.txt"
objdump -d -C --start-address=0x32f4ac --stop-address=0x32f4cd \
  "$JAVA_HOME/lib/server/libjvm.so" > "$inspection_output/vmstruct-initializer.txt"
readelf -rW "$JAVA_HOME/lib/server/libjvm.so" | \
  rg '000000000172c4(00|08|10|30|38|40|60|68|70)|00000000016422d8' \
  > "$inspection_output/vmstruct-relocations.txt"
objdump -s --start-address=0x12f120e --stop-address=0x12f1220 \
  "$JAVA_HOME/lib/server/libjvm.so" > "$inspection_output/vmstruct-method-name.txt"
objdump -s --start-address=0x1322a80 --stop-address=0x1322ab0 \
  "$JAVA_HOME/lib/server/libjvm.so" > "$inspection_output/vmstruct-field-names.txt"

"$JAVA_HOME/bin/javac" --add-modules jdk.internal.vm.ci \
  --add-exports jdk.internal.vm.ci/jdk.vm.ci.hotspot=ALL-UNNAMED \
  -classpath "$runtime_lib/*" -d "$inspection_output/classes" \
  "$experiment_dir/VmMetadata.java"
"$JAVA_HOME/bin/java" --add-modules jdk.internal.vm.ci \
  --add-exports jdk.internal.vm.ci/jdk.vm.ci.hotspot=ALL-UNNAMED \
  -classpath "$inspection_output/classes:$runtime_lib/*" VmMetadata \
  > "$inspection_output/vm-metadata.txt"
echo "Inspection complete: $inspection_output (no guest workload executed)"
