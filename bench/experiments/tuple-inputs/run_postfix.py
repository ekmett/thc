#!/usr/bin/env python3
"""Two final-source bytecode inline controls after the legacy scalar-read fix."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

from run import EXPORTS, HERE, ROOT, digest, require

CASES = [('pairInputs', 2, 7), ('mixedCase', 1, 11)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--collect-only', action='store_true')
    parser.add_argument('--capture', type=Path)
    args = parser.parse_args()
    out = args.output.resolve()
    java = Path(os.environ['JAVA_HOME'])
    classes = out / 'classes'
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
        # Build a current main JAR without replacing the historical installDist capture.
        run([ROOT / 'scripts/gradle.sh', '--offline', '--no-daemon', '-Pkotlin.incremental=false',
             '-Pkapt.incremental.apt=false', 'jar'], out / 'jar.log')
        require(sources == {p: digest(ROOT / p) for p in paths}, 'Runtime changed during build')
        jar = ROOT / 'build/libs/thc-0.1-experiment.jar'
        dependencies = sorted(p for p in (ROOT / 'build/install/thc/lib').glob('*.jar') if p.name != jar.name)
        require(dependencies, 'Build installDist dependencies before using this bounded runner')
        cp = ':'.join(map(str, [classes, jar, *dependencies]))
        module = ROOT / 'build/tuple-input/pre-core/TupleInputAudit.json'
        manifest = ROOT / 'build/tuple-input/provenance.json'
        inputs = [HERE / p for p in ('run_postfix.py', 'run.py', 'summarize.py', 'TupleInputGraphProbe.java')]
        inputs += [ROOT / 'tools/GraphInspect.java', ROOT / 'scripts/gradle.sh', module, manifest,
                   ROOT / 'build/tuple-input/oracle.tsv', ROOT / 'build/tuple-input/oracle-pairs.tsv',
                   java / 'release', jar, *dependencies]
        launch = dict(runtimeCommit=revision, runtimeSourceSha256=sources,
                      inputSha256={str(p.resolve()): digest(p) for p in inputs}, classpath=cp,
                      javaVersion=subprocess.check_output([java / 'bin/java', '-version'], stderr=subprocess.STDOUT, text=True).splitlines(),
                      nativeProvenance=json.loads(manifest.read_text()))
        (out / 'launch.json').write_text(json.dumps(launch, indent=2) + '\n')
        run([java / 'bin/javac', '-cp', cp, '-d', classes, HERE / 'TupleInputGraphProbe.java'])
        run([java / 'bin/javac', *EXPORTS, '-d', classes, ROOT / 'tools/GraphInspect.java'])
        flags = ['--enable-native-access=ALL-UNNAMED', '--add-modules=jdk.incubator.vector', '-XX:+UseCompactObjectHeaders',
                 '-Djdk.graal.Dump=Truffle:2', '-Djdk.graal.PrintGraph=File', '-Djdk.graal.PrintGraphWithSchedule=true',
                 '-Djdk.graal.PrintBackendCFG=true']
        for entry, _, _ in CASES:
            case = out / ('bytecode-inline-' + entry)
            case.mkdir()
            oracle = ROOT / 'build/tuple-input' / ('oracle-pairs.tsv' if entry == 'pairInputs' else 'oracle.tsv')
            run([java / 'bin/java', *flags, '-Djdk.graal.DumpPath=' + str(case / 'graphs'), '-cp', cp,
                 'TupleInputGraphProbe', module, oracle, entry, 'bytecode', 'inline'], case / 'run.log')
            raw = [case / 'run.log', *sorted((case / 'graphs').glob('*.bgv')), *sorted((case / 'graphs').glob('*.cfg'))]
            (case / 'raw-sha256.json').write_text(json.dumps({str(p.relative_to(case)): digest(p) for p in raw}, indent=2) + '\n')
            for graph in sorted((case / 'graphs').glob('*.bgv')):
                run([java / 'bin/java', '-XX:-UseJVMCICompiler', *EXPORTS, '-cp', classes, 'GraphInspect', graph,
                     case / ('parsed-' + graph.stem), '(Before phase HighTierLowering|After low tier)'],
                    case / ('parse-' + graph.stem + '.log'))

    launch = json.loads((out / 'launch.json').read_text())
    for path, sha in launch['inputSha256'].items():
        require(digest(path) == sha, 'Capture input changed: ' + path)
    records = []
    for entry, arity, rows in CASES:
        case = out / ('bytecode-inline-' + entry)
        raw = json.loads((case / 'raw-sha256.json').read_text())
        require('run.log' in raw and any(p.endswith('.bgv') for p in raw) and any(p.endswith('.cfg') for p in raw),
                'Incomplete raw capture: ' + entry)
        for path, sha in raw.items():
            require(digest(case / path) == sha, 'Raw capture changed: ' + str(case / path))
        run(['python3', HERE / 'summarize.py', case, 'inline'])
        graph = json.loads((case / 'graph-evidence.json').read_text())
        expected = f'PASS entry={entry} backend=bytecode mode=inline nativeRows={rows} arity={arity} validAfterEveryRow=true'
        require(graph['mode'] == 'inline' and graph['checks'] == [expected], 'Native case identity mismatch')
        records.append(dict(backend='bytecode', mode='inline', entry=entry, graph=graph,
                            finalLir=(case / 'final-lir.txt').read_text()))
    evidence = dict(scope='Two final-source bytecode inline controls after legacy scalar-read correction; pre-Tidy native inputs, no timing',
                    launch=launch, native=records,
                    artifactSha256={str(p.relative_to(out)): digest(p) for p in out.rglob('*') if p.is_file() and
                                    p.suffix in ('.bgv', '.cfg', '.json', '.txt', '.log', '.class') and
                                    p.name not in ('commands.json', 'collect-commands.json')},
                    limitations=['18 native rows per replay pass, two passes; separate from historical 18-control capture',
                                 'Graph instrumentation disabled; exact compiled-entry counters are independently tested',
                                 'Scalar Long Object-return box remains', 'No new residual or cycle capture is claimed'])
    if args.capture:
        args.capture.mkdir(parents=True, exist_ok=True)
        (args.capture / 'postfix-evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print('PASS two final-source bytecode inline controls')


if __name__ == '__main__':
    main()
