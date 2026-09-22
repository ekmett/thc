#!/usr/bin/env python3
"""Check canonical speculation certificates in the actual optimized Core export."""
import json
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

doc = json.loads((Path(__file__).resolve().parent.parent / "build/core/SpeculationAudit.json").read_text())
bindings = {binding["name"]: binding for binding in doc["bindings"]}
all_apps = apps(doc["bindings"])
assert all(len(node) == 6 and type(node[4]) is bool and type(node[5]) is bool
           for node in all_apps), "Expected separate WHNF and speculation flags"
assert calls(bindings["safe"], "ignore")[0][4:] == [False, False]
assert calls(bindings["safe"], "-#")[0][4:] == [False, True]
assert calls(bindings["safe"], "Box")[0][4:] == [False, True]
assert calls(bindings["unsafe"], "quotInt#")[0][4:] == [False, False]
assert calls(bindings["unsafe"], "ignore")[0][2][0][0] == "case"
assert calls(bindings["safeDivision"], "quotInt#")[0][4:] == [False, True]
paps = calls(doc["bindings"], "addBox")
assert len(paps) == 1 and paps[0][4:] == [True, True]
assert paps[0][2][0] == ["var", "main:SpeculationAudit.bottom"]
lazy = calls(doc["bindings"], "Lazy")
assert len(lazy) == 1 and lazy[0][4:] == [True, True]
assert lazy[0][2][0] == ["var", "main:SpeculationAudit.bottom"]
dfun_id = bindings["$fFooWrap"]["id"]
rec_group = next(group for group in doc["groups"]
                 if group["recursive"] and dfun_id in group["ids"])
rec_bindings = [binding for binding in doc["bindings"] if binding["id"] in rec_group["ids"]]
dfun_calls = calls(rec_bindings, "$fFooWrap")
assert len(dfun_calls) == 1 and dfun_calls[0][5] is False
assert "[DFunId]" in doc["sourceCore"]
print("PASS: safe subtraction, safe constant division, unsafe divide-zero case, lazy bottom/PAP, enclosing recursive DFun guard")
