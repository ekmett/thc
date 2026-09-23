#!/usr/bin/env python3
"""Benchmark guard fixtures; never starts a JVM, benchmark or host probe."""
from pathlib import Path
import runpy
import json
import tempfile
from unittest.mock import patch
import unittest

harness = runpy.run_path(str(Path(__file__).with_name('compare-map-runtimes.py')))
parse_power_status = harness['parse_power_status']
power_warnings = harness['power_warnings']


class HashCompatibilityTest(unittest.TestCase):
    def test_streaming_fallback_matches_native_hashing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'input.bin'
            for contents in (b'', b'abc', bytes(range(256)) * 8193):
                path.write_bytes(contents)
                expected = harness['hashlib'].sha256(contents).hexdigest()
                self.assertEqual(harness['sha256'](path), expected)
                with patch.object(harness['hashlib'], 'file_digest', None, create=True):
                    self.assertEqual(harness['sha256'](path), expected)


class PowerStatusTest(unittest.TestCase):
    def test_low_battery_discharging_with_reported_ac_power(self):
        raw = "Now drawing from 'AC Power'\n -InternalBattery-0 (id=123)\t4%; discharging; (no estimate) present: true\n"
        status = parse_power_status(raw)
        self.assertEqual(status, {'source': 'AC Power', 'batteryPercent': 4, 'batteryState': 'discharging'})
        warnings = power_warnings(status)
        self.assertEqual(len(warnings), 1)
        self.assertIn('Critical battery: 4%', warnings[0])
        self.assertIn('source=AC Power, state=discharging', warnings[0])

    def test_battery_threshold_and_source_transition(self):
        ac = parse_power_status("Now drawing from 'AC Power'\n -InternalBattery-0\t4%; charging; 0:20 remaining\n")
        battery = parse_power_status("Now drawing from 'Battery Power'\n -InternalBattery-0\t10%; discharging; 0:20 remaining\n")
        self.assertEqual(power_warnings(ac), [])
        self.assertEqual(len(power_warnings(battery, ac)), 2)
        battery['batteryPercent'] = 11
        self.assertEqual(power_warnings(battery), [])
        self.assertEqual(parse_power_status('unknown future format'), {})


    def test_thermal_observations_warn_without_changing_timing_validation(self):
        self.assertEqual(power_warnings(dict(thermalState=1, thermalStateName='fair', lowPowerMode=False)), [])
        warnings = power_warnings(dict(thermalState=2, thermalStateName='serious', lowPowerMode=True))
        self.assertEqual(warnings, ['Thermal state: serious', 'macOS low-power mode is enabled'])
        self.assertEqual(power_warnings(dict(thermalError='unavailable')), ['Thermal status unavailable: unavailable'])


class WarmupGuardTest(unittest.TestCase):
    def log(self, directory, calls=12288, seconds=15):
        lines = ['PHASE WARM BEGIN',
                 f'PHASE WARM END calls={calls} elapsedNs={seconds * 1000000000} checksum={17 * (calls // 16)}']
        for sample in range(1, 6):
            lines += [f'PHASE MEASURE {sample} BEGIN', f'PHASE MEASURE {sample} END']
        lines += ['PHASE VERIFY BEGIN', 'PHASE VERIFY END guestLastTierInstalled=true',
                  'diagnostics=' + json.dumps(dict(instrumented=False, backend='bytecode',
                      unsupportedPolicy='diagnostic-traps', unsupportedTraps=0,
                      sourceNotesEnabled=True, sourceSpanCount=1, sourceRootCount=1))]
        path = Path(directory) / 'run.log'
        path.write_text('\n'.join(lines) + '\n')
        return path

    def test_default_guard_remains_compatible(self):
        with tempfile.TemporaryDirectory() as directory:
            result = harness['validate_jvm_log'](self.log(directory), 17, 'bytecode', 'on')
            self.assertTrue(result['lastTierVerified'])

    def test_requested_call_and_time_minima_are_enforced(self):
        with tempfile.TemporaryDirectory() as directory:
            validate = harness['validate_jvm_log']
            with self.assertRaises(harness['InvalidRun']):
                validate(self.log(directory), 17, 'bytecode', 'on', 30000, 45)
            with self.assertRaises(harness['InvalidRun']):
                validate(self.log(directory, calls=30208, seconds=30), 17, 'bytecode', 'on', 30000, 45)
            result = validate(self.log(directory, calls=30208, seconds=45), 17, 'bytecode', 'on', 30000, 45)
            self.assertEqual(result['warmup']['calls'], 30208)

    def test_cli_cannot_weaken_warmup_guards(self):
        for option, value in [('--jvm-warm-seconds', '14'), ('--jvm-warm-seconds', 'nan'),
                              ('--jvm-warm-seconds', 'inf'), ('--minimum-warm-calls', '11999'),
                              ('--native-warm-seconds', '0'), ('--native-warm-seconds', 'nan')]:
            with self.subTest(option=option, value=value):
                argv = ['compare', 'a', 'b', 'c', 'd', 'e', '--baseline-commit', 'test',
                        '--java-home', '/nonexistent', option, value]
                with patch('sys.argv', argv), self.assertRaises(harness['InvalidRun']):
                    harness['main']()


if __name__ == '__main__':
    unittest.main()
