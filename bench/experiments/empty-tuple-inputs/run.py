#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Build the reviewed runtime and capture fixed native-backed empty-input controls; no timing."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
CASES = [(stage, backend, mode, entry) for stage in ('pre', 'post')
         for backend in ('ast', 'bytecode') for mode in ('inline', 'residual')
         for entry in ('beforeCase', 'scalarControl', 'betweenInputs')]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sources():
    paths = subprocess.check_output(['git', 'ls-files', 'src/main', 'build.gradle.kts',
        'settings.gradle.kts', 'gradle.properties', 'gradle', 'gradlew', 'gradlew.bat'], cwd=ROOT, text=True).splitlines()
    return {p: digest(ROOT / p) for p in paths}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--collect-only', action='store_true')
    parser.add_argument('--capture', type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    if not args.collect_only:
        output.mkdir(parents=True, exist_ok=False)
        before = sources()
        revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        for path, sha in before.items():
            committed = subprocess.check_output(['git', 'show', revision + ':' + path], cwd=ROOT)
            assert hashlib.sha256(committed).hexdigest() == sha, 'Commit runtime source before capture: ' + path
        subprocess.run(['python3', ROOT / 'scripts/prepare-empty-tuple-input-audit.py', '--check-only'], cwd=ROOT, check=True)
        with (output / 'installDist.log').open('w') as log:
            subprocess.run([ROOT / 'gradlew', '--no-daemon', 'installDist'], cwd=ROOT,
                           stdout=log, stderr=subprocess.STDOUT, check=True)
        assert sources() == before, 'Runtime source changed during build'
        java = Path(os.environ['JAVA_HOME'])
        tools = [HERE / f for f in ('run.py', 'run-one.py', 'summarize.py', 'EmptyInputGraphProbe.java')]
        tools += [ROOT / 'tools/GraphInspect.java', ROOT / 'gradlew', ROOT / 'Makefile']
        inputs = tools + [java / 'release', *sorted((ROOT / 'build/install/thc/lib').glob('*.jar'))]
        launch = dict(runtimeSourceCommit=revision, runtimeSourceSha256=before,
                      inputSha256={str(p.resolve()): digest(p) for p in inputs},
                      javaVersion=subprocess.check_output([java / 'bin/java', '-version'], stderr=subprocess.STDOUT, text=True).splitlines())
        (output / 'launch.json').write_text(json.dumps(launch, indent=2) + '\n')
        for stage, backend, mode, entry in CASES:
            oracle = 'oracle-pairs.tsv' if entry == 'betweenInputs' else 'oracle.tsv'
            name = '-'.join((stage, backend, mode, entry))
            print('START', name, flush=True)
            subprocess.run(['python3', HERE / 'run-one.py',
                            ROOT / f'build/empty-tuple-input/{stage}-core/EmptyTupleInputAudit.json',
                            ROOT / f'build/empty-tuple-input/{oracle}', entry, backend, mode, output / name],
                           cwd=ROOT, check=True)
    launch = json.loads((output / 'launch.json').read_text())
    assert sources() == launch['runtimeSourceSha256'], 'Runtime sources differ from captured build'
    for path, sha in launch['inputSha256'].items():
        assert digest(Path(path)) == sha, 'Capture input changed: ' + path
    command = ['python3', HERE / 'summarize.py', output]
    if args.capture:
        command += ['--capture', args.capture]
    subprocess.run(command, cwd=ROOT, check=True)


if __name__ == '__main__':
    main()
