# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Exact bounded vector proof checks shared by Core audit sites."""
import json
from pathlib import Path

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
    'timesInt32X4#': ([VECTOR32_REP, VECTOR32_REP], VECTOR32_REP),
})
VECTOR16_REP = {'kind': 'vector', 'primReps': ['VecRep 8 Int16ElemRep'], 'evaluated': True,
                'vector': {'lanes': 8, 'element': 'Int16ElemRep'}}
LANE16_REP = {'kind': 'long', 'primReps': ['Int16Rep'], 'evaluated': True}
TUPLE16_REP = {'kind': 'unknown', 'primReps': ['Int16Rep'] * 8, 'evaluated': True,
               'aggregate': 'unboxed-tuple', 'components': [LANE16_REP] * 8}
OPERATIONS.update({
    'packInt16X8#': ([TUPLE16_REP], VECTOR16_REP),
    'unpackInt16X8#': ([VECTOR16_REP], TUPLE16_REP),
    'broadcastInt16X8#': ([LANE16_REP], VECTOR16_REP),
    'plusInt16X8#': ([VECTOR16_REP, VECTOR16_REP], VECTOR16_REP),
    'minusInt16X8#': ([VECTOR16_REP, VECTOR16_REP], VECTOR16_REP),
    'negateInt16X8#': ([VECTOR16_REP], VECTOR16_REP),
    'timesInt16X8#': ([VECTOR16_REP, VECTOR16_REP], VECTOR16_REP),
})
VECTOR8_REP = {'kind': 'vector', 'primReps': ['VecRep 16 Int8ElemRep'], 'evaluated': True,
               'vector': {'lanes': 16, 'element': 'Int8ElemRep'}}
LANE8_REP = {'kind': 'long', 'primReps': ['Int8Rep'], 'evaluated': True}
TUPLE8_REP = {'kind': 'unknown', 'primReps': ['Int8Rep'] * 16, 'evaluated': True,
              'aggregate': 'unboxed-tuple', 'components': [LANE8_REP] * 16}
OPERATIONS.update({
    'packInt8X16#': ([TUPLE8_REP], VECTOR8_REP),
    'unpackInt8X16#': ([VECTOR8_REP], TUPLE8_REP),
    'broadcastInt8X16#': ([LANE8_REP], VECTOR8_REP),
    'plusInt8X16#': ([VECTOR8_REP, VECTOR8_REP], VECTOR8_REP),
    'minusInt8X16#': ([VECTOR8_REP, VECTOR8_REP], VECTOR8_REP),
    'negateInt8X16#': ([VECTOR8_REP], VECTOR8_REP),
    'timesInt8X16#': ([VECTOR8_REP, VECTOR8_REP], VECTOR8_REP),
})
VECTOR_WORD8_REP = {'kind': 'vector', 'primReps': ['VecRep 16 Word8ElemRep'], 'evaluated': True,
                    'vector': {'lanes': 16, 'element': 'Word8ElemRep'}}
LANE_WORD8_REP = {'kind': 'long', 'primReps': ['Word8Rep'], 'evaluated': True}
TUPLE_WORD8_REP = {'kind': 'unknown', 'primReps': ['Word8Rep'] * 16, 'evaluated': True,
                  'aggregate': 'unboxed-tuple', 'components': [LANE_WORD8_REP] * 16}
OPERATIONS.update({
    'packWord8X16#': ([TUPLE_WORD8_REP], VECTOR_WORD8_REP),
    'unpackWord8X16#': ([VECTOR_WORD8_REP], TUPLE_WORD8_REP),
    'broadcastWord8X16#': ([LANE_WORD8_REP], VECTOR_WORD8_REP),
    'plusWord8X16#': ([VECTOR_WORD8_REP, VECTOR_WORD8_REP], VECTOR_WORD8_REP),
    'minusWord8X16#': ([VECTOR_WORD8_REP, VECTOR_WORD8_REP], VECTOR_WORD8_REP),
    'timesWord8X16#': ([VECTOR_WORD8_REP, VECTOR_WORD8_REP], VECTOR_WORD8_REP),
})
VECTOR_WORD16_REP = {'kind': 'vector', 'primReps': ['VecRep 8 Word16ElemRep'], 'evaluated': True,
                    'vector': {'lanes': 8, 'element': 'Word16ElemRep'}}
LANE_WORD16_REP = {'kind': 'long', 'primReps': ['Word16Rep'], 'evaluated': True}
TUPLE_WORD16_REP = {'kind': 'unknown', 'primReps': ['Word16Rep'] * 8, 'evaluated': True,
                  'aggregate': 'unboxed-tuple', 'components': [LANE_WORD16_REP] * 8}
OPERATIONS.update({
    'packWord16X8#': ([TUPLE_WORD16_REP], VECTOR_WORD16_REP),
    'unpackWord16X8#': ([VECTOR_WORD16_REP], TUPLE_WORD16_REP),
    'broadcastWord16X8#': ([LANE_WORD16_REP], VECTOR_WORD16_REP),
    'plusWord16X8#': ([VECTOR_WORD16_REP, VECTOR_WORD16_REP], VECTOR_WORD16_REP),
    'minusWord16X8#': ([VECTOR_WORD16_REP, VECTOR_WORD16_REP], VECTOR_WORD16_REP),
    'timesWord16X8#': ([VECTOR_WORD16_REP, VECTOR_WORD16_REP], VECTOR_WORD16_REP),
})
VECTOR_WORD32_REP = {'kind': 'vector', 'primReps': ['VecRep 4 Word32ElemRep'], 'evaluated': True,
                    'vector': {'lanes': 4, 'element': 'Word32ElemRep'}}
LANE_WORD32_REP = {'kind': 'long', 'primReps': ['Word32Rep'], 'evaluated': True}
TUPLE_WORD32_REP = {'kind': 'unknown', 'primReps': ['Word32Rep'] * 4, 'evaluated': True,
                  'aggregate': 'unboxed-tuple', 'components': [LANE_WORD32_REP] * 4}
OPERATIONS.update({
    'packWord32X4#': ([TUPLE_WORD32_REP], VECTOR_WORD32_REP),
    'unpackWord32X4#': ([VECTOR_WORD32_REP], TUPLE_WORD32_REP),
    'broadcastWord32X4#': ([LANE_WORD32_REP], VECTOR_WORD32_REP),
    'plusWord32X4#': ([VECTOR_WORD32_REP, VECTOR_WORD32_REP], VECTOR_WORD32_REP),
    'minusWord32X4#': ([VECTOR_WORD32_REP, VECTOR_WORD32_REP], VECTOR_WORD32_REP),
    'timesWord32X4#': ([VECTOR_WORD32_REP, VECTOR_WORD32_REP], VECTOR_WORD32_REP),
})
VECTOR_FLOAT_REP = {'kind': 'vector', 'primReps': ['VecRep 4 FloatElemRep'], 'evaluated': True,
                    'vector': {'lanes': 4, 'element': 'FloatElemRep'}}
LANE_FLOAT_REP = {'kind': 'float', 'primReps': ['FloatRep'], 'evaluated': True}
TUPLE_FLOAT_REP = {'kind': 'unknown', 'primReps': ['FloatRep'] * 4, 'evaluated': True,
                   'aggregate': 'unboxed-tuple', 'components': [LANE_FLOAT_REP] * 4}
OPERATIONS.update({
    'packFloatX4#': ([TUPLE_FLOAT_REP], VECTOR_FLOAT_REP),
    'unpackFloatX4#': ([VECTOR_FLOAT_REP], TUPLE_FLOAT_REP),
    'broadcastFloatX4#': ([LANE_FLOAT_REP], VECTOR_FLOAT_REP),
    'plusFloatX4#': ([VECTOR_FLOAT_REP, VECTOR_FLOAT_REP], VECTOR_FLOAT_REP),
    'minusFloatX4#': ([VECTOR_FLOAT_REP, VECTOR_FLOAT_REP], VECTOR_FLOAT_REP),
    'timesFloatX4#': ([VECTOR_FLOAT_REP, VECTOR_FLOAT_REP], VECTOR_FLOAT_REP),
})
VECTOR_DOUBLE_REP = {'kind': 'vector', 'primReps': ['VecRep 2 DoubleElemRep'], 'evaluated': True,
                    'vector': {'lanes': 2, 'element': 'DoubleElemRep'}}
LANE_DOUBLE_REP = {'kind': 'double', 'primReps': ['DoubleRep'], 'evaluated': True}
TUPLE_DOUBLE_REP = {'kind': 'unknown', 'primReps': ['DoubleRep'] * 2, 'evaluated': True,
                   'aggregate': 'unboxed-tuple', 'components': [LANE_DOUBLE_REP] * 2}
OPERATIONS.update({
    'packDoubleX2#': ([TUPLE_DOUBLE_REP], VECTOR_DOUBLE_REP),
    'unpackDoubleX2#': ([VECTOR_DOUBLE_REP], TUPLE_DOUBLE_REP),
    'broadcastDoubleX2#': ([LANE_DOUBLE_REP], VECTOR_DOUBLE_REP),
    'plusDoubleX2#': ([VECTOR_DOUBLE_REP, VECTOR_DOUBLE_REP], VECTOR_DOUBLE_REP),
    'minusDoubleX2#': ([VECTOR_DOUBLE_REP, VECTOR_DOUBLE_REP], VECTOR_DOUBLE_REP),
    'timesDoubleX2#': ([VECTOR_DOUBLE_REP, VECTOR_DOUBLE_REP], VECTOR_DOUBLE_REP),
})

# The same pinned declarative table drives concrete JVM source generation. This
# table describes exact signatures; core-capabilities still gates availability.
GENERATED_SHAPES = []
for family in json.loads((Path(__file__).with_name('simd-families.json')).read_text())['families']:
    shape = dict(lanes=family['lanes'], element=family['element'])
    vector = dict(kind='vector', evaluated=True, primReps=[f"VecRep {shape['lanes']} {shape['element']}"], vector=shape)
    lane = dict(kind={'FloatRep': 'float', 'DoubleRep': 'double'}.get(family['laneRep'], 'long'),
                evaluated=True, primReps=[family['laneRep']])
    packed = dict(kind='unknown', evaluated=True, aggregate='unboxed-tuple',
                  primReps=lane['primReps'] * family['lanes'], components=[lane] * family['lanes'])
    if family['newCarrier']:
        GENERATED_SHAPES.append(shape)
    for operation in family['operations']:
        arguments = [packed] if operation == 'pack' else [lane] if operation == 'broadcast' else [vector, lane, dict(kind='long', evaluated=True, primReps=['IntRep'])] if operation == 'insert' else [vector] * (2 if operation in ('plus', 'minus', 'times', 'divide') else 1)
        OPERATIONS[operation + family['name'] + '#'] = (arguments, packed if operation == 'unpack' else vector)

def is_vector(rep): return isinstance(rep, dict) and rep.get('kind') == 'vector'
def signature_matches(expected, actual):
    """A primop signature requires concrete carriers, including every tuple lane."""
    if not isinstance(actual, dict): return False
    if any(expected.get(key) != actual.get(key) for key in ('kind', 'primReps', 'aggregate', 'vector')): return False
    children, other = expected.get('components'), actual.get('components')
    if children is None: return other is None
    return isinstance(other, list) and len(children) == len(other) and all(signature_matches(a, b) for a, b in zip(children, other))
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
    if shape not in (VECTOR_REP['vector'], VECTOR32_REP['vector'], VECTOR16_REP['vector'], VECTOR8_REP['vector'], VECTOR_WORD8_REP['vector'], VECTOR_WORD16_REP['vector'], VECTOR_WORD32_REP['vector'], VECTOR_FLOAT_REP['vector'], VECTOR_DOUBLE_REP['vector'], *GENERATED_SHAPES):
        return 'Unsupported Core vector representation'
    return None
