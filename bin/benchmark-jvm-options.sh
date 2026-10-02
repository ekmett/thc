# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Direct benchmark JVMs use the distribution's defaults, preserving inherited
# caller choices and opaque argument files. Source from Bash before launching.
THC_BENCH_JVM_OPTIONS=()
case "${JAVA_TOOL_OPTIONS-} ${JDK_JAVA_OPTIONS-} ${_JAVA_OPTIONS-}" in
  *jdk.graal.Vectorization=*|*jdk.graal.VectorizeLoops=*|*@*) ;;
  *) THC_BENCH_JVM_OPTIONS+=(-Djdk.graal.VectorizeLoops=false) ;;
esac
case "${JAVA_TOOL_OPTIONS-} ${JDK_JAVA_OPTIONS-} ${_JAVA_OPTIONS-}" in
  *UseCompactObjectHeaders*|*@*) ;;
  *) THC_BENCH_JVM_OPTIONS+=(-XX:+UseCompactObjectHeaders) ;;
esac
case "${JAVA_TOOL_OPTIONS-} ${JDK_JAVA_OPTIONS-} ${_JAVA_OPTIONS-}" in
  *UseCompressedOops*|*MaxRAM*|*MinRAM*|*InitialRAM*|*@*) ;;
  *) THC_BENCH_JVM_OPTIONS+=(-XX:+UseCompressedOops) ;;
esac
