#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Strict host probe parsing; numeric test values are synthetic, not platform errno."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("stdio_abi", Path(__file__).with_name("generate-stdio-abi.py"))
abi = importlib.util.module_from_spec(spec)
spec.loader.exec_module(abi)


class StdioAbiTest(unittest.TestCase):
    def document(self):
        return {"widths": dict(abi.WIDTHS), "errno": {name: index + 1 for index, name in enumerate(sorted(abi.ERRNOS))}}

    def test_exact_probe(self):
        value = self.document()
        self.assertIs(value, abi.validate_probe(value))

    def test_width_mutations_and_missing_fields(self):
        for name in abi.WIDTHS:
            for wrong in (None, True, 4.0, "8", -1, 0, 32):
                with self.subTest(name=name, wrong=wrong):
                    value = self.document(); value["widths"][name] = wrong
                    with self.assertRaises(ValueError):
                        abi.validate_probe(value)
            value = self.document(); del value["widths"][name]
            with self.assertRaises(ValueError):
                abi.validate_probe(value)

    def test_errno_mutations_and_closed_fields(self):
        for name in abi.ERRNOS:
            for wrong in (None, True, 9.0, "9", -1, 0, 0x80000000):
                with self.subTest(name=name, wrong=wrong):
                    value = self.document(); value["errno"][name] = wrong
                    with self.assertRaises(ValueError):
                        abi.validate_probe(value)
        value = self.document()
        for mutation in (None, [], {}, value | {"extra": 1}, value | {"widths": None},
                         value | {"errno": []}, value | {"errno": value["errno"] | {"extra": 1}}):
            with self.assertRaises(ValueError):
                abi.validate_probe(mutation)


if __name__ == "__main__":
    unittest.main()
