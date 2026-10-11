#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Archive an already relocated and checked plain Jam JDK."""

import argparse
from pathlib import Path
import tarfile
from archive_graal import metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    home, output = args.java_home.resolve(), args.output.resolve()
    if output.exists() or output.is_relative_to(home):
        parser.error('choose a new archive path outside the JDK')
    for name in ('release', 'lib/jam/jam-vm.jar', 'lib/jam/runtime-libraries.txt', 'legal/jam-vm/NOTICE.md'):
        if not (home / name).is_file():
            parser.error(f'missing packaged input: {name}')
    for path in home.rglob('*'):
        if path.is_symlink() and (path.readlink().is_absolute() or not path.exists() or not path.resolve().is_relative_to(home)):
            parser.error(f'broken or external symlink: {path}')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(output, 'w:gz', compresslevel=1) as archive:
        archive.add(home, arcname='jdk', filter=metadata)


if __name__ == '__main__':
    main()
