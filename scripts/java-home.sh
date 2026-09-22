# Source after locating the repository; do not guess another checkout's JDK.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    echo 'Set JAVA_HOME to a GraalVM 25.3.4.1 installation (JDK 25), with bin/java and bin/javac.' >&2
    echo 'For example: export JAVA_HOME=/path/to/graalvm-jdk-25' >&2
    exit 1
fi
export JAVA_HOME
