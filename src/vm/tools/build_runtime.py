#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Prepare and build a managed runtime using the already-built THC VM adapter."""

import argparse
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile

from build_graal import packaging_arguments
from platform_paths import ROOT, NATIVE_BUILD


def run(script, *arguments):
    subprocess.run([sys.executable, str(ROOT / 'tools' / script), *map(str, arguments)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runtime', choices=('graalvm',))
    parser.add_argument('--runtime-prefix', type=Path)
    parser.add_argument('--runtime-license', type=Path, action='append', default=[])
    parser.add_argument('--compiler-runtime-license', type=Path,
                        default=os.environ.get('JAM_COMPILER_RUNTIME_LICENSE'))
    options = parser.parse_args()
    package_args = packaging_arguments(options)
    if platform.system() == 'Windows':
        os.environ['PATH'] = str(NATIVE_BUILD) + os.pathsep + os.environ.get('PATH', '')
    run('fetch_sources.py', '--graal')
    source = ROOT / 'upstream/labsjdk25'
    if not source.exists():
        run('prepare_jdk.py')
    run('prepare_graal.py')
    run('check_patches.py', '--graal')
    if platform.system() == 'Windows':
        subprocess.run(['pwsh', '-NoProfile', '-File', str(ROOT / 'tools/build_hotspot.ps1')], check=True)
    else:
        subprocess.run(['bash', str(ROOT / 'tools/build_hotspot.sh'),
                        *(['--with-native-debug-symbols=none'] if platform.system() == 'Linux' else [])], check=True)
    output = ROOT / 'build/graalvm'
    output.parent.mkdir(parents=True, exist_ok=True)
    # Keep the last working package until its replacement passes packaging checks.
    with tempfile.TemporaryDirectory(prefix='.runtime-', dir=output.parent) as temporary:
        stage = Path(temporary) / 'image'
        run('build_graal.py', '--output', stage, *package_args)
        previous = Path(temporary) / 'previous'
        if output.exists():
            output.rename(previous)
        try:
            stage.rename(output)
        except BaseException:
            if previous.exists():
                previous.rename(output)
            raise
    print(f'Built {options.runtime}: {output}')


if __name__ == '__main__':
    main()
