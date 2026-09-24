#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Strict native-backed Int16X8 graph/LIR gate. Synthetic parser tests are not evidence."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

ENTRIES = {'plusCase': 'AddNode', 'minusCase': 'SubNode',
           'timesCase': 'MulNode', 'negateCase': 'NegateNode'}
OPCODES = {'plusCase': 'VPADDW', 'minusCase': 'VPSUBW',
           'timesCase': 'VPMULLW', 'negateCase': 'VPSUBW'}
CORE = Path('build/simd-int16x8')


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
    require(proof['vector'] == 'int16x8' and proof['positiveAuditsAccepted'] is True,
            'Exact accepted Int16X8 provenance required')
    require(proof['stages'] == ['pre', 'post'], 'Both native pre/post Core stages are required')
    require(isinstance(proof['nativeRows'], int) and proof['nativeRows'] > 0
            and proof['modelMatched'] is True, 'Native oracle required; model-only input is not evidence')
    require(proof['toolchain']['ghcVersion'] == '9.14.1'
            and re.fullmatch(r'[0-9a-f]{64}', proof['toolchain']['ghcBinarySha256'])
            and proof['commands'], 'Missing pinned native toolchain/command provenance')
    verify_records(root, proof['sources'] + proof['artifacts'])
    artifacts = {item['path'] for item in proof['artifacts']}
    needed = {str(CORE / name) for name in (
        'oracle.tsv', 'expected.tsv', 'native/int16x8-oracle',
        'pre-core/SimdInt16X8.json', 'post-core/SimdInt16X8.json')}
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
    sources += [path for path in sorted((root / 'bench/experiments/int16x8-foundation').iterdir())
                if path.suffix in ('.py', '.sh', '.java')]
    jars = sorted((root / 'build/install/thc/lib').glob('*.jar'))
    require(jars, 'Build installDist before capture')
    return dict(sourceRevision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
                sources=[record(path) for path in sources], runtimeJars=[record(path) for path in jars],
                jdkFiles=[record(java_home / name) for name in ('release', 'bin/java', 'bin/javac', 'lib/modules')])


def kind(node):
    return node['nodeClass'].rsplit('.', 1)[-1]


def packed_stamp(stamp):
    if not isinstance(stamp, str) or not stamp.startswith('<') or not stamp.endswith('>'):
        return False
    lanes = stamp[1:-1].split(',')
    return len(lanes) == 8 and all(re.match(r'^i16(?:\s|$)', lane) for lane in lanes)


def zero_vector(node):
    return kind(node) == 'ConstantNode' and packed_stamp(node['properties'].get('stamp')) \
        and node['properties'].get('rawvalue') == '<0,0,0,0,0,0,0,0>'


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
        require(not re.search(r'thc\.runtime\.Int16X8|Short(?:\d+)?Vector|short\[\]|\[S(?:;|$)', str(stamp)),
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
        if entry == 'negateCase' and packed_stamp(stamp) and name == 'SubNode':
            left = [edge['from'] for edge in graph['edges'] if edge['to'] == node['id'] and edge['label'] == 'x']
            require(len(left) == 1 and zero_vector(nodes[left[0]]), 'Packed negation is not zero minus input')
            arithmetic.append(node)
    require(len(allocated_boxes) == 1, 'Exactly one public Long result allocation is expected')
    require(len(unboxes) == 2, 'Exactly the two scalar host Long input unboxes are expected')
    require(len(arithmetic) == 1, 'Expected exactly one live i16x8 ' + ENTRIES[entry])
    # Verify this is result arithmetic, not an unrelated/dead packed instruction.
    current = {allocated_boxes[0]}
    while True:
        parents = {edge['from'] for edge in graph['edges']
                   if edge['to'] in current and edge['type'] == 'Value'}
        if parents <= current:
            break
        current |= parents
    require(arithmetic[0]['id'] in current, 'Packed arithmetic does not feed the public result')
    cuts = {edge['to'] for edge in graph['edges'] if edge['from'] == arithmetic[0]['id']
            and edge['type'] == 'Value' and edge['to'] in current}
    observed = {nodes[node]['properties']['offset'] for node in cuts
                if kind(nodes[node]) == 'SimdCutNode' and nodes[node]['properties'].get('length') == 1}
    require(observed == set(range(8)), 'The public checksum must observe all eight packed output lanes')
    returns = [node['id'] for node in nodes.values() if kind(node) == 'ReturnNode']
    require(len(returns) == 1 and any(edge['from'] == allocated_boxes[0]
            and edge['to'] == returns[0] and edge['type'] == 'Value' for edge in graph['edges']),
            'Allowed Long allocation is not the direct public return')
    return dict(id=arithmetic[0]['id'], nodeClass=arithmetic[0]['nodeClass'],
                stamp=arithmetic[0]['properties']['stamp'])


def inspect_lir(text, target, entry, arch):
    require(arch in ('x86_64', 'amd64'), 'This bounded gate proves x86 XMM word instructions only: ' + arch)
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
    # Match instruction position, physical register, exact lane kind and XMM size.
    # A scalar instruction, YMM/ZMM operation, comment, or mnemonic substring cannot pass.
    operations = [line.strip() for line in lir.splitlines() if re.search(
        r'<\|@ instruction xmm\d+\|V128_WORD = ' + opcode + r'\b.*\bsize: XMM\b', line)]
    require(len(operations) == 1, 'Expected one allocated XMM ' + opcode)
    require(not re.search(r'\b(?:YMM|ZMM|V256_WORD|V512_WORD)\b', operations[0]), 'Wrong packed width')
    # For negateCase, the connected NegateNode (or explicit zero-minus SubNode)
    # establishes the operand semantics; VPSUBW establishes the physical lowering.
    return '\n'.join(line.rstrip() for line in lir.splitlines()) + '\n', operations[0]


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
    allowed = {str(root / 'bench/experiments/int16x8-foundation' / name)
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
                lir, instruction = inspect_lir(cfgs[0].read_text(), target['root'], entry, arch)
                (path / 'final-lir.txt').write_text(lir)
                results.append(dict(stage=stage, backend=backend, entry=entry, rows=rows,
                    compiledCorpusPasses=2, installedEntryValidAfterEveryInput=True, activeTargetCheckedAfterEveryInput=True,
                    packedArithmeticNode=arithmetic, physicalPackedInstruction=instruction,
                    temporaryVectorAllocations=False, vectorFieldTraffic=False, intermediateLaneBoxes=False,
                    hostLongResultBox=True, highTierGraph=summary, parsedGraph=record(graph_path),
                    rawGraph=record(bgvs[0]), rawLir=record(cfgs[0]), log=record(path / 'run.log'),
                    command=record(path / 'run.command.txt'), exitStatus=record(path / 'run.exit-status.txt'),
                    lir=record(path / 'final-lir.txt')))
    write_json(out / 'evidence.json', dict(schema=1, vector='int16x8', architecture=arch,
        results=results, **initial, checkerCorrection=correction, inputProvenance=record(out / 'input-provenance.json'),
        jdkRelease=record(out / 'jdk-release.txt'), javaVersion=record(out / 'java-version.txt'),
        claim='Native-backed real pre/post Core on AST and bytecode; compiled i16x8 arithmetic in 128-bit XMM registers with temporary carrier/vector/array allocations removed.',
        limitations=['The host Long result may allocate; this is not a globally allocation-free ABI.',
                     'Interpreted JDK ShortVector fallbacks allocate private short[] payloads.',
                     'No vector function/formal/capture/join ABI, throughput, no-spill or non-x86 claim.',
                     'Compiled-entry counters are disabled; instrumented correctness tests must check their per-input deltas separately.']))
    print(f'Int16X8 actual-Core graph gate passed: {arch}, {len(results)} records')


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
