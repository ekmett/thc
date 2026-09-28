# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Independent signed modulo-2^32 semantics for genuine Int32X4 multiplication."""
MASK = (1 << 32)-1
WEIGHTS = (3, 5, 7, 11)
HELPERS = {'scalarHelperCase': 'scalarWorker', 'tupleHelperCase': 'tupleWorker'}
EDGE = (-2147483648, -2147483647, -65537, -1, 0, 1, 65537, 2147483646, 2147483647)
# Odd affine coefficients are invertible modulo 2^32. Each lane has independent
# left/right scalar seeds, so each operand pair can be observed individually.
LEFT = ((0, 1, 0), (1, 1, 0), (0, 1, 1), (1, 1, -1))
RIGHT = ((1, 1, 2), (0, 1, -3), (1, 7, 13), (0, 11, -17))


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def narrow(value):
    bits = value % (1 << 32)
    return bits if bits < (1 << 31) else bits-(1 << 32)


def operands(a, b):
    args = (a, b)
    return tuple([narrow(args[source]*scale+bias) for source, scale, bias in description]
                 for description in (LEFT, RIGHT))


def result_lanes(a, b):
    x, y = operands(a, b)
    return [narrow(p*q) for p, q in zip(x, y)]


def expected(name, *args):
    check(all(type(x) is int and -(1 << 63) <= x < (1 << 63) for x in args), 'Requires machine Int inputs')
    if name == 'laneCase':
        lane, a, b = args
        check(0 <= lane < 4, 'Outside the declared selector domain')
        return result_lanes(a, b)[lane]
    check(name in ('timesCase', *HELPERS), 'Unknown multiplication entry')
    a, b = args
    score = sum(weight*value for weight, value in zip(WEIGHTS, result_lanes(a, b)))
    return score+48 if name == 'scalarHelperCase' else score


def scalar_cases():
    seeds = {-(1 << 63), (1 << 63)-1, -(1 << 32)-1, -(1 << 32), (1 << 32)-1, 1 << 32, (1 << 32)+1,
             0x123456789abcdef, -0x123456789abcdef, *EDGE}
    seeds |= {sign*((1 << bit)+delta) for bit in range(32) for delta in (-1, 0, 1) for sign in (-1, 1)}
    values = sorted(seeds)
    pairs = {(a, values[(17*i+5) % len(values)]) for i, a in enumerate(values)}
    pairs |= {(b, a) for a, b in tuple(pairs)}
    pairs |= {(a, b) for a in EDGE for b in EDGE}
    return [list(pair) for pair in sorted(pairs)]


def lane_cases():
    cases = []
    for lane in range(4):
        for x in EDGE:
            for y in EDGE:
                args = [0, 0]
                for target, description in ((x, LEFT[lane]), (y, RIGHT[lane])):
                    source, scale, bias = description
                    args[source] = ((target-bias)*pow(scale, -1, 1 << 32)) & MASK
                cases.append([lane, *args])
    return cases


def entries():
    common = scalar_cases()
    return [dict(name='timesCase', arity=2, cases=common),
            dict(name='laneCase', arity=3, cases=lane_cases()),
            *[dict(name=name, arity=2, cases=common) for name in HELPERS]]


def model_rows():
    return {(entry['name'], *args): expected(entry['name'], *args)
            for entry in entries() for args in entry['cases']}


def parse_rows(text):
    arities = {entry['name']: entry['arity'] for entry in entries()}
    rows = {}
    for line in text.splitlines():
        name, *fields = line.split('\t')
        check(name in arities and len(fields) == arities[name]+1, 'Wrong native row shape: '+line)
        key = (name, *map(int, fields[:-1]))
        check(key not in rows, 'Duplicate native row: '+line)
        rows[key] = int(fields[-1])
    return rows
