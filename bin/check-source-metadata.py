#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Validate source tables independently of the runtime's section resolver."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('paths', nargs='*', type=Path)
parser.add_argument('--fixture', action='store_true', help='require Unicode, tabs, absent content and nested notes')
args = parser.parse_args()
paths = args.paths or [Path('build/core/SourceNotes.json'), Path('build/core/RepresentationAudit.json')]
counts = {'files': 0, 'spans': 0, 'references': 0, 'nestedNotes': 0, 'missingContent': 0, 'unicode': 0, 'tabs': 0}


def boundaries(text):
    result = {}
    line = column = 1
    offset = 0
    for c in text:
        result[line, column] = offset
        offset += len(c.encode('utf-16-le')) // 2
        if c == '\n':
            line, column = line + 1, 1
        elif c == '\t':
            column += 8 - ((column - 1) % 8)
        else:
            column += 1
    result[line, column] = offset
    return result


for path in paths:
    data = json.loads(path.read_text())
    files = {f['id']: f for f in data['sourceFiles']}
    spans = {s['id']: s for s in data['sourceSpans']}
    assert len(files) == len(data['sourceFiles']), path
    assert len(spans) == len(data['sourceSpans']), path
    offsets = {key: boundaries(f['content']) if f['content'] is not None else {} for key, f in files.items()}
    counts['files'] += len(files)
    for file in files.values():
        counts['missingContent'] += file['content'] is None
        counts['unicode'] += file['content'] is not None and any(ord(c) > 0xffff for c in file['content'])
        counts['tabs'] += file['content'] is not None and '\t' in file['content']
    for span in spans.values():
        counts['spans'] += 1
        start = (span['startLine'], span['startColumn'])
        end = (span['endLine'], span['endColumn'])
        assert min(*start, *end) >= 1 and end >= start, span
        positions = offsets[span['file']]
        begin, finish = positions.get(start), positions.get(end)
        expected = (begin, finish - begin) if begin is not None and finish is not None else (None, None)
        assert (span['charIndex'], span['charLength']) == expected, (path, span, expected)
    def check(node):
        if isinstance(node, dict):
            if 'source' in node:
                counts['references'] += 1
                assert node['source'] in spans, (path, node['source'])
            if 'sourceNotes' in node:
                notes = node['sourceNotes']
                assert notes and node['source'] == notes[-1], (path, node)
                assert all(note in spans for note in notes), (path, notes)
                counts['nestedNotes'] += len(notes) > 1
            for child in node.values():
                check(child)
        elif isinstance(node, list):
            for child in node:
                check(child)
    check(data['bindings'])
if args.fixture:
    assert all(counts[k] for k in ['nestedNotes', 'missingContent', 'unicode', 'tabs']), counts
print('PASS source metadata:', json.dumps(counts, sort_keys=True))
