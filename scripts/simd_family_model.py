"""Fixture inputs and observations, independent of JVM vector implementations."""
import json
from pathlib import Path
from simd_integer_model import integer_operation
from simd_float_model import float_operation

FAMILIES = json.loads(Path(__file__).with_name('simd-families.json').read_text())['families']


def signed(value, width=64):
    return (value + (1 << (width - 1))) % (1 << width) - (1 << (width - 1))


def entries():
    return [(op + f['name'], f, op) for f in FAMILIES for op in f['operations'] if op not in ('pack', 'unpack')]


def result(family, operation, lane, a, b):
    width = family['bits'] // family['lanes']
    left = signed(a + lane * 104729)
    right = signed(b - lane * 7919)
    if family['laneRep'] in ('FloatRep', 'DoubleRep'):
        value = float_operation(operation, a if operation == 'broadcast' else left, right, width)
    else:
        value = integer_operation(operation, a if operation == 'broadcast' else left,
                                  right, width, family['laneRep'].startswith('Word'))
    # Public wrappers retain an OPAQUE scalar worker and a post-call addition.
    return signed(value + 17)


INTEGER_EDGES = [-(1 << 63), -(1 << 63) + 1, -(1 << 32), -(1 << 31), -(1 << 31) + 1,
                 -65537, -1, 0, 1, 2, 65537, (1 << 31) - 1, 1 << 31, (1 << 32) - 1,
                 (1 << 63) - 1]


def cases(family):
    width = family['bits'] // family['lanes']
    if family['laneRep'] in ('FloatRep', 'DoubleRep'):
        fraction_bits, exponent_bits = (23, 8) if width == 32 else (52, 11)
        inf = ((1 << exponent_bits) - 1) << fraction_bits
        one = ((1 << (exponent_bits - 1)) - 1) << fraction_bits
        magnitude = [0, 1, 3, (1 << fraction_bits) - 1, 1 << fraction_bits,
                     one - 1, one, one + 1, one + (1 << fraction_bits), inf - 1, inf,
                     inf + 1, inf + (1 << (fraction_bits - 1)), inf + (1 << fraction_bits) - 1]
        edges = [signed(x | sign << (width - 1)) for x in magnitude for sign in (0, 1)]
        pairs = [(a, b) for a in edges for b in edges]
    else:
        # Include exact lane boundaries as well as sign/truncation controls for
        # the 64-bit scalar carrier, including both sides of unsigned wraparound.
        edges = sorted(set(INTEGER_EDGES + [-(1 << (width - 1)), -(1 << (width - 1)) + 1,
                           (1 << (width - 1)) - 1, 1 << (width - 1), (1 << width) - 1]))
        edges = list(dict.fromkeys(signed(value) for value in edges))
        pairs = [(a, b) for a in edges for b in edges]
    # Shift each selected lane back to the edge pattern. Other lanes carry
    # distinct dynamic values, so every position and sign-extension is observed.
    return [(lane, signed(a - lane * 104729), signed(b + lane * 7919))
            for lane in range(family['lanes']) for a, b in pairs]


def rows():
    for name, family, operation in entries():
        for lane, a, b in cases(family):
            yield name, lane, a, b, result(family, operation, lane, a, b)
