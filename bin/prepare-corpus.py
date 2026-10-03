#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Fixture rationale (119 corpus)
# Purpose: Check a broad set of ordinary Haskell semantics and integer conversions against
#   native GHC.
# Produces/consumed result: Per-group CBDs, native reference results and coverage
#   declarations.
# Cost and overlap: A shared semantic corpus has value and can absorb small duplicate
#   fixtures. Current directory-clearing acquisition is quarantined; coverage bookkeeping
#   alone is not a reason to keep every case.
# Build status: QUARANTINED: excluded from the new fixture build; see docs/fixture-quarantine.log.
# Detailed file inputs/outputs: docs/fixture-inputs.log, entry 119.

"""Export independent real-Core bundles and generate native results for the coverage corpus."""
import hashlib
from core_package_manifest import inspect_cbd
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/corpus'


def run(command, **kwargs):
    subprocess.run([str(x) for x in command], cwd=ROOT, check=True, **kwargs)


def binding_paths(value, identity, path=()):
    if isinstance(value, dict):
        if value.get('id') == identity and 'expr' in value:
            yield path
        for key, child in value.items():
            yield from binding_paths(child, identity, (*path, key))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from binding_paths(child, identity, (*path, index))


def at_path(value, path):
    for key in path:
        value = value[key]
    return value


def prepare():
    manifest = json.loads((ROOT / 't/fixtures/core/coverage.json').read_text())
    assert manifest['schema'] == 1
    groups = manifest['groups']
    assert groups, 'Empty corpus'
    seen = set()
    for group in groups:
        assert re.fullmatch(r'[a-z][a-z0-9-]*', group['id']) and group['id'] not in seen
        seen.add(group['id'])
        assert re.fullmatch(r'[A-Z][A-Za-z0-9]*(\.[A-Z][A-Za-z0-9]*)*', group['module'])
        assert group.get('sourceLibraryFrontier') in (None, 'lists')
        source = (ROOT / group['source']).resolve()
        assert source.is_relative_to(ROOT / 't/fixtures/core') and source.is_file()
        names = set()
        assert group['entries']
        for entry in group['entries']:
            assert re.fullmatch(r'[a-z][A-Za-z0-9_]*', entry['name']) and entry['name'] not in names
            names.add(entry['name'])
            assert entry['focus'].strip()
            warm, cold = entry['warmInputs'], entry['coldInputs']
            assert warm and cold and not set(warm).intersection(cold)
            assert len(warm + cold) == len(set(warm + cold))
            assert all(type(n) is int and -(2**63) <= n < 2**63 for n in warm + cold)
    BUILD.mkdir(parents=True, exist_ok=True)
    # Never let an interrupted preparation leave a stale successful run manifest.
    result_path = BUILD / 'corpus.json'
    result_path.unlink(missing_ok=True)
    spec = importlib.util.spec_from_file_location('core_audit', ROOT / 'bin/audit-core.py')
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    capabilities = json.loads((ROOT / 'bin/core-capabilities.json').read_text())
    prepared = []
    extra_artifacts = set()
    inputs = set((ROOT / 't/fixtures/core').rglob('*.hs')) | set((ROOT / 'src/compiler').rglob('*.hs'))
    inputs.update(ROOT / p for p in ['t/fixtures/core/coverage.json', 'bin/build-compiler.sh', 'bin/export-core.sh',
        'bin/toolchain.sh', 'bin/export-boot.py', 'bin/prepare-corpus.py', 'bin/audit-core.py',
        'bin/core-capabilities.json', 'src/main/resources/thc/scalar-primop-signatures.json', 'bin/check-corpus-structure.py'])
    inputs.update((ROOT / 'bin').glob('core_*.py'))
    input_hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(inputs)}
    for group in groups:
        directory = BUILD / 'groups' / group['id']
        if directory.exists():
            shutil.rmtree(directory)
        core = directory / 'core'
        env = dict(os.environ, THC_CORE_OUT=str(core), THC_GHC_OUT=str(directory / 'ghc'))
        # Each group gets its own interface closure: the plugin writes one frontier per module.
        run([ROOT / 'bin/export-core.sh', *['-fplugin-opt=THC.Plugin:closure=' + e['name']
            for e in group['entries']], group['source']], env=env)
        if frontier := group.get('sourceLibraryFrontier'):
            run([sys.executable, ROOT / 'bin/export-boot.py', '--frontier', frontier,
                 '--build-dir', directory], env=env)
            provenance_path = directory / 'boot-provenance.json'
            extra_artifacts.add(str(provenance_path.relative_to(ROOT)))
            for source in json.loads(provenance_path.read_text())['sources']:
                digest = hashlib.sha256((ROOT / source['path']).read_bytes()).hexdigest()
                assert digest == source['sha256'], 'Source provenance mismatch: ' + source['path']
                input_hashes[source['path']] = digest
        paths = sorted(core.glob('*.cbd'))
        assert paths, 'No exported modules: ' + group['id']
        modules = [(str(path.relative_to(ROOT)), inspect_cbd(path.read_bytes())) for path in paths]
        for entry in group['entries']:
            entry_id = group['id'] + '/' + entry['name']
            report = audit.Audit(modules, capabilities).run(['main:' + group['module'] + '.' + entry['name']])
            audit_path = directory / (entry['name'] + '.audit.json')
            audit_path.write_text(json.dumps(report, indent=2) + '\n')
            if not report['accepted']:
                raise RuntimeError(f'{entry_id}: unsupported reachable Core; inspect {audit_path}')
            actual_primitives = {primitive['name'] for primitive in report['primitives']}
            missing_primitives = set(entry.get('requiredPrimitives', [])) - actual_primitives
            if missing_primitives:
                raise RuntimeError(f'{entry_id}: intended primitives did not survive optimization: {sorted(missing_primitives)}')
            missing_literals = set(entry.get('requiredLiteralKinds', [])) - {lit['kind'] for lit in report['literals']}
            if missing_literals:
                raise RuntimeError(f'{entry_id}: intended literal kinds did not survive optimization: {sorted(missing_literals)}')
            prepared.append(dict(entry, id=entry_id, sharedBindingPaths={}, entry='main:' + group['module'] + '.' + entry['name'], modules=[p for p, _ in modules],
                                 audit=str(audit_path.relative_to(ROOT))))
    run([sys.executable, ROOT / 'bin/check-corpus-structure.py'])
    facts = json.loads((BUILD / 'structure.json').read_text())['facts']
    for entry in prepared:
        if not entry.get('sharedBindings'):
            continue
        assert len(entry['sharedBindings']) == 1, 'Expected one shared role: ' + entry['id']
        witnesses = [fact for fact in facts if fact.get('entry') == entry['entry'] and
                     fact['kind'] in ('sharedLazyBinding', 'sharedUnpackedUnsignedRecords')]
        assert len(witnesses) == 1, 'Ambiguous structural sharing witness: ' + entry['id']
        witness = witnesses[0]
        identity = witness.get('local', witness.get('binding'))
        module_path = BUILD / 'groups' / entry['id'].split('/')[0] / 'core' / (entry['entry'].split(':')[1].rsplit('.', 1)[0] + '.cbd')
        module = inspect_cbd(module_path.read_bytes())
        owners = [binding for binding in module['bindings'] if binding['id'] == entry['entry']]
        assert len(owners) == 1, 'Ambiguous sharing entry: ' + entry['entry']
        paths = list(binding_paths(owners[0]['expr'], identity))
        assert len(paths) == 1, 'Ambiguous shared executable binding: ' + identity
        path = ['expr', *paths[0]]
        binding = at_path(owners[0], path)
        assert binding['arity'] == 0 and binding['rep']['evaluated'] is False
        assert identity.startswith('@local/') and binding['name'] == identity
        entry['sharedBindingPaths'][entry['sharedBindings'][0]] = dict(path=path, compactId=identity)
    driver = BUILD / 'NativeCorpus.hs'
    lines = ['{-# LANGUAGE MagicHash #-}', 'module Main (main) where',
             'import GHC.Exts (Int(I#), Int#)']
    lines += [f'import qualified {g["module"]} as C{i}' for i, g in enumerate(groups)]
    lines += ['emit :: String -> (Int# -> Int#) -> [Int] -> IO ()',
              'emit name f = mapM_ (\\input@(I# n) -> putStrLn (name ++ "\\t" ++ show input ++ "\\t" ++ show (I# (f n))))',
              'main :: IO ()', 'main = do']
    for i, group in enumerate(groups):
        for entry in group['entries']:
            lines.append(f'  emit "{group["id"]}/{entry["name"]}" C{i}.{entry["name"]} ' +
                         '[' + ','.join(str(n) for n in entry['warmInputs'] + entry['coldInputs']) + ']')
    driver.write_text('\n'.join(lines) + '\n')
    native_dir = BUILD / 'native'
    native_dir.mkdir(exist_ok=True)
    ghc = os.environ.get('GHC', 'ghc')
    executable = native_dir / 'corpus-oracle'
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint',
         '-i' + str(ROOT / 't/fixtures/core'), '-odir', native_dir, '-hidir', native_dir,
         driver, '-o', executable])
    # The fixture inputs are deliberately bounded. A divergent oracle is a failed preparation.
    completed = subprocess.run([str(executable)], check=True, text=True, capture_output=True, timeout=60)
    expected = {}
    for line in completed.stdout.splitlines():
        entry, raw_input, raw_result = line.split('\t')
        key = (entry, int(raw_input))
        assert key not in expected, 'Duplicate oracle row: ' + repr(key)
        expected[key] = int(raw_result)
    wanted = {(e['id'], n) for e in prepared for n in e['warmInputs'] + e['coldInputs']}
    assert set(expected) == wanted, 'Native oracle rows disagree with corpus manifest'
    (BUILD / 'oracle.tsv').write_text(completed.stdout)
    for entry in prepared:
        entry['expected'] = {str(n): expected[(entry['id'], n)] for n in entry['warmInputs'] + entry['coldInputs']}
    result_path.write_text(json.dumps(dict(schema=1, entries=prepared,
        inputHashes=input_hashes,
        artifactHashes={path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest()
                        for path in sorted({p for entry in prepared for p in entry['modules']} | extra_artifacts |
                                           {'build/corpus/oracle.tsv', 'build/corpus/structure.json'})},
        sourceManifestSha256=hashlib.sha256((ROOT / 't/fixtures/core/coverage.json').read_bytes()).hexdigest()), indent=2) + '\n')
    print(f'Prepared {len(prepared)} strict Core entries / {len(expected)} native oracle rows')


if __name__ == '__main__':
    prepare()
