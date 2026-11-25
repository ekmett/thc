#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Build, verify and package the pinned GraalVM Community distribution."""

import argparse
import json
import math
import re
import runpy
import signal
import os
from pathlib import Path
from platform_paths import NATIVE_BUILD
import platform
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

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


def diagnostic_url(url):
    """Omit credentials, query parameters and fragments from transport receipts."""
    parts = urllib.parse.urlsplit(url)
    host = parts.hostname or ''
    if ':' in host:
        host = '[' + host + ']'
    if parts.port:
        host += ':' + str(parts.port)
    return urllib.parse.urlunsplit((parts.scheme, host, parts.path, '', ''))


class AcquisitionResponse(urllib.request.BaseHandler):
    # Observe each response before urllib follows redirects or raises HTTPError.
    handler_order = 499

    def http_response(self, request, response):
        receipt = {'url': diagnostic_url(request.full_url), 'status': response.code}
        for name in ('Server', 'X-Request-ID', 'X-Amz-Request-ID', 'CF-Ray'):
            value = response.headers.get(name)
            if value:
                receipt[name.lower()] = value[:512]
        location = response.headers.get('Location')
        if location:
            try:
                receipt['redirect'] = diagnostic_url(urllib.parse.urljoin(request.full_url, location))
            except ValueError:
                pass  # Diagnostic metadata must not replace the transport result.
        print('THC acquisition HTTP ' + json.dumps(receipt), file=sys.stderr, flush=True)
        return response

    https_response = http_response


def run_mx(suite, arguments, environment, capture=False, timeout=None):
    python = environment.get('MX_PYTHON') or (
        'python' + environment['MX_PYTHON_VERSION'] if environment.get('MX_PYTHON_VERSION') else sys.executable)
    command = [python, '-u', str(MX), *arguments]
    if timeout is None:
        result = subprocess.run(command, cwd=ROOT / 'upstream/graal25' / suite,
                                env=environment, check=True, text=True,
                                stdout=subprocess.PIPE if capture else None)
        output = result.stdout
    else:
        command = [python, '-u', str(Path(__file__).resolve()), '--run-mx', str(MX), *arguments]
        def interrupted(signum, frame):
            raise KeyboardInterrupt('Graal acquisition interrupted')
        previous = signal.signal(signal.SIGTERM, interrupted)
        try:
            with subprocess.Popen(command, cwd=ROOT / 'upstream/graal25' / suite,
                                  env=environment, stdout=subprocess.PIPE if capture else None,
                                  text=True, start_new_session=os.name != 'nt') as process:
                try:
                    output, _ = process.communicate(timeout=timeout)
                except BaseException:
                    # Do not signal a group after communicate has reaped its leader.
                    if process.returncode is None:
                        if os.name == 'nt':
                            try:
                                subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                                               check=True, timeout=30, stdout=subprocess.DEVNULL,
                                               stderr=subprocess.DEVNULL)
                            finally:
                                process.kill()
                                process.wait()
                        else:
                            os.killpg(process.pid, signal.SIGKILL)
                            process.wait()
                    raise
                if process.returncode:
                    raise subprocess.CalledProcessError(process.returncode, command, output)
        finally:
            signal.signal(signal.SIGTERM, previous)
    if capture:
        lines = [line.strip() for line in output.splitlines() if line.strip()]
        if len(lines) != 1:
            raise SystemExit(f'Expected one result from mx {arguments}: {output}')
        return lines[0]
    return None


def acquire(environment):
    # A failure budget, not a performance target; includes cold dependency fetches.
    timeout = float(environment.get('THC_VM_ACQUISITION_TIMEOUT', '600'))
    if not math.isfinite(timeout) or timeout <= 0:
        raise SystemExit('THC_VM_ACQUISITION_TIMEOUT must be positive and finite')
    run_mx('vm', ['--max-cpus', environment.get('JAM_JOBS', '3'), '--env', 'ce',
                  'build', '--download-only'], environment, timeout=timeout)


def boot_jdk():
    home = os.environ.get('JAM_BOOT_JDK')
    if not home:
        raise SystemExit('JAM_BOOT_JDK must select a boot JDK 24 or 25')
    home = Path(home).resolve()
    version = re.search(r'^JAVA_VERSION="(\d+)[.\"+\-]',
                        (home / 'release').read_text(), re.MULTILINE)
    if not version or int(version[1]) not in (24, 25):
        raise SystemExit(f'Expected boot JDK 24 or 25: {home}')
    if int(version[1]) == 24:
        print('Graal acquisition deferred until LabsJDK: the dependency graph requires JDK 25.', flush=True)
        return None
    launcher = home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    description = subprocess.check_output([str(launcher), '-version'],
                                          stderr=subprocess.STDOUT, text=True, timeout=120)
    if 'GraalVM' in description:
        print('Graal acquisition deferred until LabsJDK: GraalVM cannot be its own base JDK.', flush=True)
        return None
    return home


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
    parser.add_argument('--download-only', action='store_true', help='acquire pinned build dependencies without compiling')
    parser.add_argument('--boot-jdk', action='store_true', help='use JAM_BOOT_JDK for early acquisition; defer JDK 24')
    parser.add_argument('--java-home', type=Path, help='matching LabsJDK builder (or JAM_GRAAL_BASE_JDK)')
    parser.add_argument('--output', type=Path, default=os.environ.get('JAM_GRAAL_OUTPUT', ROOT / 'build/graalvm'))
    parser.add_argument('--runtime-prefix', type=Path, help='C++ runtime prefix; MSVC redist on Windows')
    parser.add_argument('--runtime-license', type=Path, action='append', default=[],
                        help='MSVC notice; repeat for multiple files (Windows CI defaults to JAM_CI_TOOLS notices)')
    parser.add_argument('--compiler-runtime-license', type=Path,
                        default=os.environ.get('JAM_COMPILER_RUNTIME_LICENSE'),
                        help='LLVM compiler-runtime notice (or JAM_COMPILER_RUNTIME_LICENSE)')
    options = parser.parse_args()
    if options.boot_jdk:
        if not options.download_only or options.java_home:
            parser.error('--boot-jdk requires --download-only and excludes --java-home')
        options.java_home = boot_jdk()
        if options.java_home is None:
            return
    environment = graal_environment(options.java_home)
    if options.download_only:
        verify_sources(environment)
        acquire(environment)
        return
    package_args = packaging_arguments(options)
    output = options.output.resolve()
    if output.exists():
        raise SystemExit(f'Preserving existing output: {output}')
    verify_sources(environment)
    acquire(environment)
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
    if sys.argv[1:2] == ['--run-mx']:
        # Let mx normalize HTTP/SOCKS proxies before urllib constructs its opener.
        # Also retain receipts if mx installs an explicitly configured opener.
        original_build_opener = urllib.request.build_opener
        def observed_opener(*handlers):
            return original_build_opener(AcquisitionResponse(), *handlers)
        urllib.request.build_opener = observed_opener
        entry = Path(sys.argv[2]).resolve()
        sys.argv = [str(entry), *sys.argv[3:]]
        sys.path.insert(0, str(entry.parent))
        runpy.run_path(str(entry), run_name='__main__')
    else:
        main()
