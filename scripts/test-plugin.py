# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Focused checks for Cabal's registered plugin path representation."""

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "compiler"))
from plugin import one_package_path


class RegisteredPathTest(unittest.TestCase):
    def test_ghc_pkg_quoted_path_with_spaces(self):
        self.assertEqual(one_package_path('"/checkout with spaces/dist-newstyle/build"'),
                         "/checkout with spaces/dist-newstyle/build")

    def test_plain_path_and_multiple_paths(self):
        self.assertEqual(one_package_path("/checkout/dist-newstyle/build"),
                         "/checkout/dist-newstyle/build")
        with self.assertRaisesRegex(RuntimeError, "one Cabal"):
            one_package_path("/first /second")


if __name__ == "__main__":
    unittest.main()
