#!/usr/bin/env python3
"""Serial local validation; run only after the parent grants this CPU lane."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
repo = root.parents[1]
java = Path('/Users/ekmett/cadenza/.toolchains/graalvm-25.3.4.1+1.1/Contents/Home')
results = root / 'validation'
resume = '--resume-freeze' in sys.argv
if not resume: results.mkdir(exist_ok=False)
env = dict(os.environ, JAVA_HOME=str(java), THC_GRADLE_USER_HOME=str(repo / '.gradle-user-home'))
env.pop('JAVA_TOOL_OPTIONS', None)
summary = json.loads((results / 'summary.json').read_text()) if resume else {
    'startedAtUtc': datetime.now(timezone.utc).isoformat(), 'base': 'e9c3db9f2c8710f65281ee10e1521a25de7a7566', 'runs': []}

def save():
    (results / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')

def run(command, label, overrides=None):
    record = {'name': label, 'argv': list(map(str, command)), 'javaToolOptions': (overrides or {}).get('JAVA_TOOL_OPTIONS', ''),
              'startedAtUtc': datetime.now(timezone.utc).isoformat()}
    summary['runs'].append(record)
    save()
    with (results / (label + '.log')).open('w') as out:
        process = subprocess.run(record['argv'], cwd=root, env=dict(env, **(overrides or {})), stdout=out, stderr=subprocess.STDOUT)
    record['returncode'] = process.returncode
    record['endedAtUtc'] = datetime.now(timezone.utc).isoformat()
    save()
    if process.returncode:
        print('FAILED:', label, 'see', results / (label + '.log'), flush=True)
        sys.exit(process.returncode)
    return record

if not resume:
    for label, flags in [
        ('default', '-Dthc.classOwnedLayouts=false'),
        ('owned', '-Dthc.classOwnedLayouts=true'),
        ('owned-compact-checked', '-Dthc.classOwnedLayouts=true -Dthc.constructorClassIdentity=true -Dthc.boxedValueCache=true -XX:+UseCompactObjectHeaders'),
        ('owned-compact-unchecked', '-Dthc.classOwnedLayouts=true -Dthc.constructorClassIdentity=true -Dthc.boxedValueCache=true -Dthc.staticShapeUnchecked=true -XX:+UseCompactObjectHeaders'),
    ]:
        tasks = ['test', 'installDist'] if label == 'default' else ['test', '--rerun']
        record = run([root / 'scripts/gradle.sh', *tasks, '--no-daemon'], label, {'JAVA_TOOL_OPTIONS': flags})
        destination = results / (label + '-xml')
        destination.mkdir()
        totals = dict(tests=0, failures=0, errors=0, skipped=0)
        for source in sorted((root / 'build/test-results/test').glob('TEST-*.xml')):
            shutil.copy2(source, destination / source.name)
            suite = ET.parse(source).getroot()
            for key in totals:
                totals[key] += int(suite.attrib.get(key, 0))
        record['tests'] = totals
        save()
        assert totals['tests'] >= 157 and not (totals['failures'] or totals['errors'] or totals['skipped']), totals
        print(label, totals, flush=True)

    classes = root / 'build/classes/kotlin/main'
    for name in ['DataValue', 'LayoutDataValue', 'ClassOwnedLayouts']:
        run([java / 'bin/javap', '-p', '-c', '-classpath', classes, 'thc.runtime.' + name], name + '-bytecode')

frozen = root / 'frozen'
if not resume:
    frozen.mkdir()
    shutil.copytree(root / 'build/install/thc/lib', frozen / 'lib')
    shutil.copytree(root / 'src', frozen / 'src')
    shutil.copytree(repo / 'work/boxed-value-cache-v7/map', frozen / 'map')
modules = frozen / 'map/modules.txt'
modules.chmod(modules.stat().st_mode | 0o200)
modules.write_text(''.join(str(frozen / 'map' / Path(line).name) + '\n' for line in modules.read_text().splitlines() if line.strip()))
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = {'base': summary['base'], 'createdAtUtc': datetime.now(timezone.utc).isoformat(),
            'status': 'Isolated class-owned-layout prototype; default false; validated source and libraries; no throughput claim',
            'testResults': summary, 'files': [{'path': str(path.relative_to(frozen)), 'sha256': sha(path)}
                for path in sorted(frozen.rglob('*')) if path.is_file()]}
(frozen / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
summary['frozen'] = str(frozen)
save()

probe = repo / 'bench/results/constructor-class/object-sizes/object-sizes.py'
for label, flags in [
    ('off-normal', ['-Dthc.classOwnedLayouts=false', '-XX:-UseCompactObjectHeaders']),
    ('on-normal', ['-Dthc.classOwnedLayouts=true', '-XX:-UseCompactObjectHeaders']),
    ('off-compact', ['-Dthc.classOwnedLayouts=false', '-XX:+UseCompactObjectHeaders']),
    ('on-compact', ['-Dthc.classOwnedLayouts=true', '-XX:+UseCompactObjectHeaders']),
]:
    run([sys.executable, probe, frozen, results / ('sizes-' + label), '--java-home', java,
         *['--jvm-option=' + flag for flag in flags]], 'sizes-' + label)
summary['completedAtUtc'] = datetime.now(timezone.utc).isoformat()
save()
print('Validation and actual factory size probes complete:', results, flush=True)
