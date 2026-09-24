"""Independent decimal/string observations for the public GHC Show Int fixture."""
ENTRIES = ('showChecksum', 'showCharacter')
def wrap(n):
    return (n + (1 << 63)) % (1 << 64) - (1 << 63)
def inputs():
    values = set(range(-20, 21)) | {-(1 << 63), (1 << 63)-1}
    for power in range(19):
        for sign in (-1, 1):
            for delta in (-1, 0, 1):
                values.add(sign * (10**power + delta))
    # Deliberately ordered/repeated/internal-zero digits, plus binary boundaries.
    values.update(sign*n for sign in (-1, 1) for n in (120, 102, 210, 1001, 1010, 1111, 90909, 123456789, 987654321))
    values.update(wrap(sign*((1 << bit)+delta)) for sign in (-1, 1) for bit in range(64) for delta in (-1, 0, 1))
    return sorted(values)
def requests():
    values = inputs()
    return [('showChecksum', x, 0) for x in values] + [
        ('showCharacter', x, i) for x in values for i in [-1, *range(len(str(x))+1)]]
def expected(name, x, index):
    text = str(x)
    if name == 'showCharacter':
        return ord(text[index]) if 0 <= index < len(text) else -1
    if name != 'showChecksum':
        raise ValueError(name)
    result = 5381
    for c in text:
        result = wrap(33*result + ord(c))
    return result
def verify(text):
    rows = [line.split('\t') for line in text.splitlines()]
    actual = [(name, int(x), int(i)) for name, x, i, _ in rows]
    if actual != requests():
        raise AssertionError('Missing, duplicate, reordered or unexpected Show oracle row')
    for name, x, i, value in rows:
        if int(value) != expected(name, int(x), int(i)):
            raise AssertionError((name, x, i, value))
    return rows
