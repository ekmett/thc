#!/usr/bin/env python3
"""Trace materialized Object[] packets in GraphInspect JSON; never runs guest code.

Usage: python3 tools/audit-call-packets.py PARSED_CAPTURE_DIR --output report.json
Optional --baseline REPORT compares latest compilations by root label.
--require-clean-lookup rejects any late Object[] allocation or residual Java call
in the known Map lookup root. Static sites are not dynamic allocation counts.
"""
import argparse
import collections
import json
import pathlib
import re
import sys

ALIASES = {'AllocatedObjectNode', 'ValuePhiNode', 'ValueProxyNode', 'PiNode', 'PiArrayNode'}
DEBUG_STATE = {'FrameState', 'VirtualObjectState', 'MaterializedObjectState'}


def short(node):
    return node['nodeClass'].split('.')[-1]


def source(node):
    return node['properties'].get('nodeSourcePosition', '').splitlines()


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
    for e in graph['edges']:
        out[e['from']].append(e)

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
    return {'root': graph['group'], 'beforeHighOrdinal': graph['ordinal'], 'midOrdinal': mid['ordinal'],
            'beforeHighNodes': len(graph['nodes']), 'afterMidNodes': len(mid['nodes']),
            'committedPacketCount': len(packets), 'lateObjectArrayCount': len(late),
            'packetsBySource': dict(collections.Counter(p['sourceFamily'] for p in packets)),
            'packetsByClassification': dict(collections.Counter(p['classification'] for p in packets)),
            'lateArraysBySource': dict(collections.Counter(p['sourceFamily'] for p in late)),
            'methodTargets': dict(method_targets), 'packets': packets, 'lateArrays': late,
            'lateLongBoxCount': len(late_boxes),
            'lateLongBoxesByOrigin': dict(collections.Counter(box['origin'] for box in late_boxes)),
            'lateLongBoxes': late_boxes}


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
            for key in ('committedPacketCount', 'lateObjectArrayCount', 'packetsBySource', 'packetsByClassification', 'lateArraysBySource', 'methodTargets', 'lateLongBoxCount', 'lateLongBoxesByOrigin'):
                row[key] = {'baseline': old[root].get(key), 'candidate': new[key]}
            report['comparison'].append(row)
    if args.require_clean_lookup:
        lookup = [g for root, g in latest.items() if 'lambda_def,_ww,_ds1' in root]
        passed = len(lookup) == 1 and lookup[0]['lateObjectArrayCount'] == 0 and not lookup[0]['methodTargets']
        report['lookupGate'] = {'passed': passed, 'requirement': 'Exactly one latest lookup root; zero Object[] allocations after mid-tier and zero residual MethodCallTarget nodes before high-tier lowering.'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, allow_nan=False) + '\n')
    for root, g in latest.items():
        print(f"{g['compilationId']} {root}: packets={g['committedPacketCount']} lateArrays={g['lateObjectArrayCount']} lateLongBoxes={g['lateLongBoxCount']} sources={g['packetsBySource']} classes={g['packetsByClassification']}")
    if args.require_clean_lookup and not report['lookupGate']['passed']:
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
