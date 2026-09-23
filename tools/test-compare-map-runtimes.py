#!/usr/bin/env python3
"""Small power-provenance fixtures; never starts a benchmark or probes the host."""
from pathlib import Path
import runpy
import unittest

harness = runpy.run_path(str(Path(__file__).with_name('compare-map-runtimes.py')))
parse_power_status = harness['parse_power_status']
power_warnings = harness['power_warnings']


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


if __name__ == '__main__':
    unittest.main()
