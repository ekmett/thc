# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Independent unsigned decimal and bracket/comma list model; no Haskell formatter."""
import random

ENTRIES = ('wordChecksum', 'wordCharacter', 'listChecksum', 'listCharacter')
SHAPES = (0, 1, 3)
MASK = (1 << 64) - 1

def wrap(n):
    return (n + (1 << 63)) % (1 << 64) - (1 << 63)

def inputs():
    values = set(range(-12, 13)) | {-(1 << 63), (1 << 63)-1}
    values.update(wrap(sign*((1 << bit)+delta))
                  for sign in (-1, 1) for bit in range(64) for delta in (-1, 0, 1))
    values.update(wrap(sign*(10**power+delta))
                  for sign in (-1, 1) for power in range(20) for delta in (-1, 0, 1))
    values.update(wrap(sign*n) for sign in (-1, 1)
                  for n in (102, 120, 210, 1001, 1010, 1111, 90909, 123456789, 987654321))
    rng = random.Random(0x53484f57)
    values.update(wrap(rng.getrandbits(64)) for _ in range(24))
    return sorted(values)

def formatted(name, x, shape):
    if name.startswith('word'):
        return str(x & MASK)
    if not name.startswith('list') or shape not in SHAPES:
        raise ValueError((name, shape))
    values = [] if shape == 0 else [x] if shape == 1 else [x, wrap(x+1), wrap(-x)]
    return '[' + ','.join(str(n) for n in values) + ']'

def requests():
    result = []
    for name in ENTRIES:
        for x in inputs():
            for shape in ((0,) if name.startswith('word') else SHAPES):
                indices = [-1, *range(len(formatted(name, x, shape))+1)] if name.endswith('Character') else [0]
                result.extend((name, x, shape, i) for i in indices)
    return result

def expected(name, x, shape, index):
    if name not in ENTRIES:
        raise ValueError(name)
    text = formatted(name, x, shape)
    if name.endswith('Character'):
        return ord(text[index]) if 0 <= index < len(text) else -1
    result = 5381
    for c in text:
        result = wrap(33*result + ord(c))
    return result

def verify(text):
    rows = [line.split('\t') for line in text.splitlines()]
    actual = [(name, int(x), int(s), int(i)) for name, x, s, i, _ in rows]
    if actual != requests():
        raise AssertionError('Missing, duplicate, reordered or unexpected Show Word/list oracle row')
    for name, x, shape, index, value in rows:
        if int(value) != expected(name, int(x), int(shape), int(index)):
            raise AssertionError((name, x, shape, index, value))
    return rows
