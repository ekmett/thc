#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Check representation and lexical WHNF proofs in actual GHC 9.14.1 Core."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
module = json.loads((ROOT / 'build/core/RepresentationAudit.json').read_text())
bindings = {b['name']: b for b in module['bindings']}
SLOTS = dict(var=2, lit=3, app=6, lam=3, let=4, case=4, con=3, prim=2, void=1)


def walk(value):
    yield value
    if isinstance(value, list):
        for child in value:
            yield from walk(child)
    elif isinstance(value, dict):
        for child in value.values():
            yield from walk(child)


def nodes(value, tag):
    return [n for n in walk(value) if isinstance(n, list) and n and n[0] == tag]


def rep(value, kind, evaluated=None):
    assert value['kind'] == kind, value
    assert isinstance(value['primReps'], list) or value['primReps'] is None, value
    assert type(value['evaluated']) is bool, value
    if evaluated is not None:
        assert value['evaluated'] is evaluated, value


for node in walk(module['bindings']):
    if (isinstance(node, list) and node and isinstance(node[0], str) and node[0] in SLOTS
            and not (isinstance(node[-1], dict) and 'binders' in node[-1])):
        slot = SLOTS[node[0]]
        assert len(node) == slot + 1 and isinstance(node[slot], dict), node
        proof = node[slot]['rep']
        rep(proof, proof['kind'])
        if node[0] == 'case':
            binder = node[slot]['binder']
            assert binder['id'] == node[2]
            assert binder['rep']['evaluated'] is True
            for alt in node[3]:
                assert [b['id'] for b in alt[4]['binders']] == alt[2]

apply = bindings['applyLong']['expr']
rep(apply[1][0]['rep'], 'closure', False)
rep(apply[1][1]['rep'], 'long', True)
rep(apply[3]['resultRep'], 'long')
assert apply[1][1]['rep']['primReps'] == ['IntRep']
rep(bindings['addressIdentity']['expr'][1][0]['rep'], 'address', True)
rep(bindings['wrappedIdentity']['expr'][1][0]['rep'], 'object', False)
rep(bindings['familyIdentity']['expr'][1][0]['rep'], 'object', False)
rep(bindings['emptyTuple']['expr'][1][0]['rep'], 'unknown', True)
assert bindings['emptyTuple']['expr'][1][0]['rep']['primReps'] == []
rep(bindings['firstField']['expr'][1][0]['rep'], 'data', False)
fields = [b for alt in nodes(bindings['firstField'], 'data') for b in alt[4]['binders']]
assert any(b['rep']['kind'] == 'long' and b['rep']['evaluated'] for b in fields)
assert any(b['rep']['kind'] == 'data' and not b['rep']['evaluated'] for b in fields)
strict = bindings['strictField']['expr']
# Demand is strict here, but the caller still passes an unevaluated lifted value.
rep(strict[1][0]['rep'], 'data', False)
strict_field = nodes(strict, 'data')[0][4]['binders'][0]
rep(strict_field['rep'], 'data', True)
uses = [v for v in nodes(strict, 'var') if v[1] == strict_field['id']]
assert uses and all(v[2]['rep']['evaluated'] for v in uses)
joins = {b['name']: b for b in walk(module) if isinstance(b, dict) and 'joinValueArity' in b}
assert joins['done']['info']['joinArity'] == 2
assert joins['done']['joinValueArity'] == 1
rep(joins['done']['joinResultRep'], 'long')
returned = joins['doneFunction']
assert returned['info']['joinArity'] == 2 and returned['joinValueArity'] == 1
assert len(returned['expr'][1]) == 2, 'The join prefix must leave a returned value lambda'
rep(returned['joinResultRep'], 'closure', True)
rep(returned['expr'][3]['resultRep'], 'long')
print('PASS: primitive/boxed/newtype/family proofs, case and strict-field WHNF, erased type join prefix, function-returning join')
