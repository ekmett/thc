#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Prepare the pinned Graal tree from upstream edits and ordinary Jam sources."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
mode = parser.add_mutually_exclusive_group()
mode.add_argument('--check', action='store_true', help='Verify prepared sources without changing them')
mode.add_argument('--export', action='store_true', dest='export_sources',
                  help='Export edits from the prepared tree to src/graal/ and the upstream patch')
options = parser.parse_args()
source = root / 'upstream/graal25'
overlay = root / 'src/graal'
pin = json.loads((root / 'config/source-pins.json').read_text())['graal']['commit']
patch = root / 'patches/graal-jam.patch'
prepared_ref = 'refs/jam/prepared'


def git(*arguments, **keywords):
    return subprocess.check_output(['git', '-C', str(source), *arguments], **keywords)


if git('rev-parse', 'HEAD').decode().strip() != pin:
    raise SystemExit('Preserving a Graal checkout at a different revision.')
if git('ls-files', '--others', '--exclude-standard'):
    raise SystemExit('Preserving untracked files in the Graal checkout; stage new source files before export.')

if options.export_sources:
    # Existing upstream files stay a patch; additions are ordinary source files.
    names = [os.fsdecode(name) for name in git('diff', '--no-renames', '--name-only', '-z', '--diff-filter=A', 'HEAD').split(b'\0') if name]
    patch.write_bytes(git('diff', '--binary', '--no-renames', '--diff-filter=MDT', 'HEAD'))
    for path in overlay.rglob('*'):
        if path.is_file() and path.relative_to(overlay).as_posix() not in names:
            path.unlink()
    for name in names:
        target = overlay / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((source / name).read_bytes())
    print(f'Exported {len(names)} Graal source files and the upstream edits.')

# Git constructs the expected tree without changing the real index or worktree.
with tempfile.TemporaryDirectory(prefix='jam-graal-index-') as temporary:
    environment = dict(os.environ, GIT_INDEX_FILE=str(Path(temporary) / 'index'))
    def index(*arguments, **keywords):
        return git(*arguments, env=environment, **keywords)
    index('read-tree', 'HEAD')
    if patch.stat().st_size:
        index('apply', '--cached', str(patch))
    for path in sorted(overlay.rglob('*')):
        if not path.is_file():
            continue
        name = path.relative_to(overlay).as_posix()
        if index('ls-files', '--', name):
            raise SystemExit(f'Graal overlay replaces an upstream file; use the patch: {name}')
        blob = git('hash-object', '-w', '--stdin', input=path.read_bytes()).decode().strip()
        index('update-index', '--add', '--cacheinfo', '100644', blob, name)
    expected = index('write-tree').decode().strip()

if git('diff', expected, '--'):
    if options.check:
        raise SystemExit('Graal sources do not match the Jam sources and patch.')
    recorded = subprocess.run(['git', '-C', str(source), 'rev-parse', '--verify', prepared_ref],
                              capture_output=True, text=True)
    previous = recorded.stdout.strip() if recorded.returncode == 0 else 'HEAD'
    if git('diff', previous, '--') or git('diff', '--cached', previous, '--'):
        raise SystemExit('Preserving local Graal edits; export them or use a fresh workspace.')
    # Advance only a pristine previous preparation. Git also protects untracked
    # files that would be overwritten by a newly added source file.
    git('read-tree', '-m', '-u', previous, expected)
    if git('diff', expected, '--'):
        raise SystemExit('Prepared Graal sources differ from the expected tree.')
if options.export_sources:
    # Export accepted the working files; stage that same state for the next update.
    git('read-tree', expected)
if not options.check:
    git('update-ref', prepared_ref, expected)
print(f'Graal at {pin} matches the Jam sources and upstream patch.')
