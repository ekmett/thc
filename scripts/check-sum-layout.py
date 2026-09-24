#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Pinned GHC sum layouts, native fixtures and exact runtime capability boundaries."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
from sum_layout_model import alternative_slots

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/sum-layout'
FIXTURE = ROOT / 'compiler/test-fixtures/SumLayoutAudit.hs'
NATIVE = ROOT / 'compiler/test-fixtures/SumLayoutAuditNative.hs'
STAGES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}
LIFTED, UNLIFTED = 'BoxedRep (Just Lifted)', 'BoxedRep (Just Unlifted)'

def check(condition, message):
    if not condition: raise AssertionError(message)

def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)

def leaf(kind, reps, evaluated=True):
    return dict(kind=kind, primReps=reps, evaluated=evaluated)

def tup(children, reps):
    return dict(leaf('unknown', reps), aggregate='unboxed-tuple', components=children)

def summ(children, reps, evaluated=False):
    return dict(leaf('unknown', reps, evaluated), aggregate='unboxed-sum', alternatives=children,
                tagSlot=0, alternativeSlots=alternative_slots(children, reps))

INT, WORD, VOID = leaf('long', ['IntRep']), leaf('long', ['WordRep']), leaf('void', [])
EMPTY, BOX = tup([], []), leaf('data', [LIFTED], False)
FLOAT, DOUBLE = leaf('float', ['FloatRep']), leaf('double', ['DoubleRep'])
UNKNOWN = leaf('unknown', None, False)
V4 = dict(leaf('vector', ['VecRep 4 Int32ElemRep']), vector=dict(lanes=4, element='Int32ElemRep'))
V2 = dict(leaf('vector', ['VecRep 2 Int64ElemRep']), vector=dict(lanes=2, element='Int64ElemRep'))
EXPECTED = {
    'returnedSum': summ([INT, WORD], ['WordRep', 'WordRep']),
    'nestedSum': summ([tup([INT, INT], ['IntRep', 'IntRep']),
                       summ([INT, DOUBLE], ['WordRep', 'WordRep', 'DoubleRep'], True)],
                      ['WordRep', 'WordRep', 'WordRep', 'DoubleRep']),
    'lazySum': summ([BOX, FLOAT], ['WordRep', LIFTED, 'FloatRep']),
    'zeroSum': summ([VOID, EMPTY], ['WordRep']),
    'unitSum': summ([BOX, EMPTY], ['WordRep', LIFTED]),
    'boxedKindsSum': summ([BOX, leaf('object', [UNLIFTED])], ['WordRep', LIFTED, UNLIFTED]),
    'floatDoubleSum': summ([FLOAT, DOUBLE], ['WordRep', 'FloatRep', 'DoubleRep']),
    'narrowWideSum': summ([leaf('long', ['Int32Rep']), leaf('long', ['Word64Rep'])], ['WordRep', 'Word64Rep']),
    'threeWaySum': summ([EMPTY, INT, WORD], ['WordRep', 'WordRep']),
    'aliasIdentity': summ([VOID, EMPTY], ['WordRep'], True),
    'runtimePolymorphic': summ([UNKNOWN, INT], None),
    'levityPolymorphic': summ([leaf('object', ['BoxedRep Nothing'], False), INT], None),
    'abstractSumIdentity': summ(None, ['WordRep', 'WordRep'], True),
    'abstractRuntimeSum': summ(None, None),
    'abstractAlternative': summ([tup(None, ['IntRep']), INT], ['WordRep', 'WordRep'], True),
    'addressResult': summ([leaf('address', ['AddrRep']), INT], ['WordRep', 'WordRep']),
    'vectorResult': summ([V4, V2], ['WordRep', 'VecRep 2 Int64ElemRep', 'VecRep 4 Int32ElemRep']),
}
ENTRIES = ['sumCase', 'directCase', 'nestedCase', 'lazyCase', 'zeroCase', 'unitCase',
           'boxedKindsCase', 'floatDoubleCase', 'narrowWideCase', 'threeWayCase']
SUPPORTED = {'sumCase', 'directCase', 'lazyCase', 'zeroCase', 'unitCase', 'boxedKindsCase', 'floatDoubleCase'}
INPUTS = [-(1 << 63), -2147483649, -2147483648, -5, -1, 0, 1, 7,
          2147483647, 2147483648, 4294967295, 4294967296, (1 << 63)-1]

def signed(value, bits=64):
    return ((value + (1 << (bits-1))) % (1 << bits)) - (1 << (bits-1))

def model(name, x):
    if name in ('sumCase', 'directCase'): return signed(x-3 if x < 0 else x+8)
    if name == 'nestedCase': return signed(2*x+2) if x < 0 else 11 if x == 0 else 2
    if name == 'lazyCase': return x if x < 0 else 3
    if name == 'zeroCase': return 17 if x < 0 else 23
    if name == 'unitCase': return 31 if x < 0 else 37
    if name == 'boxedKindsCase': return 41 if x < 0 else 0
    if name == 'floatDoubleCase': return 3 if x < 0 else 2
    if name == 'narrowWideCase': return signed(x, 32) if x < 0 else x
    if name == 'threeWayCase': return 13 if x < 0 else 17 if x == 0 else x
    raise ValueError(name)

def validate_layout(record):
    check(record.get('tagSlot') == 0 and type(record.get('tagSlot')) is int, 'Sum tag slot must be exact zero')
    expected = alternative_slots(record.get('alternatives'), record.get('primReps'))
    check('alternativeSlots' in record and record['alternativeSlots'] == expected, 'Wrong or missing sum projection')
    if expected is not None:
        check(all(type(index) is int for row in record['alternativeSlots'] for index in row), 'Invalid slot index')

def inventory(stage):
    path = OUT / f'{stage}-core/SumLayoutAudit.json'
    module = json.loads(path.read_text())
    check(module['ghc'] == '9.14.1' and module['schema'] == 1 and module['boundary'] == STAGES[stage], 'Wrong export provenance')
    bindings = {b['name']: b for b in module['bindings']}
    for name, expected in EXPECTED.items():
        expression = bindings[name]['expr']
        check(expression[0] == 'lam' and expression[3]['resultRep'] == expected, stage+'/'+name+': result layout changed')
        if name.endswith('Identity') or name == 'abstractAlternative':
            check(expression[1][0]['rep'] == expected and expression[2][0] == 'var', name+': constructor-free alias lost')
    records = 0
    for record in walk(module):
        if isinstance(record, dict):
            if record.get('aggregate') == 'unboxed-sum':
                validate_layout(record); records += 1
            else:
                check('tagSlot' not in record and 'alternativeSlots' not in record, 'Non-sum acquired sum slots')
    families = {}
    for constructor in module['constructors']:
        if constructor['kind'] == 'unboxed-sum':
            size, tag = constructor.get('sumArity'), constructor['tag']
            check(size in (2, 3) and type(size) is int and type(tag) is int and 1 <= tag <= size,
                  'Invalid sum constructor family or tag')
            check(constructor['arity'] == 1 and constructor['fieldReps'] == [None], 'Sum payload arity is not family arity')
            families.setdefault(size, set()).add(tag)
        else: check('sumArity' not in constructor, 'Non-sum acquired sum family arity')
    check(families == {2: {1, 2}, 3: {1, 2, 3}}, 'Missing native sum constructor families')
    spec = importlib.util.spec_from_file_location('sum_audit', ROOT/'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
    cap = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
    supported = SUPPORTED if 'unboxed-sum' in cap.get('aggregateResults', []) else {'directCase'}
    roots = [*EXPECTED, *ENTRIES]
    reports = []
    for name in roots:
        report = audit.Audit([(str(path), module)], cap).run([name])
        check(report['accepted'] == (name in supported), stage+'/'+name+': sum rejection changed')
        if name not in supported:
            check(any(i['code'] in ('aggregate-representation', 'aggregate-boundary') and i['detail'].startswith('unboxed-sum') for i in report['issues']), name+': missing explicit sum rejection')
        reports.append(dict(entry=name, accepted=report['accepted'], firstIssue=report['issues'][0] if report['issues'] else None))
    return dict(stage=stage, records=records, exactResultShapes=len(EXPECTED), audits=reports)

def record(path):
    path = Path(path)
    return dict(path=str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path),
                sha256=hashlib.sha256(path.read_bytes()).hexdigest())

def prepare():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT/'provenance.json').unlink(missing_ok=True)
    ghc, pkg = os.environ.get('GHC', 'ghc'), os.environ.get('GHC_PKG', 'ghc-pkg')
    def output(argv): return subprocess.check_output(argv, cwd=ROOT, text=True).strip()
    check(output([ghc, '--numeric-version']) == '9.14.1', 'Requires pinned GHC9.14.1')
    commands = []
    def run(argv, env=None):
        commands.append(dict(argv=list(map(str, argv)), environment=env or {}))
        completed = subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(env or {})), text=True, capture_output=True)
        if completed.returncode:
            raise RuntimeError(f'Command failed: {argv}\n{completed.stdout}\n{completed.stderr}')
        return completed.stdout
    run(['compiler/build.sh'])
    for stage in STAGES:
        flags = ['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []
        run(['compiler/export.sh', *flags, str(FIXTURE)], dict(THC_CORE_OUT=str(OUT/f'{stage}-core'), THC_GHC_OUT=str(OUT/f'{stage}-ghc'), THC_SOURCE_NOTES='true'))
    native = OUT/'native'; native.mkdir(exist_ok=True)
    binary = native/'sum-layout-oracle'
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', '-icompiler/test-fixtures',
         '-odir', str(native), '-hidir', str(native), str(NATIVE), '-o', str(binary)])
    oracle = run([str(binary)])
    expected = ''.join(f'{name}\t{x}\t{model(name,x)}\n' for name in ENTRIES for x in INPUTS)
    check(oracle == expected, 'Native sum oracle differs from independent model')
    (OUT/'oracle.tsv').write_text(oracle)
    sources = [FIXTURE, NATIVE, Path(__file__).resolve(), ROOT/'scripts/sum_layout_model.py', ROOT/'scripts/test-sum-layout.py',
               ROOT/'scripts/audit-core.py', ROOT/'scripts/core-capabilities.json', ROOT/'src/main/resources/thc/scalar-primop-signatures.json',
               *sorted((ROOT/'scripts').glob('core_*.py')), *sorted((ROOT/'compiler/THC').glob('*.hs')),
               *[ROOT/'compiler'/n for n in ('build.sh','export.sh','toolchain.sh')]]
    artifacts = [OUT/'oracle.tsv', *[p for directory in ('native', 'pre-core', 'pre-ghc', 'post-core', 'post-ghc')
                 for p in sorted((OUT/directory).rglob('*')) if p.is_file()]]
    artifacts += sorted((ROOT/'build/compiler').glob('libHSthc-core-plugin-*'))
    toolchain = dict(version='9.14.1', ghc=record(Path(shutil.which(ghc) or ghc).resolve()),
                     ghcPkg=record(Path(shutil.which(pkg) or pkg).resolve()), ghcInfo=output([ghc,'--info']),
                     packages={name:output([pkg,'describe',name]) for name in ('ghc','base','ghc-internal','ghc-prim')})
    return dict(schema=1, nativeRows=len(ENTRIES)*len(INPUTS), entries=ENTRIES, inputs=INPUTS, toolchain=toolchain,
                sources=[record(p) for p in sources], artifacts=[record(p) for p in artifacts], commands=commands,
                claim='Exact sum layout evidence with capability-gated result/case execution and explicit unsupported boundaries.')

def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--prepare', action='store_true'); args=parser.parse_args()
    provenance = prepare() if args.prepare else json.loads((OUT/'provenance.json').read_text())
    for item in provenance['sources']+provenance['artifacts']+[provenance['toolchain']['ghc'], provenance['toolchain']['ghcPkg']]:
        check(record(ROOT/item['path']) == item, 'Stale sum layout evidence: '+item['path'])
    coverage = [inventory(stage) for stage in STAGES]
    if args.prepare: (OUT/'provenance.json').write_text(json.dumps(provenance, indent=2)+'\n')
    (OUT/'checks.json').write_text(json.dumps(dict(coverage=coverage, nativeRows=provenance['nativeRows'],
        provenance=record(OUT/'provenance.json'), supportedSumEntries=sum(r['accepted'] and r['entry'] != 'directCase' for r in coverage[0]['audits'])), indent=2)+'\n')
    print(f"Sum metadata: {len(EXPECTED)} shapes × 2 stages, {provenance['nativeRows']} native/model rows; capability boundaries checked")

if __name__ == '__main__': main()
