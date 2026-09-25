#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Pinned real DoubleX2 Core, exact integer binary64 model, and optional native oracle."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/simd-doublex2'
FIXTURE = ROOT / 'compiler/test-fixtures/SimdDoubleX2.hs'
NATIVE = ROOT / 'compiler/test-fixtures/SimdDoubleX2Native.hs'
from doublex2_model import entries, model_rows, parse_rows

PRIMITIVES = {p + 'DoubleX2#' for p in ('broadcast', 'pack', 'unpack', 'plus', 'minus', 'times')}
STAGES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def rep(expression):
    return expression[-1].get('rep') if isinstance(expression, list) and expression and isinstance(expression[-1], dict) else None


def double_tuple(proof):
    return bool(proof and proof.get('aggregate') == 'unboxed-tuple' and proof.get('primReps') == ['DoubleRep']*2
                and len(proof.get('components', [])) == 2 and all(
                    c.get('kind') == 'double' and c.get('primReps') == ['DoubleRep'] for c in proof['components']))


def compiled_entry_counts(module):
    """Count retained strict guest calls; fail if paths need different counts.

    This is deliberately fixture-specific, not an interprocedural Core analysis.
    CAF reads are excluded: every declared input is interpreted before measuring.
    All application operands in these entries are unlifted and evaluated eagerly.
    """
    bindings = {b['id']: b for b in module['bindings']}
    def body(expr, active):
        tag = expr[0]
        if tag in ('var', 'lit', 'con', 'prim'):
            return 0
        if tag == 'case':
            branches = {body(alt[3], active) for alt in expr[3]}
            check(len(branches) == 1, 'Input-dependent compiled call count')
            return body(expr[1], active) + branches.pop()
        check(tag == 'app', 'Unexpected expression in call-count proof: '+tag)
        check(all(flag is False for flag in expr[3]), 'Lazy operand in compiled call-count proof')
        count = sum(body(arg, active) for arg in expr[2])
        function = expr[1]
        if function[0] in ('prim', 'con'):
            return count
        check(function[0] == 'var' and function[1] in bindings, 'Unknown call in fixture')
        target = bindings[function[1]]['expr']
        check(target[0] == 'lam' and len(target[1]) == len(expr[2]), 'Nonexact fixture call')
        check(function[1] not in active, 'Recursive fixture call count')
        return count + 1 + body(target[2], active | {function[1]})
    names = {b['name']: b for b in module['bindings']}
    return {e['name']: 1 + body(names[e['name']]['expr'][2], {names[e['name']]['id']}) for e in entries()}


def inventory(module, stage):
    check(module['boundary'] == STAGES[stage], 'Wrong Core boundary')
    vectors = [v for v in walk(module) if isinstance(v, dict) and v.get('kind') == 'vector']
    check(vectors and all(v.get('vector') == {'lanes': 2, 'element': 'DoubleElemRep'}
          and v.get('primReps') == ['VecRep 2 DoubleElemRep'] and 'aggregate' not in v for v in vectors),
          'Missing or inexact DoubleX2 vector metadata')
    applications = [v for v in walk(module['bindings']) if isinstance(v, list) and len(v) > 2 and v[0] == 'app'
                    and isinstance(v[1], list) and v[1][:1] == ['prim'] and v[1][1] in PRIMITIVES]
    check({v[1][1] for v in applications} == PRIMITIVES, 'GHC removed a required vector operation')
    packs = [v for v in applications if v[1][1] == 'packDoubleX2#']
    for call in packs:
        check(len(call[2]) == 1, 'packDoubleX2# must have ONE logical tuple argument')
        check(double_tuple(rep(call[2][0])), 'Pack must retain two Double# tuple leaves')
    unpacked = [v for v in applications if v[1][1] == 'unpackDoubleX2#']
    check(all(double_tuple(rep(v)) for v in unpacked), 'Unpack result is not two Double# leaves')
    bindings = {b['name']: b for b in module['bindings']}
    for entry in entries():
        lam = bindings[entry['name']]['expr']
        check(lam[0] == 'lam' and len(lam[1]) == entry['arity']
              and all(arg['rep'].get('primReps') == ['IntRep'] for arg in lam[1])
              and lam[3]['resultRep'].get('primReps') == (['IntRep'] if entry['result'] == 'long' else ['DoubleRep']), 'Host entry ABI drift: '+entry['name'])
    frontier = bindings['vectorArgument']['expr']
    check(frontier[0] == 'lam' and frontier[1][0]['rep'].get('kind') == 'vector', 'Missing actual vector formal frontier')
    return dict(vectorProofs=len(vectors), packSites=len(packs), unpackSites=len(unpacked), primitives=sorted(PRIMITIVES),
                compiledEntriesByEntry=compiled_entry_counts(module))


def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--export-only', action='store_true', help='Pre-Tidy/Core + model only; no code generation, native oracle or post-Tidy claim')
    args = parser.parse_args()
    ghc = os.environ.get('GHC', 'ghc')
    check(subprocess.check_output([ghc, '--numeric-version'], text=True).strip() == '9.14.1', 'Requires pinned GHC9.14.1')
    OUT.mkdir(parents=True, exist_ok=True)
    # Never leave an old native/provenance claim attached to a new incomplete run.
    for name in ('oracle.tsv', 'provenance.json'):
        (OUT / name).unlink(missing_ok=True)
    commands = []
    def run(argv, env=None):
        commands.append(dict(argv=argv, environment=env or {}))
        subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True)
    wanted = model_rows()
    (OUT / 'expected.tsv').write_text(''.join('\t'.join(map(str, (*key, answer)))+'\n' for key, answer in wanted.items()))
    run(['compiler/build.sh'])
    stages = ['pre'] if args.export_only else ['pre', 'post']
    spec = importlib.util.spec_from_file_location('doublex2_auditor', ROOT / 'scripts/audit-core.py')
    auditor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(auditor)
    capabilities = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    audits, structure = {}, {}
    artifacts = [OUT / 'expected.tsv']
    for stage in stages:
        module_path = OUT / f'{stage}-core/SimdDoubleX2.json'
        module_path.unlink(missing_ok=True)
        options = ['-fno-code', '-fwrite-if-simplified-core'] if args.export_only else []
        if stage == 'post':
            options += ['-fplugin-opt=THC.Plugin:post-tidy']
        run(['compiler/export.sh', *options, str(FIXTURE)],
            dict(THC_CORE_OUT=str(module_path.parent), THC_GHC_OUT=str(OUT / f'{stage}-ghc'), THC_SOURCE_NOTES='true'))
        module = json.loads(module_path.read_text())
        structure[stage] = inventory(module, stage)
        audits[stage] = {name: auditor.Audit([(str(module_path), module)], capabilities).run([name])
                         for name in [e['name'] for e in entries()] + ['vectorArgument']}
        check(not audits[stage]['vectorArgument']['accepted'] and any(
              i['code'] == 'vector-boundary' and i['detail'] == ('vector host argument' if 'arguments' in capabilities.get('vectorTransport', []) else 'vector formal argument')
              for i in audits[stage]['vectorArgument']['issues']), 'Vector ABI frontier was not rejected specifically')
        audit_path = OUT / f'{stage}-audit.json'
        audit_path.write_text(json.dumps(audits[stage], indent=2)+'\n')
        artifacts += [module_path, audit_path]
    native_rows = None
    if not args.export_only:
        native = OUT / 'native'
        native.mkdir(exist_ok=True)
        binary = native / 'doublex2-oracle'
        run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', '-icompiler/test-fixtures',
             '-odir', str(native), '-hidir', str(native), '-o', str(binary), str(NATIVE)])
        commands.append(dict(argv=[str(binary)], stdout=str(OUT / 'oracle.tsv')))
        output = subprocess.check_output([str(binary)], cwd=ROOT, text=True)
        (OUT / 'oracle.tsv').write_text(output)
        actual = parse_rows(output)
        check(actual == wanted, 'Native DoubleX2 oracle disagrees with independent binary64 model: '+str(
            next(((k, actual.get(k), v) for k, v in wanted.items() if actual.get(k) != v), 'extra native rows')))
        native_rows = len(actual)
        artifacts += [OUT / 'oracle.tsv', binary]
    sources = [FIXTURE, NATIVE, Path(__file__).resolve(), ROOT / 'scripts/test-doublex2-model.py', ROOT / 'scripts/doublex2_model.py',
               ROOT / 'scripts/audit-core.py', ROOT / 'scripts/core-capabilities.json',
               ROOT / 'src/main/resources/thc/scalar-primop-signatures.json',
               *sorted((ROOT / 'scripts').glob('core_*.py')), *sorted((ROOT / 'compiler/THC').glob('*.hs')),
               *[ROOT / 'compiler' / name for name in ('build.sh', 'export.sh', 'toolchain.sh')]]
    positives = all(audits[s][e['name']]['accepted'] for s in stages for e in entries())
    provenance = dict(schema=1, vector='doublex2', stages=stages, nativeRows=native_rows,
        modelMatched=True if native_rows is not None else None, modelRows=len(wanted), entries=entries(),
        frontiers=[dict(name='vectorArgument', arity=1, reason='public host vector arguments are unsupported')],
        positiveAuditsAccepted=positives, audits=audits, structure=structure, commands=commands,
        sources=[record(p) for p in sources], artifacts=[record(p) for p in artifacts],
        toolchain=dict(ghcVersion='9.14.1', host=platform.node(), machine=platform.machine(), system=platform.platform(),
            ghcInfo=subprocess.check_output([ghc, '--info'], text=True)),
        claim='Native/model comparison plus exact Core metadata and static audit; no installed guest or hardware SIMD claim.'
              if native_rows is not None else 'Pre-Tidy Core and independent model only; NO native/post-Tidy validation.',
        limitations=['Non-NaN Double results compare raw 64-bit encodings; arithmetic NaNs compare by class.',
                     'Finite arithmetic checks use bounded double2Int signatures; exceptional cases never convert to Int.',
                     'This corpus tests local operations; guest vector transport has separate evidence. Public host vector values, heap captures and fields remain unsupported.'])
    (OUT / 'provenance.json').write_text(json.dumps(provenance, indent=2)+'\n')
    print(f'DoubleX2 stages={stages}, native rows={native_rows}, model rows={len(wanted)}, positive strict audits={positives}')
    check(positives, 'DoubleX2 strict audit rejected a positive entry; see stage audit reports (no support claim)')


if __name__ == '__main__':
    main()
