# SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Compile fresh and audit the VM finalizer model theorem dependencies."""
from pathlib import Path
import hashlib
import os
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parent
log = []


def run(args, env=None):
    log.append('$ ' + ' '.join(map(str, args)))
    result = subprocess.run(args, cwd=root, env=env, text=True, capture_output=True)
    log.extend([result.stdout + result.stderr, f'exit: {result.returncode}\n'])
    if result.returncode:
        (root / 'verification.txt').write_text('\n'.join(log))
        raise SystemExit(result.returncode)
    return result.stdout


version = run(['lean', '--version'])
assert 'version 4.24.0' in version, 'Use the pinned lean-toolchain.'
for name in ['Lifecycle', 'Closure', 'Tokens', 'Batch']:
    assert not re.search(r'\b(sorry|axiom|native_decide)\b', (root / (name + '.lean')).read_text()), name
with tempfile.TemporaryDirectory(prefix='jam-finalizer-lean-') as build:
    env = dict(os.environ, LEAN_PATH=build)
    for name in ['Lifecycle', 'Closure', 'Tokens', 'Batch']:
        run(['lean', '-DwarningAsError=true', '-o', str(Path(build) / (name + '.olean')),
             str(root / (name + '.lean'))], env)
    audits = run(['lean', '-DwarningAsError=true', str(root / 'Check.lean')], env)
    lines = [s for s in audits.splitlines() if 'depends on axioms:' in s or 'does not depend on any axioms' in s]
    assert len(lines) == 25, audits
    for line in lines:
        if 'depends on axioms:' in line:
            names = line.split('[', 1)[1].rsplit(']', 1)[0]
            assert set(names.replace(' ', '').split(',')) <= {'propext', 'Classical.choice', 'Quot.sound'}, line
log.append('SHA256 of proof and reproduction inputs:')
for p in sorted(root.iterdir()):
    if p.suffix == '.lean' or p.name == 'check.py':
        log.append(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name)
(root / 'verification.txt').write_text('\n'.join(log) + '\n')
print('PASS: four Lean modules; warnings as errors; 25 axiom audits.')
