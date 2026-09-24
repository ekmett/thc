"""Independent byte/IEEE32 model: no SIMD, scalar bitcasts, or guest imports.

Raw movement uses only integer bytes. Finite graph seeds are exactly representable
integers; their four weighted products and left-associated sums are also exact.
Selected signaling NaNs are native diagnostics, never portable corpus entries.
"""
BYTE_ORDER = 'little'
WEIGHTS = (3, 5, 7, 11)
RAW_BITS = (0, 0x80000000, 1, 0x80000001, 0x007fffff, 0x807fffff,
            0x00800000, 0x80800000, 0x3f800000, 0xbf800000,
            0x7f7fffff, 0xff7fffff, 0x7f800000, 0xff800000,
            0x7fc12345, 0xffc54321)
SIGNALING_BITS = (0x7f800001, 0xff800001, 0x7fa12345, 0xffa54321)
GRAPH_EDGE = (-65536, -257, -1, 0, 1, 255, 256, 65535)
GRAPH_SENTINELS = (-31, 127, -1024, 4096)
HELPERS = {family+operation+'Case': family+({'GraphIndex': 'IndexGraph', 'GraphStore': 'StoreGraph'}.get(operation, operation+'Worker'))
           for family in ('vector', 'scalar') for operation in ('Index', 'Read', 'Write', 'GraphIndex', 'GraphStore')}


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def integer_float_bits(value):
    """Exact IEEE binary32 encoding for the bounded integer-only graph domain."""
    check(type(value) is int and abs(value) <= 65536, 'Graph integer outside exact domain')
    if value == 0:
        return 0
    magnitude = abs(value)
    exponent = magnitude.bit_length()-1
    return ((1 << 31) if value < 0 else 0) | ((exponent+127) << 23) | ((magnitude-(1 << exponent)) << (23-exponent))


def checksum(values):
    check(len(values) == 4, 'Four lanes required')
    return sum(x*w for x, w in zip(values, WEIGHTS))


def initial_bytes():
    return bytearray(b''.join((0x89abcdef+i*0x01030507).to_bytes(4, BYTE_ORDER) for i in range(16)))


def offset_scale(name):
    check(name.startswith(('vector', 'scalar')), 'Unknown offset family')
    return 16 if name.startswith('vector') else 4


def put_lanes(storage, address, bits):
    check(len(bits) == 4 and 0 <= address <= len(storage)-16, 'Out of bounds vector access')
    check(all(type(x) is int and 0 <= x < 1 << 32 for x in bits), 'Raw lane outside Word32')
    result = bytearray(storage)
    for i, value in enumerate(bits):
        result[address+4*i:address+4*i+4] = value.to_bytes(4, BYTE_ORDER)
    return result


def read_lanes(storage, address):
    check(0 <= address <= len(storage)-16, 'Out of bounds vector access')
    return [int.from_bytes(storage[address+i*4:address+i*4+4], BYTE_ORDER) for i in range(4)]


def patterns(family, graph=False, diagnostic=False):
    check(family in ('vector', 'scalar'), 'Unknown offset family')
    check(not (graph and diagnostic), 'Native diagnostics are not graph inputs')
    count = 4 if family == 'vector' else 13
    result = []
    if graph:
        for lane in range(4):
            for boundary in GRAPH_EDGE:
                values = list(GRAPH_SENTINELS); values[lane] = boundary
                result.append([len(result) % count, *values])
    else:
        domain = SIGNALING_BITS if diagnostic else RAW_BITS
        for i in range(len(domain)):
            result.append([i % count, *[domain[(i+5*lane) % len(domain)] for lane in range(4)]])
    check(len({tuple(x) for x in result}) == len(result), 'Duplicate pattern')
    if not diagnostic:
        check({x[0] for x in result} == set(range(count)), 'Missing safe offset')
    return result


def expected(name, *args):
    check(all(type(x) is int and -(1 << 63) <= x < 1 << 63 for x in args), 'Machine Int inputs required')
    check(name in HELPERS or name in ('vectorUnitCase', 'scalarUnitCase'), 'Unknown scalar entry')
    graph = 'Graph' in name
    check(len(args) == (5 if name.endswith('GraphIndexCase') else 6), 'Wrong scalar entry arity')
    offset, *values = args[:5]
    bits = [integer_float_bits(x) for x in values] if graph else values
    storage = put_lanes(initial_bytes(), offset*offset_scale(name), bits)
    if name.endswith('UnitCase'):
        selector = args[5]
        check(0 <= selector < 8, 'Wrong before/after lane selector')
        return bits[selector % 4] ^ (0x80000000 if selector == 5 else 0)
    if name.endswith(('IndexCase', 'ReadCase')) and not graph:
        check(0 <= args[5] < 4, 'Wrong raw lane selector')
        return read_lanes(storage, offset*offset_scale(name))[args[5]]
    if name.endswith('GraphIndexCase'):
        return checksum(values)
    check(0 <= args[5] < 64, 'Wrong byte selector')
    return checksum(values)*257+storage[args[5]]


def entries():
    result = []
    for family in ('vector', 'scalar'):
        for operation, selectors in (('Unit', 8), ('Index', 4), ('Read', 4), ('Write', 64), ('GraphIndex', 0), ('GraphStore', 64)):
            domain = patterns(family, graph=operation.startswith('Graph'))
            cases = [[*args, i] for args in domain for i in range(selectors)] if selectors else domain
            result.append(dict(name=family+operation+'Case', arity=6 if selectors else 5, cases=cases))
    return result


def graph_entries():
    result = []
    for family in ('vector', 'scalar'):
        for operation in ('Index', 'Store'):
            cases = []
            for args in patterns(family, graph=True):
                offset, *lanes = args
                before = initial_bytes()
                after = put_lanes(before, offset*offset_scale(family), [integer_float_bits(x) for x in lanes])
                cases.append(dict(offset=offset, lanes=lanes,
                                  initialBytes=list(after if operation == 'Index' else before),
                                  expectedBytes=list(after), expectedScalar=checksum(lanes),
                                  nativeEntry=family+'Graph'+operation+'Case', nativeArguments=args))
            result.append(dict(name=family+operation+'Graph', arity=2 if operation == 'Index' else 7,
                               operation=operation.lower(), offsetUnitBytes=offset_scale(family), cases=cases))
    return result


def model_rows():
    rows = {}
    for entry in entries():
        for args in entry['cases']:
            key = (entry['name'], *args)
            check(key not in rows, 'Duplicate corpus key')
            rows[key] = expected(*key)
    check(len(rows) == 6720, 'Wrong portable corpus cardinality')
    return rows


def diagnostic_rows():
    """Selected native-only sNaNs: no portable scalar copying/boxing promise."""
    return {(family+operation+'Case', *args, selector): expected(family+operation+'Case', *args, selector)
            for family in ('vector', 'scalar') for operation, count in (('Unit', 8), ('Index', 4), ('Read', 4), ('Write', 64))
            for args in patterns(family, diagnostic=True) for selector in range(count)}


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
