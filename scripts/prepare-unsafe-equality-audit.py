#!/usr/bin/env python3
"""Fresh native oracle and exact GHC late unsafe-equality case regression."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/unsafe-equality'
FIXTURES = [ROOT / 'compiler/test-fixtures' / name for name in
            ('UnsafeEqualityAudit.hs', 'UnsafeEqualityAuditNative.hs', 'UnsafeEqualityPredicate.hs')]
ENTRIES = ['primitiveCase', 'liftedCase', 'tupleCase', 'lazyCase', 'unusedCase', 'unusedBottomCase', 'nestedCase']
FRONTIERS = ['firstClassProof', 'liveBinder']
EFFECTS = ['wrongCalleeCase', 'demandedBottomCase']
PROOF = 'ghc-internal:GHC.Internal.Unsafe.Coerce.unsafeEqualityProof'
STAGES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}
INPUTS = [-(1 << 63), -4097, -1, 0, 1, 4097, (1 << 63) - 1]


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def audit_inputs():
    return [ROOT / 'scripts/audit-core.py', ROOT / 'scripts/core-capabilities.json',
            *sorted((ROOT / 'scripts').glob('core_*.py')),
            ROOT / 'src/main/resources/thc/scalar-primop-signatures.json', ROOT / 'scripts/generate-scalar-signatures.py']


def inventory(stage):
    module = json.loads((OUT / f'{stage}-core/UnsafeEqualityAudit.json').read_text())
    closure = json.loads((OUT / f'{stage}-core/THC.InterfaceClosure.json').read_text())
    check(module['ghc'] == '9.14.1' and module['boundary'] == STAGES[stage], 'Wrong Core boundary')
    bindings = {b['name']: b for b in module['bindings'] + closure['bindings']}
    result = {}
    for name, kind in [('primitive', 'long'), ('nested', 'long'), ('tuple', 'unknown'), ('lazyValue', 'data'), ('unsafeCoerce', 'object')]:
        marks = [n for n in walk(bindings[name]['expr']) if isinstance(n, dict) and 'unsafeEqualityCase' in n]
        check(len(marks) == 1, f'{stage}/{name}: genuine late case must be retained then lowered exactly once')
        mark = marks[0]
        check(mark['unsafeEqualityCase'] == 'GHC.Core.Utils.isUnsafeEqualityCase/CoreToStg' and mark['rep']['kind'] == kind,
              f'{name}: wrong lowering or representation')
        check(not any(isinstance(n, list) and n[:2] == ['var', PROOF] for n in walk(bindings[name]['expr'])),
              f'{name}: lowered case still depends on proof')
        if name != 'unsafeCoerce':
            check(mark.get('sourceNotes') and all(note in {s['id'] for s in module['sourceSpans']} for note in mark['sourceNotes']),
                  f'{name}: lost source notes')
        result[name] = mark['rep']
    check(result['primitive']['primReps'] == ['IntRep'], 'Unlifted result lost exact representation')
    check(result['tuple']['aggregate'] == 'unboxed-tuple' and
          result['tuple']['primReps'] == ['IntRep', 'BoxedRep (Just Lifted)'], 'Tuple result proof lost')
    check(result['lazyValue']['evaluated'] is False and result['unsafeCoerce']['evaluated'] is False,
          'Lowering must not mark arbitrary lifted payload evaluated')
    check('unsafeEqualityProof' in module['sourceCore'] and 'unsafeEqualityProof' in closure['sourceCore'],
          'Original Core evidence must retain the pre-CoreToStg cases')
    for name in FRONTIERS:
        check(any(isinstance(n, list) and n[:2] == ['var', PROOF] for n in walk(bindings[name]['expr'])),
              f'{name}: nonmatching proof dependency disappeared')
    live = [n for n in walk(bindings['liveBinder']['expr']) if isinstance(n, list) and n[:1] == ['case']]
    check(len(live) == 1 and live[0][1][:2] == ['var', PROOF] and
          live[0][-1]['binder']['info']['occurrence'] != 'Dead' and
          any(isinstance(n, list) and n[:2] == ['var', live[0][2]] for n in walk(live[0][3])),
          'Genuine live case binder must remain used and unlowered')
    check(not any('unsafeEqualityCase' in n for n in walk(bindings['wrongCalleeCase']['expr']) if isinstance(n, dict)),
          'Arbitrary proof-producing call must not be erased')
    check(any(b.get('origin') == 'interface-core-unfolding' and b['name'] == 'unsafeCoerce' for b in closure['bindings']),
          'Expected actual installed unsafeCoerce unfolding')
    return dict(stage=stage, resultProofs=result, nonmatchingFrontiers=FRONTIERS)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    provenance = OUT / 'provenance.json'
    if not args.check_only:
        ghc = os.environ.get('GHC', 'ghc')
        check(subprocess.check_output([ghc, '--numeric-version'], text=True).strip() == '9.14.1', 'Requires pinned GHC9.14.1')
        for folder in ('native', 'api'):
            (OUT / folder).mkdir(parents=True, exist_ok=True)
        commands = []
        def run(argv, env=None):
            commands.append(dict(argv=argv, environment=env or {}))
            subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True)
        run(['compiler/build.sh'])
        run([ghc, '--make', '-v0', '-O0', '-dynamic', '-package', 'ghc', '-icompiler',
             '-odir', str(OUT / 'api'), '-hidir', str(OUT / 'api'), str(FIXTURES[2]), '-o', str(OUT / 'api/predicate')])
        run([str(OUT / 'api/predicate'), subprocess.check_output([ghc, '--print-libdir'], text=True).strip()])
        run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-icompiler/test-fixtures',
             '-odir', str(OUT / 'native'), '-hidir', str(OUT / 'native'), '-o', str(OUT / 'native/oracle'), str(FIXTURES[1])])
        commands.append(dict(argv=[str(OUT / 'native/oracle')], stdout=str(OUT / 'oracle.tsv')))
        (OUT / 'oracle.tsv').write_text(subprocess.check_output([str(OUT / 'native/oracle')], text=True))
        spec = importlib.util.spec_from_file_location('audit', ROOT / 'scripts/audit-core.py')
        audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
        caps = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
        for stage in STAGES:
            run(['compiler/export.sh', *(['-fplugin-opt=Thc.Plugin:post-tidy'] if stage == 'post' else []),
                 *['-fplugin-opt=Thc.Plugin:closure=' + name for name in ENTRIES + FRONTIERS + EFFECTS], str(FIXTURES[0])],
                dict(THC_CORE_OUT=str(OUT / f'{stage}-core'), THC_GHC_OUT=str(OUT / f'{stage}-ghc'), THC_SOURCE_NOTES='true'))
            paths = sorted((OUT / f'{stage}-core').glob('*.json'))
            modules = [(str(p), json.loads(p.read_text())) for p in paths]
            report = audit.Audit(modules, caps).run(ENTRIES + EFFECTS)
            check(report['accepted'], f'{stage}: strict native roots rejected: {report["summary"]}')
            (OUT / f'{stage}-audit.json').write_text(json.dumps(report, indent=2) + '\n')
            for name in FRONTIERS:
                report = audit.Audit(modules, caps).run([name])
                check(not report['accepted'] and [m['id'] for m in report['missingGlobals']] == [PROOF] and not report['issues'],
                      f'{stage}/{name}: expected exact unresolved proof frontier')
                (OUT / f'{stage}-{name}-audit.json').write_text(json.dumps(report, indent=2) + '\n')
        sources = [*FIXTURES, Path(__file__).resolve(), ROOT / 'compiler/build.sh', ROOT / 'compiler/export.sh',
                   ROOT / 'compiler/toolchain.sh', *sorted((ROOT / 'compiler/Thc').glob('*.hs')), *audit_inputs()]
        artifacts = [p for folder in ('native', 'api', 'pre-core', 'post-core') for p in sorted((OUT / folder).rglob('*')) if p.is_file()]
        artifacts += [OUT / 'oracle.tsv', *sorted(OUT.glob('*-audit.json'))]
        provenance.write_text(json.dumps(dict(schema=1, recordedAtUtc=datetime.now(timezone.utc).isoformat(), commands=commands,
            ghcInfo=subprocess.check_output([ghc, '--info'], text=True),
            sources=[record(p) for p in sources], artifacts=[record(p) for p in artifacts],
            claim='Original native Haskell and actual GHC API predicate; only matching late cases are lowered. Runtime compilation checked separately.'), indent=2) + '\n')
    evidence = json.loads(provenance.read_text())
    check({str(p.relative_to(ROOT)) for p in audit_inputs()} <= {r['path'] for r in evidence['sources']}, 'Missing current auditor source hashes')
    for item in evidence['sources'] + evidence['artifacts']:
        check(record(ROOT / item['path']) == item, 'Stale unsafe-equality fixture: ' + item['path'])
    rows = [line.split('\t') for line in (OUT / 'oracle.tsv').read_text().splitlines()]
    wrap = lambda n: (n + (1 << 63)) % (1 << 64) - (1 << 63)
    def model(name, x):
        if name == 'tupleCase': return wrap(2*x + 3)
        if name == 'unusedCase': return wrap(x + (7 if x < 0 else 17))
        return wrap(x + dict(primitiveCase=5, liftedCase=11, lazyCase=-13, unusedBottomCase=19, nestedCase=-23)[name])
    expected = {(name, x): model(name, x) for name in ENTRIES for x in INPUTS}
    check(len(rows) == len(expected) and {(name, int(x)): int(y) for name, x, y in rows} == expected,
          'Native results disagree with independent integer model')
    (OUT / 'checks.json').write_text(json.dumps(dict(schema=1, nativeRows=len(rows), coverage=[inventory(s) for s in STAGES],
                                                    provenance=record(provenance)), indent=2) + '\n')
    print(f'Unsafe equality: {len(rows)} native/model rows, exact GHC predicate controls, 9 strict roots and 2 explicit frontiers at both stages')


if __name__ == '__main__':
    main()
