#!/usr/bin/env python3
"""Audit real GHC CBV entry obligations without upgrading WHNF evidence."""
import argparse
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def walk(value):
    yield value
    if isinstance(value, dict):
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def definitions(module):
    return [v for v in walk(module['bindings'])
            if isinstance(v, dict) and 'expr' in v and 'info' in v]


def audit(module):
    result = Counter()
    marked = []
    for binding in definitions(module):
        result['definitions'] += 1
        info = binding['info']
        assert type(info['cbvEligible']) is bool, binding['id']
        raw = info['cbvMarks']
        assert raw is None or (isinstance(raw, list) and all(type(x) is bool for x in raw)), binding['id']
        marks = binding['entryStrict']
        assert isinstance(marks, list) and all(type(x) is bool for x in marks), binding['id']
        origin = binding['entryStrictSource']
        assert origin in ('none', 'ghc-id', 'ghc-tidy-proposal'), binding['id']
        if origin == 'none':
            assert not any(marks), binding['id']
        if origin == 'ghc-id':
            assert raw and any(raw) and marks[:len(raw)] == raw, binding['id']
        if origin == 'ghc-tidy-proposal':
            assert info['cbvEligible'] and not any(raw or []) and any(marks), binding['id']
        expression = binding['expr']
        if expression[0] == 'lam':
            assert len(marks) == len(expression[1]), binding['id']
            assert expression[3]['entryStrict'] == marks, binding['id']
            assert expression[3]['entryStrictSource'] == origin, binding['id']
        if 'joinValueArity' in binding:
            assert not any(marks[binding['joinValueArity']:]), binding['id']
        if any(marks):
            result['markedDefinitions'] += 1
            result['markedArguments'] += sum(marks)
            result[origin] += 1
            marked.append({'id': binding['id'], 'name': binding['name'], 'entryStrict': marks,
                           'source': origin, 'joinValueArity': binding.get('joinValueArity')})
    return {'module': module['module'], 'counts': dict(result), 'marked': marked}


def fixture_checks(worker, joins, coercions):
    constructors = {c['id']: c for c in worker['constructors']}
    char = constructors['ghc-internal:GHC.Internal.Types.C#']
    assert char['name'] == 'C#' and char['arity'] == 1 and char['kind'] == 'boxed', char
    assert char['fieldReps'] == [['WordRep']] and char['fieldLifted'] == [False], char
    workers = {b['name']: b for b in definitions(worker)}
    assert workers['charBox']['expr'][0] == 'lam', workers['charBox']
    for name in ('plainStrict', 'lazyIgnore'):
        assert not any(workers[name]['entryStrict']), workers[name]
    marked_workers = [b for b in definitions(worker) if any(b['entryStrict'])]
    assert marked_workers, 'Real worker-wrapper fixture must produce a CBV worker'
    assert any('walk' in b['name'] for b in marked_workers)
    # Even on marked formals, the original Core WHNF fact is not rewritten.
    for binding in marked_workers:
        assert binding['entryStrictSource'] == 'ghc-tidy-proposal'
        if binding['expr'][0] == 'lam':
            for parameter, strict in zip(binding['expr'][1], binding['entryStrict']):
                if strict:
                    assert parameter['lifted'] is True and not parameter['rep']['evaluated'], parameter
    local = {b['name']: b for b in definitions(joins) if 'joinValueArity' in b}
    done = local['done']
    assert done['info']['joinArity'] == 3 and done['joinValueArity'] == 2, done
    assert done['entryStrict'] == [False, True], done
    returned = local['doneFunction']
    assert returned['info']['joinArity'] == 2 and returned['joinValueArity'] == 1, returned
    assert len(returned['expr'][1]) == 2 and returned['entryStrict'] == [False, False], returned
    coercion_before_mark = False
    for binding in definitions(coercions):
        expression = binding['expr']
        if expression[0] != 'lam':
            continue
        parameters = expression[1]
        for index, mark in enumerate(binding['entryStrict']):
            if mark and any(p['coercion'] for p in parameters[:index]):
                assert parameters[index]['lifted'] is True, binding
                for p, required in zip(parameters, binding['entryStrict']):
                    if p['coercion']:
                        assert p['rep']['primReps'] == [] and not required, binding
                coercion_before_mark = True
    assert coercion_before_mark, 'Real worker must retain a coercion before a CBV lifted argument'


def compare_actual_tidy(before, after):
    """A derived contract must agree with the actual post-Tidy Id marks."""
    post = {}
    for binding in definitions(after):
        post.setdefault(binding['name'], []).append(binding)
    matches = 0
    for binding in definitions(before):
        if not any(binding['entryStrict']):
            continue
        candidates = post.get(binding['name'], [])
        assert len(candidates) == 1, (binding['name'], 'must find unique post-Tidy counterpart')
        actual = candidates[0]
        assert actual['entryStrictSource'] == 'ghc-id', actual
        assert actual['entryStrict'] == binding['entryStrict'], (binding, actual)
        matches += 1
    assert matches, 'Fixture must exercise at least one actual GHC CBV contract'
    return matches


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('modules', nargs='*', type=Path)
    parser.add_argument('--json', action='store_true', help='Print per-definition audit results')
    parser.add_argument('--post-tidy-dir', type=Path, default=ROOT / 'build/cbv-post-core')
    args = parser.parse_args()
    files = args.modules or [ROOT / 'build/core/CBVAudit.json', ROOT / 'build/core/CBVJoinAudit.json', ROOT / 'build/core/CBVCoercionAudit.json']
    modules = [json.loads(p.read_text()) for p in files]
    summaries = [audit(module) for module in modules]
    if not args.modules:
        fixture_checks(*modules)
        for module in modules:
            actual = json.loads((args.post_tidy_dir / (module['module'] + '.json')).read_text())
            audit(actual)
            compare_actual_tidy(module, actual)
    if args.json:
        print(json.dumps(summaries, indent=2))
    else:
        for summary in summaries:
            print(f"PASS {summary['module']}: {summary['counts']}")
        if not args.modules:
            print('PASS: real worker marks, strict ordinary function exclusion, unchanged WHNF facts, erased type join prefix, returned lambda suffix, retained coercion alignment, actual post-Tidy agreement')


if __name__ == '__main__':
    main()
