#!/usr/bin/env python3
"""Check the unsupported aggregate frontier separately from executable coverage.

Requires genuine GHC -O2 pre/post-Tidy exports and a native oracle. Rejection is
the expected capability result; no row contributes to the supported corpus.
"""
import argparse
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('audit_core', ROOT / 'scripts/audit-core.py')
audit_core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit_core)
CAP = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
CASES = {
    'tupleOutstanding': ['tuple', 'opaque-producer', 'forwarder', 'two-outstanding-results', 'unequal-weights'],
    'tupleZeroLazy': ['tuple', 'nested-zero-width', 'lazy-bottom-payload'],
    'sumPayload': ['sum', 'both-alternatives', 'tuple-payload', 'long-and-reference'],
    'sumZeroLazy': ['sum', 'both-alternatives', 'zero-width-alternative', 'lazy-bottom-payload'],
    'coldTuple': ['tuple', 'cold-branch'],
    'coldSum': ['sum', 'cold-branch'],
    'emptyIdentity': ['constructor-free', 'zero-width', 'tuple'],
    'emptyDiscard': ['constructor-free', 'zero-width', 'unused-formal', 'tuple'],
    'singletonIdentity': ['constructor-free', 'one-register', 'tuple'],
    'pairIdentity': ['constructor-free', 'multiple-registers', 'tuple'],
    'sumIdentity': ['constructor-free', 'multiple-registers', 'sum'],
}
INPUTS = [-(1 << 63), -4097, -1, 0, 1, 4097, 3000000000, (1 << 63) - 1]


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def nodes(value):
    yield value
    if isinstance(value, dict):
        for child in value.values():
            yield from nodes(child)
    elif isinstance(value, list):
        for child in value:
            yield from nodes(child)


def variables(expr):
    return {n[1] for n in nodes(expr) if isinstance(n, list) and len(n) >= 2 and n[0] == 'var'}


def inventory(path, stage):
    module = json.loads(path.read_text())
    bindings = {b['name']: b for b in module['bindings']}
    check(module['ghc'] == '9.14.1', 'Pinned compiler mismatch')
    first = bindings['tupleOutstanding']['expr'][2]
    check(first[0] == 'case' and first[1][0] == 'app' and first[1][1][1] == bindings['forward']['id'], 'Lost first opaque tuple call')
    first_alt = first[3][0]
    second = first_alt[3]
    check(second[0] == 'case' and second[1][0] == 'app' and second[1][1][1] == bindings['pair']['id'], 'Lost second opaque tuple call')
    second_alt = second[3][0]
    fields = first_alt[2] + second_alt[2]
    check(len(fields) == 4 and set(fields) <= variables(second_alt[3]), 'All four fields must remain live after the second call')
    literals = {n[2] for n in nodes(second_alt[3]) if isinstance(n, list) and len(n) >= 3 and n[0] == 'lit'}
    check({'3', '5', '7'} <= literals, 'Unequal tuple field weights disappeared')
    forward = bindings['forward']['expr']
    check(forward[0] == 'lam' and forward[2][0] == 'app' and forward[2][1][1] == bindings['pair']['id'], 'Forwarder must retain a real tail call')
    check(bindings['stateIdentity']['expr'][3]['resultRep'] == dict(primReps=[], kind='void', evaluated=True), 'State token control changed')
    empty = bindings['emptyIdentity']['expr'][3]['resultRep']
    check(empty['primReps'] == [] and empty['aggregate'] == 'unboxed-tuple', 'Lost logical empty tuple evidence')
    singleton = bindings['singletonIdentity']['expr'][3]['resultRep']
    check(singleton['primReps'] == ['IntRep'] and singleton['aggregate'] == 'unboxed-tuple', 'Lost singleton tuple evidence')
    check(any(isinstance(n, dict) and n.get('aggregate') == 'unboxed-tuple' and n.get('primReps') == []
              for n in nodes(bindings['zeroLazy']['expr'])), 'Zero-width tuple component disappeared')
    rows = []
    for entry, features in CASES.items():
        report = audit_core.Audit([(str(path), module)], CAP).run([entry])
        codes = sorted({i['code'] for i in report['issues']})
        check(not report['accepted'] and 'aggregate-representation' in codes, f'{stage}/{entry}: must reject aggregate type evidence')
        check(not report['missingGlobals'], f'{stage}/{entry}: unrelated missing globals')
        constructors = report['constructors']
        if 'constructor-free' in features:
            check(not constructors, f'{entry}: must expose a constructor-free boundary')
        else:
            check('constructor-kind' in codes, f'{entry}: must also exercise constructor rejection')
        if entry in ('sumPayload', 'sumZeroLazy'):
            tags = {c['metadata']['tag'] for c in constructors if c['metadata']['kind'] == 'unboxed-sum'}
            check(tags == {1, 2}, f'{entry}: both sum alternatives must survive')
        rows.append(dict(entry=entry, supported=False, expected='reject-at-load', features=features,
                         auditAccepted=report['accepted'], issueCodes=codes,
                         aggregateKinds=sorted({i['detail'] for i in report['issues'] if i['code'] == 'aggregate-representation'}),
                         reachableBindings=sorted(r['id'].split('.')[-1] for r in report['reachableBindings']),
                         constructorKinds=sorted({c['metadata']['kind'] for c in constructors})))
    for control in ('abstractIdentity', 'stateIdentity'):
        check(audit_core.Audit([(str(path), module)], CAP).run([control])['accepted'], f'{control}: unrelated aggregate globals must remain unreachable')
    return dict(stage=stage, entries=rows)


def wrap(x):
    return (x + (1 << 63)) % (1 << 64) - (1 << 63)


def check_native(path):
    formulas = {
        'tupleOutstanding': lambda x: 16*x + 39428,
        'tupleZeroLazy': lambda x: x + 4099 if x <= 0 else 4*x + 2554,
        'sumPayload': lambda x: 3*x + 51 if x <= 0 else 12*x + 5890,
        'sumZeroLazy': lambda x: x + 19 if x <= 0 else x - 23,
        'coldTuple': lambda x: x + 5,
        'coldSum': lambda x: x - 7,
    }
    actual = {}
    for line in path.read_text().splitlines():
        name, value, result = line.split('\t')
        key = (name, int(value))
        check(key not in actual, f'Duplicate native row: {key}')
        actual[key] = int(result)
    expected = {(name, x): wrap(formula(x)) for name, formula in formulas.items() for x in INPUTS}
    check(actual == expected, 'Native aggregate oracle disagrees with bounded independent wraparound formulas')
    check(31337 not in INPUTS, 'Cold branch inputs accidentally exercised')
    return dict(rows=len(actual), inputs=INPUTS, coldBranchInput=31337, coldBranchesExecuted=False,
                purpose='future semantic oracle only; no THC execution success claimed')


def check_unknown_compatibility():
    for registers in (None, [], ['IntRep'], ['IntRep', 'IntRep'], ['BoxedRep (Just Lifted)'], ['FloatRep']):
        audit = audit_core.Audit([], CAP)
        proof = dict(kind='unknown', primReps=registers, evaluated=True)
        audit.representation(proof, None, '/compatibility')
        check(not audit.issues, 'Unknown metadata must not infer aggregate shape from registers')
        audit.representation(dict(proof, aggregate='unboxed-tuple'), None, '/explicit-marker')
        check([i['code'] for i in audit.issues] == ['aggregate-representation'], 'Explicit aggregate marker must reject')
    audit = audit_core.Audit([], CAP)
    audit.representation(dict(kind='unknown', primReps=None, evaluated=True, aggregate=['invalid']), None, '/invalid-marker')
    check([i['code'] for i in audit.issues] == ['representation-proof'], 'Invalid aggregate marker must fail closed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pre', type=Path, default=ROOT / 'build/aggregate-core/AggregateFrontier.json')
    parser.add_argument('--post', type=Path, default=ROOT / 'build/aggregate-post-core/AggregateFrontier.json')
    parser.add_argument('--oracle', type=Path, default=ROOT / 'build/aggregate-native/oracle.tsv')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/aggregate-frontier.json')
    args = parser.parse_args()
    check_unknown_compatibility()
    report = dict(schema=1, category='unsupported-aggregate-frontier', supportedEntries=0,
                  coverage=[inventory(args.pre, 'pre-tidy'), inventory(args.post, 'post-tidy')],
                  nativeOracle=check_native(args.oracle))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(f'Aggregate frontier: {len(CASES)} unsupported entries in both export stages; {report["nativeOracle"]["rows"]} native-only oracle rows; 0 supported entries')


if __name__ == '__main__':
    main()
