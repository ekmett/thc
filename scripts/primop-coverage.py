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
    return dict(schema=2, ghc='9.14.1', counts=dict(total=len(inventory),
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
    """Count registered runtime implementations independently of carrier shape."""
    if scalars.get('schema') != 1 or scalars.get('ghc') != '9.14.1' or scalars.get('targetWordSize') != 64:
        raise ValueError('Expected the pinned 64-bit scalar signature contract')
    signatures = scalars['primitives']
    inventory = {row['name']: row for row in data['primitives']}
    for name, signature in signatures.items():
        row = inventory.get(name)
        if not row or not row['advertised'] or len(signature['arguments']) != row['valueArity']:
            raise ValueError(f'Stale scalar signature contract: {name}')
    counts = dict(implemented=0, missing=0)
    for row in data['primitives']:
        name = row['name']
        status = 'implemented' if row['advertised'] else 'missing'
        contract = 'No declared lowering'
        if row['advertised']:
            contract = 'Specialized lowering'
            for key, label in (('tuplePrimitives', 'Scalar tuple result'),
                               ('managedByteArrayPrimitives', 'Byte-array operation'),
                               ('managedArrayPrimitives', 'Boxed-array operation'),
                               ('managedMutVarPrimitives', 'Mutable-reference operation'),
                               ('managedStablePtrPrimitives', 'Stable-pointer operation'),
                               ('managedStableNamePrimitives', 'Stable-name operation'),
                               ('managedWeakPrimitives', 'Weak-pointer operation'),
                               ('managedThreadPrimitives', 'Thread operation'),
                               ('managedSTMPrimitives', 'STM operation'),
                               ('managedCompactPrimitives', 'Compact-region operation'),
                               ('managedCompactImagePrimitives', 'Compact image or heap-address operation'),
                               ('managedMVarPrimitives', 'MVar operation'),
                               ('managedPinnedMemoryPrimitives', 'Pointer or pinned-memory operation')):
                if name in capability.get(key, {}):
                    contract = label
                    break
            if name in signatures:
                signature = signatures[name]
                contract = ('Pointer scalar signature' if 'AddrRep' in signature['arguments'] + [signature['result']]
                            else 'Numeric scalar signature')
            if name == 'tagToEnum#' and 'tagToEnum' in capability:
                contract = 'Nullary constructor-family operation'
            if name in ('dataToTagSmall#', 'dataToTagLarge#') and 'dataToTag' in capability:
                contract = 'Algebraic constructor-family operation'
        row.update(status=status, contract=contract)
        counts[status] += 1
    data['implementationCounts'] = counts
    data['limitations'] = list(capability.get('limitations', []))
    data['claim'] = ('Runtime implementations registered for the pinned 64-bit GHC primop inventory. '
                     'Registration follows implementation and ordinary testing, not formal proof. '
                     'Concrete known limitations remain documented separately; representation alone '
                     'does not make an implementation partial. Primop coverage is not whole-GHC feature parity.')
    return data


def checklist(data, capability):
    counts = data['implementationCounts']
    total = data['counts']['total']
    percentage = 100 * counts['implemented'] / total if total else 0
    lines = [
        '# Primop checklist', '',
        '<!-- Generated by scripts/primop-coverage.py; change capabilities, not this list. -->', '',
        "GHC 9.14.1 exposes **%d primops** on the pinned 64-bit target. This list is" % total,
        'generated from `allThePrimOps`, the [runtime capabilities](../scripts/core-capabilities.json)',
        'and the [shared scalar signatures](../src/main/resources/thc/scalar-primop-signatures.json).', '',
        f"**Implementation coverage: {counts['implemented']} / {total} ({percentage:.1f}%).**", '',
        '| Status | Count | Meaning |', '| --- | ---: | --- |',
        f"| Implemented | {counts['implemented']} | A runtime implementation is registered in the capability inventory. |",
        f"| Missing | {counts['missing']} | No runtime implementation is registered. |", '',
        'Implemented means translating the GHC operation to a sensible runtime implementation and',
        'checking it with ordinary tests. It does not require formal proof or exhaustive input testing.',
        'The capability inventory records those implementations; accepting a name in the exporter',
        'alone is not sufficient grounds to add one.', '',
        'Numeric, tuple, vector, pointer and stateful operations use the same counting rule.',
        'A Sulong pointer abstraction, managed storage, exact vector species, the selected 64-bit',
        'target and GHC input preconditions do not themselves make an implementation partial.', '',
        'The [primop behavior reference](primop-behavior.md) names known differences and limits',
        'primop by primop, including automatic weak finalization and asynchronous-delivery restrictions.',
        'Machine-readable capability notes are reproduced below; see also the [coverage guide](README.md).',
        'Primop implementation coverage is one part of GHC feature parity, alongside the compiler,',
        'libraries, FFI and runtime. Arity counts logical arguments, not flattened tuple fields.', '',
        '## Updating the list', '',
        'After implementing and testing a primitive, update its capability contract. For a new',
        'monomorphic scalar operation, also regenerate the shared signature table:', '',
        '```sh', 'python3 scripts/generate-scalar-signatures.py --write',
        'python3 scripts/primop-coverage.py --write-checklist',
        'python3 scripts/primop-coverage.py --check', 'python3 scripts/test-primop-coverage.py', '```', '',
        'CI checks this file against a fresh query of the pinned GHC API. The schema-2 report',
        'in `build/primop-coverage.json` retains exact GHC signatures, contract families, limitations',
        'and generation provenance. `implementationCounts` replaces the old `supportCounts`, whose',
        'supported/partial split incorrectly treated every non-numeric-scalar implementation as partial.', '',
        '## Known behavior and runtime limits', '',
        'The following describes implemented behavior and specific remaining restrictions.',
        'Shared runtime gaps are not automatically attributed to every operation using that runtime.', '',
    ]
    lines.extend('- ' + item for item in capability.get('limitations', []))
    for status, title in (('implemented', 'Implemented primops'), ('missing', 'Missing primops')):
        lines.extend(['', f'## {title}', ''])
        if status == 'missing':
            lines.extend(['<details>', '<summary>Remaining GHC primops, in name order</summary>', ''])
        for row in data['primitives']:
            if row['status'] != status:
                continue
            mark = 'x' if status == 'implemented' else ' '
            contract = f" — {row['contract']}" if status == 'implemented' else ''
            lines.append(f"- [{mark}] `{row['name']}` — arity {row['valueArity']}{contract}")
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
    print(f"GHC primop inventory: {data['implementationCounts']['implemented']} implemented / {len(rows)} total; "
          f"names and arities agree; {data['implementationCounts']}")


if __name__ == '__main__':
    main()
