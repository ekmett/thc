# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Defined native LP64 write domains; no invalid pointers or out-of-bounds IO."""
import errno

ENTRIES = ('originalWrite', 'originalSafeWrite', 'originalWriteErrno', 'originalSafeWriteErrno')
PAYLOAD = bytes(range(256))
SLICES = ((0, 0), (256, 0), (0, 1), (1, 8), (10, 1), (127, 3), (128, 128), (255, 1), (0, 256))


def check(condition, message):
    if not condition:
        raise ValueError(message)


def expected(entry, arguments):
    check(entry in ENTRIES, 'Unknown original stdio entry')
    check(len(arguments) == 3 and all(type(x) is int for x in arguments), 'Expected exact fd/offset/count integers')
    fd, offset, count = arguments
    check(fd in (-2147483648, -1, 1, 2), 'Native fd outside the defined domain')
    check(0 <= offset <= len(PAYLOAD) and 0 <= count <= len(PAYLOAD)-offset, 'Native buffer bounds')
    failed = fd < 0
    result = errno.EBADF if failed else -(count+2) if entry.endswith('Errno') else count
    if failed and not entry.endswith('Errno'):
        result = -1
    output = PAYLOAD[offset:offset+count] if not failed else b''
    return dict(result=result, stdoutHex=(output if fd == 1 else b'').hex(),
                stderrHex=(output if fd == 2 else b'').hex())


def cases():
    for entry in ENTRIES:
        for fd in (-2147483648, -1, 1, 2):
            for offset, count in SLICES:
                arguments = (fd, offset, count)
                yield dict(entry=entry, arguments=list(arguments), **expected(entry, arguments))


def validate_rows(rows):
    check(type(rows) is list, 'Native rows must be a list')
    wanted = list(cases())
    check(len(rows) == len(wanted), 'Missing or duplicate native rows')
    for actual, required in zip(rows, wanted):
        check(actual == required, 'Native row differs from model: '+repr(required))
    return len(rows)
