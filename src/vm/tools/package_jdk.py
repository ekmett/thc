#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Bundle Jam and its guest API into a relocatable macOS, Linux or Windows JDK."""

import argparse
import filecmp
import os
from pathlib import Path
from platform_paths import NATIVE_BUILD, build_flavor, runtime_flavor
import platform
import re
import shutil
import subprocess
import tempfile
from pe_runtime import (check_import_library, check_pe_paths, find_dll, pe_commands,
                        pe_machine, windows_runtime_libraries)

ROOT = Path(__file__).resolve().parents[1]
SYSTEM = ('/usr/lib/', '/System/Library/')
DARWIN_RUNTIME = ('libjam-vm.dylib', 'libc++.1.dylib', 'libc++abi.1.dylib', 'libunwind.1.dylib')
CPP_RUNTIME = re.compile(r'lib(?:c\+\+|c\+\+abi|unwind|stdc\+\+)\.so(?:\..+)?$')


def elf(path):
    with path.open('rb') as source:
        return source.read(4) == b'\x7fELF'


def elf_commands(path):
    result = subprocess.check_output(['readelf', '--dynamic', '--wide', str(path)], text=True)
    identity, dependencies, rpaths = None, set(), set()
    for kind, value in re.findall(r'\((SONAME|NEEDED|RUNPATH|RPATH)\).*?\[(.*?)\]', result):
        if kind == 'SONAME':
            identity = value
        elif kind == 'NEEDED':
            dependencies.add(value)
        else:
            rpaths.update(value.split(':'))
    return identity, dependencies, rpaths


def runtime_libraries(directory):
    manifest = directory / 'runtime-libraries.txt'
    if manifest.is_file():
        names = tuple(manifest.read_text().splitlines())
        windows = platform.system() == 'Windows'
        collector = 'jam-vm.dll' if windows else 'libjam-vm.dylib' if platform.system() == 'Darwin' else 'libjam-vm.so'
        pattern = r'[a-z0-9_+.-]+\.dll' if windows else r'lib[A-Za-z0-9_+.-]+'
        if collector not in names or len(set(name.casefold() if windows else name for name in names)) != len(names) or any(
                not re.fullmatch(pattern, name) for name in names):
            raise SystemExit(f'Invalid Jam runtime manifest: {manifest}')
        return names
    if platform.system() == 'Darwin':
        return DARWIN_RUNTIME
    raise SystemExit(f'Missing Jam runtime manifest: {manifest}')


def loader_environment(environment):
    result = dict(environment)
    # An installed toolchain must not mask a broken deployment search path.
    for name in ('LD_LIBRARY_PATH', 'LD_PRELOAD', 'LD_AUDIT', 'LD_DEBUG_OUTPUT',
                 'DYLD_LIBRARY_PATH', 'DYLD_FALLBACK_LIBRARY_PATH', 'DYLD_INSERT_LIBRARIES'):
        result.pop(name, None)
    if platform.system() == 'Windows':
        from pe_runtime import loader_environment as windows_environment
        return windows_environment(result)
    result['DYLD_PRINT_LIBRARIES' if platform.system() == 'Darwin' else 'LD_DEBUG'] = (
        '1' if platform.system() == 'Darwin' else 'libs')
    return result


def check_loaded_libraries(output, directory, names):
    if platform.system() == 'Windows':
        from pe_runtime import check_loaded_libraries as check_windows_libraries
        return check_windows_libraries(output, directory, names)
    loaded = set()
    pattern = (r'dyld\[[^]]+\]: (?:<[^>]+> )?(.+)' if platform.system() == 'Darwin'
               else r'\s*\d+:\s+calling init:\s+(.+)')
    for line in output.splitlines():
        match = re.fullmatch(pattern, line)
        if not match:
            continue
        path = Path(match[1].strip())
        # Apple system frameworks can independently load the system C++ runtime.
        if platform.system() == 'Darwin' and str(path).startswith(SYSTEM):
            continue
        if path.name in names:
            if path.resolve() != (directory / path.name).resolve():
                raise SystemExit(f'Loaded an external Jam runtime: {path}')
            loaded.add(path.name)
    if loaded != set(names):
        raise SystemExit(f'Loader did not report the complete Jam runtime: {set(names) - loaded}')


def linux_runtime_libraries(native_library, runtime):
    """Follow only C++ runtime dependencies; glibc and OS libraries stay on the host."""
    libraries = {}
    pending = [native_library]
    with native_library.open('rb') as source:
        header = source.read(20)
    while pending:
        for dependency in sorted(elf_commands(pending.pop())[1]):
            name = Path(dependency).name
            if name in libraries or not CPP_RUNTIME.fullmatch(name):
                continue
            candidates = set()
            for candidate in runtime.rglob(name):
                if candidate.is_file():
                    with candidate.open('rb') as source:
                        other = source.read(20)
                    # ELF class, byte order and machine must match the collector.
                    if other[:6] == header[:6] and other[18:20] == header[18:20]:
                        candidates.add(candidate.resolve())
            if len(candidates) != 1:
                raise SystemExit(f'Expected one {name} matching {native_library.name} under {runtime}; found {len(candidates)}')
            libraries[name] = candidates.pop()
            pending.append(libraries[name])
    return libraries


def rewrite_elf(path, stage, java_home, library_dir, libraries, runtime):
    identity, dependencies, rpaths = elf_commands(path)
    private = path.parent == library_dir
    changes = []
    if private and identity != path.name:
        changes += ['--set-soname', path.name]
    desired_paths = []
    for dependency in sorted(dependencies):
        name = Path(dependency).name
        replacement = dependency
        if name in libraries:
            replacement = name
            desired_paths.append('$ORIGIN/' + os.path.relpath(library_dir, path.parent))
        elif dependency.startswith('/'):
            if Path(dependency).is_relative_to(java_home):
                replacement = '$ORIGIN/' + os.path.relpath(stage / Path(dependency).relative_to(java_home), path.parent)
            else:
                raise SystemExit(f'External dependency in {path.relative_to(stage)}: {dependency}')
        elif '/' in dependency and not dependency.startswith(('$ORIGIN/', '${ORIGIN}/')):
            raise SystemExit(f'Relative dependency in {path.relative_to(stage)}: {dependency}')
        if replacement != dependency:
            changes += ['--replace-needed', dependency, replacement]
    if private:
        desired_paths = ['$ORIGIN']
    else:
        for rpath in sorted(rpaths):
            if rpath.startswith('/'):
                resolved = Path(rpath).resolve()
                if resolved.is_relative_to(java_home):
                    desired_paths.append('$ORIGIN/' + os.path.relpath(stage / resolved.relative_to(java_home), path.parent))
                elif any(resolved.is_relative_to(base) for base in (ROOT, NATIVE_BUILD, runtime)):
                    continue
                else:
                    raise SystemExit(f'External runtime path in {path.relative_to(stage)}: {rpath}')
            elif rpath in ('$ORIGIN', '${ORIGIN}') or rpath.startswith(('$ORIGIN/', '${ORIGIN}/')):
                desired_paths.append(rpath)
            else:
                raise SystemExit(f'Unanchored runtime path in {path.relative_to(stage)}: {rpath}')
    desired_paths = list(dict.fromkeys(desired_paths))
    if set(desired_paths) != rpaths:
        changes += ['--set-rpath', ':'.join(desired_paths)] if desired_paths else ['--remove-rpath']
    if changes:
        path.chmod(path.stat().st_mode | 0o200)
        subprocess.run(['patchelf', *changes, str(path)], check=True)


def check_elf_paths(path, root):
    identity, dependencies, rpaths = elf_commands(path)
    if identity and '/' in identity:
        raise SystemExit(f'Nonportable ELF identity in {path}: {identity}')
    for value in dependencies | rpaths:
        if value in ('$ORIGIN', '${ORIGIN}') or value.startswith(('$ORIGIN/', '${ORIGIN}/')):
            suffix = value.removeprefix('${ORIGIN}').removeprefix('$ORIGIN')
            target = (path.parent / suffix.lstrip('/')).resolve()
            if not target.is_relative_to(root) or not target.exists():
                raise SystemExit(f'Broken or external ELF loader path in {path}: {value}')
        elif '/' in value or value in rpaths:
            raise SystemExit(f'Nonportable ELF loader path in {path}: {value}')


def macho(path):
    with path.open('rb') as source:
        header = source.read(8)
    if header[:4] in (b'\xcf\xfa\xed\xfe', b'\xfe\xed\xfa\xcf', b'\xce\xfa\xed\xfe', b'\xfe\xed\xfa\xce'):
        return True
    if header[:4] in (b'\xca\xfe\xba\xbe', b'\xca\xfe\xba\xbf'):
        return 0 < int.from_bytes(header[4:], 'big') < 20
    return False


def load_commands(path):
    if elf(path):
        return elf_commands(path)
    if pe_machine(path) is not None:
        return pe_commands(path)
    result = subprocess.check_output(['otool', '-l', str(path)], text=True)
    identity, dependencies, rpaths = None, set(), set()
    for block in result.split('Load command ')[1:]:
        command = re.search(r'^\s*cmd (LC_\w+)$', block, re.MULTILINE)
        value = re.search(r'^\s*(?:name|path) (.*?) \(offset \d+\)$', block, re.MULTILINE)
        if not command or not value:
            continue
        kind, name = command[1], value[1]
        if kind == 'LC_ID_DYLIB':
            identity = name
        elif kind == 'LC_RPATH':
            rpaths.add(name)
        elif kind in ('LC_LOAD_DYLIB', 'LC_LOAD_WEAK_DYLIB', 'LC_REEXPORT_DYLIB',
                      'LC_LAZY_LOAD_DYLIB', 'LC_LOAD_UPWARD_DYLIB'):
            dependencies.add(name)
    return identity, dependencies, rpaths


def static_libraries(build, runtime, system, configuration="Release"):
    """Read the native build's archive closure and add the matching C++ runtime."""
    manifest = build / f'jam-vm-static-libraries-{configuration}.txt'
    if not manifest.is_file():
        raise SystemExit(f'Missing static Jam build manifest: {manifest}')
    archives = [Path(line) for line in manifest.read_text().splitlines()]
    if system != 'Windows':
        for name in ('libc++.a', 'libc++abi.a', 'libunwind.a'):
            candidates = {p.resolve() for p in runtime.rglob(name) if p.is_file()}
            if len(candidates) != 1:
                raise SystemExit(f'Expected one matching {name} under {runtime}; found {len(candidates)}')
            archives.append(candidates.pop())
    names = [p.name for p in archives]
    collector = 'jam-vm-static.lib' if system == 'Windows' else 'libjam-vm-static.a'
    if not names or names[0] != collector or len(names) != len(set(names)):
        raise SystemExit(f'Invalid static Jam archive closure: {manifest}')
    for path in archives:
        if not path.is_file() or path.suffix != ('.lib' if system == 'Windows' else '.a'):
            raise SystemExit(f'Missing static Jam archive: {path}')
        with path.open('rb') as source:
            if source.read(8) != b'!<arch>\n':
                raise SystemExit(f'Expected a self-contained static archive: {path}')
    return archives


def package(java_home, output, runtime, runtime_licenses=(), compiler_runtime_license=None):
    system = platform.system()
    if system not in ('Darwin', 'Linux', 'Windows'):
        raise SystemExit('JDK packaging supports macOS, Linux and Windows.')
    if system == 'Linux':
        for tool in ('readelf', 'patchelf'):
            if shutil.which(tool) is None:
                raise SystemExit(f'Linux packaging requires {tool} on PATH.')
    java_home, output, runtime = java_home.resolve(), output.resolve(), runtime.resolve()
    if output.exists():
        raise SystemExit(f'Preserving existing output: {output}')
    launcher = 'java.exe' if system == 'Windows' else 'java'
    if not (java_home / 'bin' / launcher).is_file():
        raise SystemExit(f'Pass the JDK home containing bin/{launcher}.')
    if output.is_relative_to(java_home):
        raise SystemExit('The output must be outside the source JDK.')
    libraries = {
        'libjam-vm.dylib': NATIVE_BUILD / 'libjam-vm.dylib',
        'libjam_bridge.dylib': ROOT / 'build/bridge/lib/libjam_bridge.dylib',
        'libc++.1.dylib': runtime / 'lib/c++/libc++.1.dylib',
        'libc++abi.1.dylib': runtime / 'lib/c++/libc++abi.1.dylib',
        'libunwind.1.dylib': runtime / 'lib/unwind/libunwind.1.dylib',
    } if system == 'Darwin' else {
        'libjam-vm.so': NATIVE_BUILD / 'libjam-vm.so',
        'libjam_bridge.so': ROOT / 'build/bridge/lib/libjam_bridge.so',
    }
    import_library = None
    if system == 'Windows':
        libraries = {
            'jam-vm.dll': NATIVE_BUILD / 'jam-vm.dll',
            'jam_bridge.dll': ROOT / 'build/bridge/lib/jam_bridge.dll',
        }
        import_library = NATIVE_BUILD / 'jam-vm.lib'
        for source in (*libraries.values(), import_library):
            if not source.is_file():
                raise SystemExit(f'Missing package input: {source}')
        libraries.update(windows_runtime_libraries(libraries, runtime, java_home))
        check_import_library(import_library, pe_machine(java_home / 'bin/java.exe'))
        introduced = [name for name, source in libraries.items() if not name.startswith(('jam_', 'jam-'))
                      and ((existing := find_dll(java_home / 'bin', name)) is None
                           or not filecmp.cmp(source, existing, shallow=False))]
        if introduced and not runtime_licenses:
            raise SystemExit('Pass --runtime-license with the genuine MSVC redistributable notice for: '
                             + ', '.join(introduced))
    if system == 'Linux':
        if not libraries['libjam-vm.so'].is_file():
            raise SystemExit(f'Missing package input: {libraries["libjam-vm.so"]}')
        libraries.update(linux_runtime_libraries(libraries['libjam-vm.so'], runtime))
    archives = static_libraries(NATIVE_BUILD, runtime, system, os.environ.get("JAM_NATIVE_CONFIG", "Release"))
    native_runtime = [name for name in libraries if not name.startswith(('libjam_bridge.', 'jam_bridge.'))]
    licenses = {
        'LICENSE.md': ROOT.parent / 'LICENSE.md',
        'THIRD_PARTY_NOTICES.md': ROOT.parent / 'THIRD_PARTY_NOTICES.md',
        'NOTICE.md': ROOT / 'NOTICE.md',
        'jam-LICENSE.md': ROOT.parent / 'LICENSE.md',
        'native-LICENSE.md': NATIVE_BUILD / 'native-LICENSE.md',
    }
    if system == 'Windows':
        if compiler_runtime_license is None:
            raise SystemExit('Pass --compiler-runtime-license or set JAM_COMPILER_RUNTIME_LICENSE '
                             'to the LLVM compiler-runtime license used by the native build.')
        licenses['LLVM-LICENSE.txt'] = Path(compiler_runtime_license)
        for index, source in enumerate(runtime_licenses, 1):
            source = Path(source)
            licenses[f'MSVC-{index}-{source.name}'] = source
    else:
        licenses['LLVM-LICENSE.txt'] = runtime / 'LICENSE.TXT'
    jars = [ROOT / 'build/bridge' / name for name in ('jam-vm.jar', 'jam-vm-sources.jar')]
    header = ROOT / 'src/adapter/jam_vm.h'
    for source in [*libraries.values(), *licenses.values(), *jars, header]:
        if not source.is_file():
            raise SystemExit(f'Missing package input: {source}')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='jam-package-', dir=output.parent) as temporary:
        stage = Path(temporary) / 'jdk'
        shutil.copytree(java_home, stage, symlinks=True)
        library_dir = stage / 'lib/jam'
        library_dir.mkdir(parents=True)
        legal_dir = stage / 'legal/jam-vm'
        legal_dir.mkdir(parents=True)
        for name, source in libraries.items():
            shutil.copy2(source, library_dir / name)
            if system == 'Windows':
                # PE imports use the executable directory, not an ELF/Mach-O
                # loader path. Keep a second copy for Native Image deployment.
                shutil.copy2(source, stage / 'bin' / name)
        if import_library is not None:
            shutil.copy2(import_library, library_dir / import_library.name)
        (library_dir / 'runtime-libraries.txt').write_text('\n'.join(native_runtime) + '\n')
        static_dir = library_dir / 'static'
        static_dir.mkdir()
        for archive in archives:
            shutil.copy2(archive, static_dir / archive.name)
        static_names = [p.stem if system == 'Windows' else p.stem.removeprefix('lib') for p in archives]
        (library_dir / 'native-image-libraries.txt').write_text('\n'.join(static_names) + '\n')
        for source in jars:
            shutil.copy2(source, library_dir / source.name)
        (library_dir / 'include').mkdir()
        shutil.copy2(header, library_dir / 'include/jam_vm.h')
        for name, source in licenses.items():
            shutil.copy2(source, legal_dir / name)

        binaries = []
        for path in stage.rglob('*'):
            if path.is_symlink():
                target = Path(os.readlink(path))
                if target.is_absolute():
                    if not target.is_relative_to(java_home):
                        raise SystemExit(f'External symlink in JDK: {path.relative_to(stage)} -> {target}')
                    path.unlink()
                    path.symlink_to(os.path.relpath(stage / target.relative_to(java_home), path.parent))
                if not path.resolve().is_relative_to(stage) or not path.exists():
                    raise SystemExit(f'Broken or external symlink in JDK: {path.relative_to(stage)}')
            elif system == 'Linux' and path.suffix == '.debuginfo':
                # OpenJDK's detached symbols retain ELF headers but no loader data.
                continue
            elif path.is_file() and (macho(path) if system == 'Darwin' else
                                    pe_machine(path) is not None if system == 'Windows' else elf(path)):
                binaries.append(path)

        if system == 'Windows':
            # Graal also launches executables below lib/svm/bin. Each executable
            # directory needs its own CRT closure; these tool-only DLLs are not
            # part of the Jam runtime manifest used by native applications.
            directories = {path.parent for path in binaries
                           if path.suffix.casefold() == '.exe' and path.parent != stage / 'bin'}
            for directory in sorted(directories):
                local = {path.name.casefold(): path for path in binaries if path.parent == directory}
                tool_runtime = windows_runtime_libraries(local, runtime, stage)
                introduced = [name for name, source in tool_runtime.items()
                              if (existing := find_dll(java_home / 'bin', name)) is None
                              or not filecmp.cmp(source, existing, shallow=False)]
                if introduced and not runtime_licenses:
                    raise SystemExit('Pass --runtime-license with the genuine MSVC redistributable notice for: '
                                     + ', '.join(introduced))
                for name, source in tool_runtime.items():
                    target = directory / name
                    shutil.copy2(source, target)
                    binaries.append(target)

        for path in binaries:
            if system == 'Windows':
                continue
            if system == 'Linux':
                rewrite_elf(path, stage, java_home, library_dir, libraries, runtime)
                continue
            identity, dependencies, rpaths = load_commands(path)
            changes = []
            if identity and (path.parent == library_dir or identity.startswith('/')):
                changes += ['-id', '@rpath/' + path.name]
            for dependency in sorted(dependencies):
                if dependency.startswith(SYSTEM):
                    continue
                if Path(dependency).name in libraries:
                    target = library_dir / Path(dependency).name
                elif path.parent == library_dir:
                    raise SystemExit(f'Unbundled Jam runtime dependency in {path.name}: {dependency}')
                elif dependency.startswith('/') and Path(dependency).is_relative_to(java_home):
                    target = stage / Path(dependency).relative_to(java_home)
                elif dependency.startswith('/'):
                    raise SystemExit(f'Unbundled dependency in {path.relative_to(stage)}: {dependency}')
                else:
                    continue
                replacement = '@loader_path/' + os.path.relpath(target, path.parent)
                if dependency != replacement:
                    changes += ['-change', dependency, replacement]
            for rpath in sorted(rpaths):
                if path.parent == library_dir:
                    # These libraries are flattened into lib/jam and every
                    # dependency above now has a direct loader-relative path.
                    changes += ['-delete_rpath', rpath]
                    continue
                if rpath.startswith('@loader_path/'):
                    target = path.parent / rpath.removeprefix('@loader_path/')
                    if not target.exists() and all(dep.startswith(SYSTEM) for dep in dependencies):
                        # Statically linked distribution tools can retain an
                        # unused build-layout search path (for example lld).
                        changes += ['-delete_rpath', rpath]
                        continue
                if not rpath.startswith('/') or rpath.startswith(SYSTEM):
                    continue
                resolved = Path(rpath).resolve()
                if resolved.is_relative_to(java_home):
                    target = stage / resolved.relative_to(java_home)
                    replacement = '@loader_path/' + os.path.relpath(target, path.parent)
                    changes += ['-rpath', rpath, replacement]
                elif any(resolved.is_relative_to(base) for base in (ROOT, NATIVE_BUILD, runtime)):
                    changes += ['-delete_rpath', rpath]
                else:
                    raise SystemExit(f'External runtime path in {path.relative_to(stage)}: {rpath}')
            if changes:
                path.chmod(path.stat().st_mode | 0o200)
                subprocess.run(['install_name_tool', *changes, str(path)], check=True)
                signed = subprocess.run(['codesign', '--force', '--sign', '-',
                                         '--preserve-metadata=identifier,entitlements,flags,runtime', str(path)],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
                if signed.returncode:
                    raise SystemExit(f'Could not sign {path.relative_to(stage)}: {signed.stderr}')

        # Check every native image after rewriting, including native-image and libgraal.
        for path in binaries:
            if system == 'Windows':
                check_pe_paths(path, stage, pe_machine(stage / 'bin/java.exe'))
                continue
            if system == 'Linux':
                check_elf_paths(path, stage)
                continue
            identity, dependencies, rpaths = load_commands(path)
            for value in [identity, *dependencies, *rpaths]:
                if value and value.startswith('/') and not value.startswith(SYSTEM):
                    raise SystemExit(f'External load path remains in {path.relative_to(stage)}: {value}')
            for value in dependencies | rpaths:
                if value.startswith('@loader_path/'):
                    target = (path.parent / value.removeprefix('@loader_path/')).resolve()
                    if not target.is_relative_to(stage) or not target.exists():
                        raise SystemExit(f'Broken or external loader path in {path.relative_to(stage)}: {value}')
        actual = runtime_flavor(stage, loader_environment(os.environ))
        if actual != build_flavor():
            raise SystemExit(f'Packaged VM flavor {actual} differs from requested {build_flavor()}')
        release_file = stage / 'release'
        release_text = re.sub(r'^JAM_BUILD_FLAVOR=.*\n?', '', release_file.read_text(), flags=re.MULTILINE)
        release_file.write_text(release_text.rstrip() + f'\nJAM_BUILD_FLAVOR="{actual}"\n')
        stage.rename(output)
    print(f'Packaged Jam JDK: {output}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--runtime-prefix', type=Path,
                        default=os.environ.get('JAM_LIBCXX_PREFIX', '/opt/homebrew/opt/llvm@22') if platform.system() == 'Darwin'
                        else os.environ.get('JAM_MSVC_REDIST', os.environ.get('VCToolsRedistDir')) if platform.system() == 'Windows'
                        else os.environ.get('JAM_LIBCXX_PREFIX'),
                        help='private C++ runtime prefix; the MSVC redistributable root on Windows')
    parser.add_argument('--runtime-license', type=Path, action='append', default=[],
                        help='genuine MSVC redistributable notice for added Windows CRT DLLs (repeatable)')
    parser.add_argument('--compiler-runtime-license', type=Path,
                        default=os.environ.get('JAM_COMPILER_RUNTIME_LICENSE'),
                        help='LLVM compiler-runtime license for the Windows native build')
    options = parser.parse_args()
    if options.runtime_prefix is None:
        parser.error('pass --runtime-prefix for the private C++ runtime '
                     '(or set JAM_LIBCXX_PREFIX / JAM_MSVC_REDIST for this platform)')
    package(options.java_home, options.output, options.runtime_prefix,
            options.runtime_license, options.compiler_runtime_license)
