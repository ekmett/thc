#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Extract the hash-checked JDK input and apply the HotSpot adapter patch."""
import hashlib
import argparse
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--graal', action='store_true', help='Prepare the matching LabsJDK for GraalVM')
label = 'labsjdk25' if parser.parse_args().graal else 'jdk25'
archive, destination = root / 'upstream' / (label + '.tar.gz'), root / 'upstream' / label
expected = json.loads((root / 'config/source-pins.json').read_text())[label]['archive_sha256']
if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
    raise SystemExit('JDK archive hash mismatch.')
if destination.exists():
    raise SystemExit(f'Preserving existing upstream/{label}; use a fresh workspace to reproduce.')
destination.mkdir()
subprocess.run(['tar', '-xzf', str(archive), '-C', str(destination), '--strip-components=1'], check=True)
patch = os.environ.get('JAM_PATCH', 'patch')
subprocess.run([patch, '--batch', '--fuzz=0', '-p1', '-i', str(root / 'patches/hotspot-jam.patch')], cwd=destination, check=True)
if label == 'labsjdk25':
    subprocess.run([patch, '--batch', '--fuzz=0', '-p1', '-i', str(root / 'patches/labsjdk-compat.patch')], cwd=destination, check=True)
print(destination)
