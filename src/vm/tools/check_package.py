#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Run the public weak API using only a packaged JDK and its bundled libraries."""

import argparse
import os
from pathlib import Path
import platform
import re
import subprocess
import tempfile
from package_jdk import check_loaded_libraries, loader_environment, runtime_libraries
from platform_paths import java_tool, build_flavor, runtime_flavor
from runtime_probe import run
from macho_deployment import check_macos_deployment

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java-home', required=True, type=Path)
options = parser.parse_args()
home = options.java_home.resolve()
if platform.system() == 'Darwin':
    check_macos_deployment(home, os.environ.get('MACOSX_DEPLOYMENT_TARGET', '15.5'))
actual = runtime_flavor(home, loader_environment(os.environ))
recorded = re.findall(r'^JAM_BUILD_FLAVOR="([^"\n]+)"$', (home / 'release').read_text(), re.MULTILINE)
if recorded != [actual] or actual != build_flavor():
    raise SystemExit(f'Package flavor mismatch: VM={actual}, recorded={recorded}, requested={build_flavor()}')
library = home / 'lib/jam'
jar = library / 'jam-vm.jar'
windows = platform.system() == 'Windows'
native_library = home / 'bin' if windows else library
with tempfile.TemporaryDirectory(prefix='jam-package-check-') as temporary:
    classes = Path(temporary) / 'classes'
    subprocess.run([str(java_tool(home, 'javac')), '-cp', str(jar), '-d', str(classes),
                    str(root / 't/bridge/WeakBridgeSmoke.java')], check=True)
    result = run([
        str(java_tool(home, 'java')), '-Xshare:off', '-Xms32m', '-Xmx32m',
        '-XX:+UnlockExperimentalVMOptions', '-XX:+UnlockDiagnosticVMOptions', '-XX:+UseJamGC',
        '-XX:+VerifyBeforeGC', '-XX:+VerifyAfterGC', '--enable-native-access=ALL-UNNAMED',
        *(['-Djam.runtime.audit=true'] if windows else []),
        '-Djava.library.path=' + str(native_library), '-cp', os.pathsep.join((str(classes), str(jar))),
        'WeakBridgeSmoke'], cwd=temporary, env=loader_environment(os.environ),
        timeout=120)
output = result.stdout + result.stderr
native_image = next((home / 'bin' / name for name in ('native-image.exe', 'native-image.cmd', 'native-image')
                     if (home / 'bin' / name).is_file()), None)
version = None
if native_image is not None:
    version = subprocess.run([str(native_image), '--version'], text=True,
                             capture_output=True, timeout=120, env=loader_environment(os.environ))
    output += '\n' + version.stdout + version.stderr
(root / 'evidence').mkdir(exist_ok=True)
(root / 'evidence/package-check.log').write_text(output)
if version is not None and version.returncode:
    raise SystemExit(f'Packaged native-image launcher failed:\n{version.stdout}{version.stderr}')
if result.returncode or 'Weak bridge passed:' not in output:
    raise SystemExit(f'Packaged JDK failed (exit {result.returncode}):\n{output[-6000:]}')
names = (*runtime_libraries(library), 'jam_bridge.dll' if windows else
         'libjam_bridge.dylib' if platform.system() == 'Darwin' else 'libjam_bridge.so')
check_loaded_libraries(output, native_library, names)
print('Packaged JDK passed: public weak API and bundled native libraries.')
