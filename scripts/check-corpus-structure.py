#!/usr/bin/env python3
"""Check the optimized structures promised by the ordinary functional corpus.

Run after prepare-corpus has exported the groups and written their entry audits.
These checks establish Core structure, not runtime evaluation counts. GHC is
pinned to 9.14.1; worker occurrence names are used where worker/wrapper is part
of the expected optimized fixture, but generated unique suffixes are ignored.
"""

import argparse
import json
from pathlib import Path
import sys


FUNCTIONAL_GROUPS = ("lists", "functions", "trees", "pointers")
LIST_CON = "ghc-internal:GHC.Internal.Types.:"


class StructureError(Exception):
    """A fixture no longer has the optimized structure it promises."""


def require(condition, message):
    if not condition:
        raise StructureError(message)


def only(items, description):
    items = list(items)
    require(len(items) == 1, f"{description}: expected one, found {len(items)}")
    return items[0]


def read_json(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError) as error:
        raise StructureError(f"Cannot read {path}: {error}") from error


def children(expr):
    """Visit executable children only, excluding source/representation metadata."""
    tag = expr[0]
    if tag == "lam":
        yield expr[2]
    elif tag == "app":
        yield expr[1]
        yield from expr[2]
    elif tag == "let":
        yield from (binding["expr"] for binding in expr[2])
        yield expr[3]
    elif tag == "case":
        yield expr[1]
        yield from (alternative[3] for alternative in expr[3])


def walk(expr):
    yield expr
    for child in children(expr):
        yield from walk(child)


def references(expr, key):
    return [node for node in walk(expr) if node[:2] == ["var", key]]


def calls(expr, key):
    return [node for node in walk(expr)
            if node[0] == "app" and node[1][:2] == ["var", key]]


def free_variables(expr, bound=frozenset()):
    """Compute term free variables with the exported Core's lexical scopes."""
    tag = expr[0]
    if tag == "var":
        return {expr[1]} - bound
    if tag == "lam":
        return free_variables(expr[2], bound | {p["id"] for p in expr[1]})
    if tag == "let":
        local = {binding["id"] for binding in expr[2]}
        rhs_bound = bound | local if expr[1] else bound
        result = free_variables(expr[3], bound | local)
        for binding in expr[2]:
            result |= free_variables(binding["expr"], rhs_bound)
        return result
    if tag == "case":
        result = free_variables(expr[1], bound)
        for alternative in expr[3]:
            result |= free_variables(
                alternative[3], bound | {expr[2]} | set(alternative[2]))
        return result
    result = set()
    for child in children(expr):
        result |= free_variables(child, bound)
    return result


def lambda_parts(binding, count):
    expr = binding["expr"]
    name = binding["name"]
    require(expr[0] == "lam", f"{name}: expected a leading term lambda")
    require(len(expr[1]) == count,
            f"{name}: expected {count} lambda parameters, found {len(expr[1])}")
    return expr[1], expr[2]


def local_binding(expr, name):
    return only((binding for node in walk(expr) if node[0] == "let"
                 for binding in node[2] if binding["name"] == name),
                f"Local binding {name}")


def data_alternative(expr, constructor, description):
    require(expr[0] == "case", f"{description}: expected a case expression")
    return only((alt for alt in expr[3]
                 if alt[:2] == ["data", constructor]), description)


class CorpusChecks:
    def __init__(self, root, facts):
        self.root = root
        self.build = root / "build/corpus"
        self.facts = facts
        manifest = read_json(root / "examples/coverage.json")
        require(manifest.get("schema") == 1, "Expected coverage manifest schema 1")
        self.groups = {}
        self.modules = {}
        for group_id in FUNCTIONAL_GROUPS:
            group = only((g for g in manifest["groups"] if g["id"] == group_id),
                         f"Manifest group {group_id}")
            self.groups[group_id] = group
            self.modules[group_id] = read_json(
                self.build / "groups" / group_id / "core" / (group["module"] + ".json"))
        self.modules["support"] = read_json(
            self.build / "groups/functions/core/THC.CoverageSupport.json")
        for name, module in self.modules.items():
            require(module.get("ghc") == "9.14.1", f"{name}: expected GHC 9.14.1 export")
        self.global_ids = {b["id"] for module in self.modules.values()
                           for b in module["bindings"]}

    def binding(self, group, name):
        return only((b for b in self.modules[group]["bindings"] if b["name"] == name),
                    f"{group} binding {name}")

    def worker(self, group, name):
        # Ignore the unstable GHC unique suffix on a known worker occurrence.
        return only((b for b in self.modules[group]["bindings"]
                     if b["name"].startswith("$w" + name)),
                    f"{group} worker for {name}")

    def fact(self, kind, **details):
        self.facts.append(dict(kind=kind, **details))

    def applications(self):
        choose = self.binding("functions", "chooseBinary")
        over = self.binding("functions", "capturedOverapply")
        require(choose["arity"] == 1, "chooseBinary: overcall target must retain arity 1")
        lambda_parts(choose, 1)
        overcall = only(calls(over["expr"], choose["id"]), "capturedOverapply call")
        require(len(overcall[2]) == 3, "capturedOverapply: expected three supplied arguments")
        self.fact("overapplication", entry=over["id"], callee=choose["id"],
                  calleeArity=1, suppliedArguments=3)

        combine = self.binding("support", "combine3")
        pap = self.binding("functions", "reusedLazyPAP")
        require(combine["arity"] == 3, "combine3: PAP target must retain arity 3")
        parameters, body = lambda_parts(combine, 3)
        partial = only(calls(pap["expr"], combine["id"]), "reusedLazyPAP partial call")
        require(len(partial[2]) == 2, "reusedLazyPAP: expected two supplied arguments")
        bottom = self.binding("support", "neverInt")["id"]
        require(partial[2][1][:2] == ["var", bottom],
                "reusedLazyPAP: second captured argument must remain neverInt")
        require(not references(body, parameters[1]["id"]),
                "combine3: unused second formal must not occur in its body")
        self.fact("partialApplication", entry=pap["id"], callee=combine["id"],
                  calleeArity=3, suppliedArguments=2, unusedBottomArgument=bottom)

        reuse = self.binding("functions", "reuseUnary")
        parameters, body = lambda_parts(reuse, 3)
        require(len(calls(body, parameters[0]["id"])) == 2,
                "reuseUnary: the same function formal must be called twice")
        reuse_call = only(calls(pap["expr"], reuse["id"]), "reusedLazyPAP reuseUnary call")
        require(reuse_call[2][0] == partial,
                "reusedLazyPAP: the preserved PAP must be passed to reuseUnary")
        self.fact("reusedUnknownCall", binding=reuse["id"], callCount=2)

    def sharing(self):
        fixtures = (
            ("lists", "sharedListConsumers", "spine", "sharedProducer", 2),
            ("trees", "treeShared", "tree", "sharedTreeProducer", 2),
            ("functions", "sharedCapturedThunk", "sharedValue", "expensiveInt", 1),
        )
        for group, entry, local_name, producer_name, reference_count in fixtures:
            binding = self.binding(group, entry)
            local = local_binding(binding["expr"], local_name)
            producer_group = "support" if producer_name == "expensiveInt" else group
            producer = self.binding(producer_group, producer_name)
            require(len(calls(local["expr"], producer["id"])) == 1,
                    f"{entry}: expected one producer call in {local_name}")
            require(local["arity"] == 0 and local["rep"]["evaluated"] is False,
                    f"{entry}: {local_name} must remain an unevaluated arity-zero binding")
            require(len(references(binding["expr"], local["id"])) == reference_count,
                    f"{entry}: expected {reference_count} references to the same {local_name}")
            self.fact("sharedLazyBinding", entry=binding["id"], local=local["id"],
                      referenceCount=reference_count)

        shared = self.binding("functions", "sharedCapturedThunk")
        value = local_binding(shared["expr"], "sharedValue")
        retain = self.binding("functions", "retainUnary")
        retain_call = only(calls(shared["expr"], retain["id"]), "sharedCapturedThunk retain call")
        closure = retain_call[2][0]
        require(closure[0] == "lam" and value["id"] in free_variables(closure),
                "sharedCapturedThunk: retained lambda must capture the shared thunk")
        extraction = only((node for node in walk(shared["expr"])
                           if node[0] == "case" and node[1] == retain_call),
                          "sharedCapturedThunk closure extraction")
        alternative = data_alternative(
            extraction, "main:THC.FunctionCoverage.Unary", "sharedCapturedThunk Unary case")
        extracted = only(alternative[2], "sharedCapturedThunk extracted function")
        reuse = self.binding("functions", "reuseUnary")
        reuse_call = only(calls(alternative[3], reuse["id"]), "sharedCapturedThunk reuse call")
        require(reuse_call[2][0][:2] == ["var", extracted],
                "sharedCapturedThunk: reuseUnary must receive the same extracted closure")
        self.fact("sharedCaptureReuse", entry=shared["id"], capture=value["id"],
                  closureCalls=2, note="One lexical capture reused by two calls; no runtime count claimed")

    def cyclic_list(self):
        knot = self.binding("lists", "streamKnot")
        parameters, _ = lambda_parts(knot, 1)
        local = local_binding(knot["expr"], "values")
        recursive = only((node for node in walk(knot["expr"])
                          if node[0] == "let" and local in node[2]), "streamKnot local group")
        require(recursive[1] is True, "streamKnot: values must remain a recursive let binding")
        require(len(references(local["expr"], local["id"])) == 1,
                "streamKnot: expected one back edge to the same values binding")
        require(references(local["expr"], parameters[0]["id"]),
                "streamKnot: the cycle must still depend directly on its input")
        constructors = [node for node in walk(local["expr"]) if node[:2] == ["con", LIST_CON]]
        require(len(constructors) == 2, "streamKnot: expected two cons cells in the cycle")
        self.fact("dynamicKnot", entry=knot["id"], local=local["id"],
                  constructorCount=2, selfReferenceCount=1)

    def closure_transport(self):
        run = self.binding("functions", "runFunctions")
        _, body = lambda_parts(run, 2)
        cons = data_alternative(body, LIST_CON, "runFunctions cons case")
        require(len(calls(cons[3], cons[2][0])) == 1,
                "runFunctions: the function stored in the cons head must be applied")
        make = self.worker("functions", "makeFunctions")
        closures = [node for node in walk(make["expr"])
                    if node[0] == "lam" and len(node[1]) == 1]
        require(len(closures) == 2, "makeFunctions: expected two alternative unary lambdas")
        captures = [sorted(free_variables(closure) - self.global_ids) for closure in closures]
        require(all(captures), "makeFunctions: each unary lambda must retain a local capture")
        self.fact("closureTransport", producer=make["id"], consumer=run["id"],
                  capturedLambdaCount=2, captures=captures)

    def list_laziness(self):
        spine = self.binding("lists", "spineLength")
        _, body = lambda_parts(spine, 2)
        cons = data_alternative(body, LIST_CON, "spineLength cons case")
        require(not references(cons[3], cons[2][0]),
                "spineLength: the ignored cons head must not occur in the branch body")
        bottom_heads = self.worker("lists", "bottomHeads")
        bottom = self.binding("support", "neverInt")["id"]
        require(references(bottom_heads["expr"], bottom),
                "bottomHeads: bottom-valued heads must survive optimization")
        self.fact("unforcedHeads", producer=bottom_heads["id"], consumer=spine["id"],
                  headReferences=0)

        prefix = self.binding("lists", "prefixChecksum")
        parameters, body = lambda_parts(prefix, 3)
        require(body[0] == "case" and body[1][:2] == ["var", parameters[0]["id"]],
                "prefixChecksum: the outer case must examine count before the spine")
        unbox = data_alternative(body, "ghc-internal:GHC.Internal.Types.I#",
                                "prefixChecksum count unboxing")
        guard = unbox[3]
        require(guard[0] == "case" and guard[1][0] == "app"
                and guard[1][1][:2] == ["prim", "<=#"],
                "prefixChecksum: expected the nonpositive-count guard before list scrutiny")
        stop = only((alt for alt in guard[3] if alt[:2] == ["lit", ["int", "1"]]),
                    "prefixChecksum stopping alternative")
        require(not references(stop[3], parameters[1]["id"]),
                "prefixChecksum: stopping must not reference the spine")
        never_list = self.binding("lists", "neverList")
        tail = self.binding("lists", "listTailLazy")
        require(references(tail["expr"], never_list["id"]),
                "listTailLazy: the bottom tail must survive optimization")
        self.fact("unforcedTail", entry=tail["id"], consumer=prefix["id"],
                  countCheckedBeforeSpine=True)

    def recursive_trees(self):
        expected = {"main:THC.TreeCoverage." + name for name in ("Leaf", "Fork", "Tagged")}
        for name, arity in (("foldTree", 1), ("mapTree", 2), ("selectTree", 2)):
            binding = self.binding("trees", name)
            _, body = lambda_parts(binding, arity)
            require(body[0] == "case", f"{name}: expected the tree case at entry")
            alternatives = [alt[1] for alt in body[3] if alt[0] == "data"]
            require(len(alternatives) == 3 and set(alternatives) == expected,
                    f"{name}: all Leaf/Fork/Tagged alternatives must survive")
            recursive_calls = len(calls(body, binding["id"]))
            require(recursive_calls >= 3, f"{name}: recursive child calls must survive")
            self.fact("recursiveThreeWayCase", binding=binding["id"],
                      alternatives=alternatives, recursiveCalls=recursive_calls)

        tagged = only((c for c in self.modules["trees"]["constructors"]
                       if c["id"] == "main:THC.TreeCoverage.Tagged"), "Tagged constructor")
        require(tagged["fieldLifted"] == [False, True]
                and tagged["fieldReps"][0] == ["IntRep"],
                "Tagged: strict source Int must be unboxed beside the lifted Tree field")
        require(tagged["strictFields"][1] is False,
                "Tagged: the recursive Tree field must remain lazy")
        self.fact("mixedConstructorLayout", constructor=tagged["id"],
                  fieldLifted=tagged["fieldLifted"], fieldReps=tagged["fieldReps"])

    def library_lists(self):
        directory = self.build / "groups/lists"
        expected = {"Base": "++", "List": "reverse1"}
        identities = []
        for module, occurrence in expected.items():
            source = read_json(directory / "core" / ("GHC.Internal." + module + ".json"))
            require(source.get("unit") == "ghc-internal" and
                    source.get("boundary") == "optimized-Core-after-Tidy-before-CorePrep",
                    f"{module}: expected original ghc-internal post-Tidy source export")
            identity = "ghc-internal:GHC.Internal." + module + "." + occurrence
            body = only((b for b in source["bindings"] if b["id"] == identity), identity)
            lambda_parts(body, 2)
            require(calls(body["expr"], identity), f"{identity}: original recursive body must survive")
            identities.append(identity)
        for entry, required in (("listAppendReverse", identities), ("listTailLazy", identities[:1])):
            report = read_json(directory / (entry + ".audit.json"))
            reachable = {b["id"] for b in report["reachableBindings"]}
            require(set(required) <= reachable,
                    f"{entry}: actual append/reverse library bodies must remain reachable")
        provenance = read_json(directory / "boot-provenance.json")
        require(provenance.get("frontier") == "lists" and provenance.get("sourcePatches") == [],
                "Lists must use the unmodified pinned source frontier")
        self.fact("originalLibraryBodies", bindings=identities,
                  ghcTag=provenance["ghcTag"], sourcePatches=[])

    def pointer_identity(self):
        binding = self.binding("pointers", "sameKey")
        parameters, body = lambda_parts(binding, 2)
        require(body[0] == "case", "sameKey must branch on pointer equality before its fallback")
        comparison = body[1]
        require(comparison[0] == "app" and comparison[1][:2] == ["prim", "reallyUnsafePtrEquality#"],
                "sameKey must retain the genuine pointer-equality primop")
        require(comparison[3] == [True, True] and
                [arg[:2] for arg in comparison[2]] == [["var", p["id"]] for p in parameters],
                "Pointer equality must receive the two untouched lifted operands")
        metadata = comparison[6]
        require(metadata["callDemand"] == {"arity": 2, "strictArgs": [False, False]} and
                metadata["rep"]["primReps"] == ["IntRep"],
                "Pointer equality must retain GHC's non-strict argument and Int# result evidence")
        shortcut = only((alt for alt in body[3] if alt[:2] == ["lit", ["int", "1"]]),
                        "sameKey identity shortcut")
        require(shortcut[3][:3] == ["lit", "int", "1"], "sameKey identity shortcut must return true")
        fallback = only((alt for alt in body[3] if alt[0] == "default"), "sameKey equality fallback")
        require(all(references(fallback[3], p["id"]) for p in parameters),
                "sameKey must retain the value-based fallback for both operands")
        lazy = self.binding("pointers", "pointerLazyPayload")
        require(references(lazy["expr"], self.binding("support", "neverInt")["id"]),
                "pointerLazyPayload must retain its irrelevant bottom-valued field")
        self.fact("nonStrictPointerEquality", binding=binding["id"],
                  argumentLifted=comparison[3], strictArgs=[False, False], resultReps=["IntRep"],
                  valueFallback=True, unusedBottomPayload=True)

    def static_audits(self):
        for group_id, group in self.groups.items():
            directory = self.build / "groups" / group_id
            closure = read_json(directory / "core/THC.InterfaceClosure.json")
            # Missing interface unfoldings remain recorded. Complete source
            # exports may resolve those exact identities without changing them.
            source_ids = {b["id"] for path in (directory / "core").glob("*.json")
                          if path.name != "THC.InterfaceClosure.json"
                          for b in read_json(path)["bindings"]}
            missing = {b["id"] for b in closure["missingDefinitions"]}
            require(missing <= source_ids,
                    f"{group_id}: interface closure has unresolved source definitions: {sorted(missing - source_ids)}")
            entries = []
            for entry in group["entries"]:
                name = entry["name"]
                audit = read_json(directory / (name + ".audit.json"))
                entry_id = "main:" + group["module"] + "." + name
                require(audit.get("roots") == [entry_id],
                        f"{group_id}/{name}: audit does not describe the expected entry")
                require(audit.get("accepted") is True,
                        f"{group_id}/{name}: reachable static audit was rejected")
                summary = audit["summary"]
                require(summary["missingGlobals"] == 0 and summary["issues"] == 0,
                        f"{group_id}/{name}: audit must have zero missing globals/issues")
                entries.append(dict(name=name, reachableBindings=summary["reachableBindings"]))
            self.fact("fullStaticAudit", group=group_id, entries=entries,
                      interfaceBindings=len(closure["bindings"]), interfaceMissing=len(missing),
                      sourceResolved=sorted(missing))

    def run(self):
        self.static_audits()
        self.applications()
        self.sharing()
        self.cyclic_list()
        self.closure_transport()
        self.list_laziness()
        self.library_lists()
        self.pointer_identity()
        self.recursive_trees()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1],
                        help="Repository root (defaults to this script's parent repository)")
    args = parser.parse_args()
    root = args.root.resolve()
    output = root / "build/corpus/structure.json"
    result = dict(schema=1, validation="optimized-core-structure", accepted=False,
                  groups=list(FUNCTIONAL_GROUPS), facts=[])
    try:
        CorpusChecks(root, result["facts"]).run()
        result["accepted"] = True
    except StructureError as error:
        result["error"] = str(error)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n")
    if not result["accepted"]:
        print(f"Corpus structure check failed: {result['error']}", file=sys.stderr)
        return 1
    print(f"Verified {len(result['facts'])} functional corpus structure facts: {output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
