"""Pure Git/source selection tests: never compile or execute guest/JUnit code."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

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
        self.policy = dict(schema=1, smoke=dict(junit=["example.SmokeTest"], python=["scripts/test-smoke.py"]),
                           leafSources={"src/main/kotlin/Leaf.kt": dict(junit=["example.LeafTest"], python=[])})
        files = {
            select.SCRIPT: Path(select.__file__).read_text(),
            select.POLICY: json.dumps(self.policy),
            "src/test/kotlin/example/SmokeTest.kt": kotlin("SmokeTest"),
            "src/test/kotlin/example/LeafTest.kt": kotlin("LeafTest"),
            "src/test/kotlin/example/OtherTest.kt": kotlin("OtherTest"),
            "src/main/kotlin/Leaf.kt": "package example\nfun leaf() = 1\n",
            "src/main/kotlin/Critical.kt": "package example\nclass Critical\n",
            "scripts/test-smoke.py": PYTHON_TEST,
            "scripts/test-other.py": PYTHON_TEST,
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

    def test_unknown_production_configuration_resources_and_compiler_widen(self):
        for path in ("src/main/kotlin/Critical.kt", "src/main/kotlin/ArgumentLayout.kt", "compiler/Thc/Plugin.hs",
                     "build.gradle.kts", "src/main/resources/proof.json", ".github/workflows/fast.yml", "scripts/helper.py"):
            with self.subTest(path=path):
                self.write(path, "changed")
                self.commit()
                self.full("unmapped-source-or-configuration")

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
        self.full("selection-policy-changed")
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
        result = self.full("selection-policy-changed")
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
        groups = [policy["smoke"], *policy["leafSources"].values()]
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


class PrimitiveFamilyPolicyTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[2]
        self.policy = json.loads(Path(__file__).with_name("fast-tests.json").read_text())
        self.families = self.policy["leafSources"]

    def family(self, name):
        return self.families["src/main/kotlin/thc/runtime/" + name + ".kt"]

    def test_every_mapping_target_is_a_real_test_and_each_path_is_explicit(self):
        self.assertEqual({"BitPrimitives", "RawBitCasts", "FloatingPrimitives", "VectorExpressions",
                          "Vector32Expressions", "Vector16Expressions", "Vector8Expressions",
                          "VectorWord8Expressions", "VectorWord16Expressions", "VectorWord32Expressions",
                          "VectorFloatExpressions", "VectorDoubleExpressions"},
                         {Path(path).stem for path in self.families})
        classes = {name for path in (self.root / "src/test").rglob("*.kt")
                   for name in select.junit_info(path.read_text())[0]}
        for path, group in self.families.items():
            with self.subTest(path=path):
                self.assertTrue((self.root / path).is_file())
                self.assertFalse(any(c in path for c in "*?[]"))
                self.assertTrue(group["junit"])
                self.assertEqual(len(group["junit"]), len(set(group["junit"])))
                self.assertEqual(len(group["python"]), len(set(group["python"])))
                self.assertLessEqual(set(group["junit"]), classes)
                for test in group["python"]:
                    self.assertTrue((self.root / test).is_file(), test)
                    self.assertTrue(select.python_test(test), test)

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

    def test_each_vector_family_keeps_native_storage_proof_and_multiply_consumers(self):
        expected = {
            "VectorExpressions": ["SimdVectorTest"],
            "Vector8Expressions": ["SimdInt8VectorTest"],
            "Vector16Expressions": ["SimdInt16VectorTest"],
            "VectorWord8Expressions": ["SimdWord8VectorTest"],
            "VectorWord16Expressions": ["SimdWord16VectorTest"],
            "Vector32Expressions": ["SimdInt32VectorTest", "SimdInt32MultiplyTest", "SimdInt32ByteArrayTest",
                                    "Int32VectorMemoryProofTest", "Int32VectorStorageTest"],
            "VectorWord32Expressions": ["SimdWord32VectorTest", "SimdWord32ByteArrayTest",
                                        "Word32VectorMemoryProofTest", "Word32VectorStorageTest"],
            "VectorFloatExpressions": ["SimdFloatVectorTest", "SimdFloatByteArrayTest",
                                       "FloatVectorMemoryProofTest", "FloatVectorStorageTest"],
            "VectorDoubleExpressions": ["SimdDoubleVectorTest", "SimdDoubleByteArrayTest",
                                        "DoubleVectorMemoryProofTest", "DoubleVectorStorageTest"],
        }
        for name, tests in expected.items():
            with self.subTest(name=name):
                self.assertEqual({"thc.runtime." + test for test in tests}, set(self.family(name)["junit"]))
                self.assertIn("scripts/test-core-vectors.py", self.family(name)["python"])

    def test_shared_dispatch_loaders_memory_proofs_layouts_and_carriers_stay_full(self):
        # Scalar64's identity fallback processes every ordinary scalar operation;
        # the shared state/vector memory node and durable layouts are not leaves.
        names = ("Scalar64Primitives", "VectorByteArrayExpressions", "CoreVectorMemory", "CoreVectors",
                 "Program", "BytecodeProgram", "CoreRepresentations", "ArgumentLayout", "TupleResults", "Handoff")
        self.assertFalse({"src/main/kotlin/thc/runtime/" + name + ".kt" for name in names} & self.families.keys())
        self.assertFalse(any(path.startswith(("compiler/", "src/main/java/")) for path in self.families))


if __name__ == "__main__":
    unittest.main()
