#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
"""Small policy/provenance tests; synthetic JSON here is never an export input."""

from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import putstrln_export as recipe
from putstrln_inventory import inventory


class RecipeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()

    def test_source_options_preserve_algorithm_and_lines(self):
        source = "{-# OPTIONS_GHC -O2 -fno-warn-name-shadowing #-}\nf x = x\n"
        configured = recipe.configuration_text("GHC.Internal.Encoding.UTF8", source)
        self.assertEqual(source.count("\n"), configured.count("\n"))
        self.assertEqual(configured.splitlines()[1:], source.splitlines()[1:])
        self.assertIn("-O2 -fno-warn-name-shadowing -fignore-interface-pragmas", configured)
        self.assertEqual(recipe.configuration_text("Other", "f x = x\n"), "f x = x\n")
        for flags in ("-O", "-O0", "-O1", "-O2", "-fno-ignore-interface-pragmas"):
            with self.subTest(flags=flags), self.assertRaises(ValueError):
                recipe.configuration_text("Other", "{-# OPTIONS_GHC " + flags + " #-}\n")

    def test_failed_compilation_restores_installed_interface(self):
        installed, overlay, own = (self.root / p for p in ("installed", "overlay", "own"))
        installed.mkdir()
        original = installed / "Example.dyn_hi"
        original.write_bytes(b"installed input")
        recipe.create_overlay(installed, overlay)
        with self.assertRaisesRegex(ValueError, "compiler failed"):
            with recipe.isolated_interface(installed, overlay, Path("Example"), own):
                self.assertFalse((overlay / "Example.hi").exists())
                (overlay / "Example.hi").write_bytes(b"partial generated interface")
                raise ValueError("compiler failed")
        self.assertEqual(original.read_bytes(), b"installed input")
        self.assertTrue((overlay / "Example.hi").is_symlink())
        self.assertEqual((overlay / "Example.hi").resolve(), original)
        self.assertEqual((own / "Example.hi").read_bytes(), b"partial generated interface")

    def export(self, directory, bindings=(), constructors=(), interface_bindings=()):
        source, interface = directory / "Example.json", directory / "THC.InterfaceClosure.json"
        recipe.write(source, {"ghc": "9.14.1", "module": "Example", "boundary": "optimized-Core-after-Tidy-before-CorePrep",
                              "bindings": list(bindings), "constructors": list(constructors)})
        recipe.write(interface, {"module": "THC.InterfaceClosure", "bindings": list(interface_bindings), "constructors": []})
        return {"exit": 0, "paths": [str(source), str(interface)]}

    def test_root_and_interface_body_admission(self):
        binding = {"id": "example:Example.main", "name": "main", "expr": ["lit", "int", "1"]}
        self.export(self.root, [binding])
        self.assertEqual(recipe.validate_export(self.root, "Example", {"main"})["matchedRoots"], ["main"])
        with self.assertRaisesRegex(ValueError, "roots"):
            recipe.validate_export(self.root, "Example", {"renamedWorker"})
        self.export(self.root, [binding], interface_bindings=[{"id": "ghc-internal:Example.privateWorker"}])
        with self.assertRaisesRegex(ValueError, "interface body"):
            recipe.validate_export(self.root, "Example", {"main"})

    def test_merge_excludes_failed_artifacts_and_detects_conflicts(self):
        first = self.export(self.root / "first", [{"id": "example:Example.a"}], [{"id": "C", "arity": 1}])
        failed = self.export(self.root / "failed", [{"id": "example:Example.bad"}])
        failed["exit"] = 1
        merged, _ = recipe.merge_exports([first, failed])
        self.assertEqual([b["id"] for b in merged["bindings"]], ["example:Example.a"])
        conflict = self.export(self.root / "conflict", [], [{"id": "C", "arity": 2}])
        with self.assertRaisesRegex(ValueError, "constructor"):
            recipe.merge_exports([first, conflict])
        duplicate = self.export(self.root / "duplicate", [{"id": "example:Example.a"}])
        with self.assertRaisesRegex(ValueError, "source identity"):
            recipe.merge_exports([first, duplicate])

    def test_inventory_has_honest_missing_metadata_and_full_shapes(self):
        core = {"bindings": [{"id": "main", "expr": [
            {"foreignCall": {"symbol": "fdReady", "safety": "safe"}},
            {"foreignCall": {"symbol": "fdReady", "safety": "unsafe"}},
            ["lit", "unsupported", "&enabled_capabilities"]]}]}
        audit = {"summary": {}, "reachableBindings": [{"id": "main", "reachableVia": ["main"]}],
                 "issues": [{"code": "unsupported-primitive", "detail": "takeMVar#"}], "missingGlobals": []}
        missing = inventory(core, audit, False)
        self.assertIsNone(missing["foreignDeclarations"])
        self.assertEqual(missing["foreignMetadataStatus"], "unavailable-exporter-prerequisite")
        available = inventory(core, audit, True)
        self.assertEqual(len(available["foreignDeclarations"]), 2)
        self.assertEqual(available["unsupportedPrimitives"], ["takeMVar#"])
        self.assertEqual(available["addressAndUnsupportedLiterals"][0]["value"], "&enabled_capabilities")

    def test_generated_sources_reject_failure_and_tampering(self):
        module = "GHC.Internal.Heap.Constants"
        original = self.root / "libraries/ghc-internal/src/GHC/Internal/Heap/Constants.hsc"
        original.parent.mkdir(parents=True)
        original.write_text("-- synthetic test input\n")
        generated = self.root / "generated/Example.hs"
        generated.parent.mkdir()
        generated.write_text("-- synthetic test output\n")
        record = {"module": module, "exit": 0, "source": str(original), "output": str(generated),
                  "sourceSha256": recipe.digest(original), "outputSha256": recipe.digest(generated)}
        manifest = self.root / "provenance.json"
        data = {"sourceCommit": recipe.GHC_COMMIT, "ghcVersion": "9.14.1", "modules": [record]}
        recipe.write(manifest, data)
        self.assertEqual(set(recipe.generated_sources(manifest, self.root)), {module})
        record["exit"] = 1
        recipe.write(manifest, data)
        with self.assertRaisesRegex(ValueError, "source/exit"):
            recipe.generated_sources(manifest, self.root)
        record["exit"] = 0
        recipe.write(manifest, data)
        generated.write_text("-- changed output\n")
        with self.assertRaisesRegex(ValueError, "Changed"):
            recipe.generated_sources(manifest, self.root)

    def test_fresh_plugin_binds_binary_sources_and_capability(self):
        source = self.root / "repo/compiler/THC/Plugin.hs"
        source.parent.mkdir(parents=True)
        source.write_text("-- unit fixture without foreign metadata\n")

        def fake_compiler(argv, cwd, log):
            # Synthetic unit-test bytes, never admitted to a real export run.
            Path(argv[argv.index("-o") + 1]).write_bytes(b"unit test plugin")
            self.assertIn("-fforce-recomp", argv)
            self.assertIn("-clear-package-db", argv)
            return {"exit": 0, "argv": argv, "cwd": str(cwd), "log": str(log)}

        tools = {"ghc": "unit-test-ghc", "compilerPackageFlags": ["-clear-package-db"]}
        directory = self.root / "build/plugin"
        with patch.object(recipe, "run", side_effect=fake_compiler):
            record = recipe.build_plugin(self.root / "repo", directory, tools)
            with self.assertRaisesRegex(ValueError, "fresh"):
                recipe.build_plugin(self.root / "repo", directory, tools)
        self.assertFalse(record["foreignMetadataExporterAvailable"])
        source.write_text('"foreignCall" -- a different current checkout\n')
        recipe.verify_plugin(record)  # Capability follows the built snapshot, not this new source.
        record["foreignMetadataExporterAvailable"] = True
        with self.assertRaisesRegex(ValueError, "capability"):
            recipe.verify_plugin(record)
        record["foreignMetadataExporterAvailable"] = False
        binary = Path(record["binary"])
        binary.write_bytes(b"stale replacement")
        with self.assertRaisesRegex(ValueError, "binary"):
            recipe.verify_plugin(record)
        binary.write_bytes(b"unit test plugin")
        snapshot = Path(next(iter(record["sourceHashes"])))
        snapshot.write_text("-- changed captured source\n")
        with self.assertRaisesRegex(ValueError, "provenance input"):
            recipe.verify_plugin(record)
        with patch.object(recipe, "run", side_effect=fake_compiler):
            rebuilt = recipe.build_plugin(self.root / "repo", self.root / "build/rebuilt", tools)
        self.assertTrue(rebuilt["foreignMetadataExporterAvailable"])

    def test_toolchain_rejects_other_installation_and_binds_package_queries(self):
        bindir = self.root / "a/bin"
        bindir.mkdir(parents=True)
        other = self.root / "b/bin"
        other.mkdir(parents=True)
        for directory in (bindir, other):
            for name in ("ghc", "ghc-pkg", "hsc2hs"):
                path = directory / name
                path.write_text("unit fixture")
                path.chmod(0o755)
        libdir = self.root / "a/lib"
        database = libdir / "package.conf.d"
        database.mkdir(parents=True)
        values = {"--numeric-version": "9.14.1", "--version": "GHC package manager version 9.14.1",
                  "--print-libdir": str(libdir), "--print-global-package-db": str(database), "--info": "[]"}
        with patch.object(recipe, "output", side_effect=lambda argv: values[argv[-1]]):
            tools = recipe.toolchain(str(bindir / "ghc"))
            self.assertIn("--global-package-db=" + str(database), tools["packageQuery"])
            self.assertEqual(tools["compilerPackageFlags"][-1], str(database))
            with self.assertRaisesRegex(ValueError, "bindir"):
                recipe.toolchain(str(bindir / "ghc"), str(other / "ghc-pkg"))
        with self.assertRaisesRegex(ValueError, "bindir"):
            recipe.sibling_tool(tools["ghc"], str(other / "hsc2hs"), "hsc2hs")
        include = libdir / "include"
        include.mkdir()
        with patch.object(recipe, "output", return_value=str(include)) as query:
            self.assertEqual(recipe.package_dirs(tools, "rts", "include-dirs"), [include])
            self.assertEqual(query.call_args.args[0][:4], tools["packageQuery"])
        with patch.object(recipe, "output", return_value=str(other)), self.assertRaisesRegex(ValueError, "libdir"):
            recipe.package_dirs(tools, "rts", "include-dirs")

    def test_auditor_and_recipe_hashes_reject_changed_inputs(self):
        script = self.root / "audit-core.py"
        script.write_text("# unit fixture\n")
        hashes = {str(script): recipe.digest(script)}
        recipe.verify_hashes(hashes)
        script.write_text("# changed auditor\n")
        with self.assertRaisesRegex(ValueError, "provenance input"):
            recipe.verify_hashes(hashes)


if __name__ == "__main__":
    unittest.main()
