#!/usr/bin/env python3
"""Bounded native/Core feasibility probe; no THC runtime or capability changes."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/int32x4-bytearray-probe'
FIXTURE = 'compiler/test-fixtures/SimdInt32X4ByteArray.hs'


def run(name, argv, env=None):
    (OUT / (name + '.command.json')).write_text(json.dumps(argv) + '\n')
    with (OUT / (name + '.log')).open('wb') as log:
        result = subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(env or {})),
                                stdout=log, stderr=subprocess.STDOUT)
    (OUT / (name + '.exit-status.txt')).write_text(str(result.returncode) + '\n')
    if result.returncode:
        sys.exit(f'{name} failed: {OUT / (name + ".log")}')


def signed32(value):
    bits = value % (1 << 32)
    return bits if bits < (1 << 31) else bits - (1 << 32)


def expected(name, offset, seed):
    values = [signed32(seed), signed32(seed + 17), signed32(seed * 3 - 29),
              signed32(seed ^ 0x55aa55aa)]
    weights = [3, 5, 7, 11]
    before = sum(x * w for x, w in zip(values, weights))
    storage = bytearray(64)
    address = offset * (16 if name == 'vectorUnitCase' else 4)
    for lane, value in enumerate(values):
        storage[address + 4 * lane:address + 4 * lane + 4] = value.to_bytes(4, 'little', signed=True)
    if name == 'vectorUnitCase':
        storage[address + 4:address + 8] = signed32(seed ^ 0x80000000).to_bytes(4, 'little', signed=True)
    else:
        storage[address + 7] = (seed + 101) % 256
    after = sum(int.from_bytes(storage[address + 4 * lane:address + 4 * lane + 4],
                               'little', signed=True) * weight
                for lane, weight in enumerate(weights))
    return before + 15 * after


def main():
    if sys.byteorder != 'little':
        sys.exit('This initial native byte-alias probe is little-endian only')
    if OUT.exists():
        sys.exit(f'Refusing to overwrite existing probe evidence: {OUT}')
    OUT.mkdir(parents=True)
    ghc = os.environ['GHC']
    native = OUT / 'native'
    native.mkdir(exist_ok=True)
    run('native-build', [ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint',
                        '-keep-s-files', '-icompiler/test-fixtures',
                        '-odir', str(native), '-hidir', str(native),
                        'compiler/test-fixtures/SimdInt32X4ByteArrayNative.hs',
                        '-o', str(native / 'int32x4-bytearray-probe')])
    # GHC -keep-s-files places these beside the input sources despite -odir.
    # Preserve the exact generated assembly under this probe's ignored output.
    for stem in ['SimdInt32X4ByteArray', 'SimdInt32X4ByteArrayNative']:
        shutil.move(ROOT / 'compiler/test-fixtures' / (stem + '.s'),
                    native / (stem + '.s'))
    run('native', [str(native / 'int32x4-bytearray-probe')])
    rows = (OUT / 'native.log').read_text().splitlines()
    if len(rows) != 306:
        sys.exit(f'Expected 306 native probe rows, got {len(rows)}')
    for row in rows:
        name, raw_offset, raw_seed, raw_result = row.split('\t')
        offset, seed, result = map(int, (raw_offset, raw_seed, raw_result))
        wanted = expected(name, offset, seed)
        if result != wanted:
            sys.exit(f'Native/model mismatch: {row}; expected {wanted}')
    run('plugin-build', ['compiler/build.sh'])
    for stage in ['pre', 'post']:
        argv = ['compiler/export.sh']
        if stage == 'post':
            argv.append('-fplugin-opt=THC.Plugin:post-tidy')
        argv.append(FIXTURE)
        run(stage + '-export', argv,
            {'THC_CORE_OUT': str(OUT / (stage + '-core')),
             'THC_GHC_OUT': str(OUT / (stage + '-ghc'))})
    print(f'PASS: {len(rows)} native/model rows; genuine pre/post exports in {OUT}')


if __name__ == '__main__':
    main()
