#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Check Jam under libgraal and require installed JVMCI code for the barrier probes."""

import argparse
import os
from pathlib import Path
from platform_paths import NATIVE_BUILD
import subprocess
import xml.etree.ElementTree as ET
from build_jni_test import build as build_jni_test

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java-home', type=Path, default=root / 'build/graalvm')
parser.add_argument('--unsupported-libgraal', type=Path,
                    help='Check that an unmodified matching libgraal directory is rejected')
options = parser.parse_args()
home = options.java_home.resolve()
mode = 'libgraal'
classes = root / 'build-graal-tests'
jar = home / 'lib/jam/jam-vm.jar'
evidence = root / 'evidence'
evidence.mkdir(exist_ok=True)
native = classes / 'native'
build_jni_test(home, native)
build_jni_test(home, native, collector=True)
subprocess.run([str(home / 'bin/javac'), '-cp', str(jar), '-d', str(classes),
                *map(str, sorted((root / 't/java').glob('*.java'))),
                str(root / 't/bridge/WeakBridgeSmoke.java'),
                str(root / 't/bridge/JNIWeakSmoke.java')], check=True)
exports = ['--add-modules=jdk.internal.vm.ci',
           '--add-exports=jdk.internal.vm.ci/jdk.vm.ci.hotspot=ALL-UNNAMED']
subprocess.run([str(home / 'bin/javac'), *exports, '-d', str(classes),
                str(root / 't/graal/InvalidationReasonSmoke.java')], check=True)
flags = [
    '-Xshare:off', '-Xms32m', '-Xmx32m', '-XX:+UnlockExperimentalVMOptions', '-XX:+UnlockDiagnosticVMOptions', '-XX:+UseJamGC',
    '-XX:+EnableJVMCI', '-XX:+UseJVMCICompiler', '-XX:+UseJVMCINativeLibrary',
    '-XX:+VerifyBeforeGC', '-XX:+VerifyAfterGC', '-XX:JamWorkers=4',
    '-Xbatch', '-XX:-TieredCompilation', '-XX:CompileThreshold=1000',
    '-XX:+LogCompilation', '-Xlog:gc=debug',
    '-Djdk.graal.CompilationFailureAction=ExitVM', '-Djdk.graal.ShowConfiguration=info',
    '-Djdk.graal.DumpPath=' + str(evidence / 'graal-dumps'),
    '--enable-native-access=ALL-UNNAMED',
    '-Djava.library.path=' + os.pathsep.join(map(str, (home / 'lib/jam', NATIVE_BUILD, native))),
    '-cp', os.pathsep.join((str(classes), str(jar))),
]
cases = [
    ('InvalidationReasonSmoke', [*exports,
     '--add-opens=jdk.internal.vm.ci/jdk.vm.ci.hotspot=ALL-UNNAMED'], [],
     'InvalidationReasonSmoke passed:'),
    ('HeapSmoke', [], ['walk'], 'allocation checks passed'),
    ('JvmHeapWalkSmoke', ['-Xms128m', '-Xmx128m', '-Djam.heap.walk.first=jvmti', '-XX:-VerifyBeforeGC', '-XX:-VerifyAfterGC', '-XX:-VerifyBeforeExit'],
     ['allocate', 'array', 'duplicate'], 'JvmHeapWalkSmoke passed:'),
    ('WeakSmoke', [], [], 'finalization checks passed'),
    ('BoundarySmoke', [], [], 'phantom checks passed'),
    ('GenerationSmoke', [], ['store', 'arrayStore', 'unsafeStore', 'largeStore', 'copy'],
     'GenerationSmoke passed:'),
    ('CompiledBarrierSmoke', ['-Xms128m', '-Xmx128m', '-XX:JamYoungSize=8m'],
     ['compare', 'exchange', 'initialize', 'duplicate'], 'CompiledBarrierSmoke passed:'),
    ('GenerationCapacitySmoke', ['-Xms64m', '-Xmx64m', '-XX:JamYoungSize=32m', '-XX:JamPromoteEvery=1000'],
     [], 'GenerationCapacitySmoke passed:'),
    ('WeakBridgeSmoke', [], [], 'Weak bridge passed:'),
    ('JNIWeakGenerations', ['-Xcheck:jni'], [], 'JNI weak globals passed:'),
]
for name, extra, methods, expected in cases:
    log = evidence / f'graal-{mode}-{name}.xml'
    result = subprocess.run([str(home / 'bin/java'), *flags, *extra,
                             '-XX:LogFile=' + str(log),
                             *[f'-XX:CompileCommand=dontinline,{name}::{method}' for method in methods],
                             name], cwd=root, text=True, capture_output=True, timeout=300)
    output = result.stdout + result.stderr
    (evidence / f'graal-{mode}-{name}.log').write_text(output + f'\nexit={result.returncode}\n')
    if result.returncode or expected not in output:
        raise SystemExit(f'{mode} {name} failed (exit {result.returncode}):\n{output[-6000:]}')
    compilation = ET.parse(log)
    required = {name + ' ' + method for method in methods}
    probes = {entry.get('compile_id'): entry.get('method', '').split(' (', 1)[0]
              for entry in compilation.iter('nmethod')
              if entry.get('compiler', '').lower() == 'jvmci'
              and entry.get('compile_kind') != 'osr'
              and entry.get('method', '').split(' (', 1)[0] in required}
    missing = required - set(probes.values())
    if missing:
        raise SystemExit(f'{mode} {name} did not install Graal code for: {sorted(missing)}')
    invalid = {probes[entry.get('compile_id')]
               for entry in compilation.iter()
               if entry.tag in ('make_not_entrant', 'deoptimized', 'uncommon_trap')
               and entry.get('compile_id') in probes}
    if invalid:
        raise SystemExit(f'{mode} {name} invalidated or deoptimized required Graal probes: {sorted(invalid)}')
    print(f'{mode} {name}: passed' + (f'; {len(methods)} required methods compiled by Graal' if methods else ''))

if options.unsupported_libgraal:
    result = subprocess.run([
        str(home / 'bin/java'), *flags, '-XX:+UseJVMCINativeLibrary',
        '-XX:JVMCILibPath=' + str(options.unsupported_libgraal.resolve()),
        '-XX:LogFile=' + str(evidence / 'graal-unsupported.xml'),
        '-XX:CompileCommand=compileonly,HeapSmoke::walk', 'HeapSmoke'],
        cwd=root, text=True, capture_output=True, timeout=120)
    output = result.stdout + result.stderr
    (evidence / 'graal-unsupported.log').write_text(output + f'\nexit={result.returncode}\n')
    if result.returncode == 0 or 'JVMCI compiler does not support Jam GC' not in output:
        raise SystemExit(f'Unmodified libgraal was not rejected at code installation:\n{output[-6000:]}')
    installed = [entry for entry in ET.parse(evidence / 'graal-unsupported.xml').iter('nmethod')
                 if entry.get('compiler', '').lower() == 'jvmci']
    if installed:
        raise SystemExit('Unmodified libgraal installed code before rejection.')
    print('Unmodified libgraal rejected before installing Java code.')
