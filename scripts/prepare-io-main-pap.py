#!/usr/bin/env python3
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
"""Genuine optimized IO () PAPs, checked native effects, and strict IO audits."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/io-main-pap'
FIXTURES = [ROOT / 'compiler/test-fixtures' / name for name in
            ('IoMainPapAudit.hs', 'IoMainPapNative.hs')]
ENTRIES = ['goodMain', 'badMain']
STAGES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}
PREFIX = 'main:IoMainPapAudit.'
EXPECTED_ROWS = [['goodMain', 'completed'], ['badMain', 'throws']]


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def sources():
    return [*FIXTURES, Path(__file__).resolve(), ROOT / 'compiler/build.sh', ROOT / 'compiler/export.sh',
            ROOT / 'compiler/toolchain.sh', *sorted((ROOT / 'compiler/THC').glob('*.hs')),
            ROOT / 'scripts/audit-core.py', ROOT / 'scripts/core-capabilities.json',
            *sorted((ROOT / 'scripts').glob('core_*.py')),
            ROOT / 'src/main/resources/thc/scalar-primop-signatures.json']


def inventory(stage, modules):
    """Inspect actual GHC output; never manufacture or eta-expand a positive."""
    module = next(m for _, m in modules if m['module'] == 'IoMainPapAudit')
    check(module['ghc'] == '9.14.1' and module['boundary'] == STAGES[stage], 'Wrong GHC/Core boundary')
    bindings = {b['id']: b for _, m in modules for b in m['bindings']}
    coverage = []
    for name, flag in zip(ENTRIES, ('0', '1')):
        entry = bindings[PREFIX + name]
        check(entry['type'] == 'IO ()' and entry['arity'] == 1, f'{stage}/{name}: not IO ()')
        expr, aliases = entry['expr'], []
        while expr[0] == 'var':
            key = expr[1]
            check(key not in aliases, 'Cyclic entry alias')
            aliases.append(key)
            expr = bindings[key]['expr']
        check(expr[0] == 'app' and expr[1][:1] == ['var'], f'{stage}/{name}: not a global PAP')
        check([a[:3] for a in expr[2]] == [['lit', 'int', '41'], ['lit', 'int', flag]],
              f'{stage}/{name}: fixed two-argument prefix changed')
        check(expr[3] == [False, False], f'{stage}/{name}: prefix lost unliftedness')
        worker = bindings[expr[1][1]]
        lam = worker['expr']
        check(worker['arity'] == 3 and lam[0] == 'lam' and len(lam[1]) == 3,
              f'{stage}/{name}: worker no longer has three formals')
        check([b['type'] for b in lam[1]] == ['Int#', 'Int#', 'State# RealWorld'],
              f'{stage}/{name}: lost exact state binder type')
        state = lam[1][2]
        check(state['lifted'] is False and state['rep']['kind'] == 'void' and state['rep']['primReps'] == [],
              f'{stage}/{name}: remaining state is not exactly void')
        result = lam[-1]['resultRep']
        check(result['kind'] == 'unknown' and result['aggregate'] == 'unboxed-tuple'
              and result['primReps'] == ['BoxedRep (Just Lifted)'] and len(result['components']) == 2,
              f'{stage}/{name}: wrong tuple result')
        state_result, unit_result = result['components']
        check(state_result['kind'] == 'void' and state_result['primReps'] == []
              and unit_result['kind'] == 'data' and unit_result['primReps'] == ['BoxedRep (Just Lifted)'],
              f'{stage}/{name}: wrong state/unit fields')
        coverage.append(dict(entry=entry['id'], aliases=aliases, worker=worker['id'], supplied=2,
                             remainingBinder=state, result=result))
    return dict(stage=stage, entries=coverage)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-only', action='store_true', help='Verify current hashes, native rows and strict audits')
    args = parser.parse_args()
    provenance = OUT / 'provenance.json'
    if not args.check_only:
        OUT.mkdir(parents=True, exist_ok=True)
        provenance.unlink(missing_ok=True)
        ghc = os.environ.get('GHC', 'ghc')
        commands = []

        def run(command, environment=None, **kwargs):
            argv = [str(x) for x in command]
            commands.append(dict(argv=argv, environment=environment or {}))
            return subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(environment or {})), check=True, **kwargs)

        version = run([ghc, '--numeric-version'], text=True, capture_output=True).stdout.strip()
        check(version == '9.14.1', f'Expected GHC 9.14.1, got {version}')
        spec = importlib.util.spec_from_file_location('core_audit', ROOT / 'scripts/audit-core.py')
        audit = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(audit)
        capabilities = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
        artifacts, coverage, rejected = [], [], []
        for stage in STAGES:
            directory = OUT / stage
            options = ['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []
            run([ROOT / 'compiler/export.sh', *options,
                 *['-fplugin-opt=THC.Plugin:closure=' + name for name in ENTRIES], FIXTURES[0]],
                dict(THC_CORE_OUT=str(directory / 'core'), THC_GHC_OUT=str(directory / 'ghc'), THC_SOURCE_NOTES='true'))
            paths = sorted((directory / 'core').glob('*.json'))
            check({p.name for p in paths} == {'IoMainPapAudit.json', 'THC.InterfaceClosure.json'}, 'Unexpected Core module set')
            modules = [(str(p.relative_to(ROOT)), json.loads(p.read_text())) for p in paths]
            artifacts.extend(paths)
            coverage.append(inventory(stage, modules))
            for name in ENTRIES:
                report = audit.Audit(modules, capabilities).run([PREFIX + name], io_main=True)
                report_path = directory / (name + '-audit.json')
                report_path.write_text(json.dumps(report, indent=2) + '\n')
                artifacts.append(report_path)
                check({'newMutVar#', 'readMutVar#', 'writeMutVar#', 'raise#'} <= {p['name'] for p in report['primitives']},
                      f'{stage}/{name}: checked effect or failing branch disappeared')
                if not report['accepted']:
                    rejected.append(f'{stage}/{name}: {report["issues"]}; missing={report["missingGlobals"]}')
        native = OUT / 'native'
        native.mkdir(exist_ok=True)
        executable = native / 'io-main-pap-oracle'
        run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint',
             '-i' + str(ROOT / 'compiler/test-fixtures'), '-odir', native, '-hidir', native,
             FIXTURES[1], '-o', executable])
        native_result = run([executable], text=True, capture_output=True, timeout=30)
        (OUT / 'oracle.tsv').write_text(native_result.stdout)
        (OUT / 'native-stderr.txt').write_text(native_result.stderr)
        check([line.split('\t') for line in native_result.stdout.splitlines()] == EXPECTED_ROWS,
              'Native effects disagree with completion/exception contract')
        artifacts += [executable, OUT / 'oracle.tsv', OUT / 'native-stderr.txt']
        # Rejected evidence remains inspectable for pre-fix regression runs, but
        # is explicitly marked rejected and can never make preparation succeed.
        provenance.write_text(json.dumps(dict(schema=1, ghc=version, recordedAtUtc=datetime.now(timezone.utc).isoformat(),
            accepted=not rejected, rejections=rejected, commands=commands, coverage=coverage,
            sources=[record(p) for p in sources()], artifacts=[record(p) for p in artifacts]), indent=2) + '\n')
    evidence = json.loads(provenance.read_text())
    check({str(p.relative_to(ROOT)) for p in sources()} == {r['path'] for r in evidence['sources']}, 'Incomplete source fingerprints')
    for item in evidence['sources'] + evidence['artifacts']:
        check(record(ROOT / item['path']) == item, 'Stale IO-main PAP evidence: ' + item['path'])
    check([line.split('\t') for line in (OUT / 'oracle.tsv').read_text().splitlines()] == EXPECTED_ROWS, 'Wrong native rows')
    for stage in STAGES:
        for name in ENTRIES:
            report = json.loads((OUT / stage / (name + '-audit.json')).read_text())
            check(report['accepted'], f'Strict IO audit rejected {stage}/{name}: {report["issues"]}; missing={report["missingGlobals"]}')
    check(evidence['accepted'], 'Strict IO audit rejected: ' + '\n'.join(evidence['rejections']))
    print('IO-main PAP: 2 checked native actions, exact two-argument PAPs, strict IO audits at both Core boundaries')


if __name__ == '__main__':
    main()
