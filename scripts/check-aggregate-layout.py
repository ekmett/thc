#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Check recursive aggregate evidence from genuine GHC 9.14.1 pre/post-Tidy Core.

--prepare rebuilds the exporter, compiles the fixture without the plugin, exports
both native stages, and records the exact toolchain, inputs, commands and outputs.
This is metadata and rejection coverage, never supported THC execution coverage.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
from sum_layout_model import alternative_slots

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/aggregate-layout'
FIXTURE = ROOT / 'compiler/test-fixtures/AggregateLayoutAudit.hs'
STAGES = {'pre': 'optimized-Core-before-Tidy',
          'post': 'optimized-Core-after-Tidy-before-CorePrep'}
spec = importlib.util.spec_from_file_location('audit_core', ROOT / 'scripts/audit-core.py')
audit_core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit_core)
CAP = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def walk(value):
    yield value
    children = value.values() if isinstance(value, dict) else value if isinstance(value, list) else []
    for child in children:
        yield from walk(child)


def leaf(kind, reps, evaluated=True):
    return dict(kind=kind, primReps=reps, evaluated=evaluated)


def aggregate(tag, children, reps, evaluated=True):
    field = 'components' if tag == 'unboxed-tuple' else 'alternatives'
    return dict(leaf('unknown', reps, evaluated), aggregate=tag, **{field: children})


def tup(children, reps, evaluated=True):
    return aggregate('unboxed-tuple', children, reps, evaluated)


def summ(children, reps, evaluated=True):
    return dict(aggregate('unboxed-sum', children, reps, evaluated), tagSlot=0,
                alternativeSlots=alternative_slots(children, reps))


LIFTED = 'BoxedRep (Just Lifted)'
INT = leaf('long', ['IntRep'])
WORD = leaf('long', ['WordRep'])
VOID = leaf('void', [])
EMPTY = tup([], [])
ONE = tup([INT], ['IntRep'])
BOX = leaf('data', [LIFTED], False)
UNKNOWN = leaf('unknown', None, False)
INNER_SUM = summ([INT, BOX], ['WordRep', LIFTED, 'WordRep'])
NESTED = tup([EMPTY, VOID, ONE,
              summ([INT, tup([BOX, tup([WORD, VOID], ['WordRep'])], [LIFTED, 'WordRep'])],
                   ['WordRep', LIFTED, 'WordRep'])],
             ['IntRep', 'WordRep', LIFTED, 'WordRep'])
ALIASED_TUPLE = tup([EMPTY, INT], ['IntRep'])
EXPECTED = {
    'nestedIdentity': NESTED,
    'lazyIdentity': tup([BOX, leaf('closure', [LIFTED], False),
                         *[leaf('object', [LIFTED], False) for _ in range(4)]], [LIFTED] * 6),
    'alternativesIdentity': summ([EMPTY, VOID, ONE,
                                  tup([leaf('float', ['FloatRep']), leaf('double', ['DoubleRep']), INT],
                                      ['FloatRep', 'DoubleRep', 'IntRep']), INNER_SUM],
                                 ['WordRep', LIFTED, 'WordRep', 'WordRep', 'FloatRep', 'DoubleRep']),
    'polymorphicTuple': tup([UNKNOWN, INT], None, False),
    'polymorphicSum': summ([UNKNOWN, INT], None, False),
    'polymorphicNested': tup([tup([UNKNOWN, VOID], None), summ([UNKNOWN, INT], None)], None, False),
    'levityPolymorphic': tup([leaf('object', ['BoxedRep Nothing'], False), INT], None, False),
    'tupleAliasIdentity': ALIASED_TUPLE,
    'sumAliasIdentity': summ([VOID, BOX], ['WordRep', LIFTED]),
    'nestedAliasIdentity': ALIASED_TUPLE,
    'emptyAliasIdentity': EMPTY,
    'abstractTupleRep': tup(None, None, False),
    'abstractSumRep': summ(None, None, False),
    'abstractFixedTupleIdentity': tup(None, ['IntRep']),
    'abstractEmptyIdentity': tup(None, []),
    'abstractSumIdentity': summ(None, ['WordRep', LIFTED, 'WordRep']),
    'familyTupleIdentity': tup(None, ['IntRep']),
    'abstractComponentIdentity': tup([tup(None, ['IntRep']), INT], ['IntRep', 'IntRep']),
}
BOXED_CONTROLS = {
    'boxedPairIdentity': leaf('data', [LIFTED], False),
    'boxedUnitIdentity': leaf('data', [LIFTED], False),
    'boxedSoloIdentity': leaf('data', [LIFTED], False),
    'unliftedProductIdentity': leaf('data', ['BoxedRep (Just Unlifted)']),
}


def inventory(stage):
    path = OUT / f'{stage}-core/AggregateLayoutAudit.json'
    module = json.loads(path.read_text())
    check(module['schema'] == 1 and module['ghc'] == '9.14.1', 'Pinned schema/compiler mismatch')
    check(module['boundary'] == STAGES[stage], f'{stage}: wrong real export boundary')
    bindings = {b['name']: b for b in module['bindings']}
    records = 0
    for value in walk(module):
        if not isinstance(value, dict) or 'aggregate' not in value:
            continue
        records += 1
        tag = value['aggregate']
        check(tag in ('unboxed-tuple', 'unboxed-sum'), 'Unexpected aggregate marker')
        field = 'components' if tag == 'unboxed-tuple' else 'alternatives'
        other = 'alternatives' if field == 'components' else 'components'
        check(value['kind'] == 'unknown' and other not in value, 'Conflicting layout metadata')
        check(value[field] is None or isinstance(value[field], list), 'Invalid recursive logical layout')
        if value[field] is not None:
            check(all(isinstance(child, dict) and {'kind', 'primReps', 'evaluated'} <= child.keys()
                      for child in value[field]), 'Component is not representation evidence')
    for name, expected in EXPECTED.items():
        expr = bindings[name]['expr']
        check(expr[0] == 'lam', f'{stage}/{name}: lost the actual function boundary')
        check(expr[3]['resultRep'] == expected, f'{stage}/{name}: wrong logical/physical result layout')
        if name.endswith('Identity'):
            check(expr[1][0]['rep'] == expected, f'{stage}/{name}: wrong formal layout')
            check(expr[2][0] == 'var', f'{stage}/{name}: identity must remain constructor-free')
        report = audit_core.Audit([(str(path), module)], CAP).run([name])
        check(not report['accepted'], f'{stage}/{name}: aggregates must remain rejected')
        check(any(i['code'] in ('aggregate-representation', 'aggregate-boundary') for i in report['issues']),
              f'{stage}/{name}: missing explicit aggregate rejection')
        check(not report['missingGlobals'], f'{stage}/{name}: unrelated missing globals')
        if name.endswith('Identity'):
            check(not report['constructors'], f'{stage}/{name}: constructor fallback masks the boundary')
    # Null layouts above prove a boundary without inventing logical components
    # from an abstract type's RuntimeRep, including zero/one physical registers.
    recursive = bindings['recursiveNewtypeIdentity']['expr']
    check(recursive[1][0]['rep'] == leaf('object', [LIFTED], False),
          'Recursive scalar newtype changed classification or unwrapping did not terminate')
    check(audit_core.Audit([(str(path), module)], CAP).run(['recursiveNewtypeIdentity'])['accepted'],
          'Unreachable aggregate bindings must not reject the scalar newtype control')
    for name in ('stateAliasIdentity', 'proxyIdentity'):
        primitive = bindings[name]['expr']
        check(primitive[1][0]['rep'] == VOID and primitive[3]['resultRep'] == VOID,
              f'{name}: an exposed zero-width primitive must not become an empty tuple')
        check(audit_core.Audit([(str(path), module)], CAP).run([name])['accepted'],
              f'{name}: a known primitive/alias must remain supported')
    for name, expected in BOXED_CONTROLS.items():
        expr = bindings[name]['expr']
        check(expr[1][0]['rep'] == expected and expr[3]['resultRep'] == expected,
              f'{stage}/{name}: boxedness must not be inferred from liftedness or tuple syntax')
        check(not any(isinstance(value, dict) and {'aggregate', 'components', 'alternatives'} & value.keys()
                      for value in walk(bindings[name])), f'{stage}/{name}: boxed value acquired an aggregate layout')
        check(audit_core.Audit([(str(path), module)], CAP).run([name])['accepted'],
              f'{stage}/{name}: ordinary boxed control must remain accepted')
    for name, register in (('boxedLazy', LIFTED), ('unliftedLazy', 'BoxedRep (Just Unlifted)')):
        expr = bindings[name]['expr']
        check(expr[3]['resultRep'] == leaf('data', [register]),
              f'{stage}/{name}: constructed boxed object should be in WHNF')
        payloads = [value for value in walk(expr) if isinstance(value, list) and len(value) == 3
                    and value[0] == 'var' and value[1] == bindings['bottomBox']['id']]
        check(payloads and all(value[2]['rep'] == BOX for value in payloads),
              f'{stage}/{name}: enclosing WHNF must not evaluate the bottom payload')
        check(audit_core.Audit([(str(path), module)], CAP).run([name + 'Use'])['accepted'],
              f'{stage}/{name}: lazy payload observer must remain accepted')
    constructors = {c['name']: c for c in module['constructors']}
    for name in ('UnliftedProduct', '(,)'):
        con = constructors[name]
        check(con['kind'] == 'boxed' and con['fieldReps'] == [[LIFTED], [LIFTED]],
              f'{stage}/{name}: boxed product lost its lazy reference fields')
        check(con['strictFields'] == [False, False] and
              all(field['evaluated'] is False for field in con['fieldTypes']),
              f'{stage}/{name}: product evaluatedness must not make its lifted fields strict')
    return dict(stage=stage, boundary=module['boundary'], aggregateRecords=records,
                checkedLayouts=list(EXPECTED), boxedControls=list(BOXED_CONTROLS),
                lazyBoxedObservers=['boxedLazyUse', 'unliftedLazyUse'], supportedEntries=0)


def output(command):
    return subprocess.check_output(command, cwd=ROOT, text=True).strip()


def record(path):
    path = Path(path)
    try:
        label = str(path.relative_to(ROOT))
    except ValueError:
        label = str(path)
    return dict(path=label, sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def prepare():
    ghc = os.environ.get('GHC', 'ghc')
    ghc_pkg = os.environ.get('GHC_PKG', 'ghc-pkg')
    check(output([ghc, '--numeric-version']) == '9.14.1', 'GHC must be 9.14.1')
    check(output([ghc_pkg, '--version']) == 'GHC package manager version 9.14.1', 'ghc-pkg must be 9.14.1')
    OUT.mkdir(parents=True, exist_ok=True)
    commands = []

    def run(argv, env=None):
        overrides = env or {}
        commands.append(dict(argv=argv, environment=overrides, cwd=str(ROOT)))
        subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **overrides), check=True)

    run(['compiler/build.sh'])
    native = OUT / 'native'
    native.mkdir(exist_ok=True)
    run([ghc, '--make', '-no-link', '-O2', '-dynamic', '-fforce-recomp', '-dcore-lint',
         '-odir', str(native), '-hidir', str(native), str(FIXTURE)])
    for stage in STAGES:
        flags = ['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []
        run(['compiler/export.sh', *flags, str(FIXTURE)], dict(
            THC_CORE_OUT=str(OUT / f'{stage}-core'), THC_GHC_OUT=str(OUT / f'{stage}-ghc'),
            THC_SOURCE_NOTES='true'))
    sources = [FIXTURE, Path(__file__).resolve(), ROOT / 'scripts/audit-core.py',
               ROOT / 'scripts/core-capabilities.json', ROOT / 'scripts/sum_layout_model.py', ROOT / 'compiler/build.sh',
               ROOT / 'compiler/export.sh', ROOT / 'compiler/toolchain.sh',
               *sorted((ROOT / 'compiler/THC').glob('*.hs')),
               ROOT / 'thc.cabal', ROOT / 'cabal.project']
    artifacts = [p for directory in ('native', 'pre-core', 'pre-ghc', 'post-core', 'post-ghc')
                 for p in sorted((OUT / directory).rglob('*')) if p.is_file()]
    plugin_manifest = ROOT / 'build/compiler/plugin.json'
    plugin = json.loads(plugin_manifest.read_text())
    check(plugin['schema'] == 1 and plugin['unitId'] and plugin['sharedLibrary'], 'Invalid plugin manifest')
    artifacts += [plugin_manifest, Path(plugin['sharedLibrary'])]
    return dict(schema=1, recordedAtUtc=datetime.now(timezone.utc).isoformat(),
                recordingMode='fresh-native-compile-and-plugin-exports',
                toolchain=dict(ghc=record(Path(shutil.which(ghc) or ghc).resolve()),
                               ghcPkg=record(Path(shutil.which(ghc_pkg) or ghc_pkg).resolve()),
                               version='9.14.1', libdir=output([ghc, '--print-libdir']),
                               ghcInfo=output([ghc, '--info']),
                               packages={p: output([ghc_pkg, 'describe', p]) for p in
                                         ('ghc', 'base', 'ghc-internal', 'ghc-prim', 'bytestring',
                                          'containers', 'directory', 'filepath')},
                               environment={k: os.environ[k] for k in
                                            ('GHC', 'GHC_PKG', 'GHC_ENVIRONMENT', 'GHC_PACKAGE_PATH', 'GHCRTS')
                                            if k in os.environ}),
                commands=commands, sources=[record(p) for p in sources],
                artifacts=[record(p) for p in artifacts],
                limits='Native object compilation and metadata/rejection evidence only; no THC aggregate execution or benchmark claim.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare', action='store_true')
    args = parser.parse_args()
    provenance_path = OUT / 'provenance.json'
    provenance = prepare() if args.prepare else json.loads(provenance_path.read_text())
    # Rechecking an old bundle must not certify a changed fixture or exporter.
    for item in provenance['sources'] + provenance['artifacts'] + [
            provenance['toolchain']['ghc'], provenance['toolchain']['ghcPkg']]:
        check(record(ROOT / item['path']) == item, 'Stale aggregate evidence: ' + item['path'])
    coverage = [inventory(stage) for stage in STAGES]
    if args.prepare:
        provenance_path.write_text(json.dumps(provenance, indent=2) + '\n')
    report = dict(schema=1, category='unsupported-aggregate-layout', supportedEntries=0,
                  coverage=coverage, provenance=record(provenance_path))
    (OUT / 'checks.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'Aggregate layout: {len(EXPECTED)} recursive/unknown layout proofs in both native stages; '
          'polymorphic/alias regressions checked; 0 supported entries')


if __name__ == '__main__':
    main()
