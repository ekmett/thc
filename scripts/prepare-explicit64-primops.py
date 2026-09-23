#!/usr/bin/env python3
"""Prepare exact Int64#/Word64# scalar primitives, native results and an integer model."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import random
import subprocess

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/explicit64-primops'
SOURCE = 'compiler/test-fixtures/Explicit64PrimopsAudit.hs'
MODULUS = 1 << 64
MINIMUM = -(1 << 63)
MAXIMUM = (1 << 63) - 1


def entries():
    result = []
    def add(name, args, output, operation, unsigned=False):
        result.append(dict(name=name, primitive=name+'#', arguments=args, result=output,
                           operation=operation, unsigned=unsigned, arity=len(args)))
    for name, source, target in [('int64ToWord64', 'Int64Rep', 'Word64Rep'),
            ('word64ToInt64', 'Word64Rep', 'Int64Rep'), ('wordToWord64', 'WordRep', 'Word64Rep'),
            ('word64ToWord', 'Word64Rep', 'WordRep')]:
        add(name, [source], target, 'identity')
    for kind in ('Int', 'Word'):
        rep = kind+'64Rep'
        for op in ('plus', 'sub', 'times', 'quot', 'rem', 'eq', 'ne', 'lt', 'le', 'gt', 'ge'):
            add(op+kind+'64', [rep, rep], 'IntRep' if op in ('eq', 'ne', 'lt', 'le', 'gt', 'ge') else rep, op, kind == 'Word')
    add('negateInt64', ['Int64Rep'], 'Int64Rep', 'negate')
    for op in ('and', 'or', 'xor', 'not'):
        add(op+'64', ['Word64Rep']*(1 if op == 'not' else 2), 'Word64Rep', op, True)
    for name, rep, op in [('uncheckedIShiftL64', 'Int64Rep', 'shiftL'),
            ('uncheckedIShiftRA64', 'Int64Rep', 'shiftRA'), ('uncheckedIShiftRL64', 'Int64Rep', 'shiftRL'),
            ('uncheckedShiftL64', 'Word64Rep', 'shiftL'), ('uncheckedShiftRL64', 'Word64Rep', 'shiftRL')]:
        add(name, [rep, 'IntRep'], rep, op, rep == 'Word64Rep')
    assert len(result) == 36
    result += [dict(name='word64Literals', primitive=None, arguments=['IntRep'], result='Word64Rep', operation='literals', unsigned=False, arity=1),
               dict(name='word64Case', primitive=None, arguments=['Word64Rep'], result='IntRep', operation='case', unsigned=True, arity=1)]
    return result


def signed(value):
    return (value - MINIMUM) % MODULUS + MINIMUM


def mathematical(entry, left, right):
    op = entry['operation']
    x, y = (left % MODULUS, right % MODULUS) if entry['unsigned'] else (left, right)
    if op == 'identity': result = x
    elif op == 'literals': result = {0: 0, 1: MAXIMUM, 2: MODULUS-1}.get(x, 1 << 63)
    elif op == 'case': result = {0: 11, 1 << 63: 13, MODULUS-1: 17}.get(x, 19)
    elif op == 'negate': result = -x
    elif op == 'plus': result = x+y
    elif op == 'sub': result = x-y
    elif op == 'times': result = x*y
    elif op in ('quot', 'rem'):
        assert y != 0 and (entry['unsigned'] or (x, y) != (MINIMUM, -1))
        q = abs(x)//abs(y)*(-1 if (x < 0) != (y < 0) else 1)
        result = q if op == 'quot' else x-q*y
    elif op in ('eq', 'ne', 'lt', 'le', 'gt', 'ge'):
        result = int(dict(eq=x==y, ne=x!=y, lt=x<y, le=x<=y, gt=x>y, ge=x>=y)[op])
    elif op == 'and': result = x & y
    elif op == 'or': result = x | y
    elif op == 'xor': result = x ^ y
    elif op == 'not': result = ~x
    elif op == 'shiftL': result = x << right
    elif op == 'shiftRA': result = left >> right
    elif op == 'shiftRL': result = (left % MODULUS) >> right
    else: raise AssertionError(op)
    return signed(result)


def samples():
    values = {MINIMUM, MINIMUM+1, MAXIMUM, MAXIMUM-1, -1, 0, 1, 2, 3, -4097, 4097,
              0x5555555555555555, signed(0xaaaaaaaaaaaaaaaa)}
    for bit in (1, 7, 8, 15, 16, 31, 32, 62, 63):
        values.update(signed(sign*(1 << bit)+delta) for sign in (-1, 1) for delta in (-1, 0, 1))
    rng = random.Random(641491)
    values.update(signed(rng.getrandbits(64)) for _ in range(24))
    return sorted(values)


def operands(entry):
    values = samples(); op = entry['operation']
    if entry['arity'] == 1: return [(x, 0) for x in values]
    if op.startswith('shift'): return [(x, n) for x in values for n in range(64)]
    anchors = (MINIMUM, MINIMUM+1, MAXIMUM, -4097, -1, 0, 1, 3, 4097)
    pairs = {(x, y) for x in values for y in anchors} | {(x, y) for x in anchors for y in values}
    pairs.update((x, signed(x+delta)) for x in values for delta in (-1, 0, 1))
    return [(x, y) for x, y in sorted(pairs) if op not in ('quot', 'rem') or
            y != 0 and (entry['unsigned'] or (x, y) != (MINIMUM, -1))]


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def hashes(paths):
    return {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}


def verify():
    manifest = json.loads((OUT/'manifest.json').read_text())
    module = json.loads((OUT/'core/Explicit64PrimopsAudit.json').read_text())
    assert module['ghc'] == '9.14.1'
    spec = importlib.util.spec_from_file_location('audit_core', ROOT/'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
    capabilities = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
    bindings = {b['name']: b for b in module['bindings']}
    for entry in manifest['entries']:
        name = entry['name']; body = bindings[name]['expr']
        assert body[0] == 'lam'
        assert [b['rep']['primReps'] for b in body[1]] == [[r] for r in entry['arguments']], name
        assert body[3]['resultRep']['primReps'] == [entry['result']], name
        if entry['primitive']:
            apps = [v for v in walk(body) if isinstance(v, list) and len(v) > 6 and v[0] == 'app' and
                    isinstance(v[1], list) and v[1][:2] == ['prim', entry['primitive']]]
            assert len(apps) == 1, (name, 'primitive erased or duplicated')
            app = apps[0]
            assert [audit.Audit.expression_rep(a)['primReps'] for a in app[2]] == [[r] for r in entry['arguments']], name
            assert app[6]['rep']['primReps'] == [entry['result']], name
        report = audit.Audit([('Explicit64PrimopsAudit.json', module)], capabilities).run([name])
        assert report['accepted'], report['issues']
        (OUT/(name+'.audit.json')).write_text(json.dumps(report, indent=2)+'\n')
    assert any(isinstance(v, list) and v[:2] == ['lit', 'word64'] for v in walk(bindings['word64Literals']['expr']))
    expected = {(e['name'], x, y): mathematical(e, x, y) for e in entries() for x, y in operands(e)}
    rows = [line.split('\t') for line in (OUT/'oracle.tsv').read_text().splitlines()]
    actual = {(name, int(x), int(y)): int(result) for name, x, y, result in rows}
    assert len(actual) == len(rows) and actual == expected, 'Native/model mismatch'
    print(f'Verified 36 explicit64 primops + 2 literal controls / {len(rows)} native/model rows')


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check-only', action='store_true'); args=parser.parse_args()
    manifest = OUT/'manifest.json'
    if args.check_only:
        data=json.loads(manifest.read_text())
        for group in ('inputHashes', 'artifactHashes'):
            assert hashes([ROOT/p for p in data[group]]) == data[group], f'Stale {group}'
        verify(); return
    OUT.mkdir(parents=True, exist_ok=True); manifest.unlink(missing_ok=True)
    ghc = os.environ.get('GHC', 'ghc')
    assert subprocess.check_output([ghc, '--numeric-version'], text=True).strip() == '9.14.1'
    commands=[]
    def run(command, **kwargs):
        command=list(map(str,command));commands.append(command)
        return subprocess.run(command, cwd=ROOT, check=True, **kwargs)
    run(['compiler/export.sh', SOURCE], env=dict(os.environ, THC_CORE_OUT=str(OUT/'core'), THC_GHC_OUT=str(OUT/'ghc')))
    driver=['{-# LANGUAGE MagicHash #-}', 'module Main where', 'import GHC.Exts', 'import qualified Explicit64PrimopsAudit as P']
    incoming={'IntRep':lambda x:x, 'WordRep':lambda x:f'(int2Word# {x})', 'Int64Rep':lambda x:f'(intToInt64# {x})',
              'Word64Rep':lambda x:f'(wordToWord64# (int2Word# {x}))'}
    outgoing={'IntRep':lambda x:x, 'WordRep':lambda x:f'word2Int# ({x})', 'Int64Rep':lambda x:f'int64ToInt# ({x})',
              'Word64Rep':lambda x:f'word2Int# (word64ToWord# ({x}))'}
    for e in entries():
        args=['x','y'][:e['arity']]
        driver += [f"host_{e['name']} :: "+' -> '.join(['Int#']*(e['arity']+1)),
                   f"host_{e['name']} "+' '.join(args)+' = '+outgoing[e['result']](f"P.{e['name']} "+' '.join(incoming[r](a) for r,a in zip(e['arguments'],args)))]
    driver += ['emit1 :: String -> (Int# -> Int#) -> Int -> IO ()',
        'emit1 n f x@(I# a) = putStrLn (n ++ "\\t" ++ show x ++ "\\t0\\t" ++ show (I# (f a)))',
        'emit2 :: String -> (Int# -> Int# -> Int#) -> Int -> Int -> IO ()',
        'emit2 n f x@(I# a) y@(I# b) = putStrLn (n ++ "\\t" ++ show x ++ "\\t" ++ show y ++ "\\t" ++ show (I# (f a b)))',
        'dispatch :: [String] -> IO ()', 'dispatch [name,x,y] = case name of']
    driver += [f'  "{e["name"]}" -> emit{e["arity"]} name host_{e["name"]} (read x)'+(' (read y)' if e['arity']==2 else '') for e in entries()]
    driver += ['  _ -> error "unknown operation"', 'dispatch _ = error "invalid input"', 'main = getContents >>= mapM_ (dispatch . words) . lines']
    native=OUT/'native';native.mkdir(exist_ok=True);source=OUT/'NativeExplicit64.hs';source.write_text('\n'.join(driver)+'\n');executable=native/'explicit64-oracle'
    run([ghc,'--make','-O2','-fforce-recomp','-dcore-lint','-dstg-lint','-i'+str(ROOT/'compiler/test-fixtures'),'-odir',native,'-hidir',native,source,'-o',executable])
    requests=''.join(f'{e["name"]}\t{x}\t{y}\n' for e in entries() for x,y in operands(e))
    result=run([executable],input=requests,text=True,capture_output=True,timeout=60);(OUT/'oracle.tsv').write_text(result.stdout)
    inputs=[ROOT/p for p in [SOURCE,'scripts/prepare-explicit64-primops.py','scripts/core-capabilities.json', 'src/main/resources/thc/scalar-primop-signatures.json','scripts/audit-core.py','scripts/core_vectors.py',
                            'compiler/build.sh','compiler/export.sh','compiler/toolchain.sh']]+sorted((ROOT/'compiler/Thc').glob('*.hs'))
    artifacts=[OUT/'core/Explicit64PrimopsAudit.json', OUT/'oracle.tsv',source]
    manifest.write_text(json.dumps(dict(schema=1,ghc='9.14.1',ghcInfo=subprocess.check_output([ghc,'--info'],text=True),entries=entries(),commands=commands,
        excludedInputs=['zero divisors','signed minBound / -1 (quotient and remainder)','shift counts outside [0,64)'],
        inputHashes=hashes(inputs),artifactHashes=hashes(artifacts)),indent=2)+'\n')
    try: verify()
    except BaseException:
        manifest.unlink(missing_ok=True);raise


if __name__ == '__main__': main()
