#!/usr/bin/env python3
"""Recheck the published cache experiment without Java or original work directories."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
root = Path(__file__).resolve().parent
manifest = json.loads((root / 'manifest.json').read_text())
for item in manifest['files']:
    path = root / item['path']
    assert path.stat().st_size == item['bytes'], path
    assert hashlib.sha256(path.read_bytes()).hexdigest() == item['sha256'], path
spec = importlib.util.spec_from_file_location('recorded_harness', root / 'tools/compare-map-runtimes.py')
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)
config = json.loads((root / 'throughput/run-config.json').read_text())
summary = json.loads((root / 'throughput/summary.json').read_text())
cycle = config['oracle']['cycleChecksumSigned64']
assert cycle == harness.signed64(sum(config['oracle']['values']))
assert config['provenance']['runtimeJars']['baseline'] == config['provenance']['runtimeJars']['candidate']
assert config['moduleManifests']['baseline'] == config['moduleManifests']['candidate']
assert config['backends'] == {'baseline': 'bytecode', 'candidate': 'bytecode'}
assert config['forks'] == 3 and config['samples'] == 5
windows = 0
for engine in ('baseline', 'candidate', 'native'):
    all_rows = []
    for fork in range(1, 4):
        rows = harness.read_windows(root / f'throughput/{engine}-{fork}.tsv', config['inputBase'], cycle)
        for row in rows:
            row['fork'] = fork
        all_rows.extend(rows)
        windows += len(rows)
        if engine != 'native':
            harness.validate_jvm_log(root / f'throughput/{engine}-{fork}.log', cycle, 'bytecode', 'on')
            command = next(c['argv'] for c in config['commands'] if c['engine'] == engine and c['fork'] == fork)
            assert '-Dthc.boxedValueCache=' + ('false' if engine == 'baseline' else 'true') in command
            for fixed in ('-Dthc.constructorClassIdentity=false', '-Dthc.staticShapeUnchecked=false', '-XX:-UseCompactObjectHeaders'):
                assert fixed in command
    assert harness.engine_summary(all_rows) == summary['engines'][engine], engine
assert windows == summary['windowCount'] == 45
for fork in range(1, 4):
    pair = [next(c['argv'] for c in config['commands'] if c['engine'] == e and c['fork'] == fork) for e in ('baseline', 'candidate')]
    assert [[v for v in cmd if not v.startswith('-Dthc.boxedValueCache=')] for cmd in pair][0] == \
           [[v for v in cmd if not v.startswith('-Dthc.boxedValueCache=')] for cmd in pair][1]
for folder, expected in [('default', 153), ('enabled', 153), ('combined-array', 41), ('focused', 5)]:
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    for xml in (root / 'validation/tests' / folder).glob('TEST-*.xml'):
        result = ET.parse(xml).getroot()
        for key in totals:
            totals[key] += int(result.get(key, 0))
    assert totals == dict(tests=expected, failures=0, errors=0, skipped=0), (folder, totals)
fixture = ET.parse(root / 'validation/fixture-cleanup/build/test-results/test/TEST-thc.runtime.BoxedValueCacheTest.xml').getroot()
assert {k: int(fixture.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')} == dict(tests=5, failures=0, errors=0, skipped=0)
for mode, expected in [('off', 8329466.5), ('on', 8115653.5)]:
    validation = json.loads((root / f'allocation/{mode}/validation.json').read_text())
    assert validation['installedCode'] and validation['warmCalls'] >= 12000
    assert not validation['forbiddenCompilationEvents']
    assert validation['diagnostics']['unsupportedTraps'] == 0
    rows = (root / f'allocation/{mode}/run.tsv').read_text().splitlines()
    assert len(rows) == 3
    for index, line in enumerate(rows, 1):
        sample, calls, base, checksum, allocated = map(int, line.split('\t'))
        assert sample == index and calls == 256 and base == config['inputBase']
        assert checksum == harness.signed64(cycle * (calls // 16))
        assert allocated / calls == expected
        assert validation['allocationSamples'][index - 1] == dict(bytes=allocated, calls=calls, bytesPerCall=expected)
compact = json.loads((root / 'validation/compact-headers/validation.json').read_text())
assert compact['javaToolOptions'] == ['-XX:+UseCompactObjectHeaders']
assert compact['sameAsFrozenV7'] and compact['sourceMainMatchesFrozenV7']
assert compact['runtimeJarSha256'] == 'a73ec0e731b96b63523933cafc876b5e1c64884e24a42ded1b504b60d5bb0d7d'
totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
for xml in (root / 'validation/compact-headers/xml').glob('TEST-*.xml'):
    result = ET.parse(xml).getroot()
    for key in totals:
        totals[key] += int(result.get(key, 0))
assert totals == compact['totals'] == dict(tests=153, failures=0, errors=0, skipped=0)
checks = json.loads((root / 'validation/map/checks.json').read_text())
assert [c['name'] for c in checks] == ['default', 'cache', 'class', 'compact']
oracle = [line.split('\t')[1:] for line in (root / 'validation/map/oracle.tsv').read_text().splitlines()]
assert len(oracle) == 18 and len({r[0] for r in oracle}) == 18
for check in checks:
    name = check['name']
    assert check['passed'] and check['returncode'] == 0
    assert check['beforeRows'] == check['afterRows'] == 18
    lines = (root / f'validation/map/{name}.log').read_text().splitlines()
    for phase in ('before-requested-compilation', 'after-requested-compilation'):
        actual = [line.split('\t')[2:] for line in lines if line.startswith('VERIFIED_MAP\t' + phase + '\t')]
        assert actual == oracle, (name, phase)
    diagnostics = [json.loads(line.removeprefix('MAP_DIAGNOSTICS ')) for line in lines if line.startswith('MAP_DIAGNOSTICS ')]
    assert diagnostics == [check['diagnostics']]
    assert diagnostics[0]['unsupportedTraps'] == 0 and diagnostics[0]['compiledEntries'] > 0
    assert '-Dthc.boxedValueCache=' + ('true' if name == 'cache' else 'false') in check['argv']
    assert '-Dthc.constructorClassIdentity=' + ('true' if name == 'class' else 'false') in check['argv']
    assert '-Dthc.staticShapeUnchecked=false' in check['argv']
    assert ('-XX:+UseCompactObjectHeaders' if name == 'compact' else '-XX:-UseCompactObjectHeaders') in check['argv']
hashes = json.loads((root / 'validation/map/input-hashes.json').read_text())
assert next(v for k, v in hashes.items() if k.endswith('/map/oracle.tsv')) == hashlib.sha256((root / 'validation/map/oracle.tsv').read_bytes()).hexdigest()
assert next(v for k, v in hashes.items() if k.endswith('/lib/thc-0.1-experiment.jar')) == compact['runtimeJarSha256']
print(f'PASS: {len(manifest["files"])} artifact hashes; 45 checksum/duration windows; six last-tier/no-event logs; same-JAR one-flag comparison; 153/153/41 + focused and 153 compact-header tests; six exact allocation samples; all 18 Map inputs before/after compilation in four configurations')
