#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

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


def check(condition, message):
    if not condition:
        raise ValueError(message)


def source_hashes():
    paths = subprocess.check_output(['git', 'ls-files', 'src/main', 'build.gradle.kts',
        'settings.gradle.kts', 'gradle.properties', 'gradle', 'gradlew', 'gradlew.bat'], cwd=ROOT, text=True).splitlines()
    return {path: digest(ROOT / path) for path in paths}


def validate_hashes(hashes):
    for path, sha in hashes.items():
        check(digest(Path(path)) == sha, 'Input changed since graph capture: ' + path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--collect-only', action='store_true')
    parser.add_argument('--capture', type=Path, help='Write the compact evidence to this directory')
    args = parser.parse_args()
    output = args.output.resolve()
    check(args.collect_only or not output.exists() or not any(output.iterdir()),
          'Use a new or empty output directory; previous graphs must not enter a new capture')
    output.mkdir(parents=True, exist_ok=True)
    java_home = Path(os.environ['JAVA_HOME'])
    java = java_home / 'bin'
    module = ROOT / 'build/floating-tuple/pre-core/FloatingTupleAudit.json'
    oracle = ROOT / 'build/floating-tuple/oracle.tsv'
    provenance = ROOT / 'build/floating-tuple/provenance.json'
    tooling = [Path(__file__).resolve(), SHARED / 'TupleRuntimeGraphProbe.java', SHARED / 'inputs.py',
               SHARED / 'summarize.py', ROOT / 'tools/GraphInspect.java', ROOT / 'scripts/gradle.sh']
    manifest_path = output / 'launch-manifest.json'
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
        before_build = source_hashes()
        revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        for path, sha in before_build.items():
            committed = subprocess.check_output(['git', 'show', revision + ':' + path], cwd=ROOT)
            check(hashlib.sha256(committed).hexdigest() == sha, 'Commit runtime source before graph capture: ' + path)
        run(['python3', ROOT / 'scripts/prepare-floating-tuples.py', '--check-only'])
        run([ROOT / 'scripts/gradle.sh', '--no-daemon', 'installDist'], output / 'installDist.log')
        check(source_hashes() == before_build, 'Runtime source changed during installDist')
        launch = dict(schema=1, runtimeSourceCommit=revision, runtimeSourceSha256=before_build,
            javaVersion=subprocess.check_output([java / 'java', '-version'], stderr=subprocess.STDOUT, text=True).splitlines(),
            javaHome=str(java_home.resolve()), jdkReleaseSha256=digest(java_home / 'release'),
            toolingSha256={str(p.relative_to(ROOT)): digest(p) for p in tooling},
            nativeProvenance=json.loads(provenance.read_text()),
            inputsSha256={str(p): digest(p) for p in [module, oracle, provenance, *tooling, java_home / 'release',
                *sorted((ROOT / 'build/install/thc/lib').glob('*.jar'))]})
        manifest_path.write_text(json.dumps(launch, indent=2) + '\n')
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
        launch = json.loads(manifest_path.read_text())
        validate_hashes(launch['inputsSha256'])
        records, excerpts, revisions = [], [], set()
        for backend, mode, entry in CASES:
            out = output / f'{backend}-{mode}-{entry}'
            inputs = json.loads((out / 'run-inputs.json').read_text())
            validate_hashes(inputs['inputsSha256'])
            revisions.add(inputs['runtimeCheckoutHeadAtLaunch'])
            graph = json.loads((out / 'graph-evidence.json').read_text())
            rows = sum(line.split('\t')[0] == entry for line in oracle.read_text().splitlines())
            expected = f'PASS entry={entry} backend={backend} mode={mode} nativeRows={rows} arity=1 validAfterExecution=true'
            check(rows > 0 and graph['mode'] == mode and graph['checks'] == [expected],
                  'Graph case or native checks do not match: ' + str(out))
            raw = out / 'graphs' / graph['entryGraph']
            check(digest(raw) == graph['bgvSha256'] and digest(raw.with_suffix('.cfg')) == graph['cfgSha256'],
                  'Raw graph changed since audit: ' + str(raw))
            cfg = raw.with_suffix('.cfg').read_text()
            marker = '  name "After FinalCodeAnalysisStage"'
            check(marker in cfg, 'Missing final physical LIR: ' + str(raw))
            block = cfg[cfg.index(marker):].split('\nbegin_cfg', 1)[0]
            final_lir = '\n'.join(line.strip() for line in block.splitlines() if 'instruction ' in line) + '\n'
            check((out / 'final-lir.txt').read_text() == final_lir, 'Final LIR differs from audited CFG: ' + str(raw))
            records.append(dict(backend=backend, entry=entry, mode=mode, inputs=inputs, graph=graph))
            if mode == 'inline':
                excerpts.append(f'=== {backend}/{entry}: After FinalCodeAnalysisStage ===')
                excerpts.extend(line for line in final_lir.splitlines()
                                if any(token in line for token in ('FADD', 'FSUB', 'FMUL', 'FNEG', 'FLOATCONVERT', 'RETURN')))
        check(revisions == {launch['runtimeSourceCommit']}, 'Mixed runtime revisions')
        evidence = dict(scope='Actual production Core execution; fixed native oracle, no timing',
            **{key: launch[key] for key in ['runtimeSourceCommit', 'runtimeSourceSha256', 'javaVersion',
                'jdkReleaseSha256', 'toolingSha256', 'nativeProvenance']},
            launchManifest=launch, results=records,
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
