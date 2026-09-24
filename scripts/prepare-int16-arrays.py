#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Pinned public Int16/Word16 arrays and typed/byte-alias native evidence."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/int16-arrays'
MASK = (1 << 64)-1
MASK16 = (1 << 16)-1
PATTERN = 0x55aa
GROUPS = [dict(source='examples/THC/Unboxed16Arrays.hs', module='THC.Unboxed16Arrays',
               entries=['unboxedInt16Accum', 'unboxedInt16ST', 'unboxedWord16Accum', 'unboxedWord16ST']),
          dict(source='compiler/test-fixtures/Int16ArrayAudit.hs', module='Int16ArrayAudit',
               entries=['aliasInt16Bytes', 'aliasWord16Bytes'])]
ENTRIES = [name for group in GROUPS for name in group['entries']]
LITERAL_ENTRIES = {'noinlineInt16Literal': ('int16', -32768),
                   'noinlineWord16Literal': ('word16', 65535)}
LITERAL_INPUTS = [-(1 << 63), -32768, -1, 0, 1, 32767, (1 << 63)-1]
REQUIRED = {}
EXACT_ALIAS = {}
for kind in ('Int16', 'Word16'):
    memory = {'newByteArray#', 'unsafeFreezeByteArray#', 'read'+kind+'Array#',
              'write'+kind+'Array#', 'index'+kind+'Array#'}
    conversions = {'intToInt16#', 'int16ToInt#'} if kind == 'Int16' else {'wordToWord16#', 'word16ToWord#', 'int2Word#', 'word2Int#'}
    for suffix in ('Accum', 'ST'):
        REQUIRED['unboxed'+kind+suffix] = memory | conversions | {'plus'+kind+'#'}
    REQUIRED['unboxed'+kind+'ST'] |= {'sub'+kind+'#', 'times'+kind+'#'}
    counts = {'newByteArray#': 1, 'unsafeFreezeByteArray#': 1, 'write'+kind+'Array#': 2,
              'read'+kind+'Array#': 3, 'index'+kind+'Array#': 2, 'writeWord8Array#': 2,
              'indexWord8Array#': 4, '*#': 9, '+#': 10, 'xorI#': 1,
              'word8ToWord#': 4, 'wordToWord8#': 2}
    counts.update({'int2Word#': 2, 'word2Int#': 4, 'intToInt16#': 2, 'int16ToInt#': 5} if kind == 'Int16'
                  else {'int2Word#': 4, 'word2Int#': 9, 'wordToWord16#': 2, 'word16ToWord#': 5})
    EXACT_ALIAS['alias'+kind+'Bytes'] = counts
    REQUIRED['alias'+kind+'Bytes'] = set(counts)
BOUNDARIES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def signed(x):
    return ((x+(1 << 63)) & MASK)-(1 << 63)


def lane(x, unsigned):
    bits = x & MASK16
    return bits if unsigned or bits < (1 << 15) else bits-(1 << 16)


def inputs():
    values = set(range(-16, 17)) | {-(1 << 63), -(1 << 63)+1, (1 << 63)-2, (1 << 63)-1}
    values |= {signed(sign*((1 << bit)+delta)) for bit in range(64) for delta in (-1, 0, 1) for sign in (-1, 1)}
    values |= {signed(x) for x in (0x5555555555555555, 0xaaaaaaaaaaaaaaaa, 0x55aa55aa55aa55aa,
                                  0xaa55aa55aa55aa55, 0x0123456789abcdef, 0xfedcba9876543210,
                                  0x8000000080000000, 0xffffffff00000000, 0x800000007fffffff,
                                  0x7fffffff80000000, 0xffffffff7fffffff, 0x0000000100000001,
                                  0x12345678abcdef01, 0x80008000, 0xffff0000, 0x80007fff,
                                  0x7fff8000, 0xffff7fff, 0x00010001, 0x12345678abcd8000)}
    return sorted(values)


def mathematical(name, raw, byte_order=sys.byteorder):
    check(name in ENTRIES, 'Unknown entry: '+name)
    check(-(1 << 63) <= raw < (1 << 63), 'Requires signed 64-bit input')
    unsigned = 'Word16' in name
    u = raw & MASK16
    decode = lambda value: lane(value, unsigned)
    if name.endswith('Accum'):
        # Duplicate accumulation at -3, then widen each observed cell separately.
        cells = [decode(u+1), decode(u+5), decode(2*u)]
        return 7*cells[0] + 11*cells[1] + 13*cells[2]
    if name.endswith('ST'):
        cells = [decode(u), decode(u+7), decode(2*u+21)]
        return 7*cells[0] + 11*cells[1] + 13*cells[2]
    check(byte_order in ('little', 'big'), 'Unknown native byte order')
    storage = bytearray(u.to_bytes(2, byte_order) + (u ^ PATTERN).to_bytes(2, byte_order))
    before = decode(u)
    storage[1], storage[2] = (raw+101) & 255, (raw+37) & 255
    first, second = [int.from_bytes(storage[i:i+2], byte_order, signed=not unsigned) for i in (0, 2)]
    # Distinct weights retain pre-write read, post-write reads, immutable indexes,
    # and four byte observations. The maximum result is safely within signed64.
    return (3*before + 5*first + 7*second + 11*first + 13*second
            + 17*storage[0] + 19*storage[1] + 23*storage[2] + 29*storage[3])


def parse_rows(text, values):
    expected = {(name, x) for name in ENTRIES for x in values}
    rows = {}
    for line in text.splitlines():
        parts = line.split('\t')
        check(len(parts) == 3, 'Malformed native row: '+line)
        name, x, answer = parts
        key = (name, int(x))
        check(key in expected and key not in rows, 'Unknown or duplicate native row: '+line)
        rows[key] = int(answer)
    check(rows.keys() == expected, 'Missing native rows')
    return rows


def check_report(name, report):
    check(report['accepted'], name+': strict capability audit failed')
    counts = {p['name']: len(p['uses']) for p in report['primitives']}
    check(REQUIRED[name] <= counts.keys(), name+': required reachable primitive disappeared')
    if name in EXACT_ALIAS:
        check(counts == EXACT_ALIAS[name], name+': exact alias primitive counts changed: '+str(counts))
    return counts


def nodes(value):
    if isinstance(value, list):
        yield value
        for item in value:
            yield from nodes(item)
    elif isinstance(value, dict):
        for item in value.values():
            yield from nodes(item)


def guest_lambdas(name, expr):
    # Mirror CoreJoins: complete proven join prefixes do not create GuestRoots.
    # Traverse dictionary-held expressions and join bodies without exceptions.
    join_prefixes = set()
    for node in nodes(expr):
        if node and node[0] == 'let':
            for binding in node[2]:
                arity, rhs = binding.get('joinValueArity'), binding.get('expr')
                if (type(arity) is int and arity > 0 and isinstance(rhs, list)
                        and rhs[0] == 'lam' and arity == len(rhs[1])):
                    result = binding.get('joinResultRep')
                    check(isinstance(result, dict) and result.get('primReps') == ['IntRep']
                          and result.get('kind') == 'long' and isinstance(rhs[-1], dict)
                          and result == rhs[-1].get('resultRep'), name+': join result proof changed')
                    join_prefixes.add(id(rhs))
    return [node for node in nodes(expr) if node and node[0] == 'lam' and id(node) not in join_prefixes]


def check_structure(name, report, modules):
    bindings = {b['id']: b for _, module in modules for b in module['bindings']}
    check(len(report['roots']) == 1, name+': expected one root')
    root = bindings[report['roots'][0]]
    check({b['id'] for b in report['reachableBindings']} == {root['id']}, name+': global closure changed')
    expr = root['expr']
    check(expr[0] == 'lam' and len(expr[1]) == 1 and expr[1][0]['rep']['primReps'] == ['IntRep'],
          name+': expected unary Int# entry')
    call = expr[2]
    check(call[0] == 'app' and call[1][0] == 'lam', name+': State# lambda must be called immediately')
    state_lambda = call[1]
    check(len(state_lambda[1]) == 1, name+': State# lambda must have one formal')
    formal = state_lambda[1][0]
    void_rep = dict(primReps=[], kind='void', evaluated=True)
    check(formal.get('type') == 'State# RealWorld' and formal.get('rep') == void_rep
          and formal.get('lifted') is False and formal.get('coercion') is False,
          name+': local lambda must have an actual zero-slot State# formal')
    check(len(call[2]) == 1 and call[2][0][0] == 'void' and call[2][0][-1].get('rep') == void_rep,
          name+': State# lambda must receive exactly one void argument')
    check(call[3:6] == [[False], False, False], name+': State# call flags changed')
    lambdas = guest_lambdas(name, expr)
    check(len(lambdas) == 2 and lambdas[0] is expr and lambdas[1] is state_lambda,
          name+': unexpected additional guest lambda')
    for node in nodes(expr):
        if node and node[0] == 'var':
            check(node[1] not in bindings, name+': unexpected global reference')
    return len(lambdas)  # Entry and its unconditional local State# lambda; no host bridge.


def check_literal_structure(name, report, modules):
    """Require the genuine erased literal and exactly one unconditional worker call."""
    kind, value = LITERAL_ENTRIES[name]
    payload = 'Int16Rep' if kind == 'int16' else 'Word16Rep'
    worker_name = 'literalInt16Worker' if kind == 'int16' else 'literalWord16Worker'
    bindings = {b['id']: b for _, module in modules for b in module['bindings']}
    check(len(report['roots']) == 1, name+': expected one literal root')
    root = bindings[report['roots'][0]]
    reachable = {b['id'] for b in report['reachableBindings']}
    workers = [bindings[i] for i in reachable if bindings[i]['name'] == worker_name]
    check(len(workers) == 1, name+': required opaque literal worker disappeared')
    worker = workers[0]
    check(reachable == {root['id'], worker['id']}, name+': literal closure changed')
    for binding, reps in ((root, ['IntRep']), (worker, ['IntRep', payload])):
        expr = binding['expr']
        check(expr[0] == 'lam' and len(expr[1]) == len(reps)
              and all(formal.get('rep') == dict(kind='long', primReps=[rep], evaluated=True)
                      for formal, rep in zip(expr[1], reps)), name+': literal formal shape changed')
        check(len(guest_lambdas(name, expr)) == 1, name+': extra literal guest lambda')
    call = root['expr'][2]
    check(call[0] == 'app' and call[1][:2] == ['var', worker['id']]
          and len(call[2]) == 2 and call[3:6] == [[False, False], False, False],
          name+': literal worker must be called directly and saturated')
    check(call[2][0][:2] == ['var', root['expr'][1][0]['id']], name+': lost dynamic input')
    literal = call[2][1]
    check(literal[:3] == ['lit', kind, str(value)] and literal[-1].get('rep') ==
          dict(kind='unknown', primReps=None, evaluated=False),
          name+': expected genuine unconstrained noinline literal proof')
    for binding in (root, worker):
        references = [node[1] for node in nodes(binding['expr'])
                      if node and node[0] == 'var' and node[1] in bindings]
        check(references == ([worker['id']] if binding is root else []),
              name+': unexpected literal global call')
    primitives = {p['name']: len(p['uses']) for p in report['primitives']}
    expected = {'+#': 1, 'int16ToInt#': 1} if kind == 'int16' else {'+#': 1, 'word16ToWord#': 1, 'word2Int#': 1}
    check(primitives == expected, name+': literal worker primitive body changed')
    return 2  # Dynamic entry and its retained opaque worker; no local State lambda.


def hashes(paths):
    return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths))}


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    manifest = BUILD / 'manifest.json'
    manifest.unlink(missing_ok=True)
    ghc, ghc_pkg = os.environ.get('GHC', 'ghc'), os.environ.get('GHC_PKG', 'ghc-pkg')
    commands = []
    def run(command, env=None, **kwargs):
        command = [str(x) for x in command]
        commands.append(dict(argv=command, environment=env or {}))
        return subprocess.run(command, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True, **kwargs)
    check(run([ghc, '--numeric-version'], text=True, capture_output=True).stdout.strip() == '9.14.1', 'Requires GHC9.14.1')
    version = run([ghc_pkg, 'field', 'array', 'version', '--simple-output'], text=True, capture_output=True).stdout.strip()
    check(version == '0.5.8.0', 'Requires installed array0.5.8.0')
    spec = importlib.util.spec_from_file_location('int16_array_auditor', ROOT / 'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    capabilities = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    artifacts, stages, closures, primitive_counts, guest_calls, literal_calls = [], {}, {}, {}, {}, {}
    for stage, boundary in BOUNDARIES.items():
        paths = []
        for index, group in enumerate(GROUPS):
            directory = BUILD / stage / str(index)
            core = directory / 'core'
            options = ['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []
            run([ROOT / 'compiler/export.sh', *options,
                 *['-fplugin-opt=THC.Plugin:closure='+name for name in group['entries']], group['source']],
                env=dict(THC_CORE_OUT=str(core), THC_GHC_OUT=str(directory / 'ghc'), THC_SOURCE_NOTES='true'))
            module_path = core / (group['module']+'.json')
            check(json.loads(module_path.read_text())['boundary'] == boundary, 'Wrong Core boundary')
            paths += [module_path, core / 'THC.InterfaceClosure.json']
        modules = [(str(p.relative_to(ROOT)), json.loads(p.read_text())) for p in paths]
        stages[stage] = [p for p, _ in modules]
        artifacts += paths
        for name in ENTRIES:
            report = audit.Audit(modules, capabilities).run([name])
            path = BUILD / stage / (name+'.audit.json')
            path.write_text(json.dumps(report, indent=2)+'\n')
            artifacts.append(path)
            primitive_counts[stage+'/'+name] = check_report(name, report)
            closures[stage+'/'+name] = report['reachableBindings']
            guest_calls[stage+'/'+name] = check_structure(name, report, modules)
        for name in LITERAL_ENTRIES:
            report = audit.Audit(modules, capabilities).run([name])
            check(report['accepted'], name+': strict literal audit failed')
            literal_calls[stage+'/'+name] = check_literal_structure(name, report, modules)
            path = BUILD / stage / (name+'.audit.json')
            path.write_text(json.dumps(report, indent=2)+'\n')
            artifacts.append(path)
    check(all(guest_calls['pre/'+n] == guest_calls['post/'+n] for n in ENTRIES), 'Guest root count changed across Tidy')
    driver = ['{-# LANGUAGE MagicHash #-}', 'module Main where', 'import GHC.Exts (Int(I#), Int#)',
              'import Data.Bits (finiteBitSize)', 'import qualified THC.Unboxed16Arrays as U',
              'import qualified Int16ArrayAudit as P',
              'emit :: String -> (Int# -> Int#) -> Int -> IO ()',
              'emit n f x@(I# a) = putStrLn (n ++ "\\t" ++ show x ++ "\\t" ++ show (I# (f a)))',
              'dispatch :: [String] -> IO ()', 'dispatch [name, x] = case name of']
    for group in GROUPS:
        for name in group['entries']:
            prefix = 'U.' if group['module'] == 'THC.Unboxed16Arrays' else 'P.'
            driver.append(f'  "{name}" -> emit name {prefix}{name} (read x)')
    for name in LITERAL_ENTRIES:
        driver.append(f'  "{name}" -> emit name P.{name} (read x)')
    driver += ['  _ -> error "unknown entry"', 'dispatch _ = error "invalid input"',
               'main :: IO ()', 'main = if finiteBitSize (0 :: Int) /= 64 then error "Requires 64-bit Int"',
               '       else getContents >>= mapM_ (dispatch . words) . lines']
    source = BUILD / 'NativeInt16Array.hs'
    source.write_text('\n'.join(driver)+'\n')
    native = BUILD / 'native'
    native.mkdir(exist_ok=True)
    binary = native / 'int16-array-oracle'
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint',
         '-i'+str(ROOT/'examples'), '-i'+str(ROOT/'compiler/test-fixtures'),
         '-odir', native, '-hidir', native, source, '-o', binary])
    values = inputs()
    result = run([binary], input=''.join(f'{n}\t{x}\n' for n in ENTRIES for x in values),
                 text=True, capture_output=True, timeout=60)
    actual = parse_rows(result.stdout, values)
    wanted = {(name, x): mathematical(name, x) for name in ENTRIES for x in values}
    check(actual == wanted, 'Native/model mismatch: '+str(next(
        ((k, actual[k], v) for k, v in wanted.items() if actual[k] != v), None)))
    (BUILD/'oracle.tsv').write_text(result.stdout)
    (BUILD/'expected.tsv').write_text(''.join(f'{name}\t{x}\t{answer}\n' for (name,x),answer in wanted.items()))
    literal_result = run([binary], input=''.join(f'{n}\t{x}\n' for n in LITERAL_ENTRIES for x in LITERAL_INPUTS),
                         text=True, capture_output=True, timeout=60).stdout
    expected_literals = ''.join(f'{n}\t{x}\t{signed(x+value)}\n'
                                for n, (_, value) in LITERAL_ENTRIES.items() for x in LITERAL_INPUTS)
    check(literal_result == expected_literals, 'Noinline narrow literal native/model mismatch')
    (BUILD/'literal-oracle.tsv').write_text(literal_result)
    inputs_to_hash = [ROOT/g['source'] for g in GROUPS] + [Path(__file__).resolve(),
        ROOT/'scripts/test-int16-array-model.py', ROOT/'scripts/core-capabilities.json', ROOT/'scripts/audit-core.py',
        ROOT/'src/main/resources/thc/scalar-primop-signatures.json',
        *sorted((ROOT/'scripts').glob('core_*.py')), *sorted((ROOT/'compiler/THC').glob('*.hs')),
        *[ROOT/'compiler'/n for n in ('build.sh','export.sh','toolchain.sh')]]
    artifacts += [BUILD/'oracle.tsv', BUILD/'expected.tsv', BUILD/'literal-oracle.tsv', source, binary]
    manifest.write_text(json.dumps(dict(schema=1, ghc='9.14.1', array=version, wordBits=64, elementBits=16,
        byteOrder=sys.byteorder, entries=ENTRIES, inputs=values, stages=stages, nativeRows=len(actual),
        literalEntries=list(LITERAL_ENTRIES), literalInputs=LITERAL_INPUTS, literalNativeRows=len(LITERAL_ENTRIES)*len(LITERAL_INPUTS),
        literalExpectedGuestCallsByEntry={n:2 for n in LITERAL_ENTRIES}, checkedLiteralGuestCallsByStage=literal_calls,
        allNativeResultsMatchIndependentModels=True,
        expectedGuestCallsByEntry={n:guest_calls['pre/'+n] for n in ENTRIES}, checkedGuestCallsByStage=guest_calls,
        requiredPrimitivesByEntry={n:sorted(v) for n,v in REQUIRED.items()},
        primitiveCounts=primitive_counts, reachableBindings=closures, commands=commands,
        installedArray=run([ghc_pkg,'describe','array'],text=True,capture_output=True).stdout,
        ghcInfo=run([ghc,'--info'],text=True,capture_output=True).stdout,
        inputHashes=hashes(inputs_to_hash), artifactHashes=hashes(artifacts),
        claim='Native/model, exact original public library/interface Core and all-branch strict audits; guest/compiled execution checked separately.',
        limitations=['Public checked bounds/indices are constants; dynamic checked-index error paths are not claimed.',
                    'Native execution validates recorded host endianness; model tests separately cover both byte orders.',
                    'Machine Int is 64 bits; stored Int16/Word16 elements are exactly two native-endian bytes.',
                    'Guest counts include the immediate local State# lambda but exclude local joins and the host bridge.']),indent=2)+'\n')
    print(f'Prepared {len(ENTRIES)} Int16/Word16 array entries / {len(actual)} native-model rows / '
          f'{len(LITERAL_ENTRIES)*len(LITERAL_INPUTS)} noinline literal rows / strict pre+post Core')


if __name__ == '__main__':
    main()
