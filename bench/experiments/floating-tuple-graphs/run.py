#!/usr/bin/env python3
"""Fixed native inputs through production THC; inspect floating tuple elimination, not timing."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SHARED = HERE.parent / 'tuple-runtime-graphs'
EXPORTS = ['--add-modules', 'jdk.graal.compiler',
           '--add-exports', 'jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED',
           '--add-exports', 'jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED']
CASES = [(backend, mode, entry) for backend in ('ast', 'bytecode')
         for mode, entries in [('inline', ['complexFloatCase', 'complexDoubleCase', 'mixedCase']),
                               ('residual', ['mixedCase'])] for entry in entries]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--collect-only', action='store_true')
    parser.add_argument('--capture', type=Path, help='Write the compact evidence to this directory')
    args = parser.parse_args()
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    java_home = Path(os.environ['JAVA_HOME'])
    java = java_home / 'bin'
    module = ROOT / 'build/floating-tuple/pre-core/FloatingTupleAudit.json'
    oracle = ROOT / 'build/floating-tuple/oracle.tsv'
    classes = output / 'classes'; classes.mkdir(exist_ok=True)
    cp = str(classes) + ':' + str(ROOT / 'build/install/thc/lib/*')
    commands = []
    def run(argv, log=None):
        commands.append(list(map(str, argv)))
        (output / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        if log:
            with log.open('w') as stream:
                subprocess.run(argv, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, check=True)
        else:
            subprocess.run(argv, cwd=ROOT, check=True)
    if not args.collect_only:
        run(['python3', ROOT / 'scripts/prepare-floating-tuples.py', '--check-only'])
        run([java / 'javac', '-cp', cp, '-d', classes, SHARED / 'TupleRuntimeGraphProbe.java'])
        run([java / 'javac', *EXPORTS, '-d', classes, ROOT / 'tools/GraphInspect.java'])
        for backend, mode, entry in CASES:
            out = output / f'{backend}-{mode}-{entry}'; out.mkdir(exist_ok=True)
            run(['python3', SHARED / 'inputs.py', out, ROOT, module, oracle])
            run([java / 'java', '--enable-native-access=ALL-UNNAMED', '--add-modules=jdk.incubator.vector',
                 '-XX:+UseCompactObjectHeaders', '-Djdk.graal.Dump=Truffle:2', '-Djdk.graal.PrintGraph=File',
                 '-Djdk.graal.PrintGraphWithSchedule=true', '-Djdk.graal.PrintBackendCFG=true',
                 '-Djdk.graal.DumpPath=' + str(out / 'graphs'), '-cp', cp,
                 'TupleRuntimeGraphProbe', module, oracle, entry, backend, mode], out / 'run.log')
            for bgv in (out / 'graphs').glob('*.bgv'):
                run([java / 'java', '-XX:-UseJVMCICompiler', *EXPORTS, '-cp', classes, 'GraphInspect', bgv,
                     out / ('parsed-' + bgv.stem), '(Before phase HighTierLowering|After low tier)'], out / ('parse-' + bgv.stem + '.log'))
            run(['python3', SHARED / 'summarize.py', out, mode])
    if args.capture:
        records, excerpts, revisions = [], [], set()
        for backend, mode, entry in CASES:
            out = output / f'{backend}-{mode}-{entry}'
            inputs = json.loads((out / 'run-inputs.json').read_text())
            for path, sha in inputs['inputsSha256'].items():
                assert digest(Path(path)) == sha, 'Inputs changed since graph capture: ' + path
            revisions.add(inputs['runtimeCheckoutHeadAtLaunch'])
            graph = json.loads((out / 'graph-evidence.json').read_text())
            assert graph['checks'] and all('validAfterExecution=true' in line for line in graph['checks'])
            records.append(dict(backend=backend, entry=entry, mode=mode, inputs=inputs, graph=graph))
            if mode == 'inline':
                excerpts.append(f'=== {backend}/{entry}: After FinalCodeAnalysisStage ===')
                excerpts.extend(line for line in (out / 'final-lir.txt').read_text().splitlines()
                                if any(token in line for token in ('FADD', 'FSUB', 'FMUL', 'FNEG', 'FLOATCONVERT', 'RETURN')))
        assert len(revisions) == 1, 'Mixed runtime revisions'
        revision = revisions.pop()
        runtime_files = ['Handoff.kt', 'TupleResults.kt', 'LocalJoins.kt', 'Program.kt', 'BytecodeProgram.kt', 'CoreRepresentations.kt']
        sources = [ROOT / 'src/main/kotlin/thc/runtime' / name for name in runtime_files]
        sources += [ROOT / 'src/main/java/thc/runtime/BytecodeRoot.java', ROOT / 'compiler/test-fixtures/FloatingTupleAudit.hs',
                    ROOT / 'compiler/test-fixtures/FloatingTupleAuditNative.hs']
        hashes = {str(path.relative_to(ROOT)): hashlib.sha256(subprocess.check_output(
            ['git', 'show', revision + ':' + str(path.relative_to(ROOT))], cwd=ROOT)).hexdigest() for path in sources}
        evidence = dict(scope='Actual production Core execution; fixed native oracle, no timing', runtimeSourceCommit=revision,
            runtimeSourceSha256=hashes, javaVersion=subprocess.check_output([java / 'java', '-version'], stderr=subprocess.STDOUT, text=True).splitlines(),
            jdkReleaseSha256=digest(java_home / 'release'),
            toolingSha256={str(p.relative_to(ROOT)): digest(p) for p in [Path(__file__).resolve(),
                SHARED / 'TupleRuntimeGraphProbe.java', SHARED / 'inputs.py', SHARED / 'summarize.py', ROOT / 'tools/GraphInspect.java']},
            nativeProvenance=json.loads((ROOT / 'build/floating-tuple/provenance.json').read_text()), results=records,
            limitations=['Graph instrumentation is disabled: exact target validity, not per-row compiled-entry counters',
                         'Instrumented JVM tests separately require exact per-row compiled-entry increments',
                         'Final scalar Long host-result box remains', 'Residual scalar floating inputs still use Object packets',
                         'No multiple-register residual return ABI; register allocation may spill'])
        args.capture.mkdir(parents=True, exist_ok=True)
        (args.capture / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
        (args.capture / 'lir-excerpts.txt').write_text('\n'.join(excerpts) + '\n')
        (args.capture / 'native-oracle.tsv').write_bytes(oracle.read_bytes())
        print('Captured', len(records), 'native-backed production graph controls')


if __name__ == '__main__':
    main()
