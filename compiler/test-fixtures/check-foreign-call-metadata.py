#!/usr/bin/env python3
"""Check genuine ForeignCallAudit exports, not runtime FFI support."""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'scripts'))


def require(condition, detail):
    if not condition:
        raise AssertionError(detail)


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def exact(actual, expected):
    """Exact keys, JSON scalar types and values, including bool versus int."""
    if type(actual) is not type(expected):
        return False
    if isinstance(expected, dict):
        return actual.keys() == expected.keys() and all(exact(actual[k], v) for k, v in expected.items())
    if isinstance(expected, list):
        return len(actual) == len(expected) and all(exact(a, b) for a, b in zip(actual, expected))
    return actual == expected


def rep(kind, prims, evaluated=False):
    return dict(kind=kind, primReps=prims, evaluated=evaluated)


ADDR, STATE, INT32, DOUBLE = rep('address', ['AddrRep']), rep('void', []), rep('long', ['Int32Rep']), rep('double', ['DoubleRep'])


def descriptor(symbol, arguments, safety='unsafe', double_result=False):
    result = dict(rep('unknown', ['DoubleRep'] if double_result else []), aggregate='unboxed-tuple',
                  components=[rep('void', [], True)] + ([rep('double', ['DoubleRep'], True)] if double_result else []))
    target = dict(kind='static', symbol=symbol, unit='main', isFunction=True) if symbol else dict(kind='dynamic')
    return dict(schema=1, target=target, convention='ccall', safety=safety,
                arity=len(arguments), suppliedArity=len(arguments), argumentReps=arguments, resultRep=result)


EXPECTED = {
    'md5Init': descriptor('__hsbase_MD5Init', [ADDR, STATE]),
    'md5Update': descriptor('__hsbase_MD5Update', [ADDR, ADDR, INT32, STATE]),
    'md5Final': descriptor('__hsbase_MD5Final', [ADDR, ADDR, STATE]),
    'safeInit': descriptor('__hsbase_MD5Init', [ADDR, STATE], 'safe'),
    'interruptibleInit': descriptor('__hsbase_MD5Init', [ADDR, STATE], 'interruptible'),
    'wrongSignatureInit': descriptor('__hsbase_MD5Init', [ADDR, INT32, STATE]),
    'dynamicInit': descriptor(None, [ADDR, ADDR, STATE]),
    'unknownCall': descriptor('__thc_unsupported_foreign', [DOUBLE, STATE], double_result=True),
}


def without_evaluated(value):
    if isinstance(value, dict):
        return {k: without_evaluated(v) for k, v in value.items() if k != 'evaluated'}
    if isinstance(value, list):
        return list(map(without_evaluated, value))
    return value


def check_module(module, auditor, capabilities):
    require(module['schema'] == 1 and module['unit'] == 'main', 'Wrong fixture schema/unit')
    found = {}
    for binding in module['bindings']:
        for node in walk(binding['expr']):
            if isinstance(node, list) and node and isinstance(node[-1], dict) and 'foreignCall' in node[-1]:
                require(node[0] == 'app' and node[1][0] == 'var', 'Foreign descriptor moved executable shape')
                name = binding['name']
                require(name in EXPECTED and name not in found, 'Unexpected or repeated foreign application')
                expected = EXPECTED[name]
                require(exact(node[-1]['foreignCall'], expected), 'Wrong exact descriptor: '+name)
                require(len(node[2]) == expected['arity'] and len(node[3]) == expected['arity'], 'Wrong application arity')
                for arg, declared in zip(node[2], expected['argumentReps']):
                    require(exact(without_evaluated(arg[-1]['rep']), without_evaluated(declared)), 'Argument proof mismatch')
                require(exact(without_evaluated(node[-1]['rep']), without_evaluated(expected['resultRep'])), 'Result proof mismatch')
                found[name] = node[-1]['foreignCall']
    require(found.keys() == EXPECTED.keys(), 'Missing genuine foreign controls')
    for name in [*EXPECTED, '__hsbase_MD5Init']:
        report = auditor.Audit([('ForeignCallAudit.json', module)], capabilities).run([name])
        if name in EXPECTED:
            issues = [('aggregate-boundary', 'unboxed-tuple host result')]
            if EXPECTED[name]['target'].get('symbol') in ('__hsbase_MD5Init', '__hsbase_MD5Update', '__hsbase_MD5Final'):
                # These genuine declarations belong to main, not ghc-internal:
                # the closed MD5 adapter must reject them, never relabel them.
                issues.append(('foreign-call-representation', 'Invalid MD5 foreign call: static ghc-internal function target'))
            require(not report['accepted'] and len(report['missingGlobals']) == 1
                    and [(i['code'], i['detail']) for i in report['issues']] == issues, 'Foreign frontier changed: '+name)
        else:
            require(report['accepted'] and not report['missingGlobals'] and not report['issues'], 'Ordinary same-name Haskell control changed')
    return found


def compare_expressions(before, after):
    """No signature inference: only original-ID equality modulo GHC uniques."""
    old = {b['name']: b for b in before['bindings']}
    new = {b['name']: b for b in after['bindings']}
    require(old.keys() == new.keys(), 'Binding set changed')
    renaming = {}
    def compare(a, b):
        require(type(a) is type(b), 'Expression JSON type changed')
        if isinstance(a, dict):
            ignored = {'foreignCall', 'source', 'sourceNotes'}
            ak, bk = a.keys()-ignored, b.keys()-ignored
            require(ak == bk, 'Existing expression metadata keys changed')
            for k in ak: compare(a[k], b[k])
        elif isinstance(a, list):
            require(len(a) == len(b), 'Expression arity changed')
            for x, y in zip(a, b): compare(x, y)
        elif a != b:
            require(isinstance(a, str) and a.startswith('main:ForeignCallAudit.') and b.startswith('main:ForeignCallAudit.'),
                    'Non-identifier expression evidence changed')
            require(re.sub(r'_[A-Za-z0-9]+$', '', a) == re.sub(r'_[A-Za-z0-9]+$', '', b), 'Identifier changed beyond GHC unique')
            require(a not in renaming or renaming[a] == b, 'Inconsistent identifier renaming')
            renaming[a] = b
    for name in old:
        compare(old[name]['expr'], new[name]['expr'])
        for field in ('arity', 'lifted', 'rep', 'joinValueArity', 'joinResultRep', 'entryStrict'):
            require(exact(old[name].get(field), new[name].get(field)), 'Binding proof changed: '+field)
    require(len(renaming) == len(set(renaming.values())), 'Non-bijective identifier renaming')
    return len(renaming)


def malformed_controls():
    original = EXPECTED['md5Init']
    changes = [('schema', 1.0), ('arity', True), ('arity', 2.0), ('suppliedArity', 1),
               ('convention', 'capi'), ('safety', 'safe'), ('safety', 'interruptible'),
               ('target', {'kind': 'dynamic'}), ('argumentReps', [ADDR, INT32]),
               ('resultRep', STATE)]
    mutations = []
    for field, value in changes:
        mutated = copy.deepcopy(original); mutated[field] = value; mutations.append(mutated)
    for field, value in [('symbol', '__hsbase_MD5Final'), ('unit', None), ('unit', 'ghc-internal'), ('isFunction', False)]:
        mutated = copy.deepcopy(original); mutated['target'][field] = value; mutations.append(mutated)
    mutated = copy.deepcopy(original); mutated['resultRep']['components'] = []; mutations.append(mutated)
    mutated = copy.deepcopy(original); mutated['argumentReps'][0]['primReps'] = ['IntRep']; mutations.append(mutated)
    for changed in mutations:
        require(not exact(changed, original), 'Malformed metadata control escaped exact reader')
    return len(mutations)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('modules', type=Path, nargs='+')
    parser.add_argument('--before', type=Path, action='append', default=[])
    args = parser.parse_args()
    require(not args.before or len(args.before) == len(args.modules), 'One baseline per module required')
    spec = importlib.util.spec_from_file_location('foreign_auditor', ROOT/'scripts/audit-core.py')
    auditor = importlib.util.module_from_spec(spec); spec.loader.exec_module(auditor)
    capabilities = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
    stages = set()
    for i, path in enumerate(args.modules):
        module = json.loads(path.read_text()); stages.add(module['boundary'])
        found = check_module(module, auditor, capabilities)
        renamed = None
        if args.before:
            before = json.loads(args.before[i].read_text())
            require(before['boundary'] == module['boundary'], 'Mismatched baseline stage')
            require(not any(isinstance(x, dict) and 'foreignCall' in x for x in walk(before)), 'Baseline already has descriptors')
            renamed = compare_expressions(before, module)
        print(f'PASS {path}: {len(found)} exact descriptors; 8 strict foreign frontiers; 1 ordinary control; renamed IDs={renamed}')
    require(stages == {'optimized-Core-before-Tidy', 'optimized-Core-after-Tidy-before-CorePrep'}, 'Both genuine Core stages required')
    print(f'PASS {malformed_controls()} malformed descriptor controls (reader-only, not runtime support)')


if __name__ == '__main__':
    main()
