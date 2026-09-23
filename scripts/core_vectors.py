"""Exact bounded vector proof checks shared by Core audit sites."""
VECTOR_REP = {'kind': 'vector', 'primReps': ['VecRep 2 Int64ElemRep'], 'evaluated': True,
              'vector': {'lanes': 2, 'element': 'Int64ElemRep'}}
LANE_REP = {'kind': 'long', 'primReps': ['Int64Rep'], 'evaluated': True}
TUPLE_REP = {'kind': 'unknown', 'primReps': ['Int64Rep', 'Int64Rep'], 'evaluated': True,
             'aggregate': 'unboxed-tuple', 'components': [LANE_REP, LANE_REP]}
OPERATIONS = {
    'packInt64X2#': ([TUPLE_REP], VECTOR_REP),
    'unpackInt64X2#': ([VECTOR_REP], TUPLE_REP),
    'broadcastInt64X2#': ([LANE_REP], VECTOR_REP),
    'plusInt64X2#': ([VECTOR_REP, VECTOR_REP], VECTOR_REP),
    'minusInt64X2#': ([VECTOR_REP, VECTOR_REP], VECTOR_REP),
    'negateInt64X2#': ([VECTOR_REP], VECTOR_REP),
}
VECTOR32_REP = {'kind': 'vector', 'primReps': ['VecRep 4 Int32ElemRep'], 'evaluated': True,
                'vector': {'lanes': 4, 'element': 'Int32ElemRep'}}
LANE32_REP = {'kind': 'long', 'primReps': ['Int32Rep'], 'evaluated': True}
TUPLE32_REP = {'kind': 'unknown', 'primReps': ['Int32Rep'] * 4, 'evaluated': True,
               'aggregate': 'unboxed-tuple', 'components': [LANE32_REP] * 4}
OPERATIONS.update({
    'packInt32X4#': ([TUPLE32_REP], VECTOR32_REP),
    'unpackInt32X4#': ([VECTOR32_REP], TUPLE32_REP),
    'broadcastInt32X4#': ([LANE32_REP], VECTOR32_REP),
    'plusInt32X4#': ([VECTOR32_REP, VECTOR32_REP], VECTOR32_REP),
    'minusInt32X4#': ([VECTOR32_REP, VECTOR32_REP], VECTOR32_REP),
    'negateInt32X4#': ([VECTOR32_REP], VECTOR32_REP),
})
def is_vector(rep): return isinstance(rep, dict) and rep.get('kind') == 'vector'
def proof_error(rep):
    registers = rep.get('primReps')
    has_vector = isinstance(registers, list) and any(isinstance(r, str) and r.startswith('VecRep ') for r in registers)
    if not is_vector(rep):
        return 'Vector representation lacks exact vector metadata' if 'vector' in rep or has_vector and 'aggregate' not in rep else None
    shape = rep.get('vector')
    if not isinstance(shape, dict) or type(shape.get('lanes')) is not int or not isinstance(shape.get('element'), str) or 'aggregate' in rep:
        return 'Invalid Core vector shape'
    if registers != [f"VecRep {shape['lanes']} {shape['element']}"]:
        return 'Vector shape disagrees with primitive representation'
    if shape not in (VECTOR_REP['vector'], VECTOR32_REP['vector']):
        return 'Unsupported Core vector representation'
    return None
