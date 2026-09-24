#!/usr/bin/env python3
"""Retain the first capture's semantic successes and genuine compiled frontier."""
import hashlib
import json
from collections import Counter
from pathlib import Path

root = Path('build/int32x4-bytearray-runtime-frozen')
output = root / 'first-capture-frontier.json'
if output.exists():
    raise RuntimeError('Refusing to replace first-capture report')
def record(path):
    return {'path': str(path), 'bytes': path.stat().st_size,
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
def require(condition, message):
    if not condition:
        raise AssertionError(message)
require((root / 'check.exit-status.txt').read_text().strip() == '1', 'Expected retained reader failure')
records = []
for stage in ('pre', 'post'):
    for backend in ('ast', 'bytecode'):
        for entry in ('vectorIndexWorker', 'scalarIndexWorker', 'vectorStoreGraph', 'scalarStoreGraph'):
            folder = root / f'{stage}-{backend}-{entry}'
            for command in ('run', 'parse'):
                require((folder / f'{command}.exit-status.txt').read_text().strip() == '0', 'Capture failed')
            graphs = list((folder / 'parsed').glob('graph-*.json'))
            require(len(graphs) == 1, 'Expected one selected graph')
            graph = json.loads(graphs[0].read_text())
            kinds = Counter(n['nodeClass'].rsplit('.', 1)[-1] for n in graph['nodes'])
            require(kinds['CommitAllocationNode'] == kinds['AllocatedObjectNode'] == kinds['InvokeWithExceptionNode'] == 1,
                    'Expected genuine allocation and call frontier')
            relevant = []
            for node in graph['nodes']:
                p = node['properties']
                if (node['nodeClass'].rsplit('.', 1)[-1] in ('CommitAllocationNode', 'AllocatedObjectNode', 'InvokeWithExceptionNode')
                    or 'RuntimeFault' in str(p.get('type', '')) or 'fillInStackTrace' in str(p.get('targetMethod', ''))):
                    relevant.append({'id': node['id'], 'nodeClass': node['nodeClass'],
                                     'properties': {k: v for k, v in p.items() if k in ('type', 'targetMethod', 'stamp')}})
            records.append({'stage': stage, 'backend': backend, 'entry': entry,
                            'graph': record(graphs[0]), 'nodeKinds': dict(kinds), 'frontierNodes': relevant,
                            'artifacts': [record(p) for p in sorted(folder.rglob('*'))
                                          if p.is_file() and p.suffix in ('.bgv', '.cfg', '.log', '.txt')]})
report = {'claim': 'All sixteen first guest/capture/parse commands passed; compiled graph acceptance FAILED.',
          'sourceRevision': json.loads((root / 'runtime-snapshot.json').read_text())['sourceRevision'],
          'reason': 'Bounds-error RuntimeFault allocation and Throwable.fillInStackTrace remained in every compiled graph.',
          'firstCheckerError': 'Residual private carrier/vector/payload reference: a!# byte[]',
          'runtimeFix': '62ad4655b5ab56fd203bc18f8d03139e3d89d1ad',
          'recapturePolicy': 'New runtime checkpoint in a new directory; original captures/checker logs are unchanged.',
          'originalCheck': record(root / 'check.log'), 'records': records}
output.write_text(json.dumps(report, indent=2) + '\n')
print('Retained failed frontier for', len(records), 'captures:', output)
