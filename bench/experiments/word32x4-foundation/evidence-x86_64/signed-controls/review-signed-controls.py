"""Offline report only: real signed Int32 controls are not unsigned capture evidence."""
import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--root', type=Path, required=True)
parser.add_argument('--signed-root', type=Path, required=True)
args = parser.parse_args()
root = args.root.resolve()
source = args.signed_root.resolve()
out = root/'build/word32x4-signed-controls'
reader = root/'bench/experiments/word32x4-foundation/runtime-audit.py'
spec = importlib.util.spec_from_file_location('word32_reader', reader)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
record = lambda path: dict(path=str(path), sha256=sha(path))
original = source/'evidence.json'
proof = json.loads(original.read_text())
published = root/'bench/experiments/simd-foundation/evidence-int32x4-x86_64/runtime/evidence.json'
retained = json.loads(published.read_text())
assert retained['packaging']['originalEvidenceSha256'] == sha(original)
results = []
for capture in proof['results']:
    name = f'{capture["stage"]}-{capture["backend"]}-{capture["entry"]}'
    entry = {'vectorCase': 'plusCase', 'subtractCase': 'minusCase'}[capture['entry']]
    directory = source/name
    index_path = directory/'parsed/index.json'
    index = json.loads(index_path.read_text())
    selected = [graph for graph in index['graphs'] if 'Before phase HighTierLowering' in graph['name']]
    assert len(selected) == 1 and selected[0] == capture['highTierGraph']
    parsed = directory/'parsed'/selected[0]['file']
    compressed = out/(name+'.graph.json.gz')
    assert gzip.decompress(compressed.read_bytes()) == parsed.read_bytes()
    graph = json.loads(parsed.read_text())
    for key in ('name', 'group', 'nodeClassCounts'):
        assert graph[key] == selected[0][key]
    try:
        audit.inspect_graph(graph, entry)
    except AssertionError as error:
        rejection = str(error)
        assert rejection == 'Exactly the two scalar host Long input unboxes are expected'
    else:
        raise AssertionError('Signed capture unexpectedly accepted by the complete unsigned reader')
    nodes = {node['id']: node for node in graph['nodes']}
    edges = [edge for edge in graph['edges'] if edge['type'] == 'Value']
    unboxes = [node for node in nodes.values() if audit.kind(node) == 'UnboxNode']
    assert len(unboxes) == 4 and all(node['properties']['boxingKind'] == 'JavaKind.Long' for node in unboxes)
    arithmetic = [node for node in nodes.values() if audit.kind(node) == audit.ENTRIES[entry]
                  and audit.packed_stamp(node['properties'].get('stamp'))]
    assert len(arithmetic) == 1
    cuts = [nodes[edge['to']] for edge in edges if edge['from'] == arithmetic[0]['id']]
    assert len(cuts) == 4 and sorted(node['properties']['offset'] for node in cuts) == list(range(4))
    boxes = [node['id'] for node in nodes.values() if audit.kind(node) == 'BoxNode$AllocatingBoxNode']
    returns = [node['id'] for node in nodes.values() if audit.kind(node) == 'ReturnNode']
    assert len(boxes) == len(returns) == 1
    def descendants(start):
        seen, pending = {start}, [start]
        while pending:
            current = pending.pop()
            for destination in {edge['to'] for edge in edges if edge['from'] == current}-seen:
                seen.add(destination)
                pending.append(destination)
        return seen
    lanes = []
    for cut in cuts:
        assert audit.kind(cut) == 'SimdCutNode' and cut['properties']['length'] == 1
        consumers = [nodes[edge['to']] for edge in edges if edge['from'] == cut['id']]
        assert len(consumers) == 1
        extension = consumers[0]
        assert audit.kind(extension) == 'SignExtendNode'
        assert extension['properties']['inputBits'] == 32 and extension['properties']['resultBits'] == 64
        assert boxes[0] in descendants(extension['id']) and returns[0] in descendants(extension['id'])
        lanes.append(dict(lane=cut['properties']['offset'], cut=cut['id'], extension=extension['id'],
                          extensionKind='SignExtendNode', inputBits=32, resultBits=64,
                          resultConnected=True, satisfiesUnsignedExtensionPredicate=False))
    results.append(dict(stage=capture['stage'], backend=capture['backend'], sourceEntry=capture['entry'],
                        unsignedReaderEntry=entry, parsedGraph=record(parsed), compressedGraph=record(compressed),
                        parsedIndex=record(index_path), rootGroup=graph['group'], scalarHostUnboxes=4,
                        fullReaderAccepted=False, fullReaderRejection=rejection,
                        packedArithmetic=dict(id=arithmetic[0]['id'], nodeClass=arithmetic[0]['nodeClass'],
                                              stamp=arithmetic[0]['properties']['stamp']),
                        signedLaneChains=sorted(lanes, key=lambda lane: lane['lane'])))
assert len(results) == 8 and sum(len(result['signedLaneChains']) for result in results) == 32
print(json.dumps(dict(schema=1, sourceVector='int32x4', unsignedTarget='word32x4',
    readerRevision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
    reader=record(reader), reviewScript=record(Path(__file__).resolve()),
    originalEvidence=record(original), publishedEvidence=record(published),
    declaredOriginalRuntimeRevision=retained['sourceRevision'], graphCount=8, signedLaneChainCount=32,
    compression='Review-time gzip -n -c of the exact original local parsed graph; decoded bytes checked identical. Not an original capture artifact hash.',
    scope='All eight real signed add/sub graphs fail the full Word32 reader FIRST at host arity 4 versus 2. Separately, all 32 live output chains use SignExtend32to64 and fail the exact unsigned widening predicate. No unsigned-specific full-gate rejection is claimed.',
    limitations=['No signed multiplication capture was available or inferred.',
                 'Original parsed graphs were newly hashed from the retained local capture; published historical evidence retained graph summaries, not parsed-file hashes.',
                 'No guest, native, JVM, compiler, or capture execution was repeated. No runtime or reader source was edited.'],
    results=results), indent=2))
