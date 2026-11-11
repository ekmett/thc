# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Validate packaged Mach-O images and archive members before running the JDK."""

import re
import subprocess
from package_jdk import macho


def macos_version(value):
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+){0,2}', value):
        raise ValueError(f'Malformed macOS deployment version: {value!r}')
    parts = tuple(map(int, value.split('.')))
    if not parts[0]:
        raise ValueError(f'Malformed macOS deployment version: {value!r}')
    return parts + (0,) * (3 - len(parts))


def deployment_versions(output, archive=False):
    images = output.split('Mach header\n')[1:]
    if not images and not archive:
        raise ValueError('Missing Mach-O header')
    versions = []
    for image in images:
        header = re.match(r'\s*magic[^\n]*\n([^\n]+)', image)
        if not header or len(header[1].split()) != 8:
            raise ValueError('Malformed Mach-O header')
        filetype = int(header[1].split()[4])
        targets = []
        for block in image.split('Load command ')[1:]:
            command = re.findall(r'^\s*cmd (LC_\w+)$', block, re.MULTILINE)
            if command == ['LC_BUILD_VERSION']:
                if re.findall(r'^\s*platform (\S+)$', block, re.MULTILINE) != ['1']:
                    raise ValueError('Non-macOS or malformed LC_BUILD_VERSION platform')
                target = re.findall(r'^\s*minos (\S+)$', block, re.MULTILINE)
            elif command == ['LC_VERSION_MIN_MACOSX']:
                target = re.findall(r'^\s*version (\S+)$', block, re.MULTILINE)
            else:
                continue
            if len(target) != 1:
                raise ValueError('Missing or malformed Mach-O deployment target')
            targets.append(macos_version(target[0]))
        # Assembler objects and detached debug symbols can lack deployment metadata.
        if not targets and filetype in (1, 10):
            continue
        if len(targets) != 1:
            raise ValueError('Expected one macOS deployment target per Mach-O image')
        versions.extend(targets)
    return versions


def check_macos_deployment(home, target):
    limit = macos_version(target)
    checked = 0
    for path in sorted(home.rglob('*')):
        if not path.is_file() or path.is_symlink():
            continue
        with path.open('rb') as source:
            archive = source.read(8) == b'!<arch>\n'
        if not archive and not macho(path):
            continue
        result = subprocess.run(['otool', '-arch', 'all', '-hl', str(path)],
                                text=True, capture_output=True, check=True)
        try:
            if result.stderr.strip():
                raise ValueError(result.stderr.strip())
            versions = deployment_versions(result.stdout, archive=archive)
            for version in versions:
                if version > limit:
                    raise ValueError(f'requires macOS {".".join(map(str, version))}, '
                                     f'exceeds MACOSX_DEPLOYMENT_TARGET={target}')
        except ValueError as error:
            raise SystemExit(f'MacOS deployment check failed for {path.relative_to(home)}: {error}') from error
        checked += 1
    print(f'Packaged macOS deployment targets passed: {checked} Mach-O files/archives, maximum {target}.')
