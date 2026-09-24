# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Independent integer-only semantics and input domains for genuine Word32X4 Core."""
MASK = 4294967295
WEIGHTS = (3, 5, 7, 11)
OPERATIONS = ('plusCase', 'minusCase', 'timesCase', 'packCase', 'broadcastCase')
HELPERS = {'scalarHelperCase': 'scalarWorker', 'tupleHelperCase': 'tupleWorker'}
EDGE = (0, 1, 2, 1073741823, 2147483647, 2147483648, 2147483649, 4294967294, 4294967295)
# Each left/right lane has independent scalar seeds and odd affine coefficients,
# invertible modulo 4294967296 for complete independent per-lane operand grids.
LEFT = ((0, 1, 0), (1, 1, 0), (0, 1, 1), (1, 1, -1))
RIGHT = ((1, 1, 2), (0, 1, -3), (1, 7, 13), (0, 11, -17))


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def narrow(value):
    return value % 4294967296


def operands(a, b):
    args = (a, b)
    return tuple([narrow(args[source]*scale+bias) for source, scale, bias in description]
                 for description in (LEFT, RIGHT))


def result_lanes(name, a, b):
    x, y = operands(a, b)
    if name == 'plusCase': return [narrow(p+q) for p, q in zip(x, y)]
    if name == 'minusCase': return [narrow(p-q) for p, q in zip(x, y)]
    if name == 'timesCase': return [narrow(p*q) for p, q in zip(x, y)]
    if name == 'packCase': return x
    if name == 'broadcastCase': return [narrow(a-b+29)]*4
    raise ValueError(name)


def expected(name, *args):
    check(all(type(x) is int and -(1 << 63) <= x < (1 << 63) for x in args), 'Requires machine Int inputs')
    if name == 'laneCase':
        operation, lane, a, b = args
        check(0 <= operation < 5 and 0 <= lane < 4, 'Outside the declared selector domain')
        return result_lanes(OPERATIONS[operation], a, b)[lane]
    a, b = args
    operation = {'scalarHelperCase': 'plusCase', 'tupleHelperCase': 'timesCase'}.get(name, name)
    score = sum(weight*value for weight, value in zip(WEIGHTS, result_lanes(operation, a, b)))
    return score+48 if name == 'scalarHelperCase' else score


def scalar_cases():
    seeds = {-(1 << 63), (1 << 63)-1, -4294967297, -4294967296, 4294967295, 4294967296, 4294967297,
             0x123456789abcdef, -0x123456789abcdef, *EDGE}
    seeds |= {sign*((1 << bit)+delta) for bit in range(32) for delta in (-1, 0, 1) for sign in (-1, 1)}
    values = sorted(seeds)
    pairs = {(a, values[(17*i+5) % len(values)]) for i, a in enumerate(values)}
    pairs |= {(b, a) for a, b in tuple(pairs)}
    pairs |= {(a, b) for a in EDGE for b in EDGE}
    return [list(pair) for pair in sorted(pairs)]


def lane_cases():
    cases = []
    for operation in range(5):
        for lane in range(4):
            for x in EDGE:
                for y in EDGE:
                    args = [0, 0]
                    if operation == 4:
                        args = [x+y-29, y]  # Broadcast exactly x at every lane.
                    else:
                        for target, description in ((x, LEFT[lane]), (y, RIGHT[lane])):
                            source, scale, bias = description
                            args[source] = ((target-bias)*pow(scale, -1, 4294967296)) & MASK
                    cases.append([operation, lane, *args])
    return cases


def entries():
    common = scalar_cases()
    return [dict(name=name, arity=2, cases=common) for name in OPERATIONS] + [
        dict(name='laneCase', arity=4, cases=lane_cases())] + [
        dict(name=name, arity=2, cases=common) for name in HELPERS]


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
