#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Run the selected runtime's checks without fetching or rebuilding the runtime."""

import argparse
import os
from pathlib import Path
import subprocess
import sys
from platform_paths import ROOT, NATIVE_BUILD, java_tool


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runtime', choices=('graalvm', 'substratevm'))
    parser.add_argument('--java-home', type=Path, default=ROOT / 'build/graalvm')
    parser.add_argument('--unsupported-libgraal', type=Path,
                        help='Check rejection of an unmodified matching libgraal')
    options = parser.parse_args()
    home = options.java_home.resolve()
    if options.runtime != 'graalvm' and options.unsupported_libgraal:
        parser.error('--unsupported-libgraal requires the graalvm checks')
    environment = dict(os.environ)
    environment['PATH'] = str(NATIVE_BUILD) + os.pathsep + environment.get('PATH', '')
    environment['JAM_JAVA'] = str(java_tool(home, 'java'))
    environment['JAM_JAVAC'] = str(java_tool(home, 'javac'))
    if options.runtime == 'graalvm':
        # The Graal distribution includes HotSpot; keep its interpreter/C1/C2
        # and unsupported-GC controls alongside libgraal qualification.
        graal_arguments = ['--java-home', str(home)]
        if options.unsupported_libgraal:
            graal_arguments += ['--unsupported-libgraal', str(options.unsupported_libgraal)]
        checks = [('check_vm.py',), ('check_gc_registration.py',), ('check_bridge.py',),
                  ('check_graal.py', *graal_arguments),
                  ('check_package.py', '--java-home', str(home))]
    else:
        checks = [('check_substrate.py', '--java-home', str(home))]
    for script, *arguments in checks:
        subprocess.run([sys.executable, str(ROOT / 'tools' / script), *arguments],
                       cwd=ROOT, env=environment, check=True)


if __name__ == '__main__':
    main()
