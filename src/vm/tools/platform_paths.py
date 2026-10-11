#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Locate and identify the selected JDK image on supported build hosts."""

import argparse
import os
from pathlib import Path
import platform
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
NATIVE_BUILD = Path(os.environ.get('JAM_NATIVE_BUILD', ROOT / 'build-jam')).resolve()


def build_flavor():
    flavor = os.environ.get('JAM_BUILD_FLAVOR', 'fastdebug')
    if flavor not in ('release', 'fastdebug'):
        raise SystemExit(f'Unsupported JAM_BUILD_FLAVOR: {flavor}')
    return flavor


def reported_flavor(output):
    matches = re.findall(r'^\s*jdk\.debug = (\S+)\s*$', output, re.MULTILINE)
    if len(matches) != 1 or matches[0] not in ('release', 'fastdebug'):
        raise ValueError('VM must report exactly one supported jdk.debug property')
    return matches[0]


def runtime_flavor(home, environment=None):
    result = subprocess.run([str(java_tool(home, 'java')), '-Xshare:off',
                             '-XshowSettings:properties', '-version'],
                            text=True, capture_output=True, check=True, timeout=120,
                            env=environment)
    return reported_flavor(result.stdout + result.stderr)


def java_tool(home, name):
    suffixes = ('.exe', '.cmd', '') if platform.system() == 'Windows' else ('',)
    for suffix in suffixes:
        path = home / 'bin' / (name + suffix)
        if path.is_file():
            return path
    raise SystemExit(f'Missing JDK tool: {home}/bin/{name}')


def jdk_home(graal=False):
    system = {'Darwin': 'macosx', 'Linux': 'linux', 'Windows': 'windows'}.get(platform.system())
    machine = platform.machine().lower()
    architecture = {'arm64': 'aarch64', 'aarch64': 'aarch64',
                    'x86_64': 'x86_64', 'amd64': 'x86_64'}.get(machine)
    if system is None or architecture is None:
        raise SystemExit(f'Unsupported build host: {platform.system()} {machine}')
    source = 'labsjdk25' if graal else 'jdk25'
    image = 'graal-builder-jdk' if graal else 'jdk'
    return ROOT / 'upstream' / source / 'build' / f'{system}-{architecture}-server-{build_flavor()}' / 'images' / image


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--graal', action='store_true')
    print(jdk_home(parser.parse_args().graal))
