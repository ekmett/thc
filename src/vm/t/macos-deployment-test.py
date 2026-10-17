#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Check the package deployment floor without building a JDK."""

import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from macho_deployment import check_macos_deployment, deployment_versions, macos_version


def image(command, filetype=2):
    return (f'Mach header\n      magic cputype cpusubtype caps filetype ncmds sizeofcmds flags\n'
            f' 0xfeedfacf 16777228 0 0x00 {filetype} 1 24 0x0\nLoad command 0\n{command}\n')


class DeploymentTests(unittest.TestCase):
    def test_only_deployment_commands_count(self):
        output = image(' cmd LC_BUILD_VERSION\n platform 1\n minos 15.5\n sdk 26.0\n'
                       'Load command 1\n cmd LC_SOURCE_VERSION\n version 999.0')
        self.assertEqual(deployment_versions(output), [(15, 5, 0)])
        legacy = image(' cmd LC_VERSION_MIN_MACOSX\n version 10.15.7\n sdk 15.5')
        self.assertEqual(deployment_versions(output + legacy), [(15, 5, 0), (10, 15, 7)])
        self.assertEqual(macos_version('15.5'), macos_version('15.5.0'))

    def test_missing_malformed_or_wrong_platform_fails(self):
        for command in (' cmd LC_SOURCE_VERSION\n version 15.5',
                        ' cmd LC_BUILD_VERSION\n platform 1\n minos invalid',
                        ' cmd LC_BUILD_VERSION\n platform 2\n minos 15.5',
                        ' cmd LC_VERSION_MIN_MACOSX\n version 0'):
            with self.subTest(command=command), self.assertRaises(ValueError):
                deployment_versions(image(command))
        with self.assertRaises(ValueError):
            deployment_versions('not a Mach-O header')
        valid = image(' cmd LC_VERSION_MIN_MACOSX\n version 15.5')
        with self.assertRaises(ValueError):
            deployment_versions(valid + image(' cmd LC_SOURCE_VERSION\n version 15.5'))
        self.assertEqual(deployment_versions(image(' cmd LC_SEGMENT_64', filetype=1)), [])

    @unittest.skipUnless(platform.system() == 'Darwin', 'requires Apple compiler and otool')
    def test_compiled_executable_dylib_universal_and_archive(self):
        with tempfile.TemporaryDirectory(prefix='jam-deployment-test-') as temporary:
            root = Path(temporary)
            source = root / 'probe.c'
            source.write_text('int main(void) { return 0; }\n')
            package = root / 'package'
            package.mkdir()
            def compile(name, target, *flags):
                subprocess.run(['xcrun', 'clang', f'-mmacosx-version-min={target}',
                                *flags, str(source), '-o', str(package / name)],
                               check=True, capture_output=True, text=True)
            compile('java', '15.5', '-Wl,-source_version,999.0')
            compile('libprobe.dylib', '15.5', '-dynamiclib')
            check_macos_deployment(package, '15.5')
            compile('libprobe.dylib', '26.0', '-dynamiclib')
            with self.assertRaisesRegex(SystemExit, r'libprobe.dylib.*26.0'):
                check_macos_deployment(package, '15.5')
            marker = root / 'java-launched'
            (package / 'bin').mkdir()
            launcher = package / 'bin/java'
            launcher.write_text(f'#!/bin/sh\ntouch "{marker}"\n')
            launcher.chmod(0o755)
            result = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1] /
                                     'tools/check_package.py'), '--java-home', str(package)],
                                    capture_output=True, text=True,
                                    env={key: value for key, value in os.environ.items()
                                         if key != 'MACOSX_DEPLOYMENT_TARGET'})
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('exceeds MACOSX_DEPLOYMENT_TARGET=15.5', result.stderr)
            self.assertFalse(marker.exists(), 'deployment audit must precede JVM launch')
            (package / 'libprobe.dylib').unlink()
            compile('arm', '15.5', '-arch', 'arm64')
            compile('x86', '26.0', '-arch', 'x86_64')
            subprocess.run(['xcrun', 'lipo', '-create', str(package / 'arm'), str(package / 'x86'),
                            '-output', str(package / 'universal')], check=True)
            (package / 'arm').unlink()
            (package / 'x86').unlink()
            with self.assertRaisesRegex(SystemExit, r'universal.*26.0'):
                check_macos_deployment(package, '15.5')
            check_macos_deployment(package, '26.0')
            (package / 'universal').unlink()
            compile('probe.o', '26.0', '-c')
            subprocess.run(['xcrun', 'ar', 'rcs', str(package / 'libprobe.a'),
                            str(package / 'probe.o')], check=True)
            (package / 'probe.o').unlink()
            with self.assertRaisesRegex(SystemExit, r'libprobe.a.*26.0'):
                check_macos_deployment(package, '15.5')


if __name__ == '__main__':
    unittest.main()
