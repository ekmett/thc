#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Check Graal compiler barriers and derived pointers with the matching Jam VM."""

import argparse
from pathlib import Path

from build_graal import graal_environment, run_mx, verify_sources


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, help='matching LabsJDK builder (or JAM_GRAAL_BASE_JDK)')
    environment = graal_environment(parser.parse_args().java_home)
    verify_sources(environment)
    run_mx('compiler', ['--max-cpus', environment.get('JAM_JOBS', '3'), 'build', '--build-logs=silent'], environment)
    run_mx('compiler', ['unittest', '-XX:+UnlockExperimentalVMOptions', '-XX:+UnlockDiagnosticVMOptions', '-XX:+UseJamGC', '-Xshare:off',
                       '-Xms128m', '-Xmx128m', '-XX:+VerifyBeforeGC', '-XX:+VerifyAfterGC',
                       '-Xlog:gc=debug',
                       'GraalHotSpotVMConfigAccessTest', 'WriteBarrierAdditionTest', 'DeferredBarrierAdditionTest',
                       'DerivedOopTest', 'PointerTrackingTest'], environment)


if __name__ == '__main__':
    main()
