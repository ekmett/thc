#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Inspect PE imports and select the app-local MSVC runtime for a Jam image."""

import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess

CRT = re.compile(r'(?:msvcp|msvcr|vcruntime|concrt)[0-9]+(?:_[a-z0-9]+)*\.dll')
CRT_FAMILY = re.compile(r'(?:msvcp|msvcr|vcruntime|concrt)[0-9].*\.dll|ucrtbased\.dll')
API_SET = re.compile(r'(?:api|ext)-ms-win-[a-z0-9-]+\.dll')
IMPORT_NAME = re.compile(r'[a-z0-9_+.-]+\.(?:dll|drv)')
MACHINES = {0x8664: 'x64', 0xaa64: 'arm64'}


def pe_machine(path):
    """Return the COFF machine for a PE image, or None for another file format."""
    with path.open('rb') as source:
        header = source.read(64)
        if len(header) != 64 or header[:2] != b'MZ':
            return None
        source.seek(int.from_bytes(header[60:64], 'little'))
        image = source.read(6)
        return int.from_bytes(image[4:6], 'little') if image[:4] == b'PE\0\0' else None


def readobj(*arguments):
    tool = os.environ.get('LLVM_READOBJ') or shutil.which('llvm-readobj')
    if not tool:
        raise SystemExit('Windows packaging requires llvm-readobj on PATH or LLVM_READOBJ.')
    return subprocess.check_output([tool, *map(str, arguments)], text=True, encoding='utf-8')


def pe_commands(path):
    output = readobj('--coff-imports', path)
    if not re.search(r'^Format: COFF-', output, re.MULTILINE):
        raise SystemExit(f'Expected a PE image: {path}')
    imports = set()
    for block in re.findall(r'^(?:Import|DelayImport) \{(.*?)^\}', output, re.MULTILINE | re.DOTALL):
        name = re.search(r'^\s*Name: ([^\r\n]+)$', block, re.MULTILINE)
        if name is None or not IMPORT_NAME.fullmatch(name[1].casefold()):
            raise SystemExit(f'Invalid PE import name in {path}')
        imports.add(name[1].casefold())
    return None, imports, set()


def check_import_library(path, machine):
    output = readobj('--file-headers', path)
    machines = {int(value, 16) for value in re.findall(r'Machine: \S+ \(0x([0-9A-Fa-f]+)\)', output)}
    if machines != {machine}:
        raise SystemExit(f'Import library does not match the JDK architecture: {path}')


def find_dll(directory, name):
    if not directory.is_dir():
        return None
    matches = [path for path in directory.iterdir() if path.name.casefold() == name and path.is_file()]
    if len(matches) > 1:
        raise SystemExit(f'Ambiguous DLL name under {directory}: {name}')
    return matches[0] if matches else None


def system_dependency(name, machine):
    # API-set contracts are resolved by Windows and need not exist as disk files.
    if API_SET.fullmatch(name):
        return True
    system_root = os.environ.get('SystemRoot')
    if not system_root or CRT_FAMILY.fullmatch(name):
        return False
    candidate = find_dll(Path(system_root) / 'System32', name)
    return candidate is not None and pe_machine(candidate) == machine


def windows_runtime_libraries(libraries, runtime, java_home):
    """Follow MSVC imports only; Windows and API-set DLLs remain OS-owned."""
    if not runtime.is_dir():
        raise SystemExit(f'Missing MSVC redistributable directory: {runtime}')
    machine = pe_machine(java_home / 'bin/java.exe')
    if machine not in MACHINES:
        raise SystemExit('Windows packaging requires an x64 or arm64 PE JDK.')
    available = {}
    for candidate in runtime.rglob('*'):
        name = candidate.name.casefold()
        if CRT.fullmatch(name) and candidate.is_file() and pe_machine(candidate) == machine:
            available.setdefault(name, []).append(candidate.resolve())
    pending = list(libraries.values())
    result = {}
    while pending:
        path = pending.pop()
        if pe_machine(path) != machine:
            raise SystemExit(f'PE architecture does not match the JDK: {path}')
        for name in sorted(pe_commands(path)[1]):
            if name in libraries or name in result:
                continue
            if not CRT.fullmatch(name):
                if not system_dependency(name, machine):
                    raise SystemExit(f'Unbundled Windows dependency in {path.name}: {name}')
                continue
            # A redistributable root can contain identical copies. Different
            # builds require the caller to select a narrower runtime prefix.
            candidates = {}
            for candidate in available.get(name, ()):
                digest = hashlib.sha256(candidate.read_bytes()).digest()
                candidates.setdefault(digest, candidate)
            if len(candidates) > 1:
                raise SystemExit(f'Ambiguous {name}; select its exact MSVC redistributable directory.')
            selected = next(iter(candidates.values()), None) or find_dll(java_home / 'bin', name)
            if selected is None or pe_machine(selected) != machine:
                raise SystemExit(f'Missing {name} for {MACHINES[machine]} under {runtime} or JDK bin.')
            result[name] = selected
            pending.append(selected)
    return result


def check_pe_paths(path, root, machine=None):
    """Reject external imports and dependencies absent from the deployment unit."""
    actual = pe_machine(path)
    if machine is not None and actual != machine:
        raise SystemExit(f'PE architecture does not match the deployment unit: {path}')
    if actual not in MACHINES:
        raise SystemExit(f'Unsupported PE architecture: {path}')
    for name in pe_commands(path)[1]:
        # JDK-loaded DLLs may use bin and the JVM already loaded from bin/server.
        # Executables outside bin need their own adjacent dependencies.
        directories = (path.parent,) if path.suffix.casefold() == '.exe' else (
            path.parent, root / 'bin', root / 'bin/server')
        candidate = next((found for directory in directories if (found := find_dll(directory, name))), None)
        if candidate is None:
            if system_dependency(name, actual):
                continue
            raise SystemExit(f'Unbundled PE dependency in {path}: {name}')
        if not candidate.resolve().is_relative_to(root.resolve()) or pe_machine(candidate) != actual:
            raise SystemExit(f'External or mismatched PE dependency in {path}: {candidate}')


def loader_environment(environment):
    result = {key: value for key, value in environment.items() if key.casefold() != 'path'}
    system_root = next((value for key, value in result.items() if key.casefold() == 'systemroot'), None)
    if not system_root:
        raise SystemExit('Windows loader verification requires SystemRoot.')
    # Exclude compiler and build directories from the fallback DLL search path.
    result['PATH'] = os.pathsep.join((str(Path(system_root) / 'System32'), system_root))
    return result


def check_loaded_libraries(output, directory, names):
    """Check paths observed in the test process by the Windows module API."""
    expected = {name.casefold() for name in names}
    loaded = set()
    for line in output.splitlines():
        prefix = 'jam-loaded-library: '
        if not line.startswith(prefix):
            continue
        path = Path(line.removeprefix(prefix))
        name = path.name.casefold()
        if name in expected:
            if not path.is_absolute() or str(path.resolve()).casefold() != str((directory / name).resolve()).casefold():
                raise SystemExit(f'Loaded an external Jam runtime: {path}')
            loaded.add(name)
    if loaded != expected:
        raise SystemExit(f'Loader did not report the complete Jam runtime: {expected - loaded}')
