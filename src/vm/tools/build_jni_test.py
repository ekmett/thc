#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

"""Build the same JNI weak-global fixture for HotSpot and Native Image."""

import os
from pathlib import Path
import platform
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def build(java_home, output, *, collector=False):
    output.mkdir(parents=True, exist_ok=True)
    system = platform.system()
    windows = system == 'Windows'
    include = {'Darwin': 'darwin', 'Linux': 'linux', 'Windows': 'win32'}[system]
    name = {'Darwin': 'libjam_weak_test.dylib', 'Linux': 'libjam_weak_test.so',
            'Windows': 'jam_weak_test.dll'}[system]
    if collector:
        name = name.replace('jam_weak_test', 'jam_jni')
    library = output / name
    sources = [ROOT / 't' / name for name in
               (('jam_jni.c', 'critical_jni.c', 'heap_walk_jni.c') if collector else ('weak_jni.c',))]
    compiler = shlex.split(os.environ.get('CC', 'clang-cl' if windows else 'cc'))
    if windows:
        flags = ['/nologo', '/std:c11', '/O2', '/MD', '/W4', '/WX', '/LD',
                 '/I' + str(java_home / 'include'), '/I' + str(java_home / 'include' / include),
                 *map(str, sources), '/Fe' + str(library), '/link',
                 '/IMPLIB:' + str(library.with_suffix('.lib'))]
    else:
        flags = ['-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', '-fvisibility=hidden',
                 *(['-dynamiclib'] if system == 'Darwin' else ['-shared', '-fPIC']),
                 '-I' + str(java_home / 'include'), '-I' + str(java_home / 'include' / include),
                 *map(str, sources), '-o', str(library),
                 *(['-undefined', 'dynamic_lookup'] if collector and system == 'Darwin' else [])]
    subprocess.run([*compiler, *flags], cwd=output, check=True)
    return library
