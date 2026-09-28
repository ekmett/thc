# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Exact logical tuple ingress proofs; no capability is enabled by this helper.

The recursive logical tree remains distinct from its flattened storage. VOID
leaves consume no storage but do not become empty tuples. Lifted reference leaves
retain their own evaluatedness; demanding the surrounding tuple does not force them.
"""

LONG_REPS = {'IntRep', 'WordRep', 'Int8Rep', 'Word8Rep', 'Int16Rep', 'Word16Rep',
             'Int32Rep', 'Word32Rep', 'Int64Rep', 'Word64Rep'}
BOXED_REPS = {'BoxedRep (Just Lifted)', 'BoxedRep (Just Unlifted)', 'BoxedRep Nothing'}
from core_vectors import is_vector, proof_error as vector_proof_error
from core_sums import is_sum, proof_error as sum_proof_error


def contains_tuple(proof):
    if not isinstance(proof, dict):
        return False
    return (proof.get('aggregate') == 'unboxed-tuple' or
            any(contains_tuple(child) for key in ('components', 'alternatives')
                for child in (proof.get(key) if isinstance(proof.get(key), list) else [])))


def proof_error(proof, allow_vectors=False, allow_addresses=False, allow_sums=False):
    """Mirror TupleShape.validate; unknown boxed levity still has pointer storage."""
    def visit(rep):
        if not isinstance(rep, dict) or type(rep.get('evaluated')) is not bool:
            raise ValueError('Missing exact tuple input representation record')
        registers = rep.get('primReps')
        aggregate = rep.get('aggregate')
        if aggregate == 'unboxed-tuple':
            if vector_proof_error(rep):
                raise ValueError('Invalid physical vector annotation on tuple input')
            if rep.get('kind') != 'unknown' or not isinstance(rep.get('components'), list):
                raise ValueError('Missing exact recursive tuple input components')
            children = [visit(child) for child in rep['components']]
            flattened = None if any(child is None for child in children) else [r for child in children for r in child]
            if registers != flattened:
                raise ValueError('Tuple input components disagree with physical representations')
            return flattened
        if is_sum(rep) and allow_sums:
            error = sum_proof_error(rep)
            if error:
                raise ValueError('Invalid sum tuple input component: ' + error)
            return registers
        if is_vector(rep) and allow_vectors:
            if vector_proof_error(rep):
                raise ValueError('Invalid exact vector tuple input component')
            return registers
        if 'aggregate' in rep or rep.get('kind') == 'vector' or 'vector' in rep:
            raise ValueError('Sum/vector tuple input component unsupported')
        if not isinstance(registers, list) or any(not isinstance(r, str) for r in registers):
            raise ValueError('Unresolved tuple input primitive representations')
        kind = rep.get('kind')
        valid = (kind == 'void' and registers == [] or
                 kind == 'long' and len(registers) == 1 and registers[0] in LONG_REPS or
                 kind == 'float' and registers == ['FloatRep'] or
                 kind == 'double' and registers == ['DoubleRep'] or
                 allow_addresses and kind == 'address' and registers == ['AddrRep'] and rep['evaluated'] or
                 kind in ('object', 'data', 'closure') and len(registers) == 1 and registers[0] in BOXED_REPS)
        if not valid:
            raise ValueError('Unsupported or unknown tuple input leaf representation')
        return registers

    try:
        if not isinstance(proof, dict) or proof.get('aggregate') != 'unboxed-tuple':
            raise ValueError('Exact unboxed tuple input proof required')
        visit(proof)
    except ValueError as error:
        return str(error)
    return None
