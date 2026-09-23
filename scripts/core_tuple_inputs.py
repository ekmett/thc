"""Exact logical tuple ingress proofs; no capability is enabled by this helper.

The recursive logical tree remains distinct from its flattened storage. VOID
leaves consume no storage but do not become empty tuples. Lifted reference leaves
retain their own evaluatedness; demanding the surrounding tuple does not force them.
"""

LONG_REPS = {'IntRep', 'WordRep', 'Int8Rep', 'Word8Rep', 'Int16Rep', 'Word16Rep',
             'Int32Rep', 'Word32Rep', 'Int64Rep', 'Word64Rep'}
BOXED_REPS = {'BoxedRep (Just Lifted)', 'BoxedRep (Just Unlifted)'}


def contains_tuple(proof):
    if not isinstance(proof, dict):
        return False
    return (proof.get('aggregate') == 'unboxed-tuple' or
            any(contains_tuple(child) for key in ('components', 'alternatives')
                for child in (proof.get(key) if isinstance(proof.get(key), list) else [])))


def proof_error(proof):
    """Mirror TupleShape.validate plus the input-only known boxed-levity guard."""
    def visit(rep):
        if not isinstance(rep, dict) or type(rep.get('evaluated')) is not bool:
            raise ValueError('Missing exact tuple input representation record')
        registers = rep.get('primReps')
        if not isinstance(registers, list) or any(not isinstance(r, str) for r in registers):
            raise ValueError('Unresolved tuple input primitive representations')
        aggregate = rep.get('aggregate')
        if aggregate == 'unboxed-tuple':
            if rep.get('kind') != 'unknown' or not isinstance(rep.get('components'), list):
                raise ValueError('Missing exact recursive tuple input components')
            flattened = [r for child in rep['components'] for r in visit(child)]
            if registers != flattened:
                raise ValueError('Tuple input components disagree with physical representations')
            return flattened
        if 'aggregate' in rep or rep.get('kind') == 'vector' or 'vector' in rep:
            raise ValueError('Sum/vector tuple input component unsupported')
        kind = rep.get('kind')
        valid = (kind == 'void' and registers == [] or
                 kind == 'long' and len(registers) == 1 and registers[0] in LONG_REPS or
                 kind == 'float' and registers == ['FloatRep'] or
                 kind == 'double' and registers == ['DoubleRep'] or
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
