import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile

root = Path('/home/ekmett/ai/thc-pinned-addresses-01a0cdeb')
out = root / 'bench/experiments/pinned-addresses/evidence-x86_64'
base = '9a7fef75cc4ce44cb2b1031c61c92aadffb78859'
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
assert head == '3e2c124e039926c165e907b034cc80bb9197b50b', head

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def check(path, expected):
    actual = digest(path)
    if actual != expected:
        raise ValueError((str(path), expected, actual))

pin = json.loads((root / 'build/pinned-addresses/manifest.json').read_text())
md5 = json.loads((root / 'build/managed-md5-native/provenance.json').read_text())
verified = []
for group in ['inputHashes', 'artifactHashes']:
    for name, expected in pin[group].items():
        check(root / name, expected)
        verified.append({'path': name, 'sha256': expected, 'manifest': 'pinned/' + group})
for group in ['sources', 'artifacts', 'tools']:
    for record in md5[group]:
        check(root / record['path'], record['sha256'])
        verified.append(dict(record, manifest='md5/' + group))
for prefix in ['ghcBinary', 'ghcLauncher']:
    check(Path(pin[prefix + 'Path']), pin[prefix + 'Sha256'])
    verified.append({'path': pin[prefix + 'Path'], 'sha256': pin[prefix + 'Sha256'],
                     'manifest': 'pinned/' + prefix})
assert pin['nativeRows'] == pin['modelRows'] == 7269
assert pin['strictAccepted'] is True
assert (md5['cases'], md5['caseRows'], md5['aliasRows'], md5['hashlibCases']) == (558, 2232, 15, 540)
assert md5['independentModelMatched'] is True

native = out / 'native'
native.mkdir(exist_ok=False)
names = sorted(set(pin['inputHashes']) | set(pin['artifactHashes']) |
               {'build/pinned-addresses/manifest.json'})
with tarfile.open(native / 'pinned-inputs-and-artifacts.tar.gz', 'w:gz') as archive:
    for name in names:
        archive.add(root / name, arcname=name, recursive=False)
with tarfile.open(native / 'managed-md5-inputs-and-artifacts.tar.gz', 'w:gz') as archive:
    names = sorted({r['path'] for group in ['sources', 'artifacts'] for r in md5[group]
                    if not Path(r['path']).is_absolute()} |
                   {'build/managed-md5-native/provenance.json'})
    for name in names:
        archive.add(root / name, arcname=name, recursive=False)
    for record in md5['sources']:
        if Path(record['path']).is_absolute():
            archive.add(record['path'], arcname='external-inputs/' + Path(record['path']).name,
                        recursive=False)

prior = root / 'build/managed-md5-native.previous-jv7qxdpx/managed-md5-native'
old_md5 = json.loads((prior / 'provenance.json').read_text())
for record in old_md5['artifacts']:
    check(prior / Path(record['path']).name, record['sha256'])
assert len(list(prior.iterdir())) == 18
with tarfile.open(native / 'preserved-first-md5-attempt.tar.gz', 'w:gz') as archive:
    archive.add(prior, arcname='managed-md5-native')

(out / 'verified-native-hashes.json').write_text(json.dumps(verified, indent=2) + '\n')
source_paths = sorted(set(subprocess.check_output(
    ['git', 'diff', '--name-only', base, head], cwd=root, text=True).splitlines()) |
    set(pin['inputHashes']))
with (out / 'tested-source-snapshot.tar.gz').open('xb') as output:
    subprocess.run(['git', 'archive', '--format=tar.gz', head, '--', *source_paths],
                   cwd=root, stdout=output, check=True)
ancestry = subprocess.check_output(['git', 'log', '--reverse', '--format=%H %s',
                                    base + '..' + head], cwd=root, text=True)
(out / 'source-ancestry.txt').write_text(ancestry)
tool_commands = [['uname', '-a'], ['lscpu'], ['java', '--version'],
                 ['ghc', '--numeric-version'], ['ghc-pkg', '--version'],
                 ['python3', '--version'], ['gcc', '--version']]
tool_records = []
for command in tool_commands:
    result = subprocess.run(command, cwd=root, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, check=False)
    tool_records.append({'command': command, 'exitCode': result.returncode,
                         'output': result.stdout})
    if result.returncode:
        raise ValueError(tool_records[-1])
(out / 'tool-identities.json').write_text(json.dumps(tool_records, indent=2) + '\n')
(out / 'source-identity.json').write_text(json.dumps({
    'base': base, 'testedSource': head,
    'runtime': '2cbf1aa5f369658100fd7137a3725ba9dd85a639',
    'tree': subprocess.check_output(['git', 'rev-parse', head + '^{tree}'], cwd=root, text=True).strip(),
    'host': 'eak-quartus', 'javaHome': os.environ.get('JAVA_HOME'),
    'priorAttempts': 'Earlier dirty development attempts are historical failures, not claims of this source identity.',
}, indent=2) + '\n')
print(json.dumps({'verifiedHashes': len(verified), 'priorMd5ArtifactsVerified': len(old_md5['artifacts']),
                  'sourcePaths': len(source_paths), 'testedSource': head}))
