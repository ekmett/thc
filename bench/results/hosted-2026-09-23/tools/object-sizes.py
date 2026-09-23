#!/usr/bin/env python3
"""Build/run a load-only shallow-size agent, only after a coordinated CPU grant."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('frozen', type=Path)
parser.add_argument('out', type=Path, help='new output directory')
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--backend', choices=('ast', 'bytecode'), default='bytecode')
parser.add_argument('--jvm-option', action='append', default=[])
args = parser.parse_args()
frozen, out = args.frozen.resolve(), args.out.resolve()
assert not out.exists(), 'Output directory must be new'
tools = Path(__file__).resolve().parent
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = json.loads((frozen / 'manifest.json').read_text())
for record in manifest['files']:
    assert sha(frozen / record['path']) == record['sha256'], record['path']
jars = sorted((frozen / 'lib').glob('*.jar'))
assert jars
sources = [tools / 'ObjectSizesAgent.java', tools / 'ObjectSizes.java']
inputs = [*sources, Path(__file__).resolve(), frozen / 'manifest.json', args.java_home / 'release']
config = {'recordedAtUtc': datetime.now(timezone.utc).isoformat(), 'scope': 'Load-only shallow object sizes; no workload timing or guest compilation',
          'backend': args.backend, 'jvmOptions': args.jvm_option, 'frozenManifest': manifest,
          'inputs': [{'path': str(path), 'sha256': sha(path)} for path in inputs], 'commands': []}
out.mkdir(parents=True)
classes = out / 'classes'
classes.mkdir()
def save():
    (out / 'config.json').write_text(json.dumps(config, indent=2) + '\n')
def run(command, name):
    command = list(map(str, command))
    record = {'argv': command, 'stdout': name + '.out', 'stderr': name + '.err'}
    config['commands'].append(record)
    save()
    with (out / record['stdout']).open('w') as stdout, (out / record['stderr']).open('w') as stderr:
        result = subprocess.run(command, stdout=stdout, stderr=stderr, timeout=120)
    record['returncode'] = result.returncode
    save()
    assert result.returncode == 0, (name, result.returncode)
cp = os.pathsep.join(map(str, jars))
run([args.java_home / 'bin/javac', '-cp', cp, '-d', classes, *sources], 'javac')
(out / 'agent.mf').write_text('Premain-Class: ObjectSizesAgent\n\n')
agent = out / 'object-sizes-agent.jar'
run([args.java_home / 'bin/jar', '--create', '--file', agent, '--manifest', out / 'agent.mf', '-C', classes, 'ObjectSizesAgent.class'], 'jar')
run([args.java_home / 'bin/java', *args.jvm_option, '--enable-native-access=ALL-UNNAMED', '-javaagent:' + str(agent),
     '-cp', str(classes) + os.pathsep + cp, 'ObjectSizes', frozen / 'map/modules.txt', args.backend], 'sizes')
for record in manifest['files']:
    assert sha(frozen / record['path']) == record['sha256'], record['path']
for record in config['inputs']:
    assert sha(Path(record['path'])) == record['sha256'], record['path']
config['validatedInputsUnchanged'] = True
save()
print('Measured shallow sizes:', out / 'sizes.out')
