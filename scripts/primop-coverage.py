#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Inventory pinned GHC primops and generate the capability-derived checklist."""
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
CHECKLIST = ROOT / 'docs/primops.md'
SCALARS = ROOT / 'src/main/resources/thc/scalar-primop-signatures.json'
# allThePrimOps includes GHC's generated vector families and target-specific table.
QUERY = r'''mapM_ (\op -> let (_,_,_,arity,_) = GHC.Builtin.PrimOps.primOpSig op in putStrLn (GHC.Types.Name.Occurrence.occNameString (GHC.Builtin.PrimOps.primOpOcc op) ++ "\t" ++ show arity ++ "\t" ++ unwords (words (GHC.Utils.Outputable.showSDocUnsafe (GHC.Utils.Outputable.ppr (GHC.Builtin.PrimOps.primOpType op)))))) GHC.Builtin.PrimOps.allThePrimOps'''


def report(rows, advertised):
    inventory = {}
    for name, arity, signature in rows:
        if name in inventory:
            raise ValueError(f'Duplicate GHC primop: {name}')
        inventory[name] = dict(name=name, valueArity=int(arity), signature=signature,
                               advertised=name in advertised)
    for name, arity in advertised.items():
        if name not in inventory:
            raise ValueError(f'Advertised primitive absent from pinned GHC: {name}')
        expected = inventory[name]['valueArity']
        if type(arity) is not int or arity != expected:
            raise ValueError(f'Primitive arity mismatch: {name}: THC {arity!r}, GHC {expected}')
    return dict(schema=1, ghc='9.14.1', counts=dict(total=len(inventory),
                advertised=len(advertised), unadvertised=len(inventory)-len(advertised)),
                claim='Advertised names and value arities only; not proof of runtime semantics, lowering, or tested input coverage.',
                primitives=[inventory[name] for name in sorted(inventory)])


def declared_primitives(capability):
    """Include the family gates which the auditor handles before generic primops."""
    advertised = dict(capability['primitives'])
    for key, contract, names in (
        ('tagToEnum', 'concrete-nullary-family', ('tagToEnum#',)),
        ('dataToTag', 'concrete-algebraic-family-64', ('dataToTagSmall#', 'dataToTagLarge#')),
    ):
        if key not in capability:
            continue
        if capability[key] != contract:
            raise ValueError(f'Unrecognized {key} contract: {capability[key]!r}')
        for name in names:
            if name in advertised and (type(advertised[name]) is not int or advertised[name] != 1):
                raise ValueError(f'Contradictory family primitive arity: {name}')
            advertised[name] = 1
    return advertised


def classify(data, capability, scalars):
    """Conservative progress labels, not an independent semantic support registry."""
    if scalars.get('schema') != 1 or scalars.get('ghc') != '9.14.1' or scalars.get('targetWordSize') != 64:
        raise ValueError('Expected the pinned 64-bit scalar signature contract')
    signatures = scalars['primitives']
    inventory = {row['name']: row for row in data['primitives']}
    for name, signature in signatures.items():
        row = inventory.get(name)
        if not row or not row['advertised'] or len(signature['arguments']) != row['valueArity']:
            raise ValueError(f'Stale scalar signature contract: {name}')
    # Address carriers have a deliberately narrower managed-literal domain.
    numeric = {'IntRep', 'WordRep', 'Int8Rep', 'Word8Rep', 'Int16Rep', 'Word16Rep',
               'Int32Rep', 'Word32Rep', 'Int64Rep', 'Word64Rep', 'FloatRep', 'DoubleRep'}
    counts = dict(supported=0, partial=0, missing=0)
    for row in data['primitives']:
        name = row['name']
        signature = signatures.get(name)
        if not row['advertised']:
            status, scope = 'missing', 'No declared lowering'
        elif signature and set(signature['arguments'] + [signature['result']]) <= numeric:
            status, scope = 'supported', 'Fixed scalar contract'
        else:
            status, scope = 'partial', 'Specialized lowering; see capability and coverage limits'
            for key, label in (('tuplePrimitives', 'Exact tuple arithmetic'),
                               ('managedByteArrayPrimitives', 'Managed byte storage'),
                               ('managedArrayPrimitives', 'Managed lifted arrays'),
                               ('managedMutVarPrimitives', 'Managed lazy reference cells'),
                               ('managedMVarPrimitives', 'Managed blocking cells; no guest scheduler or async exceptions')):
                if name in capability.get(key, {}):
                    scope = label
                    break
            if signature and 'AddrRep' in signature['arguments'] + [signature['result']]:
                scope = 'Managed literal addresses only'
            if name == 'tagToEnum#' and 'tagToEnum' in capability:
                scope = capability['tagToEnum']
            if name in ('dataToTagSmall#', 'dataToTagLarge#') and 'dataToTag' in capability:
                scope = capability['dataToTag']
        row.update(status=status, scope=scope)
        counts[status] += 1
    data['supportCounts'] = counts
    data['claim'] = ('Capability-derived progress, not exhaustive semantic or test coverage. '
                     'Supported means an advertised fixed scalar contract on the pinned 64-bit target; '
                     'partial means another advertised, restricted lowering. GHC input preconditions '
                     'and documented Core/calling-convention limits apply to both.')
    return data


def checklist(data, capability):
    counts = data['supportCounts']
    lines = [
        '# Primop checklist', '',
        '<!-- Generated by scripts/primop-coverage.py; change capabilities, not this list. -->', '',
        "GHC 9.14.1 exposes **%d primops** on the pinned 64-bit target. This list is" % data['counts']['total'],
        'generated from `allThePrimOps`, the [runtime capabilities](../scripts/core-capabilities.json)',
        'and the [shared scalar signatures](../src/main/resources/thc/scalar-primop-signatures.json).', '',
        '| Status | Count | Meaning |', '| --- | ---: | --- |',
        f"| Supported | {counts['supported']} | Implemented fixed numeric/character scalar forms. |",
        f"| Partial | {counts['partial']} | Implemented with additional representation, storage or use-site limits. |",
        f"| Missing | {counts['missing']} | No declared lowering. |", '',
        'A checked box records the scalar contract, **not** unrestricted Haskell support or exhaustive',
        'testing. Defined-input preconditions, exact representation proofs and the current call ABI still',
        'apply. Partial entries deliberately stay unchecked: a local SIMD operation does not imply vector',
        'arguments or results, and managed array access does not imply foreign memory access.',
        'Names and arities alone cannot certify an implementation. See the [coverage guide](README.md)',
        'for native tests, retained graphs and the boundaries of each family. Arity counts logical value',
        'arguments, not flattened tuple fields.', '',
        '## Updating the list', '',
        'After implementing and testing a primitive, update its existing capability contract. For a new',
        'monomorphic scalar operation, also regenerate the shared signature table:', '',
        '```sh', 'python3 scripts/generate-scalar-signatures.py --write',
        'python3 scripts/primop-coverage.py --write-checklist',
        'python3 scripts/primop-coverage.py --check', 'python3 scripts/test-primop-coverage.py', '```', '',
        'CI checks this file against a fresh query of the pinned GHC API. The machine-readable report',
        'in `build/primop-coverage.json` includes exact GHC signatures and generation provenance.',
        'New capability entries default to partial unless the existing scalar contract establishes the',
        'fixed numeric form; adding a name cannot mark an arbitrary operation fully supported.', '',
        '## Current aggregate and address limits', '',
    ]
    lines.extend('- ' + item for item in capability.get('limitations', []))
    for status, title in (('supported', 'Supported scalar forms'), ('partial', 'Partial forms'), ('missing', 'Missing forms')):
        lines.extend(['', f'## {title}', ''])
        if status == 'missing':
            lines.extend(['<details>', '<summary>Remaining GHC primops, in name order</summary>', ''])
        for row in data['primitives']:
            if row['status'] != status:
                continue
            mark = 'x' if status == 'supported' else ' '
            scope = f" — {row['scope']}" if status == 'partial' else ''
            lines.append(f"- [{mark}] `{row['name']}` — arity {row['valueArity']}{scope}")
        if status == 'missing':
            lines.extend(['', '</details>'])
    return '\n'.join(lines) + '\n'


def check_document(path, expected):
    if not path.is_file() or path.read_text() != expected:
        raise ValueError(f'{path.name} is stale; run python3 scripts/primop-coverage.py --write-checklist')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/primop-coverage.json')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--write-checklist', action='store_true', help='regenerate docs/primops.md')
    mode.add_argument('--check', action='store_true', help='fail if docs/primops.md differs from current contracts')
    args = parser.parse_args()
    args.output.unlink(missing_ok=True)
    ghc = os.environ.get('GHC', 'ghc')
    version = subprocess.check_output([ghc, '--numeric-version'], text=True).strip()
    if version != '9.14.1':
        raise SystemExit(f'THC requires GHC 9.14.1; found {version}')
    info = subprocess.check_output([ghc, '--info'], text=True)
    if dict(ast.literal_eval(info))['target word size in bits'] != '64':
        raise SystemExit('The capability checklist describes the pinned 64-bit target only')
    command = [ghc, '-ignore-dot-ghci', '-package', 'ghc', '-e', QUERY]
    result = subprocess.run(command, check=True, text=True, capture_output=True)
    rows = [line.split('\t', 2) for line in result.stdout.splitlines()]
    capability = ROOT / 'scripts/core-capabilities.json'
    cap = json.loads(capability.read_text())
    data = classify(report(rows, declared_primitives(cap)), cap, json.loads(SCALARS.read_text()))
    document = checklist(data, cap)
    if args.write_checklist:
        CHECKLIST.write_text(document)
    if args.check:
        check_document(CHECKLIST, document)
    data['provenance'] = dict(command=command, compilerInfo=info,
        inputs={str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in (capability, SCALARS, Path(__file__).resolve())})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, indent=2) + '\n')
    print(f"GHC primop inventory: {data['counts']['advertised']} declared / {len(rows)} total; names and arities agree; {data['supportCounts']}")


if __name__ == '__main__':
    main()
