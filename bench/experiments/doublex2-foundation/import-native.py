#!/usr/bin/env python3
"""Verify and import the source-matched DoubleX2 native handoff, without executing it."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import shutil
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts'))
import doublex2_model as model
spec = importlib.util.spec_from_file_location('doublex2_prepare', ROOT / 'scripts/prepare-doublex2-audit.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--sha256', required=True, help='Expected archive hash from its trusted handoff')
    args = parser.parse_args()
    assert digest(args.archive) == args.sha256, 'Native handoff archive hash mismatch'
    with tempfile.TemporaryDirectory(prefix='doublex2-native-') as temporary:
        extracted = Path(temporary)
        with tarfile.open(args.archive, 'r:gz') as archive:
            for member in archive:
                name = PurePosixPath(member.name)
                assert not name.is_absolute() and '..' not in name.parts, 'Unsafe archive path'
                assert member.isfile() or member.isdir(), 'Links and special files are not accepted'
                target = extracted / name
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with archive.extractfile(member) as source, target.open('wb') as destination:
                        shutil.copyfileobj(source, destination)
        origin, = extracted.iterdir()
        records = {}
        for line in (origin / 'SHA256SUMS').read_text().splitlines():
            sha, relative = line.split(None, 1)
            path = PurePosixPath(relative)
            assert not path.is_absolute() and '..' not in path.parts and relative not in records, 'Invalid payload hash path'
            assert digest(origin / relative) == sha, 'Native payload hash mismatch: '+relative
            records[relative] = sha
        actual = {str(p.relative_to(origin)) for p in origin.rglob('*') if p.is_file()} - {'SHA256SUMS'}
        assert set(records) == actual and len(records) == 29, 'Incomplete native handoff hashes'
        native = json.loads((origin / 'provenance.json').read_text())
        assert native['sourceRevision'] == '69615799e0982377e99f19bc1be12b856684b030'
        assert native['ghc'] == '9.14.1' and native['platform'] == 'x86_64-linux'
        assert native['nativeBackend'] == 'GHC x86_64 NCG (default; no -fllvm)'
        assert all(command['exitCode'] == 0 for command in native['commands'])
        expected_sources = {str(p.relative_to(ROOT)) for p in (prepare.FIXTURE, prepare.NATIVE,
            *sorted((ROOT/'compiler/Thc').glob('*.hs')),
            *(ROOT/'compiler'/name for name in ('build.sh', 'export.sh', 'toolchain.sh')))}
        assert set(native['inputHashes']) == expected_sources, 'Native source inventory drift'
        for relative, sha in native['inputHashes'].items():
            assert digest(origin/'inputs'/relative) == sha == digest(ROOT/relative), 'Native source mismatch: '+relative
        wanted = model.model_rows()
        actual_rows = model.parse_rows((origin/'oracle.tsv').read_text())
        assert actual_rows == wanted, next(((key, actual_rows.get(key), value) for key, value in wanted.items()
            if actual_rows.get(key) != value), 'Unexpected additional native rows')
        audit_spec = importlib.util.spec_from_file_location('doublex2_audit', ROOT/'scripts/audit-core.py')
        auditor = importlib.util.module_from_spec(audit_spec)
        audit_spec.loader.exec_module(auditor)
        capabilities = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
        modules, audits, structure = {}, {}, {}
        for stage in ('pre', 'post'):
            path = origin/stage/'core/SimdDoubleX2.json'
            modules[stage] = json.loads(path.read_text())
            structure[stage] = prepare.inventory(modules[stage], stage)
            audits[stage] = {name: auditor.Audit([(str(path), modules[stage])], capabilities).run([name])
                            for name in [e['name'] for e in model.entries()] + ['vectorArgument']}
            assert all(audits[stage][e['name']]['accepted'] for e in model.entries()), 'Imported positive audit failed'
            frontier = audits[stage]['vectorArgument']
            assert not frontier['accepted'] and any(i['code'] == 'vector-boundary' and
                i['detail'] == 'vector formal argument' for i in frontier['issues']), 'Missing exact vector frontier'
        # Publish only after all incoming source, payload, model and audit checks pass.
        out = prepare.OUT
        out.mkdir(parents=True, exist_ok=True)
        (out/'provenance.json').unlink(missing_ok=True)
        imported = out/'imported-native'
        if imported.exists():
            shutil.rmtree(imported)
        shutil.copytree(origin, imported)
        for stage in ('pre', 'post'):
            (out/f'{stage}-core').mkdir(exist_ok=True)
            shutil.copyfile(imported/stage/'core/SimdDoubleX2.json', out/f'{stage}-core/SimdDoubleX2.json')
            (out/f'{stage}-audit.json').write_text(json.dumps(audits[stage], indent=2)+'\n')
        shutil.copyfile(imported/'oracle.tsv', out/'oracle.tsv')
        (out/'expected.tsv').write_text(''.join('\t'.join(map(str, (*key, answer)))+'\n' for key, answer in wanted.items()))
        sources = [ROOT/p for p in sorted(expected_sources)] + [Path(__file__).resolve(),
            ROOT/'scripts/prepare-doublex2-audit.py', ROOT/'scripts/doublex2_model.py', ROOT/'scripts/test-doublex2-model.py',
            ROOT/'scripts/audit-core.py', ROOT/'scripts/core-capabilities.json',
            ROOT/'src/main/resources/thc/scalar-primop-signatures.json', *sorted((ROOT/'scripts').glob('core_*.py'))]
        artifacts = [p for p in sorted(imported.rglob('*')) if p.is_file()] + [out/'oracle.tsv', out/'expected.tsv']
        artifacts += [out/f'{s}-core/SimdDoubleX2.json' for s in ('pre', 'post')]
        artifacts += [out/f'{s}-audit.json' for s in ('pre', 'post')]
        proof = dict(schema=1, vector='doublex2', stages=['pre', 'post'], nativeRows=len(actual_rows), modelRows=len(wanted),
            modelMatched=True, entries=model.entries(), positiveAuditsAccepted=True, audits=audits, structure=structure,
            sources=[prepare.record(p) for p in sources], artifacts=[prepare.record(p) for p in artifacts],
            nativeOrigin=dict(archiveSha256=args.sha256, originalProvenance=prepare.record(imported/'provenance.json'),
                originalChecksums=prepare.record(imported/'SHA256SUMS'), provenance=native),
            commands=[dict(argv=['python3', str(Path(__file__).relative_to(ROOT)), str(args.archive), '--sha256', args.sha256])],
            claim='Imported supported x86 GHC native oracle and genuine pre/post Core; locally verified source hashes, all payload hashes, exact integer binary64 model and strict audits. No local GHC native execution or hardware SIMD claim.')
        (out/'provenance.json').write_text(json.dumps(proof, indent=2)+'\n')
        print(f'Imported {len(actual_rows)} source-matched native rows; 26 positive strict audits and both exact vector frontiers passed')
        print('Compiled guest entries by stage: '+json.dumps({s: structure[s]['compiledEntriesByEntry'] for s in structure}))


if __name__ == '__main__':
    main()
