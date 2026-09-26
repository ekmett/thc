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
        # No background Git process should outlive this temporary repository.
        self.git("config", "maintenance.auto", "false")
        self.git("config", "gc.auto", "0")
        smoke = dict(junit=["example.SmokeTest"], python=["scripts/test-smoke.py"])
        affected = dict(junit=["example.OtherTest"], python=["scripts/test-other.py"])
        self.policy = dict(schema=2, smoke=smoke,
                           leafSources={"src/main/kotlin/Leaf.kt": dict(junit=["example.LeafTest"], python=[])},
                           owners={"compiler/test-fixtures/Family.hs": affected,
                                   "scripts/prepare-family.py": affected,
                                   "src/test/kotlin/example/SharedContext.kt": affected},
                           primopFamilies={name: affected for name in
                                           ("bit-primops", "integer-primops", "signed-narrow-primops", "explicit64-primops",
                                            "simd-generated-primops")},
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

    def test_owned_runtime_file_keeps_polyglot_lane_and_shared_program_widens(self):
        path = "src/main/kotlin/thc/runtime/ManagedFiles.kt"
        self.policy["owners"][path] = dict(junit=["example.OtherTest"], python=[])
        self.write(select.POLICY, json.dumps(self.policy))
        self.write(path, "package thc.runtime\nclass ManagedFiles\n")
        before = self.commit()
        self.write(path, "package thc.runtime\nclass ManagedFiles { val changed = true }\n")
        self.commit()
        selected = self.plan(base=before)
        self.assertEqual("narrow", selected["mode"], selected)
        self.assertIn("example.OtherTest", selected["affected"]["junit"])
        self.assertTrue(selected["polyglot"]["required"])
        self.write("src/main/kotlin/thc/runtime/Program.kt", "package thc.runtime\nclass Program\n")
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

    def test_exact_generated_simd_extrema_addition_selects_family_checks(self):
        group = json.loads(Path(__file__).with_name("fast-tests.json").read_text())["primopFamilies"]["simd-generated-primops"]
        self.policy["primopFamilies"]["simd-generated-primops"] = group
        self.write(select.POLICY, json.dumps(self.policy))
        for name in group["junit"]:
            package, short = name.rsplit(".", 1)
            self.write("src/test/kotlin/" + name.replace(".", "/") + ".kt",
                       kotlin(short).replace("package example", "package " + package))
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
        program = ("class BytecodeProgram {\n    // BEGIN GENERATED SIMD FAMILIES\n"
                   "    // existing operation\n    // END GENERATED SIMD FAMILIES\n}\n")
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
                f'        "{op}Word32X8#" -> ProvenExpression(Expression {{ e ->\n'
                "            val b = e.builder\n"
                f"            b.begin{node}(); operands.forEach {{ it.emit(e) }}; b.end{node}()\n"
                "        }, GeneratedVectors.proofWord32X8)\n")
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
        self.assertEqual([], result["haskell"]["suites"])
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
        for path in ("src/main/kotlin/Critical.kt", "src/main/kotlin/ArgumentLayout.kt", "compiler/THC/Plugin.hs",
                     "build.gradle.kts", "src/main/resources/proof.json", "scripts/helper.py"):
            with self.subTest(path=path):
                self.write(path, "changed")
                self.commit()
                self.full("unmapped-source-or-configuration")

    def test_comment_only_unmapped_production_source_uses_lexical_boundaries(self):
        path = "src/main/kotlin/thc/Language.kt"
        before = ('package thc\nclass Language {\n'
                  '  val address = "https://example.invalid/a//b"\n'
                  '  val raw = """literal /* not a comment */"""\n'
                  '  val label = "${link.unit}"\n'
                  '  /* outer /* inner */ original */ val value = 1 // original\n}\n')
        self.write(path, before)
        self.base = self.commit()
        after = before.replace('/* inner */ original', '/* nested */ revised').replace('// original', '// revised')
        self.write(path, after)
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual([], result["affected"]["junit"])
        self.assertFalse(result["polyglot"]["required"])
        self.assertEqual([], result["haskell"]["suites"])
        self.write(path, after.replace('https://example.invalid', 'http://example.invalid'))
        self.commit()
        self.full("unmapped-source-or-configuration")
        self.write(path, after.replace('val value = 1', 'val value = 2'))
        self.commit()
        self.full("unmapped-source-or-configuration")
        self.write(path, after.replace('/* nested */', '/* unclosed'))
        self.commit()
        self.full("unmapped-source-or-configuration")

    def test_comment_only_proof_rejects_java_and_nested_kotlin_interpolation(self):
        java = "src/main/java/example/Foreign.java"
        self.write(java, "class Foreign { String text = \"// literal\"; /* old */ }\n")
        self.base = self.commit()
        self.write(java, "class Foreign { String text = \"// literal\"; /* new */ }\n")
        self.commit()
        self.full("unmapped-source-or-configuration")
        # Kotlin permits a quoted argument inside a template expression. The
        # outer-string scanner must reject it, not reinterpret // as a comment.
        for source in ('val x = "${foo("// old")}" // changed\n',
                       r'val x = "\\${foo("// old")}" // changed' + '\n',
                       'val x = """${foo("// old")}""" // changed\n'):
            with self.subTest(source=source), self.assertRaises(select.SelectionError):
                select.lexical_source(source, comments_only=True)
        for separator in ('\r', '\u0085', '\u2028', '\u2029'):
            with self.subTest(separator=repr(separator)), self.assertRaises(select.SelectionError):
                select.lexical_source('val x = 1 // comment' + separator + 'val y = 2\n', comments_only=True)

    def test_class_literals_private_generic_helpers_and_tempdir_are_local_test_syntax(self):
        path = "src/test/kotlin/example/OtherTest.kt"
        source = ('package example\nimport org.junit.jupiter.api.Test\n'
                  'class OtherTest {\n'
                  '  @TempDir lateinit var directory: Path\n'
                  '  private fun <T> entered(body: () -> T): T = body()\n'
                  '  @Test fun catches() = assertThrows(RuntimeFault::class.java) { entered { 1 } }\n'
                  '}\n')
        self.write(path, source)
        self.base = self.commit()
        self.write(path, source.replace('entered { 1 }', 'entered { 2 }'))
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(["example.OtherTest"], result["affected"]["junit"])
        for changed, reason in (
                (source.replace('private fun <T>', 'fun <T>'), "shared-test-member"),
                (source.replace('@TempDir lateinit var', 'lateinit var'), "shared-test-member"),
                (source.replace('fun <T> entered', 'fun <T : Any> entered'), "unresolved-test-declaration")):
            self.write(path, changed)
            self.commit()
            self.full(reason)

    def test_anonymous_objects_keep_private_helpers_local_without_hiding_shared_ones(self):
        path = "src/test/kotlin/example/OtherTest.kt"
        source = ('package example\nimport org.junit.jupiter.api.Test\n'
                  'private val local = object { val value = 1 }\n'
                  'class OtherTest {\n'
                  '  private fun thunk() = Holder(object : RootNode(null) {\n'
                  '    override fun execute() = local.value\n'
                  '  })\n'
                  '  @Test fun works() { assertEquals(1, thunk().execute()) }\n'
                  '}\n')
        self.write(path, source)
        self.base = self.commit()
        self.write(path, source.replace('val value = 1', 'val value = 2'))
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(["example.OtherTest"], result["affected"]["junit"])
        for changed, reason in (
                (source.replace('private fun thunk', 'fun thunk'), "shared-test-member"),
                (source.replace('private val local', 'val local'), "shared-test-helper"),
                (source + 'object Shared { val value = 1 }\n', "shared-test-helper")):
            with self.subTest(reason=reason):
                self.write(path, changed)
                self.commit()
                self.full(reason)

    def test_dot_qualified_escaped_members_keep_exact_test_selection(self):
        path = "src/test/kotlin/example/OtherTest.kt"
        source = kotlin("OtherTest", '@Test fun works() {\n'
                        '  Context.newBuilder("thc").`in`(input).build()\n'
                        '  builder?.`class`(); builder!!.\n    `value_1`\n'
                        '}')
        self.write(path, source)
        self.commit()
        result = self.plan()
        self.assertEqual("narrow", result["mode"], result)
        self.assertEqual(["example.OtherTest"], result["affected"]["junit"])
        code = select.code_only(source)
        masked = select.mask_escaped_members(code)
        self.assertNotIn('`', masked)
        self.assertEqual(len(code), len(masked))
        self.assertEqual([i for i, char in enumerate(code) if char == '\n'],
                         [i for i, char in enumerate(masked) if char == '\n'])
        self.assertEqual(source.index('class OtherTest'), masked.index('class OtherTest'))

    def test_escaped_declarations_unknown_syntax_and_shared_helpers_still_widen(self):
        path = "src/test/kotlin/example/OtherTest.kt"
        body = '@Test fun works() { builder.`in`(input) }'
        for source, reason in (
                (kotlin("OtherTest", '@Test fun `works`() {}'), "backtick-test-declaration"),
                (kotlin("OtherTest", body + '\nprivate fun Receiver.`local`() {}'), "backtick-test-declaration"),
                (kotlin("OtherTest", body + '\nprivate fun Receiver.\n`local`() {}'), "backtick-test-declaration"),
                (kotlin("OtherTest", body + '\nprivate val Receiver.`local` get() = 1'), "backtick-test-declaration"),
                (kotlin("OtherTest", body.replace('`in`', '`odd name`')), "backtick-test-declaration"),
                (kotlin("OtherTest", body.replace('builder.', '')), "backtick-test-declaration"),
                (kotlin("OtherTest", body.replace('builder.', 'builder..')), "backtick-test-declaration"),
                (kotlin("OtherTest", body.replace('builder.', '.')), "backtick-test-declaration"),
                (kotlin("OtherTest", body.replace('`in`', '`unterminated')), "backtick-test-declaration"),
                (kotlin("OtherTest", body + '\nfun shared() = builder.`in`(input)'), "shared-test-member"),
                (kotlin("OtherTest", body) + '\nfun shared() = builder.`in`(input)\n', "shared-test-helper")):
            with self.subTest(source=source):
                self.write(path, source)
                self.commit()
                self.full(reason)
        self.write(path, kotlin("OtherTest"))
        self.write("src/test/kotlin/example/ConsumerTest.kt",
                   kotlin("ConsumerTest", '@Test fun reads() { example.`OtherTest`() }'))
        self.base = self.commit()
        self.write(path, kotlin("OtherTest", '@Test fun changed() {}'))
        self.commit()
        self.full("test-class-used-as-helper")

    def test_native_file_buffers_inventory_remains_exact(self):
        path = "src/test/kotlin/thc/runtime/NativeFileBuffersTest.kt"
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
                    "thc.runtime.Int16BoundaryCompilationTest",
                    "thc.runtime.Int32ArrayNativeTest", "thc.runtime.InterfaceCoreNativeTest"}
        self.assertEqual(sorted(expected), result["affected"]["junit"])
        self.assertEqual(14, result["junit"]["count"])  # Eleven affected + three smoke.
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
        self.assertEqual({"AddressIdentity", "BitPrimitives", "RawBitCasts", "FloatingPrimitives", "FloatingAddresses", "ManagedSmallArrays", "ManagedMutVars", "ManagedNativeAllocations", "StablePointers", "CoreStablePointers", "CoreSharedCAFStores", "ManagedWeaks", "CoreMainThreadForeign", "CoreBoundThreadForeign",
                         "IntegerVectorPrimitives", "FloatingVectorPrimitives", "CoreDataLabels", "FileWaitPrimitives", "CoreRtsShutdown", "ManagedSTM", "STMPrimops", "HintTracePrimops"},
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
        self.assertEqual({"junit": ["thc.runtime.CoreBoundThreadForeignTest"],
                          "python": ["scripts/test-audit-core.py"]},
                         self.family("CoreBoundThreadForeign"))

    def test_native_malloc_source_and_composite_owners_select_both_consumers(self):
        malloc = "thc.runtime.NativeMallocTest"
        addresses = "thc.runtime.NativeAddressTest"
        buffers = "thc.runtime.NativeFileBuffersTest"
        self.assertEqual([buffers, malloc], self.family("ManagedNativeAllocations")["junit"])
        owners = self.policy["owners"]
        self.assertEqual([buffers, malloc], owners["src/main/java/thc/runtime/NativeMallocAllocation.java"]["junit"])
        self.assertEqual({malloc, addresses},
                         set(owners["test/haskell-fixtures/NativeAddressFixtures.hs"]["junit"]))
        self.assertEqual({malloc, addresses},
                         set(owners["src/main/kotlin/thc/runtime/NativeAddresses.kt"]["junit"]))
        for path in ("compiler/test-fixtures/NativeMallocNative.hs",
                     "src/test/resources/core/original-malloc-descriptors.json"):
            self.assertEqual([malloc], owners[path]["junit"])
        self.assertIn(malloc, owners["test/haskell-fixtures/Main.hs"]["junit"])

    def test_saved_termios_owners_select_pointer_and_original_fixture_controls(self):
        owners = self.policy["owners"]
        original = "thc.runtime.OriginalSavedTermiosTest"
        self.assertEqual({"thc.runtime.SavedTermiosTest", original},
                         set(owners["src/main/kotlin/thc/runtime/SavedTermios.kt"]["junit"]))
        for fixture in ("Audit", "Native"):
            self.assertEqual([original], owners[f"compiler/test-fixtures/OriginalSavedTermios{fixture}.hs"]["junit"])
        for path in ("test/haskell-fixtures/OriginalTermiosFixtures.hs", "test/haskell-fixtures/Main.hs",
                     "src/main/kotlin/thc/runtime/CoreOriginalStdio.kt", "src/main/kotlin/thc/runtime/OriginalStdioExpression.kt"):
            self.assertIn(original, owners[path]["junit"])

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

    def test_tcsetattr_sources_select_the_original_native_comparison(self):
        for path in ("compiler/test-fixtures/OriginalTcsetattrAudit.hs", "compiler/test-fixtures/OriginalTcsetattrNative.hs",
                     "test/haskell-fixtures/OriginalTcsetattrFixtures.hs", "src/main/c/native-file-api.c",
                     "src/main/kotlin/thc/runtime/NativeFileProvider.kt", "src/main/kotlin/thc/runtime/NativeOpenRequest.kt",
                     "src/main/kotlin/thc/runtime/ManagedStdio.kt", "src/main/kotlin/thc/runtime/ManagedFiles.kt",
                     "src/main/kotlin/thc/runtime/CoreOriginalStdio.kt", "src/main/kotlin/thc/runtime/OriginalStdioExpression.kt",
                     "test/haskell-fixtures/Main.hs"):
            self.assertIn("thc.runtime.OriginalTcsetattrTest", self.policy["owners"][path]["junit"], path)
    def test_sigprocmask_sources_select_the_platform_thread_controls(self):
        for path in ("compiler/test-fixtures/OriginalSigprocmaskAudit.hs", "compiler/test-fixtures/OriginalSigprocmaskNative.hs",
                     "test/haskell-fixtures/OriginalSigprocmaskFixtures.hs", "src/main/c/native-signal-api.c",
                     "src/main/kotlin/thc/runtime/ManagedSignalMask.kt", "src/main/kotlin/thc/runtime/CoreOriginalStdio.kt",
                     "src/main/kotlin/thc/runtime/OriginalStdioExpression.kt", "test/haskell-fixtures/Main.hs"):
            self.assertIn("thc.runtime.OriginalSigprocmaskTest", self.policy["owners"][path]["junit"], path)

    def test_tcgetattr_sources_select_the_original_native_comparison(self):
        for path in ("compiler/test-fixtures/OriginalTcgetattrAudit.hs", "compiler/test-fixtures/OriginalTcgetattrNative.hs",
                     "test/haskell-fixtures/OriginalTcgetattrFixtures.hs", "src/main/c/native-file-api.c",
                     "src/main/kotlin/thc/runtime/NativeFileProvider.kt", "src/main/kotlin/thc/runtime/NativeOpenRequest.kt",
                     "src/main/kotlin/thc/runtime/ManagedStdio.kt", "src/main/kotlin/thc/runtime/ManagedFiles.kt",
                     "src/main/kotlin/thc/runtime/CoreOriginalStdio.kt", "src/main/kotlin/thc/runtime/OriginalStdioExpression.kt",
                     "test/haskell-fixtures/Main.hs"):
            self.assertIn("thc.runtime.OriginalTcgetattrTest", self.policy["owners"][path]["junit"], path)

    def test_array_core_helper_selects_all_consuming_suites(self):
        group = self.policy["owners"]["src/test/kotlin/thc/runtime/ArrayCoreEvidence.kt"]
        consumers = set()
        for path in (self.root / "src/test/kotlin/thc/runtime").glob("*.kt"):
            source = path.read_text()
            if path.name != "ArrayCoreEvidence.kt" and "ArrayCoreEvidence(" in source:
                consumers.update(select.junit_info(source)[0])
        self.assertEqual(9, len(consumers))
        # The isolated boundary control delegates to the native test's genuine
        # two-root fixture helper, so it also consumes ArrayCoreEvidence.
        self.assertEqual(consumers | {"thc.runtime.Int16BoundaryCompilationTest"}, set(group["junit"]))
        self.assertEqual([], group["python"])

    def test_int16_boundary_control_tracks_its_helper_and_genuine_inputs(self):
        expected = {"thc.runtime.Int16ArrayNativeTest", "thc.runtime.Int16BoundaryCompilationTest"}
        for path in ("src/test/kotlin/thc/runtime/Int16ArrayNativeTest.kt",
                     "src/test/kotlin/thc/runtime/ArrayCoreEvidence.kt",
                     "compiler/test-fixtures/Int16ArrayAudit.hs",
                     "examples/THC/Unboxed16Arrays.hs", "test/haskell-fixtures/Main.hs"):
            self.assertTrue(expected <= set(self.policy["owners"][path]["junit"]), path)
        self.assertTrue(expected <= self.classes)

    def test_stack_info_layout_helper_selects_all_consumers(self):
        group = self.policy["owners"]["src/test/kotlin/thc/runtime/ManagedStackInfoImageTest.kt"]
        consumers = set()
        for path in (self.root / "src/test/kotlin/thc/runtime").glob("*.kt"):
            source = path.read_text()
            if "StackInfoTestLayout" in source:
                consumers.update(select.junit_info(source)[0])
        self.assertEqual({"thc.runtime.ManagedStackInfoImageTest", "thc.runtime.OriginalStackInfoCallTest",
                          "thc.runtime.OriginalStackDecoderCallTest"}, consumers)
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
        bitcasts = self.family("RawBitCasts")
        self.assertEqual(floating["python"], bitcasts["python"])
        self.assertEqual(set(floating["junit"]) | {"thc.runtime.FloatingAddressTest"}, set(bitcasts["junit"]))
        self.assertEqual({"thc.SumLayoutMetadataTest", *{"thc.runtime." + name for name in (
            "BytecodeTypedTupleInputTest", "CompiledThunkRetentionTest", "DoubleArrayNativeTest", "DoubleArrayTest",
            "DoubleVectorMemoryProofTest", "DoubleVectorStorageTest", "FloatArrayTest",
            "FloatVectorMemoryProofTest", "FloatVectorStorageTest", "FloatWordArrayNativeTest",
            "FloatingPrimitiveTest", "FloatingTupleTest", "FusedFloatingTest", "WordFloatingTest", "ScalarBitCastTest", "SimdDoubleByteArrayTest",
            "SimdDoubleVectorTest", "SimdFloatByteArrayTest", "SimdFloatVectorTest", "SimdFloatFmaTest", "SimdWideFloatFmaTest", "SqrtPrimitiveTest",
            "SumProtocolTest", "SumResultTest", "TupleInputNativeTest", "TypedInputScalarSourceTest")}},
                         set(floating["junit"]))
        self.assertLessEqual({"scripts/test-core-sums.py",
                             "scripts/test-sum-layout.py", "scripts/test-tuple-inputs.py",
                             "scripts/test-doublex2-bytearray-model.py", "scripts/test-floatx4-bytearray-model.py"},
                            set(floating["python"]))
        fixtures = json.loads(Path(__file__).with_name("fast-fixtures.json").read_text())
        prepared = {name for group in fixtures["groups"].values() for name in group["junit"]}
        self.assertLessEqual(set(floating["junit"]), prepared | set(fixtures["fixtureFreeJunit"]))

    def test_floating_haskell_producers_and_main_keep_their_consumers(self):
        owners = self.policy["owners"]
        for path in ("test/haskell-fixtures/BigNatLiteralFixtures.hs",
                     "compiler/test-fixtures/BigNatLiteralAudit.hs", "compiler/test-fixtures/BigNatLiteralAuditNative.hs",
                     "src/test/kotlin/thc/runtime/BigNatLiteralTest.kt"):
            self.assertEqual(["thc.runtime.BigNatLiteralTest"], owners[path]["junit"])
            self.assertEqual(["scripts/test-audit-core.py"], owners[path]["python"])
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
                             set(owners["compiler/test-fixtures/" + fixture + ".hs"]["junit"]))

    def test_grouped_vectors_keep_the_union_of_all_former_family_consumers(self):
        expected = {
            "IntegerVectorPrimitives": ["SimdVectorTest", "SimdInt8VectorTest", "SimdInt16VectorTest",
                "SimdWord8VectorTest", "SimdWord16VectorTest", "SimdInt32VectorTest", "SimdInt32MultiplyTest",
                "SimdInt32ByteArrayTest", "Int32VectorMemoryProofTest", "Int32VectorStorageTest",
                "SimdWord32VectorTest", "SimdWord32ByteArrayTest", "Word32VectorMemoryProofTest",
                "Word32VectorStorageTest"],
            "FloatingVectorPrimitives": ["SimdFloatVectorTest", "SimdFloatFmaTest", "SimdWideFloatFmaTest", "SimdFloatByteArrayTest",
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

    def test_file_and_stdio_owners_keep_native_and_lifecycle_controls(self):
        owners = self.policy["owners"]
        native = {"thc.runtime.OriginalStdioNativeTest", "thc.runtime.OriginalStdioReadTest",
                  "thc.runtime.OriginalHandleReadinessNativeTest", "thc.runtime.OriginalStdioCloseNativeTest",
                  "thc.runtime.OriginalStdioSeekNativeTest", "thc.runtime.OriginalStdioTruncateNativeTest"}
        for name in ("ManagedFiles", "ManagedStdio", "StdioHostAbi", "CoreOriginalStdio",
                     "OriginalStdioExpression"):
            path = "src/main/kotlin/thc/runtime/" + name + ".kt"
            with self.subTest(path=path):
                self.assertLessEqual(native, set(owners[path]["junit"]))
                self.assertNotIn(path, self.families)  # Preserve the foreign callback lane.
        self.assertLessEqual({"thc.runtime.NativeFileBuffersTest", "thc.runtime.ManagedFilesTest", "thc.runtime.GuestThreadsTest",
                              "thc.GuestExceptionsTest"}, set(owners["src/main/kotlin/thc/runtime/ManagedFiles.kt"]["junit"]))
        self.assertLessEqual({"thc.runtime.CoreManagedFilesTest", "thc.runtime.ManagedFileCallTest"},
                             set(owners["src/main/kotlin/thc/runtime/CoreManagedFiles.kt"]["junit"]))
        self.assertLessEqual({"thc.runtime.StdioHostAbiTest", *native},
                             set(owners["src/main/c/stdio-abi-probe.c"]["junit"]))
        for path in ("src/main/kotlin/thc/runtime/Program.kt", "src/main/kotlin/thc/runtime/BytecodeProgram.kt",
                     "src/main/kotlin/thc/runtime/CoreRepresentations.kt", "src/main/java/thc/runtime/BytecodeRoot.java"):
            self.assertNotIn(path, owners)

    def test_cabal_plugin_build_inputs_are_not_driver_only(self):
        for name in ("thc.cabal", "cabal.project", "Setup.hs"):
            with self.subTest(name=name):
                self.assertNotIn(name, self.families)
                self.assertNotIn(name, self.policy["owners"])


if __name__ == "__main__":
    unittest.main()
