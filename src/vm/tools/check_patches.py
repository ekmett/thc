#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Reproduce the HotSpot adaptation from its patch."""
import argparse
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--graal', action='store_true', help='Check LabsJDK and Graal adaptations')
options = parser.parse_args()
label = 'labsjdk25'
with tarfile.open(root / 'upstream' / (label + '.tar.gz')) as archive:
    members = {m.name.split('/', 1)[1]: m for m in archive.getmembers()
               if '/' in m.name and m.isfile()}
    patches = [root / 'patches/hotspot-jam.patch', root / 'patches/labsjdk-compat.patch']
    adapted = root / 'upstream' / label
    paths = sorted({line[6:] for patch in patches for line in patch.read_text().splitlines()
                    if line.startswith('--- a/')})
    with tempfile.TemporaryDirectory(prefix='jam-patch-') as temporary:
        stage = Path(temporary)
        for name in paths:
            relative = Path(name)
            if relative.is_absolute() or '..' in relative.parts:
                raise SystemExit('Invalid patch path')
            target = stage / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if name in members:
                target.write_bytes(archive.extractfile(members[name]).read())
        for patch in patches:
            subprocess.run([os.environ.get('JAM_PATCH', 'patch'), '--batch', '--fuzz=0', '-p1', '-i', str(patch)], cwd=stage, check=True)
        for name in paths:
            if (stage / name).read_bytes() != (adapted / name).read_bytes():
                raise SystemExit(f'Patch round trip differs: {name}')
    print(f'{", ".join(patch.name for patch in patches)}: {len(paths)} files reproduced exactly')
    epsilon_prefix = 'src/hotspot/share/gc/epsilon/'
    expected = {name for name in members if name.startswith(epsilon_prefix)}
    actual = {p.relative_to(adapted).as_posix()
              for p in (adapted / epsilon_prefix).rglob('*') if p.is_file()}
    if actual != expected:
        raise SystemExit('Epsilon source file set differs from the pinned original')
    expected.add('src/jdk.hotspot.agent/share/classes/sun/jvm/hotspot/gc/epsilon/EpsilonHeap.java')
    for name in expected:
        if (adapted / name).read_bytes() != archive.extractfile(members[name]).read():
            raise SystemExit(f'Original Epsilon source changed: {name}')
    print('Epsilon collector and SA heap sources are byte-for-byte upstream.')
if options.graal:
    import sys
    subprocess.run([sys.executable, str(root / 'tools/prepare_graal.py'), '--check'], check=True)
