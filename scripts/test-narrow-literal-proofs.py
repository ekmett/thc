#!/usr/bin/env python3
"""Audit the same metadata projections consumed by both runtime loaders."""
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('prepare', Path(__file__).with_name('prepare-narrow-literal-proofs.py'))
prepare = importlib.util.module_from_spec(spec); spec.loader.exec_module(prepare)

class NarrowLiteralProofs(unittest.TestCase):
    def test_actual_prepared_direct_write_metadata_projections(self):
        for stage in ('pre', 'post'):
            module = json.loads((prepare.OUT/f'{stage}-core/NarrowLiteralProofAudit.json').read_text())
            self.assertEqual(24, len(list(prepare.write_literals(module))))
            for variant in ('exact', 'absent', 'unknown'):
                report = prepare.audit(prepare.project(module, variant))
                self.assertTrue(report['accepted'], (stage, variant, report['issues']))
            for proof in prepare.bad_proofs():
                self.assertFalse(prepare.audit(prepare.project(module, proof))['accepted'], (stage, proof))

    def test_native_values_match_signed_and_high_bit_model(self):
        self.assertEqual(54, prepare.check_inputs()['nativeRows'])

if __name__ == '__main__': unittest.main()
