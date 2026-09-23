#!/usr/bin/env python3
"""Summarize existing GraphInspect JSON. Does not compile, parse BGV, or run guest code.

python3 work/intthunk-graph-summary.py CAPTURE_DIR --output summary.json
python3 work/intthunk-graph-summary.py graph-00042.json --root '.*'

Counts are STATIC GRAPH SITES, not dynamic allocation counts/instruction counts.
Before-high committed objects and after-mid allocations are separate phases, never summed.
"""
import argparse
import collections
import json
import pathlib
import re


def short(node):
    return node['nodeClass'].rsplit('.', 1)[-1]


def source(node):
    return str(node['properties'].get('nodeSourcePosition', '')).splitlines()[:8]


def node_type(node):
    p = node['properties']
    if short(node).endswith('ArrayNode'):
        component = p.get('componentType') or p.get('elementType')
        if component:
            return str(component) + '[]'
    return str(p.get('instanceClass') or p.get('type') or p.get('stamp') or '<unknown>')


def role(node):
    ty = node_type(node)
    src = '\n'.join(source(node))
    fields = ' '.join(map(str, node['properties'].get('fields', [])))
    if ty == 'thc.runtime.Thunk':
        return 'ordinary-thunk'
    if ty == 'thc.runtime.ExperimentalIntThunk':
        return 'primitive-int-thunk'
    if ty == 'java.lang.Long':
        return 'java-long-box'
    if ty == 'java.lang.Object[]' or 'java.lang.Object[]' in ty:
        return 'object-array'
    if 'GeneratedStaticObject' in ty:
        if 'CaptureLayout.capture' in src or 'capture__' in fields:
            return 'capture-carrier'
        if 'DataLayout.createLong' in src:
            return 'i-sharp-carrier'
        if 'field__0' in fields:
            return 'constructor-carrier-review-source'
        return 'generated-carrier-review-source'
    return 'other'


READS = {'LoadFieldNode', 'ReadNode', 'FloatingReadNode', 'RawLoadNode',
         'JavaReadNode', 'LoadIndexedNode'}
PASSTHROUGH = {'PiNode', 'PiArrayNode', 'ValueProxyNode', 'ValuePhiNode',
               'CompressionNode', 'UncompressPointerNode', 'ZeroExtendNode',
               'SignExtendNode', 'NarrowNode', 'ConvertNode'}


def field_name(node):
    p = node['properties']
    return str(p.get('field') or p.get('location') or p.get('locationIdentity')
               or ('Array: indexed' if short(node) == 'LoadIndexedNode' else '<unresolved>'))


def inspect(graph, path):
    nodes = {n['id']: n for n in graph['nodes']}
    incoming = collections.defaultdict(list)
    for edge in graph['edges']:
        incoming[edge['to']].append(edge)

    def value_inputs(ident):
        return [e for e in incoming[ident] if e['type'] == 'Value']

    def tiny_node(ident):
        n = nodes[ident]
        return dict(node=ident, kind=short(n), block=n.get('block'),
                    properties={k: n['properties'][k] for k in
                                ('field', 'location', 'locationIdentity', 'value', 'type',
                                 'checkedType', 'stamp', 'condition', 'targetMethod')
                                if k in n['properties']})

    def dependencies(ident):
        """Only trace value/address aliases, not graph memory-order dependencies."""
        first = [e for e in incoming[ident] if e['type'] == 'Value' or
                 (e['type'] == 'Association' and e['label'] == 'address')]
        queue = collections.deque((e['from'], [ident, e['from']], 0) for e in first)
        seen = set()
        found = []
        while queue:
            cur, trail, depth = queue.popleft()
            if cur in seen or depth > 10:
                continue
            seen.add(cur)
            n = nodes[cur]
            kind = short(n)
            if kind in READS:
                found.append(dict(node=cur, kind=kind, field=field_name(n), path=list(reversed(trail))))
                continue
            if kind in PASSTHROUGH or kind.endswith('AddressNode') or kind.endswith('CompressionNode'):
                queue.extend((e['from'], trail + [e['from']], depth + 1) for e in value_inputs(cur))
        return found

    commits, instances, arrays, reads, calls, branches = [], [], [], [], [], []
    for n in graph['nodes']:
        p, kind = n['properties'], short(n)
        common = dict(node=n['id'], kind=kind, block=n.get('block'),
                      relativeFrequency=p.get('relativeFrequency'), source=source(n))
        if kind == 'CommitAllocationNode':
            for key, description in p.items():
                match = re.fullmatch(r'object\((\d+)\)', key)
                if not match:
                    continue
                v = nodes[int(match.group(1))]
                commits.append(dict(commit=n['id'], virtual=v['id'], block=n.get('block'),
                    relativeFrequency=p.get('relativeFrequency'), type=node_type(v), role=role(v),
                    fields=v['properties'].get('fields'), description=description, source=source(v)))
        if kind.endswith('NewInstanceNode'):
            instances.append(dict(common, type=node_type(n), role=role(n)))
        if kind.endswith('NewArrayNode'):
            length_inputs = [tiny_node(e['from']) for e in value_inputs(n['id']) if e['label'] == 'length']
            arrays.append(dict(common, type=node_type(n), role=role(n), lengthInputs=length_inputs))
        if kind in READS:
            reads.append(dict(common, field=field_name(n), stamp=p.get('stamp'),
                              dependentInputReads=dependencies(n['id'])))
        if kind.endswith('CallTargetNode') or 'ForeignCall' in kind:
            calls.append(dict(common, target=p.get('targetMethod') or p.get('target')
                              or p.get('descriptor') or p.get('name'),
                              arguments=[tiny_node(e['from']) for e in value_inputs(n['id'])]))
        if kind == 'IfNode':
            condition_edges = [e for e in value_inputs(n['id']) if e['label'] == 'condition']
            condition_nodes = []
            queue = collections.deque((e['from'], 0) for e in condition_edges)
            seen = set()
            while queue and len(condition_nodes) < 24:
                ident, depth = queue.popleft()
                if ident in seen or depth > 3:
                    continue
                seen.add(ident)
                condition_nodes.append(tiny_node(ident))
                queue.extend((e['from'], depth + 1) for e in value_inputs(ident))
            branches.append(dict(common,
                probability={k: v for k, v in p.items() if 'probab' in k.lower()},
                conditionNodes=condition_nodes))

    def counts(rows, key):
        return dict(collections.Counter(str(r.get(key)) for r in rows))

    return dict(file=str(path), group=graph.get('group'), phase=graph.get('name'),
        ordinal=graph.get('ordinal'), totalNodes=len(nodes), totalBlocks=len(graph.get('blocks', [])),
        committedByType=counts(commits, 'type'), committedByRole=counts(commits, 'role'),
        newInstancesByType=counts(instances, 'type'), newInstancesByRole=counts(instances, 'role'),
        newArraysByType=counts(arrays, 'type'), readsByField=counts(reads, 'field'),
        callsByTarget=counts(calls, 'target'), committedObjects=commits, newInstances=instances,
        newArrays=arrays, fieldReads=reads, calls=calls, branches=branches)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inputs', nargs='+', type=pathlib.Path)
    parser.add_argument('--output', type=pathlib.Path)
    parser.add_argument('--root', default='(?i)IntThunk|IntBatchRoot|IntProducerRoot', help='Regex on graph group/root label')
    parser.add_argument('--phase', default='(?i)Before phase HighTierLowering|After mid tier|After low tier|Final Schedule')
    parser.add_argument('--all-compilations', action='store_true')
    args = parser.parse_args()
    root_re, phase_re = re.compile(args.root), re.compile(args.phase)
    paths = set()
    for item in args.inputs:
        if item.is_dir():
            paths.update(item.rglob('index.json'))
        else:
            paths.add(item)
    candidates = []
    for path in sorted(paths):
        data = json.loads(path.read_text())
        if 'graphs' in data:
            match = re.search(r'TruffleHotSpotCompilation-(\d+)', data.get('source', str(path)))
            compilation = int(match.group(1)) if match else None
            for row in data['graphs']:
                if row.get('file') and root_re.search(row['group']) and phase_re.search(row['name']):
                    candidates.append(dict(path=path.parent / row['file'], row=row,
                                           compilation=compilation, capture=str(path.parent.parent),
                                           source=data.get('source')))
        elif isinstance(data.get('nodes'), list):
            if root_re.search(data.get('group', '')) and phase_re.search(data.get('name', '')):
                candidates.append(dict(path=path, row=data, compilation=None,
                                       capture=str(path.parent), source=None))
    latest = {}
    for c in candidates:
        key = (c['capture'], c['row']['group'])
        latest[key] = max(latest.get(key, -1), c['compilation'] if c['compilation'] is not None else -1)
    reports = []
    for c in candidates:
        key = (c['capture'], c['row']['group'])
        compilation = c['compilation'] if c['compilation'] is not None else -1
        if not args.all_compilations and compilation != latest[key]:
            continue
        graph = json.loads(c['path'].read_text())
        report = inspect(graph, c['path'])
        report.update(compilationId=c['compilation'], bgv=c['source'], capture=c['capture'])
        reports.append(report)
    result = dict(schema=1, claimScope='Static graph sites and value dependencies only; no guest execution or dynamic counts.',
        caveats=['Tier1 and Tier2 labels remain separate; choose the installed Tier2 compilation.',
                 'A virtual object alone is not an allocation. Commit sites and after-mid allocation nodes are phase-specific.',
                 'Absence in a caller is insufficient if allocation can remain in an out-of-line callee.',
                 'Generated class names are JVM-local. I# classification uses source evidence, not numeric class suffixes.',
                 'Repeated alternative commits are distinct static sites; do not sum them as per-iteration counts.'],
        graphs=reports)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n')
        for r in reports:
            print(json.dumps({k: r[k] for k in ('compilationId', 'group', 'phase', 'committedByRole',
                              'newInstancesByRole', 'newArraysByType', 'readsByField', 'callsByTarget')}))
    else:
        print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
