#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Check selection, management identity, feature boundaries and original Epsilon."""
import os
from pathlib import Path
from platform_paths import NATIVE_BUILD
import subprocess
from platform_paths import jdk_home, java_tool
from build_bridge import build as build_bridge
from build_jni_test import build as build_jni_test

root = Path(__file__).resolve().parents[1]
vm = Path(os.environ.get('JAM_JAVA', jdk_home() / 'bin/java'))
home = vm.parent.parent
classes = root / 'build-registration-tests'
native = classes / 'native'
jar = root / 'build/bridge/jam-vm.jar'
(root / 'evidence').mkdir(exist_ok=True)
build_bridge(home)
build_jni_test(home, native, collector=True)
subprocess.run([str(java_tool(home, 'javac')), '-cp', str(jar), '-d', str(classes),
                *map(str, (root / 't/java' / name for name in
                           ('CollectorIdentitySmoke.java', 'HeapSmoke.java', 'JamWeak.java')))], check=True)
common = ['-Xshare:off', '-Xms32m', '-Xmx32m', '-XX:+UnlockExperimentalVMOptions', '-Xlog:gc',
          '--enable-native-access=ALL-UNNAMED',
          '-Djava.library.path=' + os.pathsep.join(map(str, (root / 'build/bridge/lib', NATIVE_BUILD, native))),
          '-cp', os.pathsep.join(map(str, (classes, jar)))]

def check(name, flags, args, expected, success=True, absent=()):
    result = subprocess.run([str(vm), *common, *flags, *args], cwd=root,
                            capture_output=True, text=True, timeout=120)
    output = result.stdout + result.stderr
    (root / 'evidence' / ('jam-registration-' + name + '.log')).write_text(
        output + f'\nexit={result.returncode}\n')
    if ((result.returncode == 0) != success or any(item not in output for item in expected)
            or any(item in output for item in absent)):
        raise SystemExit(f'{name} failed (exit={result.returncode}):\n{output[-5000:]}')
    print(f'{name}: passed')

check('jam', ['-XX:+UseJamGC'], ['CollectorIdentitySmoke', 'Jam'],
      ['Using Jam', 'CollectorIdentitySmoke passed: Jam Heap'], absent=['Using Epsilon'])
check('epsilon', ['-XX:+UseEpsilonGC'], ['CollectorIdentitySmoke', 'Epsilon'],
      ['Using Epsilon', 'CollectorIdentitySmoke passed: Epsilon Heap'], absent=['Jam collector:'])
check('epsilon-allocation', ['-XX:+UseEpsilonGC'], ['HeapSmoke', 'allocation'],
      ['OutOfMemoryError'], success=False, absent=['allocation smoke passed'])
check('mutual-exclusion', ['-XX:+UseJamGC', '-XX:+UseEpsilonGC'], ['-version'],
      ['Multiple garbage collectors selected'], success=False)
check('jvmci-enabled', ['-XX:+UseJamGC', '-XX:+EnableJVMCI', '-XX:-UseJVMCICompiler',
      '--add-modules=jdk.internal.vm.ci',
      '--add-exports=jdk.internal.vm.ci/jdk.vm.ci.hotspot=ALL-UNNAMED'],
      ['CollectorIdentitySmoke', 'Jam', 'JVMCI'],
      ['JVMCI runtime initialized with Jam identity', 'CollectorIdentitySmoke passed: Jam Heap'])
check('jvmci-compiler-selection', ['-XX:+UseJamGC', '-XX:+EnableJVMCI', '-XX:+UseJVMCICompiler',
      '-XX:-UseJVMCINativeLibrary'], ['-version'], ['Using Jam'])
check('encoding-rejected', ['-XX:+UseJamGC', '-XX:-UseCompressedOops'], ['-version'],
      ['requires compressed oops'], success=False)
