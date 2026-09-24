#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Exercise the native GHC frontend rewrite without a JavaScript engine."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent


def foreign_calls(value):
    if isinstance(value, dict):
        if "foreignCall" in value:
            yield value["foreignCall"]
        for child in value.values():
            yield from foreign_calls(child)
    elif isinstance(value, list):
        for child in value:
            yield from foreign_calls(child)


class JavaScriptFrontendTest(unittest.TestCase):
    def export(self, declarations):
        with tempfile.TemporaryDirectory(prefix="thc-javascript-ffi-") as directory:
            path = Path(directory)
            source = path / "JavaScriptFixture.hs"
            source.write_text(
                "{-# LANGUAGE ForeignFunctionInterface, InterruptibleFFI #-}\n"
                "module JavaScriptFixture where\n" + declarations,
                encoding="utf-8",
            )
            env = os.environ.copy()
            env.update(
                THC_CORE_OUT=str(path / "core"),
                THC_GHC_OUT=str(path / "ghc"),
                THC_SOURCE_NOTES="false",
            )
            process = subprocess.run(
                [str(ROOT / "compiler/export.sh"), f"-i{path}", str(source)],
                cwd=ROOT,
                env=env,
                capture_output=True,
                text=True,
            )
            core = path / "core/JavaScriptFixture.json"
            return process, json.loads(core.read_text()) if core.exists() else None

    def test_native_ghc_exports_typed_io_calls(self):
        declarations = (
            'foreign import javascript "(x,y) => x + y" add :: Int -> Int -> IO Int\n'
            'foreign import javascript unsafe "(x) => x * 0.5" half :: Double -> IO Double\n'
            'foreign import javascript "() => 42" answer :: IO Int\n'
            'foreign import javascript "() => console.log(\'é\')" logMessage :: IO ()\n'
        )
        process, core = self.export(declarations)
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        calls = list(foreign_calls(core))
        self.assertEqual(len(calls), 4)
        by_source = {call["javascriptSource"]: call for call in calls}
        self.assertEqual(len(by_source), 4)
        for source, call in by_source.items():
            self.assertEqual(call["intrinsic"], "javascript-v1")
            self.assertEqual(call["convention"], "ccall")
            self.assertEqual(call["target"]["kind"], "static")
            self.assertTrue(call["target"]["isFunction"])
            self.assertEqual(call["target"]["symbol"], "thc_javascript_v1_" + source.encode("utf-8").hex())
            self.assertEqual(call["arity"], call["suppliedArity"])
        self.assertEqual(by_source["(x,y) => x + y"]["safety"], "safe")
        self.assertEqual(by_source["(x) => x * 0.5"]["safety"], "unsafe")
        self.assertEqual(
            [rep["primReps"] for rep in by_source["(x,y) => x + y"]["argumentReps"]],
            [["IntRep"], ["IntRep"], []],
        )
        self.assertEqual(
            [rep["primReps"] for rep in by_source["() => console.log('é')"]["argumentReps"]],
            [[]],
        )
        self.assertEqual(
            [rep["primReps"] for rep in by_source["() => console.log('é')"]["resultRep"]["components"]],
            [[]],
        )

    def test_unsupported_signatures_and_targets_are_located_errors(self):
        cases = [
            ('foreign import javascript "x => x" bad :: Int -> Int\n', "expected Int/Double"),
            ('foreign import javascript "x => x" bad :: Bool -> IO Int\n', "expected Int/Double"),
            ('foreign import javascript interruptible "x => x" bad :: Int -> IO Int\n', "interruptible"),
            ('foreign import javascript "dynamic" bad :: Int -> IO Int\n', "static JavaScript"),
            ('foreign import javascript "wrapper" bad :: Int -> IO Int\n', "static JavaScript"),
            ('foreign import ccall "thc_javascript_v1_78203d3e2078" bad :: Int -> IO Int\n', "reserved"),
        ]
        for declaration, reason in cases:
            with self.subTest(declaration=declaration):
                process, _ = self.export(declaration)
                output = process.stdout + process.stderr
                self.assertNotEqual(process.returncode, 0, output)
                self.assertIn("JavaScriptFixture.hs:3:1: error", output)
                self.assertIn(reason, output)

    def test_shadowed_io_does_not_turn_effects_into_a_pure_import(self):
        process, _ = self.export(
            "import Prelude hiding (IO)\n"
            "type IO a = a\n"
            'foreign import javascript unsafe "x => x" bad :: Int -> IO Int\n'
        )
        output = process.stdout + process.stderr
        self.assertNotEqual(process.returncode, 0, output)
        self.assertIn("JavaScriptFixture.hs:5:1: error", output)
        self.assertIn("resolved type must use the canonical IO", output)


if __name__ == "__main__":
    unittest.main()
