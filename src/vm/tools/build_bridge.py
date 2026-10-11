#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

"""Build the guest API and JNI shim without building jam or HotSpot."""

import argparse
import os
from pathlib import Path
import platform
import shlex
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def build(java_home):
    jdk = Path(java_home).resolve()
    system = platform.system()
    if system not in ("Darwin", "Linux", "Windows"):
        raise SystemExit("The bridge supports macOS, Linux and Windows builds")
    windows = system == "Windows"
    include = {"Darwin": "darwin", "Linux": "linux", "Windows": "win32"}[system]
    library = {"Darwin": "libthc_bridge.dylib", "Linux": "libthc_bridge.so", "Windows": "thc_bridge.dll"}[system]
    suffix = ".exe" if windows else ""
    output = ROOT / "build/bridge"
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="bridge-", dir=output.parent) as temporary:
        stage = Path(temporary)
        for directory in ("classes", "include", "lib", "api"):
            (stage / directory).mkdir()
        sources = sorted((ROOT / "src/bridge/java").rglob("*.java"))
        subprocess.run([str(jdk / "bin" / ("javac" + suffix)), "--release", "25", "-Xlint:all", "-Werror",
                        "-h", str(stage / "include"), "-d", str(stage / "classes"), *map(str, sources)], check=True)
        compiler = shlex.split(os.environ.get("CC", "clang-cl" if windows else "cc"))
        if windows:
            flags = ["/nologo", "/std:c11", "/O2", "/W4", "/WX", "/LD",
                     "/I" + str(jdk / "include"), "/I" + str(jdk / "include" / include),
                     "/I" + str(stage / "include"), str(ROOT / "src/bridge/thc_bridge.c"),
                     "/Fe" + str(stage / "lib" / library), "/link", "/IMPLIB:" + str(stage / "thc_bridge.lib")]
        else:
            flags = ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
                        "-fvisibility=hidden", *(["-dynamiclib", "-mmacosx-version-min=" +
                           os.environ.get("MACOSX_DEPLOYMENT_TARGET", "15.5")]
                          if system == "Darwin" else ["-shared", "-fPIC"]),
                        "-I" + str(jdk / "include"),
                        "-I" + str(jdk / "include" / include), "-I" + str(stage / "include"),
                        str(ROOT / "src/bridge/thc_bridge.c"), "-o", str(stage / "lib" / library),
                        *([] if system == "Darwin" else ["-ldl"])]
        subprocess.run([*compiler, *flags], cwd=stage, check=True)
        manifest = stage / "MANIFEST.MF"
        manifest.write_text("Manifest-Version: 1.0\nAutomatic-Module-Name: thc.vm\n\n")
        subprocess.run([str(jdk / "bin" / ("jar" + suffix)), "--create", "--file", str(stage / "thc-vm.jar"),
                        "--manifest", str(manifest), "-C", str(stage / "classes"), "."], check=True)
        subprocess.run([str(jdk / "bin" / ("jar" + suffix)), "--create", "--file", str(stage / "thc-vm-sources.jar"),
                        "-C", str(ROOT / "src/bridge/java"), "."], check=True)
        subprocess.run([str(jdk / "bin" / ("javadoc" + suffix)), "-quiet", "-Xdoclint:all", "-Werror",
                        "-d", str(stage / "api"), *map(str, sources)], check=True)
        # Replace generated outputs only, after every tool has succeeded.
        for name in ("thc-vm.jar", "thc-vm-sources.jar", "lib", "include", "api"):
            destination = output / name
            if destination.is_dir():
                shutil.rmtree(destination)
            elif destination.exists():
                destination.unlink()
            shutil.move(str(stage / name), destination)
        for obsolete in ("LICENSE-BSD-2-Clause.md", "LICENSE-APACHE.md", "LICENSE.spdx"):
            (output / obsolete).unlink(missing_ok=True)
        for name in ("LICENSE.md", "NOTICE.md"):
            shutil.copyfile(ROOT / name, output / name)
    print(f"Built guest API, JNI library, headers and Javadoc: {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", default=os.environ.get("JAM_BOOT_JDK", os.environ.get("JAVA_HOME")))
    options = parser.parse_args()
    if not options.java_home:
        parser.error("set JAM_BOOT_JDK/JAVA_HOME or pass --java-home (JDK 25)")
    build(options.java_home)
