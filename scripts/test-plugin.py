# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Focused checks for Cabal's registered plugin path representation."""

from pathlib import Path
from contextlib import ExitStack
import json
import os
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "compiler"))
import plugin
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


class PluginRegistryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory(prefix="plugin registry ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.local = self.root / "dist-newstyle/packagedb/ghc-9.14.1"
        self.store = self.root / "configured store"
        self.store_db = self.store / "ghc-9.14.1-abi/package.db"
        self.local.mkdir(parents=True)
        self.store_db.mkdir(parents=True)
        self.plan_path = self.root / "dist-newstyle/cache/plan.json"
        self.plan_path.parent.mkdir()
        self.plan = {"compiler-id": "ghc-9.14.1", "install-plan": [
            dict(id="thc-unit", type="configured", style="local", **{
                "pkg-name": "thc", "component-name": "lib", "pkg-src": {"path": str(self.root)},
                "dist-dir": str(self.root / "dist-newstyle/build/thc"),
                "depends": ["base-unit", "index-unit"]}),
            dict(id="index-unit", type="configured", style="local", depends=["crypto-unit", "base-unit"]),
            dict(id="crypto-unit", type="configured", style="global", depends=["base-unit"]),
            dict(id="base-unit", type="pre-existing", depends=[]),
            dict(id="unrelated-unit", type="configured", style="global", depends=[])]}
        self.save_plan()
        self.boot = self.record("base-unit", [])
        self.write_registration(self.local, "thc-unit", ["base-unit", "index-unit"])
        self.write_registration(self.local, "index-unit", ["crypto-unit", "base-unit"])
        self.write_registration(self.store_db, "crypto-unit", ["base-unit"])
        self.calls = []
        self.fail_check = False
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.dict(os.environ, CABAL="selected-cabal"))
        self.stack.enter_context(patch.object(plugin.subprocess, "check_output", side_effect=self.output))
        self.stack.enter_context(patch.object(plugin.subprocess, "check_call", side_effect=self.call))

    def save_plan(self):
        self.plan_path.write_text(json.dumps(self.plan))

    def record(self, unit, dependencies):
        return (f"name: {unit}\nid: {unit}\nkey: {unit}\nabi: retained-abi\n"
                f"depends: {' '.join(dependencies)}\n"
                "library-dirs: ${pkgroot}/lib\nlibrary-dirs-static: ${pkgroot}/static\n"
                "data-dir: ${pkgroot}/data\nhaddock-html: ${pkgrooturl}/html\n")

    def described(self, record, root):
        return record.replace("library-dirs: ${pkgroot}/lib", "library-dirs: " + json.dumps(str(root) + "/lib")).replace(
            "haddock-html: ${pkgrooturl}/html", "haddock-html: " + json.dumps(root.as_uri() + "/html"))

    def relocated(self, record, root):
        return self.described(record, root).replace("library-dirs-static: ${pkgroot}/static",
            "library-dirs-static: " + json.dumps(str(root) + "/static")).replace(
            "data-dir: ${pkgroot}/data", "data-dir: " + json.dumps(str(root) + "/data"))

    def write_registration(self, database, unit, dependencies):
        path = database / (unit + ".conf")
        path.write_text(self.record(unit, dependencies))
        return path

    def output(self, arguments, **kwargs):
        self.assertEqual(kwargs.get("cwd"), self.root)
        self.calls.append(arguments)
        if arguments[0] == "selected-cabal":
            self.assertEqual(arguments[1:], ["path", "--store-dir"])
            return str(self.store)
        self.assertEqual(arguments[0], "selected-ghc-pkg")
        if arguments[1:] == ["--version"]:
            return "GHC package manager version 9.14.1\n"
        self.assertIn("--no-user-package-db", arguments)
        self.assertEqual(sum(flag in arguments for flag in ("--expand-pkgroot", "--no-expand-pkgroot")), 1)
        if arguments[-1] == "dump":
            # The selected GHC 9.14.1 dump retains these placeholders despite
            # --expand-pkgroot; only describe expands the selected record.
            return self.boot
        self.assertEqual(arguments[-3:-1], ["--ipid", "describe"])
        if "--package-db" not in arguments:
            self.assertEqual(arguments[-1], "base-unit")
            root, record = Path("/boot"), self.boot
        else:
            database = Path(arguments[arguments.index("--package-db") + 1])
            root, record = database.parent, (database / (arguments[-1] + ".conf")).read_text()
        if "--no-expand-pkgroot" in arguments:
            return record + "pkgroot: " + json.dumps(str(root)) + "\n"
        return self.described(record, root)

    def call(self, arguments, **kwargs):
        self.calls.append(arguments)
        self.assertEqual(arguments[0], "selected-ghc-pkg")
        self.assertEqual(kwargs.get("cwd"), self.root)
        self.assertIn("--no-user-package-db", arguments)
        database = Path(arguments[arguments.index("--package-db") + 1])
        if arguments[-1] == "recache":
            (database / "package.cache").write_bytes(b"validated package cache")
        elif arguments[-1] == "check":
            if self.fail_check:
                raise subprocess.CalledProcessError(1, arguments)
        else:
            self.fail("Unexpected package mutation: " + repr(arguments))

    def registry(self):
        return plugin.registry(self.root, "selected-ghc-pkg")

    def test_exact_transitive_closure_and_original_pkgroot_expansion(self):
        result = self.registry()
        database = Path(result["packageDb"])
        self.assertEqual({path.stem for path in database.glob("*.conf")},
                         {"thc-unit", "index-unit", "crypto-unit"})
        crypto = (database / "crypto-unit.conf").read_text()
        self.assertEqual(crypto, self.relocated((self.store_db / "crypto-unit.conf").read_text(), self.store_db.parent))
        self.assertIn("abi: retained-abi", crypto)
        self.assertNotIn("${pkgroot}", crypto)
        self.assertEqual(result["unitId"], "thc-unit")
        self.assertEqual(database.parent, self.root / plugin.REGISTRIES)
        self.assertFalse((self.root / plugin.MANIFEST).exists(), "registry-only must not replace a shared manifest")
        self.assertEqual(len([call for call in self.calls if call[-1] == "recache"]), 1)

    def test_reuse_and_changed_registration_have_content_bound_identities(self):
        first = self.registry()
        self.assertEqual(first, self.registry())
        self.assertEqual(len([call for call in self.calls if call[-1] == "recache"]), 1)
        source = self.local / "index-unit.conf"
        source.write_text(source.read_text().replace("retained-abi", "rebuilt-abi"))
        self.assertNotEqual(first["packageDb"], self.registry()["packageDb"])
        self.assertTrue(Path(first["packageDb"]).is_dir(), "a published registry remains immutable")

    def test_wrapped_long_unit_id_preserves_registration_text(self):
        unit = "cryptohash-sha256-0.11.102.1-26bea2a9c8543ee12a84c9ca11cfc0c9032ef3f873cbd48ef185b584b7eeea18"
        for item in self.plan["install-plan"]:
            if item["id"] == "crypto-unit":
                item["id"] = unit
            item["depends"] = [unit if value == "crypto-unit" else value for value in item["depends"]]
        self.save_plan()
        index = self.local / "index-unit.conf"
        index.write_text(index.read_text().replace("crypto-unit", unit))
        original = self.store_db / "crypto-unit.conf"
        record = original.read_text().replace("id: crypto-unit", "id:\n    " + unit)
        original.unlink()
        (self.store_db / (unit + ".conf")).write_text(record)
        result = self.registry()
        copied = (Path(result["packageDb"]) / (unit + ".conf")).read_text()
        self.assertEqual(copied, self.relocated(record, self.store_db.parent))
        self.assertEqual(plugin.registration_fields(copied)["id"], unit)

    def test_boot_registration_change_changes_identity_without_copying_boot(self):
        first = self.registry()
        self.boot = self.boot.replace("retained-abi", "other-boot-abi")
        second = self.registry()
        self.assertNotEqual(first["packageDb"], second["packageDb"])
        self.assertFalse((Path(second["packageDb"]) / "base-unit.conf").exists())

    def test_boot_dump_placeholders_are_expanded_at_original_global_database(self):
        self.assertIn("${pkgroot}/lib", self.boot)
        self.assertIn("${pkgrooturl}/html", self.boot)
        result = self.registry()
        descriptions = [call for call in self.calls if call[-3:] == ["--ipid", "describe", "base-unit"]
                        and "--expand-pkgroot" in call]
        self.assertEqual(descriptions, [["selected-ghc-pkg", "--global", "--no-user-package-db",
            "--expand-pkgroot", "--ipid", "describe", "base-unit"]])
        self.assertFalse((Path(result["packageDb"]) / "base-unit.conf").exists())

    def test_residual_paths_preserve_ghc_quoting_and_other_declarations(self):
        record = ('id: original-unit\ndepends: base-unit\n'
                  'library-dirs-static:\n    ${pkgroot}/one "${pkgroot}/two words" /already/expanded\n'
                  'data-dir: ${pkgroot}/data\n'
                  'haddock-html: "file:///already%20expanded/docs"\n')
        root = r'"C:\\compiler root\\lib"'
        expected = ('id: original-unit\ndepends: base-unit\n'
                    'library-dirs-static:\n    "C:\\\\compiler root\\\\lib/one" '
                    '"C:\\\\compiler root\\\\lib/two words" /already/expanded\n'
                    'data-dir: "C:\\\\compiler root\\\\lib/data"\n'
                    'haddock-html: "file:///already%20expanded/docs"\n')
        self.assertEqual(plugin.relocate_package_paths(record, root), expected)
        self.assertEqual(plugin.relocate_package_paths(r'data-dir: ${pkgroot}\data' + '\n', root),
                         r'data-dir: "C:\\compiler root\\lib\\data"' + '\n')
        quoted_suffix = r'data-dir: "${pkgroot}\\data with spaces"' + '\n'
        self.assertEqual(plugin.relocate_package_paths(quoted_suffix, root),
                         r'data-dir: "C:\\compiler root\\lib\\data with spaces"' + '\n')
        # No changes to a description literal or an already expanded URL.
        unchanged = 'description: ${pkgroot}/literal\nhaddock-html: file:///unchanged\n'
        self.assertEqual(plugin.relocate_package_paths(unchanged, root), unchanged)

    def test_residual_paths_require_the_tool_reported_quoted_root(self):
        for root in (None, "/guessed/root", '"unterminated'):
            with self.assertRaisesRegex(RuntimeError, "GHC-quoted original package root"):
                plugin.relocate_package_paths('data-dir: ${pkgroot}/data\n', root)

    def test_missing_or_ambiguous_store_registration_is_rejected(self):
        original = self.store_db / "crypto-unit.conf"
        contents = original.read_text()
        original.unlink()
        with self.assertRaisesRegex(RuntimeError, "one actual Cabal registration"):
            self.registry()
        original.write_text(contents)
        duplicate = self.store / "other-compiler/package.db"
        duplicate.mkdir(parents=True)
        (duplicate / original.name).write_text(contents)
        with self.assertRaisesRegex(RuntimeError, "one actual Cabal registration"):
            self.registry()

    def test_registration_identity_and_dependencies_must_match_plan(self):
        source = self.store_db / "crypto-unit.conf"
        original = source.read_text()
        for changed in (original.replace("id: crypto-unit", "id: another-unit"),
                        original.replace("depends: base-unit", "depends: unplanned-unit")):
            source.write_text(changed)
            with self.assertRaisesRegex(RuntimeError, "differs from planned dependency closure"):
                self.registry()

    def test_missing_plan_dependency_and_duplicate_plan_ids_are_rejected(self):
        self.plan["install-plan"] = [item for item in self.plan["install-plan"] if item["id"] != "crypto-unit"]
        self.save_plan()
        with self.assertRaisesRegex(RuntimeError, "absent from Cabal plan"):
            self.registry()
        self.plan["install-plan"].append(self.plan["install-plan"][1])
        self.save_plan()
        with self.assertRaisesRegex(RuntimeError, "Duplicate Cabal plan unit"):
            self.registry()

    def test_selected_global_database_must_supply_planned_boot_dependency(self):
        self.boot = ""
        with self.assertRaisesRegex(RuntimeError, "boot dependency missing"):
            self.registry()

    def test_wrong_selected_package_tool_is_rejected_before_copying(self):
        with patch.object(plugin.subprocess, "check_output", return_value="GHC package manager version 8.8.4"):
            with self.assertRaisesRegex(RuntimeError, "requires ghc-pkg 9.14.1"):
                self.registry()
        self.assertFalse((self.root / plugin.REGISTRIES).exists())

    def test_failed_ghc_pkg_validation_does_not_publish_partial_registry(self):
        self.fail_check = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.registry()
        self.assertEqual(list((self.root / plugin.REGISTRIES).iterdir()), [])

    def test_changed_cached_registration_is_rejected(self):
        result = self.registry()
        source = Path(result["packageDb"]) / "crypto-unit.conf"
        source.write_text(source.read_text().replace("retained-abi", "tampered-abi"))
        with self.assertRaisesRegex(RuntimeError, "differs from its Cabal registrations"):
            self.registry()

    def test_registry_only_needs_no_shared_library(self):
        self.assertEqual(self.registry()["unitId"], "thc-unit")
        self.assertFalse((self.root / "dist-newstyle/build/thc").exists())

    def test_manifest_reader_accepts_old_and_new_databases_but_not_another_root(self):
        result = self.registry()
        shared = self.root / "build/compiler/plugin.so"
        shared.write_bytes(b"shared library")
        data = result | {"sharedLibrary": str(shared), "cabalSharedLibrary": str(shared)}
        manifest = self.root / plugin.MANIFEST
        for database in (result["packageDb"], str(self.local)):
            manifest.write_text(json.dumps(data | {"packageDb": database}))
            self.assertEqual(plugin.read(self.root)["packageDb"], database)
        manifest.write_text(json.dumps(data | {"packageDb": str(self.store_db)}))
        with self.assertRaisesRegex(RuntimeError, "another checkout"):
            plugin.read(self.root)


if __name__ == "__main__":
    unittest.main()
