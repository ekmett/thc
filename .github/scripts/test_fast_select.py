# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Pure Git/source selection tests: never compile or execute guest/JUnit code."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("fast_select", Path(__file__).with_name("fast_select.py"))
select = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(select)


def java_fixture(name, body="@Test void works() {}"):
    return "package example;\nimport org.junit.jupiter.api.Test;\nclass " + name + " {\n" + body + "\n}\n"


PYTHON_TEST = '''import unittest
class Example(unittest.TestCase):
    def test_example(self): self.assertTrue(True)
if __name__ == "__main__": unittest.main()
'''


class FastSelectionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name)
        self.git("init", "-q")
        # No background Git process should outlive this temporary repository.
        self.git("config", "maintenance.auto", "false")
        self.git("config", "gc.auto", "0")
        smoke = dict(junit=["example.SmokeTest"], python=["bin/test-smoke.py"])
        affected = dict(junit=["example.OtherTest"], python=["bin/test-other.py"])
        self.policy = dict(schema=2, smoke=smoke,
                           leafSources={"src/main/java/Leaf.java": dict(junit=["example.LeafTest"], python=[])},
                           owners={"test/fixtures/compiler/Family.hs": affected,
                                   "bin/prepare-family.py": affected,
                                   "src/test/java/example/SharedContext.java": affected},
                           primopFamilies={name: affected for name in
                                           ("bit-primops", "integer-primops", "signed-narrow-primops", "explicit64-primops",
                                            "simd-generated-primops")},
                           automation={name: dict(junit=[], python=["bin/test-other.py"]) for name in
                                       (select.SCRIPT, select.POLICY, ".github/scripts/test_fast_select.py",
                                        ".github/workflows/fast.yml", ".github/scripts/fast_ci.py")})
        files = {
            select.SCRIPT: Path(select.__file__).read_text(),
            select.POLICY: json.dumps(self.policy),
            "src/test/java/example/SmokeTest.java": java_fixture("SmokeTest"),
            "src/test/java/example/LeafTest.java": java_fixture("LeafTest"),
            "src/test/java/example/OtherTest.java": java_fixture("OtherTest"),
            "src/polyglotTest/java/example/PolyglotTest.java": java_fixture("PolyglotTest"),
            "src/main/java/Leaf.java": "package example;\nclass Leaf { static int leaf() { return 1; } }\n",
            "src/main/java/Critical.java": "package example;\nclass Critical {}\n",
            "bin/test-smoke.py": PYTHON_TEST,
            "bin/test-other.py": PYTHON_TEST,
            "test/haskell-driver/Main.hs": "module Main where\nmain = pure ()\n",
            "test/primop-tools/Main.hs": "module Main where\nmain = pure ()\n",
            "test/json-index/Main.hs": "module Main where\nmain = pure ()\n",
            "test/compact-core/Main.hs": "module Main where\nmain = pure ()\n",
            "README.md": "Documentation\n",
        }
        for path, text in files.items():
            self.write(path, text)
        self.base = self.commit()

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], stderr=subprocess.PIPE).decode().strip()

    def write(self, path, text):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    def commit(self):
        self.git("add", "--all")
        self.git("-c", "user.name=Selector test", "-c", "user.email=test@example.invalid", "commit", "-qm", "fixture")
        return self.git("rev-parse", "HEAD")

    def plan(self, base=None, head="HEAD"):
        value = select.select(self.repo, self.base if base is None else base, head)
        self.assertEqual(value["junit"]["count"], len(value["junit"]["classes"]))
        self.assertEqual(value["python"]["count"], len(value["python"]["files"]))
        self.assertEqual(value["python"]["commands"], [["python3", p] for p in value["python"]["files"]])
        return value

    def full(self, code=None, **kwargs):
        result = self.plan(**kwargs)
        self.assertEqual("full", result["mode"], result)
        self.assertEqual(["*"], result["junit"]["patterns"])
        if code:
            self.assertIn(code, {r["code"] for r in result["reasons"]}, result)
        return result

    def test_no_diff_and_documentation_keep_nonempty_smoke(self):
        self.assertEqual("narrow", self.plan()["mode"])
        self.assertEqual([], self.plan()["haskell"]["suites"])
        self.assertEqual({"required": False, "classes": []}, self.plan()["polyglot"])
        self.write("README.md", "New documentation\n")
        head = self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"])
        self.assertEqual(self.base, result["base"])
        self.assertEqual(head, result["head"])
        self.assertEqual(["example.SmokeTest"], result["junit"]["patterns"])
        self.assertEqual(["README.md"], result["changedPaths"])
        self.assertRegex(result["policySha256"], "^[0-9a-f]{64}$")
        self.assertEqual(result, self.plan())


    def test_store_project_tests_select_driver_suite_without_full_jvm_run(self):
        path = "test/haskell-driver/StoreProjectTests.hs"
        policy = json.loads(Path(__file__).with_name("fast-tests.json").read_text())
        self.policy["owners"][path] = policy["owners"][path]
        self.write(select.POLICY, json.dumps(self.policy))
        self.write(path, "module StoreProjectTests where\nexample = False\n")
        self.base = self.commit()
        self.write(path, "module StoreProjectTests where\nexample = True\n")
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result["reasons"])
        self.assertEqual(["driver-tests"], result["haskell"]["suites"])
        self.assertEqual(["example.SmokeTest"], result["junit"]["classes"])
        self.assertEqual([], result["affected"]["junit"])

    def full_core_addition(self):
        before = ("cabal-version: 3.0\nname: example\nversion: 0.1\n"
                  "extra-source-files:\n  README.md\n"
                  "flag full-core-tests\n  description: Full Core regressions\n"
                  "  default: False\n  manual: True\n"
                  "executable thc\n  main-is: Main.hs\n  build-depends: base\n"
                  "test-suite old-test\n  main-is: Old.hs\n")
        self.write("thc.cabal", before)
        self.write("test/haskell-driver/TestSupport.hs", "module TestSupport where\n")
        base = self.commit()
        prefix = "test/fixtures/run-added/"
        fixture_paths = [prefix + path for path in ("cabal.project", "run-added.cabal", "app/Main.hs")]
        harness = "test/haskell-driver/AddedFullCore.hs"
        for path in fixture_paths + [harness]:
            self.write(path, "new test input\n")
        after = before.replace("  README.md\n", "  README.md\n" +
                               "".join("  " + path + "\n" for path in fixture_paths))
        after += "\ntest-suite added-full-core\n" + select.FULL_CORE_TEST_BODY.format(main="AddedFullCore.hs") + "\n"
        self.write("thc.cabal", after)
        return base, before, after

    def test_added_disabled_full_core_harness_is_compiled_with_smoke(self):
        base, _, _ = self.full_core_addition()
        self.commit()
        result = self.plan(base=base)
        self.assertEqual("narrow", result["mode"], result["reasons"])
        self.assertEqual(["example.SmokeTest"], result["junit"]["patterns"])
        self.assertEqual([], result["haskell"]["suites"])
        self.assertEqual(["test:added-full-core"], result["haskell"]["compileTargets"])
        self.assertFalse(result["polyglot"]["required"])

    def test_full_core_exception_rejects_active_unknown_and_production_changes(self):
        base, _, after = self.full_core_addition()
        changes = {
            "active default": after.replace("  default: False", "  default: True"),
            "automatic flag": after.replace("  manual: True", "  manual: False"),
            "active stanza": after.replace("if !flag(full-core-tests)", "if flag(full-core-tests)"),
            "unknown condition": after.replace("if !flag(full-core-tests)", "if os(linux)"),
            "overridden buildable": after.replace("  main-is: AddedFullCore.hs", "  buildable: True\n  main-is: AddedFullCore.hs"),
            "production source dir": after.replace("hs-source-dirs: test/haskell-driver", "hs-source-dirs: src"),
            "production module": after.replace("other-modules: TestSupport", "other-modules: THC.Driver.Project"),
            "production import": after.replace("test-suite added-full-core", "test-suite added-full-core\n  import: production"),
            "production dependency": after.replace("  build-depends: base\n", "  build-depends: base, containers\n"),
            "changed test dependency": after.replace("    aeson >= 2.3 && < 2.4,", "    thc,"),
            "changed old stanza": after.replace("  main-is: Old.hs", "  main-is: Changed.hs"),
            "removed old stanza": after.replace("test-suite old-test\n  main-is: Old.hs\n", ""),
        }
        for name, text in changes.items():
            with self.subTest(name=name):
                self.write("thc.cabal", text)
                self.commit()
                result = self.plan(base=base)
                self.assertEqual("full", result["mode"])
                self.assertEqual([], result["haskell"]["compileTargets"])
                self.assertTrue(result["polyglot"]["required"])

    def test_full_core_exception_does_not_hide_unowned_or_shared_inputs(self):
        base, _, _ = self.full_core_addition()
        self.write("test/haskell-driver/TestSupport.hs", "module TestSupport where\nchanged = True\n")
        self.commit()
        result = self.plan(base=base)
        self.assertEqual("full", result["mode"])
        self.assertIn("test/haskell-driver/TestSupport.hs", [r.get("path") for r in result["reasons"]])
        self.assertEqual(["test:added-full-core"], result["haskell"]["compileTargets"])

    def test_full_core_exception_requires_new_exact_fixture_ownership(self):
        base, before, after = self.full_core_addition()
        statuses = {"thc.cabal": "M", "test/haskell-driver/AddedFullCore.hs": "A",
                    **{f"test/fixtures/run-added/{name}": "A" for name in
                       ("cabal.project", "run-added.cabal", "app/Main.hs")}}
        self.assertIsNotNone(select.additive_full_core_tests(before, after, statuses, []))
        self.assertIsNone(select.additive_full_core_tests(before, after, statuses,
                          ["test/fixtures/run-added/existing.hs"]))
        for path in statuses.keys() - {"thc.cabal"}:
            with self.subTest(path=path):
                self.assertIsNone(select.additive_full_core_tests(before, after, statuses | {path: "M"}, []))
        self.write("test/fixtures/run-added/unowned.hs", "unlisted input\n")
        self.commit()
        self.assertEqual("full", self.plan(base=base)["mode"])


    def test_driver_source_selects_cabal_suite(self):
        self.policy["owners"]["src/driver/THC/Driver/Project.hs"] = dict(
            junit=[], python=[], haskell=["driver-tests"])
        self.write(select.POLICY, json.dumps(self.policy))
        self.write("src/driver/THC/Driver/Project.hs", "module THC.Driver.Project where\n")
        before = self.commit()
        self.write("src/driver/THC/Driver/Project.hs", "module THC.Driver.Project where\nchanged = True\n")
        self.commit()
        selected = self.plan(base=before)
        self.assertEqual("narrow", selected["mode"], selected)
        self.assertEqual(["driver-tests"], selected["haskell"]["suites"])
        self.assertEqual(["driver-tests"], selected["affected"]["haskell"])

    def test_primop_tests_select_the_new_cabal_suite(self):
        path = "test/primop-tools/Main.hs"
        self.policy["owners"][path] = dict(junit=[], python=[], haskell=["primop-tools"])
        self.write(select.POLICY, json.dumps(self.policy))
        before = self.commit()
        self.write(path, "module Main where\nmain = print True\n")
        self.commit()
        selected = self.plan(base=before)
        self.assertEqual("narrow", selected["mode"], selected)
        self.assertEqual(["primop-tools"], selected["haskell"]["suites"])
        self.assertEqual(["primop-tools"], selected["affected"]["haskell"])

    def test_json_index_inputs_select_the_cabal_suite(self):
        policy = json.loads(Path(__file__).with_name("fast-tests.json").read_text())
        for path in ("json-index/THC/JsonIndex.hs", "json-index/THC/JsonIndex/Scanner.hs", "compiler/json-index/json_index.c",
                     "compiler/json-index/json_index.h", "test/json-index/Main.hs", "test/json-index/native.c"):
            with self.subTest(path=path):
                self.policy["owners"][path] = policy["owners"][path]
                self.write(select.POLICY, json.dumps(self.policy))
                self.write(path, "original\n")
                before = self.commit()
                self.write(path, "changed\n")
                self.commit()
                selected = self.plan(base=before)
                self.assertEqual("narrow", selected["mode"], selected)
                self.assertTrue(selected["runnable"])
                suites = ["json-index"] if path.startswith("test/") else ["driver-tests", "json-index"]
                self.assertEqual(suites, selected["haskell"]["suites"])
                self.assertEqual(suites, selected["affected"]["haskell"])
                self.assertEqual(["example.SmokeTest"], selected["junit"]["classes"])

    def test_compact_core_sources_and_goldens_select_all_consumers(self):
        self.write("src/test/java/thc/CoreCompactGoldenTest.java",
                   java_fixture("CoreCompactGoldenTest").replace("package example", "package thc"))
        policy = json.loads(Path(__file__).with_name("fast-tests.json").read_text())
        for path in ("src/cbd/THC/Compact/Wire.hs", "src/cbd/THC/Compact/Writer.hs",
                     "src/cbd/THC/Compact/Compression.hs", "src/cbd/THC/Compact/Zip.hs",
                     "test/compact-core/CbdTests.hs", "test/compact-core/CompressionTests.hs",
                     "test/compact-core/Main.hs", "test/compact-core/golden/integers-v1.json",
                     "test/compact-core/golden/cbd-header-v1.hex",
                     "test/compact-core/golden/cbd-module-v1.json",
                     "test/compact-core/golden/cbd-module-v1-stored.cbd",
                     "test/compact-core/golden/cbd-module-v1-deflated.cbd",
                     "test/compact-core/golden/cbd-module-v1-mixed.cbd"):
            with self.subTest(path=path):
                self.policy["owners"][path] = policy["owners"][path]
                self.write(select.POLICY, json.dumps(self.policy))
                self.write(path, "original\n")
                before = self.commit()
                self.write(path, "changed\n")
                self.commit()
                selected = self.plan(base=before)
                self.assertEqual("narrow", selected["mode"], selected)
                self.assertTrue(selected["runnable"])
                self.assertEqual(["compact-core-tests"], selected["haskell"]["suites"])
                self.assertEqual(["compact-core-tests"], selected["affected"]["haskell"])
                junit = ["example.SmokeTest"]
                if path.startswith("test/compact-core/golden/"):
                    junit.append("thc.CoreCompactGoldenTest")
                self.assertEqual(junit, selected["junit"]["classes"])

    def test_json_index_documentation_retains_only_smoke(self):
        for path in ("compiler/json-index/README.md", "compiler/json-index/LICENSE.succinctly"):
            with self.subTest(path=path):
                before = self.git("rev-parse", "HEAD")
                self.write(path, "Documentation or license update\n")
                self.commit()
                selected = self.plan(base=before)
                self.assertEqual("narrow", selected["mode"], selected)
                self.assertEqual([], selected["haskell"]["suites"])
                self.assertEqual(["example.SmokeTest"], selected["junit"]["classes"])

    def test_full_selection_includes_json_index(self):
        self.write("json-index/THC/JsonIndex/Producer.hs", "unreviewed production dependency\n")
        self.commit()
        selected = self.full("unmapped-source-or-configuration")
        self.assertTrue(selected["runnable"])
        self.assertEqual(["compact-core-tests", "driver-tests", "json-index", "primop-tools"], selected["haskell"]["suites"])

    def test_polyglot_changes_select_actual_optional_class_without_all_regular_tests(self):
        path = "src/polyglotTest/java/example/PolyglotTest.java"
        self.write(path, java_fixture("PolyglotTest", "@Test void changed() {}"))
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual({"required": True, "classes": ["example.PolyglotTest"]}, result["polyglot"])
        self.assertEqual(["example.SmokeTest"], result["junit"]["classes"])

    def test_shared_core_and_frontend_changes_require_polyglot_but_leaf_does_not(self):
        for path in ("src/main/java/thc/runtime/CoreRepresentations.java",
                     "src/main/java/thc/Language.java", "src/main/java/thc/Json.java",
                     "src/compiler/THC/Plugin.hs", "src/main/java/thc/runtime/Calls.java",
                     "src/main/java/thc/runtime/WindowsMalloc.java", "src/main/java/thc/runtime/StdioHostAbi.java",
                     "bin/audit-core.py", "build.gradle", "Makefile",
                     "buildSrc/src/main/java/thc/buildlogic/BytecodeNormalizers.java", "gradle/bytecode-metadata.gradle",
                     "bin/plugin.py", "gradlew", "gradle/wrapper/gradle-wrapper.properties"):
            with self.subTest(path=path):
                self.write(path, "changed\n")
                self.commit()
                self.assertTrue(self.plan()["polyglot"]["required"])
                self.base = self.git("rev-parse", "HEAD")
        self.write("src/main/java/Leaf.java", "package example;\nclass Leaf { static int leaf() { return 2; } }\n")
        self.commit()
        self.assertFalse(self.plan()["polyglot"]["required"])

    def test_full_core_source_set_is_not_an_optional_language_test_inventory(self):
        # Protocol helpers cannot safely enter the portable optional inventory.
        # The dedicated source set must neither block Fast checks nor claim they ran.
        optional = "src/polyglotTest/java/example/ForeignExceptionTest.java"
        source = java_fixture("ForeignExceptionTest") + "\nclass ForeignProtocolHelper {}\n"
        self.write(optional, source)
        self.commit()
        self.assertFalse(self.plan()["runnable"])
        self.git("rm", optional)
        self.write("src/fullCoreTest/java/example/ForeignExceptionTest.java", source)
        self.write("src/polyglotTest/java/example/PolyglotTest.java", java_fixture("PolyglotTest", "@Test void stillRuns() {}"))
        self.commit()
        result = self.plan()
        self.assertTrue(result["runnable"])
        self.assertNotIn("example.ForeignExceptionTest", result["junit"]["classes"])
        self.assertEqual(["example.PolyglotTest"], result["polyglot"]["classes"])

    def test_missing_optional_class_cannot_pass_a_required_lane(self):
        path = "src/polyglotTest/java/example/PolyglotTest.java"
        self.git("rm", path)
        self.commit()
        result = self.plan()
        self.assertTrue(result["polyglot"]["required"])
        self.assertFalse(result["runnable"])
        self.assertIn("empty-polyglot-inventory", {reason["code"] for reason in result["reasons"]})

    def test_changed_test_is_never_removed_for_smoke_budget(self):
        self.write("src/test/java/example/OtherTest.java", java_fixture("OtherTest", "@Test void expensiveNativeCampaign() {}"))
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(["example.OtherTest", "example.SmokeTest"], result["junit"]["classes"])

    def test_multiple_top_level_classes_use_actual_packages_not_filename(self):
        self.write("src/test/java/example/misleading.java", java_fixture("AddedTest") + "\nclass SecondTest { @Test void other() {} }\n")
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(["example.AddedTest", "example.SecondTest"], result["affected"]["junit"])

    def test_comments_strings_and_text_blocks_do_not_create_tests(self):
        body = r'''/* class Fake { @Test void fake() {} } /* not a nested comment */
private String raw = """
\""" is an escaped delimiter
class FakeRaw { @Test void fake() {} }
""";
private String text = "class FakeString { @Test }";
@Test void real() { char quote = '\''; String slash = "\\"; }
'''
        self.write("src/test/java/example/OtherTest.java", java_fixture("OtherTest", body))
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(["example.OtherTest"], result["affected"]["junit"])
        masked = select.code_only(java_fixture("OtherTest", body))
        self.assertEqual(len(java_fixture("OtherTest", body)), len(masked))
        self.assertNotIn("FakeRaw", masked)
        self.assertEqual([i for i, char in enumerate(java_fixture("OtherTest", body)) if char == '\n'],
                         [i for i, char in enumerate(masked) if char == '\n'])

    def test_batched_blob_reads_preserve_bytes_and_reject_truncation(self):
        path = "binary-payload.bin"
        content = b"first\n\x00middle\nlast\n"
        (self.repo / path).write_bytes(content)
        self.commit()
        oid = self.git("rev-parse", "HEAD:" + path)
        self.assertEqual(content, select.batch_blobs(self.repo, [oid, oid])[oid])
        with mock.patch.object(select, "git", return_value=oid.encode() + b" blob 4\nabc\n"):
            with self.assertRaises(select.SelectionError):
                select.batch_blobs(self.repo, [oid])

    def test_changed_python_script_uses_literal_argv_including_weird_filename(self):
        path = "bin/test-name space\tline\n'$(touch nope);.py"
        self.write(path, PYTHON_TEST)
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertIn(["python3", path], result["python"]["commands"])
        self.assertIn(path, result["changedPaths"])
        self.assertEqual(result, json.loads(json.dumps(result)))
        self.assertFalse((self.repo / "nope").exists())

    def test_declared_leaf_adds_its_test_and_smoke(self):
        self.write("src/main/java/Leaf.java", "package example;\nclass Leaf { static int leaf() { return 2; } }\n")
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(["example.LeafTest", "example.SmokeTest"], result["junit"]["classes"])

    def test_owned_runtime_file_keeps_polyglot_lane_and_shared_program_widens(self):
        path = "src/main/java/thc/runtime/ManagedFiles.java"
        self.policy["owners"][path] = dict(junit=["example.OtherTest"], python=[])
        self.write(select.POLICY, json.dumps(self.policy))
        self.write(path, "package thc.runtime;\nclass ManagedFiles {}\n")
        before = self.commit()
        self.write(path, "package thc.runtime;\nclass ManagedFiles { boolean changed = true; }\n")
        self.commit()
        selected = self.plan(base=before)
        self.assertEqual("narrow", selected["mode"], selected)
        self.assertIn("example.OtherTest", selected["affected"]["junit"])
        self.assertTrue(selected["polyglot"]["required"])
        self.write("src/main/java/thc/runtime/Program.java", "package thc.runtime;\nclass Program {}\n")
        self.commit()
        self.full("unmapped-source-or-configuration", base=before)

    def test_fixture_preparer_and_shared_test_context_select_their_owner(self):
        for path in self.policy["owners"]:
            with self.subTest(path=path):
                self.write(path, "changed family input\n")
                self.commit()
                result = self.plan()
                self.assertEqual("narrow", result["mode"], result)
                self.assertIn("example.OtherTest", result["affected"]["junit"])
                self.assertIn("bin/test-other.py", result["affected"]["python"])
                self.base = result["head"]

    def test_unknown_fixture_and_preparer_still_widen(self):
        for path in ("test/fixtures/compiler/Unknown.hs", "bin/prepare-unknown.py"):
            with self.subTest(path=path):
                self.write(path, "unmapped\n")
                self.commit()
                self.full("unmapped-source-or-configuration")
                self.base = self.git("rev-parse", "HEAD")

    def test_capability_primop_addition_is_scoped_but_other_contract_edits_widen(self):
        path = select.CAPABILITIES
        original = {"name": "contract", "primitives": {"plusInt#": 2}}
        self.write(path, json.dumps(original))
        self.base = self.commit()
        changed = copy.deepcopy(original)
        changed["primitives"]["popCnt8#"] = 1
        self.write(path, json.dumps(changed))
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertIn("example.OtherTest", result["affected"]["junit"])
        changed["name"] = "different contract"
        self.write(path, json.dumps(changed))
        self.commit()
        self.full("shared-primop-registry-change")

    def test_uncovered_primitive_name_does_not_claim_native_oracle_coverage(self):
        path = select.CAPABILITIES
        self.write(path, json.dumps({"primitives": {}}))
        self.base = self.commit()
        self.write(path, json.dumps({"primitives": {"byteSwap8#": 1}}))
        self.commit()
        self.full("shared-primop-registry-change")

    def test_shared_java_program_dispatch_stays_full(self):
        self.assertEqual("src/main/java/thc/runtime/Program.java", select.PROGRAM)
        self.assertEqual("src/main/java/thc/runtime/BytecodeProgram.java", select.BYTECODE_PROGRAM)
        for path in (select.PROGRAM, select.BYTECODE_PROGRAM):
            with self.subTest(path=path):
                before = ('package thc.runtime;\nclass Dispatch {\n'
                          '  static int arity(String operation) { return switch (operation) {\n'
                          '    case "plusInt#" -> 2;\n    default -> 0;\n  }; }\n}\n')
                self.write(path, before)
                base = self.commit()
                after = before.replace('    default ->', '    case "popCnt8#" -> 1;\n    default ->')
                self.write(path, after)
                self.commit()
                self.full("shared-primop-registry-change", base=base)
                self.assertTrue(self.plan(base=base)["polyglot"]["required"])
                self.write(path, after.replace('case "plusInt#" -> 2', 'case "plusInt#" -> 1'))
                self.commit()
                self.full("shared-primop-registry-change", base=base)


    def test_exact_generated_simd_extrema_addition_selects_family_checks(self):
        group = json.loads(Path(__file__).with_name("fast-tests.json").read_text())["primopFamilies"]["simd-generated-primops"]
        self.policy["primopFamilies"]["simd-generated-primops"] = group
        self.write(select.POLICY, json.dumps(self.policy))
        for name in group["junit"]:
            package, short = name.rsplit(".", 1)
            self.write("src/test/java/" + name.replace(".", "/") + ".java",
                       java_fixture(short).replace("package example", "package " + package))
        for path in group["python"]:
            self.write(path, PYTHON_TEST)
        fixtures = json.loads(Path(__file__).with_name("fast-fixtures.json").read_text())
        owners = {name: group_id for group_id, entry in fixtures["groups"].items() for name in entry["junit"]}
        self.assertEqual({"thc.runtime.SimdCapabilitySmokeTest": "simd-capability-smoke",
                          "thc.runtime.SimdFamiliesTest": None},
                         {name: owners.get(name) for name in group["junit"]})
        self.assertIn("thc.runtime.SimdFamiliesTest", fixtures["fixtureFreeJunit"])
        spec = dict(schema=1, families=[dict(name="Word32X8", laneRep="Word32Rep", operations=["insert"])])
        capabilities = dict(primitives={"insertWord32X8#": 3})
        root = ("class BytecodeRoot {\n    // BEGIN GENERATED SIMD FAMILIES\n"
                "    // existing operation\n    // END GENERATED SIMD FAMILIES\n}\n")
        program = ("class BytecodeProgram {\n"
                   "  Expression generatedVectorPrimitive(String name, java.util.List<Expression> operands) {\n"
                   "    return switch (name) {\n    // BEGIN GENERATED SIMD FAMILIES\n"
                   "    // existing operation\n    // END GENERATED SIMD FAMILIES\n"
                   "    default -> throw new IllegalArgumentException(name);\n    };\n  }\n}\n")
        original = {select.SIMD_SPEC: json.dumps(spec), select.CAPABILITIES: json.dumps(capabilities),
                    select.BYTECODE_ROOT: root, select.BYTECODE_PROGRAM: program}
        for path, body in original.items():
            self.write(path, body)
        self.base = self.commit()
        spec["families"][0]["operations"] += ["min", "max"]
        capabilities["primitives"].update({"minWord32X8#": 2, "maxWord32X8#": 2})
        blocks = {}
        for op in ("min", "max"):
            node = "GeneratedWord32X8" + op.capitalize()
            blocks[node] = (
                f"    @Operation public static final class {node} {{\n"
                f"        @Specialization public static Word32X8 apply(Word32X8 left, Word32X8 right) {{ return Word32X8.{op}(left, right); }}\n"
                "    }\n",
                f'            case "{op}Word32X8#" -> new ProvenExpression(e -> {{\n'
                "                var b = e.builder;\n"
                f"                b.begin{node}(); for (var operand : operands) operand.emit(e); b.end{node}();\n"
                "            }, GeneratedVectors.proofWord32X8);\n")
        updated = {select.SIMD_SPEC: json.dumps(spec), select.CAPABILITIES: json.dumps(capabilities),
                   select.BYTECODE_ROOT: root.replace("    // END GENERATED", "".join(pair[0] for pair in blocks.values()) + "    // END GENERATED"),
                   select.BYTECODE_PROGRAM: program.replace("    // END GENERATED", "".join(pair[1] for pair in blocks.values()) + "    // END GENERATED")}
        for path, body in updated.items():
            self.write(path, body)
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(sorted(group["junit"]), result["affected"]["junit"])
        self.assertEqual(sorted(group["python"]), result["affected"]["python"])
        self.assertEqual(["primop-tools"], result["haskell"]["suites"])
        self.assertFalse(result["polyglot"]["required"])

        for path, body, reason in (
                (select.BYTECODE_ROOT, updated[select.BYTECODE_ROOT].replace("class BytecodeRoot", "class OtherRoot"), "unverified-simd-generated-code"),
                (select.BYTECODE_ROOT, updated[select.BYTECODE_ROOT].replace("    // END GENERATED SIMD FAMILIES", "    // missing marker"), "unverified-simd-generated-code"),
                (select.BYTECODE_PROGRAM, updated[select.BYTECODE_PROGRAM].replace("// existing operation", "// changed operation"), "unverified-simd-generated-code"),
                (select.CAPABILITIES, json.dumps(dict(primitives={**capabilities["primitives"], "plusInt#": 2})), "shared-primop-registry-change"),
                (select.SIMD_SPEC, json.dumps(dict(schema=1, families=[dict(name="Word32X8", laneRep="Word32Rep", operations=["min", "max"])])), "unverified-simd-generation-change")):
            with self.subTest(path=path):
                self.write(path, body)
                self.commit()
                self.full(reason)
                self.write(path, updated[path])
                self.commit()
        self.write(select.SIMD_GENERATOR, "# changed generator semantics\n")
        self.commit()
        self.full("unverified-simd-generation-change")

    def test_unknown_production_configuration_resources_and_compiler_widen(self):
        for path in ("src/main/java/Critical.java", "src/main/java/ArgumentLayout.java", "src/compiler/THC/Plugin.hs",
                     "build.gradle", "src/main/resources/proof.json", "bin/helper.py"):
            with self.subTest(path=path):
                self.write(path, "changed")
                self.commit()
                self.full("unmapped-source-or-configuration")

    def test_unmapped_java_comments_and_code_remain_conservative(self):
        path = "src/main/java/thc/Language.java"
        before = ('package thc;\nclass Language {\n'
                  '  String address = "https://example.invalid/a//b";\n'
                  '  /* original */ int value = 1; // original\n}\n')
        self.write(path, before)
        self.base = self.commit()
        for after in (before.replace("/* original */", "/* revised */"),
                      before.replace("https://", "http://"),
                      before.replace("int value = 1", "int value = 2"),
                      before.replace("/* original */", "/* unclosed")):
            with self.subTest(after=after):
                self.write(path, after)
                self.commit()
                self.full("unmapped-source-or-configuration")
                self.assertTrue(self.plan()["polyglot"]["required"])

    def test_unclosed_java_lexical_regions_reject_inventory(self):
        for source in ('/* unclosed', '"unclosed', '"""\nunclosed', "'x"):
            with self.subTest(source=source), self.assertRaises(select.SelectionError):
                select.code_only(source)

    def test_class_literals_private_generic_helpers_and_tempdir_are_local_test_syntax(self):
        path = "src/test/java/example/OtherTest.java"
        source = java_fixture("OtherTest", '''
  @TempDir java.nio.file.Path directory;
  private <T> T entered(java.util.function.Supplier<T> body) { return body.get(); }
  @Test void catches() { org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> entered(() -> 1)); }
''')
        self.write(path, source)
        self.base = self.commit()
        self.write(path, source.replace("() -> 1", "() -> 2"))
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(["example.OtherTest"], result["affected"]["junit"])
        for changed in (source.replace("private <T>", "<T>"),
                        source.replace("@TempDir ", "")):
            self.write(path, changed)
            self.commit()
            self.full("shared-test-member")

    def test_anonymous_classes_keep_private_helpers_local_without_hiding_shared_ones(self):
        path = "src/test/java/example/OtherTest.java"
        source = java_fixture("OtherTest", '''
  private java.util.function.IntSupplier thunk() {
    return new java.util.function.IntSupplier() { public int getAsInt() { return 1; } };
  }
  @Test void works() { org.junit.jupiter.api.Assertions.assertEquals(1, thunk().getAsInt()); }
''')
        self.write(path, source)
        self.base = self.commit()
        self.write(path, source.replace("return 1;", "return 2;"))
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(["example.OtherTest"], result["affected"]["junit"])
        for changed, reason in (
                (source.replace("private java.util.function.IntSupplier", "java.util.function.IntSupplier"), "shared-test-member"),
                (source + "class Shared {}\n", "shared-test-helper")):
            self.write(path, changed)
            self.commit()
            self.full(reason)


    def test_native_file_buffers_inventory_remains_exact(self):
        path = "src/test/java/thc/runtime/NativeFileBuffersTest.java"
        source = (select.ROOT / path).read_text()
        classes, unsafe, _ = select.junit_info(source)
        self.assertEqual(["thc.runtime.NativeFileBuffersTest"], classes)
        self.assertEqual([], unsafe)
        self.write(path, source)
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(["thc.runtime.NativeFileBuffersTest"], result["affected"]["junit"])

    def test_automation_changes_use_control_tests_and_smoke(self):
        for path in (".github/workflows/fast.yml", ".github/scripts/fast_ci.py"):
            with self.subTest(path=path):
                self.write(path, "changed")
                self.commit()
                result = self.plan()
                self.assertEqual("narrow", result["mode"], result)
                self.assertEqual(["example.SmokeTest"], result["junit"]["classes"])
                self.assertIn("bin/test-other.py", result["python"]["files"])
                self.base = result["head"]

    def test_deleted_and_renamed_tests_widen_and_retain_both_paths(self):
        old = "src/test/java/example/OtherTest.java"
        new = "src/test/java/example/RenamedTest.java"
        self.git("mv", old, new)
        self.commit()
        result = self.full("deleted-renamed-or-typechanged")
        self.assertEqual([old, new], result["changes"][0]["paths"])
        self.assertEqual(sorted([old, new]), result["changedPaths"])
        self.assertIn("example.OtherTest", result["junit"]["classes"])
        self.git("rm", new)
        self.commit()
        result = self.full("deleted-renamed-or-typechanged")
        self.assertNotIn("example.OtherTest", result["junit"]["classes"])

    def test_removed_class_inside_existing_file_widens(self):
        self.write("src/test/java/example/OtherTest.java", java_fixture("ReplacementTest"))
        self.commit()
        self.full("removed-junit-class")

    def test_python_delete_widens_and_does_not_execute_missing_file(self):
        self.git("rm", "bin/test-other.py")
        self.commit()
        result = self.full("deleted-renamed-or-typechanged")
        self.assertNotIn("bin/test-other.py", result["python"]["files"])

    def test_missing_base_invalid_head_and_nonancestor_base_fail_closed(self):
        for base in ("", "missing", "--help", "a" * 40):
            with self.subTest(base=base):
                self.full("missing-base", base=base)
        self.full("missing-head", head="missing")
        self.write("README.md", "later")
        later = self.commit()
        self.full("head-checkout-mismatch", head=self.base)
        self.full("base-not-ancestor", base=later, head=self.base)

    def test_dirty_and_untracked_source_widen(self):
        self.write("untracked-helper.java", "class Shared {}")
        self.full("dirty-checkout")
        self.commit()
        self.write("src/main/java/Leaf.java", "not committed")
        self.full("dirty-checkout")

    def test_test_helpers_and_shared_members_widen(self):
        path = "src/test/java/example/OtherTest.java"
        for code in ("package example;\nclass Shared { static int helper() { return 1; } }\n",
                     java_fixture("OtherTest") + "\nclass Shared {}\n",
                     java_fixture("OtherTest", "@Test void test() {}\nint helper() { return 1; }"),
                     java_fixture("OtherTest", "@Test void test() {}\nprivate void local() {} int shared() { return 1; }"),
                     java_fixture("OtherTest", "private void local() { class Inner { @Test void nested() {} } } int shared() { return 1; }\n@Test void test() {}"),
                     java_fixture("OtherTest", "static class Shared {}\n@Test void test() {}")):
            with self.subTest(code=code):
                self.write(path, code)
                self.commit()
                self.full()

    def test_private_nested_records_are_not_shared_members(self):
        path = "src/test/java/example/OtherTest.java"
        body = "@Test void test() {}\nprivate record Row(String entry, long input, long expected) {}"
        self.write(path, java_fixture("OtherTest", body))
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(["example.OtherTest"], result["affected"]["junit"])
        self.write(path, java_fixture("OtherTest", body.replace("private record", "record")))
        self.commit()
        self.full("shared-test-member")
        self.write(path, java_fixture("OtherTest", body + "\nint shared = 1;"))
        self.commit()
        self.full("shared-test-member")

    def test_java_tests_select_their_class_but_widen_unknown_helper_grammar(self):
        self.write("src/test/java/example/JavaTest.java", "package example;\npublic class JavaTest { @Test public void test() {} public static void helper() {} }\n")
        self.commit()
        result = self.full("shared-test-member")
        self.assertIn("example.JavaTest", result["affected"]["junit"])

    def test_java_public_and_package_private_junit_methods_select_actual_classes(self):
        path = "src/test/java/example/NotTheClassName.java"
        for modifier in ("public ", ""):
            with self.subTest(modifier=modifier):
                self.write(path, "package example;\n" + modifier + "final class JavaTest {\n"
                           "  @org.junit.jupiter.api.Test " + modifier + "void boundary() {}\n"
                           "  @BeforeEach void setup() {}\n"
                           "  private static final Class<?> TYPE = String.class;\n"
                           "  private static int helper(int value) { return value; }\n"
                           "}\n")
                self.commit()
                result = self.plan()
                self.assertEqual("narrow", result["mode"], result["reasons"])
                self.assertEqual(["example.JavaTest"], result["affected"]["junit"])

    def test_java_members_cannot_hide_package_private_helpers_or_fields(self):
        path = "src/test/java/example/JavaTest.java"
        for member in ("int helper() { return 1; }", "static int shared = 1;",
                       "private void local() {} void shared() {}",
                       "public record Shared(int value) {}", "JavaTest() {}"):
            with self.subTest(member=member):
                self.write(path, "package example;\nclass JavaTest {\n"
                           "  @Test void works() {}\n  " + member + "\n}\n")
                self.commit()
                self.full("shared-test-member")

    def test_java_annotations_strings_nested_types_and_unknown_syntax_are_conservative(self):
        path = "src/test/java/example/JavaTest.java"
        source = ('package example;\nclass JavaTest {\n'
                  '  @ParameterizedTest @ValueSource(ints = {1, 2}) void value(int n) {}\n'
                  '  @TempDir Path temporary;\n'
                  '  private record Row(int value) {}\n'
                  '  private String text = "@Test class Fake { public void shared() {} }";\n'
                  '}\n')
        self.write(path, source)
        self.commit()
        self.assertEqual("narrow", self.plan()["mode"])
        for added, reason in (("\nrecord Shared(int x) {}\n", "shared-test-helper"),
                              ("\ninterface Shared {}\n", "shared-test-helper")):
            self.write(path, source + added)
            self.commit()
            self.full(reason)
        self.write(path, source.replace("private record Row(int value) {}", "unrecognized member syntax"))
        self.commit()
        self.full("shared-test-member")

    def test_java_inheritance_and_test_class_reuse_still_widen(self):
        path = "src/test/java/example/JavaTest.java"
        self.write(path, "package example;\nclass JavaTest implements Shared { @Test void works() {} }\n")
        self.commit()
        self.full("inherited-test-class")
        self.write(path, "package example;\nclass JavaTest { @Test void works() {} }\n")
        self.write("src/test/java/example/OtherTest.java", java_fixture("OtherTest", "@Test void usesJava() { new JavaTest(); }"))
        self.commit()
        self.full("test-class-used-as-helper")

    def test_java_multiple_classes_removed_class_and_qualified_nested_still_widen(self):
        path = "src/test/java/example/JavaTest.java"
        source = ("package example;\nclass JavaTest { @Test void works() {} }\n"
                  "class SecondTest { @Test void checks() {} }\n")
        self.write(path, source)
        self.commit()
        self.assertEqual(["example.JavaTest", "example.SecondTest"], self.plan()["affected"]["junit"])
        self.base = self.git("rev-parse", "HEAD")
        self.write(path, source.split("class SecondTest")[0])
        self.commit()
        self.full("removed-junit-class")
        self.base = self.git("rev-parse", "HEAD")
        self.write(path, "package example;\nclass JavaTest { @Test void works() {}\n"
                   "  @org.junit.jupiter.api.Nested private class Inner { @Test void checks() {} }\n}\n")
        self.commit()
        self.full("nested-test-class")

    def test_class_reused_as_helper_widens(self):
        self.write("src/test/java/example/ConsumerTest.java", java_fixture("ConsumerTest", "@Test void reads() { new OtherTest(); }"))
        self.base = self.commit()
        self.write("src/test/java/example/OtherTest.java", java_fixture("OtherTest", "@Test void changed() {}"))
        self.commit()
        self.full("test-class-used-as-helper")

    def test_python_helper_without_runner_and_imported_test_module_widen(self):
        self.write("bin/test-other.py", "def helper(): return 1\n")
        self.commit()
        self.full("python-test-helper-or-unknown-runner")
        self.write("bin/test-other.py", PYTHON_TEST)
        self.write("bin/consumer.py", "import importlib\nother = 'test-other'\n")
        self.base = self.commit()
        self.write("bin/test-other.py", PYTHON_TEST + "# changed\n")
        self.commit()
        self.full("python-test-used-as-helper")

    def test_historical_python_snapshots_are_data_not_executable_tests(self):
        path = "bench/experiments/example/evidence-x86_64/test-smoke.py"
        self.write(path, "historical failure snapshot, not executable")
        self.commit()
        result = self.full("unmapped-source-or-configuration")
        self.assertNotIn(path, result["python"]["files"])

    def test_python_lookalike_without_testcase_is_not_narrow(self):
        self.write("bin/test-other.py", PYTHON_TEST.replace("(unittest.TestCase)", ""))
        self.commit()
        self.full("python-test-helper-or-unknown-runner")

    def test_test_factory_is_selected_but_nested_and_inherited_classes_widen(self):
        path = "src/test/java/example/OtherTest.java"
        self.write(path, java_fixture("OtherTest", "@TestFactory java.util.List<?> dynamicCases() { return java.util.List.of(); }"))
        self.commit()
        self.assertEqual("narrow", self.plan()["mode"])
        self.write(path, "package example;\nclass OtherTest extends SharedBase { @Test void test() {} }\n")
        self.commit()
        self.full("inherited-test-class")
        self.write(path, java_fixture("OtherTest", "@Nested class Inner { @Test void test() {} }"))
        self.commit()
        self.full("nested-test-class")

    def test_policy_changes_and_invalid_missing_test_mappings_widen(self):
        self.policy["smoke"]["junit"].append("example.OtherTest")
        self.write(select.POLICY, json.dumps(self.policy))
        self.commit()
        self.assertEqual("narrow", self.plan()["mode"])
        for mutation in (dict(schema=True), dict(smoke=dict(junit=[], python=[])),
                         dict(smoke=dict(junit=["example.NoSuchTest"], python=["bin/test-smoke.py"])),
                         dict(leafSources={"missing.java": dict(junit=["example.LeafTest"], python=[])})):
            policy = copy.deepcopy(self.policy); policy.update(mutation)
            self.write(select.POLICY, json.dumps(policy))
            self.commit()
            self.full("invalid-selection-policy")

    def test_policy_hash_covers_script_and_configuration(self):
        before = self.plan()["policySha256"]
        script = self.repo / select.SCRIPT
        script.write_text(script.read_text() + "\n# harmless change\n")
        self.commit()
        # This unit invokes the original module against a modified checkout;
        # actual CI executes the changed selector from that checkout.
        result = self.full("executed-selector-mismatch")
        self.assertNotEqual(before, result["policySha256"])

    def test_deleted_selected_file_and_symlink_never_return_runnable_narrow(self):
        path = self.repo / "src/test/java/example/SmokeTest.java"
        path.unlink()
        result = self.full("selected-test-file-missing-or-symlinked")
        self.assertFalse(result["runnable"])
        path.symlink_to("OtherTest.java")
        self.commit()
        self.full("nonregular-changed-path")

    def test_empty_and_corrupt_inventory_cannot_be_an_empty_success(self):
        for path in (self.repo / "src/test").rglob("*.java"):
            path.unlink()
        self.commit()
        result = self.full("empty-junit-inventory")
        self.assertFalse(result["runnable"])

    def test_nul_parser_rejects_truncation_and_preserves_weird_paths(self):
        self.assertEqual([dict(status="R100", paths=["a\nb", "c\td"])],
                         select.paths_from_diff(b"R100\x00a\nb\x00c\td\x00"))
        for raw in (b"M\x00path", b"R100\x00one\x00", b"Q\x00path\x00", b"M\x00\x00"):
            with self.subTest(raw=raw), self.assertRaises(select.SelectionError):
                select.paths_from_diff(raw)

    def test_cli_uses_json_and_missing_base_full_not_argument_injection(self):
        result = subprocess.run([sys.executable, select.__file__, "--repo", str(self.repo), "--base=--help"],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(result.stdout)
        self.assertEqual("full", plan["mode"])
        self.assertIn("missing-base", {r["code"] for r in plan["reasons"]})

    def test_every_reviewed_family_selects_its_complete_union_without_budget_truncation(self):
        policy = json.loads(Path(__file__).with_name("fast-tests.json").read_text())
        self.write(select.POLICY, json.dumps(policy))
        groups = [policy["smoke"], *policy["leafSources"].values(), *policy["owners"].values(),
                  *policy["primopFamilies"].values(), *policy["automation"].values()]
        for name in {name for group in groups for name in group["junit"]}:
            package, short = name.rsplit(".", 1)
            self.write("src/test/java/" + name.replace(".", "/") + ".java",
                       java_fixture(short).replace("package example", "package " + package))
        for path in {path for group in groups for path in group["python"]}:
            self.write(path, PYTHON_TEST)
        for path in policy["leafSources"]:
            self.write(path, "// synthetic family source: selection only\n")
        previous = self.commit()
        for path, group in policy["leafSources"].items():
            with self.subTest(path=path):
                self.write(path, "// changed synthetic family source: no JVM execution\n")
                current = self.commit()
                result = self.plan(base=previous)
                self.assertEqual("narrow", result["mode"], result)
                self.assertEqual([path], result["changedPaths"])
                self.assertEqual(sorted(set(group["junit"]) | set(policy["smoke"]["junit"])),
                                 result["junit"]["classes"])
                self.assertEqual(sorted(set(group["python"]) | set(policy["smoke"]["python"])),
                                 result["python"]["files"])
                self.assertEqual(sorted(group["junit"]), result["affected"]["junit"])
                self.assertEqual(sorted(group["python"]), result["affected"]["python"])
                previous = current

    def test_warning_only_fixture_changes_select_pr80_consumers(self):
        policy = json.loads(Path(__file__).with_name("fast-tests.json").read_text())
        self.write(select.POLICY, json.dumps(policy))
        groups = [policy["smoke"], *policy["leafSources"].values(), *policy["owners"].values(),
                  *policy["primopFamilies"].values(), *policy["automation"].values()]
        for name in {name for group in groups for name in group["junit"]}:
            package, short = name.rsplit(".", 1)
            self.write("src/test/java/" + name.replace(".", "/") + ".java",
                       java_fixture(short).replace("package example", "package " + package))
        for path in {path for group in groups for path in group["python"]}:
            self.write(path, PYTHON_TEST)
        for path in policy["leafSources"]:
            self.write(path, "// synthetic production source\n")
        changed = ["test/fixtures/compiler/CBVCoercionAudit.hs",
                   "test/fixtures/compiler/DataToTagAudit.hs",
                   "test/fixtures/compiler/MutableByteArraySizeAudit.hs",
                   "examples/THC/Unboxed8Arrays.hs",
                   "examples/THC/Unboxed16Arrays.hs",
                   "examples/THC/Unboxed32Arrays.hs"]
        for path in changed:
            self.write(path, "fixture before\n")
        base = self.commit()
        for path in changed:
            self.write(path, "fixture after\n")
        self.commit()
        result = self.plan(base=base)
        self.assertEqual("narrow", result["mode"], result["reasons"])
        self.assertEqual([], result["reasons"])
        self.assertEqual(sorted(changed), result["changedPaths"])
        expected = {"thc.RealCoreEntryContractTest", "thc.runtime.ScalarLexicalProofTest",
                    "thc.runtime.BoxedLexicalProofTest", "thc.runtime.ScalarPrimitiveSignatureTest",
                    "thc.runtime.DataToTagTest", "thc.runtime.MutableByteArraySizeTest",
                    "thc.runtime.Int8ArrayNativeTest", "thc.runtime.Int16ArrayNativeTest",
                    "thc.runtime.Int16BoundaryCompilationTest",
                    "thc.runtime.Int32ArrayNativeTest", "thc.runtime.InterfaceCoreNativeTest"}
        self.assertEqual(sorted(expected), result["affected"]["junit"])
        self.assertEqual(14, result["junit"]["count"])  # Eleven affected + three smoke.
        self.assertEqual(sorted({"bin/test-core-data-tags.py", "bin/test-core-bytearrays.py"}), result["affected"]["python"])


class PrimitiveFamilyPolicyTest(unittest.TestCase):
    integer_vector_nodes = (
        "VectorPack",
        "VectorUnpack",
        "VectorOperation",
        "Vector32Pack",
        "Vector32Unpack",
        "Vector32Operation",
        "Vector16Pack",
        "Vector16Unpack",
        "Vector16Operation",
        "Vector8Pack",
        "Vector8Unpack",
        "Vector8Operation",
        "VectorWord32Pack",
        "VectorWord32Unpack",
        "VectorWord32Operation",
        "VectorWord16Pack",
        "VectorWord16Unpack",
        "VectorWord16Operation",
        "VectorWord8Pack",
        "VectorWord8Unpack",
        "VectorWord8Operation",
    )
    floating_vector_nodes = (
        "VectorFloatPack",
        "VectorFloatUnpack",
        "VectorFloatOperation",
        "VectorDoublePack",
        "VectorDoubleUnpack",
        "VectorDoubleOperation",
        "VectorFloat8Fused",
        "VectorDouble4Fused",
        "VectorFloat16Fused",
        "VectorDouble8Fused",
    )

    @classmethod
    def setUpClass(cls):
        cls.root = Path(__file__).resolve().parents[2]
        cls.policy = json.loads(Path(__file__).with_name("fast-tests.json").read_text())
        cls.families = cls.policy["leafSources"]
        cls.classes = {name for path in (cls.root / "src/test").rglob("*")
                       if path.suffix == ".java"
                       for name in select.junit_info(path.read_text())[0]}

    def family(self, name):
        matches = [group for path, group in self.families.items() if Path(path).stem == name]
        self.assertEqual(1, len(matches), name)
        return matches[0]

    def test_converted_java_model_keeps_junit_and_shared_oracle_inventory(self):
        self.assertIn("thc.ScalarPrimopModelTest", self.classes)
        for helper in ("ScalarPrimopModel", "NumericPrimopCoreEvidence"):
            path = "src/test/java/thc/" + helper + ".java"
            self.assertTrue((self.root / path).is_file())
            self.assertTrue(self.policy["owners"][path]["junit"])


    def test_every_mapping_target_is_a_real_test_and_each_path_is_explicit(self):
        self.assertEqual({"RubbishLiterals", "CoreMemoryCopyForeign", "MemcpyExpression", "MemmoveExpression", "CoreFileWait", "WaitFileDescriptor",
                           "CoreStringRtsForeign", "StringRtsOp", "StringRtsExpression", "GuestEnvironment", "CoreEnvironmentForeign", "GuestArguments", "CoreRtsArgumentsForeign", "EnvironmentOp", "EnvironmentExpression", "RtsArgumentsOp", "RtsArgumentsExpression", "CoreCurrentCCS", "ManagedAddressOrder", "CompareManagedAddress", "CompareOrderedManagedAddress", "GetCurrentCCS", "AddressToInt", "IntToAddress", "SubtractManagedAddress", "RemainderManagedAddress", "AtomicAddressOp", "AtomicAddressExpression", "NativeNarrowAtomic", "BitPrimitives", "RawBitCasts", "FloatingPrimitives", "FloatingAddresses", "FloatingAddressOp", "FloatingAddressExpression", "ManagedSmallArray", "SmallArrayStorage", "SmallArrayOp", "ManagedMutVar", "ModifiedMutVar", "MutVarModifySite", "MutVarOp", "ManagedNativeAllocations", "StablePointers", "StablePointerToken", "CoreStablePointers", "StablePointerOp", "MakeStablePointer", "DereferenceStablePointer", "EqualStablePointers", "FreeStablePointer", "CoreSharedCAFStores", "SharedCAFStore", "SharedCAFStoreExpression", "ManagedWeaks", "CoreMainThreadForeign", "CoreBoundThreadForeign",
                         "VectorAddressExpression", "VectorIntegerDivision", "FloatDecodeExpression", "CoreDataLabels", "FileWaitPrimitives", "CoreRtsShutdown", "AddressArrayCopyOp", "AddressToByteArrayExpression", "ByteArrayToAddressExpression", "AtomicIntArrayOp", "AtomicIntArrayExpression", "ThreadObservation", "ManagedSTM", "ManagedTVar", "STMRetry", "STMConflict", "ManagedCompacts", "CompactImages", "HeapAddresses", "CompactImageOp", "CompactImageExpression", "BoundThreadSupport", "RegisterMainThread", "CpuAffinityQuery", "STMCall", "STMExpression", "STMOp", "STMRestart", "PrefetchExpression", "TraceExpression", "TraceOp", "GhcBCO", "GhcInstruction", "GhcBCORoot", "GhcBCOExpression", "CoreCpuAffinity", "NativeEpoll", "NativeEventWait", "AstStackScope", "AstStackSpill", "AstStackContinuation", "AstChildSuspension", "AstStacks"} |
                         {"WeakResult", "WeakExpression", "WeakOp", "MainThreadWeakKey", "ManagedCompact", "CompactCopyNode", "CompactOp", "CompactExpression",
                          "ShutdownRuntime", "RtsShutdownOp", "GuestShutdown", "CoreThreadObservation"} |
                         set(self.integer_vector_nodes + self.floating_vector_nodes),
                         {Path(path).stem for path in self.families})
        for path, group in self.families.items():
            with self.subTest(path=path):
                self.assertTrue((self.root / path).is_file())
                self.assertFalse(any(c in path for c in "*?[]"))
                self.assertTrue(group["junit"])
                self.assertEqual(len(group["junit"]), len(set(group["junit"])))
                self.assertEqual(len(group["python"]), len(set(group["python"])))
                self.assertLessEqual(set(group["junit"]), self.classes)
                for test in group["python"]:
                    self.assertTrue((self.root / test).is_file(), test)
                    self.assertTrue(select.python_test(test), test)

    def test_bound_thread_query_leaf_keeps_structural_and_foreign_audit_controls(self):
        self.assertEqual({"junit": ["thc.runtime.CoreBoundThreadForeignTest", "thc.runtime.ThreadSchedulingTest"],
                          "python": ["bin/test-audit-core.py"]},
                         self.family("CoreBoundThreadForeign"))

    def test_thread_inventory_lowering_and_example_keep_native_and_structural_owners(self):
        self.assertEqual({"thc.runtime.GuestThreadInventoryTest", "thc.runtime.ThreadInventoryNativeTest"},
                         set(self.family("ThreadObservation")["junit"]))
        for path in ("examples/ThreadInventory.hs", "test/fixtures/compiler/ThreadInventoryNative.hs",
                     "test/fixtures/compiler/CallbackIdentityNative.hs", "test/fixtures/compiler/callback-identity.c",
                     "test/haskell-fixtures/ThreadInventoryFixtures.hs", "test/haskell-fixtures/Main.hs"):
            self.assertIn("thc.runtime.ThreadInventoryNativeTest", self.policy["owners"][path]["junit"])

    def test_native_malloc_source_and_composite_owners_select_both_consumers(self):
        malloc = "thc.runtime.NativeMallocTest"
        addresses = "thc.runtime.NativeAddressTest"
        buffers = "thc.runtime.NativeFileBuffersTest"
        self.assertEqual([buffers, malloc, "thc.runtime.AtomicAddressTest"], self.family("ManagedNativeAllocations")["junit"])
        owners = self.policy["owners"]
        self.assertEqual([buffers, malloc], owners["src/main/java/thc/runtime/NativeMallocAllocation.java"]["junit"])
        self.assertEqual({malloc, addresses},
                         set(owners["test/haskell-fixtures/NativeAddressFixtures.hs"]["junit"]))
        self.assertEqual({malloc, addresses},
                         set(owners["src/main/java/thc/runtime/NativeAddresses.java"]["junit"]))
        for path in ("test/fixtures/compiler/NativeMallocNative.hs",
                     "src/test/resources/core/original-malloc-descriptors.json"):
            self.assertEqual([malloc], owners[path]["junit"])
        self.assertIn(malloc, owners["test/haskell-fixtures/Main.hs"]["junit"])

    def test_saved_termios_owners_select_pointer_and_original_fixture_controls(self):
        owners = self.policy["owners"]
        original = "thc.runtime.OriginalSavedTermiosTest"
        self.assertEqual({"thc.runtime.SavedTermiosTest", original},
                         set(owners["src/main/java/thc/runtime/SavedTermios.java"]["junit"]))
        for fixture in ("Audit", "Native"):
            self.assertEqual([original], owners[f"test/fixtures/compiler/OriginalSavedTermios{fixture}.hs"]["junit"])
        for path in ("test/haskell-fixtures/OriginalTermiosFixtures.hs", "test/haskell-fixtures/Main.hs",
                     "src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java", "src/main/java/thc/runtime/OriginalStdioExpression.java"):
            self.assertIn(original, owners[path]["junit"])

    def test_fixture_owners_match_the_preparation_manifest(self):
        fixture = json.loads(Path(__file__).with_name("fast-fixtures.json").read_text())
        owners = self.policy["owners"]
        native_only = {"examples/NativeOracle.hs", "examples/THC/MapWorkload.hs",
                       "bin/native-oracle.sh"}
        source_groups = {}
        for group in fixture["groups"].values():
            for path in group["sources"]:
                source_groups.setdefault(path, set()).update(group["junit"])
        for name, group in fixture["groups"].items():
            for path in group["sources"]:
                with self.subTest(group=name, path=path):
                    self.assertTrue((self.root / path).is_file(), path)
                    if path not in owners:
                        # A transitive source without a narrow owner still widens
                        # to the complete suite when changed.
                        continue
                    actual = set(owners[path]["junit"])
                    self.assertTrue(actual & set(group["junit"]))
                    self.assertLessEqual(actual, source_groups[path])
                    if path in native_only:
                        self.assertEqual("runtime-core-native", name)
                        self.assertLessEqual({"thc.RuntimeTest", "thc.BytecodeBackendTest"}, actual)
        self.assertEqual({"thc.runtime.BitPrimopsTest", "thc.IntegerPrimopsTest",
                          "thc.SignedNarrowPrimopsTest", "thc.runtime.AddressIdentityNativeTest",
                          "thc.runtime.ManagedAddressStorageTest", "thc.runtime.ManagedAllocationTest"},
                         set(owners["src/test/java/thc/PrimopTestContext.java"]["junit"]))

    def test_tcsetattr_sources_select_the_original_native_comparison(self):
        for path in ("test/fixtures/compiler/OriginalTcsetattrAudit.hs", "test/fixtures/compiler/OriginalTcsetattrNative.hs",
                     "test/haskell-fixtures/OriginalTcsetattrFixtures.hs", "src/main/c/native-file-api.c",
                     "src/main/java/thc/runtime/NativeFileProvider.java", "src/main/java/thc/runtime/NativeOpenRequest.java", "src/main/java/thc/runtime/NativeFileResource.java", "src/main/java/thc/runtime/OpenedNativeFile.java",
                     "src/main/java/thc/runtime/ManagedStdio.java", "src/main/java/thc/runtime/ManagedFiles.java",
                     "src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java", "src/main/java/thc/runtime/OriginalStdioExpression.java",
                     "test/haskell-fixtures/Main.hs"):
            self.assertIn("thc.runtime.OriginalTcsetattrTest", self.policy["owners"][path]["junit"], path)
    def test_sigprocmask_sources_select_the_platform_thread_controls(self):
        for path in ("test/fixtures/compiler/OriginalSigprocmaskAudit.hs", "test/fixtures/compiler/OriginalSigprocmaskNative.hs",
                     "test/haskell-fixtures/OriginalSigprocmaskFixtures.hs", "src/main/c/native-signal-api.c",
                     "src/main/java/thc/runtime/ManagedSignalMask.java", "src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java",
                     "src/main/java/thc/runtime/OriginalStdioExpression.java", "test/haskell-fixtures/Main.hs"):
            self.assertIn("thc.runtime.OriginalSigprocmaskTest", self.policy["owners"][path]["junit"], path)

    def test_tcgetattr_sources_select_the_original_native_comparison(self):
        for path in ("test/fixtures/compiler/OriginalTcgetattrAudit.hs", "test/fixtures/compiler/OriginalTcgetattrNative.hs",
                     "test/haskell-fixtures/OriginalTcgetattrFixtures.hs", "src/main/c/native-file-api.c",
                     "src/main/java/thc/runtime/NativeFileProvider.java", "src/main/java/thc/runtime/NativeOpenRequest.java", "src/main/java/thc/runtime/NativeFileResource.java", "src/main/java/thc/runtime/OpenedNativeFile.java",
                     "src/main/java/thc/runtime/ManagedStdio.java", "src/main/java/thc/runtime/ManagedFiles.java",
                     "src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java", "src/main/java/thc/runtime/OriginalStdioExpression.java",
                     "test/haskell-fixtures/Main.hs"):
            self.assertIn("thc.runtime.OriginalTcgetattrTest", self.policy["owners"][path]["junit"], path)

    def test_unlinkat_sources_select_the_genuine_safe_call_comparison(self):
        for path in ("test/fixtures/compiler/OriginalUnlinkAtAudit.hs",
                     "test/haskell-fixtures/OriginalUnlinkAtFixtures.hs",
                     "src/test/java/thc/runtime/OriginalUnlinkAtTest.java",
                     "test/haskell-fixtures/OriginalPosixStatFixtures.hs",
                     "src/main/c/native-file-api.c", "src/main/c/stdio-abi-probe.c",
                     "src/main/java/thc/runtime/NativeFileProvider.java",
                     "src/main/java/thc/runtime/NativeOpenRequest.java", "src/main/java/thc/runtime/NativeFileResource.java", "src/main/java/thc/runtime/OpenedNativeFile.java",
                     "src/main/java/thc/runtime/ManagedStdio.java",
                     "src/main/java/thc/runtime/ManagedFiles.java",
                     "src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java",
                     "src/main/java/thc/runtime/OriginalStdioExpression.java",
                     "test/haskell-fixtures/Main.hs"):
            self.assertIn("thc.runtime.OriginalUnlinkAtTest", self.policy["owners"][path]["junit"], path)
        self.assertIn("thc.runtime.StdioHostAbiTest",
                      self.policy["owners"]["src/main/c/stdio-abi-probe.c"]["junit"])

    def test_fstatat_sources_select_the_genuine_safe_call_comparison(self):
        for path in ("test/fixtures/compiler/OriginalFstatAtAudit.hs",
                     "test/haskell-fixtures/OriginalFstatAtFixtures.hs",
                     "src/test/java/thc/runtime/OriginalFstatAtTest.java",
                     "test/haskell-fixtures/OriginalPosixStatFixtures.hs",
                     "src/main/c/native-file-api.c", "src/main/c/stdio-abi-probe.c",
                     "src/main/java/thc/runtime/NativeFileProvider.java",
                     "src/main/java/thc/runtime/NativeOpenRequest.java", "src/main/java/thc/runtime/NativeFileResource.java", "src/main/java/thc/runtime/OpenedNativeFile.java",
                     "src/main/java/thc/runtime/ManagedStdio.java",
                     "src/main/java/thc/runtime/ManagedFiles.java",
                     "src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java",
                     "src/main/java/thc/runtime/OriginalStdioExpression.java",
                     "test/haskell-fixtures/Main.hs"):
            self.assertIn("thc.runtime.OriginalFstatAtTest", self.policy["owners"][path]["junit"], path)
        self.assertIn("thc.runtime.StdioHostAbiTest",
                      self.policy["owners"]["src/main/c/stdio-abi-probe.c"]["junit"])

    def test_current_directory_sources_select_the_genuine_context_directory_comparison(self):
        for path in ("test/fixtures/compiler/OriginalCurrentDirectoryAudit.hs",
                     "test/haskell-fixtures/OriginalCurrentDirectoryFixtures.hs",
                     "src/test/java/thc/runtime/OriginalCurrentDirectoryTest.java",
                     "test/haskell-fixtures/OriginalPosixStatFixtures.hs",
                     "src/main/c/native-file-api.c", "src/main/c/stdio-abi-probe.c",
                     "src/main/java/thc/runtime/NativeDirectoryOwner.java",
                     "src/main/java/thc/NativeFileSystem.java",
                     "src/main/java/thc/runtime/NativeFileProvider.java",
                     "src/main/java/thc/runtime/NativeOpenRequest.java", "src/main/java/thc/runtime/NativeFileResource.java", "src/main/java/thc/runtime/OpenedNativeFile.java",
                     "src/main/java/thc/runtime/ManagedStdio.java",
                     "src/main/java/thc/runtime/ManagedFiles.java",
                     "src/main/java/thc/runtime/CoreOriginalStdio.java", "src/main/java/thc/runtime/OriginalStdioOp.java",
                     "src/main/java/thc/runtime/OriginalStdioExpression.java",
                     "test/haskell-fixtures/Main.hs"):
            self.assertIn("thc.runtime.OriginalCurrentDirectoryTest", self.policy["owners"][path]["junit"], path)
        self.assertIn("thc.runtime.StdioHostAbiTest",
                      self.policy["owners"]["src/main/c/stdio-abi-probe.c"]["junit"])

    def test_directory_filesystem_owner_selects_lifetime_and_original_controls(self):
        for path in ("src/main/java/thc/runtime/NativeDirectoryOwner.java",
                     "src/main/java/thc/NativeFileSystem.java", "src/main/java/thc/NativeIO.java",
                     "src/main/java/thc/runtime/NativeFileProvider.java", "src/main/c/native-file-api.c",
                     "src/main/c/native-directory-api.c"):
            self.assertIn("thc.runtime.NativeDirectoryFileSystemTest", self.policy["owners"][path]["junit"], path)
            self.assertIn("thc.runtime.OriginalCurrentDirectoryTest", self.policy["owners"][path]["junit"], path)

    def test_array_core_helper_selects_all_consuming_suites(self):
        group = self.policy["owners"]["src/test/java/thc/runtime/ArrayCoreEvidence.java"]
        consumers = set()
        for path in (self.root / "src/test").glob("*/thc/runtime/*"):
            if path.suffix != ".java":
                continue
            source = path.read_text()
            if path.stem != "ArrayCoreEvidence" and "ArrayCoreEvidence(" in source:
                consumers.update(select.junit_info(source)[0])
        self.assertEqual(45, len(consumers))
        self.assertIn("thc.runtime.SumResultTest", consumers)
        self.assertIn("thc.runtime.TupleInputNativeTest", consumers)
        self.assertIn("thc.runtime.ManagedWeakTest", consumers)
        self.assertIn("thc.runtime.OriginalHandleReadinessNativeTest", consumers)
        self.assertIn("thc.runtime.OriginalTermiosTest", consumers)

        self.assertIn("thc.runtime.Explicit64ArrayTest", consumers)
        self.assertIn("thc.runtime.OriginalPathStatTest", consumers)
        self.assertIn("thc.runtime.OriginalPathModeTest", consumers)
        self.assertIn("thc.runtime.OriginalPathLinkTest", consumers)
        self.assertIn("thc.runtime.OriginalPathAccessTest", consumers)
        self.assertIn("thc.runtime.OriginalUnlinkAtTest", consumers)
        self.assertIn("thc.runtime.OriginalFstatAtTest", consumers)
        self.assertIn("thc.runtime.OriginalCurrentDirectoryTest", consumers)
        self.assertIn("thc.runtime.OriginalDirectoryStreamsTest", consumers)
        self.assertIn("thc.runtime.OriginalDirectoryPathsTest", consumers)
        self.assertIn("thc.runtime.UnalignedScalarMemoryTest", consumers)
        self.assertIn("thc.runtime.AlignedScalarMemoryTest", consumers)
        self.assertIn("thc.runtime.IntegerCompletionTest", consumers)
        # The isolated boundary control delegates to the native test's genuine
        # two-root fixture helper, so it also consumes ArrayCoreEvidence.
        self.assertEqual(consumers | {"thc.runtime.Int16BoundaryCompilationTest"}, set(group["junit"]))
        self.assertEqual([], group["python"])

    def test_int16_boundary_control_tracks_its_helper_and_genuine_inputs(self):
        expected = {"thc.runtime.Int16ArrayNativeTest", "thc.runtime.Int16BoundaryCompilationTest"}
        for path in ("src/test/java/thc/runtime/Int16ArrayNativeTest.java",
                     "src/test/java/thc/runtime/ArrayCoreEvidence.java",
                     "test/fixtures/compiler/Int16ArrayAudit.hs",
                     "examples/THC/Unboxed16Arrays.hs", "test/haskell-fixtures/Main.hs"):
            self.assertTrue(expected <= set(self.policy["owners"][path]["junit"]), path)
        self.assertTrue(expected <= self.classes)

    def test_stack_info_layout_helper_selects_all_consumers(self):
        group = self.policy["owners"]["src/test/java/thc/runtime/ManagedStackInfoImageTest.java"]
        consumers = set()
        for path in (self.root / "src/test").glob("*/thc/runtime/*"):
            if path.suffix != ".java":
                continue
            source = path.read_text()
            if "StackInfoTestLayout" in source:
                consumers.update(select.junit_info(source)[0])
        self.assertEqual({"thc.runtime.ManagedStackInfoImageTest", "thc.runtime.OriginalStackInfoCallTest",
                          "thc.runtime.OriginalStackDecoderCallTest", "thc.runtime.RtsFlagsTest",
                          "thc.runtime.ReturnedForeignPointerTest"}, consumers)
        self.assertEqual(consumers, set(group["junit"]))
        self.assertEqual([], group["python"])

    def test_thread_inventory_evidence_helper_selects_all_consumers(self):
        group = self.policy["owners"]["src/test/java/thc/runtime/ThreadInventoryCoreEvidence.java"]
        consumers = set()
        for path in (self.root / "src/test").glob("*/thc/runtime/*"):
            if path.suffix != ".java":
                continue
            source = path.read_text()
            if "ThreadInventoryCoreEvidence" in source:
                consumers.update(select.junit_info(source)[0])
        self.assertIn("thc.runtime.ProcessSignalsTest", consumers)
        self.assertEqual(consumers, set(group["junit"]))
        self.assertEqual([], group["python"])

    def test_control_and_owner_targets_exist_and_are_runnable(self):
        checked_python = set()
        for group in [*self.policy["owners"].values(), *self.policy["primopFamilies"].values(),
                      *self.policy["automation"].values()]:
            self.assertLessEqual(set(group["junit"]), self.classes)
            for suite in group.get("haskell", []):
                self.assertIn(suite, select.HASKELL_TESTS)
                self.assertTrue((self.root / select.HASKELL_TESTS[suite]).is_file())
                self.assertIn("test-suite " + suite, (self.root / "thc.cabal").read_text())
            for path in group["python"]:
                if path in checked_python:
                    continue
                checked_python.add(path)
                self.assertTrue((self.root / path).is_file(), path)
                source = (self.root / path).read_text()
                self.assertTrue(select.standalone_python_test(source), path)

    def test_fast_automation_sources_have_control_owners(self):
        automation = self.policy["automation"]
        for path in (".github/scripts/fast_ci.py", ".github/scripts/fast_inputs.py",
                     ".github/scripts/fast_fixtures.py", ".github/scripts/fast_select.py",
                     ".github/scripts/fast-fixtures.json", ".github/scripts/fast-tests.json",
                     ".github/scripts/test_fast_ci.py", ".github/scripts/test_fast_inputs.py",
                     ".github/scripts/test_fast_fixtures.py", ".github/scripts/test_fast_select.py",
                     ".github/workflows/fast.yml"):
            with self.subTest(path=path):
                self.assertIn(path, automation)
                self.assertIn(".github/scripts/test_fast_select.py", automation[path]["python"])

    def test_floating_dispatch_retains_hidden_native_sum_tuple_memory_and_bitcast_consumers(self):
        floating = self.family("FloatingPrimitives")
        bitcasts = self.family("RawBitCasts")
        self.assertEqual(floating["python"], bitcasts["python"])
        self.assertEqual(set(floating["junit"]) | {"thc.runtime.FloatingAddressTest"}, set(bitcasts["junit"]))
        self.assertEqual({"thc.SumLayoutMetadataTest", *{"thc.runtime." + name for name in (
            "BytecodeTypedTupleInputTest", "CompiledThunkRetentionTest", "DoubleArrayNativeTest", "DoubleArrayTest",
            "DoubleVectorMemoryProofTest", "DoubleVectorStorageTest", "FloatArrayTest",
            "FloatVectorMemoryProofTest", "FloatVectorStorageTest", "FloatWordArrayNativeTest",
            "FloatingRemainderTest", "FloatingPrimitiveTest", "FloatingTupleTest", "FusedFloatingTest", "WordFloatingTest", "ScalarBitCastTest", "SimdDoubleByteArrayTest",
            "SimdDoubleVectorTest", "SimdFloatByteArrayTest", "SimdFloatVectorTest", "SimdFloatFmaTest", "SimdWideFloatFmaTest", "SqrtPrimitiveTest",
            "SumProtocolTest", "SumResultTest", "TupleInputNativeTest", "TypedInputScalarSourceTest")}},
                         set(floating["junit"]))
        self.assertLessEqual({"bin/test-core-sums.py",
                             "bin/test-sum-layout.py", "bin/test-tuple-inputs.py",
                             "bin/test-core-double-vector-memory.py", "bin/test-core-float-vector-memory.py"},
                            set(floating["python"]))
        fixtures = json.loads(Path(__file__).with_name("fast-fixtures.json").read_text())
        prepared = {name for group in fixtures["groups"].values() for name in group["junit"]}
        self.assertLessEqual(set(floating["junit"]), prepared | set(fixtures["fixtureFreeJunit"]))

    def test_proxy_void_producer_and_native_sources_select_only_their_consumer(self):
        owners = self.policy["owners"]
        for path in ("test/haskell-fixtures/ProxyVoidFixtures.hs",
                     "test/fixtures/compiler/ProxyVoidAudit.hs", "test/fixtures/compiler/ProxyVoidAuditNative.hs",
                     "test/fixtures/compiler/ProxyVoidPredicate.hs", "src/test/java/thc/runtime/ProxyVoidTest.java"):
            self.assertEqual({"junit": ["thc.runtime.ProxyVoidTest"], "python": []}, owners[path])
        self.assertIn("thc.runtime.ProxyVoidTest", owners["test/haskell-fixtures/Main.hs"]["junit"])

    def test_floating_haskell_producers_and_main_keep_their_consumers(self):
        owners = self.policy["owners"]
        for path in ("test/haskell-fixtures/BigNatLiteralFixtures.hs",
                     "test/fixtures/compiler/BigNatLiteralAudit.hs", "test/fixtures/compiler/BigNatLiteralAuditNative.hs",
                     "src/test/java/thc/runtime/BigNatLiteralTest.java"):
            self.assertEqual(["thc.runtime.BigNatLiteralTest"], owners[path]["junit"])
            self.assertEqual(["bin/test-audit-core.py"], owners[path]["python"])
        self.assertIn("thc.runtime.BigNatLiteralTest", owners["test/haskell-fixtures/Main.hs"]["junit"])
        for producer, consumer in (("FusedFloatingFixtures", "FusedFloatingTest"),
                                   ("ScalarBitCastFixtures", "ScalarBitCastTest"),
                                   ("SimdFloatFmaFixtures", "SimdFloatFmaTest"),
                                   ("SimdWideFloatFmaFixtures", "SimdWideFloatFmaTest"),
                                   ("WordFloatingFixtures", "WordFloatingTest")):
            with self.subTest(producer=producer):
                junit = "thc.runtime." + consumer
                expected = {junit} | ({"thc.runtime.SimdWideFloatFmaTest"} if producer == "SimdFloatFmaFixtures" else set())
                self.assertEqual(expected, set(owners["test/haskell-fixtures/" + producer + ".hs"]["junit"]))
                self.assertIn(junit, owners["test/haskell-fixtures/Main.hs"]["junit"])
        # FMA shares these real native/exported fixtures with the earlier
        # floating suite; adding its producer must not replace their owners.
        for fixture in ("FloatingAudit", "FloatingAuditNative"):
            self.assertEqual({"thc.runtime.CompiledThunkRetentionTest", "thc.runtime.FloatingPrimitiveTest",
                              "thc.runtime.FusedFloatingTest"},
                             set(owners["test/fixtures/compiler/" + fixture + ".hs"]["junit"]))

    def test_integer_simd_producer_and_shared_model_keep_every_consumer(self):
        expected = {"thc.runtime." + name for name in ("IntegerSimdModelTest", "SimdInt8VectorTest",
                    "SimdInt16VectorTest", "SimdWord16VectorTest", "SimdWord32VectorTest")}
        for path in ("test/haskell-fixtures/IntegerSimdFixtures.hs",
                     "src/test/java/thc/runtime/IntegerSimdModelTest.java",
                     "src/test/java/thc/runtime/IntegerSimdModel.java"):
            self.assertEqual(expected, set(self.policy["owners"][path]["junit"]))
            self.assertEqual(["bin/test-core-vectors.py"], self.policy["owners"][path]["python"])
        self.assertLessEqual(expected, set(self.policy["owners"]["test/haskell-fixtures/Main.hs"]["junit"]))
        self.assertIn("    IntegerSimdFixtures", (self.root / "thc.cabal").read_text())
        callers = [(self.root / path).read_text() for path in ("bin/prepare-tests.sh",
                   "bin/test-core-vectors.py", "build.gradle", ".github/workflows/build.yml")]
        for family in ("int8x16", "int16x8", "word16x8", "word32x4"):
            for name in (f"prepare-{family}-audit.py", f"{family}_model.py", f"test-{family}-model.py"):
                self.assertFalse((self.root / "bin" / name).exists(), name)
                for caller in callers:
                    self.assertNotIn(name.removesuffix(".py"), caller)

    def test_grouped_vectors_keep_the_union_of_all_former_family_consumers(self):
        expected = {
            "IntegerVectorPrimitives": ["IntegerSimdModelTest", "SimdVectorTest", "SimdInt8VectorTest", "SimdInt16VectorTest",
                "SimdWord8VectorTest", "SimdWord16VectorTest", "SimdInt32VectorTest", "SimdInt32MultiplyTest",
                "SimdInt32ByteArrayTest", "Int32VectorMemoryProofTest", "Int32VectorStorageTest",
                "SimdWord32VectorTest", "SimdWord32ByteArrayTest", "Word32VectorMemoryProofTest",
                "Word32VectorStorageTest", "Simd128ArrayNativeTest", "Simd128ArrayProofTest",
                "SimdArithmeticTest", "SimdWideArrayNativeTest", "SimdWideArrayProofTest",
                "Simd128AddressNativeTest", "Simd128AddressTest"],
            "FloatingVectorPrimitives": ["SimdFloatVectorTest", "SimdFloatFmaTest", "SimdWideFloatFmaTest", "SimdFloatByteArrayTest",
                "FloatVectorMemoryProofTest", "FloatVectorStorageTest", "SimdDoubleVectorTest",
                "SimdDoubleByteArrayTest", "DoubleVectorMemoryProofTest", "DoubleVectorStorageTest",
                "SimdWideArrayNativeTest", "SimdWideArrayProofTest", "SimdArithmeticTest"],
        }
        python = {
            "IntegerVectorPrimitives": ["core-vector-memory", "core-vectors", "core-word32-vector-memory",
                "int32x4-multiply-model", "word8x16-model"],
            "FloatingVectorPrimitives": ["core-double-vector-memory", "core-float-vector-memory", "core-vectors",
                "doublex2-model", "floatx4-model"],
        }
        nodes = {"IntegerVectorPrimitives": self.integer_vector_nodes,
                 "FloatingVectorPrimitives": self.floating_vector_nodes}
        for name, tests in expected.items():
            for node in nodes[name]:
                with self.subTest(name=name, node=node):
                    group = self.families["src/main/java/thc/runtime/" + node + ".java"]
                    self.assertEqual({"thc.runtime." + test for test in tests}, set(group["junit"]))
                    self.assertEqual({"bin/test-" + test + ".py" for test in python[name]},
                                     set(group["python"]))

    def test_shared_dispatch_loaders_memory_proofs_layouts_and_carriers_stay_full(self):
        # Scalar64's identity fallback processes every ordinary scalar operation;
        # the shared state/vector memory node and durable layouts are not leaves.
        names = ("Scalar64Primitives", "CoreVectorMemory", "VectorMemoryFamily", "VectorMemoryOp", "VectorReadCase", "VectorByteArrayExpression", "DataTagPrimitives", "CoreVectors",
                 "Program", "BytecodeProgram", "CoreRepresentations", "ArgumentLayout", "TupleResults", "Handoff")
        self.assertFalse(set(names) & {Path(path).stem for path in self.families})
        self.assertFalse(any(path.startswith("compiler/") for path in self.families))
        self.assertEqual({"src/main/java/thc/runtime/" + name + ".java"
                          for name in self.integer_vector_nodes + self.floating_vector_nodes +
                          ("BitPrimitives", "RawBitCasts", "FloatingPrimitives", "FloatDecodeExpression",
                           "HeapAddresses", "VectorAddressExpression", "VectorIntegerDivision",
                           "CoreMemoryCopyForeign", "MemcpyExpression", "MemmoveExpression", "CoreFileWait", "WaitFileDescriptor", "FileWaitPrimitives",
                           "RubbishLiterals", "PrefetchExpression", "TraceExpression", "TraceOp",
                           "CoreRtsShutdown", "ShutdownRuntime", "RtsShutdownOp", "GuestShutdown", "ThreadObservation", "CoreThreadObservation",
                           "CoreStringRtsForeign", "StringRtsOp", "StringRtsExpression",
                           "GuestArguments", "GuestEnvironment", "CoreEnvironmentForeign", "CoreRtsArgumentsForeign",
                           "EnvironmentOp", "EnvironmentExpression", "RtsArgumentsOp", "RtsArgumentsExpression",
                           "CoreCpuAffinity", "CpuAffinityQuery", "CoreDataLabels", "CoreBoundThreadForeign",
                           "BoundThreadSupport", "CoreMainThreadForeign", "RegisterMainThread",
                           "CompactImageOp", "CompactImageExpression", "StablePointers", "StablePointerToken", "CoreStablePointers", "StablePointerOp",
                           "MakeStablePointer", "DereferenceStablePointer", "EqualStablePointers", "FreeStablePointer",
                           "ManagedWeaks", "WeakResult", "WeakExpression", "WeakOp", "MainThreadWeakKey",
                           "ManagedCompacts", "ManagedCompact", "CompactCopyNode", "CompactOp", "CompactExpression", "CompactImages",
                           "AstStackScope", "AstStackSpill", "AstStackContinuation", "AstChildSuspension", "AstStacks", "ManagedSmallArray", "SmallArrayStorage", "SmallArrayOp", "ManagedMutVar", "ModifiedMutVar", "MutVarModifySite", "MutVarOp", "ManagedNativeAllocations", "NativeEventWait", "NativeEpoll", "AtomicIntArrayOp", "AtomicIntArrayExpression", "AddressArrayCopyOp", "AddressToByteArrayExpression", "ByteArrayToAddressExpression", "AtomicAddressOp", "AtomicAddressExpression", "NativeNarrowAtomic", "FloatingAddresses", "FloatingAddressOp", "FloatingAddressExpression", "CoreCurrentCCS", "ManagedAddressOrder", "CompareManagedAddress", "CompareOrderedManagedAddress", "GetCurrentCCS", "AddressToInt", "IntToAddress", "SubtractManagedAddress", "RemainderManagedAddress", "GhcBCO", "GhcInstruction", "GhcBCORoot", "GhcBCOExpression", "ManagedSTM", "ManagedTVar", "STMRetry", "STMConflict", "STMCall", "STMExpression", "STMOp", "STMRestart", "CoreSharedCAFStores", "SharedCAFStore", "SharedCAFStoreExpression")},
                         {path for path in self.families if path.startswith("src/main/java/")})

    def test_file_and_stdio_owners_keep_native_and_lifecycle_controls(self):
        owners = self.policy["owners"]
        self.assertLessEqual({"thc.runtime.PosixStdioHostAbiModelTest", "thc.runtime.StdioHostAbiFailureTest"},
                             set(owners["src/main/java/thc/runtime/StdioHostAbi.java"]["junit"]))
        native = {"thc.runtime.OriginalStdioNativeTest", "thc.runtime.OriginalStdioReadTest",
                  "thc.runtime.OriginalHandleReadinessNativeTest", "thc.runtime.OriginalStdioCloseNativeTest",
                  "thc.runtime.OriginalStdioSeekNativeTest", "thc.runtime.OriginalStdioTruncateNativeTest"}
        for name in ("ManagedFiles", "ManagedStdio", "StdioHostAbi", "CoreOriginalStdio",
                     "OriginalStdioExpression", "OriginalStdioOp"):
            path = "src/main/java/thc/runtime/" + name + ".java"
            with self.subTest(path=path):
                self.assertLessEqual(native, set(owners[path]["junit"]))
                self.assertNotIn(path, self.families)  # Preserve the foreign callback lane.
        self.assertLessEqual({"thc.runtime.NativeFileBuffersTest", "thc.runtime.ManagedFilesTest", "thc.runtime.GuestThreadsTest",
                              "thc.GuestExceptionsTest"}, set(owners["src/main/java/thc/runtime/ManagedFiles.java"]["junit"]))
        self.assertLessEqual({"thc.runtime.CoreManagedFilesTest", "thc.runtime.ManagedFileCallTest"},
                             set(owners["src/main/java/thc/runtime/CoreManagedFiles.java"]["junit"]))
        self.assertLessEqual({"thc.runtime.StdioHostAbiTest", *native},
                             set(owners["src/main/c/stdio-abi-probe.c"]["junit"]))
        for path in ("src/main/java/thc/runtime/Program.java", "src/main/java/thc/runtime/BytecodeProgram.java",
                     "src/main/java/thc/runtime/CoreRepresentations.java", "src/main/java/thc/runtime/BytecodeRoot.java"):
            self.assertNotIn(path, owners)

    def test_cabal_plugin_build_inputs_are_not_driver_only(self):
        for name in ("thc.cabal", "cabal.project", "Setup.hs"):
            with self.subTest(name=name):
                self.assertNotIn(name, self.families)
                self.assertNotIn(name, self.policy["owners"])


if __name__ == "__main__":
    unittest.main()
