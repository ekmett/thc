#!/usr/bin/env python3
"""Exact saturated tagToEnum# families: fresh native valid tags and pre/post Core."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/tag-to-enum'
SOURCES = [ROOT / ('compiler/test-fixtures/' + name + '.hs') for name in
           ['TagToEnumAudit', 'TagToEnumExternal', 'TagToEnumAuditNative', 'TagToEnumFrontier']]
CODES = {'boolCase': [-11, 29], 'orderingCase': [71, -23, 211], 'colourCase': [17, -31, 83],
         'externalCase': [43, -7, 91, 1009], 'papCase': [17, -31, 83], 'onceCase': [102, 204]}
INPUTS = [(name, tag) for name, codes in CODES.items() for tag in range(len(codes))] + [
    (name, n) for name in ['lazyCase', 'lazyTagCase'] for n in [-(1 << 63), -1, 0, 1, 17, (1 << 63) - 1]]
ENTRIES = list(CODES) + ['lazyCase', 'lazyTagCase']

def check(ok, detail):
    if not ok: raise AssertionError(detail)

def record(p):
    return dict(path=str(p.relative_to(ROOT)), sha256=hashlib.sha256(p.read_bytes()).hexdigest())

def walk(x):
    yield x
    for child in x.values() if isinstance(x, dict) else x if isinstance(x, list) else []:
        yield from walk(child)

def audit_inputs():
    return [ROOT / 'scripts/audit-core.py', ROOT / 'scripts/core-capabilities.json',
            *sorted((ROOT / 'scripts').glob('core_*.py')), ROOT / 'src/main/resources/thc/scalar-primop-signatures.json',
            ROOT / 'scripts/generate-scalar-signatures.py']

def verify_native():
    rows = [line.split('\t') for line in (OUT / 'oracle.tsv').read_text().splitlines()]
    check([(name, int(tag)) for name, tag, _ in rows] == INPUTS, 'Native input inventory changed')
    for name, tag, result in rows:
        n = int(tag)
        expected = CODES[name][n] if name in CODES else ((n + 5 + (1 << 63)) % (1 << 64)) - (1 << 63)
        check(int(result) == expected, f'Native/model mismatch {name}({n}): {result} != {expected}')
    return len(rows)

def inventory(stage):
    m = json.loads((OUT / f'{stage}-core/TagToEnumAudit.json').read_text())
    check(m['boundary'] == ('optimized-Core-before-Tidy' if stage == 'pre' else 'optimized-Core-after-Tidy-before-CorePrep'), 'Wrong boundary')
    cons = {c['id']: c for c in m['constructors']}
    uses = [x for x in walk(m['bindings']) if isinstance(x, list) and len(x) > 1 and x[0] == 'app'
            and isinstance(x[1], list) and x[1][:2] == ['prim', 'tagToEnum#']]
    check(len(uses) == 4, 'Expected four genuine dynamic tagToEnum applications')
    sizes = []
    for app in uses:
        family = app[6]['enumFamily']; ids = family['constructors']; sizes.append(len(ids))
        check(app[3] == [False] and len(app[2]) == 1, 'Exact saturation/levity lost')
        check(app[6]['rep']['kind'] == 'data' and app[6]['rep']['primReps'] == ['BoxedRep (Just Lifted)'], 'Result identity lost')
        for i, key in enumerate(ids):
            c = cons[key]
            check(c['enumFamily'] == family and c['arity'] == 0 and c['tag'] == i + 1 and c['kind'] == 'boxed', 'Family order/completeness changed')
    check(sorted(sizes) == [2, 3, 3, 4], 'Enum family inventory changed')
    check(len([k for k in cons if ':TagToEnumExternal.' in k]) == 4,
          'Imported family must be retained in the consumer module, independent of merging defining module')
    prefix = next(b for b in m['bindings'] if b['name'] == 'prefix')
    check(len(prefix['expr'][1]) == 2, 'Prefix must retain its two-argument function')
    check(any(isinstance(x, list) and len(x) > 2 and x[0] == 'app' and x[1][:2] == ['var', prefix['id']]
              and len(x[2]) == 1 for x in walk(m['bindings'])), 'Genuine ordinary PAP disappeared')
    lazy = next(b for b in m['bindings'] if b['name'] == 'lazyTagCase')
    choose = next(b for b in m['bindings'] if b['name'] == 'chooseBool')
    check(any(isinstance(x, list) and len(x) > 2 and x[0] == 'app' and x[1][:2] == ['var', choose['id']]
              for x in walk(lazy['expr'])), 'Unused dynamic enum application was erased')
    frontier = json.loads((OUT / f'{stage}-core/TagToEnumFrontier.json').read_text())
    unknown = [x for x in walk(frontier['bindings']) if isinstance(x, list) and len(x) > 1 and x[0] == 'app'
               and isinstance(x[1], list) and x[1][:2] == ['prim', 'tagToEnum#']]
    check(len(unknown) == 2 and all('enumFamily' not in app[6] for app in unknown),
          'Parameterized/data-family type must not receive a supported nominal descriptor')
    audit = json.loads((OUT / f'{stage}-audit.json').read_text())
    check(audit['accepted'], 'Strict tagToEnum audit failed')
    return dict(stage=stage, families=4, audit=audit['summary'])

def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check-only', action='store_true'); args = parser.parse_args()
    provenance = OUT / 'provenance.json'
    if not args.check_only:
        ghc = os.environ.get('GHC', 'ghc')
        check(subprocess.check_output([ghc, '--numeric-version'], text=True).strip() == '9.14.1', 'Requires GHC9.14.1')
        (OUT / 'native').mkdir(parents=True, exist_ok=True); commands = []
        def run(argv, env=None):
            commands.append(dict(argv=list(map(str, argv)), environment=env or {}))
            subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True)
        (OUT / 'inputs.tsv').write_text(''.join(f'{name}\t{tag}\n' for name, tag in INPUTS))
        run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', '-icompiler/test-fixtures',
             '-odir', OUT / 'native', '-hidir', OUT / 'native', '-o', OUT / 'native/oracle', SOURCES[2]])
        argv = [str(OUT / 'native/oracle'), str(OUT / 'inputs.tsv')]
        commands.append(dict(argv=argv, stdout=str(OUT / 'oracle.tsv')))
        (OUT / 'oracle.tsv').write_text(subprocess.check_output(argv, text=True)); verify_native()
        for stage in ['pre', 'post']:
            run(['compiler/export.sh', '-icompiler/test-fixtures', *(['-fplugin-opt=Thc.Plugin:post-tidy'] if stage == 'post' else []), str(SOURCES[0]), str(SOURCES[3])],
                dict(THC_CORE_OUT=str(OUT / f'{stage}-core'), THC_GHC_OUT=str(OUT / f'{stage}-ghc'), THC_SOURCE_NOTES='true'))
            run([sys.executable, 'scripts/audit-core.py', *[str(p) for p in sorted((OUT / f'{stage}-core').glob('*.json'))],
                 *[v for name in ENTRIES for v in ['--entry', name]], '--output', str(OUT / f'{stage}-audit.json')])
            inventory(stage)
        sources = SOURCES + [Path(__file__).resolve(), ROOT / 'compiler/build.sh', ROOT / 'compiler/export.sh',
                             ROOT / 'compiler/toolchain.sh', *sorted((ROOT / 'compiler/Thc').glob('*.hs')), *audit_inputs()]
        artifacts = [p for folder in ['native', 'pre-core', 'post-core'] for p in sorted((OUT / folder).rglob('*')) if p.is_file()]
        artifacts += [OUT / name for name in ['inputs.tsv', 'oracle.tsv', 'pre-audit.json', 'post-audit.json']]
        provenance.write_text(json.dumps(dict(schema=1, ghcInfo=subprocess.check_output([ghc, '--info'], text=True),
            sources=[record(p) for p in sources], artifacts=[record(p) for p in artifacts], commands=commands,
            model='Explicit source-constructor score tables; signed 64-bit wrap for unused lazy argument. Only valid tags are native oracles; invalid tags are runtime error controls.'), indent=2) + '\n')
    evidence = json.loads(provenance.read_text())
    check({str(p.relative_to(ROOT)) for p in audit_inputs()} <= {r['path'] for r in evidence['sources']}, 'Missing auditor inputs')
    for item in evidence['sources'] + evidence['artifacts']:
        check(record(ROOT / item['path']) == item, 'Stale enum evidence: ' + item['path'])
    checks = dict(nativeRows=verify_native(), coverage=[inventory(s) for s in ['pre', 'post']], provenance=record(provenance))
    (OUT / 'checks.json').write_text(json.dumps(checks, indent=2) + '\n'); print(json.dumps(checks))
if __name__ == '__main__': main()
