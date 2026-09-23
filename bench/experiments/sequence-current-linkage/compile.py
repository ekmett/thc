#!/usr/bin/env python3
"""Compile only the build-only checker with the installed project's Kotlin version."""
import argparse
import os
from pathlib import Path
import subprocess


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("output", type=Path)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[3]
cache = Path(os.environ["THC_GRADLE_USER_HOME"]) / "caches/modules-2/files-2.1"
compiler = []
for name, version in [
    ("org.jetbrains.kotlin/kotlin-compiler-embeddable", "2.4.20"),
    ("org.jetbrains.kotlin/kotlin-stdlib", "2.4.20"),
    ("org.jetbrains.kotlin/kotlin-reflect", "2.4.20"),
    ("org.jetbrains.kotlin/kotlin-script-runtime", "2.4.20"),
    ("org.jetbrains.kotlin/kotlin-daemon-embeddable", "2.4.20"),
    ("org.jetbrains/annotations", "13.0"),
]:
    matches = list((cache / name / version).rglob("*.jar"))
    assert len(matches) == 1, (name, matches)
    compiler.extend(matches)
compiler.extend((cache / "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm").rglob("*.jar"))
libs = sorted((repo / "build/install/thc/lib").glob("*.jar"))
assert libs, "Run installDist first"
output = args.output.resolve()
subprocess.run([
    str(Path(os.environ["JAVA_HOME"]) / "bin/java"), "--enable-native-access=ALL-UNNAMED",
    "-cp", os.pathsep.join(map(str, compiler)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
    "-no-stdlib", "-no-reflect", "-jvm-target", "25", "-Xno-param-assertions",
    "-Xno-call-assertions", "-Xno-receiver-assertions", "-classpath",
    os.pathsep.join(map(str, libs)), "-d", str(output / "lifetime-classes"),
    str(output / "lifetime-src/LibraryCheck.kt"),
], check=True)
