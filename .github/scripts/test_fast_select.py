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


def kotlin(name, body="@Test fun works() {}"):
    return "package example\nimport org.junit.jupiter.api.Test\nclass " + name + " {\n" + body + "\n}\n"


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
        smoke = dict(junit=["example.SmokeTest"], python=["scripts/test-smoke.py"])
        affected = dict(junit=["example.OtherTest"], python=["scripts/test-other.py"])
        self.policy = dict(schema=2, smoke=smoke,
                           leafSources={"src/main/kotlin/Leaf.kt": dict(junit=["example.LeafTest"], python=[])},
                           owners={"compiler/test-fixtures/Family.hs": affected,
                                   "scripts/prepare-family.py": affected,
                                   "src/test/kotlin/example/SharedContext.kt": affected},
                           primopFamilies={name: affected for name in
                                           ("bit-primops", "integer-primops", "signed-narrow-primops", "explicit64-primops")},
                           automation={name: dict(junit=[], python=["scripts/test-other.py"]) for name in
                                       (select.SCRIPT, select.POLICY, ".github/scripts/test_fast_select.py",
                                        ".github/workflows/fast.yml", ".github/scripts/fast_ci.py")})
        files = {
            select.SCRIPT: Path(select.__file__).read_text(),
            select.POLICY: json.dumps(self.policy),
            "src/test/kotlin/example/SmokeTest.kt": kotlin("SmokeTest"),
            "src/test/kotlin/example/LeafTest.kt": kotlin("LeafTest"),
            "src/test/kotlin/example/OtherTest.kt": kotlin("OtherTest"),
            "src/polyglotTest/kotlin/example/PolyglotTest.kt": kotlin("PolyglotTest"),
            "src/main/kotlin/Leaf.kt": "package example\nfun leaf() = 1\n",
            "src/main/kotlin/Critical.kt": "package example\nclass Critical\n",
            "scripts/test-smoke.py": PYTHON_TEST,
            "scripts/test-other.py": PYTHON_TEST,
            "test/haskell-driver/Main.hs": "module Main where\nmain = pure ()\n",
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

    def test_driver_source_selects_cabal_suite(self):
        self.policy["owners"]["src/THC/Driver/Project.hs"] = dict(
            junit=[], python=[], haskell=["driver-tests"])
        self.write(select.POLICY, json.dumps(self.policy))
        self.write("src/THC/Driver/Project.hs", "module THC.Driver.Project where\n")
        before = self.commit()
        self.write("src/THC/Driver/Project.hs", "module THC.Driver.Project where\nchanged = True\n")
        self.commit()
        selected = self.plan(base=before)
        self.assertEqual("narrow", selected["mode"], selected)
        self.assertEqual(["driver-tests"], selected["haskell"]["suites"])
        self.assertEqual(["driver-tests"], selected["affected"]["haskell"])

    def test_polyglot_changes_select_actual_optional_class_without_all_regular_tests(self):
        path = "src/polyglotTest/kotlin/example/PolyglotTest.kt"
        self.write(path, kotlin("PolyglotTest", "@Test fun changed() {}"))
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual({"required": True, "classes": ["example.PolyglotTest"]}, result["polyglot"])
        self.assertEqual(["example.SmokeTest"], result["junit"]["classes"])

    def test_shared_core_and_frontend_changes_require_polyglot_but_leaf_does_not(self):
        for path in ("src/main/kotlin/thc/runtime/CoreRepresentations.kt",
                     "compiler/THC/Plugin.hs", "src/main/java/thc/runtime/Calls.java",
                     "scripts/audit-core.py", "build.gradle.kts", "Makefile",
                     "compiler/plugin.py", "gradlew", "gradle/wrapper/gradle-wrapper.properties"):
            with self.subTest(path=path):
                self.write(path, "changed\n")
                self.commit()
                self.assertTrue(self.plan()["polyglot"]["required"])
                self.base = self.git("rev-parse", "HEAD")
        self.write("src/main/kotlin/Leaf.kt", "package example\nfun leaf() = 2\n")
        self.commit()
        self.assertFalse(self.plan()["polyglot"]["required"])

    def test_missing_optional_class_cannot_pass_a_required_lane(self):
        path = "src/polyglotTest/kotlin/example/PolyglotTest.kt"
        self.git("rm", path)
        self.commit()
        result = self.plan()
        self.assertTrue(result["polyglot"]["required"])
        self.assertFalse(result["runnable"])
        self.assertIn("empty-polyglot-inventory", {reason["code"] for reason in result["reasons"]})

    def test_changed_test_is_never_removed_for_smoke_budget(self):
        self.write("src/test/kotlin/example/OtherTest.kt", kotlin("OtherTest", "@Test fun expensiveNativeCampaign() {}"))
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(["example.OtherTest", "example.SmokeTest"], result["junit"]["classes"])

    def test_multiple_top_level_classes_use_actual_packages_not_filename(self):
        self.write("src/test/kotlin/example/misleading.kt", kotlin("AddedTest") + "\nclass SecondTest { @Test fun other() {} }\n")
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(["example.AddedTest", "example.SecondTest"], result["affected"]["junit"])

    def test_comments_nested_comments_strings_and_raw_strings_do_not_create_tests(self):
        body = '''/* class Fake { @Test fun fake() {} /* nested */ } */
private val raw = """class FakeRaw { @Test fun fake() {} }"""
private val text = "class FakeString { @Test }"
@Test fun real() { val char = '\\''; val slash = "\\\\" }
'''
        self.write("src/test/kotlin/example/OtherTest.kt", kotlin("OtherTest", body))
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(["example.OtherTest"], result["affected"]["junit"])

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
        path = "scripts/test-name space\tline\n'$(touch nope);.py"
        self.write(path, PYTHON_TEST)
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertIn(["python3", path], result["python"]["commands"])
        self.assertIn(path, result["changedPaths"])
        self.assertEqual(result, json.loads(json.dumps(result)))
        self.assertFalse((self.repo / "nope").exists())

    def test_declared_leaf_adds_its_test_and_smoke(self):
        self.write("src/main/kotlin/Leaf.kt", "package example\nfun leaf() = 2\n")
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(["example.LeafTest", "example.SmokeTest"], result["junit"]["classes"])

    def test_fixture_preparer_and_shared_test_context_select_their_owner(self):
        for path in self.policy["owners"]:
            with self.subTest(path=path):
                self.write(path, "changed family input\n")
                self.commit()
                result = self.plan()
                self.assertEqual("narrow", result["mode"], result)
                self.assertIn("example.OtherTest", result["affected"]["junit"])
                self.assertIn("scripts/test-other.py", result["affected"]["python"])
                self.base = result["head"]

    def test_unknown_fixture_and_preparer_still_widen(self):
        for path in ("compiler/test-fixtures/Unknown.hs", "scripts/prepare-unknown.py"):
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

    def test_mask_registry_only_accepts_new_exact_width_arms(self):
        path = select.PROGRAM
        before = ('internal fun narrowWordPrimitiveMask(name: String): Long = when (name) {\n'
                  '    "plusWord8#" -> 0xffL\n    else -> 0L\n}\n')
        self.write(path, before)
        self.base = self.commit()
        self.write(path, before.replace('    else ->', '    "quotWord8#" -> 0xffL\n    else ->'))
        self.commit()
        self.assertEqual("narrow", self.plan()["mode"])
        self.assertFalse(self.plan()["polyglot"]["required"])
        self.write(path, before.replace('    else ->', '    "quotWord8#" -> 0xffffL\n    else ->'))
        self.commit()
        self.full("shared-primop-registry-change")
        self.assertTrue(self.plan()["polyglot"]["required"])

    def test_new_primitive_dispatch_arms_are_scoped_but_existing_arm_edits_widen(self):
        path = select.PROGRAM
        before = ('private class Primitive(private val name: String) {\n'
                  '  val arity = when (operation) {\n    "plusInt#" -> 2\n    else -> 0\n  }\n'
                  '  fun run() = return when (operation) {\n    "plusInt#" -> x + y\n    else -> 0\n  }\n}\n')
        self.write(path, before)
        self.base = self.commit()
        after = before.replace('    "plusInt#" -> 2', '    "popCnt8#" -> 1\n    "plusInt#" -> 2')
        after = after.replace('    "plusInt#" -> x + y', '    "popCnt8#" -> java.lang.Long.bitCount(x).toLong()\n    "plusInt#" -> x + y')
        self.write(path, after)
        self.commit()
        self.assertEqual("narrow", self.plan()["mode"])
        self.assertFalse(self.plan()["polyglot"]["required"])
        self.write(path, after.replace('"plusInt#" -> x + y', '"plusInt#" -> x - y'))
        self.commit()
        self.full("shared-primop-registry-change")

    def test_bytecode_new_name_to_existing_operation_is_scoped(self):
        path = select.BYTECODE_PROGRAM
        before = ('val operation = when (scalar64PrimitiveOperation(name)) {\n'
                  '    "popCnt8#" -> "PopulationCountWidth"\n'
                  '    else -> throw UnsupportedCore("unknown")\n}\n')
        self.write(path, before)
        self.base = self.commit()
        after = before.replace('    else ->', '    "popCnt16#" -> "PopulationCountWidth"\n    else ->')
        self.write(path, after)
        self.commit()
        self.assertEqual("narrow", self.plan()["mode"])
        self.assertFalse(self.plan()["polyglot"]["required"])
        self.write(path, after.replace('"PopulationCountWidth"\n    else', '"NewUnreviewedOperation"\n    else'))
        self.commit()
        self.full("shared-primop-registry-change")

    def test_unknown_production_configuration_resources_and_compiler_widen(self):
        for path in ("src/main/kotlin/Critical.kt", "src/main/kotlin/ArgumentLayout.kt", "compiler/THC/Plugin.hs",
                     "build.gradle.kts", "src/main/resources/proof.json", "scripts/helper.py"):
            with self.subTest(path=path):
                self.write(path, "changed")
                self.commit()
                self.full("unmapped-source-or-configuration")

    def test_automation_changes_use_control_tests_and_smoke(self):
        for path in (".github/workflows/fast.yml", ".github/scripts/fast_ci.py"):
            with self.subTest(path=path):
                self.write(path, "changed")
                self.commit()
                result = self.plan()
                self.assertEqual("narrow", result["mode"], result)
                self.assertEqual(["example.SmokeTest"], result["junit"]["classes"])
                self.assertIn("scripts/test-other.py", result["python"]["files"])
                self.base = result["head"]

    def test_deleted_and_renamed_tests_widen_and_retain_both_paths(self):
        old = "src/test/kotlin/example/OtherTest.kt"
        new = "src/test/kotlin/example/RenamedTest.kt"
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
        self.write("src/test/kotlin/example/OtherTest.kt", kotlin("ReplacementTest"))
        self.commit()
        self.full("removed-junit-class")

    def test_python_delete_widens_and_does_not_execute_missing_file(self):
        self.git("rm", "scripts/test-other.py")
        self.commit()
        result = self.full("deleted-renamed-or-typechanged")
        self.assertNotIn("scripts/test-other.py", result["python"]["files"])

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
        self.write("untracked-helper.kt", "fun shared() = 1")
        self.full("dirty-checkout")
        self.commit()
        self.write("src/main/kotlin/Leaf.kt", "not committed")
        self.full("dirty-checkout")

    def test_test_helpers_top_level_extensions_and_shared_members_widen(self):
        path = "src/test/kotlin/example/OtherTest.kt"
        for code in ("package example\ninternal fun helper() = 1\n",
                     kotlin("OtherTest") + "\nfun String.extensionHelper() = this\n",
                     kotlin("OtherTest", "@Test fun test() {}\nfun helper() = 1"),
                     kotlin("OtherTest", "@Test fun test() = 1\nfun helper() = 1"),
                     kotlin("OtherTest", "@Test fun test() {}\nprivate fun local() {}; fun shared() = 1"),
                     kotlin("OtherTest", "private fun local() { @Test fun inner() {} }; fun shared() = 1\n@Test fun test() {}"),
                     kotlin("OtherTest") + "\nfun <T> shared(value: T) = value\n",
                     kotlin("OtherTest") + "\nfun `shared helper`() = 1\n",
                     kotlin("OtherTest") + "\nprivate val local = 1; fun shared() = 1\n",
                     kotlin("OtherTest", "companion object { fun helper() = 1 }\n@Test fun test() {}")):
            with self.subTest(code=code):
                self.write(path, code)
                self.commit()
                self.full()

    def test_private_nested_constructor_properties_are_not_shared_members(self):
        path = "src/test/kotlin/example/OtherTest.kt"
        body = "@Test fun test() {}\nprivate data class Row(val entry: String, val input: Long, val expected: Long)"
        self.write(path, kotlin("OtherTest", body))
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(["example.OtherTest"], result["affected"]["junit"])
        self.write(path, kotlin("OtherTest", body.replace("private data class", "data class")))
        self.commit()
        self.full("shared-test-member")
        self.write(path, kotlin("OtherTest", body + "\nval shared = 1"))
        self.commit()
        self.full("shared-test-member")

    def test_java_tests_select_their_class_but_widen_unknown_helper_grammar(self):
        self.write("src/test/java/example/JavaTest.java", "package example;\npublic class JavaTest { @Test public void test() {} public static void helper() {} }\n")
        self.commit()
        result = self.full("non-kotlin-test-source")
        self.assertIn("example.JavaTest", result["affected"]["junit"])

    def test_class_reused_as_helper_widens(self):
        self.write("src/test/kotlin/example/ConsumerTest.kt", kotlin("ConsumerTest", "@Test fun reads() { OtherTest() }"))
        self.base = self.commit()
        self.write("src/test/kotlin/example/OtherTest.kt", kotlin("OtherTest", "@Test fun changed() {}"))
        self.commit()
        self.full("test-class-used-as-helper")

    def test_python_helper_without_runner_and_imported_test_module_widen(self):
        self.write("scripts/test-other.py", "def helper(): return 1\n")
        self.commit()
        self.full("python-test-helper-or-unknown-runner")
        self.write("scripts/test-other.py", PYTHON_TEST)
        self.write("scripts/consumer.py", "import importlib\nother = 'test-other'\n")
        self.base = self.commit()
        self.write("scripts/test-other.py", PYTHON_TEST + "# changed\n")
        self.commit()
        self.full("python-test-used-as-helper")

    def test_historical_python_snapshots_are_data_not_executable_tests(self):
        path = "bench/experiments/example/evidence-x86_64/test-smoke.py"
        self.write(path, "historical failure snapshot, not executable")
        self.commit()
        result = self.full("unmapped-source-or-configuration")
        self.assertNotIn(path, result["python"]["files"])

    def test_python_lookalike_without_testcase_is_not_narrow(self):
        self.write("scripts/test-other.py", PYTHON_TEST.replace("(unittest.TestCase)", ""))
        self.commit()
        self.full("python-test-helper-or-unknown-runner")

    def test_test_factory_is_selected_but_nested_and_inherited_classes_widen(self):
        path = "src/test/kotlin/example/OtherTest.kt"
        self.write(path, kotlin("OtherTest", "@TestFactory fun dynamicCases() = listOf(1)"))
        self.commit()
        self.assertEqual("narrow", self.plan()["mode"])
        self.write(path, "package example\nclass OtherTest : SharedBase() { @Test fun test() {} }\n")
        self.commit()
        self.full("inherited-test-class")
        self.write(path, kotlin("OtherTest", "@Nested class Inner { @Test fun test() {} }"))
        self.commit()
        self.full("nested-test-class")

    def test_policy_changes_and_invalid_missing_test_mappings_widen(self):
        self.policy["smoke"]["junit"].append("example.OtherTest")
        self.write(select.POLICY, json.dumps(self.policy))
        self.commit()
        self.assertEqual("narrow", self.plan()["mode"])
        for mutation in (dict(schema=True), dict(smoke=dict(junit=[], python=[])),
                         dict(smoke=dict(junit=["example.NoSuchTest"], python=["scripts/test-smoke.py"])),
                         dict(leafSources={"missing.kt": dict(junit=["example.LeafTest"], python=[])})):
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
        path = self.repo / "src/test/kotlin/example/SmokeTest.kt"
        path.unlink()
        result = self.full("selected-test-file-missing-or-symlinked")
        self.assertFalse(result["runnable"])
        path.symlink_to("OtherTest.kt")
        self.commit()
        self.full("nonregular-changed-path")

    def test_empty_and_corrupt_inventory_cannot_be_an_empty_success(self):
        for path in (self.repo / "src/test").rglob("*.kt"):
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
            self.write("src/test/kotlin/" + name.replace(".", "/") + ".kt",
                       kotlin(short).replace("package example", "package " + package))
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
            self.write("src/test/kotlin/" + name.replace(".", "/") + ".kt",
                       kotlin(short).replace("package example", "package " + package))
        for path in {path for group in groups for path in group["python"]}:
            self.write(path, PYTHON_TEST)
        for path in policy["leafSources"]:
            self.write(path, "// synthetic production source\n")
        changed = ["compiler/test-fixtures/CBVCoercionAudit.hs",
                   "compiler/test-fixtures/DataToTagAudit.hs",
                   "compiler/test-fixtures/MutableByteArraySizeAudit.hs",
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
                    "thc.runtime.Int32ArrayNativeTest"}
        self.assertEqual(sorted(expected), result["affected"]["junit"])
        self.assertEqual(12, result["junit"]["count"])  # Nine affected + three smoke.
        self.assertEqual(sorted({"scripts/test-core-data-tags.py", "scripts/test-core-bytearrays.py"}), result["affected"]["python"])


class PrimitiveFamilyPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = Path(__file__).resolve().parents[2]
        cls.policy = json.loads(Path(__file__).with_name("fast-tests.json").read_text())
        cls.families = cls.policy["leafSources"]
        cls.classes = {name for path in (cls.root / "src/test").rglob("*.kt")
                       for name in select.junit_info(path.read_text())[0]}

    def family(self, name):
        return self.families["src/main/kotlin/thc/runtime/" + name + ".kt"]

    def test_every_mapping_target_is_a_real_test_and_each_path_is_explicit(self):
        self.assertEqual({"AddressIdentity", "BitPrimitives", "RawBitCasts", "FloatingPrimitives",
                          "IntegerVectorPrimitives", "FloatingVectorPrimitives"},
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

    def test_fixture_owners_match_the_preparation_manifest(self):
        fixture = json.loads(Path(__file__).with_name("fast-fixtures.json").read_text())
        owners = self.policy["owners"]
        native_only = {"examples/NativeOracle.hs", "examples/THC/MapWorkload.hs",
                       "scripts/native-oracle.sh"}
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
                          "thc.SignedNarrowPrimopsTest"},
                         set(owners["src/test/kotlin/thc/PrimopTestContext.kt"]["junit"]))

    def test_array_core_helper_selects_all_consuming_suites(self):
        group = self.policy["owners"]["src/test/kotlin/thc/runtime/ArrayCoreEvidence.kt"]
        consumers = set()
        for path in (self.root / "src/test/kotlin/thc/runtime").glob("*.kt"):
            source = path.read_text()
            if path.name != "ArrayCoreEvidence.kt" and "ArrayCoreEvidence(" in source:
                consumers.update(select.junit_info(source)[0])
        self.assertEqual(8, len(consumers))
        self.assertEqual(consumers, set(group["junit"]))
        self.assertEqual([], group["python"])

    def test_control_and_owner_targets_exist_and_are_runnable(self):
        checked_python = set()
        for group in [*self.policy["owners"].values(), *self.policy["primopFamilies"].values(),
                      *self.policy["automation"].values()]:
            self.assertLessEqual(set(group["junit"]), self.classes)
            for suite in group.get("haskell", []):
                self.assertEqual("driver-tests", suite)
                self.assertTrue((self.root / "test/haskell-driver/Main.hs").is_file())
                self.assertIn("test-suite driver-tests", (self.root / "thc.cabal").read_text())
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
        self.assertEqual(floating, self.family("RawBitCasts"))
        self.assertEqual({"thc.SumLayoutMetadataTest", *{"thc.runtime." + name for name in (
            "BytecodeTypedTupleInputTest", "DoubleArrayNativeTest", "DoubleArrayTest",
            "DoubleVectorMemoryProofTest", "DoubleVectorStorageTest", "FloatArrayTest",
            "FloatVectorMemoryProofTest", "FloatVectorStorageTest", "FloatWordArrayNativeTest",
            "FloatingPrimitiveTest", "FloatingTupleTest", "ScalarBitCastTest", "SimdDoubleByteArrayTest",
            "SimdDoubleVectorTest", "SimdFloatByteArrayTest", "SimdFloatVectorTest", "SqrtPrimitiveTest",
            "SumProtocolTest", "SumResultTest", "TupleInputNativeTest", "TypedInputScalarSourceTest")}},
                         set(floating["junit"]))
        self.assertLessEqual({"scripts/test-scalar-bitcasts.py", "scripts/test-core-sums.py",
                             "scripts/test-sum-layout.py", "scripts/test-tuple-inputs.py",
                             "scripts/test-doublex2-bytearray-model.py", "scripts/test-floatx4-bytearray-model.py"},
                            set(floating["python"]))

    def test_grouped_vectors_keep_the_union_of_all_former_family_consumers(self):
        expected = {
            "IntegerVectorPrimitives": ["SimdVectorTest", "SimdInt8VectorTest", "SimdInt16VectorTest",
                "SimdWord8VectorTest", "SimdWord16VectorTest", "SimdInt32VectorTest", "SimdInt32MultiplyTest",
                "SimdInt32ByteArrayTest", "Int32VectorMemoryProofTest", "Int32VectorStorageTest",
                "SimdWord32VectorTest", "SimdWord32ByteArrayTest", "Word32VectorMemoryProofTest",
                "Word32VectorStorageTest"],
            "FloatingVectorPrimitives": ["SimdFloatVectorTest", "SimdFloatByteArrayTest",
                "FloatVectorMemoryProofTest", "FloatVectorStorageTest", "SimdDoubleVectorTest",
                "SimdDoubleByteArrayTest", "DoubleVectorMemoryProofTest", "DoubleVectorStorageTest"],
        }
        python = {
            "IntegerVectorPrimitives": ["core-vector-memory", "core-vectors", "core-word32-vector-memory",
                "int16x8-model", "int32x4-bytearray-model", "int32x4-multiply-model", "int8x16-model",
                "word16x8-model", "word32x4-bytearray-model", "word32x4-model", "word8x16-model"],
            "FloatingVectorPrimitives": ["core-double-vector-memory", "core-float-vector-memory", "core-vectors",
                "doublex2-bytearray-model", "doublex2-model", "floatx4-bytearray-model", "floatx4-model"],
        }
        for name, tests in expected.items():
            with self.subTest(name=name):
                self.assertEqual({"thc.runtime." + test for test in tests}, set(self.family(name)["junit"]))
                self.assertEqual({"scripts/test-" + test + ".py" for test in python[name]},
                                 set(self.family(name)["python"]))

    def test_shared_dispatch_loaders_memory_proofs_layouts_and_carriers_stay_full(self):
        # Scalar64's identity fallback processes every ordinary scalar operation;
        # the shared state/vector memory node and durable layouts are not leaves.
        names = ("Scalar64Primitives", "VectorMemoryPrimitives", "DataTagPrimitives", "CoreVectors",
                 "Program", "BytecodeProgram", "CoreRepresentations", "ArgumentLayout", "TupleResults", "Handoff")
        self.assertFalse({"src/main/kotlin/thc/runtime/" + name + ".kt" for name in names} & self.families.keys())
        self.assertFalse(any(path.startswith(("compiler/", "src/main/java/")) for path in self.families))

    def test_cabal_plugin_build_inputs_are_not_driver_only(self):
        for name in ("thc.cabal", "cabal.project", "Setup.hs"):
            with self.subTest(name=name):
                self.assertNotIn(name, self.families)
                self.assertNotIn(name, self.policy["owners"])


if __name__ == "__main__":
    unittest.main()
