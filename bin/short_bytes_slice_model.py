# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Unbounded list/byte model for public ShortByteString slicing."""
ENTRIES = ('takeCase', 'dropCase', 'splitCase')
MIN = -(1 << 63)
MAX = (1 << 63)-1

def wrap(n):
    return (n+(1 << 63)) % (1 << 64)-(1 << 63)

def seeds():
    return sorted(set(range(256)) | {MIN, MIN+1, MAX-1, MAX, -1, -17, -255, -(1 << 32), 1 << 32})

def payload(seed):
    return [(seed+i*73) % 256 for i in range(abs(seed) % 17)]

def counts(seed):
    n = len(payload(seed))
    return sorted({MIN, -1, 0, 1, n//2, n-1, n, n+1, MAX})

def sliced(name, seed, count, side):
    data = payload(seed)
    cut = min(len(data), max(0, count))
    if name == 'takeCase' and side == 0: return data[:cut]
    if name == 'dropCase' and side == 0: return data[cut:]
    if name == 'splitCase' and side in (0, 1): return data[:cut] if side == 0 else data[cut:]
    raise ValueError((name, side))

def expected(name, seed, count, side, selector):
    data = sliced(name, seed, count, side)
    if selector == -1: return len(data)
    if selector == -2:
        result = 0
        for byte in data: result = wrap(33*result+byte)
        return result
    return data[selector] if 0 <= selector < len(data) else -1

def requests():
    for name in ENTRIES:
        for seed in seeds():
            for count in counts(seed):
                for side in ((0, 1) if name == 'splitCase' else (0,)):
                    n = len(sliced(name, seed, count, side))
                    for selector in (MIN, -2, -1, *range(n+1), MAX):
                        yield name, seed, count, side, selector

def verify(text):
    rows = [line.split('\t') for line in text.splitlines()]
    wanted = list(requests())
    if len(rows) != len(wanted): raise AssertionError('Wrong native slice row count')
    for row, key in zip(rows, wanted):
        if len(row) != 6 or (row[0], *map(int, row[1:5])) != key:
            raise AssertionError('Missing, duplicate, reordered or unexpected slice row')
        if int(row[5]) != expected(*key): raise AssertionError((row, expected(*key)))
    return len(rows)
