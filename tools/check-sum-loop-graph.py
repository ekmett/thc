#!/usr/bin/env python3
"""Check the actual scheduled summation loop in a GraphInspect JSON export.

Usage:
  python3 tools/check-sum-loop-graph.py graph-00005.json --output evidence.json \
      --dot loop-nodes.dot --cfg-dot loop-blocks.dot

Requires the snapshot named "Before phase HighTierLowering". Natural loops are
formed from real LoopBegin/LoopEnd associations, CFG backedges and dominators.
The summation signature is two i64 phis: an induction phi updated by a constant
Add/Sub, and an accumulator phi updated by adding that induction value. This
separates the guest loop from host argument-conversion loops in an interop graph.

Only continuing natural-loop blocks are checked, not entry or exit blocks. All
scheduled nodes in those blocks are checked, not merely the rendered subset.
Virtual object/state descriptions do not allocate and are excluded. Actual
boxing, allocation, heap reads and all invoke/foreign-call nodes are rejected
(the call rule is deliberately stricter than rejecting guest calls alone).
This is evidence about the selected compiler snapshot, not final machine code.
DOT node edges are copied verbatim from the export; CFG DOT edges come directly
from its scheduled block successors. Neither output invents conceptual edges.
"""

import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path


ARITHMETIC = {"AddNode", "SubNode"}
STATE_CLASSES = {"FrameState", "VirtualObjectState", "MaterializedObjectState"}
VIRTUAL_CLASSES = {"VirtualObjectNode", "VirtualArrayNode", "VirtualInstanceNode", "VirtualBoxingNode"}
DRAW_EDGE_TYPES = {"Value", "Condition", "Successor", "Association"}
# Unknown operation kinds fail closed rather than silently hiding a new memory
# access/allocation node introduced by a different Graal version.
CONTROL_VALUE_CLASSES = {
    "StartNode", "BeginNode", "EndNode", "MergeNode", "LoopBeginNode", "LoopEndNode",
    "LoopExitNode", "IfNode", "ValuePhiNode", "ValueProxyNode", "GuardPhiNode",
    "GuardProxyNode", "PiNode", "PiArrayNode", "ConstantNode", "ValueAnchorNode",
    "ConditionAnchorNode", "FixedGuardNode", "GuardNode", "SafepointNode",
    "TruffleSafepointNode", "ControlFlowAnchorNode",
}


def kind(node):
    return node["nodeClass"].rsplit(".", 1)[-1]


def i64(node):
    return str(node.get("properties", {}).get("stamp", "")).startswith("i64")


def node_summary(node):
    props = node.get("properties", {})
    result = {"id": node["id"], "block": node.get("block"), "class": kind(node)}
    for key in ("name", "stamp"):
        if key in props:
            result[key] = props[key]
    return result


def forbidden(node):
    name = kind(node)
    # Graal's virtual nodes describe scalar-replaced/deoptimization objects.
    if name in VIRTUAL_CLASSES or name in STATE_CLASSES:
        return None
    if "BoxNode" in name or name == "UnboxNode":
        return "boxing or unboxing"
    if (name in {"CommitAllocationNode", "AllocatedObjectNode"}
            or re.search(r"(?:Dynamic)?New(?:Instance|Array|MultiArray|Object)", name)
            or name.startswith("Allocate")):
        return "allocation"
    if (name in {"ReadNode", "FloatingReadNode", "JavaReadNode", "RawLoadNode", "ArrayLengthNode"}
            or re.search(r"Load(?:Field|Indexed|Hub|Method|Array|Object|Pointer)", name)
            or ("Unsafe" in name and "Load" in name)):
        return "heap load"
    if "Invoke" in name or "ForeignCall" in name or name.endswith("CallTargetNode"):
        return "call (all invokes and foreign calls are conservatively rejected)"
    if name not in CONTROL_VALUE_CLASSES and not node["nodeClass"].startswith("jdk.graal.compiler.nodes.calc."):
        return "unreviewed node kind (fails closed)"
    return None


def build_model(graph):
    nodes = {node["id"]: node for node in graph["nodes"]}
    if len(nodes) != len(graph["nodes"]):
        raise ValueError("duplicate node IDs")
    blocks = {str(block["name"]): block for block in graph["blocks"]
              if str(block["name"]) != "(no block)"}
    successors = {name: set(map(str, block["successors"])) for name, block in blocks.items()}
    predecessors = {name: set() for name in blocks}
    for source, targets in successors.items():
        for target in targets:
            if target not in blocks:
                raise ValueError("CFG successor is missing from scheduled blocks: " + target)
            predecessors[target].add(source)
    starts = {str(node["block"]) for node in nodes.values() if kind(node) == "StartNode"}
    if not starts or not starts <= blocks.keys():
        raise ValueError("missing scheduled StartNode")
    reachable = set()
    work = list(starts)
    while work:
        block = work.pop()
        if block not in reachable:
            reachable.add(block)
            work.extend(successors[block])
    dominators = {block: ({block} if block in starts else set(reachable)) for block in reachable}
    changed = True
    while changed:
        changed = False
        for block in sorted(reachable - starts):
            incoming = predecessors[block] & reachable
            new = {block} | set.intersection(*(dominators[pred] for pred in incoming))
            if new != dominators[block]:
                dominators[block] = new
                changed = True
    incoming_edges = defaultdict(list)
    outgoing_edges = defaultdict(list)
    for edge in graph["edges"]:
        if edge["from"] not in nodes or edge["to"] not in nodes:
            raise ValueError("edge references missing node")
        incoming_edges[edge["to"]].append(edge)
        outgoing_edges[edge["from"]].append(edge)
    # Use the schedule as authority; reject an inconsistent node/block export.
    schedule = {}
    for block, description in blocks.items():
        for node_id in description["nodes"]:
            if node_id not in nodes or node_id in schedule:
                raise ValueError("missing or multiply scheduled node")
            if str(nodes[node_id].get("block")) != block:
                raise ValueError("node/block schedule disagrees for node " + str(node_id))
            schedule[node_id] = block
    return nodes, blocks, successors, predecessors, dominators, incoming_edges, outgoing_edges


def natural_loops(model):
    nodes, blocks, successors, predecessors, dominators, incoming, outgoing = model
    result = []
    for begin in nodes.values():
        if kind(begin) != "LoopBeginNode":
            continue
        header = str(begin.get("block"))
        if header not in dominators:
            continue
        ends = [nodes[e["to"]] for e in outgoing[begin["id"]]
                if e["label"] == "loopBegin" and kind(nodes[e["to"]]) == "LoopEndNode"]
        latches = {str(end.get("block")) for end in ends}
        if not latches or any(header not in successors.get(latch, set())
                              or header not in dominators.get(latch, set()) for latch in latches):
            continue
        loop_blocks = {header}
        pending = list(latches)
        while pending:
            block = pending.pop()
            if block in loop_blocks:
                continue
            if header not in dominators[block]:
                raise ValueError("natural-loop predecessor is not dominated by its header")
            loop_blocks.add(block)
            pending.extend(predecessors[block] - loop_blocks)
        node_ids = {node_id for block in loop_blocks for node_id in blocks[block]["nodes"]}
        phis = [nodes[e["to"]] for e in outgoing[begin["id"]]
                if e["label"] == "merge" and kind(nodes[e["to"]]) == "ValuePhiNode"
                and i64(nodes[e["to"]])]
        recurrences = {}
        for phi in phis:
            updates = []
            for edge in incoming[phi["id"]]:
                update = nodes[edge["from"]]
                if (edge["type"] != "Value" or edge["label"] != "values"
                        or update["id"] not in node_ids or kind(update) not in ARITHMETIC or not i64(update)):
                    continue
                operands = [operand["from"] for operand in incoming[update["id"]]
                            if operand["type"] == "Value"]
                if phi["id"] in operands:
                    updates.append({"node": update["id"], "operands": operands})
            if updates:
                recurrences[phi["id"]] = updates
        sums = []
        for counter_id, updates in recurrences.items():
            for update in updates:
                constants = [operand for operand in update["operands"]
                             if kind(nodes[operand]) == "ConstantNode" and i64(nodes[operand])]
                if not constants:
                    continue
                for accumulator_id, additions in recurrences.items():
                    if accumulator_id == counter_id:
                        continue
                    for addition in additions:
                        if kind(nodes[addition["node"]]) == "AddNode" and counter_id in addition["operands"]:
                            sums.append({"counterPhi": counter_id, "counterUpdate": update["node"],
                                         "stepConstants": constants, "accumulatorPhi": accumulator_id,
                                         "accumulatorUpdate": addition["node"]})
        result.append({"begin": begin["id"], "header": header, "ends": sorted(end["id"] for end in ends),
                       "latches": latches, "blocks": loop_blocks, "nodeIds": node_ids,
                       "recurrences": recurrences, "sums": sums})
    return result


def block_order(value):
    return (0, int(value)) if value.isdecimal() else (1, value)


def quote(value):
    return json.dumps(str(value), ensure_ascii=False)


def write_node_dot(path, graph, model, loop):
    nodes, _, _, _, _, incoming, _ = model
    selected = {node_id for node_id in loop["nodeIds"]
                if kind(nodes[node_id]) not in STATE_CLASSES and not kind(nodes[node_id]) in VIRTUAL_CLASSES}
    # Show immediate boundary inputs without pretending their predecessors are in the loop.
    for node_id in list(selected):
        selected.update(edge["from"] for edge in incoming[node_id]
                        if edge["type"] in DRAW_EDGE_TYPES
                        and kind(nodes[edge["from"]]) not in STATE_CLASSES
                        and not kind(nodes[edge["from"]]) in VIRTUAL_CLASSES)
    lines = ["digraph actual_graal_loop {", "  rankdir=LR;", "  graph [fontname=Helvetica];",
             "  node [shape=box,fontname=Helvetica,fontsize=10];",
             "  edge [fontname=Helvetica,fontsize=9];",
             '  label="Actual exported Graal nodes and edges; gray nodes are outside continuing loop blocks";']
    for node_id in sorted(selected):
        node = nodes[node_id]
        label = f"{node_id}: {kind(node)}\nB{node.get('block')}  {node.get('properties', {}).get('stamp', '')}"
        color = "#e8f3ff" if node_id in loop["nodeIds"] else "#eeeeee"
        lines.append(f"  n{node_id} [label={quote(label)},style=filled,fillcolor={quote(color)}];")
    for edge in graph["edges"]:
        if edge["from"] not in selected or edge["to"] not in selected or edge["type"] not in DRAW_EDGE_TYPES:
            continue
        color = "#202020" if edge["type"] == "Successor" else "#2166ac" if edge["type"] in {"Value", "Condition"} else "#888888"
        style = "dashed" if edge["type"] == "Association" else "solid"
        label = f"{edge['type']}: {edge['label']}"
        if edge.get("listIndex", -1) >= 0:
            label += f"[{edge['listIndex']}]"
        lines.append(f"  n{edge['from']} -> n{edge['to']} [label={quote(label)},color={quote(color)},style={style}];")
    lines.append("}")
    Path(path).write_text("\n".join(lines) + "\n")


def write_cfg_dot(path, model, loop):
    nodes, blocks, successors, _, _, _, _ = model
    lines = ["digraph actual_scheduled_cfg {", "  node [shape=box,fontname=Helvetica,fontsize=10];",
             '  label="Induced scheduled CFG of continuing natural-loop blocks";']
    for block in sorted(loop["blocks"], key=block_order):
        listing = "\n".join(f"{node_id}: {kind(nodes[node_id])}" for node_id in blocks[block]["nodes"]
                            if kind(nodes[node_id]) not in STATE_CLASSES and not kind(nodes[node_id]) in VIRTUAL_CLASSES)
        lines.append(f"  {quote('b' + block)} [label={quote('B' + block + chr(10) + listing)}];")
    for source in sorted(loop["blocks"], key=block_order):
        for target in sorted(successors[source] & loop["blocks"], key=block_order):
            lines.append(f"  {quote('b' + source)} -> {quote('b' + target)};")
    lines.append("}")
    Path(path).write_text("\n".join(lines) + "\n")


def check(graph, source, requested_loop):
    if "Before phase HighTierLowering" not in graph.get("name", ""):
        raise ValueError("expected the 'Before phase HighTierLowering' snapshot")
    model = build_model(graph)
    nodes, blocks, successors, _, _, _, _ = model
    loops = natural_loops(model)
    matches = [loop for loop in loops if loop["sums"] and (requested_loop is None or loop["begin"] == requested_loop)]
    evidence = {"passed": False, "graph": str(source.resolve()), "snapshot": graph["name"],
                "scope": "All scheduled nodes in continuing natural-loop blocks; excludes entry/exit paths and virtual object/state metadata.",
                "naturalLoopCount": len(loops), "summationLoopCandidates": [loop["begin"] for loop in matches]}
    if len(matches) != 1:
        evidence["error"] = "Expected exactly one structural i64 summation loop; use --loop-id to disambiguate."
        evidence["loops"] = [{"loopBegin": loop["begin"], "headerBlock": loop["header"],
                              "i64ArithmeticPhis": sorted(loop["recurrences"]),
                              "summationSignatures": loop["sums"]} for loop in loops]
        return evidence, model, None
    loop = matches[0]
    violations = []
    for node_id in sorted(loop["nodeIds"]):
        reason = forbidden(nodes[node_id])
        if reason:
            violations.append({**node_summary(nodes[node_id]), "reason": reason})
    edges = [{"from": source, "to": target} for source in sorted(loop["blocks"], key=block_order)
             for target in sorted(successors[source] & loop["blocks"], key=block_order)]
    evidence.update({"loopBegin": loop["begin"], "loopEnds": loop["ends"], "headerBlock": loop["header"],
                     "continuingBlocks": sorted(loop["blocks"], key=block_order), "cfgEdges": edges,
                     "scheduledNodeCount": len(loop["nodeIds"]), "scheduledNodeIds": sorted(loop["nodeIds"]),
                     "i64ArithmeticPhis": [{**node_summary(nodes[phi]), "updates": updates}
                                            for phi, updates in sorted(loop["recurrences"].items())],
                     "summationSignatures": loop["sums"], "violations": violations,
                     "passed": len(loop["recurrences"]) >= 2 and not violations})
    return evidence, model, loop


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("graph", type=Path, help="GraphInspect JSON before HighTierLowering")
    parser.add_argument("--output", type=Path, help="write evidence JSON here (otherwise stdout)")
    parser.add_argument("--dot", type=Path, help="write actual arithmetic/control node subgraph")
    parser.add_argument("--cfg-dot", type=Path, help="write induced scheduled block CFG")
    parser.add_argument("--loop-id", type=int, help="select this actual LoopBegin ID when several summation loops exist")
    args = parser.parse_args()
    try:
        graph = json.loads(args.graph.read_text())
        evidence, model, loop = check(graph, args.graph, args.loop_id)
        if loop is not None:
            if args.dot:
                write_node_dot(args.dot, graph, model, loop)
                evidence["nodeDot"] = str(args.dot.resolve())
            if args.cfg_dot:
                write_cfg_dot(args.cfg_dot, model, loop)
                evidence["cfgDot"] = str(args.cfg_dot.resolve())
    except (OSError, ValueError, KeyError, TypeError) as error:
        evidence = {"passed": False, "graph": str(args.graph.resolve()), "error": str(error)}
    payload = json.dumps(evidence, indent=2, ensure_ascii=False) + "\n"
    if args.output:
        args.output.write_text(payload)
        print(f"{'PASS' if evidence['passed'] else 'FAIL'}: {args.output}")
    else:
        print(payload, end="")
    return 0 if evidence["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
