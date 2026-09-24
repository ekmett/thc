#!/usr/bin/env python3
"""Retain the first genuine AST enum-dispatch failure before rebuilding."""
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil

root = Path.cwd()
capture = root / 'build/floatx4-bytearray-runtime'
out = root / 'build/floatx4-memory-first-runtime-capture'
originals = root / 'build/floatx4-memory-first-runtime-originals'
assert not out.exists() and not originals.exists()
assert (capture / 'check.exit-status.txt').read_text().strip() == '1'
out.mkdir(); originals.mkdir()
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def record(path): return dict(path=str(path), sha256=digest(path))
def write(path, value): path.write_text(json.dumps(value, indent=2) + '\n')
spec = importlib.util.spec_from_file_location('reader', root / 'bench/experiments/floatx4-bytearray/runtime-audit.py')
reader = importlib.util.module_from_spec(spec); spec.loader.exec_module(reader)
snapshot = json.loads((capture / 'runtime-snapshot.json').read_text())
copies = []
for group in ('sources', 'runtimeJars', 'jdkFiles'):
    for item in snapshot[group]:
        source = Path(item['path'])
        assert digest(source) == item['sha256']
        if group == 'jdkFiles': continue
        destination = originals / group / source.relative_to(root)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        copies.append(dict(original=item, copy=record(destination)))
shutil.copytree(root / 'build/simd-floatx4-bytearray', originals / 'native')
for name in ('runtime-snapshot.json', 'input-provenance.json', 'graph-cases.json', 'oracle.tsv',
             'capture-runtime-audit.py', 'capture-test-runtime-audit.py', 'architecture.txt',
             'java-version.txt', 'jdk-release.txt', 'stages.txt'):
    shutil.copyfile(capture / name, out / name)
for prefix in ('prepare', 'javac-probe', 'javac-reader', 'check'):
    for suffix in ('command.txt', 'log', 'exit-status.txt'):
        name = prefix + '.' + suffix
        shutil.copyfile(capture / name, out / name)
results = []
for folder in sorted(capture.glob('*-*-*Graph')):
    destination = out / folder.name; destination.mkdir()
    for prefix in ('run', 'parse'):
        assert (folder / (prefix + '.exit-status.txt')).read_text().strip() == '0'
        for suffix in ('command.txt', 'log', 'exit-status.txt'):
            name = prefix + '.' + suffix; shutil.copyfile(folder / name, destination / name)
    graph_path, = (folder / 'parsed').glob('graph-*.json')
    cfg_path, = (folder / 'graphs').glob('*.cfg')
    bgv_path, = (folder / 'graphs').glob('*.bgv')
    graph = json.loads(graph_path.read_text())
    entry = folder.name.split('-')[-1]
    target, = [json.loads(line.removeprefix('GRAPH_TARGET=')) for line in (folder / 'run.log').read_text().splitlines()
               if line.startswith('GRAPH_TARGET=')]
    result = dict(label=folder.name, graph=record(graph_path), rawLir=record(cfg_path), rawGraph=record(bgv_path))
    try:
        reader.inspect_graph(graph, entry)
        reader.inspect_lir(cfg_path.read_text(), target['root'], entry, 'x86_64')
        result['strictGraphAndLir'] = 'PASS'
    except AssertionError as error:
        result['strictGraphAndLir'] = 'FAIL'; result['failure'] = str(error)
    (destination / 'graph.json.gz').write_bytes(gzip.compress(graph_path.read_bytes(), mtime=0))
    (destination / 'raw-lir.cfg.gz').write_bytes(gzip.compress(cfg_path.read_bytes(), mtime=0))
    results.append(result)
assert len(results) == 16
assert all(r['strictGraphAndLir'] == ('PASS' if '-bytecode-' in r['label'] else 'FAIL') for r in results)
write(out / 'failure-summary.json', dict(source=snapshot['sourceRevision'], results=results,
    diagnosis='Kotlin enum when lowering retains a mutable enum switch-map array read. AST index paths retain all three vector families and lane calls; AST index/store paths retain the default exception allocation and stack-trace call. These are genuine residuals, not metadata.',
    plannedFix='Use direct exact family-identity predicates and explicit fail-closed fault; preserve the closed family mapping and all reader gates.',
    originalSourceAndJarCopies=copies, guestExecutionRepeated=False))
files = sorted(p for p in out.rglob('*') if p.is_file())
(out / 'SHA256SUMS').write_text(''.join(digest(p) + '  ' + p.relative_to(out).as_posix() + '\n' for p in files))
print(json.dumps(dict(files=len(files), originalCopies=len(copies), astFailures=8, bytecodeGraphAndLirPasses=8,
                     manifestSha256=digest(out / 'SHA256SUMS'))))
