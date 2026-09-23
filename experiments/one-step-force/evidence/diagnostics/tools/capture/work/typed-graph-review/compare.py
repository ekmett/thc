#!/usr/bin/env python3
"""Compare already parsed typed-AST captures. Never launches guest code or a JVM."""
import argparse
import collections
import hashlib
import json
from pathlib import Path


def read(path):
    return json.loads(Path(path).read_text())


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            h.update(chunk)
    return {'path': str(path), 'sha256': h.hexdigest()}


def inspect(capture, row):
    folders = list(capture.glob(f"parsed-TruffleHotSpotCompilation-{row['compilationId']}[*"))
    # Square brackets in filenames are glob metacharacters; select by a literal prefix.
    prefix = f"parsed-TruffleHotSpotCompilation-{row['compilationId']}["
    folders = [p for p in capture.iterdir() if p.name.startswith(prefix) and p.is_dir()]
    if len(folders) != 1:
        raise ValueError(f'Expected one parsed folder for {prefix}, found {len(folders)}')
    folder = folders[0]
    index = read(folder / 'index.json')
    def phase(name):
        item = next(g for g in index['graphs'] if name in g['name'])
        return item, read(folder / f"graph-{item['ordinal']:05}.json")
    pe = next(g for g in index['graphs'] if 'After PE Tier' in g['name'])
    _, graph = phase('Before phase HighTierLowering')
    nodes = {n['id']: n for n in graph['nodes']}
    incoming = collections.defaultdict(list)
    for edge in graph['edges']:
        incoming[edge['to']].append(edge)
    calls = []
    operations = collections.Counter()
    operation_nodes = []
    checks = collections.Counter()
    for n in graph['nodes']:
        cls = n['nodeClass'].split('.')[-1]
        p = n['properties']
        if cls == 'MethodCallTargetNode' and 'OptimizedCallTarget.callBoundary' in p.get('targetMethod', ''):
            receiver_edge = next(e for e in incoming[n['id']] if e['label'] == 'arguments' and e['listIndex'] == 0)
            receiver = nodes[receiver_edge['from']]
            calls.append({'node': n['id'], 'receiver': receiver['id'], 'receiverClass': receiver['nodeClass'].split('.')[-1]})
        if cls == 'InstanceOfNode':
            checks[p.get('checkedStamp', 'unknown')] += 1
        if cls in ('BoxNode', 'UnboxNode'):
            operations[cls] += 1
            operation_nodes.append({'node': n['id'], 'class': cls, 'properties': p})
    _, tree = phase('After Inline')
    tree_nodes = {n['id']: n for n in tree['nodes']}
    children = collections.defaultdict(list)
    for edge in tree['edges']:
        children[edge['from']].append(edge['to'])
    inlined, frontier = [], []
    def visit(node_id):
        p = tree_nodes[node_id]['properties']
        item = {'node': node_id, 'target': p.get('directCallTarget'), 'state': p['state'].split('.')[-1],
                'frequency': p.get('rootRelativeFrequency'), 'depth': p.get('recursionDepth'),
                'irNodes': p.get('IR Nodes'), 'score': p.get('Inline BpC'), 'threshold': p.get('Threshold')}
        if item['state'] == 'Inlined':
            if node_id:
                inlined.append(item)
            for child in children[node_id]:
                visit(child)
        else:
            frontier.append(item)
    visit(0)
    return {'root': row['rootKey'], 'compilationId': row['compilationId'], 'bgv': row['bgv'],
            'peNodes': pe['nodes'], 'beforeHighNodes': row['beforeHighNodes'], 'afterMidNodes': row['afterMidNodes'],
            'lateArrays': row['lateObjectArrayCount'], 'lateLongBoxes': row['lateLongBoxCount'],
            'lateLongBoxesByOrigin': row['lateLongBoxesByOrigin'], 'lateArraysBySource': row['lateArraysBySource'],
            'methodTargets': row['methodTargets'], 'guestCalls': len(calls),
            'constantGuestTargets': sum(c['receiverClass'] == 'ConstantNode' for c in calls),
            'guestTargetNodes': calls, 'instanceOfChecks': dict(checks), 'boxingOperationsBeforeHigh': dict(operations),
            'boxingOperationNodes': operation_nodes, 'inlinedCalls': inlined, 'activeFrontier': frontier,
            'packetsByClassification': row['packetsByClassification']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path)
    parser.add_argument('--runtime-manifest', type=Path, required=True,
                        help='Manifest recorded for the frozen JAR used by this capture; embedded verbatim')
    parser.add_argument('--modules-list', type=Path, default=Path('build/map/modules.txt'))
    parser.add_argument('--baseline', type=Path, default=Path('docs/bytecode-graphs/graph-comparison.json'))
    parser.add_argument('--baseline-run-config', type=Path, default=Path('bench/results/map-bytecode/comparison/run-config.json'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    base = read(args.baseline)
    report = read(args.capture / 'packet-audit.json')
    latest = {r['rootInstanceId']: r for r in report['graphs']
              if report['latestCompilationByRoot'][r['rootKey']] == r['compilationId']}
    rows = []
    for old in base['roots']:
        candidate_row = latest[old['rootId']]
        if candidate_row['rootKey'] != old['ast']['root']:
            raise ValueError(f"Root identity changed: {old['ast']['root']} vs {candidate_row['rootKey']}")
        rows.append({'rootId': old['rootId'], 'role': old['role'], 'baseline': old['ast'],
                     'candidate': inspect(args.capture, candidate_row)})
    modules = [digest(p.strip()) for p in args.modules_list.read_text().splitlines() if p.strip()]
    original_modules = read(args.baseline_run_config)['provenance']['modules']
    module_map = lambda records: {Path(r['path']).name: r['sha256'] for r in records}
    modules_match = module_map(modules) == module_map(original_modules)
    output = {'schema': 1, 'claimScope': 'Static scheduled-IR sites, not dynamic costs or benchmark results.',
              'baseline': str(args.baseline), 'candidateCapture': str(args.capture),
              'candidateRuntimeManifestFile': digest(args.runtime_manifest),
              'candidateRuntimeManifest': read(args.runtime_manifest),
              'candidateModules': modules, 'candidateModuleList': digest(args.modules_list),
              'moduleHashesMatchPublishedV3': modules_match,
              'provenanceCaveat': 'Module hashes are verified at analysis time. Frozen runtime manifest must identify the actual capture classpath.',
              'captureEvidence': [digest(args.capture / name) for name in ('run.log', 'run.tsv', 'packet-audit.json')],
              'roots': rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2, allow_nan=False) + '\n')
    columns = [('peNodes', 'PE'), ('beforeHighNodes', 'Before high'), ('afterMidNodes', 'After mid'),
               ('guestCalls', 'Guest calls'), ('lateArrays', 'Object[]'), ('lateLongBoxes', 'Long boxes')]
    lines = ['# Typed AST graph comparison', '', 'Latest compilations matched by root ID and label. Counts are static graph sites.', '',
             f'Current Map module hashes match the published v3 modules: **{modules_match}**.', '',
             '| Root | Compilation old/new | ' + ' | '.join(label for _, label in columns) + ' | Inlined edges |',
             '|---|---|' + '|'.join('---:' for _ in columns) + '|---:|']
    for row in rows:
        a, b = row['baseline'], row['candidate']
        lines.append('| ' + row['role'] + f" | {a['compilationId']} / {b['compilationId']} | " +
                     ' | '.join(f"{a[key]} → {b[key]}" for key, _ in columns) +
                     f" | {len(a['inlinedCalls'])} → {len(b['inlinedCalls'])} |")
    lines += ['', '## Residual Java calls', '']
    for row in rows:
        def non_guest(x):
            return {k: v for k, v in x['methodTargets'].items() if 'OptimizedCallTarget.callBoundary' not in k}
        lines.append(f"* {row['role']}: {non_guest(row['baseline'])} → {non_guest(row['candidate'])}")
    lines += ['', '## Interpretation limits', '',
              'More inlining can expose additional branching call sites, so residual static counts alone do not measure dynamic calls. Long boxes at a surviving Object call ABI may remain necessary. Check source origins before attributing any removed site to typed Case, Let, constructor fields, or root-result forwarding.', '']
    args.output.with_suffix('.md').write_text('\n'.join(lines))
    print(args.output)
    print(args.output.with_suffix('.md'))
    print(f'Module hashes match published v3: {modules_match}')


if __name__ == '__main__':
    main()
