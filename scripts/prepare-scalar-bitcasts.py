#!/usr/bin/env python3
"""Genuine scalar IEEE bitcasts, exact retained calls and integer-only native oracle."""
from collections import Counter
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
from scalar_bitcast_model import ENTRIES, PRIMITIVES, expected, inputs

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT/'build/scalar-bitcasts'
SOURCE = ROOT/'compiler/test-fixtures/ScalarBitCastAudit.hs'
CALLS = {name: next(n for suffix, n in [('Roundtrip', 5), ('Field', 6), ('Captured', 7), ('Decode', 4), ('Encode', 4)]
                   if name.endswith(suffix)) for name in ENTRIES}


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def expressions(value, tag):
    return [v for v in walk(value) if isinstance(v, list) and v[:1] == [tag]]


def structure(module, report, name):
    bindings = {b['id']: b for b in module['bindings']}
    reachable = [bindings[b['id']] for b in report['reachableBindings']]
    functions = [b for b in reachable if b['expr'][0] == 'lam']
    root, = report['roots']
    global_calls = Counter()
    dynamic_calls = 0
    immediate_calls = 0
    nested = 0
    for binding in functions:
        body = binding['expr']
        nested += len(expressions(body, 'lam'))-1
        assert all(len(c[3]) == 1 for c in expressions(body, 'case')), 'New conditional guest path'
        for call in expressions(body, 'app'):
            if call[1][0] == 'lam': immediate_calls += 1
            if call[1][0] == 'var':
                ident = call[1][1]
                if ident in bindings: global_calls[ident] += 1
                else: dynamic_calls += 1
    assert global_calls == Counter({b['id']: 1 for b in functions if b['id'] != root}), (name, global_calls)
    assert dynamic_calls == (1 if name.endswith('Captured') else 0), name
    assert immediate_calls == (1 if name.endswith(('Decode','Encode')) else 0), name
    assert nested == dynamic_calls + immediate_calls, name
    assert len(functions)+nested == CALLS[name]
    cold = [b for b in reachable if b not in functions]
    assert [b['name'] for b in cold] == (['bottom'] if name.endswith('Field') else [])
    if cold: assert cold[0]['expr'][:2] == ['var', cold[0]['id']]
    family = ('castWord32ToFloat#', 'castFloatToWord32#') if name.startswith('float') else ('castWord64ToDouble#', 'castDoubleToWord64#')
    required = {family[0]} if name.endswith('Decode') else {family[1]} if name.endswith('Encode') else set(family)
    actual = {p['name']: len(p['uses']) for p in report['primitives'] if p['name'] in PRIMITIVES}
    assert actual == {p: 1 for p in required}, (name, actual)
    return dict(guestCalls=len(functions)+nested, globalFunctions=[b['id'] for b in functions],
                nestedCallbacks=dynamic_calls, stateLambdas=immediate_calls, coldBottom=[b['id'] for b in cold], primitiveUses=actual)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT/'manifest.json').unlink(missing_ok=True)
    ghc = os.environ.get('GHC', 'ghc')
    def run(args, **kwargs): return subprocess.run([str(a) for a in args], cwd=ROOT, check=True, **kwargs)
    assert run([ghc, '--numeric-version'], text=True, capture_output=True).stdout.strip() == '9.14.1'
    spec = importlib.util.spec_from_file_location('scalar_bitcast_audit', ROOT/'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
    cap = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
    stages, reports, structures, artifacts = {}, {}, {}, []
    for stage in ('pre', 'post'):
        path = OUT/f'{stage}-core/ScalarBitCastAudit.json'
        options = ['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []
        run([ROOT/'compiler/export.sh', *options, SOURCE], env=dict(os.environ,
            THC_CORE_OUT=str(path.parent), THC_GHC_OUT=str(OUT/f'{stage}-ghc')))
        module = json.loads(path.read_text()); stages[stage] = str(path.relative_to(ROOT)); artifacts.append(path)
        stage_reports = {}
        for name in ENTRIES:
            report = audit.Audit([(str(path), module)], cap).run([name])
            assert report['accepted'] and not report['issues'] and not report['missingGlobals'], (stage, name, report)
            structures[stage+'/'+name] = structure(module, report, name)
            reports[stage+'/'+name] = report['summary']; stage_reports[name] = report
        report_path = OUT/f'{stage}-audit.json'
        report_path.write_text(json.dumps(stage_reports, indent=2)+'\n'); artifacts.append(report_path)
    driver = ['{-# LANGUAGE MagicHash #-}', 'module Main where', 'import GHC.Exts', 'import qualified ScalarBitCastAudit as P',
              'emit :: String -> (Int# -> Int#) -> Int -> IO ()',
              'emit n f x@(I# a) = putStrLn (n ++ "\\t" ++ show x ++ "\\t" ++ show (I# (f a)))',
              'dispatch [name,x] = case name of']
    driver += [f'  "{name}" -> emit name P.{name} (read x)' for name in ENTRIES]
    driver += ['  _ -> error "entry"', 'dispatch _ = error "input"', 'main = getContents >>= mapM_ (dispatch . words) . lines']
    native_source = OUT/'NativeScalarBitCast.hs'; native_source.write_text('\n'.join(driver)+'\n')
    native = OUT/'native'; native.mkdir(exist_ok=True); binary = native/'scalar-bitcast-oracle'
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', '-i'+str(SOURCE.parent),
         '-odir', native, '-hidir', native, native_source, '-o', binary])
    requests = [(n, x) for n in ENTRIES for x in inputs(32 if n.startswith('float') else 64)]
    result = run([binary], input=''.join(f'{n}\t{x}\n' for n,x in requests), text=True, capture_output=True)
    rows = [line.split('\t') for line in result.stdout.splitlines()]
    assert [(n, int(x)) for n,x,_ in rows] == requests
    for name, x, value in rows: assert int(value) == expected(name, int(x)), (name, x, value)
    oracle = OUT/'oracle.tsv'; oracle.write_text(result.stdout); artifacts += [native_source, binary, oracle]
    sources = [SOURCE, Path(__file__), ROOT/'scripts/scalar_bitcast_model.py', ROOT/'scripts/test-scalar-bitcasts.py',
               ROOT/'scripts/core-capabilities.json', ROOT/'scripts/audit-core.py', ROOT/'scripts/generate-scalar-signatures.py',
               ROOT/'src/main/resources/thc/scalar-primop-signatures.json', *sorted((ROOT/'scripts').glob('core_*.py')),
               *sorted((ROOT/'compiler/THC').glob('*.hs')), *[ROOT/'compiler'/n for n in ('build.sh','export.sh','toolchain.sh')]]
    hashes = lambda ps: {str(p.resolve().relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in ps}
    manifest = dict(schema=1, ghc='9.14.1', entries=ENTRIES, stages=stages, nativeRows=len(rows),
                    inputsByWidth={str(w):inputs(w) for w in (32,64)}, expectedGuestCalls=CALLS,
                    audits=reports, structure=structures, inputHashes=hashes(sources), artifactHashes=hashes(artifacts),
                    claim='Exact integer bits, including NaN payload/signalling/sign and signed zero; no Float equality or arithmetic NaN claim.')
    (OUT/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print(f'Prepared {len(rows)} exact native bit rows, 10 entries, pre/post strict audits and retained guest counts')


if __name__ == '__main__': main()
