#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Check concrete f32/f64 recurrences and allocation-free continuing loop blocks.

Reads actual GraphInspect JSON before HighTierLowering. Reuses the integer-loop
checker's scheduled CFG/dominator model and conservative forbidden-node policy;
entry/exit boxing through the current Object call ABI is outside this claim.
"""
import argparse
import importlib.util
import json
from pathlib import Path

spec = importlib.util.spec_from_file_location('sum_loop', Path(__file__).with_name('check-sum-loop-graph.py'))
graph_check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(graph_check)


def check(path):
    graph = json.loads(path.read_text())
    if 'Before phase HighTierLowering' not in graph['name']:
        raise ValueError('Expected Before phase HighTierLowering snapshot')
    model = graph_check.build_model(graph)
    nodes, _, _, _, _, incoming, outgoing = model
    matches = []
    for loop in graph_check.natural_loops(model):
        recurrences = []
        for edge in outgoing[loop['begin']]:
            phi = nodes[edge['to']]
            stamp = str(phi['properties'].get('stamp', ''))
            if edge['label'] != 'merge' or graph_check.kind(phi) != 'ValuePhiNode' or not stamp.startswith(('f32', 'f64')):
                continue
            for value in incoming[phi['id']]:
                update = nodes[value['from']]
                if value['type'] != 'Value' or value['label'] != 'values' or update['id'] not in loop['nodeIds']:
                    continue
                if graph_check.kind(update) != 'AddNode' or not str(update['properties'].get('stamp', '')).startswith(stamp[:3]):
                    continue
                operands = [e['from'] for e in incoming[update['id']] if e['type'] == 'Value']
                if phi['id'] in operands:
                    recurrences.append(dict(phi=phi['id'], update=update['id'], stamp=stamp, operands=operands))
        if {'f32', 'f64'} <= {r['stamp'][:3] for r in recurrences}:
            violations = [dict(node=graph_check.node_summary(nodes[i]), reason=reason)
                          for i in sorted(loop['nodeIds']) if (reason := graph_check.forbidden(nodes[i]))]
            matches.append(dict(loopBegin=loop['begin'], loopEnds=loop['ends'],
                continuingBlocks=sorted(loop['blocks'], key=graph_check.block_order),
                scheduledNodeIds=sorted(loop['nodeIds']), floatingRecurrences=recurrences,
                integerRecurrences=loop['recurrences'], violations=violations))
    return dict(passed=len(matches) == 1 and not matches[0]['violations'], graph=str(path.resolve()),
        group=graph['group'], snapshot=graph['name'], loops=matches,
        scope='Scheduled continuing natural-loop blocks; require f32 and f64 add recurrences, reject boxing/allocation/heap loads/calls. Entry and exit ABI excluded.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('graph', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    report = check(args.graph)
    text = json.dumps(report, indent=2) + '\n'
    if args.output:
        args.output.write_text(text)
    else:
        print(text, end='')
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__':
    main()
