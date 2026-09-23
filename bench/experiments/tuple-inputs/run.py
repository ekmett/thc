#!/usr/bin/env python3
"""Typed input graph controls: genuine native call edges and synthetic dynamic loops."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
EXPORTS = ['--add-modules', 'jdk.graal.compiler', '--add-exports',
           'jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED', '--add-exports',
           'jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED']
NATIVE = [(backend, mode, entry) for backend in ('ast', 'bytecode')
          for mode in ('inline', 'residual') for entry in ('pairInputs', 'mixedCase', 'lazyCase')]
LOOPS = [(shape, backend) for shape in ('self', 'cycle', 'prefix') for backend in ('ast', 'bytecode')]


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--collect-only', action='store_true')
    parser.add_argument('--capture', type=Path)
    args = parser.parse_args()
    out = args.output.resolve()
    java = Path(os.environ['JAVA_HOME'])
    classes = out / 'classes'
    cp = str(classes) + ':' + str(ROOT / 'build/install/thc/lib/*')
    module = ROOT / 'build/tuple-input/pre-core/TupleInputAudit.json'
    manifest = ROOT / 'build/tuple-input/provenance.json'
    tools = sorted([p for p in HERE.rglob('*') if p.is_file() and
                    ('captured' not in p.parts) and (p.suffix in ('.py', '.java', '.json'))])
    tools += [ROOT / 'tools/GraphInspect.java', ROOT / 'scripts/gradle.sh']
    commands = []

    def run(argv, log=None):
        argv = list(map(str, argv))
        commands.append(argv)
        (out / ('collect-commands.json' if args.collect_only else 'commands.json')).write_text(json.dumps(commands, indent=2) + '\n')
        if log:
            with log.open('w') as stream:
                subprocess.run(argv, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, check=True)
        else:
            subprocess.run(argv, cwd=ROOT, check=True)

    def parse(case, loop=False):
        graphs = list((case / 'graphs').glob('*EntryRoot*.bgv' if loop else '*.bgv'))
        require(graphs, 'Missing captured graph: ' + str(case))
        if loop:
            require(len(graphs) == 1, 'Expected one explicitly compiled host graph')
        for graph in graphs:
            parsed = case / ('parsed' if loop else 'parsed-' + graph.stem)
            phases = 'Before phase HighTierLowering' if loop else '(Before phase HighTierLowering|After low tier)'
            run([java / 'bin/java', '-XX:-UseJVMCICompiler', *EXPORTS, '-cp', classes,
                 'GraphInspect', graph, parsed, phases], case / ('parse-' + graph.stem + '.log'))

    def freeze_raw(case):
        paths = [case / 'run.log', *sorted((case / 'graphs').glob('*.bgv')), *sorted((case / 'graphs').glob('*.cfg'))]
        (case / 'raw-sha256.json').write_text(json.dumps({str(p.relative_to(case)): digest(p) for p in paths}, indent=2) + '\n')

    if not args.collect_only:
        out.mkdir(parents=True, exist_ok=False)
        classes.mkdir()
        revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        paths = subprocess.check_output(['git', 'ls-files', 'src/main', 'build.gradle.kts', 'settings.gradle.kts',
                                         'gradle.properties', 'gradle', 'gradlew', 'gradlew.bat'], cwd=ROOT, text=True).splitlines()
        sources = {p: digest(ROOT / p) for p in paths}
        for path, sha in sources.items():
            require(hashlib.sha256(subprocess.check_output(['git', 'show', revision + ':' + path], cwd=ROOT)).hexdigest() == sha,
                    'Commit runtime source before capture: ' + path)
        run(['python3', ROOT / 'scripts/prepare-tuple-input-audit.py', '--check-only'])
        run([ROOT / 'scripts/gradle.sh', '--offline', '--no-daemon', '-Pkotlin.incremental=false',
             '-Pkapt.incremental.apt=false', 'installDist'], out / 'installDist.log')
        require(sources == {p: digest(ROOT / p) for p in paths}, 'Runtime changed during build')
        inputs = tools + [module, manifest, ROOT / 'build/tuple-input/oracle.tsv', ROOT / 'build/tuple-input/oracle-pairs.tsv',
                          java / 'release', *sorted((ROOT / 'build/install/thc/lib').glob('*.jar'))]
        launch = dict(runtimeCommit=revision, runtimeSourceSha256=sources,
                      inputSha256={str(p.resolve()): digest(p) for p in inputs},
                      javaVersion=subprocess.check_output([java / 'bin/java', '-version'], stderr=subprocess.STDOUT, text=True).splitlines(),
                      nativeProvenance=json.loads(manifest.read_text()))
        (out / 'launch.json').write_text(json.dumps(launch, indent=2) + '\n')
        run([java / 'bin/javac', '-cp', cp, '-d', classes, HERE / 'TupleInputGraphProbe.java', HERE / 'CycleAllocationProbe.java'])
        run([java / 'bin/javac', *EXPORTS, '-d', classes, ROOT / 'tools/GraphInspect.java'])
        flags = ['--enable-native-access=ALL-UNNAMED', '--add-modules=jdk.incubator.vector', '-XX:+UseCompactObjectHeaders',
                 '-Djdk.graal.Dump=Truffle:2', '-Djdk.graal.PrintGraph=File', '-Djdk.graal.PrintGraphWithSchedule=true',
                 '-Djdk.graal.PrintBackendCFG=true']
        for backend, mode, entry in NATIVE:
            case = out / '-'.join((backend, mode, entry)); case.mkdir()
            oracle = ROOT / 'build/tuple-input' / ('oracle-pairs.tsv' if entry == 'pairInputs' else 'oracle.tsv')
            run([java / 'bin/java', *flags, '-Djdk.graal.DumpPath=' + str(case / 'graphs'), '-cp', cp,
                 'TupleInputGraphProbe', module, oracle, entry, backend, mode], case / 'run.log')
            freeze_raw(case)
            parse(case)
            run(['python3', HERE / 'summarize.py', case, mode])
        for shape, backend in LOOPS:
            case = out / f'{shape}-changing-{backend}'; case.mkdir()
            run([java / 'bin/java', *flags, '-Djdk.graal.DumpPath=' + str(case / 'graphs'), '-cp', cp,
                 'CycleAllocationProbe', HERE / 'loops' / f'{shape}-changing.json', backend], case / 'run.log')
            freeze_raw(case)
            parse(case, True)
    launch = json.loads((out / 'launch.json').read_text())
    for path, sha in launch['inputSha256'].items():
        require(digest(path) == sha, 'Capture input changed: ' + path)
    for label in ['-'.join(case) for case in NATIVE] + [f'{shape}-changing-{backend}' for shape, backend in LOOPS]:
        case = out / label
        raw = json.loads((case / 'raw-sha256.json').read_text())
        require('run.log' in raw and any(p.endswith('.bgv') for p in raw) and any(p.endswith('.cfg') for p in raw),
                'Incomplete raw capture: ' + label)
        for path, sha in raw.items():
            require(digest(case / path) == sha, 'Raw capture changed: ' + label + '/' + path)
    # Recompute inventories on collection; do not trust a copied case label or summary.
    for script in ('read_graphs.py', 'read_cfg.py', 'read_lir.py'):
        run(['python3', HERE / script, out, out / 'loop-review'], out / (script + '.log'))
    records = []
    for backend, mode, entry in NATIVE:
        case = out / '-'.join((backend, mode, entry))
        run(['python3', HERE / 'summarize.py', case, mode])
        graph = json.loads((case / 'graph-evidence.json').read_text())
        arity, rows = (2, 7) if entry == 'pairInputs' else (1, 11)
        expected = f'PASS entry={entry} backend={backend} mode={mode} nativeRows={rows} arity={arity} validAfterEveryRow=true'
        require(graph['mode'] == mode and graph['checks'] == [expected], 'Native case identity mismatch')
        records.append(dict(kind='native', backend=backend, mode=mode, entry=entry, graph=graph,
                            finalLir=(case / 'final-lir.txt').read_text()))
    reviews = {name: json.loads((out / 'loop-review' / name).read_text())
               for name in ('high-tier-review.json', 'cfg-review.json', 'final-lir-review.json')}
    for name, review in reviews.items():
        require(len(review) == 6, 'Expected all six loop reviews: ' + name)
    for shape, backend in LOOPS:
        case = out / f'{shape}-changing-{backend}'
        expected = f'PASS {backend} {HERE / "loops" / (shape + "-changing.json")} rows=6 hostValid=true guestValid=true; dynamic depth and payload addition'
        require([line for line in (case / 'run.log').read_text().splitlines() if line.startswith('PASS ')] == [expected],
                'Loop case identity mismatch: ' + str(case))
    # Preserve all raw hashes, including selected JSON, to make stored inventories auditable.
    artifacts = {str(p.relative_to(out)): digest(p) for p in out.rglob('*') if p.is_file() and
                 p.suffix in ('.bgv', '.cfg', '.json', '.txt', '.log') and p.name not in ('commands.json',)}
    evidence = dict(scope='Twelve genuine pre-Tidy native graph controls and six synthetic dynamic-loop controls; no timing',
                    launch=launch, native=records, loops=reviews, artifactSha256=artifacts,
                    limitations=['Graph instrumentation disabled; active installed-target validity differs from exact compiled-entry counters',
                                 'Instrumented JVM tests separately enforce exact counts at both export stages',
                                 'Synthetic loops check an independent arithmetic formula, not a new GHC native oracle',
                                 'Long Object-return boxes remain outside the loops; no no-spill or universal register ABI claim',
                                 'Residual compiled edges may allocate a precise carrier and one-element outer packet'])
    if args.capture:
        args.capture.mkdir(parents=True, exist_ok=True)
        (args.capture / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
        for name in ('oracle.tsv', 'oracle-pairs.tsv'):
            (args.capture / name).write_bytes((ROOT / 'build/tuple-input' / name).read_bytes())
    print('PASS 12 native and 6 changing-payload loop graph controls')


if __name__ == '__main__':
    main()
