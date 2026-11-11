#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Exercise the VM, both generations and interpreter/C1/C2 barriers."""

import os
from pathlib import Path
from platform_paths import NATIVE_BUILD
import subprocess
import sys
from platform_paths import jdk_home, java_tool
from build_jni_test import build as build_jni_test

root = Path(__file__).resolve().parents[1]
vm = Path(os.environ.get('JAM_JAVA', jdk_home() / 'bin/java'))
javac = Path(os.environ.get('JAM_JAVAC', java_tool(vm.parent.parent, 'javac')))
prefix = os.environ.get('JAM_EVIDENCE_PREFIX', 'jam')
classes = root / 'build-java-tests'
evidence = root / 'evidence'
classes.mkdir(exist_ok=True)
evidence.mkdir(exist_ok=True)
subprocess.run([sys.executable, str(root / 'tools/build_bridge.py'), '--java-home',
                str(javac.parent.parent)], check=True)
jar = root / 'build/bridge/thc-vm.jar'
native = classes / 'native'
build_jni_test(javac.parent.parent, native)
build_jni_test(javac.parent.parent, native, collector=True)
subprocess.run([str(javac), '-cp', str(jar), '-d', str(classes),
                *map(str, sorted((root / 't/java').glob('*.java'))),
                str(root / 't/bridge/JNIWeakSmoke.java')], check=True)
flags = ['-Xshare:off', '-Xms32m', '-Xmx32m', '-XX:+UnlockExperimentalVMOptions', '-XX:+UnlockDiagnosticVMOptions', '-XX:+UseJamGC',
         '-XX:JamWorkers=4', '-XX:+VerifyBeforeGC', '-XX:+VerifyAfterGC', '-Xlog:gc',
         '--enable-native-access=ALL-UNNAMED',
         '-Djava.library.path=' + os.pathsep.join(map(str, (root / 'build/bridge/lib', NATIVE_BUILD, native))),
         '-cp', os.pathsep.join((str(classes), str(jar)))]


def run(name, test, options=()):
    log = evidence / f'{prefix}-{name}.log'
    with log.open('w') as output:
        result = subprocess.run([str(vm), *flags, *options, test], cwd=root,
                                stdout=output, stderr=subprocess.STDOUT, timeout=300)
    lines = log.read_text().splitlines()
    if result.returncode:
        raise SystemExit(f'{name} failed (exit {result.returncode}):\n' + '\n'.join(lines[-50:]))
    print('\n'.join(lines[-2:]), flush=True)


for test in ('HeapSmoke', 'WeakSmoke', 'BoundarySmoke'):
    run(test, test)
run('CloneBarrierSmoke-c2', 'CloneBarrierSmoke',
    ['-Xbatch', '-XX:-TieredCompilation', '-XX:CompileThreshold=100',
     '-XX:CompileCommand=dontinline,CloneBarrierSmoke::copy'])
for mode, compiler in (
        ('interpreter', ['-Xint']),
        ('c1', ['-Xbatch', '-XX:TieredStopAtLevel=1', '-XX:+PrintCompilation']),
        ('c2', ['-Xbatch', '-XX:-TieredCompilation', '-XX:CompileThreshold=1000', '-XX:+PrintCompilation'])):
    methods = ('store', 'arrayStore', 'unsafeStore', 'largeStore', 'copy')
    run(f'GenerationSmoke-{mode}', 'GenerationSmoke', [*compiler,
        f'-XX:LogFile={evidence / (prefix + "-compiler-" + mode + ".log")}',
        *('-XX:CompileCommand=dontinline,GenerationSmoke::' + method for method in methods)])
    run(f'CompiledBarrierSmoke-{mode}', 'CompiledBarrierSmoke', [*compiler,
        '-Xms128m', '-Xmx128m', '-XX:JamYoungSize=8m', '-XX:CompileCommand=dontinline,CompiledBarrierSmoke::*'])
    run(f'JNIWeakGenerations-{mode}', 'JNIWeakGenerations', [*compiler, '-Xcheck:jni'])
    for first in ('histogram', 'jvmti', 'legacy', 'locks'):
        run(f'JvmHeapWalkSmoke-{mode}-{first}', 'JvmHeapWalkSmoke', [*compiler,
            '-Xms128m', '-Xmx128m', '-XX:-VerifyBeforeGC', '-XX:-VerifyAfterGC', '-XX:-VerifyBeforeExit',
            f'-Dthc.heap.walk.first={first}', '-XX:CompileCommand=dontinline,JvmHeapWalkSmoke::*'])
run('GenerationCapacitySmoke', 'GenerationCapacitySmoke',
    ['-Xms64m', '-Xmx64m', '-XX:JamYoungSize=32m', '-XX:JamPromoteEvery=1000'])
