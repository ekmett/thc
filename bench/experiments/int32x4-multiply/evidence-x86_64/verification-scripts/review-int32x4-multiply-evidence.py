"""Offline integrity and signedness review; executes no guests."""
import gzip
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
root = parser.parse_args().root.resolve()
retained = root / 'bench/experiments/int32x4-multiply/evidence-x86_64'
sys.path.insert(0, str(root / 'scripts'))

def require(condition, message):
    if not condition:
        raise RuntimeError(message)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def load_reader(name, relative):
    spec = importlib.util.spec_from_file_location(name, root / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

signed = load_reader('signed_reader', 'bench/experiments/int32x4-multiply/runtime-audit.py')
unsigned = load_reader('unsigned_reader', 'bench/experiments/word32x4-foundation/runtime-audit.py')
evidence = json.loads((retained / 'evidence.json').read_text())
require(evidence['sourceRevision'] == '597ed24ee8835606437a7cdeb313e28b726d4762', 'Frozen runtime')
require(evidence['checkerCorrection'] is None, 'No checker correction')
require(len(evidence['results']) == 4, 'Four captures')
keys = set()
comparisons = 0
hashes = 0
signed_lanes = 0
frames = []
for result in evidence['results']:
    key = (result['stage'], result['backend'], result['entry'])
    require(key not in keys, 'Duplicate capture')
    keys.add(key)
    label = '-'.join(key)
    for field in ('parsedGraph', 'rawGraph', 'rawLir', 'log', 'command', 'exitStatus', 'lir'):
        item = result[field]
        require(sha(Path(item['path']).read_bytes()) == item['sha256'], field)
        hashes += 1
    decoded = gzip.decompress((retained / label / 'graph.json.gz').read_bytes())
    require(sha(decoded) == result['parsedGraph']['sha256'], 'Compressed graph identity')
    graph = json.loads(decoded)
    require(signed.inspect_graph(graph, result['entry']) == result['packedArithmeticNode'], 'Signed graph structure')
    require((retained / label / 'final-lir.txt').read_bytes() == Path(result['lir']['path']).read_bytes(), 'Retained LIR identity')
    markers = [json.loads(line.removeprefix('GRAPH_TARGET=')) for line in
               Path(result['log']['path']).read_text().splitlines() if line.startswith('GRAPH_TARGET=')]
    require(len(markers) == 1, 'Single executed target marker')
    lir, instructions = signed.inspect_lir(Path(result['rawLir']['path']).read_text(),
        markers[0]['root'], result['entry'], evidence['architecture'])
    require(lir == (retained / label / 'final-lir.txt').read_text(), 'Extracted final LIR')
    require(instructions == result['physicalPackedInstructions'], 'Actual physical instructions')
    require(len(result['physicalPackedInstructions']) == 1 and 'VPMULLD' in result['physicalPackedInstructions'][0], 'Packed multiply')
    lanes = result['packedArithmeticNode']['signedLaneExtensions']
    require([item['lane'] for item in lanes] == list(range(4)), 'Every signed lane')
    signed_lanes += len(lanes)
    require(result['rows'] == 466 and result['compiledCorpusPasses'] == 2, 'Native capture counts')
    comparisons += result['rows'] * result['compiledCorpusPasses']
    require(result['installedEntryValidAfterEveryInput'] and result['activeTargetCheckedAfterEveryInput'], 'Per-call identity/validity')
    nodes = {node['id']: node for node in graph['nodes']}
    for node in nodes.values():
        if signed.kind(node) == 'VirtualArrayNode' and 'byte' in str(node['properties']):
            require(signed.virtual_frame_tags(node, nodes, graph['edges']), 'Exact virtual frame metadata')
            frames.append(dict(capture=label, node=node['id'], properties=node['properties']))
require(keys == {(stage, backend, 'timesCase') for stage in ('pre', 'post') for backend in ('ast', 'bytecode')}, 'Capture selection')
require({item['capture'] for item in frames} == {'pre-bytecode-timesCase', 'post-bytecode-timesCase'}
        and len(frames) == 2 and all(item['node'] == 109 and item['properties']['length'] == 16 for item in frames),
        'Exactly the two observed frame tag arrays')
for stage in ('pre', 'post'):
    require(gzip.decompress((retained / (stage + '-core.json.gz')).read_bytes()) ==
            (root / 'build/simd-int32x4-multiply' / (stage + '-core/SimdInt32X4Multiply.json')).read_bytes(), 'Original Core identity')
opposite = json.loads((retained / 'unsigned-controls/report.json').read_text())
require(sha((root / opposite['signedReader']).read_bytes()) == opposite['signedReaderSha256'], 'Negative-control reader identity')
require(sha((root / opposite['sourceEvidence']).read_bytes()) == opposite['sourceEvidenceSha256'], 'Negative-control source evidence')
for result in opposite['records']:
    compressed = (root / result['sourceCompressedGraph']).read_bytes()
    require(sha(compressed) == result['compressedSha256'], 'Original committed unsigned graph')
    decoded = gzip.decompress(compressed)
    require(sha(decoded) == result['decodedSha256'], 'Unsigned graph decompression')
    graph = json.loads(decoded)
    unsigned.inspect_graph(graph, 'timesCase')
    try:
        signed.inspect_graph(graph, 'timesCase')
    except AssertionError as error:
        require(str(error) == result['signedReaderRejection'] == 'Int32 output must sign-extend exactly 32 to 64 bits', 'Exact signedness rejection')
    else:
        raise RuntimeError('Signed reader accepted unsigned outputs')
require(len(opposite['records']) == 4 and opposite['unsignedLaneChains'] == 16, 'Unsigned-control counts')
print(json.dumps(dict(captures=len(keys), verifiedCaptureHashes=hashes,
    nativeCompiledComparisons=comparisons, signedLaneChains=signed_lanes,
    virtualFrameMetadata=frames, oppositeUnsignedCaptures=4,
    unsignedControlScope='Original committed Word32 multiplication graphs; full signed reader rejects exact widening.',
    guestExecutionRepeated=False), indent=2))
