# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Independent list/deque oracle for the ordinary Data.Sequence workload.

No finger-tree representation or containers implementation is reproduced here.
The eleven entry names and arithmetic mirror examples/THC/SequenceWorkload.hs.
"""
from collections import deque


ENTRIES = (
    'sequenceBuild', 'sequenceEnds', 'sequenceAppend', 'sequenceSplit',
    'sequenceIndexUpdate', 'sequenceAggregate', 'sequenceLazyPayloads',
    'sequenceBuildViews', 'sequenceDequeViews', 'sequenceAppendViews', 'sequenceLazyLength',
)
CHECKSUM_MASK = (1 << 31) - 1


def _signed(value):
    return ((value + (1 << 63)) % (1 << 64)) - (1 << 63)


def _payload(index):
    return ((37 * index + 11) & 2047) - 1024


def _from_list(size):
    return [_payload(index) for index in range(size)]


def _from_ends(size):
    # Even indices were prepended, odd indices appended: derive the final order
    # directly rather than simulating the library's insertion algorithm.
    return ([_payload(index) for index in reversed(range(0, size, 2))] +
            [_payload(index) for index in range(1, size, 2)])


def _measure(items):
    forward = backward = 0
    for item in items:
        forward = (forward * 33 + (item & 65535) + 1) & CHECKSUM_MASK
    # foldr f z [a,b,c] is f a (f b (f c z)).
    for item in reversed(items):
        backward = (backward * 17 + (item & 65535) + 1) & CHECKSUM_MASK
    return _signed(forward + 3 * backward + 7 * len(items))


def _drain(items, take_left):
    remaining = deque(items)
    checksum = 0
    while remaining:
        item = remaining.popleft() if take_left else remaining.pop()
        checksum = (checksum * 19 + (item & 65535) + 1) & CHECKSUM_MASK
        take_left = not take_left
    return checksum


def _view_measure(items):
    return _signed(_drain(items, True) + 3 * _drain(items, False) + 7 * len(items))


def _split(items, position):
    cut = max(0, min(len(items), position))
    return items[:cut], items[cut:]


def _split_score(items):
    count = len(items)
    checksum = 0
    # Duplicated positions for short sequences are intentional observations.
    for position in (-2, 0, 1, count // 2, count - 1, count, count + 2):
        left, right = _split(items, position)
        checksum = _signed(checksum * 5 + _measure(left) + 11 * _measure(right))
    return checksum


def _update(items, position, value):
    result = list(items)
    if 0 <= position < len(result):
        result[position] = value
    return result


def _index_update_score(items):
    count = len(items)
    updated = _update(items, count // 2, _payload(count + 17))
    updated = _update(updated, count, 88888)
    updated = _update(updated, -1, 99999)
    queries = 0
    for position in (-1, 0, count // 2, count - 1, count, count + 1):
        value = updated[position] if 0 <= position < count else -31
        queries = _signed(queries * 7 + value)
    indexed = updated[count // 2] if updated else -47
    return _signed(_measure(updated) + 13 * queries + 17 * indexed)


def sequence_model(entry, value):
    """Return the signed 64-bit result for one SequenceWorkload entry/input."""
    if entry not in ENTRIES:
        raise ValueError('Unknown Sequence entry: ' + str(entry))
    size = max(0, min(1024, value))
    if entry == 'sequenceBuild':
        return _measure(_from_list(size))
    if entry == 'sequenceBuildViews':
        return _view_measure(_from_list(size))
    if entry == 'sequenceDequeViews':
        return _view_measure(_from_ends(size))
    if entry == 'sequenceEnds':
        items = _from_ends(size)
        return _signed(_measure(items) + 5 * _drain(items, True) + 11 * _drain(items, False))
    if entry in ('sequenceAppend', 'sequenceAppendViews'):
        left, right = _from_ends(size), _from_ends(size // 2)
        score = _measure if entry == 'sequenceAppend' else _view_measure
        return _signed(score(left + right) + 3 * score(right + left))
    if entry == 'sequenceSplit':
        return _split_score(_from_ends(size))
    if entry == 'sequenceIndexUpdate':
        return _index_update_score(_from_ends(size))
    if entry == 'sequenceLazyPayloads':
        # Neither length nor the constant spine fold demands either bottom.
        return 2 * (size + 2)
    if entry == 'sequenceLazyLength':
        # The two hidden endpoints guarantee a nonempty spine, even at size 0.
        return size + 2 + 1

    initial = _from_list(size)
    edged = [_payload(size + 1)] + initial + [_payload(size + 2)] if size else initial
    combined = edged + _from_list(size // 2)
    return _signed(_measure(initial) + 3 * _measure(combined) + 5 * _drain(combined, True) +
                   7 * _drain(combined, False) + 11 * _split_score(combined) +
                   13 * _index_update_score(combined))
