#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Native-backed production Int-array graph checks. Fixed correctness inputs, no timing."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
MANIFEST = ROOT / 'build/int-arrays/manifest.json'
CASES = [(backend, entry) for backend in ('ast', 'bytecode') for entry in ('orderedInts', 'aliasIntBytes')]
EXPORTS = ['--add-modules', 'jdk.graal.compiler',
           '--add-exports', 'jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED',
           '--add-exports', 'jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED']


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check(condition, message):
    if not condition:
        raise ValueError(message)


def runtime_sources():
    files = subprocess.check_output(['git', 'ls-files', 'src/main', 'build.gradle.kts',
        'settings.gradle.kts', 'gradle.properties', 'gradle', 'gradlew', 'gradlew.bat'], cwd=ROOT, text=True).splitlines()
    return {p: sha(ROOT / p) for p in files}


def native_fixture():
    manifest = json.loads(MANIFEST.read_text())
    check((manifest['ghc'], manifest['array'], manifest['wordBits'], manifest['nativeRows']) ==
          ('9.14.1', '0.5.8.0', 64, 1965), 'Wrong native fixture/toolchain')
    for group in ('inputHashes', 'artifactHashes'):
        for path, digest in manifest[group].items():
            check(sha(ROOT / path) == digest, 'Stale native fixture: ' + path)
    for stage in ('pre', 'post'):
        for entry in manifest['entries']:
            report = json.loads((ROOT / f'build/int-arrays/{stage}/{entry}.audit.json').read_text())
            check(report['accepted'], 'Strict native fixture audit rejected: ' + entry)
    lines = (ROOT / 'build/int-arrays/oracle.tsv').read_text().splitlines()
    for entry in ('orderedInts', 'aliasIntBytes'):
        rows = [line.split('\t') for line in lines if line.split('\t')[0] == entry]
        check(len(rows) == 393 and all(len(row) == 3 for row in rows), 'Wrong native row count/arity: ' + entry)
        check({int(row[1]) for row in rows} == set(manifest['inputs']), 'Changed native cases: ' + entry)
    return manifest


def validate_launch(launch):
    check(runtime_sources() == launch['runtimeSourceSha256'], 'Runtime source changed since launch')
    for path, digest in launch['fileSha256'].items():
        check(sha(ROOT / path) == digest, 'Launch input changed: ' + path)
    check(sha(Path(launch['javaHome']) / 'release') == launch['jdkReleaseSha256'], 'JDK release changed')


def inspect_graph(case, backend, entry):
    index = json.loads((case / 'parsed/index.json').read_text())
    phases = [g for g in index['graphs'] if 'file' in g]
    check(len(phases) == 1 and 'Before phase HighTierLowering' in phases[0]['name'], 'Wrong graph phase')
    graph = json.loads((case / 'parsed' / phases[0]['file']).read_text())
    log = (case / 'run.log').read_text()
    targets = [json.loads(line.removeprefix('GRAPH_TARGET=')) for line in log.splitlines() if line.startswith('GRAPH_TARGET=')]
    check(len(targets) == 1 and targets[0]['entry'] == entry and targets[0]['backend'] == backend, 'Wrong target identity')
    check(graph['group'] == 'TruffleIR.Tier2.' + targets[0]['root'].replace(' ', '_') + '()', 'Wrong compiled graph')
    expected = f'PASS entry={entry} backend={backend} mode=inline nativeRows=393 arity=1 compiledPasses=2 validAfterEveryRow=true'
    check([line for line in log.splitlines() if line.startswith('PASS ')] == [expected], 'Native/installed-code gate missing')
    nodes = {n['id']: n for n in graph['nodes']}
    short = lambda n: n['nodeClass'].split('.')[-1]
    counts = Counter(short(n) for n in nodes.values())
    def incoming(node, label):
        found = [nodes[e['from']] for e in graph['edges'] if e['to'] == node['id'] and e['label'] == label and e['type'] != 'Successor']
        check(len(found) == 1, 'Unexpected graph input: ' + label)
        return found[0]
    raw = {kind: [n for n in nodes.values() if short(n) == kind] for kind in ('RawLoadNode', 'RawStoreNode')}
    expected_counts = (4, 5) if entry == 'orderedInts' else (2, 2)
    check(tuple(len(raw[k]) for k in raw) == expected_counts, 'Primitive memory access count changed')
    allocated = [n for n in nodes.values() if short(n) == 'AllocatedObjectNode']
    arrays = []
    for node in allocated:
        virtual = incoming(node, 'virtualObject')
        check(short(virtual) == 'VirtualArrayNode' and virtual['properties']['componentType'] == 'byte', 'Unexpected committed guest carrier')
        check(short(incoming(node, 'commit')) == 'CommitAllocationNode', 'Missing array allocation commit')
        arrays.append(virtual['properties']['length'])
    check(sorted(arrays) == ([8, 24] if entry == 'orderedInts' else [16]), 'Unexpected backing arrays')
    check(counts['CommitAllocationNode'] == len(arrays), 'Unexpected allocation commit')
    for node in raw['RawLoadNode'] + raw['RawStoreNode']:
        check(node['properties']['accessKind'] == 'JavaKind.Long' and node['properties']['locationIdentity'] == 'Array: byte', 'Nonprimitive or separate memory access')
        check(incoming(node, 'object')['id'] in {a['id'] for a in allocated}, 'Access does not use committed backing array')
        if short(node) == 'RawLoadNode':
            check(node['properties']['stamp'].startswith('i64'), 'Non-long load')
        else:
            check(incoming(node, 'value')['properties']['stamp'].startswith('i64'), 'Non-long store')
    byte_stores = [n for n in nodes.values() if short(n) == 'StoreIndexedNode']
    offsets = []
    for node in byte_stores:
        check(node['properties']['elementKind'] == 'JavaKind.Byte', 'Unexpected indexed store')
        check(incoming(node, 'array')['id'] in {a['id'] for a in allocated}, 'Byte alias has separate backing')
        offsets.append(int(incoming(node, 'index')['properties']['rawvalue']))
    check(sorted(offsets) == ([] if entry == 'orderedInts' else [7, 8]), 'Wrong byte alias boundary')
    check(not any('Invoke' in name for name in counts), 'Guest/helper call survived')
    check(not counts['NewArrayNode'] and not counts['NewInstanceNode'], 'Unexpected additional allocation')
    boxes = [n for n in nodes.values() if short(n) == 'BoxNode$AllocatingBoxNode']
    check(len(boxes) == 1 and boxes[0]['properties']['stamp'] == 'a!# java.lang.Long', 'Unexpected scalar boxes')
    returns = [n for n in nodes.values() if short(n) == 'ReturnNode']
    check(len(returns) == 1 and incoming(returns[0], 'result')['id'] == boxes[0]['id'], 'Box is not the host result')
    bgvs = list((case / 'graphs').glob('*.bgv'))
    check(len(bgvs) == 1, 'Ambiguous or stale raw graphs')
    cfg = bgvs[0].with_suffix('.cfg')
    text = cfg.read_text(); start = text.rindex('  name "After FinalCodeAnalysisStage"'); end = text.index('end_cfg', start)
    instructions = [line for line in text[start:end].splitlines() if 'instruction ' in line]
    slow_arrays = sum('Stub<new_array_or_null(' in line for line in instructions)
    slow_objects = sum('Stub<new_instance_or_null(' in line for line in instructions)
    check((slow_arrays, slow_objects) == (len(arrays), 1), 'Final allocation paths differ from high-tier evidence')
    return dict(case=case.name, backend=backend, entry=entry, coreStage='post', nativeRows=393, compiledPasses=2,
        activeTargetValidAfterEveryRow=True, phase=phases[0]['name'], graphNodes=len(nodes),
        rawLongLoads=len(raw['RawLoadNode']), rawLongStores=len(raw['RawStoreNode']),
        committedByteArraySizes=sorted(arrays), byteAliasStoreOffsets=sorted(offsets), allocatingHostLongBoxes=1,
        invokes=0, finalArrayAllocationSlowPaths=slow_arrays, finalObjectAllocationSlowPaths=slow_objects,
        nodeCounts=dict(sorted(counts.items())), check=expected,
        artifactSha256={str(p.relative_to(case)): sha(p) for p in
            (bgvs[0], cfg, case / 'run.log', case / 'parsed' / phases[0]['file'])})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--collect-only', action='store_true')
    parser.add_argument('--capture', type=Path)
    args = parser.parse_args(); out = args.output.resolve()
    fixture = native_fixture()
    if not args.collect_only:
        out.mkdir(parents=True, exist_ok=False)
        source = runtime_sources()
        revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        for path, digest in source.items():
            check(hashlib.sha256(subprocess.check_output(['git', 'show', revision + ':' + path], cwd=ROOT)).hexdigest() == digest, 'Commit runtime source before capture: ' + path)
        with (out / 'installDist.log').open('w') as log:
            subprocess.run([ROOT / 'gradlew', '--no-daemon', 'installDist'], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
        check(runtime_sources() == source, 'Source changed during build')
        java = Path(os.environ['JAVA_HOME']).resolve(); classes = out / 'classes'; classes.mkdir()
        files = [Path(__file__).resolve(), HERE / 'IntArrayGraphProbe.java', ROOT / 'tools/GraphInspect.java',
                 ROOT / 'gradlew', ROOT / 'Makefile', MANIFEST,
                 *sorted((ROOT / 'build/install/thc/lib').glob('*.jar'))]
        files += [ROOT / p for group in ('inputHashes', 'artifactHashes') for p in fixture[group]]
        launch = dict(runtimeSourceCommit=revision, runtimeSourceSha256=source,
            fileSha256={str(p.relative_to(ROOT)): sha(p) for p in sorted(set(files))},
            javaHome=str(java), jdkReleaseSha256=sha(java / 'release'),
            javaVersion=subprocess.check_output([java / 'bin/java', '-version'], stderr=subprocess.STDOUT, text=True).splitlines(),
            fixture={k:fixture[k] for k in ('ghc','array','wordBits','byteOrder','nativeRows','inputHashes','artifactHashes')})
        (out / 'launch.json').write_text(json.dumps(launch, indent=2) + '\n')
        modules = out / 'post-modules.txt'; modules.write_text(''.join(str(ROOT / p) + '\n' for p in fixture['stages']['post']))
        cp = str(classes) + ':' + str(ROOT / 'build/install/thc/lib/*')
        commands = []
        def run(command, log=None):
            command = list(map(str, command)); commands.append(command)
            (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
            if log:
                with log.open('w') as stream: subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, check=True)
            else: subprocess.run(command, cwd=ROOT, check=True)
        run([java / 'bin/javac', '-cp', cp, '-d', classes, HERE / 'IntArrayGraphProbe.java'])
        run([java / 'bin/javac', *EXPORTS, '-d', classes, ROOT / 'tools/GraphInspect.java'])
        for backend, entry in CASES:
            case = out / f'post-{backend}-{entry}'; case.mkdir()
            run([java / 'bin/java', '--add-modules=jdk.incubator.vector', '--enable-native-access=ALL-UNNAMED',
                 '-XX:+UseCompactObjectHeaders', '-Dthc.handoffSlabs=false', '-Djdk.graal.Dump=Truffle:2',
                 '-Djdk.graal.PrintGraph=File', '-Djdk.graal.PrintGraphWithSchedule=true', '-Djdk.graal.PrintBackendCFG=true',
                 '-Djdk.graal.DumpPath=' + str(case / 'graphs'), '-cp', cp, 'IntArrayGraphProbe', modules,
                 ROOT / 'build/int-arrays/oracle.tsv', entry, backend], case / 'run.log')
            graphs = list((case / 'graphs').glob('*.bgv')); check(len(graphs) == 1, 'Expected one explicit guest compilation')
            run([java / 'bin/java', '-XX:-UseJVMCICompiler', *EXPORTS, '-cp', classes, 'GraphInspect',
                 graphs[0], case / 'parsed', 'Before phase HighTierLowering'], case / 'parse.log')
        validate_launch(launch)
        records = [inspect_graph(out / f'post-{backend}-{entry}', backend, entry) for backend, entry in CASES]
        (out / 'records.json').write_text(json.dumps(records, indent=2) + '\n')
    launch = json.loads((out / 'launch.json').read_text()); validate_launch(launch)
    records = json.loads((out / 'records.json').read_text())
    check(len(records) == len(CASES), 'Wrong capture count')
    for record, (backend, entry) in zip(records, CASES):
        case = out / f'post-{backend}-{entry}'
        check(record == inspect_graph(case, backend, entry), 'Graph evidence changed since capture')
    if args.capture:
        args.capture.mkdir(parents=True, exist_ok=True)
        evidence = dict(schema=1, scope='Post-Tidy production AST/bytecode, default handoff and normal inlining; correctness and graph shape only',
            launch=launch, results=records, limitations=['Actual byte[] allocations remain, plus one scalar host Long box',
            'Instrumentation is disabled: target validity/identity, not compiled-entry counters',
            'Separate instrumented ByteArray/IntArray tests enforce per-row compiled entry',
            'No residual-call, handoff-graph, allocation-elimination or throughput claim'])
        (args.capture / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
        lines = (ROOT / 'build/int-arrays/oracle.tsv').read_text().splitlines()
        (args.capture / 'native-oracle.tsv').write_text(''.join(line+'\n' for line in lines if line.split('\t')[0] in ('orderedInts','aliasIntBytes')))
    for record in records:
        print(record['case'], 'PASS', record['rawLongLoads'], 'Long loads,', record['rawLongStores'], 'stores; byte[] sizes', record['committedByteArraySizes'])


if __name__ == '__main__':
    main()
