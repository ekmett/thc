"""Independent IEEE binary64 model using integer significands, never host FP arithmetic."""
SIGN = 1 << 63
INF = 0x7ff0000000000000
NAN = 0x7ff8000000000000
FRAC = (1 << 52) - 1
FINITE = [-18014398509481987, -9007199254740995, -9007199254740993, -9007199254740992,
          -9007199254740991, -3, -1, 0, 1, 3, 9007199254740991, 9007199254740992,
          9007199254740993, 9007199254740995, 18014398509481987]
EDGE_BITS = [0, SIGN, 1, SIGN | 1, FRAC, SIGN | FRAC, 1 << 52, SIGN | (1 << 52),
             INF - 1, SIGN | (INF - 1), INF, SIGN | INF, NAN, 0x3fe0000000000000,
             0xbfe0000000000000, 0x3ff0000000000000, 0xbff0000000000000,
             0x4340000000000000, 0x4340000000000001, 3, SIGN | 3]


def is_nan(bits):
    return bits & ~SIGN > INF


def rounded(sign, n, exponent):
    """Round exact nonnegative n * 2**exponent to binary64, nearest/ties-even."""
    assert n >= 0 and sign in (0, SIGN)
    if n == 0:
        return sign
    high = n.bit_length() - 1 + exponent
    quantum = max(high - 52, -1074)
    if exponent >= quantum:
        significand = n << (exponent - quantum)
    else:
        shift = quantum - exponent
        significand, remainder = divmod(n, 1 << shift)
        halfway = 1 << (shift - 1)
        if remainder > halfway or remainder == halfway and significand & 1:
            significand += 1
    if significand == 0:
        return sign
    if significand >= 1 << 53:
        assert significand == 1 << 53
        significand >>= 1
        quantum += 1
    if quantum == -1074 and significand < 1 << 52:
        return sign | significand
    high = quantum + 52
    if high > 1023:
        return sign | INF
    assert -1022 <= high <= 1023 and 1 << 52 <= significand < 1 << 53
    return sign | ((high + 1023) << 52) | (significand & FRAC)


def decode(bits):
    """Finite value as sign, nonnegative integer significand, binary exponent."""
    assert bits & ~SIGN < INF
    exponent = (bits >> 52) & 0x7ff
    return bits & SIGN, (bits & FRAC) | ((1 << 52) if exponent else 0), exponent - 1075 if exponent else -1074


def integer(n):
    return rounded(SIGN if n < 0 else 0, abs(n), 0)


def add(a, b):
    if is_nan(a) or is_nan(b):
        return NAN
    if a & ~SIGN == INF or b & ~SIGN == INF:
        return NAN if a & ~SIGN == INF and b & ~SIGN == INF and (a ^ b) & SIGN else a if a & ~SIGN == INF else b
    sa, na, ea = decode(a)
    sb, nb, eb = decode(b)
    exponent = min(ea, eb)
    total = (-na if sa else na) * (1 << (ea - exponent)) + (-nb if sb else nb) * (1 << (eb - exponent))
    if total == 0:
        return SIGN if na == nb == 0 and sa and sb else 0
    return rounded(SIGN if total < 0 else 0, abs(total), exponent)


def subtract(a, b):
    return add(a, b ^ SIGN)


def multiply(a, b):
    if is_nan(a) or is_nan(b):
        return NAN
    sign = (a ^ b) & SIGN
    if a & ~SIGN == INF or b & ~SIGN == INF:
        return NAN if a & ~SIGN == 0 or b & ~SIGN == 0 else sign | INF
    _, na, ea = decode(a)
    _, nb, eb = decode(b)
    return rounded(sign, na * nb, ea + eb)


def truncate(bits):
    sign, n, exponent = decode(bits)
    value = n << exponent if exponent >= 0 else n >> -exponent
    value = -value if sign else value
    assert -(1 << 63) <= value < 1 << 63, 'Unsafe double2Int corpus'
    return value


def answer(bits):
    return 'nan' if is_nan(bits) else str(bits)


def entries():
    finite = [[a, b] for a in FINITE for b in FINITE]
    edges = [[a, (a + 7) % 21, c] for a in range(21) for c in range(21)]
    moves = [[a, (a + 7) % 21] for a in range(21)]
    return ([dict(name=n, arity=2, result='long', cases=finite) for n in ('plusCase', 'minusCase', 'timesCase')]
        + [dict(name='edge'+op+str(lane), arity=3, result='double-bits', cases=edges)
           for op in ('Plus', 'Minus', 'Times') for lane in (0, 1)]
        + [dict(name='move'+str(lane), arity=2, result='double-bits', cases=moves) for lane in (0, 1)]
        + [dict(name='broadcastCase', arity=1, result='double-bits', cases=[[n] for n in range(21)]),
           dict(name='nonFmaCase', arity=1, result='double-bits', cases=[[n] for n in (-2, -1, 0, 1, 2, 3)])])


def expected(name, *inputs):
    if name in ('plusCase', 'minusCase', 'timesCase'):
        lanes = list(map(integer, inputs))
        if name == 'plusCase':
            result = [add(x, 0x3fe0000000000000) for x in lanes]
        elif name == 'minusCase':
            result = [subtract(x, 0x3fd0000000000000) for x in lanes]
        else:
            result = [multiply(x, y) for x, y in zip(lanes, (0x3fe0000000000000, 0xc000000000000000))]
        signature = 0
        for bits, weight in zip(result, (7, 11)):
            signature ^= truncate(multiply(bits, integer(4))) * weight
        return str((signature + (1 << 63)) % (1 << 64) - (1 << 63))
    if name.startswith('edge'):
        operation = {'Plus': add, 'Minus': subtract, 'Times': multiply}[name[4:-1]]
        return answer(operation(EDGE_BITS[inputs[int(name[-1])]], EDGE_BITS[inputs[2]]))
    if name.startswith('move'):
        return answer(EDGE_BITS[inputs[int(name[-1])]])
    if name == 'broadcastCase':
        return answer(EDGE_BITS[inputs[0]])
    if name == 'nonFmaCase':
        x = add(integer(inputs[0] & 1), 0x3ff0000000000001)
        return answer(subtract(multiply(x, 0x3feffffffffffffe), integer(1)))
    raise ValueError(name)


def model_rows():
    return {(entry['name'], *inputs): expected(entry['name'], *inputs)
            for entry in entries() for inputs in entry['cases']}


def parse_rows(text):
    declared = {entry['name']: entry['arity'] for entry in entries()}
    rows = {}
    for line in text.splitlines():
        name, *fields = line.split('\t')
        assert name in declared and len(fields) == declared[name] + 1, 'Wrong native row shape: ' + line
        key = (name, *map(int, fields[:-1]))
        assert key not in rows, 'Duplicate native row: ' + line
        rows[key] = fields[-1]
    return rows
