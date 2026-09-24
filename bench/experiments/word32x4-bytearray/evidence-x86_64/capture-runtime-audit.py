#!/usr/bin/env python3
"""Strict native-backed Word32X4 caller ByteArray load/store gate. Synthetic tests are not evidence."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

ENTRIES = {'vectorIndexWorker': ('index', 16, 2), 'scalarIndexWorker': ('index', 4, 2),
           'vectorStoreGraph': ('store', 16, 7), 'scalarStoreGraph': ('store', 4, 7)}
CORE = Path('build/simd-word32x4-bytearray')


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
        require(all(str(value) == text for value, text in zip(values, fields[1:])), 'Noncanonical integer spelling')
        require(all(-(1 << 63) <= value < (1 << 63) for value in values),
                'Oracle integer outside signed Int64: ' + line)
        key = (fields[0], *values[:-1])
        require(key not in rows, 'Duplicate oracle input: ' + str(key))
        rows[key] = values[-1]
    wanted = {(name, *case) for name, entry in entries.items() for case in entry['cases']}
    require(set(rows) == wanted, 'Oracle inputs differ from the declared corpus')
    return rows


def validate_inputs(root, proof):
    require(proof['vector'] == 'word32x4-bytearray' and proof['positiveAuditsAccepted'] is True,
            'Exact accepted unsigned Word32X4 ByteArray provenance required')
    require(proof['stages'] == ['pre', 'post'], 'Both native pre/post Core stages are required')
    require(type(proof['nativeRows']) is int and proof['nativeRows'] > 0
            and type(proof['modelRows']) is int
            and proof['modelMatched'] is True, 'Native oracle required; model-only input is not evidence')
    require(proof['toolchain']['ghcVersion'] == '9.14.1'
            and re.fullmatch(r'[0-9a-f]{64}', proof['toolchain']['ghcBinarySha256'])
            and proof['commands'], 'Missing pinned native toolchain/command provenance')
    require(proof['nativeByteOrder'] == proof['toolchain']['byteOrder'] == 'little'
            and proof['toolchain']['architecture'] in ('x86_64', 'amd64'),
            'This native byte corpus is specifically little-endian x86-64')
    verify_records(root, proof['sources'] + proof['artifacts'])
    artifacts = {item['path'] for item in proof['artifacts']}
    needed = {str(CORE / name) for name in (
        'oracle.tsv', 'expected.tsv', 'native/word32x4-bytearray-oracle',
        'pre-core/SimdWord32X4ByteArray.json', 'post-core/SimdWord32X4ByteArray.json')}
    require(needed <= artifacts, 'Native/Core/model artifact hash missing')
    entries = {entry['name']: entry for entry in proof['entries']}
    require(len(entries) == len(proof['entries']), 'Duplicate entry declaration')
    for name, entry in entries.items():
        require(type(entry['arity']) is int and entry['arity'] > 0 and entry['cases']
                and all(isinstance(case, list) and len(case) == entry['arity']
                        and all(type(value) is int for value in case) for case in entry['cases']),
                'Empty or malformed declared corpus: ' + name)
        require(len(entry['cases']) == len({tuple(case) for case in entry['cases']}),
                'Duplicate declared input: ' + name)
    rows = parse_oracle((root / CORE / 'oracle.tsv').read_text(), entries)
    model = parse_oracle((root / CORE / 'expected.tsv').read_text(), entries)
    require(rows == model and len(rows) == proof['nativeRows'] == proof['modelRows'] == 9666,
            'Native/model rows or counts differ')
    graph_entries = proof['graphEntries']
    require(len(graph_entries) == len(ENTRIES) and {e['name'] for e in graph_entries} == set(ENTRIES),
            'Exactly four selected memory roots required')
    for entry in graph_entries:
        name = entry['name']
        validate_graph_cases(entry, rows)
        count = proof['expectedGraphGuestCallsByEntry'][name]
        require(type(count) is int and count == 1, 'Graph root guest count changed')
        for stage in proof['stages']:
            report = proof['audits'][stage][name]
            checked = proof['checkedGraphGuestCallsByStage'][stage+'/'+name]
            require(report['accepted'] is True and not report['issues'] and not report['missingGlobals']
                    and len(report['reachableBindings']) == 1
                    and type(checked) is int and checked == 1,
                    'Root strict audit/one-guest proof rejected: ' + stage+'/'+name)
    return {e['name']: [e['arity'], len(e['cases'])] for e in graph_entries}


def validate_graph_cases(entry, native):
    """Independent integer/byte reconstruction, without importing the fixture model."""
    name = entry['name']
    require(name in ENTRIES, 'Unknown selected root')
    operation, scale, arity = ENTRIES[name]
    require(type(entry['offsetUnitBytes']) is int and type(entry['arity']) is int
            and (entry['operation'], entry['offsetUnitBytes'], entry['arity']) == (operation, scale, arity),
            'Graph operation, offset unit or arity changed')
    require(len(entry['cases']) == 36, 'Exactly 36 rotated boundary patterns required')
    boundary_order = (0, 1, 65535, 65536, 0x7fffffff, 0x80000000, 0x80000001, 0xfffffffe, 0xffffffff)
    boundaries = set(boundary_order)
    wanted = set()
    for lane in range(4):
        for value in boundary_order:
            lanes = [0x81234567, 0x92345678, 0xa3456789, 0xb456789a]
            lanes[lane] = value
            wanted.add((len(wanted) % (48 // scale+1), *lanes))
    seen, offsets, covered = set(), set(), [set() for _ in range(4)]
    for case in entry['cases']:
        offset, lanes = case['offset'], case['lanes']
        require(all(isinstance(case[key], list) and len(case[key]) == 64
                    and all(type(v) is int and 0 <= v <= 255 for v in case[key])
                    for key in ('initialBytes', 'expectedBytes')) and type(case['expectedScalar']) is int,
                'Noncanonical byte/checksum fields')
        require(type(offset) is int and 0 <= offset <= 48 // scale and isinstance(lanes, list) and len(lanes) == 4
                and all(type(v) is int and 0 <= v < (1 << 32) for v in lanes),
                'Invalid offset or canonical unsigned lane')
        key = (offset, *lanes)
        require(key not in seen, 'Duplicate graph input'); seen.add(key); offsets.add(offset)
        for i, value in enumerate(lanes):
            if value in boundaries: covered[i].add(value)
        initial = list(b''.join((0x89abcdef+i*0x01030507).to_bytes(4, 'little') for i in range(16)))
        expected = list(initial)
        address = offset * scale
        for lane, value in enumerate(lanes):
            expected[address+4*lane:address+4*lane+4] = (value & 0xffffffff).to_bytes(4, 'little')
        score = sum(v*w for v, w in zip(lanes, (3, 5, 7, 11)))
        require(case['initialBytes'] == (expected if operation == 'index' else initial)
                and case['expectedBytes'] == expected and case['expectedScalar'] == score,
                'Graph byte/checksum model mismatch')
        native_name = ('vector' if scale == 16 else 'scalar') + ('IndexCase' if operation == 'index' else 'StoreCase')
        require(case['nativeEntry'] == native_name and isinstance(case['nativeArguments'], list)
                and all(type(value) is int for value in case['nativeArguments'])
                and case['nativeArguments'] == list(key),
                'Graph/native wrapper mapping changed')
        if operation == 'index':
            require(native[(native_name, *key)] == score, 'Native index checksum mismatch')
        else:
            require(all(native[(native_name, *key, byte)] == score*257+value
                        for byte, value in enumerate(expected)), 'Native store byte witness mismatch')
    require(offsets == set(range(48 // scale+1)) and all(values == boundaries for values in covered),
            'Missing safe offset or unsigned boundary in a result lane')
    require(seen == wanted, 'Graph inputs differ from the exact rotated unsigned boundary/sentinel corpus')


def snapshot(root, java_home):
    sources = [root / 'build.gradle.kts', root / 'tools/GraphInspect.java']
    sources += [path for path in sorted((root / 'src/main').rglob('*')) if path.is_file()]
    sources += [path for path in sorted((root / 'bench/experiments/word32x4-bytearray').iterdir())
                if path.suffix in ('.py', '.sh', '.java')]
    jars = sorted((root / 'build/install/thc/lib').glob('*.jar'))
    require(jars, 'Build installDist before capture')
    return dict(sourceRevision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
                sources=[record(path) for path in sources], runtimeJars=[record(path) for path in jars],
                jdkFiles=[record(java_home / name) for name in ('release', 'bin/java', 'bin/javac', 'lib/modules')])


def kind(node):
    return node['nodeClass'].rsplit('.', 1)[-1]


def packed_stamp(stamp, bits=32, length=4):
    if not isinstance(stamp, str) or not stamp.startswith('<') or not stamp.endswith('>'):
        return False
    lanes = stamp[1:-1].split(',')
    return len(lanes) == length and all(re.match(r'^i' + str(bits) + r'(?:\s|$)', lane) for lane in lanes)


def virtual_frame_tags(node, nodes, edges):
    """Exact eliminated frame-tag storage, including multiple deopt snapshots.

    ObjectState.createEscapeObjectState replaces default-valued entries with null:
    an absent byte entry is ZERO (Object tag), not unknown payload data.
    """
    frame_type = 'com.oracle.truffle.api.impl.FrameWithoutBoxing'
    fields = [frame_type+'.'+name for name in (
        'descriptor', 'arguments', 'indexedLocals', 'indexedPrimitiveLocals', 'indexedTags', 'auxiliarySlots')]
    props = node['properties']; length = props.get('length')
    if (kind(node) != 'VirtualArrayNode' or props.get('stamp') != 'a!# byte[]'
            or props.get('componentType') != 'byte' or type(length) is not int or length <= 0):
        return False

    def incoming(number): return [e for e in edges if e['to'] == number]
    def outgoing(number): return [e for e in edges if e['from'] == number]
    def object_of(state):
        values = [e['from'] for e in incoming(state) if e['type'] == 'Value' and e['label'] == 'object']
        return values[0] if len(values) == 1 else None
    def entries_of(state, count):
        values = [e for e in incoming(state) if e['type'] == 'Value' and e['label'] == 'values']
        if (len(incoming(state)) != len(values)+1 or any(type(e.get('listIndex')) is not int
                or not 0 <= e['listIndex'] < count for e in values)
                or len({e['listIndex'] for e in values}) != len(values)):
            return None
        return {e['listIndex']: nodes[e['from']] for e in values}
    def frame_users(state):
        uses = outgoing(state)
        if not uses or any(e['type'] != 'State' or e['label'] != 'virtualObjectMappings'
                           or kind(nodes[e['to']]) != 'FrameState' for e in uses):
            return None
        return {e['to'] for e in uses}

    uses = outgoing(node['id'])
    if incoming(node['id']) or not uses or any(e['type'] != 'Value'
            or kind(nodes[e['to']]) != 'VirtualObjectState' for e in uses):
        return False
    own = [e['to'] for e in uses if e['label'] == 'object']
    owners = [e['to'] for e in uses if e['label'] == 'values' and e.get('listIndex') == 4]
    if not own or len(own) != len(set(own)) or len(owners) != 1 or len(uses) != len(own)+1:
        return False
    owner_state = owners[0]; owner = object_of(owner_state)
    if owner not in nodes: return False
    owner_node = nodes[owner]
    if (incoming(owner) or kind(owner_node) != 'VirtualInstanceNode'
            or owner_node['properties'].get('type') != frame_type or owner_node['properties'].get('fields') != fields):
        return False
    owner_frames = frame_users(owner_state)
    values = entries_of(owner_state, 6)
    if not owner_frames or values is None or set(values) != set(range(6)) or values[4]['id'] != node['id']:
        return False
    for use in outgoing(owner):
        if use['type'] != 'Value' or not (
                (use['to'] == owner_state and use['label'] == 'object')
                or (kind(nodes[use['to']]) == 'FrameState' and use['label'] == 'values')):
            return False
    # Every state chain is reconstruction-only, never a value/return/call input.
    frames = owner_frames | {e['to'] for e in outgoing(owner) if kind(nodes[e['to']]) == 'FrameState'}
    pending = list(frames)
    while pending:
        for use in outgoing(pending.pop()):
            if use['type'] != 'State': return False
            if kind(nodes[use['to']]) == 'FrameState' and use['to'] not in frames:
                frames.add(use['to']); pending.append(use['to'])

    snapshots = {}
    for state in own:
        fs = frame_users(state); tags = entries_of(state, length)
        if object_of(state) != node['id'] or not fs or tags is None or not fs <= owner_frames:
            return False
        for value in tags.values():
            p = value['properties']
            if (kind(value) != 'ConstantNode' or not re.match(r'^i32(?:\s|$)', p.get('stamp', ''))
                    or p.get('rawvalue') not in ('0', '1', '7')):
                return False
        for frame in fs:
            if frame in snapshots: return False
            snapshots[frame] = tags
    if set(snapshots) != owner_frames: return False

    siblings = {}
    for index, component in ((2, 'java.lang.Object'), (3, 'long')):
        sibling = values[index]; ident = sibling['id']; state_by_frame = {}
        if (kind(sibling) != 'VirtualArrayNode' or incoming(ident)
                or sibling['properties'].get('componentType') != component
                or sibling['properties'].get('length') != length):
            return False
        for use in outgoing(ident):
            if use['type'] != 'Value': return False
            if use['to'] == owner_state and use['label'] == 'values' and use.get('listIndex') == index: continue
            state = use['to']
            if use['label'] != 'object' or kind(nodes[state]) != 'VirtualObjectState' or object_of(state) != ident:
                return False
            fs = frame_users(state); contents = entries_of(state, length)
            if not fs or not fs <= owner_frames or contents is None: return False
            for frame in fs:
                if frame in state_by_frame: return False
                state_by_frame[frame] = contents
        if set(state_by_frame) != owner_frames: return False
        siblings[index] = state_by_frame
    for frame, tags in snapshots.items():
        objects, primitive = siblings[2][frame], siblings[3][frame]
        for index in range(length):
            tag = int(tags[index]['properties']['rawvalue']) if index in tags else 0
            if tag in (0, 7) and index in primitive: return False
            if tag in (1, 7) and index in objects: return False
            if index in primitive and not re.match(r'^i64(?:\s|$)', primitive[index]['properties'].get('stamp', '')):
                return False
            if index in objects and not objects[index]['properties'].get('stamp', '').startswith('a'):
                return False
    return True


def interpreter_array_metadata(node, nodes, edges, operation):
    """Only exact interpreter-local constant arrays used by deopt FrameStates."""
    p = node['properties']
    if kind(node) != 'ConstantNode' or p.get('stamp') not in ('a!# byte[]', 'a!# int[]'):
        return False
    if any(e['to'] == node['id'] for e in edges): return False
    uses = [e for e in edges if e['from'] == node['id']]
    if not uses or any(e['type'] != 'Value' or e['label'] != 'values'
                       or kind(nodes[e['to']]) != 'FrameState' for e in uses):
        return False
    frame_type = 'com.oracle.truffle.api.impl.FrameWithoutBoxing'
    cached = 'thc.runtime.BytecodeRootGen$CachedBytecodeNode'
    handler = 'Index' if operation == 'index' else 'Write'
    if p['stamp'] == 'a!# int[]':
        expected = {'thc.runtime.VectorWord32Unpack.executeTuple(Lcom/oracle/truffle/api/frame/VirtualFrame;, [I, I)': (2, 1)}
        if operation != 'index': return False
    else:
        expected = {
            cached+'.handle'+handler+'VectorWord32Array$'+handler+'_(Lcom/oracle/truffle/api/impl/FrameWithoutBoxing;, [B, J, J)': (2, 1),
            cached+'.continueAt(Lthc/runtime/BytecodeRootGen;, Lcom/oracle/truffle/api/impl/FrameWithoutBoxing;, J)': (7, 6)}
    observed, identities, receivers = set(), set(), set()
    for use in uses:
        state = nodes[use['to']]; code = state['properties'].get('code')
        if code not in expected or code in observed or use.get('listIndex') != expected[code][0]: return False
        observed.add(code)
        values = [e for e in edges if e['to'] == state['id'] and e['type'] == 'Value' and e['label'] == 'values']
        slots = {e.get('listIndex'): e['from'] for e in values}
        if len(slots) != len(values): return False
        frame = nodes.get(slots.get(expected[code][1]))
        if (frame is None or kind(frame) != 'VirtualInstanceNode'
                or frame['properties'].get('type') != frame_type): return False
        identities.add(frame['id'])
        if p['stamp'] == 'a!# byte[]':
            receiver = nodes.get(slots.get(0))
            if receiver is None or kind(receiver) != 'ConstantNode' or receiver['properties'].get('stamp') != 'a!# '+cached:
                return False
            receivers.add(receiver['id'])
        else:
            zero = nodes.get(slots.get(3))
            if zero is None or kind(zero) != 'ConstantNode' or zero['properties'].get('rawvalue') != '0':
                return False
        pending, seen = [state['id']], set()
        while pending:
            current = pending.pop()
            if current in seen: continue
            seen.add(current)
            for e in edges:
                if e['from'] != current: continue
                if e['type'] != 'State': return False
                if kind(nodes[e['to']]) == 'FrameState': pending.append(e['to'])
    if observed != set(expected) or len(identities) != 1 or len(receivers) > 1: return False
    owner = next(iter(identities))
    owner_states = {e['to'] for e in edges if e['from'] == owner and e['type'] == 'Value' and e['label'] == 'object'
                    and kind(nodes[e['to']]) == 'VirtualObjectState'}
    tags = [nodes[e['from']] for e in edges if e['to'] in owner_states and e['type'] == 'Value'
            and e['label'] == 'values' and e.get('listIndex') == 4]
    return len(tags) == 1 and virtual_frame_tags(tags[0], nodes, edges)


def inspect_graph(graph, entry):
    operation, scale, arity = ENTRIES[entry]
    # JSON numeric equivalence (4.0 == 4, True == 1) is not exact graph proof.
    require(all(type(node['id']) is int and all(type(node['properties'][key]) is int
                for key in ('offset', 'length', 'index', 'inputBits', 'resultBits') if key in node['properties'])
                for node in graph['nodes']), 'Noncanonical graph integer property')
    require(all(type(edge[key]) is int for edge in graph['edges'] for key in ('from', 'to', 'listIndex')),
            'Noncanonical graph edge integer')
    nodes = {node['id']: node for node in graph['nodes']}
    edges = graph['edges']
    require(len(nodes) == len(graph['nodes']), 'Duplicate graph node id')
    require(all(e['from'] in nodes and e['to'] in nodes for e in edges), 'Dangling graph edge')
    require(dict(Counter(n['nodeClass'] for n in nodes.values())) == graph['nodeClassCounts'],
            'Graph summary does not match detailed nodes')
    # A genuine compiled failure path is still compiled allocation/call traffic.
    # Report it before any metadata recognizer; no deopt exemption can hide it.
    forbidden = ('NewArray', 'NewInstance', 'NewMultiArray', 'CommitAllocation', 'AllocatedObject',
                 'MaterializedObject', 'DynamicNew', 'Invoke', 'ForeignCall', 'LoadField', 'StoreField',
                 'StoreIndexed', 'UnsafeLoad', 'UnsafeStore', 'AtomicRead', 'CompareAndSwap')
    for n in nodes.values():
        require(not any(part in kind(n) for part in forbidden), 'Residual allocation/payload/call: ' + kind(n))

    def inputs(number, label=None, typ='Value'):
        return [e['from'] for e in edges if e['to'] == number and e['type'] == typ
                and (label is None or e['label'] == label)]

    def one(number, label, typ='Value'):
        values = inputs(number, label, typ)
        require(len(values) == 1, 'Expected one ' + label + ' input at ' + str(number))
        return values[0]

    def ancestors(number):
        result = {number}
        while True:
            parents = {e['from'] for e in edges if e['to'] in result and e['type'] == 'Value'}
            if parents <= result: return result
            result |= parents

    def strip_object(number):
        seen = set()
        while kind(nodes[number]) in ('PiNode', 'PiArrayNode', 'BoxNode$TrustedBoxedValue'):
            require(number not in seen, 'Cyclic object lineage'); seen.add(number)
            label = 'value' if kind(nodes[number]) == 'BoxNode$TrustedBoxedValue' else 'object'
            number = one(number, label)
        return number

    def host_slot(number):
        """Only indexing into the original profiled guest Object[] ABI."""
        number = strip_object(number)
        require(kind(nodes[number]) == 'LoadIndexedNode'
                and nodes[number]['properties'].get('elementKind') == 'JavaKind.Object',
                'Expected caller Object[] argument origin')
        array = strip_object(one(number, 'array'))
        require(kind(nodes[array]) == 'ParameterNode' and nodes[array]['properties'].get('index') == 1
                and nodes[array]['properties'].get('stamp') == 'a java.lang.Object[]',
                'Argument origin is not the profiled guest array')
        index = nodes[one(number, 'index')]
        require(kind(index) == 'ConstantNode' and re.fullmatch(r'[0-9]+', index['properties'].get('rawvalue', '')),
                'Dynamic host argument slot')
        return int(index['properties']['rawvalue'])

    memory = [n for n in nodes.values() if kind(n) in ('ReadNode', 'WriteNode', 'FloatingReadNode')]
    wanted = 'ReadNode' if operation == 'index' else 'WriteNode'
    require(len(memory) == 1 and kind(memory[0]) == wanted, 'Exactly one fixed caller-array ' + wanted + ' required')
    access = memory[0]
    props = access['properties']
    require(props.get('locationIdentity', props.get('location')) == 'Array: byte'
            and props.get('memoryOrder') in ('PLAIN', 'MemoryOrderMode.PLAIN')
            and props.get('barrierType') in ('NONE', 'BarrierType.NONE'),
            'Only plain non-reference byte-array memory is allowed')
    address = one(access['id'], 'address', 'Association')
    require(kind(nodes[address]) == 'OffsetAddressNode', 'Expected managed array offset address')
    base, offset = one(address, 'base'), one(address, 'offset')
    require(host_slot(base) == 1, 'Memory base must be caller argument zero')
    require(nodes[base]['properties'].get('stamp') in ('a! byte[]', 'a!# byte[]'),
            'Memory base needs exact non-null byte[] proof')
    base_origin = strip_object(base)
    allowed_bytes = set()
    interpreter_arrays = {n['id'] for n in nodes.values() if interpreter_array_metadata(n, nodes, edges, operation)}
    for n in nodes.values():
        if n['properties'].get('stamp') in ('a byte[]', 'a! byte[]', 'a!# byte[]'):
            if kind(n) in ('PiNode', 'PiArrayNode') and strip_object(n['id']) == base_origin:
                allowed_bytes.add(n['id'])
            elif virtual_frame_tags(n, nodes, edges):
                allowed_bytes.add(n['id'])

    allocated_boxes, unboxes = [], {}
    for n in nodes.values():
        name, p = kind(n), n['properties']
        stamp = str(p.get('stamp', ''))
        reference = re.search(r'thc\.runtime\.(?:Word32X4|Int32X4)|(?:Byte|Short|Int)(?:\d+)?Vector|(?:byte|short|int)\[\]|\[(?:B|S|I)(?:;|$)', stamp)
        require(not reference or n['id'] in allowed_bytes or n['id'] in interpreter_arrays,
                'Residual private carrier/vector/payload reference: ' + stamp)
        if 'UnboxNode' in name:
            require(p.get('boxingKind') == 'JavaKind.Long', 'Intermediate lane unbox')
            slot = host_slot(one(n['id'], 'value'))
            require(slot not in unboxes, 'Duplicate host scalar unbox')
            unboxes[slot] = n['id']
        elif 'BoxNode' in name:
            require(stamp == 'a!# java.lang.Long', 'Intermediate lane box')
            if 'AllocatingBoxNode' in name: allocated_boxes.append(n['id'])
        if name == 'LoadIndexedNode':
            require(p.get('elementKind') == 'JavaKind.Object', 'Scalar payload load instead of packed memory')
            require(0 <= host_slot(n['id']) <= arity, 'Unrelated Object[] payload traffic')
        if name == 'ArrayLengthNode':
            array = one(n['id'], 'array')
            require(strip_object(array) == base_origin or kind(nodes[strip_object(array)]) == 'ParameterNode',
                    'Private payload array length')
    bloom_unbox = None
    if operation == 'store' and 0 in unboxes:
        # FunctionRoot.execute stores (host bloom header | root mask) into
        # FrameLayout.BLOOM_FILTER=0. Retain only that exact deopt-only use;
        # this is not an extra scalar/lane-input exception.
        bloom_unbox = unboxes[0]
        users = [e for e in edges if e['from'] == bloom_unbox and e['type'] == 'Value']
        require(len(users) == 1 and users[0]['label'] == 'x' and kind(nodes[users[0]['to']]) == 'OrNode',
                'Bloom header requires one exact mask OR')
        merged = users[0]['to']; mask = nodes[one(merged, 'y')]
        require(inputs(merged, 'x') == [bloom_unbox] and len(inputs(merged)) == 2
                and re.match(r'^i64(?:\s|$)', nodes[merged]['properties'].get('stamp', ''))
                and kind(mask) == 'ConstantNode' and re.match(r'^i64(?:\s|$)', mask['properties'].get('stamp', ''))
                and re.fullmatch(r'-?[0-9]+', mask['properties'].get('rawvalue', ''))
                and -(1 << 63) <= int(mask['properties']['rawvalue']) < (1 << 63)
                and int(mask['properties']['rawvalue']) != 0, 'Invalid constant bloom mask')
        uses = [e for e in edges if e['from'] == merged]
        require(uses, 'Unused bloom bookkeeping is not evidence')
        for use in uses:
            state = use['to']
            require(use['type'] == 'Value' and use['label'] == 'values' and use.get('listIndex') == 0
                    and kind(nodes[state]) == 'VirtualObjectState', 'Bloom header escapes frame primitive slot zero')
            primitive = one(state, 'object')
            require(kind(nodes[primitive]) == 'VirtualArrayNode'
                    and nodes[primitive]['properties'].get('componentType') == 'long', 'Bloom storage must be frame long[]')
            owners = [e['to'] for e in edges if e['from'] == primitive and e['type'] == 'Value'
                      and e['label'] == 'values' and e.get('listIndex') == 3
                      and kind(nodes[e['to']]) == 'VirtualObjectState']
            require(len(owners) == 1, 'Bloom storage requires one exact frame owner')
            tags = [e['from'] for e in edges if e['to'] == owners[0] and e['type'] == 'Value'
                    and e['label'] == 'values' and e.get('listIndex') == 4]
            require(len(tags) == 1 and virtual_frame_tags(nodes[tags[0]], nodes, edges),
                    'Bloom storage requires fully validated frame metadata')
    expected_unboxes = {2} if operation == 'index' else set(range(2, 7))
    if bloom_unbox is not None: expected_unboxes.add(0)
    require(set(unboxes) == expected_unboxes,
            'Expected exact offset/lane host Long unboxes')
    require(unboxes[2] in ancestors(offset), 'Dynamic caller offset must reach the memory address')
    # Native byte checks establish the offset scale; the graph independently
    # forbids a constant/folded-away address.
    returns = [n['id'] for n in nodes.values() if kind(n) == 'ReturnNode']
    require(len(returns) == 1, 'Expected one public return')
    returned = one(returns[0], 'result')
    def fixed_reachable(start, excluded=None):
        reachable = {start}
        while True:
            successors = {e['to'] for e in edges if e['from'] in reachable and e['type'] == 'Successor'
                          and e['to'] != excluded}
            if successors <= reachable: return reachable
            reachable |= successors
    starts = [n['id'] for n in nodes.values() if kind(n) == 'StartNode']
    require(len(starts) == 1 and access['id'] in fixed_reachable(starts[0])
            and returns[0] in fixed_reachable(access['id'])
            and returns[0] not in fixed_reachable(starts[0], access['id']),
            'Packed memory access must dominate the live public return')
    unsigned, packed_lanes = [], []
    if operation == 'index':
        require(len(allocated_boxes) == 1 and returned == allocated_boxes[0], 'Expected one direct public Long result box')
        live = ancestors(returned)
        require(access['id'] in live, 'Packed load does not feed public result')
        candidates = []
        for n in nodes.values():
            if n['id'] not in live or not packed_stamp(n['properties'].get('stamp')): continue
            current, seen = n, set()
            while kind(current) == 'ReinterpretNode':
                require(current['id'] not in seen, 'Cyclic reinterpret'); seen.add(current['id'])
                require(packed_stamp(current['properties'].get('stamp'))
                        or packed_stamp(current['properties'].get('stamp'), 8, 16), 'Non-128-bit reinterpret')
                current = nodes[one(current['id'], 'value')]
            if current['id'] == access['id']: candidates.append(n)
        require(len(candidates) == 1, 'Expected one exact i32x4 view of the live packed load')
        output = candidates[0]
        require(packed_stamp(props.get('stamp')) or packed_stamp(props.get('stamp'), 8, 16),
                'Packed load must read exactly sixteen bytes')
        cuts = [n for n in nodes.values() if kind(n) == 'SimdCutNode' and n['id'] in live
                and inputs(n['id']) == [output['id']]]
        require(len(cuts) == 4 and {n['properties'].get('offset') for n in cuts} == set(range(4)),
                'All four loaded lanes must reach the checksum')
        for cut in cuts:
            p = cut['properties']
            require(p.get('length') == 1 and re.match(r'^i32(?:\s|$)', p.get('stamp', '')), 'Wrong loaded lane width')
            uses = [e['to'] for e in edges if e['from'] == cut['id'] and e['type'] == 'Value' and e['to'] in live]
            require(len(uses) == 1, 'One exact unsigned widening per loaded lane required')
            wide = nodes[uses[0]]; p = wide['properties']
            require(kind(wide) == 'ZeroExtendNode' and p.get('inputBits') == 32 and p.get('resultBits') == 64
                    and inputs(wide['id']) == [cut['id']] and re.match(r'^i64(?:\s|$)', p.get('stamp', '')),
                    'Loaded Word32 lane must zero-extend 32 to 64 bits')
            unsigned.append(dict(lane=cut['properties']['offset'], cut=cut['id'], zeroExtend=wide['id']))
    else:
        require(not allocated_boxes and strip_object(returned) == base_origin, 'Store must return identical caller array without boxing')
        payload = one(access['id'], 'value')
        require(packed_stamp(nodes[payload]['properties'].get('stamp'))
                or packed_stamp(nodes[payload]['properties'].get('stamp'), 8, 16), 'Store must write exactly sixteen bytes')
        seen = set()
        while kind(nodes[payload]) == 'ReinterpretNode':
            require(payload not in seen, 'Cyclic store reinterpret'); seen.add(payload)
            require(packed_stamp(nodes[payload]['properties'].get('stamp'))
                    or packed_stamp(nodes[payload]['properties'].get('stamp'), 8, 16), 'Non-128-bit store reinterpret')
            payload = one(payload, 'value')
        require(packed_stamp(nodes[payload]['properties'].get('stamp')), 'Store requires four-i32 packed source')
        lane_inputs = {}
        for lane in (3, 2, 1):
            n = nodes[payload]
            require(kind(n) == 'SimdInsertNode' and n['properties'].get('offset') == lane
                    and packed_stamp(n['properties'].get('stamp')), 'Wrong packed store lane construction')
            lane_inputs[lane] = one(payload, 'y')
            payload = one(payload, 'x')
        require(kind(nodes[payload]) == 'SimdBroadcastNode' and nodes[payload]['properties'].get('length') == 4
                and packed_stamp(nodes[payload]['properties'].get('stamp')), 'Wrong store broadcast')
        lane_inputs[0] = one(payload, 'value')
        for lane in range(4):
            n = nodes[lane_inputs[lane]]; p = n['properties']
            require(kind(n) == 'NarrowNode' and p.get('inputBits') == 64 and p.get('resultBits') == 32
                    and one(n['id'], 'value') == unboxes[lane+3], 'Store lane must use exact low32 host argument')
            packed_lanes.append(dict(lane=lane, narrow=n['id'], hostSlot=lane+3, unbox=unboxes[lane+3]))
    return dict(id=access['id'], nodeClass=access['nodeClass'], stamp=props.get('stamp'),
                backingArray=base, backingArgumentSlot=1, offset=offset, offsetUnitBytes=scale,
                unsignedLaneExtensions=sorted(unsigned, key=lambda x: x['lane']), packedStoreLanes=packed_lanes,
                interpreterArrayMetadata=sorted(interpreter_arrays),
                bloomBookkeepingUnbox=bloom_unbox,
                frameTagMetadata=[n['id'] for n in nodes.values() if kind(n) == 'VirtualArrayNode'
                                  and virtual_frame_tags(n, nodes, edges)])


def inspect_lir(text, target, entry, arch):
    require(arch in ('x86_64', 'amd64'), 'This gate proves only x86 packed XMM memory access')
    roots = set(re.findall(r'  method "TruffleHotSpotCompilation-(\d+)\[(.*?)\]"', text))
    require(len(roots) == 1 and next(iter(roots))[1] == target, 'Unexpected compilation identities')
    starts = [m.end() for m in re.finditer(r'^  name "After FinalCodeAnalysisStage"$', text, re.M)]
    require(len(starts) == 1, 'Expected exactly one final allocated-register LIR')
    start = text.rfind('  name ', 0, starts[0]); end = text.find('end_cfg', starts[0])
    require(end != -1, 'Truncated final LIR')
    lir = text[start:end+len('end_cfg')]
    opcode = 'VECTORLOAD' if ENTRIES[entry][0] == 'index' else 'VECTORSTORE'
    operations = [line.strip() for line in lir.splitlines()
                  if re.search(r'<\|@ instruction .*\bVECTOR(?:LOAD|STORE)\b', line)]
    require(len(operations) == 1 and re.search(r'\b'+opcode+r'\b', operations[0]), 'Exactly one packed memory instruction required')
    line = operations[0]
    # VectorMemOp has no @Opcode: the op DATA field identifies the instruction
    # emitted by AMD64VectorMove. Parse the full line, never a mnemonic comment.
    xmm = r'xmm\d+\|V128_(?:BYTE|DWORD)'
    register = r'(?:rax|rbx|rcx|rdx|rsi|rdi|r(?:8|9|1[0-5]))'
    gpr = register+r'\|QWORD(?:\[\.\])?'
    # LIRKind '_' denotes a compressed oop. Pinned HotSpot address lowering
    # folds its uncompression into the SIB index (shift3 => scale8).
    index = r'(?:'+gpr+r' \* (?:1|2|4|8)|'+register+r'\|DWORD\[_\] \* 8)'
    address = r'\['+gpr+r'(?: \+ '+index+r')?(?: [+-] [0-9]+)?\]'
    if opcode == 'VECTORLOAD':
        body = xmm+r' = VECTORLOAD (?:address: )?'+address
    else:
        body = r'VECTORSTORE \((?:input: '+xmm+r', address: '+address+r'|address: '+address+r', input: '+xmm+r')\)'
    data = r'(?: size: XMM op: VMOVDQU(?:32)?| op: VMOVDQU(?:32)? size: XMM)'
    state = r'(?: state \[bci:-?[0-9]+(?:, -?[0-9]+)*\])?'
    require(re.fullmatch(r'nr\s+\d+\s+<\|@ instruction '+body+data+state+r' <\|@(?: <\|@)?', line),
            'Wrong memory opcode, physical register, heap address or packed width')
    return '\n'.join(line.rstrip() for line in lir.splitlines())+'\n', operations


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
    write_json(out / 'graph-cases.json', proof['graphEntries'])
    for name in ('runtime-audit.py', 'test-runtime-audit.py'):
        shutil.copyfile(root / 'bench/experiments/word32x4-bytearray' / name, out / ('capture-'+name))


def verify_snapshot(initial, current, root, checker_fix=False):
    require(all(current[key] == initial[key] for key in ('runtimeJars', 'jdkFiles')),
            'Runtime JAR/JDK changed during capture')
    if not checker_fix:
        require(current['sources'] == initial['sources'], 'Runtime source/harness changed during capture')
        return None
    # Explicit offline recheck permits ONLY the diagnostic reader and its tests
    # to change. The guest runtime, capture probe and runner remain exact.
    allowed = {str(root / 'bench/experiments/word32x4-bytearray' / name)
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
    require(json.loads((out / 'graph-cases.json').read_text()) == inputs['core']['graphEntries'], 'Copied graph cases changed')
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
                arity, rows = inputs['cases'][entry]
                lines = (path / 'run.log').read_text().splitlines()
                expected = f'PASS entry={entry} backend={backend} mode=inline oracleOrigin=native oracleRows={rows} arity={arity} compiledPasses=2 backingBytes=64 freshArrayEveryCall=true validAfterExecution=true'
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
                memory = inspect_graph(graph, entry)
                lir, instructions = inspect_lir(cfgs[0].read_text(), target['root'], entry, arch)
                (path / 'final-lir.txt').write_text(lir)
                results.append(dict(stage=stage, backend=backend, entry=entry, rows=rows,
                    compiledCorpusPasses=2, installedEntryValidAfterEveryInput=True, activeTargetCheckedAfterEveryInput=True,
                    packedMemoryNode=memory, physicalPackedInstructions=instructions,
                    temporaryVectorAllocations=False, vectorFieldTraffic=False, intermediateLaneBoxes=False,
                    hostLongResultBox=ENTRIES[entry][0] == 'index', callerBackingArray=True,
                    all64BytesChecked=True, storeReturnedArrayIdentityChecked=ENTRIES[entry][0] == 'store',
                    highTierGraph=summary, parsedGraph=record(graph_path),
                    rawGraph=record(bgvs[0]), rawLir=record(cfgs[0]), log=record(path / 'run.log'),
                    command=record(path / 'run.command.txt'), exitStatus=record(path / 'run.exit-status.txt'),
                    lir=record(path / 'final-lir.txt')))
    write_json(out / 'evidence.json', dict(schema=1, vector='word32x4-bytearray', architecture=arch,
        results=results, **initial, checkerCorrection=correction, inputProvenance=record(out / 'input-provenance.json'),
        jdkRelease=record(out / 'jdk-release.txt'), javaVersion=record(out / 'java-version.txt'),
        claim='Native-backed pre/post Core on AST and bytecode; one live packed 128-bit caller byte-array load/store, unsigned four-lane load observation or exact four-lane store construction, with private carrier/vector/payload allocation eliminated.',
        limitations=['The index host Long result may allocate; this is not a globally allocation-free ABI.',
                     'Caller arrays are intentional and freshly allocated outside each guest invocation.',
                     'Interpreted Vector API fallback payloads may allocate; only these compiled paths are checked.',
                     'Little-endian x86 native corpus and packed memory lowering only; no big-endian or alignment-performance claim.',
                     'No vector function/formal/capture/join ABI, throughput, no-spill or non-x86 claim.',
                     'Compiled-entry counters are disabled; instrumented correctness tests must check their per-input deltas separately.']))
    print(f'Word32X4 ByteArray actual-Core graph gate passed: {arch}, {len(results)} records')


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
