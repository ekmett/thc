#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Build, verify and package the pinned GraalVM Community distribution."""

import argparse
import os
from pathlib import Path
from platform_paths import NATIVE_BUILD
import platform
import shutil
import subprocess
import sys
import tempfile

from platform_paths import jdk_home

ROOT = Path(__file__).resolve().parents[1]
MX = ROOT / 'upstream/mx-graal25/mx.py'
REFRESH_TARGETS = 'graalvm-jimage,java.base.jmod_modifier,GRAALVM_COMMUNITY_JAVA25'


def jvm_library():
    paths = {'Darwin': 'lib/server/libjvm.dylib', 'Linux': 'lib/server/libjvm.so',
             'Windows': 'bin/server/jvm.dll'}
    if platform.system() not in paths:
        raise SystemExit('Graal builds require macOS, Linux or native Windows Python.')
    return Path(paths[platform.system()])


def graal_environment(java_home=None):
    environment = dict(os.environ)
    base = Path(java_home or environment.get('JAM_GRAAL_BASE_JDK') or jdk_home()).resolve()
    launcher = 'java.exe' if platform.system() == 'Windows' else 'java'
    if not (base / 'bin' / launcher).is_file() or not (base / jvm_library()).is_file():
        raise SystemExit(f'Missing matching LabsJDK builder image: {base}')
    if not MX.is_file():
        raise SystemExit(f'Missing pinned mx checkout: {MX}')
    environment['JAVA_HOME'] = str(base)
    environment.setdefault('MX_CACHE_DIR', str(ROOT / '.toolchains/mx-cache'))
    if platform.system() == 'Darwin':
        target = environment.setdefault('MACOSX_DEPLOYMENT_TARGET', '15.5')
        # Native Image filters the environment; pass the target to its compiler too.
        environment['EXTRA_IMAGE_BUILDER_ARGUMENTS'] = (
            environment.get('EXTRA_IMAGE_BUILDER_ARGUMENTS', '') +
            ' -EMACOSX_DEPLOYMENT_TARGET=' + target).strip()
    if platform.system() == 'Windows':
        # Intermediate mx/jlink images inherit jvm.dll but not Jam's DLL, which
        # is deliberately packaged separately from java.base.jmod.
        environment['PATH'] = str(NATIVE_BUILD) + os.pathsep + environment.get('PATH', '')
    return environment


def verify_sources(environment):
    subprocess.run([sys.executable, str(ROOT / 'tools/prepare_graal.py'), '--check'],
                   cwd=ROOT, env=environment, check=True)


def run_mx(suite, arguments, environment, capture=False):
    python = environment.get('MX_PYTHON') or (
        'python' + environment['MX_PYTHON_VERSION'] if environment.get('MX_PYTHON_VERSION') else sys.executable)
    result = subprocess.run([python, '-u', str(MX), *arguments],
                            cwd=ROOT / 'upstream/graal25' / suite, env=environment, check=True,
                            stdout=subprocess.PIPE if capture else None, text=True)
    if capture:
        lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
        if len(lines) != 1:
            raise SystemExit(f'Expected one result from mx {arguments}: {result.stdout}')
        return lines[0]
    return None


def identical(first, second):
    """Compare actual bytes, including after a same-size incremental VM rebuild."""
    if not first.is_file() or not second.is_file():
        return False
    with first.open('rb') as left, second.open('rb') as right:
        while True:
            block = left.read(1024 * 1024)
            if block != right.read(1024 * 1024):
                return False
            if not block:
                return True


def archived_jdk(directory):
    launcher = 'java.exe' if platform.system() == 'Windows' else 'java'
    candidates = [path.parent.parent for path in directory.rglob('bin/' + launcher)
                  if path.is_file() and (path.parent.parent / jvm_library()).is_file()]
    if len(candidates) != 1:
        raise SystemExit(f'Expected one JDK in the GraalVM archive; found {len(candidates)}')
    return candidates[0]


def packaging_arguments(options):
    arguments = []
    if options.runtime_prefix is not None:
        arguments += ['--runtime-prefix', str(options.runtime_prefix.resolve())]
    licenses = list(options.runtime_license)
    compiler_license = options.compiler_runtime_license
    if platform.system() == 'Windows':
        tools = os.environ.get('JAM_CI_TOOLS')
        if tools:
            directory = Path(tools)
            if not licenses:
                licenses = [directory / 'Microsoft-Build-Tools-License.docx',
                            directory / 'Microsoft-Redistribution.md']
            if compiler_license is None:
                compiler_license = directory / 'compiler-rt-LICENSE.TXT'
        if compiler_license is None:
            raise SystemExit('Set JAM_COMPILER_RUNTIME_LICENSE or pass --compiler-runtime-license.')
    for source in licenses:
        if not source.is_file():
            raise SystemExit(f'Missing runtime license: {source}')
        arguments += ['--runtime-license', str(source.resolve())]
    if compiler_license is not None:
        if not compiler_license.is_file():
            raise SystemExit(f'Missing compiler-runtime license: {compiler_license}')
        arguments += ['--compiler-runtime-license', str(compiler_license.resolve())]
    return arguments


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, help='matching LabsJDK builder (or JAM_GRAAL_BASE_JDK)')
    parser.add_argument('--output', type=Path, default=os.environ.get('JAM_GRAAL_OUTPUT', ROOT / 'build/graalvm'))
    parser.add_argument('--runtime-prefix', type=Path, help='C++ runtime prefix; MSVC redist on Windows')
    parser.add_argument('--runtime-license', type=Path, action='append', default=[],
                        help='MSVC notice; repeat for multiple files (Windows CI defaults to JAM_CI_TOOLS notices)')
    parser.add_argument('--compiler-runtime-license', type=Path,
                        default=os.environ.get('JAM_COMPILER_RUNTIME_LICENSE'),
                        help='LLVM compiler-runtime notice (or JAM_COMPILER_RUNTIME_LICENSE)')
    options = parser.parse_args()
    environment = graal_environment(options.java_home)
    package_args = packaging_arguments(options)
    output = options.output.resolve()
    if output.exists():
        raise SystemExit(f'Preserving existing output: {output}')
    verify_sources(environment)
    jobs = environment.get('JAM_JOBS', '3')
    build = ['--max-cpus', jobs, '--env', 'ce', 'build']
    run_mx('vm', [*build, '--build-logs=silent'], environment)
    home = Path(run_mx('vm', ['--env', 'ce', 'graalvm-home'], environment, capture=True))
    if not home.is_absolute():
        home = ROOT / 'upstream/graal25/vm' / home
    base_library = Path(environment['JAVA_HOME']) / jvm_library()
    # mx can miss a HotSpot-only rebuild when the base module image is unchanged.
    if not identical(base_library, home / jvm_library()):
        run_mx('vm', [*build, '--only', REFRESH_TARGETS, '-f', '--build-logs=silent'], environment)
    distribution = run_mx('vm', ['--env', 'ce', 'graalvm-dist-name'], environment, capture=True)
    archive = Path(run_mx('vm', ['--env', 'ce', 'paths', distribution], environment, capture=True))
    if not archive.is_absolute():
        archive = ROOT / 'upstream/graal25/vm' / archive
    # Use the canonical archive: mx performs distribution processing while
    # archiving. Windows uses ZIP; TAR extraction preserves POSIX modes/links.
    with tempfile.TemporaryDirectory(prefix='jam-graal-package-') as temporary:
        shutil.unpack_archive(str(archive), temporary)
        image = archived_jdk(Path(temporary))
        if not identical(base_library, image / jvm_library()):
            raise SystemExit('GraalVM archive contains an outdated HotSpot library')
        # The Windows archive has not acquired its app-local Jam DLLs yet. Use
        # the matching builder's Java tools and JNI headers for the guest API.
        bridge_home = Path(environment['JAVA_HOME']) if platform.system() == 'Windows' else image
        subprocess.run([sys.executable, str(ROOT / 'tools/build_bridge.py'), '--java-home', str(bridge_home)],
                       cwd=ROOT, env=environment, check=True)
        subprocess.run([sys.executable, str(ROOT / 'tools/package_jdk.py'), '--java-home', str(image),
                        '--output', str(output), *package_args], cwd=ROOT, env=environment, check=True)


if __name__ == '__main__':
    main()
