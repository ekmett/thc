#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Retain compact FloatX4 evidence without redistributing large raw compiler dumps."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
capture = Path(sys.argv[1]).resolve()
destination = Path(sys.argv[2]).resolve()
runtime_revision = sys.argv[3]
subprocess.run(['git', 'diff', '--exit-code', runtime_revision, '--', 'src/main', 'build.gradle.kts'], cwd=root, check=True)
subprocess.run([sys.executable, str(root/'bench/experiments/floatx4-foundation/runtime-audit.py'),
                'check', str(root), str(capture)], check=True)
evidence = json.loads((capture/'evidence.json').read_text())
destination.mkdir(parents=True, exist_ok=False)
def copy(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
for result in evidence['results']:
    label = f"{result['stage']}-{result['backend']}-{result['entry']}"
    for field, name in (('log', 'run.log'), ('lir', 'final-lir.txt')):
        source = Path(result[field]['path'])
        target = destination/label/name
        copy(source, target)
        result[field]['path'] = str(target.relative_to(destination))
    graph = result['highTierGraph']
    result['highTierGraph'] = {k: graph[k] for k in
        ('ordinal', 'dumpId', 'group', 'name', 'graphType', 'nodes', 'edges', 'blocks', 'nodeClassCounts')}
for section in ('sources', 'runtimeJars'):
    for item in evidence[section]:
        item['path'] = str(Path(item['path']).relative_to(root))
for name in ('input-provenance.json', 'jdk-release.txt', 'java-version.txt', 'architecture.txt', 'oracle.tsv'):
    copy(capture/name, destination/name)
for field, name in (('inputProvenance', 'input-provenance.json'), ('jdk', 'jdk-release.txt')):
    evidence[field]['path'] = name
tests = {}
for mode in ('default', 'handoff'):
    directory = root/'build/test-results'/mode
    suites = [ET.parse(p).getroot() for p in directory.glob('TEST-*.xml')]
    tests[mode] = {k: sum(int(s.get(k, 0)) for s in suites) for k in ('tests', 'failures', 'errors', 'skipped')}
    assert tests[mode]['tests'] and all(tests[mode][k] == 0 for k in ('failures', 'errors', 'skipped'))
    tests[mode]['suites'] = len(suites)
    copy(directory/'TEST-thc.runtime.SimdFloatVectorTest.xml', destination/f'test-{mode}.xml')
evidence.update(runtimeRevision=runtime_revision, tests=tests, rawCaptureDirectory=str(capture),
    rawCapturePolicy='Raw BGV/CFG remain in the local capture directory; hashes are retained per result.')
(destination/'evidence.json').write_text(json.dumps(evidence, indent=2)+'\n')
files = sorted(p for p in destination.rglob('*') if p.is_file())
(destination/'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+str(p.relative_to(destination))+'\n' for p in files))
print(f'Retained {len(files)} evidence artifacts, {sum(p.stat().st_size for p in files)} bytes')
