#!/usr/bin/env python3
"""Offline diagnostic only: corrected reader must still reject first graphs."""
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

reader = Path('bench/experiments/int32x4-bytearray/runtime-audit.py')
spec = importlib.util.spec_from_file_location('memory_reader', reader)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
out = Path(sys.argv[1]) if len(sys.argv) == 2 else Path('build/int32x4-memory-reader-checks/retained-failure.json')
audit.require(not out.exists(), 'Refusing to overwrite diagnostic report')
records = []
for stage in ('pre', 'post'):
    for backend in ('ast', 'bytecode'):
        for entry in audit.ENTRIES:
            path = Path('build/int32x4-bytearray-runtime-frozen') / f'{stage}-{backend}-{entry}'
            graphs, cfgs = list((path / 'parsed').glob('graph-*.json')), list((path / 'graphs').glob('*.cfg'))
            audit.require(len(graphs) == len(cfgs) == 1, 'Expected one graph/CFG')
            targets = [json.loads(line.removeprefix('GRAPH_TARGET=')) for line in (path/'run.log').read_text().splitlines()
                       if line.startswith('GRAPH_TARGET=')]
            audit.require(len(targets) == 1, 'Expected one selected target')
            try:
                audit.inspect_graph(json.loads(graphs[0].read_text()), entry)
            except AssertionError as error:
                message = str(error)
                audit.require(message == 'Residual allocation/payload/call: InvokeWithExceptionNode',
                              'Failure must expose genuine compiled call, not unrelated parser metadata: '+message)
            else:
                raise AssertionError('Original RuntimeFault frontier incorrectly accepted')
            _, instructions = audit.inspect_lir(cfgs[0].read_text(), targets[0]['root'], entry, 'x86_64')
            records.append({'stage': stage, 'backend': backend, 'entry': entry, 'graphRejected': message,
                            'graph': audit.record(graphs[0]), 'cfg': audit.record(cfgs[0]),
                            'physicalPackedInstructions': instructions})
audit.write_json(out, {'claim': 'Offline reader-only diagnostic; no new guest execution and no accepted graph claim.',
                      'reader': audit.record(reader), 'records': records})
print('All sixteen retained graphs still reject genuine compiled calls; all sixteen original packed LIR lines recognized.')
