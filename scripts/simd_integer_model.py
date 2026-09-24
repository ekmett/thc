"""Mathematical integer SIMD lanes, independent of Java carriers and vectors."""


def integer_operation(operation, left, right, width, unsigned):
    """Return one modular lane as the scalar-entry fixture's signed guest Long.

    Word8/16/32 unpack through wordNToWord# and are zero-extended. Word64
    preserves its full bit pattern through word2Int#, so its high half is
    observed as negative Long values. Int lanes unpack with sign extension.
    Pack, unpack and broadcast preserve the supplied left lane; this function
    does not apply the fixture's lane offsets or post-call addition.
    """
    if type(width) is not int or width not in (8, 16, 32, 64):
        raise ValueError('Integer lanes require width 8, 16, 32 or 64')
    if type(unsigned) is not bool:
        raise ValueError('Integer lane signedness must be explicit')
    if operation in ('pack', 'unpack', 'broadcast'):
        value = left
    elif operation == 'plus':
        value = left + right
    elif operation == 'minus':
        value = left - right
    elif operation == 'times':
        value = left * right
    elif operation == 'negate' and not unsigned:
        value = -left
    else:
        raise ValueError(f'Unsupported integer lane operation: {operation}')

    modulus = 1 << width
    value %= modulus
    if (not unsigned or width == 64) and value >= modulus // 2:
        value -= modulus
    return value
