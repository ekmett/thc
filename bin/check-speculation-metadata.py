#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Check canonical speculation certificates in the actual optimized Core export."""
from core_package_manifest import inspect_cbd
from pathlib import Path

def walk(value):
    if isinstance(value, list):
        yield value
        for child in value:
            yield from walk(child)
    elif isinstance(value, dict):
        for child in value.values():
            yield from walk(child)

def apps(value):
    return [node for node in walk(value) if node and node[0] == "app"]

def calls(value, name):
    return [node for node in apps(value)
            if node[1][0] in ("var", "con", "prim")
            and (node[1][1] == name or node[1][1].endswith("." + name))]

doc = inspect_cbd((Path(__file__).resolve().parent.parent / "build/core/SpeculationAudit.cbd").read_bytes())
bindings = {binding["id"]: binding for binding in doc["bindings"]}
all_apps = apps(doc["bindings"])
assert all(len(node) >= 6 and type(node[4]) is bool and type(node[5]) is bool
           for node in all_apps), "Expected separate WHNF and speculation flags"
assert calls(bindings["main:SpeculationAudit.safe"], "ignore")[0][4:6] == [False, False]
assert calls(bindings["main:SpeculationAudit.safe"], "-#")[0][4:6] == [False, True]
assert calls(bindings["main:SpeculationAudit.safe"], "Box")[0][4:6] == [False, True]
assert calls(bindings["main:SpeculationAudit.unsafe"], "quotInt#")[0][4:6] == [False, False]
assert calls(bindings["main:SpeculationAudit.unsafe"], "ignore")[0][2][0][0] == "case"
assert calls(bindings["main:SpeculationAudit.safeDivision"], "quotInt#")[0][4:6] == [False, True]
paps = calls(doc["bindings"], "addBox")
assert len(paps) == 1 and paps[0][4:6] == [True, True]
assert paps[0][2][0][:2] == ["var", "main:SpeculationAudit.bottom"]
lazy = calls(doc["bindings"], "Lazy")
assert len(lazy) == 1 and lazy[0][4:6] == [True, True]
assert lazy[0][2][0][:2] == ["var", "main:SpeculationAudit.bottom"]
# Certify the actual dictionary cycle through binding identities, rather than
# printed group/DFun labels that are not executable CBD facts.
dfun = bindings["main:SpeculationAudit.$fFooWrap"]
assert dfun['arity'] == 1 and dfun['expr'][0] == 'lam'
dictionary_ref = dfun['expr'][2]
assert dictionary_ref[0] == 'var'
dictionary = bindings[dictionary_ref[1]]['expr']
assert dictionary[:1] == ['app'] and dictionary[1][:3] == ['con', 'main:SpeculationAudit.C:Foo', 2]
constructor, = [c for c in doc['constructors'] if c['id'] == dictionary[1][1]]
assert constructor['kind'] == 'boxed' and constructor['arity'] == 2
assert [p['kind'] for p in constructor['fieldTypes']] == ['data', 'closure']
assert len(dictionary[2]) == 2 and dictionary[2][0][0] == 'var'
recursive = bindings[dictionary[2][0][1]]['expr']
assert recursive[0] == 'app' and recursive[1][:2] == ['var', dfun['id']]
assert len(recursive[2]) == dfun['arity'] and recursive[5] is False
dfun_calls = calls(doc['bindings'], '$fFooWrap')
assert len(dfun_calls) == 1 and dfun_calls[0] is recursive
print("PASS: safe subtraction, safe constant division, unsafe divide-zero case, lazy bottom/PAP, enclosing recursive DFun guard")
