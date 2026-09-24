#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Strict native-backed Word8X16 graph/LIR gate. Synthetic parser tests are not evidence."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

ENTRIES = {'plusCase': 'AddNode', 'minusCase': 'SubNode',
           'timesCase': 'ByteMultiplyExpansion'}
OPCODES = {'plusCase': 'VPADDB', 'minusCase': 'VPSUBB',
           'timesCase': 'VPMULLW'}
CORE = Path('build/simd-word8x16')


def require(condition, message):
    # This gate must remain active under python -O as well.
    if not condition:
        raise AssertionError(message)


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def record(path):
    return dict(path=str(path), sha256=digest(path))


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def verify_records(root, records):
    paths = [item['path'] for item in records]
    require(len(paths) == len(set(paths)), 'Duplicate provenance paths')
    for item in records:
        path = Path(item['path'])
        require(not path.is_absolute() and '..' not in path.parts,
                'Expected repository-relative provenance path: ' + str(path))
        require(digest(root / path) == item['sha256'], 'Stale source/artifact: ' + str(path))


def parse_oracle(text, entries):
    rows = {}
    for line in text.splitlines():
        fields = line.split('\t')
        require(fields[0] in entries, 'Unknown oracle entry: ' + fields[0])
        require(len(fields) == entries[fields[0]]['arity'] + 2, 'Wrong oracle arity: ' + line)
        require(all(re.fullmatch(r'-?(?:0|[1-9][0-9]*)', value) for value in fields[1:]),
                'Noncanonical integer oracle row: ' + line)
        values = tuple(map(int, fields[1:]))
        require(all(-(1 << 63) <= value < (1 << 63) for value in values),
                'Oracle integer outside signed Int64: ' + line)
        key = (fields[0], *values[:-1])
        require(key not in rows, 'Duplicate oracle input: ' + str(key))
        rows[key] = values[-1]
    wanted = {(name, *case) for name, entry in entries.items() for case in entry['cases']}
    require(set(rows) == wanted, 'Oracle inputs differ from the declared corpus')
    return rows


def validate_inputs(root, proof):
    require(proof['vector'] == 'word8x16' and proof['positiveAuditsAccepted'] is True,
            'Exact accepted Word8X16 provenance required')
    require(proof['stages'] == ['pre', 'post'], 'Both native pre/post Core stages are required')
    require(isinstance(proof['nativeRows'], int) and proof['nativeRows'] > 0
            and proof['modelMatched'] is True, 'Native oracle required; model-only input is not evidence')
    require(proof['toolchain']['ghcVersion'] == '9.14.1'
            and re.fullmatch(r'[0-9a-f]{64}', proof['toolchain']['ghcBinarySha256'])
            and proof['commands'], 'Missing pinned native toolchain/command provenance')
    verify_records(root, proof['sources'] + proof['artifacts'])
    artifacts = {item['path'] for item in proof['artifacts']}
    needed = {str(CORE / name) for name in (
        'oracle.tsv', 'expected.tsv', 'native/word8x16-oracle',
        'pre-core/SimdWord8X16.json', 'post-core/SimdWord8X16.json')}
    require(needed <= artifacts, 'Native/Core/model artifact hash missing')
    entries = {entry['name']: entry for entry in proof['entries']}
    require(len(entries) == len(proof['entries']), 'Duplicate entry declaration')
    for name, entry in entries.items():
        require(entry['cases'] and all(len(case) == entry['arity'] for case in entry['cases']),
                'Empty or malformed declared corpus: ' + name)
        require(len(entry['cases']) == len({tuple(case) for case in entry['cases']}),
                'Duplicate declared input: ' + name)
    for name in ENTRIES:
        require(name in entries and entries[name]['arity'] == 2, 'Expected scalar arity-two graph root: ' + name)
        for stage in proof['stages']:
            require(proof['audits'][stage][name]['accepted'] is True, 'Root strict audit rejected: ' + name)
    rows = parse_oracle((root / CORE / 'oracle.tsv').read_text(), entries)
    model = parse_oracle((root / CORE / 'expected.tsv').read_text(), entries)
    require(rows == model and len(rows) == proof['nativeRows'] == proof['modelRows'],
            'Native/model rows or counts differ')
    return {name: [entry['arity'], len(entry['cases'])] for name, entry in entries.items()}


def snapshot(root, java_home):
    sources = [root / 'build.gradle.kts', root / 'tools/GraphInspect.java']
    sources += [path for path in sorted((root / 'src/main').rglob('*')) if path.is_file()]
    sources += [path for path in sorted((root / 'bench/experiments/word8x16-foundation').iterdir())
                if path.suffix in ('.py', '.sh', '.java')]
    jars = sorted((root / 'build/install/thc/lib').glob('*.jar'))
    require(jars, 'Build installDist before capture')
    return dict(sourceRevision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
                sources=[record(path) for path in sources], runtimeJars=[record(path) for path in jars],
                jdkFiles=[record(java_home / name) for name in ('release', 'bin/java', 'bin/javac', 'lib/modules')])


def kind(node):
    return node['nodeClass'].rsplit('.', 1)[-1]


def packed_stamp(stamp, bits=8, length=16):
    if not isinstance(stamp, str) or not stamp.startswith('<') or not stamp.endswith('>'):
        return False
    lanes = stamp[1:-1].split(',')
    return len(lanes) == length and all(re.match(r'^i' + str(bits) + r'(?:\s|$)', lane) for lane in lanes)


def byte_multiply_expansion(nodes, edges, live):
    """Recognize the pinned Graal expansion, not a fictional packed byte MUL.

    Adjacent bytes are reinterpreted as eight shorts. Both byte positions are
    multiplied independently, then truncated and recombined into sixteen bytes.
    Every edge and constant matters: merely finding two word MULs is not proof.
    """
    def inputs(node, label):
        return [edge['from'] for edge in edges if edge['to'] == node and
                edge['type'] == 'Value' and edge['label'] == label]

    def child(node, label):
        values = inputs(node, label)
        return values[0] if len(values) == 1 else None

    def named(node, name, bits=16, length=8):
        return node in nodes and kind(nodes[node]) == name and packed_stamp(
            nodes[node]['properties'].get('stamp'), bits, length)

    def constant(node, value, vector=False):
        if node not in nodes or kind(nodes[node]) != 'ConstantNode':
            return False
        props = nodes[node]['properties']
        if vector:
            return packed_stamp(props.get('stamp'), 16, 8) and props.get('rawvalue') == \
                '<' + ','.join([str(value)] * 8) + '>'
        return isinstance(props.get('stamp'), str) and re.match(r'^i32(?:\s|$)', props['stamp']) \
            and props.get('rawvalue') == str(value)

    def masked(node):
        if not named(node, 'AndNode'):
            return None
        x, y = child(node, 'x'), child(node, 'y')
        if constant(y, 255, vector=True):
            return x
        if constant(x, 255, vector=True):
            return y
        return None

    def shifted(node, name):
        return child(node, 'x') if named(node, name) and constant(child(node, 'y'), 8) else None

    def unpack(node):
        if not named(node, 'ReinterpretNode'):
            return None
        source = child(node, 'value')
        return source if source in nodes and packed_stamp(nodes[source]['properties'].get('stamp')) else None

    products = [n for n in nodes if named(n, 'MulNode')]
    require(len(products) == 2, 'Byte multiply requires exactly two packed i16x8 products')
    require(not any(kind(n) == 'MulNode' and packed_stamp(n['properties'].get('stamp'))
                    for n in nodes.values()), 'A packed i8 MulNode is not the pinned x86 expansion')
    matches = []
    for output in nodes:
        if not named(output, 'ReinterpretNode', 8, 16) or output not in live:
            continue
        joined = child(output, 'value')
        if not named(joined, 'OrNode'):
            continue
        left, right = child(joined, 'x'), child(joined, 'y')
        for low, high in ((left, right), (right, left)):
            low_product, high_product = masked(low), shifted(high, 'LeftShiftNode')
            if low_product == high_product or {low_product, high_product} != set(products):
                continue
            low_inputs = [unpack(masked(child(low_product, label))) for label in ('x', 'y')]
            high_inputs = [unpack(shifted(child(high_product, label), 'UnsignedRightShiftNode'))
                           for label in ('x', 'y')]
            if (None in low_inputs or None in high_inputs or len(set(low_inputs)) != 2
                    or sorted(low_inputs) != sorted(high_inputs)):
                continue
            if not all(node in live for node in (joined, low, high, *products)):
                continue
            matches.append((output, low_product, high_product))
    require(len(matches) == 1, 'Missing unique connected mask/shift/OR byte-product reconstruction')
    output, low, high = matches[0]
    return nodes[output], dict(mode='paired-byte multiply via packed shorts',
        products=[dict(id=number, nodeClass=nodes[number]['nodeClass'],
                       stamp=nodes[number]['properties']['stamp']) for number in (low, high)],
        maskPerShort=255, shiftBits=8)


def virtual_frame_tags(node, nodes, edges):
    """Recognize only the eliminated FrameWithoutBoxing.indexedTags metadata.

    VirtualObjectState describes deoptimization reconstruction, not an allocation.
    This is deliberately not a general exception for virtual byte/vector payloads.
    """
    frame_type = 'com.oracle.truffle.api.impl.FrameWithoutBoxing'
    fields = [frame_type + '.' + name for name in (
        'descriptor', 'arguments', 'indexedLocals', 'indexedPrimitiveLocals', 'indexedTags', 'auxiliarySlots')]
    props = node['properties']
    length = props.get('length')
    if (kind(node) != 'VirtualArrayNode' or props.get('stamp') != 'a!# byte[]'
            or props.get('componentType') != 'byte' or type(length) is not int or length <= 0):
        return False

    def incoming(number):
        return [e for e in edges if e['to'] == number]

    def outgoing(number):
        return [e for e in edges if e['from'] == number]

    def object_of(state):
        objects = [e['from'] for e in incoming(state) if e['label'] == 'object' and e['type'] == 'Value']
        return objects[0] if len(objects) == 1 else None

    uses = outgoing(node['id'])
    if incoming(node['id']) or len(uses) != 2 or any(
            e['type'] != 'Value' or kind(nodes[e['to']]) != 'VirtualObjectState' for e in uses):
        return False
    own = [e['to'] for e in uses if e['label'] == 'object']
    owners = [e['to'] for e in uses if e['label'] == 'values' and e.get('listIndex') == 4]
    if len(own) != 1 or len(owners) != 1 or own == owners:
        return False
    own, owner_state = own[0], owners[0]
    owner = object_of(owner_state)
    if owner not in nodes or object_of(own) != node['id']:
        return False
    owner_node = nodes[owner]
    if (incoming(owner) or kind(owner_node) != 'VirtualInstanceNode' or owner_node['properties'].get('type') != frame_type
            or owner_node['properties'].get('fields') != fields):
        return False
    # Both reconstruction records must be used exclusively as state mappings of
    # the same actual FrameState; the virtual owner must not escape either.
    state_users = []
    for state in (own, owner_state):
        consumers = outgoing(state)
        if not consumers or any(e['type'] != 'State' or e['label'] != 'virtualObjectMappings'
                                or kind(nodes[e['to']]) != 'FrameState' for e in consumers):
            return False
        state_users.append({e['to'] for e in consumers})
    if state_users[0] != state_users[1]:
        return False
    for use in outgoing(owner):
        if use['type'] != 'Value' or not (
                (use['to'] == owner_state and use['label'] == 'object')
                or (kind(nodes[use['to']]) == 'FrameState' and use['label'] == 'values')):
            return False
    frames = state_users[0] | {e['to'] for e in outgoing(owner) if kind(nodes[e['to']]) == 'FrameState'}
    pending = list(frames)
    while pending:
        for use in outgoing(pending.pop()):
            if use['type'] != 'State':
                return False
            if kind(nodes[use['to']]) == 'FrameState' and use['to'] not in frames:
                frames.add(use['to']); pending.append(use['to'])
    values = [e for e in incoming(owner_state) if e['label'] == 'values' and e['type'] == 'Value']
    if len(incoming(owner_state)) != 7 or sorted(e.get('listIndex', -1) for e in values) != list(range(6)):
        return False
    slots = {e['listIndex']: nodes[e['from']] for e in values}
    if slots[4]['id'] != node['id']:
        return False
    for index, component in ((2, 'java.lang.Object'), (3, 'long')):
        sibling = slots[index]
        if (kind(sibling) != 'VirtualArrayNode' or sibling['properties'].get('componentType') != component
                or sibling['properties'].get('length') != length):
            return False
    tags = [e for e in incoming(own) if e['label'] == 'values' and e['type'] == 'Value']
    if len(incoming(own)) != length + 1 or sorted(e.get('listIndex', -1) for e in tags) != list(range(length)):
        return False
    # Pinned FrameWithoutBoxing.ILLEGAL_TAG is 7: these are initial frame tags,
    # not data from any guest lane. No reference is accepted merely by its name.
    return all(kind(nodes[e['from']]) == 'ConstantNode'
               and re.match(r'^i32(?:\s|$)', nodes[e['from']]['properties'].get('stamp', ''))
               and nodes[e['from']]['properties'].get('rawvalue') == '7' for e in tags)


def inspect_graph(graph, entry):
    nodes = {node['id']: node for node in graph['nodes']}
    require(len(nodes) == len(graph['nodes']), 'Duplicate graph node id')
    require(dict(Counter(node['nodeClass'] for node in nodes.values())) == graph['nodeClassCounts'],
            'Graph summary does not match detailed nodes')
    allocated_boxes, unboxes, arithmetic = [], [], []
    forbidden = ('NewArray', 'NewInstance', 'NewMultiArray', 'CommitAllocation', 'DynamicNew',
                 'Invoke', 'ForeignCall', 'LoadField', 'StoreField', 'StoreIndexed',
                 'ReadNode', 'WriteNode', 'UnsafeLoad', 'UnsafeStore', 'AtomicRead', 'CompareAndSwap')
    for node in nodes.values():
        name, props = kind(node), node['properties']
        require(not any(fragment in name for fragment in forbidden), 'Residual allocation/memory/call: ' + name)
        stamp = props.get('stamp', '')
        forbidden_reference = re.search(
            r'thc\.runtime\.(?:Word8X16|Int8X16)|(?:Byte|Short)(?:\d+)?Vector|(?:byte|short)\[\]|\[(?:B|S)(?:;|$)', str(stamp))
        require(not forbidden_reference or virtual_frame_tags(node, nodes, graph['edges']),
                'Residual carrier/vector/payload reference: ' + str(stamp))
        if 'UnboxNode' in name:
            require(props.get('boxingKind') == 'JavaKind.Long', 'Intermediate lane unbox')
            unboxes.append(node['id'])
        elif 'BoxNode' in name:
            require(stamp == 'a!# java.lang.Long', 'Intermediate lane box')
            if 'AllocatingBoxNode' in name:
                allocated_boxes.append(node['id'])
        if name == 'LoadIndexedNode':
            require(props.get('elementKind') == 'JavaKind.Object', 'Residual vector payload array load')
        if packed_stamp(stamp) and name == ENTRIES[entry]:
            arithmetic.append(node)
    require(len(allocated_boxes) == 1, 'Exactly one public Long result allocation is expected')
    require(len(unboxes) == 2, 'Exactly the two scalar host Long input unboxes are expected')
    # Verify this is result arithmetic, not an unrelated/dead packed instruction.
    current = {allocated_boxes[0]}
    while True:
        parents = {edge['from'] for edge in graph['edges']
                   if edge['to'] in current and edge['type'] == 'Value'}
        if parents <= current:
            break
        current |= parents
    require(all(node in current for node in unboxes), 'Both scalar host inputs must feed the public result')
    expansion = None
    if entry == 'timesCase':
        output, expansion = byte_multiply_expansion(nodes, graph['edges'], current)
    else:
        require(len(arithmetic) == 1, 'Expected exactly one live i8x16 ' + ENTRIES[entry])
        output = arithmetic[0]
    require(output['id'] in current, 'Packed arithmetic does not feed the public result')
    cuts = {edge['to'] for edge in graph['edges'] if edge['from'] == output['id']
            and edge['type'] == 'Value' and edge['to'] in current}
    observed = {nodes[node]['properties']['offset'] for node in cuts
                if kind(nodes[node]) == 'SimdCutNode' and nodes[node]['properties'].get('length') == 1}
    require(observed == set(range(16)), 'The public checksum must observe all sixteen packed output lanes')
    unsigned = []
    for number in sorted(cuts):
        cut = nodes[number]
        if kind(cut) != 'SimdCutNode' or cut['properties'].get('length') != 1:
            continue
        require(re.match(r'^i8(?:\s|$)', cut['properties'].get('stamp', '')),
                'Packed output cuts must preserve exactly eight bits')
        # ByteVector has signed byte carriers. Every Word8# observation must
        # preserve the bits and widen UNSIGNED, not feed a signed checksum.
        consumers = [e['to'] for e in graph['edges'] if e['from'] == number
                     and e['type'] == 'Value' and e['to'] in current]
        require(len(consumers) == 1, 'Each byte cut requires one unambiguous unsigned widening')
        widening = nodes[consumers[0]]
        props = widening['properties']
        require(kind(widening) == 'ZeroExtendNode' and props.get('inputBits') == 8
                and props.get('resultBits') == 64 and re.match(r'^i64(?:\s|$)', props.get('stamp', '')),
                'Word8 output must zero-extend exactly 8 to 64 bits')
        inputs = [e for e in graph['edges'] if e['to'] == widening['id'] and e['type'] == 'Value']
        require(len(inputs) == 1 and inputs[0]['from'] == number and inputs[0]['label'] == 'value',
                'Unsigned widening must consume the actual packed output cut')
        unsigned.append(dict(lane=cut['properties']['offset'], cut=number, zeroExtend=widening['id'],
                             inputBits=8, resultBits=64))
    require(len(unsigned) == 16, 'Exactly sixteen distinct unsigned lane observations required')
    returns = [node['id'] for node in nodes.values() if kind(node) == 'ReturnNode']
    require(len(returns) == 1 and any(edge['from'] == allocated_boxes[0]
            and edge['to'] == returns[0] and edge['type'] == 'Value' for edge in graph['edges']),
            'Allowed Long allocation is not the direct public return')
    return dict(id=output['id'], nodeClass=output['nodeClass'],
                stamp=output['properties']['stamp'], expansion=expansion,
                unsignedLaneExtensions=sorted(unsigned, key=lambda lane: lane['lane']))


def inspect_lir(text, target, entry, arch):
    require(arch in ('x86_64', 'amd64'), 'This bounded gate proves x86 XMM byte operations/word-product expansion only: ' + arch)
    # Graal can repeat metadata headers for the SAME compilation. Different
    # compilation IDs are still retries, even if their human root names match.
    roots = set(re.findall(r'  method "TruffleHotSpotCompilation-(\d+)\[(.*?)\]"', text))
    require(len(roots) == 1 and next(iter(roots))[1] == target, 'Unexpected compilation identities: ' + str(roots))
    starts = [match.end() for match in re.finditer(r'^  name "After FinalCodeAnalysisStage"$', text, re.M)]
    require(len(starts) == 1, 'Expected exactly one final allocated-register LIR')
    start = text.rfind('  name ', 0, starts[0])
    end = text.find('end_cfg', starts[0])
    require(end != -1, 'Truncated final LIR')
    lir = text[start:end + len('end_cfg')]
    opcode = OPCODES[entry]
    register_kind = 'WORD' if entry == 'timesCase' else 'BYTE'
    # Match instruction position, physical register, exact lane kind and XMM size.
    # A scalar instruction, YMM/ZMM operation, comment, or mnemonic substring cannot pass.
    operations = [line.strip() for line in lir.splitlines() if re.search(
        r'<\|@ instruction .* = ' + opcode + r'\b', line)]
    require(len(operations) == (2 if entry == 'timesCase' else 1), 'Wrong count of allocated XMM ' + opcode)
    operand = r'xmm\d+\|V128_' + register_kind
    for line in operations:
        match = re.fullmatch(r'nr\s+\d+\s+<\|@ instruction ' + operand + ' = ' + opcode
            + r' \(x: ' + operand + r', y: ' + operand
            + r'\) size: XMM <\|@(?: <\|@)?', line)
        require(match is not None, 'Wrong physical register, operand kind or packed width')
    require('VPMULLB' not in lir, 'x86 has no packed low-byte multiply instruction')
    # For timesCase, the exact connected graph establishes byte reconstruction;
    # two physical VPMULLW instructions establish the packed product lowering.
    return '\n'.join(line.rstrip() for line in lir.splitlines()) + '\n', operations


def prepare(root, out, java_home):
    require(not (out / 'runtime-snapshot.json').exists() and not list(out.glob('*/graphs/*')),
            'Use a fresh graph output directory; no retries in an existing capture')
    proof = json.loads((root / CORE / 'provenance.json').read_text())
    cases = validate_inputs(root, proof)
    write_json(out / 'input-provenance.json', dict(core=proof,
        originalProvenance=record(root / CORE / 'provenance.json'), cases=cases))
    write_json(out / 'runtime-snapshot.json', snapshot(root, java_home))
    (out / 'stages.txt').write_text('pre\npost\n')
    shutil.copyfile(root / CORE / 'oracle.tsv', out / 'oracle.tsv')


def verify_snapshot(initial, current, root, checker_fix=False):
    require(all(current[key] == initial[key] for key in ('runtimeJars', 'jdkFiles')),
            'Runtime JAR/JDK changed during capture')
    if not checker_fix:
        require(current['sources'] == initial['sources'], 'Runtime source/harness changed during capture')
        return None
    # Explicit offline recheck permits ONLY the diagnostic reader and its tests
    # to change. The guest runtime, capture probe and runner remain exact.
    allowed = {str(root / 'bench/experiments/word8x16-foundation' / name)
               for name in ('runtime-audit.py', 'test-runtime-audit.py')}
    before = [item for item in initial['sources'] if item['path'] not in allowed]
    after = [item for item in current['sources'] if item['path'] not in allowed]
    require(before == after, 'Non-checker source changed before offline recheck')
    old = [item for item in initial['sources'] if item['path'] in allowed]
    new = [item for item in current['sources'] if item['path'] in allowed]
    require({item['path'] for item in old} == {item['path'] for item in new} == allowed,
            'Checker source inventory changed')
    return dict(originalCheckerSources=old, correctedCheckerSources=new,
                checkerRevision=current['sourceRevision'], guestExecutionRepeated=False)


def check_capture(root, out, java_home, checker_fix=False):
    initial = json.loads((out / 'runtime-snapshot.json').read_text())
    current = snapshot(root, java_home)
    correction = verify_snapshot(initial, current, root, checker_fix)
    require(not (out / 'evidence.json').exists(), 'Do not overwrite completed capture evidence')
    if checker_fix:
        require((out / 'check.exit-status.txt').read_text().strip() not in ('', '0'),
                'Offline correction requires the retained original checker failure')
        correction['originalFailure'] = record(out / 'check.log')
        correction['originalExitStatus'] = record(out / 'check.exit-status.txt')
        for item in correction['originalCheckerSources']:
            archived = out / ('capture-' + Path(item['path']).name)
            require(digest(archived) == item['sha256'], 'Original checker source not retained')
    inputs = json.loads((out / 'input-provenance.json').read_text())
    original = inputs['originalProvenance']
    require(digest(Path(original['path'])) == original['sha256'], 'Core provenance changed during capture')
    require(inputs['cases'] == validate_inputs(root, inputs['core']), 'Declared input corpus changed')
    require((out / 'stages.txt').read_text() == 'pre\npost\n', 'Stage selection changed')
    require(digest(out / 'oracle.tsv') == digest(root / CORE / 'oracle.tsv'), 'Copied native oracle changed')
    require(digest(out / 'jdk-release.txt') == digest(java_home / 'release'), 'Copied JDK release changed')
    for step in ('prepare', 'javac-probe', 'javac-reader'):
        require((out / (step + '.exit-status.txt')).read_text() == '0\n', 'Failed setup: ' + step)
    arch = (out / 'architecture.txt').read_text().strip()
    results = []
    for stage in ('pre', 'post'):
        for backend in ('ast', 'bytecode'):
            for entry in ENTRIES:
                path = out / f'{stage}-{backend}-{entry}'
                for step in ('run', 'parse'):
                    require((path / (step + '.exit-status.txt')).read_text() == '0\n', 'Failed capture/parser: ' + str(path))
                rows = inputs['cases'][entry][1]
                lines = (path / 'run.log').read_text().splitlines()
                expected = f'PASS entry={entry} backend={backend} mode=inline oracleOrigin=native oracleRows={rows} arity=2 validAfterExecution=true'
                require([line for line in lines if line.startswith('PASS ')] == [expected], 'Missing exact execution proof: ' + str(path))
                targets = [json.loads(line.removeprefix('GRAPH_TARGET=')) for line in lines if line.startswith('GRAPH_TARGET=')]
                require(len(targets) == 1, 'Expected one compilation target marker')
                target = targets[0]
                require(target['entry'] == entry and target['backend'] == backend, 'Wrong compilation target')
                bgvs, cfgs = list((path / 'graphs').glob('*.bgv')), list((path / 'graphs').glob('*.cfg'))
                require(len(bgvs) == len(cfgs) == 1 and bgvs[0].stem == cfgs[0].stem, 'Expected one matching raw BGV/CFG')
                index = json.loads((path / 'parsed/index.json').read_text())
                require(Path(index['source']).resolve() == bgvs[0].resolve(), 'Parser read a different raw graph')
                graphs = [graph for graph in index['graphs'] if 'Before phase HighTierLowering' in graph['name']]
                require(len(graphs) == 1, 'Expected one pre-lowering graph')
                summary = graphs[0]
                require(summary['group'] == 'TruffleIR.Tier2.' + target['root'].replace(' ', '_') + '()', 'Graph target differs from executed entry')
                graph_path = path / 'parsed' / summary['file']
                graph = json.loads(graph_path.read_text())
                require(all(graph[key] == summary[key] for key in ('name', 'group', 'nodeClassCounts')), 'Parsed graph/index mismatch')
                arithmetic = inspect_graph(graph, entry)
                lir, instructions = inspect_lir(cfgs[0].read_text(), target['root'], entry, arch)
                (path / 'final-lir.txt').write_text(lir)
                results.append(dict(stage=stage, backend=backend, entry=entry, rows=rows,
                    compiledCorpusPasses=2, installedEntryValidAfterEveryInput=True, activeTargetCheckedAfterEveryInput=True,
                    packedArithmeticNode=arithmetic, physicalPackedInstructions=instructions,
                    temporaryVectorAllocations=False, vectorFieldTraffic=False, intermediateLaneBoxes=False,
                    hostLongResultBox=True, highTierGraph=summary, parsedGraph=record(graph_path),
                    rawGraph=record(bgvs[0]), rawLir=record(cfgs[0]), log=record(path / 'run.log'),
                    command=record(path / 'run.command.txt'), exitStatus=record(path / 'run.exit-status.txt'),
                    lir=record(path / 'final-lir.txt')))
    write_json(out / 'evidence.json', dict(schema=1, vector='word8x16', architecture=arch,
        results=results, **initial, checkerCorrection=correction, inputProvenance=record(out / 'input-provenance.json'),
        jdkRelease=record(out / 'jdk-release.txt'), javaVersion=record(out / 'java-version.txt'),
        claim='Native-backed real pre/post Core on AST and bytecode; packed byte add/sub and byte multiplication expanded through two i16x8 products in 128-bit XMM registers, all sixteen result lanes zero-extended to machine Int, with temporary carrier/vector/array allocations removed.',
        limitations=['The host Long result may allocate; this is not a globally allocation-free ABI.',
                     'Interpreted JDK ByteVector fallbacks allocate private byte[] payloads.',
                     'x86 byte multiplication is an exact mask/shift/OR packed-short expansion, not an i8 multiply instruction.',
                     'No vector function/formal/capture/join ABI, throughput, no-spill or non-x86 claim.',
                     'Compiled-entry counters are disabled; instrumented correctness tests must check their per-input deltas separately.']))
    print(f'Word8X16 actual-Core graph gate passed: {arch}, {len(results)} records')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('prepare', 'check'))
    parser.add_argument('root', type=Path)
    parser.add_argument('out', type=Path)
    parser.add_argument('java_home', type=Path)
    parser.add_argument('--recheck-after-checker-fix', action='store_true',
                        help='Offline only: retain failed original checker and verify all non-checker sources unchanged')
    args = parser.parse_args()
    require(args.mode == 'check' or not args.recheck_after_checker_fix, 'Recheck applies only to retained captures')
    if args.mode == 'prepare':
        prepare(args.root.resolve(), args.out.resolve(), args.java_home.resolve())
    else:
        check_capture(args.root.resolve(), args.out.resolve(), args.java_home.resolve(), args.recheck_after_checker_fix)


if __name__ == '__main__':
    main()
