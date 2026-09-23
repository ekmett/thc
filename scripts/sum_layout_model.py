"""Independent projection checks for pinned GHC sum metadata, not runtime support."""
WORD = {'IntRep', 'WordRep', 'Int8Rep', 'Word8Rep', 'Int16Rep', 'Word16Rep', 'Int32Rep', 'Word32Rep', 'AddrRep'}
WIDE = {'Int64Rep', 'Word64Rep'}
POINTERS = {'BoxedRep (Just Lifted)', 'BoxedRep (Just Unlifted)'}

def fits(source, target):
    if source in WORD:
        return target in ('WordRep', 'Word64Rep')
    if source in WIDE:
        return target == 'Word64Rep'
    return source == target


def alternative_slots(alternatives, physical):
    """Project source physical leaves into a supplied canonical storage vector.

    This does not reconstruct a logical type or derive a vector from its width.
    Unknown types remain unknown. Distinct pointer levities and floating/vector
    reps must never share a field merely because their host widths agree.
    """
    if alternatives is None or physical is None or any(a.get('primReps') is None for a in alternatives):
        return None
    if any('BoxedRep Nothing' in a['primReps'] for a in alternatives):
        return None
    if not physical or physical[0] != 'WordRep':
        raise ValueError('Missing physical sum tag')
    result = []
    for alternative in alternatives:
        used, ordered = set(), []
        for source in alternative['primReps']:
            destination = next((i for i, target in enumerate(physical) if i > 0 and i not in used and fits(source, target)), None)
            if destination is None:
                raise ValueError('No compatible distinct payload slot')
            used.add(destination); ordered.append(destination)
        result.append(ordered)
    return result
