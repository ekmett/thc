#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Check release qualification and installation identity without a runtime build."""

import hashlib
import io
from pathlib import Path
import sys
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools/ci'))
from release_runtime import inventory, require_run, qualified_artifacts, PLATFORMS
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from platform_paths import build_flavor, jdk_home, reported_flavor
from package_jdk import static_libraries
import build_graal
import check_runtime


class ReleaseTests(unittest.TestCase):
    def test_static_archive_closure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            build, runtime = root / 'build', root / 'runtime'
            build.mkdir(); runtime.mkdir()
            archives = [build / name for name in ('libthc-vm-static.a', 'libjam.a', 'libwork.a', 'libnative.a', 'libnative_minimal.a')]
            runtimes = [runtime / name for name in ('libc++.a', 'libc++abi.a', 'libunwind.a')]
            for archive in archives + runtimes:
                archive.write_bytes(b'!<arch>\n')
            manifest = build / 'thc-vm-static-libraries-Release.txt'
            manifest.write_text('\n'.join(map(str, archives)) + '\n')
            (build / 'thc-vm-static-libraries-Debug.txt').write_text('missing.a\n')
            self.assertEqual(static_libraries(build, runtime, 'Linux'), archives + runtimes)
            with self.assertRaises(SystemExit):
                static_libraries(build, runtime, 'Linux', 'Debug')
            # A missing runtime must fail packaging, not silently restore sidecars.
            runtimes[-1].unlink()
            with self.assertRaises(SystemExit):
                static_libraries(build, runtime, 'Linux')
            runtimes[-1].write_bytes(b'!<thin>\n')
            with self.assertRaises(SystemExit):
                static_libraries(build, runtime, 'Linux')
            runtimes[-1].write_bytes(b'!<arch>\n')
            manifest.write_text('\n'.join(map(str, archives + archives[:1])) + '\n')
            with self.assertRaises(SystemExit):
                static_libraries(build, runtime, 'Linux')

    def test_build_flavor_selection_and_report(self):
        for flavor in ('release', 'fastdebug'):
            with self.subTest(flavor=flavor), patch.dict('os.environ', {'JAM_BUILD_FLAVOR': flavor}):
                self.assertEqual(build_flavor(), flavor)
                self.assertIn(f'server-{flavor}', str(jdk_home()))
                self.assertIn('labsjdk25', str(jdk_home()))
                self.assertEqual(jdk_home().name, 'graal-builder-jdk')
                self.assertEqual(reported_flavor(f'Property settings:\n    jdk.debug = {flavor}\n'), flavor)
        with patch.dict('os.environ', {'JAM_BUILD_FLAVOR': 'unknown'}), self.assertRaises(SystemExit):
            build_flavor()
        for output in ('', 'jdk.debug = unknown', 'jdk.debug = release\njdk.debug = fastdebug'):
            with self.subTest(output=output), self.assertRaises(ValueError):
                reported_flavor(output)

    def test_darwin_graal_deployment_environment(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ('bin/java', 'lib/server/libjvm.dylib', 'mx.py'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()
            for inherited, target, prior in (({}, '15.5', ''),
                    ({'MACOSX_DEPLOYMENT_TARGET': '15.4',
                      'EXTRA_IMAGE_BUILDER_ARGUMENTS': '--verbose'}, '15.4', '--verbose ')):
                with self.subTest(target=target), patch.dict('os.environ', inherited, clear=True), \
                        patch.object(build_graal, 'ROOT', root), \
                        patch.object(build_graal, 'MX', root / 'mx.py'), \
                        patch.object(build_graal.platform, 'system', return_value='Darwin'):
                    environment = build_graal.graal_environment(root)
                    self.assertEqual(environment['MACOSX_DEPLOYMENT_TARGET'], target)
                    self.assertEqual(environment['EXTRA_IMAGE_BUILDER_ARGUMENTS'],
                                     prior + '-EMACOSX_DEPLOYMENT_TARGET=' + target)

    def test_only_qualified_main_runs(self):
        run = dict(status='completed', conclusion='success', event='push', head_branch='main', head_repository={'full_name': 'ekmett/thc'}, path='.github/workflows/vm.yml')
        jobs = [dict(name=f'{s} ({p})', conclusion='success') for p in PLATFORMS for s in
                ('Build GraalVM', 'Native and guest bridge', 'GraalVM runtime', 'SubstrateVM')]
        for event in ('push', 'workflow_dispatch', 'pull_request', 'schedule'):
            with self.subTest(event=event):
                require_run({**run, 'event': event}, jobs, {'status': 'ahead'})
                # A successful smoke-only run cannot qualify a release.
                smoke = [job for job in jobs if job['name'].startswith('Native and guest bridge')]
                with self.assertRaises(ValueError):
                    require_run({**run, 'event': event}, smoke, {'status': 'ahead'})
        for field, value in [('event', 'pull_request_target'), ('head_repository', {'full_name': 'other/fork'}), ('status', 'in_progress'), ('conclusion', 'failure'), ('path', '.github/workflows/ci.yml')]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                require_run({**run, field: value}, jobs, {'status': 'ahead'})
        with self.assertRaises(ValueError):
            require_run(run, jobs[:-1], {'status': 'ahead'})
        require_run({**run, 'event': 'pull_request', 'head_branch': 'merged-branch'}, jobs, {'status': 'ahead'})
        with self.assertRaises(ValueError):
            require_run(run, jobs, {'status': 'diverged'})

    def test_artifact_flavor_is_unambiguous(self):
        artifacts = [dict(name=f'thc-graal-release-{platform}', expired=False)
                     for platform in PLATFORMS]
        self.assertEqual(qualified_artifacts(artifacts, 'release'), artifacts)
        for invalid in (artifacts[:-1], artifacts + artifacts[:1],
                        [{**a, 'expired': True} for a in artifacts],
                        [{**a, 'name': a['name'].replace('release', 'fastdebug')} for a in artifacts]):
            with self.subTest(artifacts=invalid), self.assertRaises(ValueError):
                qualified_artifacts(invalid, 'release')

    def test_graal_qualification_keeps_shared_hotspot_controls(self):
        home = Path('/selected/graalvm')
        unsupported = Path('/stock/libgraal')
        arguments = ['check_runtime.py', 'graalvm', '--java-home', str(home),
                     '--unsupported-libgraal', str(unsupported)]
        with patch.object(sys, 'argv', arguments), \
                patch.object(check_runtime, 'java_tool', side_effect=lambda root, tool: root / 'bin' / tool), \
                patch.object(check_runtime.subprocess, 'run') as run:
            check_runtime.main()
        calls = {Path(call.args[0][1]).name: call for call in run.call_args_list}
        self.assertEqual(set(calls), {'check_vm.py', 'check_gc_registration.py', 'check_bridge.py',
                                     'check_graal.py', 'check_package.py'})
        graal = calls['check_graal.py'].args[0]
        self.assertEqual(graal[2:], ['--java-home', str(home), '--unsupported-libgraal', str(unsupported)])
        for call in calls.values():
            self.assertTrue(call.kwargs['check'])
            self.assertEqual(call.kwargs['env']['JAM_JAVA'], str(home / 'bin/java'))
            self.assertEqual(call.kwargs['env']['JAM_JAVAC'], str(home / 'bin/javac'))
        # A failing shared control must stop qualification, without retries.
        with patch.object(sys, 'argv', arguments), \
                patch.object(check_runtime, 'java_tool', side_effect=lambda root, tool: root / 'bin' / tool), \
                patch.object(check_runtime.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'control')) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                check_runtime.main()
            self.assertEqual(run.call_count, 1)

    def test_content_identity_and_unsafe_members(self):
        files = {'release': b'JAVA_VERSION="25"\r\nJAM_BUILD_FLAVOR="release"\r\n', 'lib/thc/thc-vm.jar': b'api',
                 'lib/thc/runtime-libraries.txt': b'libthc-vm.so\n', 'legal/thc-vm/NOTICE.md': b'notice'}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'runtime.tar.gz'
            def write(extra=None):
                with tarfile.open(path, 'w:gz') as archive:
                    for name, data in reversed(list(files.items())):
                        entry = tarfile.TarInfo('graalvm/' + name); entry.size = len(data)
                        archive.addfile(entry, io.BytesIO(data))
                    link = tarfile.TarInfo('graalvm/lib/api.jar'); link.type = tarfile.SYMTYPE; link.linkname = 'thc/thc-vm.jar'
                    archive.addfile(link)
                    if extra: archive.addfile(extra, io.BytesIO(b''))
            write()
            identity, _, release, _ = inventory(path, 'graalvm')
            lines = {name: hashlib.sha256(data).hexdigest() + '  ' + name + '\n' for name, data in files.items()}
            lines['lib/api.jar'] = 'link  thc/thc-vm.jar  lib/api.jar\n'
            expected = hashlib.sha256(''.join(lines[name] for name in sorted(lines)).encode()).hexdigest()
            self.assertEqual(identity['sha256'], expected)
            self.assertEqual((identity['files'], identity['symlinks']), (4, 1))
            self.assertEqual(release['JAVA_VERSION'], '25')
            self.assertEqual(release['JAM_BUILD_FLAVOR'], 'release')
            for name in ('../escape', '/absolute', 'graalvm/release'):
                write(tarfile.TarInfo(name))
                with self.subTest(name=name), self.assertRaises(ValueError): inventory(path, 'graalvm')
            link = tarfile.TarInfo('graalvm/bad'); link.type = tarfile.SYMTYPE; link.linkname = '../../outside'
            write(link)
            with self.assertRaises(ValueError): inventory(path, 'graalvm')


if __name__ == '__main__':
    unittest.main()
