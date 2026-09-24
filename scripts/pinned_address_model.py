"""Independent byte-state model; no Haskell, native, or runtime invocation."""
ENTRIES = {'pinnedBytes': 3, 'alignedBytes': 4, 'keepAliveWord8': 1,
           'keepAliveLazy': 1, 'fingerprintByte': 3}
PUBLIC_FRONTIERS = {'publicFingerprintByte': 3, 'publicFingerprintRoundtrip': 3}
VALUES = (-(1 << 63), -257, -256, -1, 0, 1, 127, 128, 255, 256, 257,
          0x0123456789abcdef, (1 << 63)-1)
SIZES = (0, 1, 2, 3, 8, 16, 17, 31, 64)
WORDS = (-(1 << 63), -1, 0, 1, 0x0123456789abcdef, 0x7f0080ff0102fe03, (1 << 63)-1)

def check(condition, message):
    if not condition:
        raise AssertionError(message)

def expected(name, arguments):
    check(name in ENTRIES or name in PUBLIC_FRONTIERS, 'unknown entry')
    check(len(arguments) == (ENTRIES | PUBLIC_FRONTIERS)[name], 'wrong arity')
    check(all(type(x) is int and -(1 << 63) <= x < (1 << 63) for x in arguments), 'machine Int domain')
    if name in ('pinnedBytes', 'alignedBytes'):
        if name == 'alignedBytes':
            size, alignment, offset, raw = arguments
            check(alignment in (8, 16), 'native alignment domain')
        else:
            size, offset, raw = arguments
        check(0 <= size <= 64 and (offset == 0 if size == 0 else 0 <= offset < size), 'native bounds domain')
        if size == 0:
            return 0
        storage = bytearray(size)
        storage[0], storage[-1] = 11, 13
        storage[offset] = raw & 255
        before = storage[offset]
        storage[offset] ^= 128
        return size*19 + before*257 + storage[offset]*65537 + storage[0]*17 + storage[-1]*23
    if name in ('keepAliveWord8', 'keepAliveLazy'):
        before = arguments[0] & 255
        delta = 7 if name == 'keepAliveWord8' else 11
        return before*257 + ((before+delta) & 255)
    high, low, selector = arguments
    if name == 'publicFingerprintRoundtrip':
        check(selector in (0, 1), 'word selector domain')
        return (high, low)[selector]
    check(0 <= selector < 16, 'byte selector domain')
    encoded = (high % (1 << 64)).to_bytes(8, 'big') + (low % (1 << 64)).to_bytes(8, 'big')
    return encoded[selector]

def cases(include_public=True):
    for size in SIZES:
        for offset in range(size) if size else (0,):
            for value in VALUES:
                yield 'pinnedBytes', (size, offset, value)
                for alignment in (8, 16):
                    yield 'alignedBytes', (size, alignment, offset, value)
    for name in ('keepAliveWord8', 'keepAliveLazy'):
        for value in VALUES:
            yield name, (value,)
    for high in WORDS:
        for low in WORDS:
            for selector in range(16):
                yield 'fingerprintByte', (high, low, selector)
                if include_public:
                    yield 'publicFingerprintByte', (high, low, selector)
            if include_public:
                for selector in (0, 1):
                    yield 'publicFingerprintRoundtrip', (high, low, selector)

def parse_rows(text, domain):
    keys = set(domain)
    check(len(keys) == len(domain), 'duplicate requested case')
    rows = {}
    for line in text.splitlines():
        fields = line.split('\t')
        check(len(fields) >= 3, 'malformed native row')
        key = fields[0], tuple(map(int, fields[1:-1]))
        check(key in keys and key not in rows, 'unknown or duplicate native row')
        rows[key] = int(fields[-1])
        check(rows[key] == expected(*key), 'native/model mismatch: '+line)
    check(rows.keys() == keys, 'missing native row')
    return rows
