#!/usr/bin/env python3
"""Validate the published layout experiment without Java or original work paths."""
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
assert config['jvmWarmSeconds'] == 45 and config['minimumJvmWarmCalls'] == 30000
assert config['nativeWarmSeconds'] == 10
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
            harness.validate_jvm_log(root / f'throughput/{engine}-{fork}.log', cycle, 'bytecode', 'on', minimum_warm_calls=30000, warm_seconds=45)
            command = next(c['argv'] for c in config['commands'] if c['engine'] == engine and c['fork'] == fork)
            assert '-Dthc.classOwnedLayouts=' + ('false' if engine == 'baseline' else 'true') in command
            for fixed in ('-Dthc.constructorClassIdentity=false', '-Dthc.staticShapeUnchecked=false', '-Dthc.boxedValueCache=false', '-XX:+UseCompactObjectHeaders'):
                assert fixed in command
    assert harness.engine_summary(all_rows) == summary['engines'][engine], engine
assert windows == summary['windowCount'] == 45
for fork in range(1, 4):
    pair = [next(c['argv'] for c in config['commands'] if c['engine'] == e and c['fork'] == fork) for e in ('baseline', 'candidate')]
    stripped = [[v for v in cmd if not v.startswith('-Dthc.classOwnedLayouts=')] for cmd in pair]
    assert stripped[0] == stripped[1]
for folder in ('default', 'owned', 'owned-compact-checked', 'owned-compact-unchecked'):
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    for xml in (root / f'prototype/validation/{folder}-xml').glob('TEST-*.xml'):
        result = ET.parse(xml).getroot()
        for key in totals:
            totals[key] += int(result.get(key, 0))
    assert totals == dict(tests=157, failures=0, errors=0, skipped=0), (folder, totals)
for mode, expected in [('off', 7959001.5), ('on', 6653506.0)]:
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
checks = json.loads((root / 'map/checks.json').read_text())
assert [c['name'] for c in checks] == ['off-compact', 'on-compact', 'on-normal', 'on-compact-unchecked']
oracle = [line.split('\t')[1:] for line in (root / 'map/oracle.tsv').read_text().splitlines()]
assert len(oracle) == 18 and len({r[0] for r in oracle}) == 18
for check in checks:
    name = check['name']
    assert check['passed'] and check['returncode'] == 0
    assert check['beforeRows'] == check['afterRows'] == 18
    lines = (root / f'map/{name}.log').read_text().splitlines()
    for phase in ('before-requested-compilation', 'after-requested-compilation'):
        actual = [line.split('\t')[2:] for line in lines if line.startswith('VERIFIED_MAP\t' + phase + '\t')]
        assert actual == oracle, (name, phase)
    diagnostics = [json.loads(line.removeprefix('MAP_DIAGNOSTICS ')) for line in lines if line.startswith('MAP_DIAGNOSTICS ')]
    assert diagnostics == [check['diagnostics']]
    assert diagnostics[0]['unsupportedTraps'] == 0 and diagnostics[0]['compiledEntries'] > 0
for mode, expected in [('off', {'Bin':40, 'I#':24, 'Tip':16}), ('on', {'Bin':32, 'I#':16, 'Tip':8})]:
    lines = (root / f'prototype/validation/sizes-{mode}-compact/sizes.out').read_text().splitlines()
    sizes = {row[2].rsplit('.',1)[-1]:int(row[4]) for line in lines if (row:=line.split('\t'))[:2] == ['SIZE','DATA']}
    assert {name:sizes[name] for name in expected} == expected
integration = json.loads((root / 'integration/frozen-defaults-manifest.json').read_text())
for run in integration['tests']:
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    for xml in (root / 'integration' / (run['name'] + '-xml')).glob('TEST-*.xml'):
        result = ET.parse(xml).getroot()
        for key in totals:
            totals[key] += int(result.get(key, 0))
    assert totals == run['totals'] and run['returncode'] == 0
    assert totals['failures'] == totals['errors'] == totals['skipped'] == 0
for check in integration['mapChecks']:
    assert check['passed'] and check['returncode'] == 0
    lines = (root / ('integration/map-default-' + check['backend'] + '.log')).read_text().splitlines()
    for phase in ('before-requested-compilation', 'after-requested-compilation'):
        actual = [line.split('\t')[2:] for line in lines if line.startswith('VERIFIED_MAP\t' + phase + '\t')]
        assert actual == oracle
    assert check['diagnostics']['unsupportedTraps'] == 0
assert ' = true ' in integration['appFlags'][0]['flag']
assert ' = false ' in integration['appFlags'][1]['flag']
graphs = json.loads((root / 'graphs/audit.json').read_text())
assert len(graphs['roots']) == 6
for graph in graphs['roots']:
    assert '-XX:+UseCompactObjectHeaders' in graph['jvmOptions']
    for phase in graph['phases']:
        if graph['mode'] == 'on':
            assert phase['layoutLoads'] == []
        assert all('OptimizedCallTarget.callBoundary' in target for target in phase['callTargets'])
print(f'PASS: {len(manifest["files"])} artifact hashes; 45 timing windows and six compilation logs with 45s/30k warmup; prototype4x157 and integration167/167/5 tests; six exact allocation samples; six full18 Map checks; compact sizes and graph audit invariants')
