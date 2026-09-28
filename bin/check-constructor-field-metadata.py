#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Check precise constructor worker field proofs and their evaluation obligations."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LONG_REPS = {f'{prefix}{width}Rep' for prefix in ('Int', 'Word') for width in ('', '8', '16', '32', '64')}
BOXED_REPS = {'BoxedRep (Just Lifted)', 'BoxedRep (Just Unlifted)', 'BoxedRep Nothing'}


def audit(module):
    for constructor in module['constructors']:
        assert type(constructor['arity']) is int and constructor['arity'] >= 0, constructor['id']
        fields = constructor['fieldTypes']
        assert len(fields) == constructor['arity'] == len(constructor['fieldReps']), constructor['id']
        assert len(fields) == len(constructor['strictFields']) == len(constructor['fieldLifted']), constructor['id']
        for index, proof in enumerate(fields):
            assert proof['primReps'] == constructor['fieldReps'][index], (constructor['id'], index)
            assert proof['kind'] in ('long', 'address', 'void', 'data', 'closure', 'object', 'unknown'), proof
            registers = proof['primReps']
            assert registers is None or isinstance(registers, list) and all(type(r) is str for r in registers), proof
            kind = proof['kind']
            if kind == 'long':
                assert len(registers or []) == 1 and registers[0] in LONG_REPS, proof
            elif kind == 'address':
                assert registers == ['AddrRep'], proof
            elif kind == 'void':
                assert registers == [], proof
            elif kind in ('data', 'closure', 'object'):
                assert len(registers or []) == 1 and registers[0] in BOXED_REPS, proof
            strict = constructor['strictFields'][index]
            lifted = constructor['fieldLifted'][index]
            assert type(strict) is bool and (lifted is None or type(lifted) is bool), (constructor['id'], index)
            if registers == ['BoxedRep (Just Lifted)']:
                assert lifted is True, (constructor['id'], index, registers, lifted)
            elif registers == ['BoxedRep (Just Unlifted)'] or registers == [] or \
                    registers is not None and len(registers) == 1 and registers[0] not in BOXED_REPS:
                assert lifted is False, (constructor['id'], index, registers, lifted)
            expected = strict or lifted is False
            assert proof['evaluated'] is expected, (constructor['id'], index, proof)
    return len(module['constructors'])


def fixture(module):
    constructors = {c['name']: c for c in module['constructors']}
    fields = constructors['Record']['fieldTypes']
    assert [(p['kind'], p['evaluated']) for p in fields] == [
        ('data', True), ('data', False), ('object', True), ('object', False),
        ('closure', True), ('closure', False)], fields
    assert constructors['Primitive']['fieldTypes'] == [
        {'primReps': ['IntRep'], 'kind': 'long', 'evaluated': True}]
    wrapped = constructors['NewtypeField']['fieldTypes']
    assert len(wrapped) == 1 and wrapped[0]['kind'] == 'object' and wrapped[0]['evaluated'] is False
    evidence = constructors['Evidence']['fieldTypes']
    assert evidence[0] == {'primReps': [], 'kind': 'void', 'evaluated': True}, evidence
    assert evidence[1]['kind'] == 'data' and evidence[1]['evaluated'] is False, evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('modules', nargs='*', type=Path)
    args = parser.parse_args()
    files = args.modules or [ROOT / 'build/core/ConstructorFieldAudit.json']
    for path in files:
        module = json.loads(path.read_text())
        count = audit(module)
        if module['module'] == 'ConstructorFieldAudit':
            fixture(module)
        if module['module'] == 'Data.Map.Internal':
            bin = next(c for c in module['constructors'] if c['id'].split(':', 1)[-1] == 'Data.Map.Internal.Bin')
            assert [(p['kind'], p['evaluated']) for p in bin['fieldTypes']] == [
                ('long', True), ('object', True), ('object', False), ('data', True), ('data', True)], bin
        print(f"PASS {module['module']}: {count} aligned constructor field proofs")
    if not args.modules:
        print('PASS: strict/lazy data and function fields, polymorphic Object fields, primitive storage, newtype conservatism, coercion slots')


if __name__ == '__main__':
    main()
