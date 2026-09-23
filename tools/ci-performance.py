#!/usr/bin/env python3
"""Manual CI orchestration: freeze inputs, check Map, then compare serially.

All timings belong to this GitHub-hosted machine. Absolute values must not be
compared with the historical local M3 runs. Timing uses frozen JARs; the optional
compact-header compatibility step separately reruns the source test suite.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import sys
import xml.etree.ElementTree as ET

ORIGINAL = '4c116a8493eafad4077a1c5eb9c046bff56fe3c4'
POLICY = ['-Dpolyglot.compiler.InliningRecursionDepth=2',
          '-Dpolyglot.compiler.InliningExpansionBudget=12000',
          '-Dpolyglot.compiler.InliningInliningBudget=12000']
CONFIGURATIONS = {
    'original': ('original', []),
    'current-default': ('current', []),
    'checked-class-off': ('current', ['-Dthc.staticShapeUnchecked=false', '-Dthc.constructorClassIdentity=false']),
    'checked-class-on': ('current', ['-Dthc.staticShapeUnchecked=false', '-Dthc.constructorClassIdentity=true']),
    'unchecked-class-on': ('current', ['-Dthc.staticShapeUnchecked=true', '-Dthc.constructorClassIdentity=true']),
    'compact-checked-class-off': ('current', ['-Dthc.staticShapeUnchecked=false', '-Dthc.constructorClassIdentity=false', '-XX:+UseCompactObjectHeaders']),
}
COMPARISONS = {
    'original-vs-current': ('original', 'current-default'),
    'constructor-class': ('checked-class-off', 'checked-class-on'),
    'owned-storage': ('checked-class-on', 'unchecked-class-on'),
    'compact-headers': ('checked-class-off', 'compact-checked-class-off'),
}
REQUIRED_COMPARISONS = tuple(name for name in COMPARISONS if name != 'compact-headers')
REQUIRED_CONFIGURATIONS = tuple(name for name in CONFIGURATIONS if not name.startswith('compact-'))


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def revision(path):
    return subprocess.check_output(['git', '-C', str(path), 'rev-parse', 'HEAD'], text=True).strip()


def copy_file(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def freeze(out, baseline):
    root = Path(__file__).resolve().parents[1]
    baseline = baseline.resolve(strict=True)
    assert revision(baseline) == ORIGINAL, 'Unexpected baseline checkout'
    frozen = out / 'frozen'
    assert not frozen.exists(), 'Frozen inputs already exist'
    frozen.mkdir(parents=True)
    for name, checkout in [('original', baseline), ('current', root)]:
        libraries = sorted((checkout / 'build/install/thc/lib').glob('*.jar'))
        assert libraries, f'Missing built libraries: {name}'
        for path in libraries:
            copy_file(path, frozen / name / 'lib' / path.name)
        shutil.copytree(checkout / 'src/main', frozen / name / 'src/main')
    source_manifest = root / 'build/map/modules.txt'
    modules = [(source_manifest.parent / line.strip()).resolve(strict=True)
               for line in source_manifest.read_text().splitlines() if line.strip()]
    assert modules and len(modules) == len(set(modules))
    copied_modules = []
    for index, path in enumerate(modules):
        target = frozen / 'map' / f'{index:02}-{path.name}'
        copy_file(path, target)
        copied_modules.append(target)
    (frozen / 'map/modules.txt').write_text(''.join(str(path) + '\n' for path in copied_modules))
    copy_file(root / 'build/map/native/native-oracle', frozen / 'map/native-oracle')
    copy_file(root / 'build/map/oracle.tsv', frozen / 'map/oracle.tsv')
    for name in ('ci-performance.py', 'compare-map-runtimes.py'):
        copy_file(root / 'tools' / name, frozen / 'helpers' / name)
    copy_file(root / '.github/workflows/performance.yml', frozen / 'helpers/performance.yml')
    for name in ('object-sizes.py', 'ObjectSizes.java', 'ObjectSizesAgent.java'):
        copy_file(root / 'bench/results/constructor-class/object-sizes' / name, frozen / 'helpers' / name)
    shutil.copytree(root / 'build/test-results/test', out / 'test-results/default')
    records = [{'path': str(path.relative_to(frozen)), 'sha256': sha(path)}
               for path in sorted(frozen.rglob('*')) if path.is_file()]
    for record in records:
        path = frozen / record['path']
        path.chmod(path.stat().st_mode & ~0o222)
    write_json(out / 'immutable-manifest.json', {'files': records})
    write_json(out / 'run.json', {
        'recordedAtUtc': datetime.now(timezone.utc).isoformat(),
        'scope': 'New GitHub-hosted macOS hardware; compare only within this run, not absolute local M3 timings.',
        'originalCommit': ORIGINAL, 'currentCommit': revision(root), 'repository': str(root),
        'host': {'system': platform.system(), 'machine': platform.machine(), 'platform': platform.platform()},
        'githubRunId': os.environ.get('GITHUB_RUN_ID'), 'githubRunAttempt': os.environ.get('GITHUB_RUN_ATTEMPT'),
        'policy': POLICY, 'configurations': CONFIGURATIONS, 'comparisons': COMPARISONS,
        'sharedCore': 'Both original and current JARs use this one current exported Core/native corpus.',
        'protocol': 'Full MapCheck uses counters to verify installed code; timing harness instrumentation is disabled.',
        'commands': [],
    })
    (out / 'logs').mkdir(exist_ok=True)
    print(f'Frozen {len(records)} files, including both runtime/source trees and one current Core/native corpus.', flush=True)


def verify(out):
    frozen = out / 'frozen'
    records = json.loads((out / 'immutable-manifest.json').read_text())['files']
    assert sorted(str(path.relative_to(frozen)) for path in frozen.rglob('*') if path.is_file()) == sorted(record['path'] for record in records), 'Frozen file inventory changed'
    for record in records:
        assert sha(frozen / record['path']) == record['sha256'], f'Frozen input changed: {record["path"]}'


def run(out, command, name, timeout, environment=None):
    config = json.loads((out / 'run.json').read_text())
    record = {'argv': list(map(str, command)), 'log': f'logs/{name}.log'}
    if environment:
        record['environmentOverrides'] = environment
    config['commands'].append(record)
    write_json(out / 'run.json', config)
    print(f'Starting {name}', flush=True)
    try:
        with (out / record['log']).open('w') as log:
            process = subprocess.Popen(record['argv'], stdout=log, stderr=subprocess.STDOUT,
                                       env={**os.environ, **environment} if environment else None, start_new_session=True)
            try:
                returncode = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                record['timedOut'] = True
                # Stop children as well, so a failed optional lane cannot leave JVMs running.
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    pass
                finally:
                    # The leader can exit before its child JVMs; kill the whole group anyway.
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.wait()
                raise
        record['returncode'] = returncode
    finally:
        write_json(out / 'run.json', config)
    if returncode:
        print('\n'.join((out / record['log']).read_text(errors='replace').splitlines()[-60:]), file=sys.stderr)
        raise RuntimeError(f'{name} failed with status {returncode}')
    print(f'Completed {name}', flush=True)
    return out / record['log']


def check_configuration(out, java, name):
    frozen = out / 'frozen'
    runtime, flags = CONFIGURATIONS[name]
    expected = [line.split('\t') for line in (frozen / 'map/oracle.tsv').read_text().splitlines() if line]
    assert len(expected) == 18, 'Expected the full 18-input Map oracle'
    verify(out)
    command = [java, '--enable-native-access=ALL-UNNAMED', '-Xss2m', *POLICY, *flags,
               '-Dthc.backend=bytecode', '-Dthc.diagnosticUnsupported=true', '-Dthc.sourceNotesEnabled=true',
               '-Dthc.traceCompilation=true', '-cp', str(frozen / runtime / 'lib/*'), 'thc.MapCheckKt',
               frozen / 'map/modules.txt', frozen / 'map/oracle.tsv']
    log = run(out, command, 'check-' + name, 600)
    lines = log.read_text().splitlines()
    for phase in ('before-requested-compilation', 'after-requested-compilation'):
        actual = [line.split('\t')[2:] for line in lines if line.startswith('VERIFIED_MAP\t' + phase + '\t')]
        assert actual == [row[1:] for row in expected], f'Incomplete Map check: {name} {phase}'
    diagnostics = json.loads(next(line.removeprefix('MAP_DIAGNOSTICS ') for line in lines if line.startswith('MAP_DIAGNOSTICS ')))
    assert diagnostics['backend'] == 'bytecode' and diagnostics['unsupportedTraps'] == 0
    assert diagnostics['sourceNotesEnabled'] is True and diagnostics['sourceRootCount'] > 0
    return {'configuration': name, 'beforeRows': 18, 'afterRows': 18, 'diagnostics': diagnostics}


def check(out, java):
    results = []
    for name in REQUIRED_CONFIGURATIONS:
        results.append(check_configuration(out, java, name))
        write_json(out / 'checks.json', {'passed': len(results) == len(REQUIRED_CONFIGURATIONS), 'configurations': results})
    verify(out)


def compare(out, name, java_home):
    checks = json.loads((out / 'checks.json').read_text())
    assert checks['passed'] is True, 'All Map configurations must pass before timing'
    if name == 'compact-headers':
        assert json.loads((out / 'compact-status.json').read_text())['readyToTime'] is True
    verify(out)
    frozen = out / 'frozen'
    left, right = COMPARISONS[name]
    baseline, baseline_flags = CONFIGURATIONS[left]
    if name == 'compact-headers':
        baseline_flags = baseline_flags + ['-XX:-UseCompactObjectHeaders']
    candidate, candidate_flags = CONFIGURATIONS[right]
    config = json.loads((out / 'run.json').read_text())
    command = [sys.executable, frozen / 'helpers/compare-map-runtimes.py',
               frozen / baseline / 'lib', frozen / candidate / 'lib', frozen / 'map/native-oracle',
               frozen / 'map/modules.txt', out / 'comparisons' / name, '--java-home', java_home,
               '--baseline-commit', ORIGINAL if baseline == 'original' else config['currentCommit'],
               '--candidate-source-dir', frozen / candidate / 'src/main',
               '--baseline-backend', 'bytecode', '--candidate-backend', 'bytecode',
               '--baseline-source-notes', 'on', '--candidate-source-notes', 'on']
    command += ['--baseline-jvm-option=' + flag for flag in POLICY + baseline_flags]
    command += ['--candidate-jvm-option=' + flag for flag in POLICY + candidate_flags]
    run(out, command, 'compare-' + name, 3000)
    validation = json.loads((out / 'comparisons' / name / 'validation.json').read_text())
    assert validation['passed'] is True and validation['validatedWindows'] == 45
    verify(out)


def compact(out, java_home):
    """An incompatible VM/compiler records a failed optional lane, without losing prior results."""
    verify(out)
    status = {'optional': True, 'passed': False, 'readyToTime': False, 'stage': 'full-tests'}
    status_path = out / 'compact-status.json'
    write_json(status_path, status)
    config = json.loads((out / 'run.json').read_text())
    root, frozen = Path(config['repository']), out / 'frozen'
    try:
        try:
            run(out, [root / 'scripts/gradle.sh', '--no-daemon', 'test', '--rerun'],
                'compact-full-tests', 1500, {'JAVA_TOOL_OPTIONS': '-XX:+UseCompactObjectHeaders'})
        finally:
            if (root / 'build/test-results/test').exists():
                shutil.copytree(root / 'build/test-results/test', out / 'test-results/compact')
        totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
        for path in (out / 'test-results/compact').glob('TEST-*.xml'):
            suite = ET.parse(path).getroot()
            for name in totals:
                totals[name] += int(suite.attrib.get(name, 0))
        assert totals['tests'] >= 148 and all(totals[name] == 0 for name in ('failures', 'errors', 'skipped')), totals
        status['tests'] = totals
        status['stage'] = 'full-map-check'
        write_json(status_path, status)
        status['mapCheck'] = check_configuration(out, java_home / 'bin/java', 'compact-checked-class-off')
        status['stage'] = 'object-sizes'
        write_json(status_path, status)
        # The published cold probe expects lib/, src/, map/, and a manifest under one root.
        probe = out / 'compact-object-sizes-input'
        shutil.copytree(frozen / 'current', probe)
        shutil.copytree(frozen / 'map', probe / 'map')
        modules = probe / 'map/modules.txt'
        modules.chmod(modules.stat().st_mode | 0o200)
        modules.write_text(''.join(str(probe / 'map' / Path(line).name) + '\n'
                                   for line in (frozen / 'map/modules.txt').read_text().splitlines() if line))
        records = [{'path': str(path.relative_to(probe)), 'sha256': sha(path)}
                   for path in sorted(probe.rglob('*')) if path.is_file()]
        write_json(probe / 'manifest.json', {'files': records})
        command = [sys.executable, frozen / 'helpers/object-sizes.py', probe,
                   out / 'compact-object-sizes', '--java-home', java_home, '--backend', 'bytecode']
        command += ['--jvm-option=' + flag for flag in CONFIGURATIONS['compact-checked-class-off'][1]]
        run(out, command, 'compact-object-sizes', 180)
        sizes = (out / 'compact-object-sizes/sizes.out').read_text()
        assert 'VM_OPTION\tUseCompactObjectHeaders\ttrue' in sizes, 'Compact object headers were not active'
        status.update(stage='timing', readyToTime=True)
        write_json(status_path, status)
        compare(out, 'compact-headers', java_home)
        status.update(stage='complete', passed=True)
    except Exception as error:
        status['error'] = str(error)
        print(f'Optional compact-header lane failed at {status["stage"]}: {error}; required results retained.', flush=True)
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as output:
                output.write(f'\nOptional compact-header experiment failed at `{status["stage"]}`; see compact-status.json and logs. Required comparisons are retained.\n')
    finally:
        write_json(status_path, status)
    # Immutable-input failure is never treated as mere option incompatibility.
    verify(out)


def finish(out):
    verify(out)
    comparisons = {}
    for name in REQUIRED_COMPARISONS:
        path = out / 'comparisons' / name
        validation = json.loads((path / 'validation.json').read_text())
        assert validation['passed'] is True and validation['validatedWindows'] == 45
        comparisons[name] = json.loads((path / 'summary.json').read_text())
    compact_status = json.loads((out / 'compact-status.json').read_text())
    if compact_status['passed']:
        comparisons['compact-headers'] = json.loads((out / 'comparisons/compact-headers/summary.json').read_text())
    summary = {'compactHeaders': compact_status, 'scope': json.loads((out / 'run.json').read_text())['scope'],
               'immutableInputsUnchanged': True, 'comparisons': comparisons}
    write_json(out / 'summary.json', summary)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as output:
            output.write('\n| Comparison | Candidate / baseline | Candidate / native |\n|---|---:|---:|\n')
            for name, result in comparisons.items():
                ratios = result['ratios']
                output.write(f'| {name} | {ratios["candidate/baseline"]:.4f} | {ratios["candidate/native"]:.4f} |\n')
    print('All three required comparisons passed, 45 windows each; frozen inputs unchanged. Compact status:', compact_status['passed'], flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('freeze', 'check', 'compare', 'compact', 'finish'))
    parser.add_argument('out', type=Path)
    parser.add_argument('--baseline-checkout', type=Path)
    parser.add_argument('--comparison', choices=COMPARISONS)
    args = parser.parse_args()
    out = args.out.resolve()
    if args.action == 'freeze':
        assert args.baseline_checkout, 'Pass --baseline-checkout'
        freeze(out, args.baseline_checkout)
    elif args.action == 'finish':
        finish(out)
    else:
        java_home = Path(os.environ['JAVA_HOME']).resolve(strict=True)
        if args.action == 'check':
            check(out, java_home / 'bin/java')
        elif args.action == 'compact':
            compact(out, java_home)
        else:
            assert args.comparison, 'Pass --comparison'
            compare(out, args.comparison, java_home)


if __name__ == '__main__':
    main()
