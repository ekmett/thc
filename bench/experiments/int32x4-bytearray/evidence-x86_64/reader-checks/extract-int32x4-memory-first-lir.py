#!/usr/bin/env python3
"""Extract compact views of retained failed captures without changing originals."""
import importlib.util
import json
from pathlib import Path

reader = Path('bench/experiments/int32x4-bytearray/runtime-audit.py')
spec = importlib.util.spec_from_file_location('memory_reader', reader)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
directory = Path('build/int32x4-memory-reader-checks')
proof = json.loads((directory/'retained-failure.json').read_text())
audit.require(audit.record(reader) == proof['reader'], 'Reader changed after diagnostic')
output = directory/'first-lir'
audit.require(not output.exists(), 'Refusing to replace generated LIR views')
output.mkdir()
for item in proof['records']:
    cfg = Path(item['cfg']['path'])
    audit.require(audit.record(cfg) == item['cfg'], 'Original CFG changed')
    folder = cfg.parent.parent
    targets = [json.loads(line.removeprefix('GRAPH_TARGET=')) for line in (folder/'run.log').read_text().splitlines()
               if line.startswith('GRAPH_TARGET=')]
    audit.require(len(targets) == 1, 'Expected one target')
    lir, instructions = audit.inspect_lir(cfg.read_text(), targets[0]['root'], item['entry'], 'x86_64')
    audit.require(instructions == item['physicalPackedInstructions'], 'LIR view changed')
    (output/(folder.name+'.txt')).write_text(lir)
print('Extracted sixteen compact final LIR views; original CFGs and failures unchanged.')
