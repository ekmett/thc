#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Retain checked scalar floating loop snapshots and their original BGVs."""
import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('floating_graph', ROOT / 'tools/check-floating-loop-graph.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ast', type=Path, required=True)
    parser.add_argument('--bytecode', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    # Record runtime source state before replacing any tracked evidence files.
    runtime_commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    tracked_clean = subprocess.run(['git', 'diff', '--quiet', 'HEAD'], cwd=ROOT).returncode == 0
    args.output.mkdir(parents=True, exist_ok=True)
    evidence = {}
    archive = args.output / 'selected-bgv.tar.xz'
    with tarfile.open(archive, 'w:xz') as tar:
        for backend, directory in [('ast', args.ast), ('bytecode', args.bytecode)]:
            candidates = []
            for index_path in sorted(directory.glob('parsed-*/index.json')):
                index = json.loads(index_path.read_text())
                for graph in index['graphs']:
                    if graph['group'] == 'TruffleIR.Tier2.lambda_n()' and 'Before phase HighTierLowering' in graph['name']:
                        path = index_path.parent / graph['file']
                        candidates.append((path, Path(index['source']), checker.check(path)))
            assert len(candidates) == 1, (backend, 'expected exactly one original entry compilation', len(candidates))
            path, bgv, report = candidates[0]
            assert report['passed'], report
            snapshot = args.output / f'{backend}-high-tier.json.gz'
            snapshot.write_bytes(gzip.compress(path.read_bytes(), mtime=0))
            report['graph'] = snapshot.name
            (args.output / f'{backend}-loop.json').write_text(json.dumps(report, indent=2) + '\n')
            tar.add(bgv, arcname=f'{backend}.bgv')
            evidence[backend] = dict(snapshot=snapshot.name, snapshotSha256=sha(snapshot),
                bgvArchiveMember=f'{backend}.bgv', bgvSha256=sha(bgv),
                originalSnapshot=str(path), originalBgv=str(bgv),
                loopBegin=report['loops'][0]['loopBegin'])
    checks = ROOT / 'build/floating/checks.json'
    jars = sorted((ROOT / 'build/install/thc/lib').glob('thc*.jar'))
    assert len(jars) == 1, jars
    manifest = dict(runtimeCommit=runtime_commit, trackedWorktreeClean=tracked_clean,
        javaVersion=subprocess.check_output(['java', '--version'], text=True).strip(),
        installedJarSha256=sha(jars[0]), floatingChecksSha256=sha(checks),
        nativeCoreSha256=sha(ROOT / 'build/floating/core/FloatingAudit.json'),
        archiveSha256=sha(archive), evidence=evidence)
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(dict(passed=True, output=str(args.output), runtimeCommit=manifest['runtimeCommit'])))


if __name__ == '__main__':
    main()
