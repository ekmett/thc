# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Exact scalar sum proofs and GHC slots; no width-only inference or coercions."""
LIFTED = 'BoxedRep (Just Lifted)'
UNLIFTED = 'BoxedRep (Just Unlifted)'
ORDER = (LIFTED, UNLIFTED, 'WordRep', 'Word64Rep', 'FloatRep', 'DoubleRep')
WORD = {'IntRep', 'WordRep', 'Int8Rep', 'Word8Rep', 'Int16Rep', 'Word16Rep', 'Int32Rep', 'Word32Rep'}
WIDE = {'Int64Rep', 'Word64Rep'}


def is_sum(proof):
    return isinstance(proof, dict) and proof.get('aggregate') == 'unboxed-sum'


def contains_sum(proof):
    return is_sum(proof) or (isinstance(proof, dict) and
        isinstance(proof.get('components'), list) and any(contains_sum(c) for c in proof['components']))


def lifted_payload(proof):
    return (isinstance(proof, dict) and 'aggregate' not in proof and
            proof.get('primReps') == [LIFTED])


def payload_leaves(proof):
    if not isinstance(proof, dict) or type(proof.get('evaluated')) is not bool:
        raise ValueError('Missing exact sum payload proof')
    aggregate, kind, reps = proof.get('aggregate'), proof.get('kind'), proof.get('primReps')
    if aggregate == 'unboxed-tuple':
        children = proof.get('components')
        if kind != 'unknown' or not isinstance(children, list):
            raise ValueError('Missing exact sum tuple payload components')
        leaves = [leaf for child in children for leaf in payload_leaves(child)]
        if reps != leaves:
            raise ValueError('Tuple payload components disagree with physical representations')
        return leaves
    if aggregate is not None or 'vector' in proof or kind == 'vector':
        raise ValueError('Nested sum or vector payload is unsupported')
    if kind == 'void' and reps == []:
        return []
    if not isinstance(reps, list) or len(reps) != 1:
        raise ValueError('Unresolved sum payload representation')
    rep = reps[0]
    valid = (kind == 'long' and rep in WORD | WIDE or
             kind == 'float' and rep == 'FloatRep' or kind == 'double' and rep == 'DoubleRep' or
             kind in ('data', 'closure', 'object') and rep in (LIFTED, UNLIFTED))
    if not valid:
        raise ValueError('Unsupported sum payload representation')
    return reps


def layout(alternatives):
    if not isinstance(alternatives, list) or len(alternatives) < 2:
        raise ValueError('Unboxed sum requires at least two exact alternatives')
    fields = [[('WordRep' if r in WORD else 'Word64Rep' if r in WIDE else r)
               for r in payload_leaves(alternative)] for alternative in alternatives]
    # Mirror GHC.Types.RepType.ubxSumRepType, including Word/Word64 sharing.
    slots = []
    for row in fields:
        needed = sorted(row, key=ORDER.index)
        merged, left, right = [], 0, 0
        while left < len(slots) and right < len(needed):
            common = fits(slots[left], needed[right])
            if common is not None:
                merged.append(common); left += 1; right += 1
            elif ORDER.index(needed[right]) < ORDER.index(slots[left]):
                merged.append(needed[right]); right += 1
            else:
                merged.append(slots[left]); left += 1
        slots = merged + slots[left:] + needed[right:]
    physical = ['WordRep'] + slots
    projections = []
    for row in fields:
        used, projected = set(), []
        for rep in row:
            index = next(i for i, target in enumerate(physical)
                         if i > 0 and i not in used and fits(rep, target) == target)
            used.add(index); projected.append(index)
        projections.append(projected)
    return physical, projections


def fits(left, right):
    if left == right:
        return left
    if left in ('WordRep', 'Word64Rep') and right in ('WordRep', 'Word64Rep'):
        return 'Word64Rep'
    return None


def proof_error(proof):
    try:
        if not is_sum(proof) or proof.get('kind') != 'unknown' or type(proof.get('evaluated')) is not bool:
            raise ValueError('Sum proof must retain exact aggregate kind and WHNF evidence')
        if 'components' in proof or 'vector' in proof:
            raise ValueError('Sum proof cannot also describe a tuple or vector')
        physical, projections = layout(proof.get('alternatives'))
        if type(proof.get('tagSlot')) is not int or proof['tagSlot'] != 0 or proof.get('primReps') != physical:
            raise ValueError('Sum physical representation or tag slot mismatch')
        actual = proof.get('alternativeSlots')
        if (not isinstance(actual, list) or any(not isinstance(row, list) or
                any(type(index) is not int for index in row) for row in actual) or actual != projections):
            raise ValueError('Sum alternative projection mismatch')
    except (TypeError, ValueError) as error:
        return str(error)
    return None


def constructor_tag(info, arity, proof=None):
    if (not isinstance(info, dict) or info.get('kind') != 'unboxed-sum' or type(arity) is not int or arity != 1 or
            type(info.get('arity')) is not int or info['arity'] != 1 or
            type(info.get('sumArity')) is not int or info['sumArity'] < 2):
        raise ValueError('Sum constructor family or payload arity mismatch')
    if proof is not None and (not is_sum(proof) or not isinstance(proof.get('alternatives'), list) or
                              len(proof['alternatives']) != info['sumArity']):
        raise ValueError('Sum constructor family or payload arity mismatch')
    tag = info.get('tag')
    if type(tag) is not int or not 1 <= tag <= info['sumArity']:
        raise ValueError('Invalid sum constructor tag')
    return tag
