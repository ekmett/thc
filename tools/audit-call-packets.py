#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Trace materialized Object[] packets in GraphInspect JSON; never runs guest code.

Usage: python3 tools/audit-call-packets.py PARSED_CAPTURE_DIR --output report.json
Optional --baseline REPORT compares latest compilations by root label.
--require-clean-lookup rejects any late Object[] allocation or residual Java call
in the known Map lookup root. Static sites are not dynamic allocation counts.
The complete explicit allocation-node inventory is separate from the packet and
Long subsets. Null-result guards describe graph paths, not observed deoptimizations.
"""
import argparse
import collections
import json
import pathlib
import re
import sys

ALIASES = {'AllocatedObjectNode', 'ValuePhiNode', 'ValueProxyNode', 'PiNode', 'PiArrayNode'}
DEBUG_STATE = {'FrameState', 'VirtualObjectState', 'MaterializedObjectState'}
RETURN_ALIASES = {'ValuePhiNode', 'ValueProxyNode', 'PiNode', 'PiArrayNode'}
ARRAY_ALLOCATIONS = {'NewArrayNode', 'NewArrayWithExceptionNode', 'DynamicNewArrayNode',
                     'DynamicNewArrayWithExceptionNode', 'LoweredNewArrayNode',
                     'LoweredDynamicNewArrayNode', 'LoweredDynamicNewObjectArrayNode',
                     'LoweredDynamicNewUnknownArrayNode', 'VectorizableNewArrayNode'}


def short(node):
    return node['nodeClass'].split('.')[-1]


def source(node):
    return node['properties'].get('nodeSourcePosition', '').splitlines()


def java_source(node):
    # Guest positions can repeat many times within each Java frame. Keep this
    # additive inventory compact; the input graph retains the full guest stack.
    return [re.sub(r'\(truffle:.*\)(?= \[bci:)', '', line) for line in source(node)]


def null_result_guards(graph, nodes, incoming):
    """Trace call results through value aliases to guards that require null.

    A call may be only one arm of a Phi. Report that path, not a claim that the
    call always reaches the guard, returns nonnull, or caused a recorded deopt.
    """
    guards = []
    for guard in graph['nodes']:
        if short(guard) not in ('FixedGuardNode', 'GuardNode') or guard['properties'].get('negated') is not False:
            continue
        conditions = [nodes[e['from']] for e in incoming[guard['id']] if e['label'] == 'condition']
        if len(conditions) != 1 or short(conditions[0]) != 'IsNullNode':
            continue
        condition = conditions[0]
        values = [e['from'] for e in incoming[condition['id']] if e['label'] == 'value']
        if len(values) != 1:
            continue
        queue = collections.deque([(values[0], [values[0], condition['id'], guard['id']])])
        seen = set()
        calls = []
        other_inputs = []
        while queue:
            current, path = queue.popleft()
            if current in seen:
                continue
            seen.add(current)
            node = nodes[current]
            kind = short(node)
            if kind in ('InvokeNode', 'InvokeWithExceptionNode'):
                targets = [nodes[e['from']] for e in incoming[current] if e['label'] == 'callTarget']
                target = targets[0]['properties'].get('targetMethod') if len(targets) == 1 else None
                calls.append({'invoke': current, 'targetMethod': target or node['properties'].get('targetMethod'),
                              'path': path})
            elif kind in RETURN_ALIASES:
                for edge in incoming[current]:
                    if edge['type'] == 'Value' and edge['label'] in ('value', 'values', 'object'):
                        queue.append((edge['from'], [edge['from']] + path))
            else:
                other_inputs.append({'node': current, 'class': kind, 'stamp': node['properties'].get('stamp')})
        if calls:
            guards.append({'guard': guard['id'], 'class': short(guard), 'block': guard.get('block'),
                           'stage': graph['name'], 'condition': condition['id'], 'value': values[0],
                           'requiredValue': 'null', 'rejectedValue': 'non-null',
                           'reason': guard['properties'].get('reason'), 'action': guard['properties'].get('action'),
                           'javaSourcePosition': java_source(guard), 'callResults': calls,
                           'otherValueInputs': other_inputs})
    return guards


def allocation_nodes(graph):
    """Explicit allocation nodes in this phase, including non-packet objects.

    Calls may allocate elsewhere; a CommitAllocation may materialize multiple
    objects. Counts are graph nodes, never allocated-object or byte counts.
    """
    result = []
    for node in graph['nodes']:
        kind = short(node)
        properties = node['properties']
        if kind.endswith(('NewInstanceNode', 'NewInstanceWithExceptionNode')):
            allocated_type = properties.get('instanceClass') or '<dynamic instance>'
        elif kind in ARRAY_ALLOCATIONS:
            element = properties.get('elementType')
            allocated_type = element + '[]' if isinstance(element, str) else '<dynamic array>'
        elif kind in ('NewMultiArrayNode', 'NewMultiArrayWithExceptionNode'):
            allocated_type = properties.get('type') or '<multi-dimensional array>'
        elif kind in ('CommitAllocationNode', 'BoxNode$AllocatingBoxNode', 'NewFrameNode', 'AllocateWithExceptionNode'):
            # Unexpected unlowered allocations must not become a false zero.
            allocated_type = '<unlowered allocation>'
        else:
            continue
        result.append({'id': node['id'], 'class': kind, 'block': node.get('block'),
                       'allocatedType': allocated_type, 'javaSourcePosition': java_source(node)})
    return result


def family(node):
    s = source(node)[0] if source(node) else ''
    # Classify the allocation site, not an outer caller elsewhere on the stack.
    if 'thc.runtime.Force.execute' in s or 'ThunkDispatch' in s or 'ThunkCall' in s or 'DispatchThunkTarget' in s:
        return 'thunk-force'
    if 'appendWithHeader' in s:
        return 'application-packet'
    if 'Closure.pap' in s:
        return 'pap-prefix'
    if 'GenericDispatch' in s:
        return 'generic-dispatch'
    if 'thc.runtime.Application.execute' in s:
        return 'application-temporary'
    return source(node)[0].split('(')[0] if source(node) else 'unknown'


def inspect_graph(graph, mid):
    nodes = {n['id']: n for n in graph['nodes']}
    out = collections.defaultdict(list)
    high_incoming = collections.defaultdict(list)
    for e in graph['edges']:
        out[e['from']].append(e)
        high_incoming[e['to']].append(e)

    def trace(start):
        queue = collections.deque([(start, [start])])
        seen = set()
        sinks = []
        while queue:
            current, path = queue.popleft()
            if current in seen:
                continue
            seen.add(current)
            for edge in out[current]:
                n = nodes[edge['to']]
                kind = short(n)
                if edge['type'] not in ('Value', 'Association'):
                    continue
                if kind in DEBUG_STATE:
                    continue
                if kind in ALIASES:
                    queue.append((n['id'], path + [n['id']]))
                    continue
                if kind == 'CommitAllocationNode' and edge['label'] == 'virtualObjects':
                    continue
                # Preserve every non-debug use, not merely call arguments.
                sink = {'node': n['id'], 'class': kind, 'block': n.get('block'),
                        'edge': edge['label'], 'path': path + [n['id']]}
                for key in ('targetMethod', 'field', 'stamp', 'name'):
                    if key in n['properties']:
                        sink[key] = n['properties'][key]
                sinks.append(sink)
        return sinks

    packets = []
    for commit in graph['nodes']:
        if short(commit) != 'CommitAllocationNode':
            continue
        for key, value in commit['properties'].items():
            if not key.startswith('object(') or not isinstance(value, str) or not value.startswith('Object[]['):
                continue
            vid = int(key[7:-1])
            virtual = nodes[vid]
            values = value[len('Object[]['):-1].split(',') if value != 'Object[][]' else []
            sinks = trace(vid)
            calls = [s for s in sinks if s['class'] == 'MethodCallTargetNode']
            reads = [s for s in sinks if s['class'] == 'LoadIndexedNode' and s['edge'] == 'array']
            stores = [s for s in sinks if s['class'] in ('StoreFieldNode', 'StoreIndexedNode', 'CommitAllocationNode')]
            classification = ('residual-call-abi' if calls else
                              'materialized-for-inlined-array-read' if reads else
                              'stored-or-escaping' if stores else 'other-use-review-required')
            packets.append({'commit': commit['id'], 'block': commit.get('block'),
                            'virtualArray': vid, 'length': len(values), 'values': values,
                            'sourceFamily': family(virtual), 'sourcePosition': source(virtual),
                            'classification': classification, 'sinks': sinks})

    mid_nodes = {n['id']: n for n in mid['nodes']}
    incoming = collections.defaultdict(list)
    for e in mid['edges']:
        incoming[e['to']].append(e)
    late = []
    for n in mid['nodes']:
        if not short(n).endswith('NewArrayNode') or n['properties'].get('elementType') != 'java.lang.Object':
            continue
        lengths = []
        for e in incoming[n['id']]:
            if e['label'] == 'length':
                arg = mid_nodes[e['from']]
                lengths.append({'node': arg['id'], 'value': arg['properties'].get('value'),
                                'stamp': arg['properties'].get('stamp')})
        late.append({'id': n['id'], 'block': n.get('block'), 'lengthInputs': lengths,
                     'sourceFamily': family(n), 'sourcePosition': source(n)})
    late_boxes = []
    for n in mid['nodes']:
        if short(n).endswith('NewInstanceNode') and n['properties'].get('instanceClass') == 'java.lang.Long':
            position = source(n)
            origin = next((line.split('(')[0] for line in position if line.startswith('thc.runtime.')), 'unknown')
            late_boxes.append({'id': n['id'], 'block': n.get('block'), 'origin': origin, 'sourcePosition': position})
    method_targets = collections.Counter(n['properties'].get('targetMethod') for n in graph['nodes'] if short(n) == 'MethodCallTargetNode')
    allocations = allocation_nodes(mid)
    guards = null_result_guards(graph, nodes, high_incoming)
    return {'root': graph['group'], 'beforeHighOrdinal': graph['ordinal'], 'midOrdinal': mid['ordinal'],
            'beforeHighNodes': len(graph['nodes']), 'afterMidNodes': len(mid['nodes']),
            'committedPacketCount': len(packets), 'lateObjectArrayCount': len(late),
            'packetsBySource': dict(collections.Counter(p['sourceFamily'] for p in packets)),
            'packetsByClassification': dict(collections.Counter(p['classification'] for p in packets)),
            'lateArraysBySource': dict(collections.Counter(p['sourceFamily'] for p in late)),
            'methodTargets': dict(method_targets), 'packets': packets, 'lateArrays': late,
            'lateLongBoxCount': len(late_boxes),
            'lateLongBoxesByOrigin': dict(collections.Counter(box['origin'] for box in late_boxes)),
            'lateLongBoxes': late_boxes,
            'lateAllocationNodeCount': len(allocations),
            'lateAllocationNodesByType': dict(collections.Counter(n['allocatedType'] for n in allocations)),
            'lateAllocationNodes': allocations,
            'nullResultGuardCount': len(guards), 'nullResultGuards': guards}


def read_capture(directory):
    results = []
    root_ids = {}
    runlog = directory / 'run.log'
    if runlog.exists():
        for line in runlog.read_text().splitlines():
            match = re.search(r'\bid=(\d+).*?\|CompId\s+(\d+)', line)
            if match:
                root_ids[int(match.group(2))] = int(match.group(1))
    for index in sorted(directory.glob('parsed-*/index.json')):
        data = json.loads(index.read_text())
        high = next((g for g in data['graphs'] if 'Before phase HighTierLowering' in g['name']), None)
        mid = next((g for g in data['graphs'] if 'After mid tier' in g['name']), None)
        if not high or not mid:
            continue
        def read(g):
            return json.loads((index.parent / f"graph-{g['ordinal']:05}.json").read_text())
        result = inspect_graph(read(high), read(mid))
        compilation = re.search(r'TruffleHotSpotCompilation-(\d+)', index.parent.name)
        result['compilationId'] = int(compilation.group(1)) if compilation else None
        result['rootInstanceId'] = root_ids.get(result['compilationId'])
        result['rootKey'] = result['root'] + (f" [rootId={result['rootInstanceId']}]" if result['rootInstanceId'] is not None else '')
        result['bgv'] = pathlib.Path(data['source']).name
        results.append(result)
    if not results:
        raise SystemExit('No parsed before-high / after-mid graph pairs found')
    latest = {}
    for result in results:
        key = result['rootKey']
        if key not in latest or (result['compilationId'] or 0) > (latest[key]['compilationId'] or 0):
            latest[key] = result
    return results, latest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=pathlib.Path)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    parser.add_argument('--baseline', type=pathlib.Path)
    parser.add_argument('--require-clean-lookup', action='store_true')
    args = parser.parse_args()
    graphs, latest = read_capture(args.capture)
    report = {'schema': 1, 'claimScope': 'Static sites in selected compiler phases; no dynamic allocation counts or execution-frequency claims.',
              'allocationScope': 'Explicit allocation nodes after mid tier, including unlowered allocations if present. Calls may allocate outside this graph. Packet and Long counts are subsets, not the full allocation inventory.',
              'guardScope': 'Before-high null-result guard paths through value aliases. Source positions and paths alone do not establish which arm ran or caused an observed deoptimization. javaSourcePosition omits embedded guest annotations retained in the input graph.',
              'graphs': graphs, 'latestCompilationByRoot': {k: v['compilationId'] for k, v in latest.items()}}
    if args.baseline:
        baseline = json.loads(args.baseline.read_text())
        old = {g['rootKey']: g for g in baseline['graphs'] if g['compilationId'] == baseline['latestCompilationByRoot'].get(g['rootKey'])}
        report['comparisonMatching'] = 'Runtime root label and per-engine root id. Confirm matching roles if guest source/root creation order changes; this comparison is intended for the same exported program.'
        report['comparison'] = []
        for root, new in latest.items():
            if root not in old:
                continue
            row = {'root': root, 'baselineCompilation': old[root]['compilationId'], 'candidateCompilation': new['compilationId']}
            for key in ('committedPacketCount', 'lateObjectArrayCount', 'packetsBySource', 'packetsByClassification', 'lateArraysBySource', 'methodTargets', 'lateLongBoxCount', 'lateLongBoxesByOrigin', 'lateAllocationNodeCount', 'lateAllocationNodesByType', 'nullResultGuardCount'):
                row[key] = {'baseline': old[root].get(key), 'candidate': new[key]}
            report['comparison'].append(row)
    if args.require_clean_lookup:
        lookup = [g for root, g in latest.items() if 'lambda_def,_ww,_ds1' in root]
        passed = len(lookup) == 1 and lookup[0]['lateObjectArrayCount'] == 0 and not lookup[0]['methodTargets']
        report['lookupGate'] = {'passed': passed, 'requirement': 'Exactly one latest lookup root; zero Object[] allocations after mid-tier and zero residual MethodCallTarget nodes before high-tier lowering.'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, allow_nan=False) + '\n')
    for root, g in latest.items():
        print(f"{g['compilationId']} {root}: packets={g['committedPacketCount']} lateArrays={g['lateObjectArrayCount']} lateLongBoxes={g['lateLongBoxCount']} sources={g['packetsBySource']} classes={g['packetsByClassification']} lateAllocationNodes={g['lateAllocationNodeCount']} nullResultGuards={g['nullResultGuardCount']}")
    if args.require_clean_lookup and not report['lookupGate']['passed']:
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
