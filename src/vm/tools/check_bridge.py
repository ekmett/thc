#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

"""Exercise the public JAR/native pair on Jam and unsupported JVM configurations."""

import os
from pathlib import Path
import subprocess
import sys
from platform_paths import jdk_home

root = Path(__file__).resolve().parents[1]
vm = Path(os.environ.get('JAM_JAVA', jdk_home() / 'bin/java')).resolve()
classes = root / 'build-bridge-test'
subprocess.run([sys.executable, str(root / 'tools/build_bridge.py'), '--java-home', str(vm.parent.parent)], check=True)
subprocess.run([str(vm.parent / 'javac'), '-cp', str(root / 'build/bridge/jam-vm.jar'),
                '-d', str(classes), str(root / 't/bridge/WeakBridgeSmoke.java')], check=True)
flags = ['-Xshare:off', '-Xms32m', '-Xmx32m', '--enable-native-access=ALL-UNNAMED',
         '-Djava.library.path=' + str(root / 'build/bridge/lib'), '-cp',
         os.pathsep.join((str(classes), str(root / 'build/bridge/jam-vm.jar')))]
cases = [
    ('jam', vm, ['-XX:+UnlockExperimentalVMOptions', '-XX:+UnlockDiagnosticVMOptions', '-XX:+UseJamGC',
                 '-XX:+VerifyBeforeGC', '-XX:+VerifyAfterGC'], [], 'Weak bridge passed'),
    ('epsilon', vm, ['-XX:+UnlockExperimentalVMOptions', '-XX:+UseEpsilonGC'],
     ['unavailable'], 'Weak bridge unavailable as expected'),
]
stock = os.environ.get('JAM_STOCK_JAVA')
if stock:
    cases.append(('stock', Path(stock), [], ['unavailable'], 'Weak bridge unavailable as expected'))
for name, launcher, options, arguments, expected in cases:
    result = subprocess.run([str(launcher), *flags, *options, 'WeakBridgeSmoke', *arguments],
                            capture_output=True, text=True, timeout=120)
    output = result.stdout + result.stderr
    (root / 'evidence').mkdir(exist_ok=True)
    (root / f'evidence/bridge-{name}.log').write_text(output + f'\nexit={result.returncode}\n')
    if result.returncode != 0 or expected not in output:
        raise SystemExit(f'{name} bridge check failed:\n{output[-6000:]}')
    print(f'{name} bridge: passed')
if not stock:
    print('Stock JVM check skipped; set JAM_STOCK_JAVA to a stock JDK 25 launcher.')
