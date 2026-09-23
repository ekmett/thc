"""Build a metadata report from retained actual JUnit XMLs and complete archives."""
import hashlib
import argparse
import json
from pathlib import Path
import subprocess
import tarfile
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
root = parser.parse_args().root.resolve()
retained = root / 'bench/experiments/int32x4-multiply/evidence-x86_64'
result = dict(runtimeRevision='597ed24ee8835606437a7cdeb313e28b726d4762',
    fixtureRows=1722, compiledInvocationsPerMode=13776, exactGuestEntriesPerMode=21232,
    environment='/home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh',
    resourceGate='/home/ekmett/.codex/worktrees/98d0/cult/tools/resource_run.py --build-dir ' + str(root/'build') + ' --',
    gradleUserHome='/home/ekmett/ai/thc-sequence-current-main-01a0cdeb/.gradle-user-home',
    modes={})
identities = {}
for mode in ('default', 'handoff'):
    paths = sorted((root / 'build/int32x4-multiply-validation' / mode).glob('TEST-*.xml'))
    assert len(paths) == 100, (mode, len(paths))
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    records = []
    cases = []
    archive = retained / (mode + '-junit.tar.gz')
    with tarfile.open(archive) as source:
        members = {Path(member.name).name: member for member in source.getmembers() if member.isfile()}
        assert set(members) == {path.name for path in paths}
        for path in paths:
            data = path.read_bytes()
            assert source.extractfile(members[path.name]).read() == data
            xml = ET.fromstring(data)
            assert len(xml.findall('testcase')) == int(xml.attrib['tests'])
            for name in totals:
                totals[name] += int(xml.attrib[name])
            records.append(dict(path=str(path.relative_to(root)), sha256=hashlib.sha256(data).hexdigest(), **xml.attrib))
            cases.extend((case.attrib['classname'], case.attrib['name']) for case in xml.findall('testcase'))
            assert not xml.findall('.//failure') and not xml.findall('.//error') and not xml.findall('.//skipped')
    assert totals == dict(tests=487, failures=0, errors=0, skipped=0), totals
    assert len(cases) == len(set(cases)) == 487
    identities[mode] = set(cases)
    command = ['scripts/gradle.sh', '--offline', '--no-daemon', '--max-workers=4', 'test', 'installDist']
    if mode == 'handoff':
        command.append('--rerun-tasks')
    log = (root/'build'/('test-' + mode + '-int32x4-multiply.log')).read_text()
    assert 'BUILD SUCCESSFUL' in log and '> Task :test\n' in log
    if mode == 'handoff':
        assert '12 actionable tasks: 12 executed' in log
        assert log.count('Picked up JAVA_TOOL_OPTIONS: -Dthc.handoffSlabs=true') >= 2
    result['modes'][mode] = dict(suites=len(paths), totals=totals, exitStatus=0,
        command=command, JAVA_TOOL_OPTIONS='-Dthc.handoffSlabs=true' if mode == 'handoff' else None,
        archive=dict(path=archive.name, sha256=hashlib.sha256(archive.read_bytes()).hexdigest()), files=records)
assert identities['default'] == identities['handoff']
snapshot = json.loads((retained/'runtime-snapshot.json').read_text())
assert snapshot['sourceRevision'] == result['runtimeRevision']
for item in snapshot['sources'] + snapshot['runtimeJars'] + snapshot['jdkFiles']:
    path = Path(item['path'])
    assert hashlib.sha256(path.read_bytes()).hexdigest() == item['sha256'], str(path)
    if item in snapshot['sources']:
        blob = subprocess.check_output(['git', 'show', result['runtimeRevision'] + ':' + str(path.relative_to(root))], cwd=root)
        assert hashlib.sha256(blob).hexdigest() == item['sha256']
result['snapshotVerifiedAfterForcedRebuild'] = {key: len(snapshot[key]) for key in ('sources', 'runtimeJars', 'jdkFiles')}
print(json.dumps(result, indent=2))
