#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import importlib.util
import hashlib
import io
import tarfile
import zipfile
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("fast_ci", Path(__file__).with_name("fast_ci.py"))
ci = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ci)


class FastRunnerTest(unittest.TestCase):
    def test_ninja_trace_excludes_history_and_groups_multi_output_edges(self):
        for version in (5, 6, 7):
            with self.subTest(version=version):
                self.check_ninja_trace(version)

    def check_ninja_trace(self, version):
        log = self.root / '.ninja_log'
        before = f'# ninja log v{version}\n0\t90\t1\told\taaa\n'.encode()
        log.write_bytes(before + b'0\t20\t2\ta\tbbb\n0\t20\t2\tb\tbbb\n5\t10\t3\tc\tccc\n')
        events = ci.ninja_events(log, before, 1000000)
        output = self.root / 'trace.json'
        ci.write_trace(output, events)
        spans = [event for event in json.loads(output.read_text())['traceEvents'] if event['ph'] == 'X']
        self.assertEqual(2, len(spans))
        self.assertEqual(['a', 'b'], spans[0]['args']['outputs'])
        self.assertEqual((1000000, 20000), (spans[0]['ts'], spans[0]['dur']))
        self.assertNotEqual(spans[0]['tid'], spans[1]['tid'])
        self.assertEqual([], ci.ninja_events(log, log.read_bytes(), 2000000))
        log.write_bytes(b'# ninja log v6\n')
        with self.assertRaisesRegex(RuntimeError, 'refusing stale timings'):
            ci.ninja_events(log, before, 3000000)

    def test_failed_commands_keep_trace_and_original_failure_on_repeated_runs(self):
        with patch.object(ci, 'git', return_value='a' * 40):
            first = ci.Recorder(self.root, self.root / 'report')
            second = ci.Recorder(self.root, self.root / 'report')
        self.assertNotEqual(first.trace_directory, second.trace_directory)
        with self.assertRaisesRegex(RuntimeError, 'exit 7'):
            first.command('failure', [sys.executable, '-c', 'raise SystemExit(7)'])
        (first.trace_directory / 'interrupted.events.json').write_text('{')
        ci.merge_traces(first.trace_directory, self.root / 'failed-trace.json')
        spans = [e for e in json.loads((self.root / 'failed-trace.json').read_text())['traceEvents'] if e['ph'] == 'X']
        self.assertEqual(7, spans[0]['args']['exitCode'])
        self.assertGreater(spans[0]['dur'], 0)

    def test_commit_graph_selects_changed_models_without_dropping_runtime_coverage(self):
        import fast_select
        changed = dict(mode="narrow", runnable=True, changedPaths=["bin/core-package-manifest.py"],
                       base="a" * 40, head="b" * 40, reasons=[],
                       affected=dict(python=["bin/test-core-package-manifest.py"], haskell=[], junit=[]))
        manifest = {"groups": {"runtime": {"cmakeTarget": "fixture-runtime", "requires": []},
                               "windows": {"gradleTask": "compileWindowsIoFixture", "requires": []}}}
        with patch.object(fast_select, "select", return_value=changed), \
             patch.object(fast_select, "group_selection", return_value={"junit": {"classes": ["RuntimeTest", "WindowsTest"]}}), \
             patch.object(ci.fixtures, "_manifest", return_value=(manifest, {"RuntimeTest": "runtime", "WindowsTest": "windows"})):
            plan = ci.commit_plan(self.root, "HEAD~1", "HEAD")
            self.assertEqual([dict(path="bin/test-core-package-manifest.py", optimized=True)], plan["python"])
            self.assertEqual(["fixture-runtime"], plan["fixtures"])
            self.assertEqual(["compileWindowsIoFixture"], plan["gradleFixtures"])
            self.assertFalse(plan["protocol"])
            self.assertEqual([], plan["haskell"])
            changed["mode"] = "full"
            plan = ci.commit_plan(self.root, "missing-base", "HEAD")
            self.assertIn(dict(path="bin/test-audit-core.py", optimized=False), plan["python"])
            self.assertIn("driver-tests", plan["haskell"])
            self.assertTrue(plan["protocol"])

    def test_application_and_test_support_are_separate_build_stages(self):
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "setup")
        with patch.object(recorder, "command") as command:
            ci.compile_common(recorder, reuse_daemon=True)
            application = [call.args[1] for call in command.call_args_list]
            self.assertEqual(["cabal", "build", "exe:thc"], application[0])
            self.assertEqual("installDist", application[1][-1])
            self.assertIn("--daemon", application[1])
            self.assertFalse(any("testClasses" in argv or "fixture-tools" in argv for argv in application))
            command.reset_mock()
            with patch.dict(os.environ, {"GHC": "/pinned/ghc", "GHC_PKG": "/pinned/ghc-pkg", "CABAL": "/pinned/cabal"}):
                ci.compile_test_support(recorder, reuse_daemon=True)
            support = [call.args[1] for call in command.call_args_list]
            self.assertTrue(any("--only-dependencies" in argv and "exe:thc-fixtures" in argv for argv in support))
            configuration = next(argv for argv in support if argv[0] == "cmake" and "-S" in argv)
            self.assertIn("-DCABAL=/pinned/cabal", configuration)
            self.assertIn("-DGHC=/pinned/ghc", configuration)
            self.assertTrue(any("fixture-tools" in argv for argv in support))
            self.assertEqual(["testClasses", "toolsJar"], support[-1][-2:])
            self.assertFalse(any("testDefault" in argv or "testDense" in argv for argv in support))

    def test_setup_processes_start_together_and_join(self):
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "setup")
        commands = []
        for own, peer in (("first", "second"), ("second", "first")):
            script = textwrap.dedent(f'''
                from pathlib import Path
                import time
                Path({own!r}).touch()
                deadline = time.monotonic() + 5
                while not Path({peer!r}).exists():
                    if time.monotonic() >= deadline:
                        raise SystemExit('peer did not start concurrently')
                    time.sleep(0.01)
            ''')
            commands.append((own, [sys.executable, "-c", script]))
        recorder.parallel(commands)
        self.assertEqual({"first", "second"}, {p["name"] for p in recorder.data["phases"]})
        self.assertTrue(all(p["exitCode"] == 0 for p in recorder.data["phases"]))

    def test_setup_failure_terminates_and_joins_other_processes(self):
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "setup")
        waiting = "import os,time; from pathlib import Path; Path('pid').write_text(str(os.getpid())); time.sleep(60)"
        failing = "from pathlib import Path; import time\nwhile not Path('pid').exists(): time.sleep(0.01)\nraise SystemExit(7)"
        with self.assertRaisesRegex(RuntimeError, "broken failed: exit 7"):
            recorder.parallel([("waiting", [sys.executable, "-c", waiting]),
                               ("broken", [sys.executable, "-c", failing])])
        with self.assertRaises(ProcessLookupError):
            os.kill(int((self.root / "pid").read_text()), 0)
        self.assertEqual(2, len(recorder.data["phases"]))

    def test_cache_restores_overlap_checkout_and_gate_only_their_consumers(self):
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "setup")
        action = self.root / "cache-action/dist/restore-only/index.js"
        action.parent.mkdir(parents=True)
        action.write_text(textwrap.dedent('''
            import os, time
            from pathlib import Path
            name = os.environ['INPUT_KEY']
            assert 'INPUT_SCRIPT' not in os.environ
            assert os.environ['INPUT_LOOKUP-ONLY'] == 'false'
            Path(name).touch()
            deadline = time.monotonic() + 5
            while not all(Path(peer).exists() for peer in ('ghc', 'java', 'index', 'source')):
                if time.monotonic() > deadline: raise SystemExit('restores did not overlap checkout')
                time.sleep(0.01)
            Path(name + '-done').touch()
            hit = {'ghc': 'true', 'java': 'false', 'index': ''}[name]
            Path(os.environ['GITHUB_OUTPUT']).write_text('cache-hit<<end\\n' + hit + '\\nend\\n')
        '''))
        layers = {name: {'path': 'cache/' + name, 'key': name} for name in ('ghc', 'java', 'index')}
        layers['disabled'] = {'enabled': False}
        with patch.dict(os.environ, THC_CACHE_ACTION=str(action.parents[2]), THC_NODE=sys.executable,
                        INPUT_SCRIPT='must not leak'):
            restores, environments, outputs = ci.cache_restores(recorder, layers)
        source = [sys.executable, '-c', "from pathlib import Path; Path('source').touch()"]
        consumer = [sys.executable, '-c', "from pathlib import Path; assert Path('ghc-done').exists()"]
        recorder.parallel([('source', source), ('haskell', consumer)] + restores,
                          dependencies={'haskell': ['restore-ghc']}, environments=environments)
        self.assertEqual({'ghc': 'true', 'java': 'false', 'index': ''},
                         {name: ci.action_outputs(path)['cache-hit'] for name, path in outputs.items()})
        phases = {phase['name']: phase for phase in recorder.data['phases']}
        self.assertGreaterEqual(phases['haskell']['started'], phases['restore-ghc']['finished'])
        events = json.loads(next(recorder.trace_directory.glob('setup-*.events.json')).read_text())['traceEvents']
        self.assertEqual(5, sum(event['ph'] == 'X' for event in events))

    def test_setup_worker_limit_and_dependencies(self):
        with patch.object(ci, 'git', return_value='a' * 40):
            recorder = ci.Recorder(self.root, self.root / 'setup')
        command = [sys.executable, '-c', 'pass']
        recorder.parallel([(str(i), command) for i in range(5)], workers=2)
        phases = recorder.data['phases']
        for phase in phases:
            active = sum(other['started'] <= phase['started'] < other['finished'] for other in phases)
            self.assertLessEqual(active, 2)
        with self.assertRaisesRegex(RuntimeError, 'Unknown setup dependency'):
            recorder.parallel([('a', command)], dependencies={'a': ['missing']})
        with self.assertRaisesRegex(RuntimeError, 'Cyclic setup dependencies'):
            recorder.parallel([('a', command)], dependencies={'a': ['a']})

    def test_cached_haskell_and_java_setup_needs_no_download(self):
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "setup")
        tools = self.root / "tools"
        ghcup = self.root / ".ghcup"
        self.jam_pin()
        with patch.dict(os.environ, THC_TOOLS=str(tools)), patch.object(ci.platform, "system", return_value="Linux"), patch.object(ci.platform, "machine", return_value="x86_64"):
            _, java, _ = ci.jam_package(self.root)
        for path, value in ((ghcup / "ghc/9.14.1/bin/ghc", "9.14.1"),
                            (ghcup / "ghc/9.14.1/bin/ghc-pkg", "GHC package manager version 9.14.1"),
                            (ghcup / "cabal/3.16.0.0/cabal", "3.16.0.0"),
                            (java / "bin/java", "GraalVM 25.3.4.1"),
                            (self.root / "bin/curl", "unexpected download")):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("#!/bin/sh\necho '" + value + "'\n" + ("exit 99\n" if path.name == "curl" else ""))
            path.chmod(0o755)
        (java / "release").write_text('GRAALVM_VERSION="25.3.4.1"\nJAVA_VERSION="25.0.4.1"\n')
        def installed_commands(commands):
            for name, argv in commands:
                if name == "haskell-toolchain":
                    recorder.command(name, argv)
                elif name == "graalvm-toolchain":
                    ci.install_jam(recorder)
        env = {"GITHUB_ACTIONS": "true", "THC_TOOLS": str(tools), "THC_GHCUP_ROOT": str(ghcup),
               "GITHUB_ENV": str(self.root / "env"), "GITHUB_PATH": str(self.root / "path"),
               "PATH": str(self.root / "bin") + os.pathsep + os.environ["PATH"]}
        with patch.dict(os.environ, env), patch.object(ci.platform, "system", return_value="Linux"), \
                patch.object(ci.platform, "machine", return_value="x86_64"), \
                patch.object(recorder, "parallel", side_effect=installed_commands), \
                patch.object(recorder, "command", wraps=recorder.command) as command:
            original = recorder.command
            def run(name, argv, **kwargs):
                if name == "verify-jam":
                    self.assertEqual([str(self.root / "gradlew"), "--no-daemon", "-q", "verifyJamToolchain"], argv)
                    self.assertEqual(str(java), kwargs["env"]["JAVA_HOME"])
                    return (0, "")
                return original._mock_wraps(name, argv, **kwargs)
            command.side_effect = run
            ci.setup_toolchain(recorder)
        self.assertIn("JAVA_HOME=" + str(java), (self.root / "env").read_text())
        self.assertIn(str(ghcup / "ghc/9.14.1/bin"), (self.root / "path").read_text())

    def jam_pin(self, transport=None, runtime=None):
        pin = {"schema": 1, "producer": {"repository": "ekmett/jam", "commit": "a" * 40},
               "platforms": {"Linux-x86_64": {
                   "transport": transport or {"kind": "github-release-asset",
                       "url": "https://github.com/ekmett/jam/releases/download/package/linux.tar.gz", "tarSha256": "b" * 64},
                   "runtime": runtime or {"installation": {"algorithm": "sha256-path-manifest-v1", "sha256": "a" * 64}}}}}
        path = self.root / "etc/jam-graalvm.json"
        path.parent.mkdir(exist_ok=True)
        path.write_text(json.dumps(pin))
        return pin

    def test_jam_verifier_selects_the_native_gradle_launcher(self):
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "setup")
        for system, launcher in (("Linux", "gradlew"), ("Darwin", "gradlew"), ("Windows", "gradlew.bat")):
            with self.subTest(system=system), patch.object(ci.platform, "system", return_value=system), \
                 patch.object(recorder, "command") as command:
                ci.verify_jam(recorder, self.root / "jdk")
                argv = command.call_args.args[1]
                self.assertEqual(str(self.root / launcher), argv[0])
                self.assertEqual("verifyJamToolchain", argv[-1])
                self.assertEqual(str(self.root / "jdk"), command.call_args.kwargs["env"]["JAVA_HOME"])

    def test_jam_release_identity_requires_the_exact_pin_and_verifier_receipt(self):
        java = self.root / "jdk"
        java.mkdir()
        release = java / "release"
        release.write_text('GRAALVM_VERSION="25.3.4.1-dev"\nJAVA_VERSION="25"\n')
        runtime = {"installation": {"algorithm": "sha256-path-manifest-v1", "sha256": "a" * 64},
                   "sha256": {"release": hashlib.sha256(release.read_bytes()).hexdigest()}}
        self.jam_pin(runtime=runtime)
        receipt = self.root / "build/toolchain/jam-verified.json"
        receipt.parent.mkdir(parents=True)
        verified = {"javaHome": str(java.resolve()), "installation": "a" * 64}
        receipt.write_text(json.dumps(verified))
        with patch.dict(os.environ, JAVA_HOME=str(java)), \
             patch.object(ci.platform, "system", return_value="Linux"), \
             patch.object(ci.platform, "machine", return_value="x86_64"):
            self.assertEqual(runtime["sha256"]["release"], ci.jam_release_identity(self.root))
            verified["installation"] = "b" * 64
            receipt.write_text(json.dumps(verified))
            with self.assertRaisesRegex(RuntimeError, "Run verifyJamToolchain"):
                ci.jam_release_identity(self.root)
            receipt.write_text(json.dumps(verified | {"installation": "a" * 64}))
            release.write_text('GRAALVM_VERSION="25.3.4.1"\nJAVA_VERSION="25"\n')
            with self.assertRaisesRegex(RuntimeError, "Pinned JAM release"):
                ci.jam_release_identity(self.root)

    def test_jam_identity_uses_exact_package_and_checks_platform_floor(self):
        pin = self.jam_pin()
        with patch.dict(os.environ, THC_TOOLS=str(self.root / "tools")), \
             patch.object(ci.platform, "system", return_value="Linux"), \
             patch.object(ci.platform, "machine", return_value="x86_64"), \
             patch.object(ci.platform, "libc_ver", return_value=("glibc", "2.38")):
            _, home, key = ci.jam_package(self.root)
            self.assertIn("installed-jam-Linux-x86_64-", key)
            (self.root / "cabal.project").write_text("irrelevant source change")
            self.assertEqual((home, key), ci.jam_package(self.root)[1:])
            pin["platforms"]["Linux-x86_64"]["runtime"]["minimumGlibc"] = "2.39"
            (self.root / "etc/jam-graalvm.json").write_text(json.dumps(pin))
            with self.assertRaisesRegex(RuntimeError, "requires glibc 2.39"):
                ci.jam_package(self.root)
            pin["platforms"]["Linux-x86_64"]["runtime"]["minimumGlibc"] = "2.38"
            pin["platforms"]["Linux-x86_64"]["transport"]["tarSha256"] = "c" * 64
            (self.root / "etc/jam-graalvm.json").write_text(json.dumps(pin))
            self.assertNotEqual(key, ci.jam_package(self.root)[2])
            pin["platforms"]["Darwin-arm64"] = pin["platforms"].pop("Linux-x86_64")
            pin["platforms"]["Darwin-arm64"]["runtime"].pop("minimumGlibc")
            pin["platforms"]["Darwin-arm64"]["runtime"]["minimumMacOS"] = "26.0.0"
            (self.root / "etc/jam-graalvm.json").write_text(json.dumps(pin))
            with patch.object(ci.platform, "system", return_value="Darwin"), \
                 patch.object(ci.platform, "machine", return_value="arm64"), \
                 patch.object(ci.platform, "mac_ver", return_value=("15.7", "", "")):
                with self.assertRaisesRegex(RuntimeError, "requires macOS 26"):
                    ci.jam_package(self.root)
                with patch.object(ci.platform, "mac_ver", return_value=("26.0", "", "")):
                    self.assertIn("Darwin-arm64", ci.jam_package(self.root)[2])

    def test_jam_acquisition_checks_archives_and_reuses_exact_cache(self):
        for zipped, corrupt in ((False, ""), (True, ""), (False, "tar"), (True, "tar"), (True, "zip")):
            with self.subTest(zipped=zipped, corrupt=corrupt), tempfile.TemporaryDirectory(dir=self.root) as directory:
                tools = Path(directory) / "tools"
                payload = Path(directory) / "package.tar.gz"
                with tarfile.open(payload, "w:gz") as tar:
                    for name, data in (("graalvm/release", b"jam"), ("upstream/graal25/sdk/mxbuild/dists/nativeimage.jar", b"SDK")):
                        item = tarfile.TarInfo(name)
                        item.size = len(data)
                        tar.addfile(item, io.BytesIO(data))
                transport = {"kind": "github-release-asset", "url": "https://github.com/ekmett/jam/releases/download/package/linux.tar.gz",
                             "tarSha256": hashlib.sha256(payload.read_bytes()).hexdigest()}
                source = payload
                if zipped:
                    source = Path(directory) / "package.zip"
                    with zipfile.ZipFile(source, "w") as outer:
                        outer.write(payload, "jam-graal-ci.tar.gz")
                    transport["zipSha256"] = hashlib.sha256(source.read_bytes()).hexdigest()
                if corrupt:
                    transport["zipSha256" if corrupt == "zip" else "tarSha256"] = "f" * 64
                self.jam_pin(transport)
                fake = Path(directory) / "bin/curl"
                fake.parent.mkdir()
                fake.write_text("#!" + sys.executable + "\nimport shutil,sys\nshutil.copyfile(" + repr(str(source)) + ", sys.argv[sys.argv.index('--output')+1])\n")
                fake.chmod(0o755)
                with patch.object(ci, "git", return_value="a" * 40):
                    recorder = ci.Recorder(self.root, Path(directory) / "report")
                with patch.dict(os.environ, THC_TOOLS=str(tools), GITHUB_ENV=str(self.root / "env"), GITHUB_PATH=str(self.root / "path"),
                                GITHUB_OUTPUT=str(Path(directory) / "outputs"),
                                PATH=str(fake.parent) + os.pathsep + os.environ["PATH"]), \
                     patch.object(ci.platform, "system", return_value="Linux"), \
                     patch.object(ci.platform, "machine", return_value="x86_64"):
                    if corrupt:
                        with self.assertRaisesRegex(RuntimeError, "archive digest differs"):
                            ci.install_jam(recorder)
                        self.assertFalse(ci.jam_package(self.root)[1].exists())
                    else:
                        home = ci.install_jam(recorder)
                        self.assertEqual(b"jam", (home / "release").read_bytes())
                        ci.jam_identity(self.root)
                        cache_root = Path(ci.action_outputs(Path(directory) / "outputs")["jam-root"])
                        self.assertEqual(home.parent, cache_root)
                        self.assertTrue(cache_root.name.startswith("jam-"))
                        cached = Path(directory) / "saved-cache"
                        ci.shutil.copytree(cache_root, cached)
                        ci.shutil.rmtree(cache_root)
                        ci.shutil.copytree(cached, cache_root)
                        self.assertEqual(b"SDK", (cache_root / "upstream/graal25/sdk/mxbuild/dists/nativeimage.jar").read_bytes())
                        fake.unlink()
                        self.assertEqual(home, ci.install_jam(recorder))
                        self.assertEqual(str(home), recorder.data["jamPackage"]["javaHome"])
                        self.assertGreater(recorder.data["phases"][0]["seconds"], 0)
                        self.assertEqual(0, recorder.data["phases"][0]["exitCode"])

    def test_jam_acquisition_refuses_escaping_tar_paths(self):
        payload = self.root / "unsafe.tar.gz"
        with tarfile.open(payload, "w:gz") as tar:
            item = tarfile.TarInfo("../../outside")
            item.size = 3
            tar.addfile(item, io.BytesIO(b"bad"))
        self.jam_pin({"kind": "github-release-asset", "url": "https://github.com/ekmett/jam/releases/download/package/linux.tar.gz",
                      "tarSha256": hashlib.sha256(payload.read_bytes()).hexdigest()})
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "setup")
        def download(name, argv):
            self.assertEqual("download-jam", name)
            ci.shutil.copyfile(payload, argv[-1])
        with patch.dict(os.environ, THC_TOOLS=str(self.root / "tools")), \
             patch.object(ci.platform, "system", return_value="Linux"), \
             patch.object(ci.platform, "machine", return_value="x86_64"), \
             patch.object(recorder, "command", side_effect=download):
            with self.assertRaises(tarfile.OutsideDestinationError):
                ci.install_jam(recorder)
            self.assertFalse(ci.jam_package(self.root)[1].exists())
            self.assertFalse((self.root / "outside").exists())

    def test_jam_acquisition_refuses_expired_and_unauthenticated_actions_artifacts(self):
        self.jam_pin({"kind": "github-actions-artifact", "url": "https://api.github.com/repos/ekmett/jam/actions/artifacts/1/zip",
                      "expiresAt": "2000-01-01T00:00:00Z", "tarSha256": "a" * 64, "zipSha256": "b" * 64})
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "setup")
        with patch.dict(os.environ, THC_TOOLS=str(self.root / "tools"), GH_TOKEN=""), \
             patch.object(ci.platform, "system", return_value="Linux"), \
             patch.object(ci.platform, "machine", return_value="x86_64"):
            with self.assertRaisesRegex(RuntimeError, "require GH_TOKEN"):
                ci.install_jam(recorder)
            with patch.dict(os.environ, GH_TOKEN="private"):
                with self.assertRaisesRegex(RuntimeError, "has expired"):
                    ci.install_jam(recorder)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def suite(self, name="example.Test", body=None, **attrs):
        attrs = dict(tests="1", failures="0", errors="0", skipped="0", **attrs)
        attributes = " ".join(f'{key}="{value}"' for key, value in attrs.items())
        body = body if body is not None else f'<testcase name="works" classname="{name}"/>'
        (self.root / ("TEST-" + name + ".xml")).write_text(
            f'<testsuite name="{name}" {attributes}>{body}</testsuite>')

    def selection(self, mode="narrow"):
        return {"mode": mode, "runnable": True, "cadence": "commit", "haskell": {"suites": [], "count": 0},
                "polyglot": {"required": False, "classes": []}, "junit": {
            "classes": ["example.Test"], "patterns": ["*"] if mode == "full" else ["example.Test"]}}

    def test_fast_requests_commit_cadence_and_reports_deferred_coverage(self):
        selection = self.selection() | {"reasons": [{"code": "unmapped-source-or-configuration"}],
            "requestedMode": "full", "deferred": {"hourly": {"junit": ["example.OtherTest"]},
            "nightly": {"junit": ["example.SlowTest"]}}, "python": {"commands": []}}
        identity = self.root / "identity.json"
        identity.write_text(json.dumps({"platform": "linux", "toolchain": {}}))
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        with patch.object(recorder, "command", side_effect=[(0, json.dumps(selection)), (0, "")]) as command, \
                patch.object(ci.fixtures, "prepare_cmake", return_value={"mode": "selected"}), \
                patch.object(ci, "run_modes", return_value=({}, [])) as modes:
            ci.execute(recorder, "HEAD", "HEAD", identity)
        self.assertEqual(command.call_args_list[0].args[1][-2:], ["--cadence", "commit"])
        self.assertEqual(recorder.data["selection"]["requestedMode"], "full")
        self.assertEqual(recorder.data["selection"]["mode"], "narrow")
        self.assertEqual(recorder.data["selection"]["deferred"], selection["deferred"])
        modes.assert_called_once_with(recorder, selection, install_dist=False)

    def test_fast_rejects_unscoped_selection_before_preparation(self):
        selection = self.selection()
        del selection["cadence"]
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        with patch.object(recorder, "command", return_value=(0, json.dumps(selection))), \
                patch.object(ci.fixtures, "prepare_cmake") as prepare, self.assertRaisesRegex(RuntimeError, "commit-cadence"):
            ci.execute(recorder, "HEAD", "HEAD", self.root / "missing-identity.json")
        prepare.assert_not_called()

    def test_group_execution_collects_both_modes_without_fail_fast(self):
        for reuse in (False, True):
            with self.subTest(reuse=reuse):
                command = ci.gradle_command(self.selection(), fail_fast=False, reuse_daemon=reuse)
                self.assertIn("--daemon" if reuse else "--no-daemon", command)
                self.assertNotIn("--no-daemon" if reuse else "--daemon", command)
                self.assertIn("testDefault", command)
                self.assertIn("testDense", command)
                self.assertEqual(2, command.count("--rerun"))
                self.assertNotIn("--fail-fast", command)

    def test_batch_workflow_continues_after_preparation_and_runtime_failures(self):
        workflow = (Path(__file__).resolve().parents[1] / "workflows/test-groups.yml").read_text()
        step = workflow.split("      - name: Run each original group with its own preparation and both handoff modes\n", 1)[1]
        script = textwrap.dedent(step.split("      - name:", 1)[0].split("        run: |\n", 1)[1])
        commands = self.root / "commands"
        commands.mkdir()
        runner = commands / "python3"
        runner.write_text(f"#!{sys.executable}\n" + textwrap.dedent('''\
            import pathlib, sys
            args = sys.argv[1:]
            assert args[:2] == [".github/scripts/fast_ci.py", "group"]
            assert "--reuse-daemon" in args
            group = args[args.index("--group") + 1]
            report = pathlib.Path(args[args.index("--report-dir") + 1])
            report.mkdir(parents=True)
            (report / "preparation.log").write_text(group)
            with pathlib.Path("executed").open("a") as stream:
                stream.write(group + "\\n")
            if group == "bad-preparation":
                sys.exit(11)
            for mode in ("default", "dense"):
                (report / mode).mkdir()
                (report / mode / "result.xml").write_text(group)
            sys.exit(12 if group == "bad-runtime" else 0)
            '''))
        runner.chmod(0o755)
        gradle = self.root / "gradlew"
        gradle.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> daemon-commands\n')
        gradle.chmod(0o755)
        for groups, expected in ((["first", "bad-preparation", "bad-runtime", "last"], 1),
                                 (["independent"], 0)):
            with self.subTest(groups=groups):
                (self.root / "executed").unlink(missing_ok=True)
                (self.root / "daemon-commands").unlink(missing_ok=True)
                summary = self.root / "summary"
                summary.write_text("")
                result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", script], cwd=self.root,
                    env=dict(os.environ, PATH=str(commands) + os.pathsep + os.environ["PATH"],
                             CI_GROUPS=" ".join(groups), CI_CADENCE="commit", GITHUB_STEP_SUMMARY=str(summary)),
                    text=True, capture_output=True)
                self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
                self.assertEqual(groups, (self.root / "executed").read_text().splitlines())
                self.assertEqual(["--stop", "--stop"], (self.root / "daemon-commands").read_text().splitlines())
                for group in groups:
                    report = self.root / "build/ci/group-results" / group
                    self.assertEqual(group, (report / "preparation.log").read_text())
                    failed = group.startswith("bad-")
                    self.assertIn(f"- {group}: {'failed' if failed else 'passed'}", summary.read_text())
                    if failed:
                        self.assertIn(f"::error title={group}::", result.stdout)
                    for mode in ("default", "dense"):
                        output = report / mode / "result.xml"
                        if group == "bad-preparation":
                            self.assertFalse(output.exists())
                        else:
                            self.assertEqual(group, output.read_text())

    def test_haskell_suite_is_selected_exactly(self):
        self.assertEqual([], ci.haskell_suites(self.selection()))
        self.assertEqual(["driver-tests"], ci.haskell_suites(self.selection() |
                         {"haskell": {"suites": ["driver-tests"], "count": 1}}))
        self.assertEqual(["primop-tools"], ci.haskell_suites(self.selection() |
                         {"haskell": {"suites": ["primop-tools"], "count": 1}}))
        self.assertEqual(["compact-core-tests"], ci.haskell_suites(self.selection() |
                         {"haskell": {"suites": ["compact-core-tests"], "count": 1}}))
        with self.assertRaisesRegex(RuntimeError, "Haskell"):
            ci.haskell_suites(self.selection() | {"haskell": {"suites": ["primop-tools", "primop-tools"], "count": 2}})
        with self.assertRaisesRegex(RuntimeError, "Haskell"):
            ci.haskell_suites(self.selection() | {"haskell": {"suites": ["other"], "count": 1}})

    def test_gradle_cache_identity_covers_groovy_and_java_build_logic_not_outputs(self):
        names = ("build.gradle", "settings.gradle", "gradle.properties", "gradlew",
                 "nih/gradle/wrapper/gradle-wrapper.jar", "nih/gradle/wrapper/gradle-wrapper.properties",
                 ".github/scripts/fast_ci.init.gradle", "src/gradle/bytecode-metadata.gradle",
                 "src/build/build.gradle", "src/build/settings.gradle", "src/build/java/thc/buildlogic/Normalizer.java")
        for name in names:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(name)
        java = self.root / "jdk"
        java.mkdir()
        (java / "release").write_text('GRAALVM_VERSION="25.3.4.1"\nJAVA_VERSION="25"\n')
        pin = self.jam_pin(runtime={"installation": {"algorithm": "sha256-path-manifest-v1", "sha256": "a" * 64},
                                   "sha256": {"release": hashlib.sha256((java / "release").read_bytes()).hexdigest()}})
        host = ci.platform.system() + "-" + {"aarch64": "arm64"}.get(ci.platform.machine(), ci.platform.machine())
        pin["platforms"][host] = pin["platforms"].pop("Linux-x86_64")
        (self.root / "etc/jam-graalvm.json").write_text(json.dumps(pin))
        receipt = self.root / "build/toolchain/jam-verified.json"
        receipt.parent.mkdir(parents=True)
        receipt.write_text(json.dumps({"javaHome": str(java.resolve()), "installation": "a" * 64}))
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        with patch.dict(os.environ, {"JAVA_HOME": str(java), "GITHUB_OUTPUT": ""}), \
                patch.object(recorder, "command", return_value=(0, "thc-fast-inputs-v1-" + "b" * 64)):
            def key():
                ci.identify(recorder, self.root / "identity.json")
                return recorder.data["gradlePrefix"]
            original = key()
            for name in names:
                path = self.root / name
                before = path.read_text()
                path.write_text(before + " changed")
                self.assertNotEqual(original, key(), name)
                path.write_text(before)
                self.assertEqual(original, key(), name)
            for name in ("src/build/build/generated/Ignore.java", "src/build/.gradle/Ignore.gradle"):
                path = self.root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("not source")
                self.assertEqual(original, key(), name)
            extra = self.root / "src/build/java/thc/buildlogic/Added.java"
            extra.write_text("new source")
            self.assertNotEqual(original, key())
            extra.unlink()
            self.assertEqual(original, key())

    def test_wrapper_launchers_and_regeneration_use_relocated_jar(self):
        root = Path(__file__).resolve().parents[2]
        jar = "nih/gradle/wrapper/gradle-wrapper.jar"
        self.assertTrue((root / jar).is_file())
        self.assertTrue((root / jar).with_suffix(".properties").is_file())
        self.assertFalse((root / "gradle").exists())
        self.assertIn(f'-jar "$APP_HOME/{jar}"', (root / "gradlew").read_text())
        self.assertIn('-jar "%APP_HOME%\\nih\\gradle\\wrapper\\gradle-wrapper.jar"',
                      (root / "gradlew.bat").read_text())
        self.assertIn(f"jarFile = file('{jar}')", (root / "build.gradle").read_text())

    def test_exact_fresh_suite_and_cases(self):
        self.suite()
        result = ci.validate_xml(self.root, ["example.Test"])
        self.assertEqual(result["tests"], 1)
        self.assertEqual(result["cases"], [["example.Test", "works"]])

    def test_haskell_compile_targets_cannot_select_production_or_inject_options(self):
        self.assertEqual([], ci.haskell_compile_targets(self.selection()))
        for targets in (["exe:thc"], ["--enable-tests"], ["test:driver-tests"], ["test:x-full-core"] * 2, "test:x-full-core"):
            with self.subTest(targets=targets), self.assertRaisesRegex(RuntimeError, "Haskell"):
                ci.haskell_compile_targets(self.selection() | {"haskell": {"compileTargets": targets}})

    def test_missing_xml_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "No fresh"):
            ci.validate_xml(self.root, ["example.Test"])

    def test_missing_or_extra_class_rejected(self):
        self.suite()
        for expected in (["other.Test"], ["example.Test", "other.Test"]):
            with self.subTest(expected=expected), self.assertRaisesRegex(RuntimeError, "class mismatch"):
                ci.validate_xml(self.root, expected)

    def test_failure_element_rejected_even_if_counter_lies(self):
        self.suite(body='<testcase name="bad" classname="example.Test"><failure/></testcase>')
        with self.assertRaisesRegex(RuntimeError, "Unsuccessful"):
            ci.validate_xml(self.root, ["example.Test"])

    def test_skipped_error_and_failure_counters_rejected(self):
        for field in ("skipped", "errors", "failures"):
            with self.subTest(field=field):
                path = self.root / "TEST-example.Test.xml"
                self.suite()
                path.write_text(path.read_text().replace(f'{field}="0"', f'{field}="1"'))
                with self.assertRaisesRegex(RuntimeError, "failures/errors/skips"):
                    ci.validate_xml(self.root, ["example.Test"])

    def test_empty_or_inconsistent_suite_rejected(self):
        self.suite(body="")
        with self.assertRaisesRegex(RuntimeError, "Empty/inconsistent"):
            ci.validate_xml(self.root, ["example.Test"])

    def test_linux_records_disabled_windows_suite_without_counting_it_as_executed(self):
        self.suite()
        windows = "thc.WindowsDistributionTest"
        self.suite(windows, body=f'<testcase name="windows" classname="{windows}"><skipped/></testcase>')
        path = self.root / f"TEST-{windows}.xml"
        path.write_text(path.read_text().replace('skipped="0"', 'skipped="1"'))
        with patch.object(ci.sys, "platform", "linux"):
            result = ci.validate_xml(self.root, ["example.Test", windows])
        self.assertEqual(result["cases"], [["example.Test", "works"]])
        self.assertEqual(result["platformSkippedCases"], [[windows, "windows"]])
        self.assertEqual(result["skipped"], 1)
        for platform in ("win32", "darwin"):
            with self.subTest(platform=platform), patch.object(ci.sys, "platform", platform):
                with self.assertRaisesRegex(RuntimeError, "Unsuccessful"):
                    ci.validate_xml(self.root, ["example.Test", windows])
        (self.root / "TEST-example.Test.xml").unlink()
        with patch.object(ci.sys, "platform", "linux"):
            with self.assertRaisesRegex(RuntimeError, "No executed"):
                ci.validate_xml(self.root, [windows])

    def test_linux_platform_exception_rejects_partial_unknown_and_failed_suites(self):
        windows = "thc.WindowsDistributionTest"
        for name, body in (
                ("other.Test", '<skipped/>'),
                (windows, '<skipped/><failure/>'),
                (windows, '<skipped/><error/>')):
            with self.subTest(name=name, body=body), patch.object(ci.sys, "platform", "linux"):
                self.suite(name, body=f'<testcase name="bad" classname="{name}">{body}</testcase>')
                with self.assertRaisesRegex(RuntimeError, "Unsuccessful"):
                    ci.validate_xml(self.root, [name])
                (self.root / f"TEST-{name}.xml").unlink()
        self.suite(windows, body=f'<testcase name="skipped" classname="{windows}"><skipped/></testcase>'
                   f'<testcase name="ran" classname="{windows}"/>')
        path = self.root / f"TEST-{windows}.xml"
        path.write_text(path.read_text().replace('tests="1"', 'tests="2"').replace('skipped="0"', 'skipped="1"'))
        with patch.object(ci.sys, "platform", "linux"):
            with self.assertRaisesRegex(RuntimeError, "Unsuccessful"):
                ci.validate_xml(self.root, [windows])

    def test_native_windows_suites_are_platform_skips_only_on_linux(self):
        self.suite()
        for windows in ("thc.runtime.WindowsDirectoryStreamsTest", "thc.runtime.WindowsCodePagesTest",
                        "thc.runtime.WindowsAbiInitializationTest"):
            with self.subTest(suite=windows):
                self.suite(windows, body=f'<testcase name="native" classname="{windows}"><skipped/></testcase>')
                path = self.root / f"TEST-{windows}.xml"
                path.write_text(path.read_text().replace('skipped="0"', 'skipped="1"'))
                with patch.object(ci.sys, "platform", "linux"):
                    result = ci.validate_xml(self.root, ["example.Test", windows])
                self.assertEqual(result["cases"], [["example.Test", "works"]])
                self.assertEqual(result["platformSkippedCases"], [[windows, "native"]])
                with patch.object(ci.sys, "platform", "win32"):
                    with self.assertRaisesRegex(RuntimeError, "Unsuccessful"):
                        ci.validate_xml(self.root, ["example.Test", windows])
                path.unlink()

    def test_wrong_testcase_class_rejected(self):
        self.suite(body='<testcase name="works" classname="other.Test"/>')
        with self.assertRaisesRegex(RuntimeError, "Mismatched"):
            ci.validate_xml(self.root, ["example.Test"])

    def test_rerun_is_task_scoped_and_full_has_no_filters(self):
        narrow = ci.gradle_command(self.selection())
        self.assertIn("--build-cache", narrow)
        self.assertIn("--rerun", narrow)
        self.assertIn("--fail-fast", narrow)
        self.assertIn(".github/scripts/fast_ci.init.gradle", narrow)
        self.assertNotIn("--rerun-tasks", narrow)
        self.assertIn("--continue", narrow)
        self.assertEqual(narrow[narrow.index("testDefault"):],
                         ["testDefault", "--rerun", "--fail-fast", "--tests", "example.Test",
                          "testDense", "--rerun", "--fail-fast", "--tests", "example.Test"])
        self.assertNotIn("installDist", narrow)
        installed = ci.gradle_command(self.selection(), install_dist=True)
        self.assertLess(installed.index("installDist"), installed.index("testDefault"))
        full = ci.gradle_command(self.selection("full"))
        self.assertIn("--fail-fast", full)
        self.assertNotIn("--tests", full)
        init = Path(__file__).with_name("fast_ci.init.gradle").read_text()
        self.assertIn("tasks.withType(org.gradle.api.tasks.testing.Test)", init)
        self.assertIn("outputs.doNotCacheIf", init)

    def test_gradle_invocations_do_not_reuse_external_daemon_state(self):
        selected = self.selection() | {"polyglot": {"required": True, "classes": ["example.PolyglotTest"]}}
        commands = [ci.gradle_command(self.selection()), ci.gradle_command(self.selection("full")),
                    ci.gradle_command(self.selection(), install_dist=True), ci.polyglot_command(selected)]
        for command in commands:
            with self.subTest(command=command):
                self.assertIn("--no-daemon", command)
                self.assertNotIn("--daemon", command)
                self.assertIn("--build-cache", command)

    def test_polyglot_task_is_optional_and_reruns_its_exact_inventory(self):
        self.assertIsNone(ci.polyglot_command(self.selection()))
        selected = self.selection() | {"polyglot": {"required": True, "classes": ["example.PolyglotTest"]}}
        command = ci.polyglot_command(selected)
        self.assertEqual("./gradlew", command[0])
        self.assertIn("polyglotTest", command)
        self.assertIn("--rerun", command)
        self.assertNotIn("--fail-fast", command)
        self.assertNotIn("--tests", command)
        for malformed in ({"required": True, "classes": []},
                          {"required": False, "classes": ["example.PolyglotTest"]},
                          {"required": True, "classes": ["example.PolyglotTest", "example.PolyglotTest"]}):
            with self.subTest(malformed=malformed), self.assertRaises(RuntimeError):
                ci.polyglot_command(self.selection() | {"polyglot": malformed})

    def test_polyglot_task_cannot_reuse_previous_xml(self):
        selected = self.selection() | {"polyglot": {"required": True, "classes": ["example.PolyglotTest"]}}
        old = self.root / "build/test-results/polyglotTest/TEST-stale.xml"
        old.parent.mkdir(parents=True)
        old.write_text("old")
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        with patch.object(recorder, "command", return_value=(0, "")):
            with self.assertRaisesRegex(RuntimeError, "No fresh JUnit XML"):
                ci.run_polyglot(recorder, selected)
        self.assertEqual((recorder.directory / "prior-polyglot/xml/TEST-stale.xml").read_text(), "old")

        def fresh(_, __, **___):
            output = self.root / "build/test-results/polyglotTest/TEST-example.PolyglotTest.xml"
            output.parent.mkdir(parents=True)
            output.write_text('<testsuite name="example.PolyglotTest" tests="1" failures="0" '
                              'errors="0" skipped="0"><testcase name="works" '
                              'classname="example.PolyglotTest"/></testsuite>')
            return (0, "")

        with patch.object(recorder, "command", side_effect=fresh):
            summary = ci.run_polyglot(recorder, selected)
        self.assertEqual(summary["classes"], ["example.PolyglotTest"])
        self.assertEqual(json.loads((recorder.directory / "polyglot/summary.json").read_text())["tests"], 1)

    def test_narrow_selection_allows_exact_methods_but_not_wildcards_or_unowned_classes(self):
        selection = self.selection()
        selection["runnable"] = False
        with self.assertRaises(RuntimeError):
            ci.gradle_command(selection)
        selection["runnable"] = True
        selection["junit"]["patterns"] = ["example.Test.oneMethod"]
        self.assertEqual(2, ci.gradle_command(selection).count("example.Test.oneMethod"))
        for patterns in ([], ["example.Test.*"], ["example.Other.oneMethod"],
                         ["example.Test.oneMethod", "example.Test.oneMethod"],
                         ["example.Test", "example.Test.oneMethod"]):
            with self.subTest(patterns=patterns), self.assertRaises(RuntimeError):
                ci.gradle_command(selection | {"junit": {"classes": ["example.Test"], "patterns": patterns}})

    def test_partial_report_must_contain_exactly_the_selected_methods(self):
        self.suite(body='<testcase name="works()" classname="example.Test"/>')
        ci.validate_xml(self.root, ["example.Test"], ["example.Test.works"])
        for patterns in (["example.Test.missing"], ["example.Test.works", "example.Test.missing"]):
            with self.subTest(patterns=patterns), self.assertRaisesRegex(RuntimeError, "method mismatch"):
                ci.validate_xml(self.root, ["example.Test"], patterns)
        with self.assertRaisesRegex(RuntimeError, "method mismatch"):
            ci.validate_methods([("example.Test", "works()"), ("example.Test", "unexpected()")],
                                ["example.Test"], ["example.Test.works"])

    def test_python_runs_normal_and_optimized_without_shell(self):
        selected = {"python": {"commands": [["python3", path] for path in (
            "odd name/test_me.py", "bin/test-javascript-ffi.py")]}}
        self.assertEqual(list(ci.python_commands(selected, "/python")),
                         [["/python", "odd name/test_me.py"], ["/python", "-O", "odd name/test_me.py"],
                          ["/python", "-O", "bin/test-javascript-ffi.py"]])
        with self.assertRaises(RuntimeError):
            list(ci.python_commands({"python": {"commands": [["bash", "-c", "exit 0"]]}}, "/python"))

    def test_python_auditors_build_declared_inputs_once_before_consuming_them(self):
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipt")
        selection = {"python": {"commands": [["python3", path] for path in (
            "bin/test-audit-core.py", "bin/test-core-package-manifest.py", "bin/test-independent.py")]}}
        built, checked = [], []
        def command(name, argv, *, env=None):
            if "--build" in argv:
                self.assertEqual(["fixture-tools", "prepare-foreign-ownership"], argv[argv.index("--target") + 1:])
                built.append(name)
                output = self.root / "build"
                output.mkdir()
                for tool in ("thc-fixtures", "thc-compact"):
                    executable = output / tool
                    executable.write_text("#!/bin/sh\nexit 0\n")
                    executable.chmod(0o755)
                    (output / (tool + ".path")).write_text(str(executable) + "\n")
                (output / "foreign-ownership.json").write_text("{}\n")
            elif name.startswith("python-"):
                checked.append(argv[-1])
                if argv[-1] == "bin/test-independent.py":
                    self.assertIsNone(env)
                else:
                    self.assertEqual(1, len(built))
                    for variable, filename in (("THC_FIXTURES", "thc-fixtures"),
                                               ("THC_COMPACT", "thc-compact"),
                                               ("THC_FOREIGN_OWNERSHIP", "foreign-ownership.json")):
                        self.assertEqual(str(self.root / "build" / filename), env[variable])
        with patch.object(recorder, "command", side_effect=command):
            self.assertEqual([], ci.run_python_checks(recorder, selection, False))
        self.assertEqual(6, len(checked))
        self.assertEqual(1, len(built))

    def test_python_auditor_failure_does_not_retry_or_block_independent_checks(self):
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipt")
        selected = {"python": {"commands": [["python3", path] for path in (
            "bin/test-audit-core.py", "bin/test-core-package-manifest.py", "bin/test-independent.py")]}}
        for failure in (RuntimeError("producer failed"), FileNotFoundError("missing output")):
            with self.subTest(failure=failure), \
                 patch.object(ci, "python_auditor_environment", side_effect=failure) as prepare, \
                 patch.object(recorder, "command") as command:
                failures = ci.run_python_checks(recorder, selected, False)
                self.assertEqual(1, len(failures))
                self.assertIn(str(failure), failures[0])
                prepare.assert_called_once_with(recorder)
                self.assertEqual(["bin/test-independent.py"] * 2,
                                 [call.args[1][-1] for call in command.call_args_list])
                prepare.reset_mock()
                self.assertEqual([], ci.run_python_checks(recorder, {
                    "python": {"commands": [["python3", "bin/test-independent.py"]]}}, False))
                prepare.assert_not_called()

    def test_matching_automation_job_reuses_only_its_complete_test_files(self):
        selected = {"python": {"commands": [["python3", path] for path in (
            ".github/scripts/test_fast_select.py", "bin/test-audit-core.py",
            ".github/scripts/extra/test_nested.py")]}}
        all_commands = list(ci.python_commands(selected, "/python"))
        reused = list(ci.python_commands(selected, "/python", automation_checked=True))
        self.assertEqual(reused, all_commands[2:])

    def test_previous_outputs_are_preserved_and_cannot_count(self):
        source = self.root / "build/test-results/test"
        source.mkdir(parents=True)
        (source / "TEST-stale.xml").write_text("old")
        destination = self.root / "build/fast/prior"
        ci.preserve_previous(self.root, destination)
        self.assertFalse(source.exists())
        self.assertEqual((destination / "xml/TEST-stale.xml").read_text(), "old")
        with self.assertRaises(RuntimeError):
            ci.validate_xml(source, ["example.Test"])

    def test_failed_gradle_preserves_partial_xml_and_reports_its_exit(self):
        class FailedRun:
            root = self.root
            directory = self.root / "receipts"

            def command(self, name, argv, **kwargs):
                output = self.root / "build/test-results/testDefault"
                output.mkdir(parents=True)
                (output / "TEST-example.Test.xml").write_text(
                    '<testsuite name="example.Test" tests="1" failures="1" errors="0" skipped="0">'
                    '<testcase name="fails" classname="example.Test"><failure/></testcase></testsuite>')
                return 1, ""

        selection = self.selection() | {"junit": {"classes": ["example.Test", "later.Test"],
                                                  "patterns": ["example.Test", "later.Test"]}}
        summaries, failures = ci.run_modes(FailedRun(), selection)
        self.assertEqual(summaries, {})
        self.assertIn("Gradle handoff batch failed with exit 1", failures)
        self.assertTrue(any("dense: No fresh JUnit XML" in failure for failure in failures))
        self.assertTrue((self.root / "receipts/default/xml/TEST-example.Test.xml").exists())

    def batch_selection(self):
        classes = ["thc.runtime.HandoffTest"]
        return self.selection() | {"junit": {"classes": classes, "patterns": classes}}

    def mode_xml(self, task, dense, *, failed=False, case="proof"):
        output = self.root / "build/test-results" / task
        output.mkdir(parents=True, exist_ok=True)
        marker = "true" if dense else "false"
        (output / "TEST-thc.runtime.HandoffTest.xml").write_text(
            f'<testsuite name="thc.runtime.HandoffTest" tests="1" failures="{int(failed)}" '
            f'errors="0" skipped="0"><testcase name="{case}" classname="thc.runtime.HandoffTest">'
            + ('<failure/>' if failed else '') + '</testcase>'
            f'<system-out>THC_HANDOFF_MODE={marker}\n</system-out></testsuite>')

    def test_group_requires_full_selected_classes_but_accepts_skips(self):
        import fast_select
        selection = self.batch_selection()
        selection['junit']['classes'].append('example.SelectedTest')
        selection['junit']['patterns'] = list(selection['junit']['classes'])
        for present in (False, True, "empty"):
            with self.subTest(present=present), patch.object(ci, 'git', return_value='a' * 40):
                recorder = ci.Recorder(self.root, self.root / str(present))

                def fresh(*args, **kwargs):
                    for dense, task in ((False, 'testDefault'), (True, 'testDense')):
                        self.mode_xml(task, dense)
                        if present:
                            (self.root / 'build/test-results' / task / 'TEST-example.SelectedTest.xml').write_text(
                                '<testsuite name="example.SelectedTest" tests="0" failures="0" errors="0" skipped="0"/>'
                                if present == "empty" else
                                '<testsuite name="example.SelectedTest" tests="1" failures="0" errors="0" skipped="1">'
                                '<testcase name="platformExcluded" classname="example.SelectedTest"><skipped/></testcase></testsuite>')
                    return 0, ''

                with patch.object(fast_select, 'group_selection', return_value=selection) as choose, \
                     patch.object(ci.fixtures, '_manifest', return_value=({'groups': {}},
                         {name: None for name in selection['junit']['classes']})), \
                     patch.object(recorder, 'command', side_effect=fresh):
                    if present is True:
                        ci.run_group(recorder, 'selected', cadence='hourly', prepared=True, exact_class='example.SelectedTest')
                        self.assertTrue(recorder.data['passed'])
                    else:
                        with self.assertRaisesRegex(RuntimeError, 'Empty/inconsistent grouped JUnit suite'
                                                   if present == 'empty' else 'Grouped JUnit class mismatch'):
                            ci.run_group(recorder, 'selected', cadence='hourly', prepared=True, exact_class='example.SelectedTest')
                    choose.assert_called_once_with(self.root, 'selected', cadence='hourly',
                                                   exact_class='example.SelectedTest')
                    for mode in ('default', 'dense'):
                        self.assertTrue((recorder.directory / mode / 'xml/TEST-thc.runtime.HandoffTest.xml').exists())

    def test_batch_runs_once_and_preserves_both_actual_mode_outputs(self):
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        self.mode_xml("testDefault", False, case="stale-default")
        self.mode_xml("testDense", True, case="stale-dense")

        def fresh(*args, **kwargs):
            self.mode_xml("testDefault", False)
            self.mode_xml("testDense", True)
            return 0, ""

        with patch.object(recorder, "command", side_effect=fresh) as command:
            summaries, failures = ci.run_modes(recorder, self.batch_selection(), install_dist=True)
        command.assert_called_once()
        self.assertIn("installDist", command.call_args.args[1])
        self.assertEqual(failures, [])
        self.assertEqual({mode: data["handoffSlabs"] for mode, data in summaries.items()},
                         {"default": False, "dense": True})
        for mode in ("default", "dense"):
            proof = recorder.directory / f"prior-{mode}/xml/TEST-thc.runtime.HandoffTest.xml"
            self.assertIn("stale-" + mode, proof.read_text())
            self.assertTrue((recorder.directory / mode / "summary.json").is_file())

    def test_batch_cannot_reuse_previous_xml_or_fake_dense_with_default(self):
        for fresh in (False, True):
            with self.subTest(fresh=fresh), patch.object(ci, "git", return_value="a" * 40):
                recorder = ci.Recorder(self.root, self.root / str(fresh))
                self.mode_xml("testDefault", False)
                self.mode_xml("testDense", True)

                def run(*args, **kwargs):
                    if fresh:
                        self.mode_xml("testDefault", False)
                        self.mode_xml("testDense", False)
                    return 0, ""

                with patch.object(recorder, "command", side_effect=run):
                    summaries, failures = ci.run_modes(recorder, self.batch_selection())
                self.assertNotIn("dense", summaries)
                self.assertTrue(any("dense: " + ("Wrong/missing" if fresh else "No fresh") in failure
                                    for failure in failures))

    def test_batch_checks_other_mode_after_failure_and_requires_same_cases(self):
        for failed in (False, True):
            with self.subTest(failed=failed), patch.object(ci, "git", return_value="a" * 40):
                recorder = ci.Recorder(self.root, self.root / str(failed))

                def run(*args, **kwargs):
                    self.mode_xml("testDefault", False, failed=failed)
                    self.mode_xml("testDense", True, case="different")
                    return int(failed), ""

                with patch.object(recorder, "command", side_effect=run):
                    summaries, failures = ci.run_modes(recorder, self.batch_selection())
                self.assertIn("dense", summaries)
                self.assertTrue(failures)
                if failed:
                    self.assertNotIn("default", summaries)
                    self.assertIn("Gradle handoff batch failed with exit 1", failures)
                else:
                    self.assertIn("Default and dense handoff executed different testcase sets", failures)

    def test_batch_requires_matching_platform_disabled_cases(self):
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        windows = "thc.WindowsDistributionTest"
        classes = ["thc.runtime.HandoffTest", windows]
        selection = self.batch_selection() | {"junit": {"classes": classes, "patterns": classes}}

        def fresh(*args, **kwargs):
            for task, dense in (("testDefault", False), ("testDense", True)):
                self.mode_xml(task, dense)
                output = self.root / "build/test-results" / task / f"TEST-{windows}.xml"
                output.write_text(f'<testsuite name="{windows}" tests="1" skipped="1">'
                                  f'<testcase name="case-{dense}" classname="{windows}">'
                                  '<skipped/></testcase></testsuite>')
            return 0, ""

        with patch.object(ci.sys, "platform", "linux"), patch.object(recorder, "command", side_effect=fresh):
            summaries, failures = ci.run_modes(recorder, selection)
        self.assertEqual(len(summaries), 2)
        self.assertIn("Default and dense handoff executed different testcase sets", failures)

    def test_linked_test_output_rejected(self):
        source = self.root / "build/test-results/test"
        source.parent.mkdir(parents=True)
        source.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(RuntimeError):
            ci.preserve_previous(self.root, self.root / "receipts")

    def test_only_successful_current_main_can_publish(self):
        head = "a" * 40
        env = {"GITHUB_REF": "refs/heads/main", "GITHUB_EVENT_NAME": "push", "GITHUB_SHA": head}
        with patch.object(ci, "git", side_effect=[head, head + "\trefs/heads/main"]):
            self.assertTrue(ci.publication_allowed(self.root, env))
        for changed in ({"GITHUB_EVENT_NAME": "pull_request"}, {"GITHUB_REF": "refs/heads/feature"},
                        {"GITHUB_EVENT_NAME": "workflow_dispatch"}, {"GITHUB_SHA": "b" * 40}):
            with self.subTest(changed=changed), patch.object(ci, "git", return_value=head):
                self.assertFalse(ci.publication_allowed(self.root, env | changed))
        dispatch = env | {"GITHUB_EVENT_NAME": "workflow_dispatch", "EXPECTED_SHA": head}
        with patch.object(ci, "git", side_effect=[head, head + "\trefs/heads/main"]):
            self.assertTrue(ci.publication_allowed(self.root, dispatch))
        with patch.object(ci, "git", side_effect=[head, "b" * 40 + "\trefs/heads/main"]):
            self.assertFalse(ci.publication_allowed(self.root, dispatch))

    def test_workflow_does_not_restore_test_status_or_write_pr_caches(self):
        workflow = Path(__file__).parents[1] / "workflows/fast.yml"
        text = workflow.read_text()
        self.assertNotIn("pull_request_target", text)
        self.assertNotIn("native-inputs.tar.gz", text)
        self.assertIn("name: Fast checks", text)
        self.assertIn("  fast-check:", text)

    def test_quarantined_foreign_exceptions_cannot_run_through_make_or_ci(self):
        root = Path(__file__).parents[2]
        gradle = (root / "build.gradle").read_text()
        dedicated = gradle.split('def foreignExceptionTests =', 1)[1].split('foreignExceptionTests[1].configure', 1)[0]
        self.assertIn('fullCoreTests.output.classesDirs + polyglotTests.output.classesDirs + sourceSets.test.output.classesDirs', dedicated)
        self.assertIn('fullCoreTests.runtimeClasspath + polyglotTests.runtimeClasspath + polyglotDemoRuntime', dedicated)
        self.assertIn('includeTags("foreign-exceptions-full-core")', dedicated)
        self.assertTrue((root / "src/fullCoreTest/java/thc/runtime/ForeignExceptionTest.java").is_file())
        self.assertFalse((root / "src/polyglotTest/java/thc/runtime/ForeignExceptionTest.java").exists())
        makefile = (root / "Makefile").read_text()
        self.assertIn('foreign-exception-test-modes: foreign-exception-fixtures', makefile)
        guard = makefile.split('foreign-exception-fixtures:\n', 1)[1].split('foreign-exception-test-modes:', 1)[0]
        self.assertIn('preparation is quarantined', guard)
        self.assertIn('@exit 2', guard)
        self.assertNotIn('cabal', guard)
        self.assertNotIn('-- foreign-exceptions', makefile)
        self.assertIn('--continue foreignExceptionTest foreignExceptionDenseTest', makefile)
        for path in (root / ".github/workflows").glob('*.yml'):
            workflow = path.read_text()
            self.assertNotIn('  foreign-exceptions:', workflow, path)
            self.assertNotIn('make foreign-exception-test-modes', workflow, path)

    def test_descriptor_flags_keep_native_regressions_in_affected_fast_tests(self):
        root = Path(__file__).parents[2]
        policy = json.loads((root / ".github/scripts/fast-tests.json").read_text())
        groups = policy["leafSources"] | policy["owners"]
        for source in ("src/main/c/native-file-api.c",
                       "src/main/java/thc/runtime/ManagedFiles.java",
                       "src/main/java/thc/runtime/ManagedStdio.java",
                       "src/main/java/thc/runtime/NativeFileProvider.java",
                       "src/main/java/thc/runtime/NativeFileResource.java",
                       "src/main/java/thc/runtime/OpenedNativeFile.java"):
            with self.subTest(source=source):
                self.assertTrue({"thc.runtime.ManagedProcessForeignTest", "thc.runtime.DescriptorFlagsTest", "thc.runtime.NativeEventDescriptorsTest",
                                 "thc.runtime.NativeEpollTest"}.issubset(groups[source]["junit"]))

    def test_previous_revision_or_driver_error_cannot_publish(self):
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
            recorder.data["passed"] = True
            self.assertTrue(ci.successful_revision(recorder))
            recorder.data["driverError"] = "failure after tests"
            self.assertFalse(ci.successful_revision(recorder))
            del recorder.data["driverError"]
            recorder.data["revision"] = "b" * 40
            self.assertFalse(ci.successful_revision(recorder))

    def test_primop_check_and_selected_fixtures_run_before_junit(self):
        selection = self.selection() | {"reasons": [], "python": {"commands": []}}
        identity = {"platform": "linux", "toolchain": {"version": "9.14.1"}}
        identity_path = self.root / "identity.json"
        identity_path.write_text(json.dumps(identity))
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        with patch.object(recorder, "command", side_effect=[(0, json.dumps(selection)), (0, "")]) as run:
            with patch.object(ci, "run_modes", return_value=({}, [])), \
                    patch.object(ci.fixtures, "prepare_cmake", return_value={"mode": "cmake", "targets": ["fixture-smoke"]}) as prepare:
                ci.execute(recorder, "b" * 40, "HEAD", identity_path)
                prepare.assert_called_once_with(self.root, selection, run)
        self.assertEqual(run.call_args_list[0].args[1][2:4], ["--base", "b" * 40])
        self.assertEqual(run.call_args_list[1].args[0], "primop-checklist")
        self.assertEqual(run.call_args_list[1].args[1],
                         ["cabal", "run", "exe:thc-primops", "--", "coverage", "--check", "--output",
                          str(recorder.directory / "primop-coverage.json")])
        self.assertEqual(run.call_count, 2)
        self.assertEqual(recorder.data["nativeInputs"]["targets"], ["fixture-smoke"])
        self.assertEqual((recorder.data["requestedBase"], recorder.data["selectionBase"]), ("b" * 40, "b" * 40))
        self.assertTrue(recorder.data["passed"])

    def test_selected_driver_units_need_no_plugin_or_runtime_distribution(self):
        selection = self.selection() | {"reasons": [], "python": {"commands": []},
                                        "haskell": {"suites": ["driver-tests"], "count": 1}}
        identity_path = self.root / "identity.json"
        identity_path.write_text(json.dumps({"platform": "linux", "toolchain": {}}))
        for failed in (False, True):
            with self.subTest(failed=failed), patch.object(ci, "git", return_value="a" * 40):
                recorder = ci.Recorder(self.root, self.root / ("failed" if failed else "passed"))
                outputs = [(0, json.dumps(selection)), (0, ""),
                           RuntimeError("units failed") if failed else (0, "")]
                with patch.object(recorder, "command", side_effect=outputs) as commands, \
                        patch.object(ci.fixtures, "prepare_cmake", return_value={"mode": "selected"}), \
                        patch.object(ci, "run_modes", return_value=({}, [])) as modes:
                    if failed:
                        with self.assertRaisesRegex(RuntimeError, "driver-tests"):
                            ci.execute(recorder, "HEAD", "HEAD", identity_path)
                    else:
                        ci.execute(recorder, "HEAD", "HEAD", identity_path)
                modes.assert_called_once_with(recorder, selection, install_dist=False)
                self.assertEqual([call.args[0] for call in commands.call_args_list[2:]], ["driver-tests"])
                self.assertEqual(["cabal", "test", "driver-tests", "-fdevelopment",
                                  "--test-show-details=direct", "--test-options=--unit-only"],
                                 commands.call_args_list[2].args[1])

    def test_non_driver_suites_run_without_building_driver_plugin(self):
        identity_path = self.root / "identity.json"
        identity_path.write_text(json.dumps({"platform": "linux", "toolchain": {}}))
        for suite in ("primop-tools", "compact-core-tests"):
            selection = self.selection() | {"reasons": [], "python": {"commands": []},
                                            "haskell": {"suites": [suite], "count": 1}}
            for failed in (False, True):
                with self.subTest(suite=suite, failed=failed), patch.object(ci, "git", return_value="a" * 40):
                    recorder = ci.Recorder(self.root, self.root / (suite + ("-failed" if failed else "-passed")))
                    outputs = [(0, json.dumps(selection)), (0, ""),
                               RuntimeError("suite failed") if failed else (0, "")]
                    with patch.object(recorder, "command", side_effect=outputs) as commands, \
                            patch.object(ci.fixtures, "prepare_cmake", return_value={"mode": "selected"}), \
                            patch.object(ci, "run_modes", return_value=({}, [])) as smoke:
                        if failed:
                            with self.assertRaisesRegex(RuntimeError, suite):
                                ci.execute(recorder, "HEAD", "HEAD", identity_path)
                        else:
                            ci.execute(recorder, "HEAD", "HEAD", identity_path)
                    smoke.assert_called_once_with(recorder, selection, install_dist=False)
                    self.assertEqual(commands.call_args_list[2].args,
                                     (suite, ["cabal", "test", suite, "-fdevelopment", "--test-show-details=direct"]))
                    self.assertEqual(3, commands.call_count)

    def test_opt_in_harness_compiles_without_executing_and_retains_jvm_smoke(self):
        selection = self.selection() | {"reasons": [], "python": {"commands": []},
                                        "haskell": {"suites": [], "count": 0,
                                                    "compileTargets": ["test:added-full-core"]}}
        identity_path = self.root / "identity.json"
        identity_path.write_text(json.dumps({"platform": "linux", "toolchain": {}}))
        for failed in (False, True):
            with self.subTest(failed=failed), patch.object(ci, "git", return_value="a" * 40):
                recorder = ci.Recorder(self.root, self.root / ("failed" if failed else "passed"))
                outputs = [(0, json.dumps(selection)), (0, ""),
                           RuntimeError("compile failed") if failed else (0, "")]
                with patch.object(recorder, "command", side_effect=outputs) as commands, \
                        patch.object(ci.fixtures, "prepare_cmake", return_value={"mode": "selected"}), \
                        patch.object(ci, "run_modes", return_value=({}, [])) as smoke:
                    if failed:
                        with self.assertRaisesRegex(RuntimeError, "haskell-compile"):
                            ci.execute(recorder, "HEAD", "HEAD", identity_path)
                    else:
                        ci.execute(recorder, "HEAD", "HEAD", identity_path)
                self.assertEqual(commands.call_args_list[2].args,
                                 ("haskell-compile", ["cabal", "build", "test:added-full-core",
                                                       "-fdevelopment", "-ffull-core-tests"]))
                smoke.assert_called_once_with(recorder, selection, install_dist=False)
                self.assertEqual(recorder.data["passed"], not failed)


    def test_required_polyglot_lane_acquires_both_demos_before_junit(self):
        selection = self.selection() | {"reasons": [], "python": {"commands": []},
                                        "polyglot": {"required": True, "classes": ["example.PolyglotTest"]}}
        identity_path = self.root / "identity.json"
        identity_path.write_text(json.dumps({"platform": "linux", "toolchain": {}}))
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        acquired = []
        def command(name, argv, **kwargs):
            if name == "select":
                return 0, json.dumps(selection)
            if name.endswith("-demo"):
                demo = name.removesuffix("-demo")
                self.assertEqual(argv, [f"bin/{demo}-demo.sh"])
                manifest = self.root / "build" / demo / "packages.json"
                manifest.parent.mkdir(parents=True)
                manifest.write_text("{}")
                acquired.append(demo)
            return 0, ""
        def run_polyglot(*args):
            for demo in ("polyglot", "javascript"):
                self.assertTrue((self.root / "build" / demo / "packages.json").is_file(), demo)
            return {"classes": ["example.PolyglotTest"]}
        with patch.object(recorder, "command", side_effect=command), \
                patch.object(ci.fixtures, "prepare_cmake", return_value={"mode": "selected"}), \
                patch.object(ci, "run_modes", return_value=({}, [])), \
                patch.object(ci, "run_polyglot", side_effect=run_polyglot) as optional:
            ci.execute(recorder, "HEAD", "HEAD", identity_path)
        optional.assert_called_once_with(recorder, selection)
        self.assertEqual(acquired, ["polyglot", "javascript"])
        self.assertTrue(recorder.data["passed"])

    def test_failed_demo_acquisition_prevents_polyglot_junit(self):
        selection = self.selection() | {"reasons": [], "python": {"commands": []},
                                        "polyglot": {"required": True, "classes": ["example.PolyglotTest"]}}
        identity_path = self.root / "identity.json"
        identity_path.write_text(json.dumps({"platform": "linux", "toolchain": {}}))
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        def command(name, argv, **kwargs):
            if name == "select":
                return 0, json.dumps(selection)
            if name == "javascript-demo":
                raise RuntimeError("javascript acquisition failed")
            return 0, ""
        with patch.object(recorder, "command", side_effect=command), \
                patch.object(ci.fixtures, "prepare_cmake", return_value={"mode": "selected"}), \
                patch.object(ci, "run_modes", return_value=({}, [])), \
                patch.object(ci, "run_polyglot", return_value={"classes": ["example.PolyglotTest"]}) as optional:
            with self.assertRaisesRegex(RuntimeError, "javascript acquisition failed"):
                ci.execute(recorder, "HEAD", "HEAD", identity_path)
        optional.assert_not_called()
        self.assertFalse(recorder.data["passed"])

    def test_native_oracle_stdout_excludes_diagnostics(self):
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        recorder.command("native", [sys.executable, "-c",
                         "import sys; print('1\\t42'); print('diagnostic', file=sys.stderr)"],
                         stdout="oracle.tsv")
        self.assertEqual((self.root / "oracle.tsv").read_text(), "1\t42\n")
        log = self.root / recorder.data["phases"][0]["log"]
        self.assertEqual(log.read_text(), "diagnostic\n")

    def test_fixture_failure_prevents_junit_success(self):
        selection = self.selection() | {"reasons": [], "python": {"commands": []}}
        identity_path = self.root / "identity.json"
        identity_path.write_text(json.dumps({"platform": "linux", "toolchain": {}}))
        with patch.object(ci, "git", return_value="a" * 40):
            recorder = ci.Recorder(self.root, self.root / "receipts")
        with patch.object(recorder, "command", side_effect=[(0, json.dumps(selection)), (0, "")]), \
                patch.object(ci.fixtures, "prepare_cmake", side_effect=RuntimeError("native failed")), \
                patch.object(ci, "run_modes") as junit:
            with self.assertRaisesRegex(RuntimeError, "native failed"):
                ci.execute(recorder, "HEAD", "HEAD", identity_path)
            junit.assert_not_called()
        self.assertNotIn("passed", recorder.data)

    def test_only_the_current_revision_can_reuse_automation_results(self):
        selection = self.selection() | {"reasons": [], "python": {"commands": [
            ["python3", ".github/scripts/test_fast_select.py"]]}}
        identity_path = self.root / "identity.json"
        identity_path.write_text(json.dumps({"platform": "linux", "toolchain": {}}))
        for checked, expected in (("a" * 40, 2), ("b" * 40, 4), ("", 4)):
            with self.subTest(checked=checked), patch.object(ci, "git", return_value="a" * 40), \
                    patch.dict(os.environ, {"FAST_AUTOMATION_SHA": checked}), \
                    patch.object(ci.fixtures, "prepare_cmake", return_value={"mode": "selected"}), \
                    patch.object(ci, "run_modes", return_value=({}, [])):
                recorder = ci.Recorder(self.root, self.root / ("run-" + (checked or "none")))
                with patch.object(recorder, "command", side_effect=[(0, json.dumps(selection))] + [(0, "")] * 3) as run:
                    ci.execute(recorder, "HEAD", "HEAD", identity_path)
                self.assertEqual(run.call_count, expected)
                self.assertEqual(recorder.data["automationReused"], checked if expected == 2 else None)


if __name__ == "__main__":
    unittest.main()
