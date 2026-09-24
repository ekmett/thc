#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Real public Double arrays and residual floating-result movement evidence."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/double-arrays'
MASK = (1 << 64) - 1
FRACTION = (1 << 52) - 1
EXPONENT = 0x7ff0000000000000
QUIET = 1 << 51
DOUBLE_PRIMITIVES = {'readDoubleArray#', 'writeDoubleArray#', 'indexDoubleArray#'}
STORAGE = {'newByteArray#', 'unsafeFreezeByteArray#'}
GROUPS = [dict(source='examples/THC/UnboxedDoubleArrays.hs', module='THC.UnboxedDoubleArrays',
               entries=['unboxedDoubleAccum', 'unboxedDoubleST']),
          dict(source='compiler/test-fixtures/DoubleArrayAudit.hs', module='DoubleArrayAudit',
               entries=['moveDoubleBits', 'indexDoubleBits'])]
ENTRIES = [name for group in GROUPS for name in group['entries']]
HELPERS = {'moveDoubleBits': 'readDoubleSlot', 'indexDoubleBits': 'indexDoubleSlot'}
# All actual guest roots: public entry, its immediate runRW# State# lambda,
# and (for movement entries) the retained primitive helper. No host bridge.
EXPECTED_CALLS = {name: 3 if name in HELPERS else 2 for name in ENTRIES}
REQUIRED = {name: DOUBLE_PRIMITIVES | STORAGE for name in ENTRIES[:2]}
EXACT_MOVEMENT = {
    'moveDoubleBits': {'newByteArray#': 2, 'writeIntArray#': 1, 'readDoubleArray#': 1,
                       'writeDoubleArray#': 1, 'unsafeFreezeByteArray#': 1, 'indexIntArray#': 1},
    'indexDoubleBits': {'newByteArray#': 2, 'writeIntArray#': 1, 'indexDoubleArray#': 1,
                        'writeDoubleArray#': 1, 'unsafeFreezeByteArray#': 2, 'indexIntArray#': 1}}
REQUIRED.update({name: set(counts) for name, counts in EXACT_MOVEMENT.items()})
BOUNDARIES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def signed(x):
    return ((x + (1 << 63)) & MASK) - (1 << 63)


def signaling_nan(x):
    bits = x & MASK
    return bits & EXPONENT == EXPONENT and bool(bits & FRACTION) and not bits & QUIET


def inputs():
    values = set(range(-16, 17)) | {-(1 << 63), -(1 << 63)+1, (1 << 63)-2, (1 << 63)-1}
    values |= {signed(sign*((1 << bit)+delta)) for bit in range(64) for delta in (-1, 0, 1) for sign in (-1, 1)}
    # Include both signs of each named floating encoding, without host FP conversions.
    magnitudes = (0, 1, 2, 3, 0x000fffffffffffff, 0x0010000000000000,
                  0x3fefffffffffffff, 0x3ff0000000000000, 0x3ff0000000000001,
                  0x7fefffffffffffff, EXPONENT, EXPONENT | QUIET,
                  EXPONENT | QUIET | 0x1234, EXPONENT | FRACTION,
                  0x5555555555555555, 0x55aa55aa55aa55aa, 0x0123456789abcdef)
    values |= {signed(bits | sign) for bits in magnitudes for sign in (0, 1 << 63)}
    values |= {signed(EXPONENT | QUIET | (1 << bit) | sign)
               for bit in range(51) for sign in (0, 1 << 63)}
    return sorted(x for x in values if not signaling_nan(x))


def mathematical(name, raw):
    check(-(1 << 63) <= raw < (1 << 63), 'Requires signed 64-bit input')
    x = (raw & 65535) - 32768
    if name == 'unboxedDoubleAccum':
        # Cells -3, 0, 4 are x+5/4, x+11/2, 3*x/2; final checksum is scaled by four.
        return 150*x + 277
    if name == 'unboxedDoubleST':
        # Read-after-write: cells -3, 0, 4 are x, x+1/4, 2*x+5/4.
        return 176*x + 76
    if name in HELPERS:
        check(not signaling_nan(raw), 'Signaling NaN movement identity is outside the evidence domain')
        return raw
    raise ValueError(name)


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
    if name in EXACT_MOVEMENT:
        check(counts == EXACT_MOVEMENT[name], name+': exact primitive movement changed: '+str(counts))
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
    # CoreJoins consumes a positive joinValueArity prefix, not info.joinArity.
    # Exempt only an entire proven prefix; keep traversing its body so a join
    # returning a function or containing another local function cannot hide it.
    join_prefixes = set()
    for node in nodes(expr):
        if node and node[0] == 'let':
            for binding in node[2]:
                arity, rhs = binding.get('joinValueArity'), binding.get('expr')
                if (type(arity) is int and arity > 0 and isinstance(rhs, list)
                        and rhs[0] == 'lam' and arity == len(rhs[1])):
                    result = binding.get('joinResultRep')
                    # These fixtures' local joins return scalar Int#, and the
                    # complete-prefix result must agree with lambdaResult.
                    check(isinstance(result, dict) and result.get('primReps') == ['IntRep']
                          and result.get('kind') == 'long' and isinstance(rhs[-1], dict)
                          and result == rhs[-1].get('resultRep'),
                          name+': join result proof changed')
                    join_prefixes.add(id(rhs))
    return [node for node in nodes(expr)
            if node and node[0] == 'lam' and id(node) not in join_prefixes]


def check_state_lambda(name, expr):
    check(expr[0] == 'lam', name+': entry lost its outer lambda')
    call = expr[2]
    check(call[0] == 'app' and call[1][0] == 'lam',
          name+': State# lambda must be called immediately, not conditionally')
    state_lambda = call[1]
    check(len(state_lambda[1]) == 1, name+': State# lambda must have one formal')
    formal = state_lambda[1][0]
    void_rep = dict(primReps=[], kind='void', evaluated=True)
    check(formal.get('type') == 'State# RealWorld' and formal.get('rep') == void_rep
          and formal.get('lifted') is False and formal.get('coercion') is False,
          name+': local lambda must have an actual zero-slot State# formal')
    check(len(call[2]) == 1 and call[2][0][0] == 'void'
          and call[2][0][-1].get('rep') == void_rep,
          name+': State# lambda must receive exactly one zero-slot void argument')
    check(call[3:6] == [[False], False, False], name+': State# call flags changed')
    lambdas = guest_lambdas(name, expr)
    check(len(lambdas) == 2 and lambdas[0] is expr and lambdas[1] is state_lambda,
          name+': unexpected additional local lambda')


def check_structure(name, report, modules):
    """Pin every guest root, including the immediate State# lambda and residual calls."""
    bindings = {b['id']: b for _, module in modules for b in module['bindings']}
    reachable = {b['id'] for b in report['reachableBindings']}
    roots = report['roots']
    check(len(roots) == 1, name+': expected one root')
    root = bindings[roots[0]]
    check_state_lambda(name, root['expr'])
    helper_name = HELPERS.get(name)
    helper = None
    if helper_name:
        candidates = [bindings[i] for i in reachable if bindings[i]['name'] == helper_name]
        check(len(candidates) == 1, name+': required residual helper disappeared')
        helper = candidates[0]
    expected = {root['id']} | ({helper['id']} if helper else set())
    check(reachable == expected, name+': guest closure changed')
    calls = []
    for binding in [root] + ([helper] if helper else []):
        for node in nodes(binding['expr']):
            if node and node[0] == 'var' and node[1] in bindings:
                check(helper is not None and binding is root and node[1] == helper['id'],
                      name+': unexpected global reference')
            if node and node[0] == 'app' and node[1][0] == 'var' and node[1][1] in bindings:
                calls.append(node)
            if helper and node and node[0] == 'case':
                check(len(node[3]) == 1, name+': residual movement gained a conditional call path')
    check(len(calls) == (1 if helper else 0), name+': guest call count changed')
    if helper:
        check(len(calls[0][2]) == helper['arity'], name+': residual helper is not saturated')
        expr = helper['expr']
        check(len(guest_lambdas(name, expr)) == 1,
              name+': helper gained an additional local lambda')
        check(expr[0] == 'lam' and expr[2][0] == 'app' and expr[2][1][0] == 'prim',
              name+': helper is no longer a direct primitive')
        primitive = 'readDoubleArray#' if name == 'moveDoubleBits' else 'indexDoubleArray#'
        check(expr[2][1][1] == primitive, name+': wrong helper primitive')
        rep = expr[-1]['resultRep']
        if name == 'moveDoubleBits':
            check(rep.get('aggregate') == 'unboxed-tuple' and rep.get('primReps') == ['DoubleRep'],
                  name+': helper lost its actual floating tuple result')
            check(rep.get('components') == [dict(primReps=[], kind='void', evaluated=True),
                                            dict(primReps=['DoubleRep'], kind='double', evaluated=True)],
                  name+': helper lost its State#/Double# tuple components')
        else:
            check(rep == dict(primReps=['DoubleRep'], kind='double', evaluated=False),
                  name+': helper lost its scalar Double# result')
    guest_calls = 2 + len(calls)  # Entry + unconditional State# lambda + residual helper.
    check(guest_calls == EXPECTED_CALLS[name], name+': actual guest root count changed')
    return guest_calls


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
    spec = importlib.util.spec_from_file_location('double_array_auditor', ROOT / 'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    capabilities = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    artifacts, stages, closures, primitive_counts, guest_calls = [], {}, {}, {}, {}
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
    driver = ['{-# LANGUAGE MagicHash #-}', 'module Main where', 'import GHC.Exts (Int(I#), Int#)',
              'import Data.Bits (finiteBitSize)', 'import qualified THC.UnboxedDoubleArrays as U',
              'import qualified DoubleArrayAudit as P',
              'emit :: String -> (Int# -> Int#) -> Int -> IO ()',
              'emit n f x@(I# a) = putStrLn (n ++ "\\t" ++ show x ++ "\\t" ++ show (I# (f a)))',
              'dispatch :: [String] -> IO ()', 'dispatch [name, x] = case name of']
    for group in GROUPS:
        for name in group['entries']:
            prefix = 'U.' if group['module'] == 'THC.UnboxedDoubleArrays' else 'P.'
            driver.append(f'  "{name}" -> emit name {prefix}{name} (read x)')
    driver += ['  _ -> error "unknown entry"', 'dispatch _ = error "invalid input"',
               'main :: IO ()', 'main = if finiteBitSize (0 :: Int) /= 64 then error "Requires 64-bit Int"',
               '       else getContents >>= mapM_ (dispatch . words) . lines']
    source = BUILD / 'NativeDoubleArray.hs'
    source.write_text('\n'.join(driver)+'\n')
    native = BUILD / 'native'
    native.mkdir(exist_ok=True)
    binary = native / 'double-array-oracle'
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint',
         '-i'+str(ROOT/'examples'), '-i'+str(ROOT/'compiler/test-fixtures'),
         '-odir', native, '-hidir', native, source, '-o', binary])
    values = inputs()
    requests = ''.join(f'{name}\t{x}\n' for name in ENTRIES for x in values)
    result = run([binary], input=requests, text=True, capture_output=True, timeout=60)
    actual = parse_rows(result.stdout, values)
    wanted = {(name, x): mathematical(name, x) for name in ENTRIES for x in values}
    check(actual == wanted, 'Native/model mismatch: '+str(next(
        ((k, actual[k], v) for k, v in wanted.items() if actual[k] != v), None)))
    (BUILD/'oracle.tsv').write_text(result.stdout)
    (BUILD/'expected.tsv').write_text(''.join(f'{name}\t{x}\t{answer}\n' for (name,x),answer in wanted.items()))
    inputs_to_hash = [ROOT/g['source'] for g in GROUPS] + [Path(__file__).resolve(),
        ROOT/'scripts/test-double-array-model.py', ROOT/'scripts/core-capabilities.json', ROOT/'scripts/audit-core.py',
        ROOT/'src/main/resources/thc/scalar-primop-signatures.json',
        *sorted((ROOT/'scripts').glob('core_*.py')), *sorted((ROOT/'compiler/THC').glob('*.hs')),
        *[ROOT/'compiler'/n for n in ('build.sh','export.sh','toolchain.sh')]]
    artifacts += [BUILD/'oracle.tsv', BUILD/'expected.tsv', source, binary]
    manifest.write_text(json.dumps(dict(schema=1, ghc='9.14.1', array=version, wordBits=64, byteOrder=sys.byteorder,
        entries=ENTRIES, inputs=values, stages=stages, nativeRows=len(actual),
        allNativeResultsMatchIndependentModels=True, signalingNaNsExcluded=True,
        movementDomain='Finite binary64 encodings, signed zeros/infinities and quiet NaN payloads; no arithmetic NaN claim.',
        expectedGuestCallsByEntry=EXPECTED_CALLS, checkedGuestCallsByStage=guest_calls,
        requiredPrimitivesByEntry={n:sorted(v) for n,v in REQUIRED.items()},
        primitiveCounts=primitive_counts, reachableBindings=closures, commands=commands,
        installedArray=run([ghc_pkg,'describe','array'],text=True,capture_output=True).stdout,
        ghcInfo=run([ghc,'--info'],text=True,capture_output=True).stdout,
        inputHashes=hashes(inputs_to_hash), artifactHashes=hashes(artifacts),
        claim='Native/model, exact original library/interface Core and all-branch strict audits; guest/compiled execution checked separately.',
        limitations=['Public checked indices/bounds are constants; dynamic checked-index error paths are not claimed.',
                    'Public arithmetic is bounded exact dyadic arithmetic, not arbitrary floating arithmetic.',
                    'Movement excludes signaling NaNs; neither arithmetic NaN payloads nor floating host arguments are claimed.',
                    'Machine Int width is 64 bits; Int/Double storage follows recorded native byte order.',
                    'Guest call counts cover all actual roots, including the immediate local State# lambda and residual helper, but exclude the host bridge.']),indent=2)+'\n')
    print(f'Prepared {len(ENTRIES)} Double array entries / {len(actual)} native-model rows / strict pre+post Core')


if __name__ == '__main__':
    main()
