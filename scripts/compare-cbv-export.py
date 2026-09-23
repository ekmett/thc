#!/usr/bin/env python3
"""Require a regenerated corpus to differ only by new CBV side metadata.

Use two module-list text files in the same order. Existing representation,
WHNF, speculation, source-note and executable fields remain part of comparison.
No identifier renaming, expression erasure, or textual Core normalization is
allowed, so changed compiler output cannot silently pass as equivalent.
"""
import argparse
import hashlib
import json
from pathlib import Path

CBV_KEYS = {'entryStrict', 'entryStrictSource', 'cbvEligible', 'cbvMarks'}


def strip_cbv(value):
    if isinstance(value, dict):
        return {k: strip_cbv(v) for k, v in value.items() if k not in CBV_KEYS}
    if isinstance(value, list):
        return [strip_cbv(v) for v in value]
    return value


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def first_difference(left, right, path='$'):
    if type(left) is not type(right):
        return f'{path}: type differs'
    if isinstance(left, dict):
        if left.keys() != right.keys():
            return f'{path}: keys differ ({sorted(left.keys() ^ right.keys())})'
        for key in left:
            mismatch = first_difference(left[key], right[key], f'{path}.{key}')
            if mismatch:
                return mismatch
    elif isinstance(left, list):
        if len(left) != len(right):
            return f'{path}: length {len(left)} != {len(right)}'
        for index, (a, b) in enumerate(zip(left, right)):
            mismatch = first_difference(a, b, f'{path}[{index}]')
            if mismatch:
                return mismatch
    elif left != right:
        return f'{path}: value differs'
    return None


def paths(manifest):
    result = []
    for line in manifest.read_text().splitlines():
        if line.strip():
            path = Path(line.strip())
            result.append(path if path.is_absolute() else manifest.parent / path)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    before, after = paths(args.baseline), paths(args.candidate)
    assert len(before) == len(after), 'Different module-list lengths'
    report = {'equivalent': True, 'ignoredMetadataKeys': sorted(CBV_KEYS), 'modules': []}
    for old, new in zip(before, after):
        baseline = strip_cbv(json.loads(old.read_text()))
        candidate = strip_cbv(json.loads(new.read_text()))
        difference = first_difference(baseline, candidate)
        record = {'module': candidate.get('module'), 'baseline': str(old), 'candidate': str(new),
                  'baselineSha256': digest(baseline), 'candidateSha256': digest(candidate),
                  'equivalent': difference is None}
        if difference:
            record['firstDifference'] = difference
            report['equivalent'] = False
        report['modules'].append(record)
    result = json.dumps(report, indent=2) + '\n'
    if args.output:
        args.output.write_text(result)
    print(result, end='')
    raise SystemExit(0 if report['equivalent'] else 1)


if __name__ == '__main__':
    main()
