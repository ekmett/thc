#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Build and relocate Jam executables, including a runtime-compiled Truffle consumer."""

import argparse
import os
from pathlib import Path
import platform
import shlex
import shutil
import subprocess
from package_jdk import (check_elf_paths, check_loaded_libraries, load_commands,
                         loader_environment, runtime_libraries, elf_commands, SYSTEM)
from pe_runtime import check_pe_paths
from platform_paths import java_tool
from runtime_probe import run as audit_runtime
from build_jni_test import build as build_jni_test

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java-home', type=Path, default=root / 'build/graalvm')
options = parser.parse_args()
home = options.java_home.resolve()
work = root / 'build-substrate-tests'
classes = work / 'classes'
scratch = work / 'tmp'
evidence = root / 'evidence'
for directory in (classes, scratch, evidence):
    directory.mkdir(parents=True, exist_ok=True)
environment = dict(os.environ, TMPDIR=str(scratch))
windows = platform.system() == 'Windows'
jar = home / 'lib/jam/jam-vm.jar'
static_runtime = (home / 'lib/jam/native-image-libraries.txt').is_file()
image_options = [java_tool(home, 'native-image'), '--gc=jam', '-ETMPDIR',
                 '-J-Djava.io.tmpdir=' + str(scratch),
                 '-J-Xmx' + os.environ.get('JAM_NATIVE_IMAGE_HEAP', '6g'),
                 '--parallelism=' + os.environ.get('JAM_JOBS', '3')]
if platform.system() == 'Darwin':
    image_options += ['-EMACOSX_DEPLOYMENT_TARGET=' + os.environ.get('MACOSX_DEPLOYMENT_TARGET', '15.5')]


def run(label, command, timeout=300, expected=None, trace_libraries=False, reject=False):
    runtime_environment = loader_environment(environment) if trace_libraries else environment
    if trace_libraries:
        result = audit_runtime(list(map(str, command)), cwd=root, env=runtime_environment, timeout=timeout)
    else:
        result = subprocess.run(list(map(str, command)), cwd=root, env=runtime_environment,
                                text=True, capture_output=True, timeout=timeout)
    output = result.stdout + result.stderr
    (evidence / f'substrate-{label}.log').write_text(output + f'\nexit={result.returncode}\n')
    if (result.returncode == 0 if reject else result.returncode != 0) or expected is not None and expected not in output:
        raise SystemExit(f'{label} failed (exit {result.returncode}):\n{output[-8000:]}')
    return output


queue_classes = work / 'queue-classes'
queue_classes.mkdir(exist_ok=True)
run('queue-javac', [java_tool(home, 'javac'), '-d', queue_classes,
                    root / 't/builder/QueueLifecycleTest.java'])
run('queue-lifecycle', [java_tool(home, 'java'), '-ea', '-cp',
    os.pathsep.join(map(str, (queue_classes, *sorted(home.rglob('*.jar'))))),
    'QueueLifecycleTest'], expected='VM operation queue lifecycle passed:')


run('javac', [java_tool(home, 'javac'), '--add-modules', 'org.graalvm.nativeimage',
               '-cp', jar, '-d', classes, *sorted((root / 't/substrate').glob('*.java')),
               root / 't/bridge/WeakBridgeSmoke.java', root / 't/bridge/JNIWeakSmoke.java'])
jni_library = build_jni_test(home, work / 'jni')
jni_metadata = classes / 'META-INF/native-image/jam-vm/jni-weak/jni-config.json'
jni_metadata.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(root / 't/bridge/jni-config.json', jni_metadata)
if windows:
    run('pin-compile', [os.environ.get('JAM_CXX', 'clang-cl'), '/nologo', '/std:c11', '/O2', '/MD',
                        '/W4', '/WX', '/c', root / 't/substrate/pin_writer.c',
                        '/Fo' + str(work / 'pin_writer.obj')])
    run('pin-archive', ['lib', '/nologo', '/OUT:' + str(work / 'jam_pin_test.lib'), work / 'pin_writer.obj'])
else:
    compiler = ['xcrun', 'clang'] if platform.system() == 'Darwin' else shlex.split(os.environ.get('CC', 'cc'))
    archiver = ['xcrun', 'ar'] if platform.system() == 'Darwin' else shlex.split(os.environ.get('AR', 'ar'))
    run('pin-compile', [*compiler, '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                        '-c', root / 't/substrate/pin_writer.c', '-o', work / 'pin_writer.o'])
    run('pin-archive', [*archiver, 'rcs', work / 'libjam_pin_test.a', work / 'pin_writer.o'])
executable = work / ('substrate-smoke.exe' if windows else 'substrate-smoke')
run('image-build', [*image_options, '--enable-monitoring=heapdump',
                    '--initialize-at-build-time=IsolateSmoke$EntryPoints,ImageRootsSmoke$ImageRoots',
                    '--initialize-at-run-time=JNIWeakSmoke',
                    '--enable-native-access=ALL-UNNAMED',
                    '-Djam.pin.include=' + str(root / 't/substrate'),
                    '-Djam.pin.library=' + str(work),
                    '-cp', os.pathsep.join(map(str, (classes, jar))),
                    'SubstrateSmoke', executable], timeout=1200,
    expected='Garbage collector: Jam')

def relocate(executable):
    # Windows loads adjacent DLLs; Unix images use their sibling runtime directory.
    bundle = executable.with_name(executable.name + '.jam')
    if static_runtime:
        if (bundle / 'linkage.txt').read_text() != 'static\n':
            raise SystemExit('Native Image did not declare static Jam linkage')
        if any(path.suffix in ('.dll', '.so', '.dylib') for path in bundle.rglob('*')):
            raise SystemExit('Static Jam image unexpectedly copied runtime libraries')
    deployed = tuple(name for name in runtime_names if not static_runtime or windows and name.casefold() != 'jam-vm.dll')
    for library in deployed:
        if not ((executable.parent if windows else bundle) / library).is_file():
            raise SystemExit(f'Missing native-image runtime: {library}')
    relocated = work / 'relocated'
    if relocated.exists():
        shutil.rmtree(relocated)
    relocated.mkdir()
    shutil.copy2(executable, relocated / executable.name)
    if not static_runtime:
        shutil.copytree(bundle, relocated / bundle.name)
    if windows:
        for library in deployed:
            shutil.copy2(executable.parent / library, relocated / library)
    result = relocated / executable.name
    libraries = relocated if windows else relocated / bundle.name
    for binary in (result, *(libraries / name for name in deployed)):
        if windows:
            check_pe_paths(binary, relocated)
            continue
        if platform.system() == 'Linux':
            check_elf_paths(binary, relocated)
            if static_runtime and any(name.startswith(('libjam', 'libc++', 'libunwind')) for name in elf_commands(binary)[1]):
                raise SystemExit('Static Jam image retains a collector/C++ runtime dependency')
            continue
        identity, dependencies, rpaths = load_commands(binary)
        if static_runtime and any(Path(name).name.startswith(('libjam', 'libc++', 'libunwind')) for name in dependencies):
            raise SystemExit('Static Jam image retains a collector/C++ runtime dependency')
        for path in (identity, *dependencies, *rpaths):
            if path and path.startswith('/') and not path.startswith(SYSTEM):
                raise SystemExit(f'{binary.name} retains a build-time load path: {path}')
    return result


runtime_names = runtime_libraries(home / 'lib/jam')


def run_executable(label, executable, arguments, expected):
    output = run(label, [executable, *(['-Djam.runtime.audit=true'] if windows else []), *arguments],
                 expected=expected, trace_libraries=True)
    bundle = executable.parent if windows else executable.with_name(executable.name + '.jam')
    if static_runtime:
        if any(name in output for name in ('libjam-vm.', 'jam-vm.dll')):
            raise SystemExit('Static Jam execution loaded a shared collector')
        if windows:
            for line in output.splitlines():
                if line.startswith('jam-loaded-library: '):
                    path = Path(line.removeprefix('jam-loaded-library: '))
                    if path.name.casefold() in {name.casefold() for name in runtime_names}:
                        if path.parent.resolve() != executable.parent.resolve():
                            raise SystemExit(f'Unexpected Microsoft runtime path: {path}')
    else:
        check_loaded_libraries(output, bundle, runtime_names)


executable = relocate(executable)
shutil.copy2(jni_library, executable.parent / jni_library.name)

for mode, expected in (
        ('image-roots', 'Jam Native Image sparse image roots passed'),
        ('heap', 'Jam Native Image heap passed'),
        ('heap-walk', 'Jam Native Image heap enumeration passed'),
        ('weak', 'Weak bridge passed:'),
        ('jni-weak', 'JNI weak globals passed:'),
        ('pin', 'Jam Native Image concurrent pin passed'),
        ('runtime', 'Jam Native Image runtime contracts passed'),
        ('continuations', 'Jam Native Image continuations passed'),
        ('isolates', 'Jam Native Image isolate lifecycle passed'),
        ('capacity', 'Jam Native Image pin capacity passed')):
    run_executable(mode, executable, ['-Xmx128m', '-Xmn32m',
                   *(['-Djava.library.path=' + str(executable.parent)] if mode == 'jni-weak' else []), mode], expected)
    print(f'Native Image {mode}: passed')

truffle_classes = work / 'truffle-classes'
truffle_classes.mkdir(exist_ok=True)
dependencies = [root / 'upstream/graal25' / suite / 'mxbuild/dists' / (name + '.jar')
                for suite, names in (
                    ('truffle', ('truffle-api', 'truffle-runtime', 'truffle-compiler')),
                    ('sdk', ('polyglot', 'collections', 'jniutils', 'nativebridge', 'nativeimage', 'word')))
                for name in names]
for dependency in dependencies:
    if not dependency.is_file():
        raise SystemExit(f'Missing Truffle build dependency: {dependency}; build GraalVM first.')
classpath = os.pathsep.join(map(str, (truffle_classes, jar, *dependencies)))
run('truffle-javac', [java_tool(home, 'javac'), '-cp', classpath, '-d', truffle_classes,
                      root / 't/substrate/truffle/TruffleSmoke.java'])
executable = work / ('truffle-smoke.exe' if windows else 'truffle-smoke')
run('truffle-image-build', [*image_options, '--macro:truffle-svm',
                           # Truffle's partial evaluator requires initialized guest classes.
                           '--initialize-at-build-time=' + ','.join(
                               path.stem for path in sorted(truffle_classes.glob('TruffleSmoke*.class'))),
                           '-J-Dpolyglot.engine.userResourceCache=' + str(scratch / 'truffle-cache'),
                           '--add-exports=org.graalvm.truffle.runtime/com.oracle.truffle.runtime=ALL-UNNAMED',
                           '--enable-native-access=ALL-UNNAMED', '-cp', classpath,
                           'TruffleSmoke', executable], timeout=1800,
    expected='Garbage collector: Jam')
amd64 = platform.machine().lower() in ('x86_64', 'amd64')
run_executable('truffle', relocate(executable), ['-Xmx512m', '-Xmn64m',
                 '-Dpolyglot.engine.BackgroundCompilation=false',
                 '-Dpolyglot.engine.CompilationFailureAction=Throw',
                 *(['check-masking'] if amd64 else [])],
    'Jam Native Image compiled Truffle consumer passed')
print('Native Image compiled Truffle: passed')
if amd64:
    run('masking-build-rejection', [*image_options, '-R:+MemoryMaskingAndFencing',
                                   '-cp', classpath, 'TruffleSmoke', work / 'unsupported-masking'],
        expected='The option is not supported when using Jam', reject=True)
    run('masking-runtime-rejection', [work / 'relocated' / executable.name, '-XX:+MemoryMaskingAndFencing'],
        expected='MemoryMaskingAndFencing is not supported when using Jam', reject=True)
    print('Unsupported memory masking: rejected at build time and runtime')
print('Relocated Jam executables passed.')
