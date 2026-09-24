#!/usr/bin/env python3
"""Prepare the bounded generated SIMD experiment; repository capability stays unchanged.

Use --export-only for pre-Tidy Core and model on hosts without native GHC SIMD
code generation. A full run requires an x86 GHC9.14.1 host able to execute the
selected fixed 512-bit instructions; explicit --ghc-option flags are recorded.
"""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess

from simd_family_model import entries, rows

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/simd-families'
GENERATED = ROOT / 'build/generated/simd/fixtures'


def load_script(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def check(value, message):
    if not value:
        raise RuntimeError(message)


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def structure(module, audits, primitives):
    seen = Counter()
    calls = {}
    for name, family, operation in entries():
        report = audits[name]
        check(report['accepted'] and not report['missingGlobals'] and not report['issues'], f'{name}: strict experimental audit failed: {report["issues"][:3]}')
        identities = {b['id']: b['name'] for b in module['bindings']}
        reachable = {identities[b['id']] for b in report['reachableBindings']}
        observer = {'FloatRep': 'bitsFloat', 'DoubleRep': 'bitsDouble'}.get(family['laneRep'])
        expected_reachable = {name, name + 'Worker'} | ({observer} if observer else set())
        check(reachable == expected_reachable, f'{name}: actual OPAQUE scalar worker closure changed: {reachable}')
        selected = [b for b in module['bindings'] if b['name'] in reachable]
        for binding in selected:
            expression = binding['expr']
            observer_binding = binding['name'] == observer
            check(expression[0] == 'lam' and len(expression[1]) == (1 if observer_binding else 3), f'{name}: expected scalar arity')
            expected_rep = family['laneRep'] if observer_binding else 'IntRep'
            check(all(a['rep'].get('primReps') == [expected_rep] for a in expression[1]), f'{name}: scalar boundary changed')
            check(expression[-1].get('resultRep', {}).get('primReps') == ['IntRep'], f'{name}: scalar result changed')
        used = {p['name'] for p in report['primitives']}
        check(operation + family['name'] + '#' in used and 'unpack' + family['name'] + '#' in used,
              f'{name}: operation or unpack eliminated')
        if operation != 'broadcast':
            check('pack' + family['name'] + '#' in used, f'{name}: pack eliminated')
        seen.update(used & primitives)
        calls[name] = len(expected_reachable)
    check(set(seen) == primitives, f'Selected operations not retained: {primitives - set(seen)}')
    return dict(expectedGuestCalls=calls, retainedPrimitives=dict(sorted(seen.items())))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--export-only', action='store_true')
    parser.add_argument('--ghc-option', action='append', default=[])
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    if args.check_only:
        manifest = json.loads((OUT / 'manifest.json').read_text())
        for item in manifest['inputs'] + manifest['artifacts']:
            check(record(ROOT / item['path']) == item, 'Stale generated SIMD input/artifact: ' + item['path'])
        print('SIMD family input/artifact hashes match')
        return
    ghc = os.environ.get('GHC', 'ghc')
    check(subprocess.check_output([ghc, '--numeric-version'], text=True).strip() == '9.14.1', 'Requires GHC9.14.1')
    OUT.mkdir(parents=True, exist_ok=True)
    commands = []
    def run(argv, env=None, **kwargs):
        argv = list(map(str, argv))
        commands.append(dict(argv=argv, environment=env or {}))
        return subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True, **kwargs)
    run(['python3', 'scripts/generate-simd-families.py', '--check', '--verify-ghc'])
    generator = load_script('simd_families_generator', ROOT / 'scripts/generate-simd-families.py')
    contracts = generator.contracts(generator.families())
    (OUT / 'contracts.json').write_text(json.dumps(contracts, indent=2) + '\n')
    # This explicitly labelled profile permits the experiment's strict audits;
    # it is not the repository's advertised support declaration.
    capabilities = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    capabilities['primitives'].update({name: contract['arity'] for name, contract in contracts.items()})
    (OUT / 'experimental-capabilities.json').write_text(json.dumps(capabilities, indent=2) + '\n')
    expected = list(rows())
    expected_text = ''.join('\t'.join(map(str, row)) + '\n' for row in expected)
    requests = ''.join('\t'.join(map(str, row[:-1])) + '\n' for row in expected)
    (OUT / 'expected.tsv').write_text(expected_text)
    (OUT / 'inputs.tsv').write_text(requests)
    run(['compiler/build.sh'])
    auditor = load_script('simd_families_auditor', ROOT / 'scripts/audit-core.py')
    stages = ['pre'] if args.export_only else ['pre', 'post']
    artifacts = [OUT / name for name in ('contracts.json', 'experimental-capabilities.json', 'expected.tsv', 'inputs.tsv')]
    structures = {}
    for stage in stages:
        module_path = OUT / f'{stage}-core/GeneratedSimdFamilies.json'
        options = ['-fno-code', '-fwrite-if-simplified-core'] if args.export_only else list(args.ghc_option)
        if stage == 'post':
            options += ['-fplugin-opt=THC.Plugin:post-tidy']
        run(['compiler/export.sh', *options, GENERATED / 'GeneratedSimdFamilies.hs'],
            env=dict(THC_CORE_OUT=str(module_path.parent), THC_GHC_OUT=str(OUT / f'{stage}-ghc'), THC_SOURCE_NOTES='true'))
        module = json.loads(module_path.read_text())
        reports = {name: auditor.Audit([(str(module_path), module)], capabilities).run([name]) for name, _, _ in entries()}
        structures[stage] = structure(module, reports, set(contracts))
        audit_path = OUT / f'{stage}-audits.json'
        audit_path.write_text(json.dumps(reports, indent=2) + '\n')
        artifacts += [module_path, audit_path]
    native_rows = None
    if not args.export_only:
        native = OUT / 'native'
        native.mkdir(exist_ok=True)
        binary = native / 'simd-families-oracle'
        run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', *args.ghc_option,
             '-i' + str(GENERATED), '-odir', native, '-hidir', native, '-o', binary,
             GENERATED / 'GeneratedSimdFamiliesNative.hs'])
        actual = run([binary], input=requests, text=True, capture_output=True, timeout=120).stdout
        check(actual == expected_text, 'Native SIMD rows disagree with independent modular/rational model')
        (OUT / 'oracle.tsv').write_text(actual)
        artifacts += [OUT / 'oracle.tsv', binary]
        native_rows = len(expected)
    sources = [ROOT / 'scripts' / name for name in ('simd-families.json', 'generate-simd-families.py',
               'simd_family_model.py', 'prepare-simd-families.py', 'core-capabilities.json', 'audit-core.py')]
    sources += sorted((ROOT / 'scripts').glob('core_*.py'))
    sources += sorted((ROOT / 'compiler/THC').glob('*.hs'))
    sources += [ROOT / 'compiler' / name for name in ('build.sh', 'export.sh', 'toolchain.sh')]
    sources += sorted(GENERATED.glob('*.hs'))
    sources += [ROOT / 'src/main/resources/thc/scalar-primop-signatures.json']
    manifest = dict(schema=1, scope=f'{len(contracts)} experimental local SIMD operations; no vector ABI or advertised capability change',
                    stages=stages, modelRows=len(expected), nativeRows=native_rows,
                    structures=structures, entries=[dict(name=n, lanes=f['lanes'], operation=o) for n, f, o in entries()],
                    inputs=[record(p) for p in sources], artifacts=[record(p) for p in artifacts], commands=commands,
                    toolchain=dict(ghc='9.14.1', info=subprocess.check_output([ghc, '--info'], text=True),
                        executableSha256=hashlib.sha256(Path(shutil.which(ghc) or ghc).resolve().read_bytes()).hexdigest(),
                        machine=platform.machine(), system=platform.platform()))
    (OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'SIMD families: {len(expected)} model rows, {native_rows} native rows, {len(stages)*len(entries())} strict experimental audits')


if __name__ == '__main__':
    main()
