#!/usr/bin/env python3
"""Native GHC address-bearing constructor fields, with exact aggregate frontier gates."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/address-fields'
SOURCE = 'compiler/test-fixtures/AddressFieldAudit.hs'
ENTRIES = ['natural', 'returned', 'captured', 'partial', 'twins', 'emptyLiteral', 'terminator', 'backwards']
VALUES = sorted(set(range(-8, 9)) | {-2**63, -2**63+1, 2**63-2, 2**63-1, -4097, 4097, -2**32, 2**32})

def signed(value): return (value + 2**63) % 2**64 - 2**63

def model(name, x):
    byte = [65, 255, 128, 0][x & 3]
    return signed({'natural': x+byte, 'returned': 4*x+2+byte,
        'captured': 4*x+5+byte, 'partial': 4*x+7+byte,
        'twins': x+630+byte, 'emptyLiteral': 4*x+11, 'terminator': 4*x+13, 'backwards': x+byte}[name])

def walk(value):
    if isinstance(value, list):
        yield value
        for child in value: yield from walk(child)
    elif isinstance(value, dict):
        for child in value.values(): yield from walk(child)

def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    (BUILD/'manifest.json').unlink(missing_ok=True)
    commands = []
    def run(command, env=None, **kwargs):
        command = list(map(str, command)); commands.append(dict(argv=command, environment=env or {}))
        return subprocess.run(command, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True, **kwargs)
    ghc = os.environ.get('GHC', 'ghc')
    assert run([ghc, '--numeric-version'], text=True, capture_output=True).stdout.strip() == '9.14.1'
    spec = importlib.util.spec_from_file_location('address_audit', ROOT/'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
    cap = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
    assert 'AddrRep' in cap['fieldRepresentations'] and 'AddrRep' not in cap['aggregateFieldRepresentations']
    stages, summaries, artifacts = {}, {}, []
    for stage in ('pre', 'post'):
        directory = BUILD/stage; core = directory/'core'
        run([ROOT/'compiler/export.sh', *(['-fplugin-opt=THC.Plugin:post-tidy'] if stage=='post' else []), SOURCE],
            env=dict(THC_CORE_OUT=str(core), THC_GHC_OUT=str(directory/'ghc')))
        path = core/'AddressFieldAudit.json'; module = json.loads(path.read_text()); artifacts.append(path)
        stages[stage] = str(path.relative_to(ROOT))
        assert module['boundary'] == ('optimized-Core-before-Tidy' if stage=='pre' else 'optimized-Core-after-Tidy-before-CorePrep')
        constructors = {c['name']: c for c in module['constructors']}
        for name, reps in [('Packet', [['AddrRep'], ['IntRep'], ['BoxedRep (Just Lifted)']]),
                           ('Twin', [['AddrRep'], ['AddrRep'], ['BoxedRep (Just Lifted)']])]:
            con = constructors[name]
            assert con['fieldReps'] == reps and con['fieldLifted'] == [False, False, True]
            for i, rep in enumerate(reps):
                if rep == ['AddrRep']:
                    assert con['fieldTypes'][i] == dict(kind='address', primReps=['AddrRep'], evaluated=True)
            assert con['fieldTypes'][-1]['evaluated'] is False
        bindings = {b['name']: b for b in module['bindings']}
        assert bindings['bottom']['expr'][:2] == ['var', bindings['bottom']['id']]
        # OPAQUE controls must retain actual constructor production, consumption,
        # and the captured/PAP routes; natural is intentionally free to optimize.
        assert any(n[:2] == ['con', constructors['Packet']['id']] for n in walk(bindings['makePacket']['expr']))
        assert any(n[:2] == ['data', constructors['Packet']['id']] for n in walk(bindings['readPacket']['expr']))
        assert sum(n[:1] == ['lam'] for n in walk(bindings['captured']['expr'])) >= 2
        assert sum(n[:1] == ['lam'] for n in walk(bindings['partial']['expr'])) >= 2
        captured_case = bindings['captured']['expr'][2]
        assert captured_case[0] == 'case'
        captured_lambda = next(n for n in walk(captured_case) if n[:1] == ['lam'])
        assert any(n[:2] == ['var', captured_case[2]] and n[2]['rep']['kind'] == 'data' and
                   n[2]['rep']['evaluated'] is True for n in walk(captured_lambda)), 'Evaluated constructor capture vanished'
        partial_lambda = list(n for n in walk(bindings['partial']['expr']) if n[:1] == ['lam'])[1]
        assert any(n[:1] == ['var'] and len(n) > 2 and n[2]['rep']['kind'] == 'address'
                   for n in walk(partial_lambda)), 'Address capture in eta-expanded constructor vanished'
        for name in ENTRIES + ['tupleFrontier']:
            report = audit.Audit([(str(path.relative_to(ROOT)), module)], cap).run([name])
            dest = directory/(name+'.audit.json'); dest.write_text(json.dumps(report, indent=2)+'\n'); artifacts.append(dest)
            assert not report['missingGlobals'], (stage, name, report['missingGlobals'])
            if name in ENTRIES:
                assert report['accepted'], (stage, name, report['issues'])
                if name != 'natural':
                    assert bindings['bottom']['id'] in {b['id'] for b in report['reachableBindings']}
            else:
                assert not report['accepted'] and report['issues']
                assert {i['code'] for i in report['issues']} == {'aggregate-representation'}, report['issues']
            summaries[stage+'/'+name] = report['summary']
    driver = ['{-# LANGUAGE MagicHash #-}', 'module Main where', 'import GHC.Exts (Int(I#))',
        'import qualified AddressFieldAudit as P', 'call name (I# x) = case name of']
    driver += ['  "'+name+'" -> I# (P.'+name+' x)' for name in ENTRIES]
    driver += ['  _ -> error "invalid entry"', 'emit [name,x] = putStrLn (name ++ "\\t" ++ x ++ "\\t" ++ show (call name (read x)))',
        'emit _ = error "invalid input"', 'main = getContents >>= mapM_ (emit . words) . lines']
    source = BUILD/'NativeAddressFields.hs'; source.write_text('\n'.join(driver)+'\n')
    native = BUILD/'native'; native.mkdir(exist_ok=True); executable = native/'address-fields-oracle'
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', '-i'+str(ROOT/'compiler/test-fixtures'),
         '-odir', native, '-hidir', native, source, '-o', executable])
    requests = ''.join(f'{name}\t{x}\n' for name in ENTRIES for x in VALUES)
    expected = ''.join(f'{name}\t{x}\t{model(name,x)}\n' for name in ENTRIES for x in VALUES)
    result = run([executable], input=requests, text=True, capture_output=True, timeout=30)
    assert result.stdout == expected, 'Native/independent bounded byte-index model mismatch'
    (BUILD/'oracle.tsv').write_text(result.stdout); (BUILD/'expected.tsv').write_text(expected)
    inputs = [ROOT/SOURCE, Path(__file__).resolve(), ROOT/'scripts/audit-core.py', ROOT/'scripts/core-capabilities.json',
        ROOT/'src/main/resources/thc/scalar-primop-signatures.json', *sorted((ROOT/'scripts').glob('core_*.py')),
        *sorted((ROOT/'compiler/THC').glob('*.hs')), *[ROOT/'compiler'/n for n in ('build.sh', 'export.sh', 'toolchain.sh')]]
    artifacts += [source, executable, BUILD/'oracle.tsv', BUILD/'expected.tsv']
    def hashes(paths): return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths))}
    (BUILD/'manifest.json').write_text(json.dumps(dict(schema=1, ghc='9.14.1', wordBits=64,
        entries=ENTRIES, values=VALUES, stages=stages, audits=summaries, nativeRows=len(ENTRIES)*len(VALUES),
        allNativeValuesMatchIndependentModel=True, inputHashes=hashes(inputs), artifactHashes=hashes(artifacts), commands=commands,
        ghcInfo=run([ghc, '--info'], text=True, capture_output=True).stdout,
        limitations=['Managed immutable literal allocations only; no foreign pointer or raw address conversion.',
                    'AddrRep tuple/sum fields remain unsupported; constructor fields do not extend aggregate ABIs.',
                    'Native reads stay within the original literal including its final NUL; invalid offsets are JVM-only controls.']), indent=2)+'\n')
    print(f'Prepared address fields: {len(ENTRIES)*len(VALUES)} native/model rows; {2*len(ENTRIES)} accepted and 2 rejected pre/post audits')

if __name__ == '__main__': main()
