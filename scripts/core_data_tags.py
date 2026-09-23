"""Pinned GHC dataToTag family proof; no nominal information is guessed from pointers."""
OPERATIONS = {'dataToTagSmall#', 'dataToTagLarge#'}

def validate(expression, operand, constructors):
    name = expression[1][1]
    def check(ok, detail):
        if not ok: raise ValueError('dataToTag: ' + detail)
    check(name in OPERATIONS and len(expression[2]) == 1, 'exactly one data operand required')
    def scalar(proof, kind, reps):
        return (isinstance(proof, dict) and proof.get('kind') == kind and proof.get('primReps') == reps
                and 'aggregate' not in proof and 'vector' not in proof)
    lifted = scalar(operand, 'data', ['BoxedRep (Just Lifted)'])
    check(lifted or scalar(operand, 'data', ['BoxedRep (Just Unlifted)']), 'exact algebraic data operand required')
    check(expression[3] == [lifted] and all(type(v) is bool for v in expression[3]), 'operand levity mismatch')
    metadata = expression[6] if len(expression) > 6 and isinstance(expression[6], dict) else {}
    check(scalar(metadata.get('rep'), 'long', ['IntRep']), 'exact IntRep result required')
    family = metadata.get('dataToTagFamily')
    check(isinstance(family, dict) and set(family) == {'typeConstructor', 'constructors', 'smallFamilyLimit', 'smallFamily'}
          and isinstance(family.get('typeConstructor'), str) and bool(family['typeConstructor']), 'missing/malformed concrete family')
    ids = family['constructors']
    check(isinstance(ids, list) and bool(ids) and all(isinstance(k, str) and k for k in ids)
          and len(set(ids)) == len(ids), 'invalid ordered constructors')
    check(type(family['smallFamilyLimit']) is int and family['smallFamilyLimit'] == 7
          and type(family['smallFamily']) is bool and family['smallFamily'] == (len(ids) <= 7), 'invalid pinned target small-family proof')
    check((name == 'dataToTagSmall#') == family['smallFamily'], 'primitive variant does not match family size')
    for key, con in constructors.items():
        declared = con.get('dataToTagFamily')
        if isinstance(declared, dict) and declared.get('typeConstructor') == family['typeConstructor']:
            check(declared == family and key in ids, 'contradictory supplied family record ' + key)
    for index, key in enumerate(ids):
        con = constructors.get(key, {})
        check(con.get('dataToTagFamily') == family and con.get('kind') == 'boxed'
              and type(con.get('arity')) is int and 0 <= con['arity'] <= 2**31-1
              and type(con.get('tag')) is int and con['tag'] == index+1, 'contradictory/missing constructor ' + key)
    return ids
