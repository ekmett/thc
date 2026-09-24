#!/usr/bin/env python3
"""Pinned Int32X4 multiply Core, integer-only lane oracle and optional native evidence."""
import argparse
from collections import Counter
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
from int32x4_multiply_model import check, entries, model_rows, parse_rows, HELPERS

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/simd-int32x4-multiply'
FIXTURE = ROOT / 'compiler/test-fixtures/SimdInt32X4Multiply.hs'
NATIVE = ROOT / 'compiler/test-fixtures/SimdInt32X4MultiplyNative.hs'
PRIMITIVES = {p+'Int32X4#' for p in ('pack', 'unpack', 'times')}
STAGES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}
EXPECTED_CALLS = {e['name']: 2 if e['name'] in HELPERS else 1 for e in entries()}
VECTOR_COUNTS = {name: {'packInt32X4#': 2, 'timesInt32X4#': 1, 'unpackInt32X4#': 1}
                 for name in ('timesCase', 'laneCase', 'scalarHelperCase', 'tupleHelperCase')}


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def expressions(value, tag):
    return [node for node in walk(value) if isinstance(node, list) and node[:1] == [tag]]


def scalar(proof, register):
    return isinstance(proof, dict) and proof.get('kind') == 'long' and proof.get('primReps') == [register] and 'aggregate' not in proof


def lane_tuple(proof):
    return (isinstance(proof, dict) and proof.get('kind') == 'unknown'
            and proof.get('aggregate') == 'unboxed-tuple' and proof.get('primReps') == ['Int32Rep']*4
            and len(proof.get('components', [])) == 4
            and all(scalar(lane, 'Int32Rep') for lane in proof['components']))


def representation(expression):
    return expression[-1].get('rep') if isinstance(expression[-1], dict) else None


def check_guest_structure(entry, report, module):
    name = entry['name']
    bindings = {b['id']: b for b in module['bindings']}
    check(len(report['roots']) == 1, name+': expected one entry')
    root = bindings[report['roots'][0]]
    reachable = {b['id'] for b in report['reachableBindings']}
    helper_name = HELPERS.get(name)
    helpers = [bindings[i] for i in reachable if bindings[i]['name'] == helper_name] if helper_name else []
    check(len(helpers) == (1 if helper_name else 0), name+': residual helper disappeared')
    check(reachable == {root['id'], *[b['id'] for b in helpers]}, name+': actual global closure changed')
    actual_roots = [root]+helpers
    for binding in actual_roots:
        expr = binding['expr']
        arity = entry['arity'] if binding is root else 2
        check(expr[0] == 'lam' and len(expr[1]) == arity
              and all(scalar(formal.get('rep'), 'IntRep') for formal in expr[1]),
              name+': function must have only machine Int formals')
        check(len(expressions(expr, 'lam')) == 1, name+': extra local guest lambda')
        result = expr[-1].get('resultRep')
        check(lane_tuple(result) if binding['name'] == 'tupleWorker' else scalar(result, 'IntRep'),
              name+': scalar/tuple result boundary changed')
        refs = [node[1] for node in expressions(expr, 'var') if node[1] in bindings]
        check(refs == ([helpers[0]['id']] if helpers and binding is root else []),
              name+': additional or missing global reference')
        calls = [node for node in expressions(expr, 'app') if node[1][:1] == ['var'] and node[1][1] in bindings]
        check(len(calls) == (1 if helpers and binding is root else 0), name+': unexpected call count')
        if calls:
            call = calls[0]
            check(len(call[2]) == 2 and call[3:6] == [[False, False], False, False]
                  and [arg[:2] for arg in call[2]] == [['var', arg['id']] for arg in expr[1]],
                  name+': helper call must be saturated with the original scalar inputs')
        if helpers:
            check(all(len(case[3]) == 1 for case in expressions(expr, 'case')),
                  name+': residual helper gained a conditional execution path')
    check(len(actual_roots) == EXPECTED_CALLS[name], name+': actual guest count changed')
    counts = {p['name']: len(p['uses']) for p in report['primitives'] if p['name'] in PRIMITIVES}
    check(counts == VECTOR_COUNTS[name], name+': required local vector operations changed')
    return dict(guestCalls=len(actual_roots), roots=[dict(id=b['id'], name=b['name']) for b in actual_roots],
                vectorPrimitiveCounts=counts)


def inventory(module, stage):
    check(module['boundary'] == STAGES[stage], 'Wrong Core boundary')
    vectors = [v for v in walk(module) if isinstance(v, dict) and v.get('kind') == 'vector']
    check(vectors and all(v.get('vector') == dict(lanes=4, element='Int32ElemRep')
          and v.get('primReps') == ['VecRep 4 Int32ElemRep'] and 'aggregate' not in v for v in vectors),
          'Missing or inexact Int32X4 representation')
    calls = [node for node in expressions(module['bindings'], 'app')
             if node[1][:1] == ['prim'] and node[1][1] in PRIMITIVES]
    check({node[1][1] for node in calls} == PRIMITIVES, 'Required vector operation disappeared')
    packs = [node for node in calls if node[1][1] == 'packInt32X4#']
    for call in packs:
        check(len(call[2]) == 1 and lane_tuple(representation(call[2][0])),
              'packInt32X4# requires ONE four-Int32 logical tuple argument')
    unpacked = [node for node in calls if node[1][1] == 'unpackInt32X4#']
    check(all(lane_tuple(representation(node)) for node in unpacked), 'Unpack must return four Int32 lanes')
    literals = [node for node in expressions(module['bindings'], 'lit') if node[1] == 'int32']
    check(literals and all(-(1 << 31) <= int(node[2]) < (1 << 31) for node in literals),
          'Missing or noncanonical genuine Int32 literal')
    frontier = next(b for b in module['bindings'] if b['name'] == 'vectorArgument')['expr']
    check(frontier[0] == 'lam' and len(frontier[1]) == 1
          and frontier[1][0]['rep'].get('kind') == 'vector', 'Missing vector-formal negative control')
    return dict(vectorProofs=len(vectors), int32LiteralSites=len(literals), packSites=len(packs), unpackSites=len(unpacked), primitives=sorted(PRIMITIVES))


def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def unsigned_control(module, variant):
    """Deliberately corrupted metadata, never a replacement native/Core input."""
    changed = copy.deepcopy(module)
    root = next(b for b in changed['bindings'] if b['name'] == 'timesCase')
    primitive = {'unsignedLaneTuple': 'packInt32X4#', 'unsignedVectorOperand': 'timesInt32X4#'}[variant]
    call = next(e for e in expressions(root['expr'], 'app') if e[1][:2] == ['prim', primitive])
    proof = representation(call[2][0])
    if variant == 'unsignedLaneTuple':
        check(lane_tuple(proof), 'Mutation requires the genuine signed four-lane tuple')
        proof['primReps'] = ['Word32Rep']*4
        for lane in proof['components']:
            lane['primReps'] = ['Word32Rep']
    else:
        check(proof.get('vector') == dict(lanes=4, element='Int32ElemRep')
              and proof.get('primReps') == ['VecRep 4 Int32ElemRep'], 'Mutation requires the signed vector operand')
        proof['primReps'] = ['VecRep 4 Word32ElemRep']
        proof['vector']['element'] = 'Word32ElemRep'
    return changed


def audit_unsigned_controls(module, module_path, auditor, capabilities):
    argument = ('vector-shape', 'Exact vector primitive argument representation required')
    aggregate = ('aggregate-shape', 'Conflicting or missing logical aggregate representation proofs')
    expected = {
        'unsignedLaneTuple': {argument: 1, aggregate: 5,
                            ('scalar-representation', 'Conflicting exact scalar primitive representations'): 4},
        'unsignedVectorOperand': {argument: 1, aggregate: 2,
                                ('vector-shape', 'Exact vector primitive result representation required'): 1}}
    controls = {}
    for variant, issues in expected.items():
        changed = unsigned_control(module, variant)
        report = auditor.Audit([(str(module_path)+' [MUTATED '+variant+']', changed)], capabilities).run(['timesCase'])
        check(not report['accepted'] and not report['missingGlobals']
              and Counter((i['code'], i['detail']) for i in report['issues']) == issues,
              variant+': unsigned/signed mutation must fail for its exact representation mismatches')
        controls[variant] = dict(origin='Deliberately mutated metadata; not original GHC Core or native oracle input',
                                 root='timesCase', report=report)
    return controls


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--export-only', action='store_true', help='Pre-Tidy Core and model only; no native/post-Tidy claim')
    args = parser.parse_args()
    ghc = os.environ.get('GHC', 'ghc')
    check(subprocess.check_output([ghc, '--numeric-version'], text=True).strip() == '9.14.1', 'Requires GHC9.14.1')
    OUT.mkdir(parents=True, exist_ok=True)
    for name in ('oracle.tsv', 'provenance.json'):
        (OUT/name).unlink(missing_ok=True)
    commands = []
    def run(argv, env=None, **kwargs):
        argv = [str(x) for x in argv]
        commands.append(dict(argv=argv, environment=env or {}))
        return subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True, **kwargs)
    wanted = model_rows()
    (OUT/'expected.tsv').write_text(''.join('\t'.join(map(str, (*key, answer)))+'\n' for key, answer in wanted.items()))
    run(['compiler/build.sh'])
    stages = ['pre'] if args.export_only else ['pre', 'post']
    spec = importlib.util.spec_from_file_location('int32x4_multiply_auditor', ROOT/'scripts/audit-core.py')
    auditor = importlib.util.module_from_spec(spec); spec.loader.exec_module(auditor)
    capabilities = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
    audits, structure, guest_calls, unsigned_controls = {}, {}, {}, {}
    artifacts = [OUT/'expected.tsv']
    for stage in stages:
        module_path = OUT/f'{stage}-core/SimdInt32X4Multiply.json'
        module_path.unlink(missing_ok=True)
        options = ['-fno-code', '-fwrite-if-simplified-core'] if args.export_only else []
        if stage == 'post': options += ['-fplugin-opt=Thc.Plugin:post-tidy']
        run(['compiler/export.sh', *options, FIXTURE],
            dict(THC_CORE_OUT=str(module_path.parent), THC_GHC_OUT=str(OUT/f'{stage}-ghc'), THC_SOURCE_NOTES='true'))
        module = json.loads(module_path.read_text())
        structure[stage] = inventory(module, stage)
        audits[stage] = {name: auditor.Audit([(str(module_path), module)], capabilities).run([name])
                         for name in [e['name'] for e in entries()]+['vectorArgument']}
        frontier = audits[stage]['vectorArgument']
        check(not frontier['accepted'] and not frontier['missingGlobals'] and len(frontier['issues']) == 1 and
              {(i['code'], i['detail']) for i in frontier['issues']} == {('vector-boundary', 'vector formal argument')},
              'Vector-formal frontier must be rejected for exactly its actual boundary')
        audit_path = OUT/f'{stage}-audit.json'
        audit_path.write_text(json.dumps(audits[stage], indent=2)+'\n')
        artifacts += [module_path, audit_path]
        structure[stage]['entries'] = {}
        for entry in entries():
            name = entry['name']; report = audits[stage][name]
            check(report['accepted'] and not report['issues'] and not report['missingGlobals'],
                  stage+'/'+name+': strict positive audit failed')
            proof = check_guest_structure(entry, report, module)
            structure[stage]['entries'][name] = proof
            guest_calls[stage+'/'+name] = proof['guestCalls']
        unsigned_controls[stage] = audit_unsigned_controls(module, module_path, auditor, capabilities)
    native_rows = None
    if not args.export_only:
        native = OUT/'native'; native.mkdir(exist_ok=True)
        binary = native/'int32x4-multiply-oracle'
        run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', '-icompiler/test-fixtures',
             '-odir', native, '-hidir', native, '-o', binary, NATIVE])
        requests = ''.join('\t'.join(map(str, key))+'\n' for key in wanted)
        output = run([binary], input=requests, text=True, capture_output=True, timeout=60).stdout
        actual = parse_rows(output)
        check(actual == wanted, 'Native/model mismatch: '+str(next(
            ((key, actual.get(key), value) for key, value in wanted.items() if actual.get(key) != value), 'extra native rows')))
        (OUT/'oracle.tsv').write_text(output)
        native_rows = len(actual)
        artifacts += [OUT/'oracle.tsv', binary]
    sources = [FIXTURE, NATIVE, Path(__file__).resolve(), ROOT/'scripts/int32x4_multiply_model.py', ROOT/'scripts/test-int32x4-multiply-model.py',
               ROOT/'scripts/audit-core.py', ROOT/'scripts/core-capabilities.json',
               ROOT/'src/main/resources/thc/scalar-primop-signatures.json',
               *sorted((ROOT/'scripts').glob('core_*.py')), *sorted((ROOT/'compiler/Thc').glob('*.hs')),
               *[ROOT/'compiler'/name for name in ('build.sh', 'export.sh', 'toolchain.sh')]]
    provenance = dict(schema=1, vector='int32x4-multiply', stages=stages, nativeRows=native_rows,
        modelMatched=True if native_rows is not None else None, modelRows=len(wanted), entries=entries(),
        frontiers=[dict(name='vectorArgument', arity=1, reason='vector formal argument remains unsupported')],
        positiveAuditsAccepted=True, audits=audits, structure=structure, commands=commands,
        signedUnsignedNegativeControls=unsigned_controls,
        expectedGuestCallsByEntry=EXPECTED_CALLS, checkedGuestCallsByStage=guest_calls,
        guestCountPolicy='Same exact per-call guest-entry count with Truffle inlining enabled or disabled; no host bridge.',
        sources=[record(p) for p in sources], artifacts=[record(p) for p in artifacts],
        toolchain=dict(ghcVersion='9.14.1', host=platform.node(), machine=platform.machine(), system=platform.platform(),
            ghcInfo=subprocess.check_output([ghc, '--info'], text=True),
            ghcBinarySha256=hashlib.sha256(Path(shutil.which(ghc) or ghc).resolve().read_bytes()).hexdigest()),
        claim='Native/model comparison, exact Core metadata and strict static audits; no JVM or hardware SIMD claim.'
              if native_rows is not None else 'Pre-Tidy Core and independent integer model only; NO native/post-Tidy validation.',
        limitations=['Int# host inputs require a 64-bit machine; lane arithmetic wraps modulo 2^32.',
                     'Vectors remain local; only Int scalar arguments/results and a four-Int32 tuple result cross helper boundaries.',
                     'Vector formals, results, captures, heap fields and vector-containing tuple fields are not enabled.'])
    (OUT/'provenance.json').write_text(json.dumps(provenance, indent=2)+'\n')
    print(f'Int32X4 multiply stages={stages}, native rows={native_rows}, model rows={len(wanted)}, positive strict audits=True')


if __name__ == '__main__':
    main()
