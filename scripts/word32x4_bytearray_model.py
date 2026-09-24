"""Integer/byte-only model of the six Word32X4 ByteArray operations.

The retained native corpus is little-endian x86_64; byte order is explicit.
No SIMD implementation, JVM runtime or GHC exporter is imported here.
"""
BYTE_ORDER = 'little'
WEIGHTS = (3, 5, 7, 11)
EDGE = (0, 1, 65535, 65536, 2147483647, 2147483648, 2147483649, 4294967294, 4294967295)
SENTINELS = (0x81234567, 0x92345678, 0xa3456789, 0xb456789a)
SEEDS = (-(1 << 63), -4294967297, -2147483649, -2147483648, -2147483647,
         -1, 0, 1, 127, 128, 255, 256, 2147483646, 2147483647,
         2147483648, 4294967295, 4294967296, (1 << 63)-1)
HELPERS = {family+operation+'Case': family+operation+('Graph' if operation == 'Store' else 'Worker')
           for family in ('vector', 'scalar') for operation in ('Index', 'Read', 'Write', 'Store')}


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def narrow(value):
    return value % (1 << 32)


def checksum(lanes):
    return sum(narrow(value)*weight for value, weight in zip(lanes, WEIGHTS))


def initial_bytes():
    return bytearray(b''.join((0x89abcdef+i*0x01030507).to_bytes(4, BYTE_ORDER) for i in range(16)))


def offset_scale(name):
    check(name.startswith(('vector', 'scalar')), 'Unknown offset family')
    return 16 if name.startswith('vector') else 4


def put_lanes(storage, address, lanes):
    check(len(lanes) == 4 and 0 <= address <= len(storage)-16, 'Out of bounds vector access')
    result = bytearray(storage)
    for lane, value in enumerate(lanes):
        result[address+lane*4:address+lane*4+4] = narrow(value).to_bytes(4, BYTE_ORDER, signed=False)
    return result


def read_lanes(storage, address):
    check(0 <= address <= len(storage)-16, 'Out of bounds vector access')
    return [int.from_bytes(storage[address+lane*4:address+lane*4+4], BYTE_ORDER, signed=False)
            for lane in range(4)]


def alias_expected(name, offset, seed):
    lanes = [seed, seed+17, seed*3-29, seed ^ 0x55aa55aa]
    address = offset*offset_scale(name)
    before = put_lanes(bytearray(64), address, lanes)
    after = bytearray(before)
    if name == 'vectorUnitCase':
        after[address+4:address+8] = narrow(seed ^ 0x80000000).to_bytes(4, BYTE_ORDER, signed=False)
    else:
        after[address+7] = (seed+101) % 256
    return checksum(read_lanes(before, address))+15*checksum(read_lanes(after, address))


def expected(name, *args):
    check(all(type(x) is int and -(1 << 63) <= x < 1 << 63 for x in args), 'Requires machine Int inputs')
    if name in ('vectorUnitCase', 'scalarUnitCase'):
        check(len(args) == 2, 'Wrong alias arity')
        return alias_expected(name, *args)
    check(name in HELPERS, 'Unknown scalar entry')
    stores = name.endswith(('WriteCase', 'StoreCase'))
    check(len(args) == (6 if stores else 5), 'Wrong scalar entry arity')
    offset, *lanes = args[:5]
    storage = put_lanes(initial_bytes(), offset*offset_scale(name), lanes)
    score = checksum(read_lanes(storage, offset*offset_scale(name)))
    if stores:
        byte = args[5]
        check(0 <= byte < 64, 'Wrong byte selector')
        return score*257+storage[byte]
    return score


def patterns(family):
    check(family in ('vector', 'scalar'), 'Unknown family')
    count = 4 if family == 'vector' else 13
    result = []
    for lane in range(4):
        for boundary in EDGE:
            values = list(SENTINELS)
            values[lane] = boundary
            result.append([len(result) % count, *values])
    check(len({tuple(row) for row in result}) == 36, 'Duplicate lane/boundary rows')
    check({row[0] for row in result} == set(range(count)), 'Missing safe offset')
    for lane in range(4):
        check({row[lane+1] for row in result if row[lane+1] in EDGE} == set(EDGE), 'Missing lane boundary')
    return result


def entries():
    result = [dict(name=family+'UnitCase', arity=2,
                   cases=[[offset, seed] for offset in range(4 if family == 'vector' else 13) for seed in SEEDS])
              for family in ('vector', 'scalar')]
    for family in ('vector', 'scalar'):
        for operation in ('Index', 'Read', 'Write', 'Store'):
            stores = operation in ('Write', 'Store')
            cases = [[*args, byte] for args in patterns(family) for byte in range(64)] if stores else patterns(family)
            result.append(dict(name=family+operation+'Case', arity=6 if stores else 5, cases=cases))
    return result


def graph_entries():
    result = []
    for family in ('vector', 'scalar'):
        for operation in ('Index', 'Store'):
            cases = []
            for args in patterns(family):
                offset, *lanes = args
                before = initial_bytes()
                after = put_lanes(before, offset*offset_scale(family), lanes)
                cases.append(dict(offset=offset, lanes=lanes,
                                  initialBytes=list(after if operation == 'Index' else before),
                                  expectedBytes=list(after), expectedScalar=checksum(lanes),
                                  nativeEntry=family+operation+'Case', nativeArguments=args))
            result.append(dict(name=family+operation+('Worker' if operation == 'Index' else 'Graph'),
                               arity=2 if operation == 'Index' else 7,
                               operation=operation.lower(), offsetUnitBytes=offset_scale(family), cases=cases))
    return result


def model_rows():
    rows = {}
    for entry in entries():
        for args in entry['cases']:
            key = (entry['name'], *args)
            check(key not in rows, 'Duplicate model row')
            rows[key] = expected(*key)
    check(len(rows) == 9666, 'Unexpected bounded corpus cardinality')
    return rows


def parse_rows(text):
    arities = {entry['name']: entry['arity'] for entry in entries()}
    rows = {}
    for line in text.splitlines():
        name, *fields = line.split('\t')
        check(name in arities and len(fields) == arities[name]+1, 'Wrong native row shape')
        key = (name, *map(int, fields[:-1]))
        check(key not in rows, 'Duplicate native row')
        rows[key] = int(fields[-1])
    return rows
