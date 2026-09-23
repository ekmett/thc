"""Review-time reconstruction of the original ephemeral offline control command.

Reads the already committed Word32 capture payloads and current signed reader.
Prints the same report schema; never executes a guest or changes any input/report.
This script was retained after the original report, not used for original capture.
"""
import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--root', type=Path, required=True)
args = parser.parse_args()
root = args.root.resolve()
base = root/'bench/experiments/word32x4-foundation/evidence-x86_64'
source = base/'evidence.json'
evidence = json.loads(source.read_text())
reader = root/'bench/experiments/int32x4-multiply/runtime-audit.py'
spec = importlib.util.spec_from_file_location('reader', reader)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
oldspec = importlib.util.spec_from_file_location('unsigned_reader', root/'bench/experiments/word32x4-foundation/runtime-audit.py')
old = importlib.util.module_from_spec(oldspec)
oldspec.loader.exec_module(old)
sha = lambda data: hashlib.sha256(data).hexdigest()
records = []
for record in evidence['results']:
    if record['entry'] != 'timesCase':
        continue
    path = base/f'{record["stage"]}-{record["backend"]}-timesCase/graph.json.gz'
    raw = path.read_bytes()
    decoded = gzip.decompress(raw)
    assert sha(decoded) == record['parsedGraph']['sha256']
    graph = json.loads(decoded)
    assert old.inspect_graph(graph, 'timesCase') == record['packedArithmeticNode']
    try:
        audit.inspect_graph(graph, 'timesCase')
    except AssertionError as error:
        reason = str(error)
        assert reason == 'Int32 output must sign-extend exactly 32 to 64 bits'
    else:
        raise AssertionError('Unsigned multiplication graph accepted')
    nodes = {node['id']: node for node in graph['nodes']}
    edges = [edge for edge in graph['edges'] if edge['type'] == 'Value']
    assert len([node for node in nodes.values() if audit.kind(node) == 'UnboxNode']) == 2
    arithmetic = nodes[record['packedArithmeticNode']['id']]
    assert audit.kind(arithmetic) == 'MulNode' and arithmetic['properties']['stamp'] == '<i32,i32,i32,i32>'
    cuts = [nodes[edge['to']] for edge in edges if edge['from'] == arithmetic['id']]
    assert len(cuts) == 4 and sorted(node['properties']['offset'] for node in cuts) == list(range(4))
    chains = []
    for cut in cuts:
        consumers = [nodes[edge['to']] for edge in edges if edge['from'] == cut['id']]
        assert len(consumers) == 1
        extension = consumers[0]
        assert audit.kind(extension) == 'ZeroExtendNode'
        assert extension['properties']['inputBits'] == 32 and extension['properties']['resultBits'] == 64
        seen = {extension['id']}
        pending = list(seen)
        while pending:
            current = pending.pop()
            for destination in {edge['to'] for edge in edges if edge['from'] == current}-seen:
                seen.add(destination)
                pending.append(destination)
        assert any(audit.kind(nodes[number]) == 'ReturnNode' for number in seen)
        chains.append(dict(lane=cut['properties']['offset'], cut=cut['id'], zeroExtend=extension['id'],
                           inputBits=32, resultBits=64, publicReturnConnected=True))
    records.append(dict(stage=record['stage'], backend=record['backend'], entry='timesCase',
                        sourceCompressedGraph=str(path.relative_to(root)), compressedSha256=sha(raw),
                        decodedSha256=sha(decoded), hostArity=2, originalUnsignedReaderAccepted=True,
                        signedReaderAccepted=False, signedReaderRejection=reason,
                        unsignedLaneChains=sorted(chains, key=lambda chain: chain['lane'])))
assert len(records) == 4 and sum(len(record['unsignedLaneChains']) for record in records) == 16
print(json.dumps(dict(schema=1, sourceRuntimeRevision=evidence['sourceRevision'],
    sourceEvidence=str(source.relative_to(root)), sourceEvidenceSha256=sha(source.read_bytes()),
    signedReader=str(reader.relative_to(root)), signedReaderSha256=sha(reader.read_bytes()),
    graphCount=4, unsignedLaneChains=16,
    scope='Four real same-arity Word32 multiplication captures pass their original unsigned reader and fail the full signed Int32 multiply reader specifically at required SignExtend32to64. Inputs were not mutated. No signed native/capture execution is claimed.',
    records=records), indent=2))
