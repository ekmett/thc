#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Export the HotSpot adaptation against the pinned source archive."""
import argparse
import difflib
import sys
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--graal', action='store_true', help='Export Graal source files and upstream edits')
if parser.parse_args().graal:
    subprocess.run([sys.executable, str(root / 'tools/prepare_graal.py'), '--export'], check=True)
    raise SystemExit(0)
def difference(before, after, name):
    return ''.join(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                                       fromfile='a/' + name, tofile='b/' + name))
# Only tracked source edits, against files read straight from the pinned archive.
with tarfile.open(root / 'upstream/jdk25.tar.gz') as archive:
    members = {m.name.split('/', 1)[1]: m for m in archive.getmembers() if '/' in m.name and m.isfile()}
    paths = [p for directory in ['epsilon', 'jam']
             for p in (root / 'upstream/jdk25/src/hotspot/share/gc' / directory).rglob('*') if p.is_file()]
    paths += [p for arch in ['x86', 'aarch64']
              for p in (root / 'upstream/jdk25/src/hotspot/cpu' / arch / 'gc/jam').rglob('*') if p.is_file()]
    paths += [root / 'upstream/jdk25' / p for p in [
        'make/autoconf/jvm-features.m4',
        'make/hotspot/lib/JvmFeatures.gmk',
        'src/hotspot/os/windows/os_windows.cpp',
        'src/hotspot/os/windows/os_windows.hpp',
        'src/hotspot/share/memory/memoryReserver.cpp',
        'src/hotspot/share/utilities/macros.hpp',
        'src/hotspot/share/gc/shared/gc_globals.hpp',
        'src/hotspot/share/gc/shared/collectedHeap.hpp',
        'src/hotspot/share/gc/shared/memAllocator.cpp',
        'src/hotspot/share/services/heapDumper.cpp',
        'src/hotspot/share/prims/jvmtiTagMap.cpp',
        'src/hotspot/share/runtime/vmOperations.cpp',
        'src/hotspot/share/gc/shared/gcConfig.cpp',
        'src/hotspot/share/gc/shared/barrierSet.hpp',
        'src/hotspot/share/gc/shared/barrierSetConfig.hpp',
        'src/hotspot/share/gc/shared/barrierSetConfig.inline.hpp',
        'src/hotspot/share/gc/shared/vmStructs_gc.hpp',
        'src/hotspot/share/gc/shared/gcName.hpp',
        'src/hotspot/share/gc/shared/gcConfiguration.cpp',
        'src/hotspot/share/jvmci/vmStructs_jvmci.cpp',
        'src/hotspot/share/jvmci/jvmciCompilerToVMInit.cpp',
        'src/hotspot/share/jvmci/jvmciCompilerToVM.cpp',
        'src/hotspot/share/jvmci/jvmci_globals.cpp',
        'src/jdk.hotspot.agent/share/classes/sun/jvm/hotspot/memory/Universe.java',
        'src/jdk.hotspot.agent/share/classes/sun/jvm/hotspot/gc/shared/CollectedHeapName.java',
        'src/jdk.hotspot.agent/share/classes/sun/jvm/hotspot/tools/HeapSummary.java',
        'src/jdk.hotspot.agent/share/classes/sun/jvm/hotspot/HSDB.java',
        'test/lib/jdk/test/whitebox/gc/GC.java',
        'src/hotspot/cpu/aarch64/gc/shared/cardTableBarrierSetAssembler_aarch64.cpp',
        'src/hotspot/cpu/riscv/gc/shared/cardTableBarrierSetAssembler_riscv.cpp',
        'src/hotspot/cpu/arm/gc/shared/cardTableBarrierSetAssembler_arm.cpp',
        'src/hotspot/share/gc/shared/cardTable.cpp',
        'src/hotspot/share/gc/shared/gcVMOperations.cpp',
        'src/hotspot/share/gc/shared/gcVMOperations.hpp',
        'src/hotspot/share/gc/shared/referenceProcessor.cpp',
        'src/hotspot/share/gc/shared/referenceProcessor.hpp',
        'src/hotspot/share/runtime/vmOperation.hpp',
        'src/hotspot/share/runtime/thread.cpp',
        'src/hotspot/share/c1/c1_Runtime1.cpp',
        'src/hotspot/share/oops/stackChunkOop.inline.hpp',
        'src/hotspot/share/runtime/continuationFreezeThaw.cpp',
        'src/jdk.hotspot.agent/share/classes/sun/jvm/hotspot/gc/epsilon/EpsilonHeap.java',
        'src/jdk.hotspot.agent/share/classes/sun/jvm/hotspot/gc/jam/JamHeap.java',
        'src/hotspot/share/include/jvm.h']]
    patch = ''
    for p in sorted(paths, key=lambda p: p.as_posix()):
        name = p.relative_to(root / 'upstream/jdk25').as_posix()
        before = archive.extractfile(members[name]).read().decode() if name in members else ''
        patch += difference(before, p.read_text(), name)
(root / 'patches/hotspot-jam.patch').write_text(patch, newline='\n')
print('Exported the HotSpot source patch.')
labs_archive = root / 'upstream/labsjdk25.tar.gz'
if labs_archive.exists():
    with tarfile.open(labs_archive) as archive:
        names = ['src/hotspot/share/code/nmethod.hpp',
                 'src/hotspot/share/jvmci/jvmciCompilerToVM.cpp']
        with tempfile.TemporaryDirectory(prefix='jam-labs-patch-') as temporary:
            stage = Path(temporary)
            for name in names:
                member = next(m for m in archive.getmembers() if m.name.endswith('/' + name))
                target = stage / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.extractfile(member).read())
            # Compare with the common Jam patch already applied to these files.
            shared = ''.join('--- a/' + section for section in patch.split('--- a/')[1:]
                             if section.splitlines()[0] in names)
            subprocess.run([os.environ.get('JAM_PATCH', 'patch'), '--batch', '--fuzz=0', '-p1'], cwd=stage,
                           input=shared.encode(), check=True)
            compatibility = ''.join(difference((stage / name).read_text(),
                (root / 'upstream/labsjdk25' / name).read_text(), name) for name in names)
            (root / 'patches/labsjdk-compat.patch').write_text(compatibility, newline='\n')
    print('Exported the LabsJDK compatibility patch.')
