#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Configuration and launch-contract checks; never starts a JVM or benchmark."""
import argparse
import importlib.util
import itertools
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('ci_performance', Path(__file__).with_name('ci-performance.py'))
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


def selection(suite, **kwargs):
    return ci.selected_suite(argparse.Namespace(suite=suite, **{
        name: str(kwargs.get(name, False)).lower()
        for name in ('constructor_class', 'unchecked', 'compact_headers')}))


def options(flags):
    return {flag[2:].split('=', 1)[0]: flag.split('=', 1)[1]
            for flag in flags if flag.startswith('-D')}


class ConfigurationTest(unittest.TestCase):
    def test_frozen_inventory_and_hash_checks_cannot_be_optimized_away(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            frozen = out / 'frozen'
            frozen.mkdir()
            payload = frozen / 'runtime.jar'
            payload.write_bytes(b'original')
            ci.write_json(out / 'immutable-manifest.json', {'files': [
                {'path': 'runtime.jar', 'sha256': ci.sha(payload)}]})
            ci.verify(out)
            extra = frozen / 'unexpected.jar'
            extra.write_bytes(b'extra')
            with self.assertRaisesRegex(AssertionError, 'Frozen file inventory changed'):
                ci.verify(out)
            extra.unlink()
            payload.write_bytes(b'changed')
            with self.assertRaisesRegex(AssertionError, 'Frozen input changed'):
                ci.verify(out)
            payload.unlink()
            with self.assertRaisesRegex(AssertionError, 'Frozen file inventory changed'):
                ci.verify(out)

    def test_rejected_checks_never_launch_timing(self):
        for failure in ('map', 'compatibility'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                ci.write_json(out / 'checks.json', {'passed': failure != 'map'})
                ci.write_json(out / 'combined-status.json', {'compatibilityPassed': False})
                with patch.object(ci, 'run') as run:
                    with self.assertRaisesRegex(AssertionError, 'must pass before timing'):
                        ci.compare(out, 'all-on', '/fake-java')
                    run.assert_not_called()

    def test_freeze_retains_separate_tools_and_diagnostics_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'repo'
            files = {
                'src/main/Runtime.kt': b'runtime',
                'src/diagnostics/kotlin/thc/Probe.kt': b'probe source',
                'src/test/Test.kt': b'test',
                'build/install/thc/lib/thc.jar': b'runtime jar',
                'build/diagnostics/thc-tools.jar': b'tools jar',
                'build/map/core.json': b'core',
                'build/map/modules.txt': b'core.json\n',
                'build/map/native/native-oracle': b'native oracle',
                'build/map/oracle.tsv': b'oracle',
                'build/test-results/test/TEST-fixture.xml': b'<testsuite/>',
                'tools/ci-performance.py': b'harness',
                'tools/compare-map-runtimes.py': b'comparison',
                '.github/workflows/performance.yml': b'workflow',
            }
            for name in ('object-sizes.py', 'ObjectSizes.java', 'ObjectSizesAgent.java'):
                files['bench/results/constructor-class/object-sizes/' + name] = b'size tool'
            for name, content in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
            out = Path(directory) / 'result'
            out.mkdir()
            with patch.object(ci, '__file__', str(root / 'tools/ci-performance.py')), \
                 patch.object(ci, 'revision', return_value='fixed-commit'):
                ci.freeze(out, None, selection('all-on'))
            tools = out / 'frozen/current/tools/thc-tools.jar'
            self.assertEqual(tools.read_bytes(), b'tools jar')
            self.assertEqual([p.name for p in (out / 'frozen/current/lib').iterdir()], ['thc.jar'])
            self.assertEqual((out / 'frozen/current/src/diagnostics/kotlin/thc/Probe.kt').read_bytes(), b'probe source')
            (root / 'build/diagnostics/thc-tools.jar').write_bytes(b'new live tools')
            ci.verify(out)
            tools.chmod(0o600)
            tools.write_bytes(b'changed frozen tools')
            with self.assertRaisesRegex(AssertionError, 'Frozen input changed'):
                ci.verify(out)

    def test_map_check_uses_the_selected_frozen_tools_classpath(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            oracle = out / 'frozen/map/oracle.tsv'
            oracle.parent.mkdir(parents=True)
            oracle.write_text(''.join(f'entry\t{i}\t{i}\n' for i in range(18)))
            log = out / 'map.log'
            log.write_text(''.join(f'VERIFIED_MAP\t{phase}\t{i}\t{i}\n'
                                  for phase in ('before-requested-compilation', 'after-requested-compilation')
                                  for i in range(18)) + 'MAP_DIAGNOSTICS ' + json.dumps({
                                      'backend': 'bytecode', 'unsupportedTraps': 0,
                                      'sourceNotesEnabled': True, 'sourceRootCount': 1}) + '\n')
            for name, runtime in (('original', 'original'), ('class-owned-baseline', 'current')):
                with patch.object(ci, 'suite_config', return_value=selection('standard')), \
                     patch.object(ci, 'verify'), patch.object(ci, 'run', return_value=log) as run:
                    ci.check_configuration(out, '/fake-java', name)
                command = run.call_args.args[1]
                paths = command[command.index('-cp') + 1].split(os.pathsep)
                expected = [str(out / 'frozen' / runtime / 'lib/*')]
                if runtime == 'current':
                    expected.append(str(out / 'frozen/current/tools/thc-tools.jar'))
                self.assertEqual(paths, expected)

    def test_standard_controls_are_explicit_and_distinct(self):
        chosen = selection('standard')
        self.assertEqual(len(chosen['requiredComparisons']), 3)
        for name, (_, flags) in chosen['configurations'].items():
            if name != 'original':
                self.assertEqual(len(options(flags)), 6)
            self.assertEqual(sum('UseCompactObjectHeaders' in flag for flag in flags), 1)
        left, right = chosen['comparisons']['constructor-class']
        lflags, rflags = [options(chosen['configurations'][name][1]) for name in (left, right)]
        self.assertEqual(lflags['thc.classOwnedLayouts'], 'false')
        self.assertEqual(rflags['thc.classOwnedLayouts'], 'false')
        self.assertEqual({key for key in lflags if lflags[key] != rflags[key]}, {'thc.constructorClassIdentity'})
        left, right = chosen['comparisons']['compact-headers']
        self.assertEqual(options(chosen['configurations'][left][1]), options(chosen['configurations'][right][1]))
        self.assertIn('-XX:-UseCompactObjectHeaders', chosen['configurations'][left][1])
        self.assertIn('-XX:+UseCompactObjectHeaders', chosen['configurations'][right][1])
        self.assertNotIn(left, chosen['requiredConfigurations'])

    def test_selected_combinations_and_all_on_preset(self):
        for bits in itertools.product((False, True), repeat=3):
            selected = dict(zip(('constructor_class', 'unchecked', 'compact_headers'), bits))
            if not any(bits):
                with self.assertRaises(ValueError): selection('combined', **selected)
                continue
            chosen = selection('combined', **selected)
            self.assertEqual(chosen['comparisons']['combined'], ('combination-control', 'selected-combination'))
            control, candidate = [chosen['configurations'][key][1] for key in chosen['requiredConfigurations']]
            self.assertIn('-XX:-UseCompactObjectHeaders', control)
            self.assertEqual(options(candidate)['thc.constructorClassIdentity'], str(bits[0]).lower())
            self.assertEqual(options(candidate)['thc.staticShapeUnchecked'], str(bits[1]).lower())
            self.assertIn('-XX:' + ('+' if bits[2] else '-') + 'UseCompactObjectHeaders', candidate)
        chosen = selection('all-on')
        self.assertEqual(chosen['comparisons']['all-on'], ('class-owned-baseline', 'all-on'))
        self.assertEqual(set(options(chosen['configurations']['all-on'][1]).values()), {'true'})
        self.assertEqual(sum(value == 'true' for value in options(chosen['configurations']['class-owned-baseline'][1]).values()), 1)
        for suite in ('standard', 'all-on'):
            with self.assertRaises(ValueError): selection(suite, unchecked=True)
        with self.assertRaises(ValueError): selection('typo')

    def test_selected_runner_gates_timing_after_tests_and_map(self):
        for suite in ('combined', 'all-on'):
            chosen = selection(suite, **({'unchecked': True} if suite == 'combined' else {}))
            with tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                events = []
                def tests(_, name, flags):
                    events.append('tests')
                    self.assertEqual(flags, chosen['configurations'][chosen['comparisons'][chosen['requiredComparisons'][0]][1]][1])
                    return dict(tests=167, failures=0, errors=0, skipped=0)
                def check(*_):
                    events.append('map')
                    ci.write_json(out / 'checks.json', {'passed': True, 'configurations': []})
                def compare(_, name, __):
                    events.append('timing')
                    self.assertEqual(name, chosen['requiredComparisons'][0])
                    self.assertTrue(json.loads((out / 'combined-status.json').read_text())['compatibilityPassed'])
                with patch.object(ci, 'suite_config', return_value=chosen), patch.object(ci, 'verify'), \
                     patch.object(ci, 'verify_test_sources'), patch.object(ci, 'full_tests', side_effect=tests), \
                     patch.object(ci, 'check', side_effect=check), patch.object(ci, 'compare', side_effect=compare):
                    ci.combined(out, Path('/fake-java'))
                self.assertEqual(events, ['tests', 'map', 'timing'])
                status = json.loads((out / 'combined-status.json').read_text())
                self.assertEqual(status['suite'], suite)
                self.assertTrue(status['timingAccepted'])

    def test_selected_compatibility_failure_never_times(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            with patch.object(ci, 'suite_config', return_value=selection('all-on')), patch.object(ci, 'verify'), \
                 patch.object(ci, 'verify_test_sources'), patch.object(ci, 'full_tests', side_effect=RuntimeError('failed tests')), \
                 patch.object(ci, 'check') as check, patch.object(ci, 'compare') as compare:
                with self.assertRaisesRegex(RuntimeError, 'failed tests'): ci.combined(out, Path('/fake-java'))
                check.assert_not_called()
                compare.assert_not_called()
            status = json.loads((out / 'combined-status.json').read_text())
            self.assertFalse(status['compatibilityPassed'])
            self.assertFalse(status['timingAccepted'])

    def test_full_suite_respects_explicit_header_control(self):
        for enabled in (False, True):
            with tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                root = out / 'repo'
                source = root / 'build/test-results/test'
                source.mkdir(parents=True)
                (source / 'TEST-stale.xml').write_text('<testsuite tests="999"/>')
                ci.write_json(out / 'run.json', {'repository': str(root)})
                def run(_, command, name, timeout, environment):
                    self.assertIn('-Pthc.compactObjectHeaders=' + str(enabled).lower(), command)
                    self.assertFalse(source.exists())
                    self.assertIn('-XX:' + ('+' if enabled else '-') + 'UseCompactObjectHeaders', environment['JAVA_TOOL_OPTIONS'])
                    source.mkdir(parents=True)
                    (source / 'TEST-current.xml').write_text('<testsuite tests="167" failures="0" errors="0" skipped="0"/>')
                with patch.object(ci, 'run', side_effect=run):
                    result = ci.full_tests(out, 'selected', ci.runtime_flags(compact=enabled))
                self.assertEqual(result['tests'], 167)

    def test_all_on_compare_emits_exact_frozen_flags(self):
        chosen = selection('all-on')
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            ci.write_json(out / 'checks.json', {'passed': True})
            ci.write_json(out / 'combined-status.json', {'compatibilityPassed': True})
            ci.write_json(out / 'run.json', {'currentCommit': 'fixed-commit'})
            result = out / 'comparisons/all-on'
            result.mkdir(parents=True)
            ci.write_json(result / 'validation.json', {'passed': True, 'validatedWindows': 45})
            with patch.object(ci, 'suite_config', return_value=chosen), patch.object(ci, 'verify'), patch.object(ci, 'run') as run:
                ci.compare(out, 'all-on', Path('/fake-java'))
            command = run.call_args.args[1]
            for side, name in (('baseline', 'class-owned-baseline'), ('candidate', 'all-on')):
                actual = [flag.split('=', 1)[1] for flag in command if isinstance(flag, str) and flag.startswith('--' + side + '-jvm-option=')]
                self.assertEqual(actual, ci.POLICY + chosen['configurations'][name][1])
                self.assertEqual(command[command.index('--' + side + '-tools-jar') + 1],
                                 out / 'frozen/current/tools/thc-tools.jar')


if __name__ == '__main__':
    unittest.main()
