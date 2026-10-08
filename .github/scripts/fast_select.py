#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Conservative fast-CI selection, not a replacement for the canonical Build.

The runner must honor mode=full, run both handoff modes, and verify fresh JUnit
coverage for EVERY selected class (not merely a nonempty aggregate report).
Commands are argv arrays, never shell source. Explicit CI cadence defers owned
coverage to scheduled runs; it does not report those tests as executed.
"""
import argparse
import ast
import hashlib
import json
import os
import platform
from pathlib import Path, PurePosixPath
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ".github/scripts/fast_select.py"
POLICY = ".github/scripts/fast-tests.json"
CAPABILITIES = "bin/core-capabilities.json"
PROGRAM = "src/main/java/thc/runtime/Program.java"
BYTECODE_PROGRAM = "src/main/java/thc/runtime/BytecodeProgram.java"
BYTECODE_ROOT = "src/main/java/thc/runtime/BytecodeRoot.java"
SIMD_SPEC = "bin/simd-families.json"
SIMD_GENERATOR = "bin/generate-simd-families.py"
SIMD_ADDITIVE = {CAPABILITIES, BYTECODE_PROGRAM, BYTECODE_ROOT, SIMD_SPEC}
POLYGLOT_TEST_ROOT = "src/polyglotTest/"
HASKELL_TESTS = {"driver-tests": "t/haskell-driver/Main.hs", "primop-tools": "t/primop-tools/Main.hs",
                 "compact-core-tests": "t/compact-core/Main.hs"}
POLYGLOT_EXACT_INPUTS = {
    "build.gradle", "settings.gradle", "gradle.properties", "gradlew",
    "nih/gradle/wrapper/gradle-wrapper.jar", "nih/gradle/wrapper/gradle-wrapper.properties",
    "Makefile", "thc.cabal", "cabal.project", "Setup.hs", "bin/plugin.py",
    "bin/toolchain.sh", "bin/audit-core.py", "bin/core_package_manifest.py",
    "bin/polyglot-demo.sh", "bin/javascript-demo.sh", "bin/acquire-polyglot-demo.sh",
    "bin/build-compiler.sh", "bin/export-core.sh", "bin/test-javascript-ffi.py",
    "src/main/java/thc/Language.java", "src/main/java/thc/Json.java",
    "src/main/java/thc/PackageScalarLinks.java",
    "src/examples/java/thc/PolyglotDemo.java",
    "src/examples/cabal.project", "src/examples/thc-examples.cabal",
    "src/main/java/thc/runtime/Calls.java", "src/main/java/thc/runtime/BytecodeRoot.java",
    "src/main/java/thc/runtime/RuntimeTypes.java",
    "src/test/resources/thc/polyglot-abi.json",
}
POLYGLOT_INPUT_PREFIXES = (
    POLYGLOT_TEST_ROOT, "src/build/", "src/gradle/", "src/compiler/THC/", "src/examples/Polyglot",
    "src/examples/JavaScript", "src/main/java/thc/runtime/",
)
TEST_ANNOTATION = r"@\s*(?:org\.junit\.(?:jupiter\.api|jupiter\.params)\.)?(?:Test|TestFactory|TestTemplate|ParameterizedTest|RepeatedTest)\b"
LIFECYCLE = r"@\s*(?:org\.junit\.jupiter\.api\.)?(?:BeforeEach|AfterEach|BeforeAll|AfterAll)\b"
JAVA_DECLARATION = re.compile(r"\b(class|interface|enum|record)\s+([A-Za-z_$][\w$]*)")
SOURCE_SPECIAL = re.compile(r'''//|/\*|"""|["']''')


class SelectionError(Exception):
    pass


def git(repo, *args, input_bytes=None):
    process = subprocess.run(["git", "--no-replace-objects", "-C", str(repo), *args],
                             input=input_bytes, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             env=dict(os.environ, GIT_OPTIONAL_LOCKS="0"))
    if process.returncode:
        raise SelectionError("git command failed: " + args[0])
    return process.stdout


def resolve(repo, revision):
    if not isinstance(revision, str) or not revision or "\x00" in revision:
        return None
    try:
        value = git(repo, "rev-parse", "--verify", "--end-of-options", revision + "^{commit}").decode().strip()
        return value if re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", value) else None
    except SelectionError:
        return None


def paths_from_diff(data):
    """--name-status -z: status NUL path NUL [second-path NUL]."""
    if not data:
        return []
    if not data.endswith(b"\x00"):
        raise SelectionError("unterminated Git diff")
    fields = data[:-1].split(b"\x00")
    records = []
    index = 0
    while index < len(fields):
        status = fields[index].decode("ascii")
        if not re.fullmatch(r"[ACDMRTUXB][0-9]*", status):
            raise SelectionError("unknown Git diff status")
        count = 2 if status[0] in "RC" else 1
        if index + count >= len(fields):
            raise SelectionError("truncated Git diff")
        paths = [p.decode("utf-8", "surrogateescape") for p in fields[index + 1:index + count + 1]]
        if any(not p for p in paths):
            raise SelectionError("empty Git diff path")
        records.append(dict(status=status, paths=paths))
        index += count + 1
    return records


def tree(repo, commit):
    result = {}
    for record in git(repo, "ls-tree", "-r", "-z", "--full-tree", commit).split(b"\x00"):
        if not record:
            continue
        header, path = record.split(b"\t", 1)
        mode, kind, oid = header.decode("ascii").split(" ")
        result[path.decode("utf-8", "surrogateescape")] = (mode, kind, oid)
    return result


def batch_blobs(repo, oids):
    """Read committed inventory sources with one Git process, keyed by exact OID."""
    oids = list(dict.fromkeys(oids))
    if not oids:
        return {}
    if any(not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", oid) for oid in oids):
        raise SelectionError("invalid Git blob id")
    data = git(repo, "cat-file", "--batch", input_bytes=("\n".join(oids) + "\n").encode("ascii"))
    result = {}
    offset = 0
    for oid in oids:
        header_end = data.find(b"\n", offset)
        if header_end < 0:
            raise SelectionError("truncated Git blob batch")
        parts = data[offset:header_end].split(b" ")
        if (len(parts) != 3 or parts[0] != oid.encode("ascii") or parts[1] != b"blob"
                or not parts[2].isdigit()):
            raise SelectionError("unexpected Git blob batch header")
        size = int(parts[2])
        start, end = header_end + 1, header_end + 1 + size
        if end >= len(data) or data[end:end + 1] != b"\n":
            raise SelectionError("truncated Git blob batch body")
        result[oid] = data[start:end]
        offset = end + 1
    if offset != len(data):
        raise SelectionError("extra Git blob batch data")
    return result


def python_test(path):
    name = PurePosixPath(path).name
    # Historical snapshots are data, not runnable test sources. Changes to these
    # paths still widen through the ordinary unmapped-path rule.
    historical = any(
        part.startswith("evidence-") for part in PurePosixPath(path).parts)
    return not historical and name.endswith(".py") and (name.startswith(("test-", "test_")) or name.endswith("_test.py"))


def junit_source(path):
    return path.startswith("src/test/") and path.endswith(".java")


def polyglot_junit_source(path):
    return path.startswith(POLYGLOT_TEST_ROOT) and path.endswith(".java")


def polyglot_input(path, leaf_sources):
    # The common Core loader/lowering can affect JavaScript even when the
    # normal test selector widens. Reviewed scalar/SIMD leaf files cannot.
    return path in POLYGLOT_EXACT_INPUTS or (
        path not in leaf_sources and path.startswith(POLYGLOT_INPUT_PREFIXES))


def code_only(source):
    """Mask Java comments and literals, preserving offsets for class discovery."""
    result = []
    index = previous = 0
    while token := SOURCE_SPECIAL.search(source, index):
        start = token.start()
        if source.startswith("//", start):
            end = source.find("\n", start)
            index = len(source) if end < 0 else end
        elif source.startswith("/*", start):
            end = source.find("*/", start + 2)
            if end < 0:
                raise SelectionError("unclosed block comment")
            index = end + 2
        else:
            delimiter = '"""' if source.startswith('"""', start) else source[start]
            index = start + len(delimiter)
            while index < len(source):
                if source[index] == "\\":
                    index += 2
                elif source.startswith(delimiter, index):
                    index += len(delimiter)
                    break
                else:
                    index += 1
            else:
                raise SelectionError("unclosed string or text block")
        result.append(source[previous:start])
        result.append("".join("\n" if char == "\n" else " " for char in source[start:index]))
        previous = index
    result.append(source[previous:])
    return "".join(result)


def java_test_members(code, depths, start, end):
    """Recognize direct Java test members; unknown/shared members still widen.

    This is deliberately not a Java parser. Bodies and annotation arguments are
    balanced before selecting a member header, so a nested annotation/body cannot
    hide a following package-private helper. Only private members, ordinary JUnit
    methods and injected TempDir fields are local to the selected class.
    """
    unsafe = []
    first = start + 1
    index = first
    parentheses = 0
    while index < end:
        char = code[index]
        if depths[index] != 1:
            index += 1
            continue
        parentheses += (char == "(") - (char == ")")
        if parentheses < 0:
            unsafe.append("unresolved-test-declaration")
            parentheses = 0
        if parentheses or char not in "{;":
            index += 1
            continue
        header = code[first:index].strip()
        # Strip complete annotations only, including balanced nested arguments.
        plain = header
        while plain.startswith("@"):
            annotation = re.match(r"@[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\s*", plain)
            if annotation is None:
                break
            position = annotation.end()
            if position < len(plain) and plain[position] == "(":
                level = 1
                position += 1
                while position < len(plain) and level:
                    level += (plain[position] == "(") - (plain[position] == ")")
                    position += 1
                if level:
                    break
            plain = plain[position:].lstrip()
        private = re.match(r"(?:(?:static|final|synchronized|strictfp)\s+)*private\b", plain)
        method = re.fullmatch(
            r"(?:(?:public|protected|static|final|synchronized|strictfp|default)\s+)*"
            r"(?:void|[A-Za-z_$][\w.$]*(?:\s*<[^{};=]+>)?(?:\s*\[\s*\])*)\s+"
            r"[A-Za-z_$][\w$]*\s*\([^{};]*\)\s*(?:throws\s+[\w.$,\s]+)?", plain)
        injected = re.search(r"@\s*(?:org\.junit\.jupiter\.api\.io\.)?TempDir\b", header) and re.fullmatch(
            r"(?:(?:public|protected|static)\s+)*(?:java\.nio\.file\.Path|Path|java\.io\.File|File)\s+[A-Za-z_$][\w$]*", plain)
        if header and not private and not injected and not (
                method and char == "{" and re.search(TEST_ANNOTATION + "|" + LIFECYCLE, header)):
            unsafe.append("shared-test-member")
        if char == "{":
            # Consume this member's whole body/initializer, not nested members.
            index = next((i for i in range(index + 1, end)
                          if code[i] == "}" and depths[i] == 2), end)
        first = index + 1
        index += 1
    if code[first:end].strip() or parentheses:
        unsafe.append("unresolved-test-declaration")
    return unsafe


def junit_info(source):
    code = code_only(source)
    packages = re.findall(r"^\s*package\s+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\s*;\s*$", code, re.M)
    depths = []
    depth = 0
    for char in code:
        depths.append(depth)
        depth += (char == "{") - (char == "}")
        if depth < 0:
            raise SelectionError("unbalanced test source")
    if depth:
        raise SelectionError("unbalanced test source")
    declarations = list(JAVA_DECLARATION.finditer(code))
    classes = []
    ranges = []
    unsafe = []
    for item in declarations:
        if depths[item.start()] != 0 or item[1] != "class":
            continue
        start = code.find("{", item.end())
        if start < 0:
            continue
        end = next((i for i in range(start + 1, len(code)) if code[i] == "}" and depths[i] == 1), None)
        if end is None:
            raise SelectionError("unclosed test class")
        if re.search(TEST_ANNOTATION, code[start:end]):
            if len(packages) != 1:
                raise SelectionError("JUnit source needs an exact package")
            classes.append(packages[0] + "." + item[2])
            ranges.append((item, start, end))
            if re.search(r"\b(?:extends|implements)\b", code[item.end():start]):
                unsafe.append("inherited-test-class")
    if re.search(TEST_ANNOTATION, code) and not classes:
        raise SelectionError("unresolved JUnit declaration")
    if re.search(r"@\s*(?:org\.junit\.jupiter\.api\.)?Nested\b", code):
        unsafe.append("nested-test-class")
    # Top-level helpers and non-private non-test members may be consumed by
    # other tests. Never silently omit them.
    test_starts = {item.start() for item, _, _ in ranges}
    declaration_starts = {item.start() for item in declarations}
    for item in re.finditer(r"(?<![.])\b(?:class|interface|enum|record)\b", code):
        if depths[item.start()] in (0, 1) and item.start() not in declaration_starts:
            unsafe.append("unresolved-test-declaration")
    for item in declarations:
        if depths[item.start()] == 0 and item.start() not in test_starts:
            unsafe.append("shared-test-helper")
    for _, start, end in ranges:
        unsafe.extend(java_test_members(code, depths, start, end))
    return sorted(set(classes)), sorted(set(unsafe)), code


def standalone_python_test(source):
    """Require real unittest test methods and a guarded unittest.main entry."""
    parsed = ast.parse(source)
    methods = any(isinstance(node, ast.ClassDef) and any(
        isinstance(base, ast.Attribute) and isinstance(base.value, ast.Name) and
        base.value.id == "unittest" and base.attr == "TestCase" for base in node.bases) and any(
        isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)) and child.name.startswith("test_")
        for child in node.body) for node in parsed.body)
    main = any(isinstance(node, ast.If) and isinstance(node.test, ast.Compare) and
               isinstance(node.test.left, ast.Name) and node.test.left.id == "__name__" and
               len(node.test.ops) == 1 and isinstance(node.test.ops[0], ast.Eq) and
               len(node.test.comparators) == 1 and isinstance(node.test.comparators[0], ast.Constant) and
               node.test.comparators[0].value == "__main__" and any(
                   isinstance(child, ast.Call) and isinstance(child.func, ast.Attribute) and
                   isinstance(child.func.value, ast.Name) and child.func.value.id == "unittest" and
                   child.func.attr == "main" for statement in node.body for child in ast.walk(statement))
               for node in parsed.body)
    return methods and main


def primop_family(name):
    """Only names exercised by the pinned, independent native primop oracles."""
    if re.fullmatch(r"(?:(?:popCnt|clz|ctz)(?:8|16|32|64)|byteSwap(?:16|32|64)?|bitReverse(?:8|16|32|64)?)#", name):
        return "bit-primops"
    if re.fullmatch(r"(?:(?:quot|rem|gt|ge)Word|(?:quot|rem|eq|ne|gt|ge|and|or|xor|not|uncheckedShiftL|uncheckedShiftRL)Word(?:8|16|32))#", name):
        return "integer-primops"
    if re.fullmatch(r"(?:negate|plus|sub|times|quot|rem|eq|ne|lt|le|gt|ge)Int(?:8|16|32)#", name):
        return "signed-narrow-primops"
    if re.fullmatch(r"(?:int64ToWord64|word64ToInt64|wordToWord64|word64ToWord|(?:plus|sub|times|quot|rem|eq|ne|lt|le|gt|ge)(?:Int|Word)64|negateInt64|(?:and|or|xor|not)64|uncheckedIShift(?:L|RA|RL)64|uncheckedShift(?:L|RL)64)#", name):
        return "explicit64-primops"
    return None


def additive_capability_families(before, after):
    """Allow only new, known primop entries; all other contract edits widen."""
    old, new = json.loads(before), json.loads(after)
    if not isinstance(old, dict) or not isinstance(new, dict):
        return None
    prior, current = old.get("primitives"), new.get("primitives")
    if not isinstance(prior, dict) or not isinstance(current, dict):
        return None
    additions = current.keys() - prior.keys()
    if not additions or any(primop_family(name) is None or type(current[name]) is not int for name in additions):
        return None
    reduced = dict(new)
    reduced["primitives"] = {name: value for name, value in current.items() if name not in additions}
    if reduced != old:
        return None
    return {primop_family(name) for name in additions}


def generated_simd_region(before, after, blocks):
    """Require exact new operation blocks within the marked generated region."""
    begin, end = "    // BEGIN GENERATED SIMD FAMILIES\n", "    // END GENERATED SIMD FAMILIES\n"
    if any(source.count(begin) != 1 or source.count(end) != 1 for source in (before, after)):
        return False
    old_prefix, old_tail = before.split(begin)
    new_prefix, new_tail = after.split(begin)
    old_body, old_suffix = old_tail.split(end)
    new_body, new_suffix = new_tail.split(end)
    if (old_prefix, old_suffix) != (new_prefix, new_suffix):
        return False
    for block in blocks:
        if new_body.count(block) != 1 or block in old_body:
            return False
        new_body = new_body.replace(block, "", 1)
    return new_body == old_body


def additive_simd_primops(old_spec, new_spec, old_cap, new_cap,
                          old_root, new_root, old_program, new_program):
    """Recognize only added min/max on existing layouts with exact generated code."""
    old, new = json.loads(old_spec), json.loads(new_spec)
    if (not isinstance(old, dict) or not isinstance(new, dict)
            or not isinstance(old.get("families"), list) or not isinstance(new.get("families"), list)
            or len(old["families"]) != len(new["families"])):
        return None
    additions = []
    reduced = dict(new)
    reduced["families"] = []
    for previous, current in zip(old["families"], new["families"]):
        if not isinstance(previous, dict) or not isinstance(current, dict):
            return None
        name, prior, operations = current.get("name"), previous.get("operations"), current.get("operations")
        if (not isinstance(name, str) or not re.fullmatch(r"(?:Int|Word)(?:8|16|32|64)X(?:2|4|8|16)|(?:Float|Double)X(?:2|4|8|16)", name)
                or not isinstance(prior, list) or not isinstance(operations, list)
                or any(not isinstance(op, str) for op in prior + operations)
                or len(prior) != len(set(prior)) or len(operations) != len(set(operations))
                or [op for op in operations if op in prior] != prior):
            return None
        added = [op for op in operations if op not in prior]
        if added and (not name.startswith(("Int", "Word")) or any(op not in ("min", "max") for op in added)):
            return None
        additions.extend((name, op) for op in added)
        restored = dict(current)
        restored["operations"] = prior
        reduced["families"].append(restored)
    if not additions or reduced != old:
        return None
    before, after = json.loads(old_cap), json.loads(new_cap)
    if not isinstance(before, dict) or not isinstance(after, dict):
        return None
    prior, current = before.get("primitives"), after.get("primitives")
    if not isinstance(prior, dict) or not isinstance(current, dict):
        return None
    names = {op + name + "#" for name, op in additions}
    if current.keys() - prior.keys() != names or any(type(current[name]) is not int or current[name] != 2 for name in names):
        return None
    restored = dict(after)
    restored["primitives"] = {name: value for name, value in current.items() if name not in names}
    if restored != before:
        return None
    root_blocks, program_blocks = [], []
    for name, op in additions:
        title = op.capitalize()
        node = f"Generated{name}{title}"
        root_blocks.append(
            f"    @Operation public static final class {node} {{\n"
            f"        @Specialization public static {name} apply({name} left, {name} right) {{ return {name}.{op}(left, right); }}\n"
            "    }\n")
        program_blocks.append(
            f'            case "{op}{name}#" -> new ProvenExpression(e -> {{\n'
            "                var b = e.builder;\n"
            f"                b.begin{node}(); for (var operand : operands) operand.emit(e); b.end{node}();\n"
            f"            }}, GeneratedVectors.proof{name});\n")
    if (not generated_simd_region(old_root, new_root, root_blocks)
            or not generated_simd_region(old_program, new_program, program_blocks)):
        return None
    return names


# This deliberately recognizes one closed Cabal addition profile, not arbitrary
# Cabal syntax. All pre-existing blocks must remain equivalent.
FULL_CORE_TEST_BODY = """  type: exitcode-stdio-1.0
  if !flag(full-core-tests)
    buildable: False
  main-is: {main}
  hs-source-dirs: t/haskell-driver
  other-modules: TestSupport
  build-tool-depends: thc:thc
  build-depends:
    base >= 4.22 && < 4.23,
    aeson >= 2.3 && < 2.4,
    bytestring >= 0.12.2 && < 0.13,
    directory >= 1.3.10 && < 1.4,
    filepath >= 1.5.4 && < 1.6,
    HUnit >= 1.6 && < 1.7,
    process >= 1.6.26 && < 1.7,
    text >= 2.1 && < 2.2,
    vector >= 0.13 && < 0.14,
    zip-archive >= 0.4.3.2 && < 0.5
  default-language: Haskell2010
  ghc-options: -Wall
  if flag(development)
    ghc-options: -Werror"""


def additive_full_core_tests(before, after, statuses, old_paths):
    """Prove ownership for new disabled tests and their new fixture files.

    Unknown fields/conditions, changed existing stanzas, and shared fixture
    directories fail closed. The runner compiles the test without executing it.
    """
    def blocks(source):
        if "\t" in source or "\r" in source:
            raise ValueError("unreviewed Cabal layout")
        result = []
        for line in source.splitlines():
            line = line.rstrip()
            if not line or line.lstrip().startswith("--"):
                continue
            if not line.startswith(" "):
                result.append([line, []])
            elif not result:
                raise ValueError("Cabal continuation without a field")
            else:
                result[-1][1].append(line)
        if len(result) != len({header for header, _ in result}):
            raise ValueError("duplicate Cabal block")
        return result

    try:
        old, new = blocks(before), blocks(after)
        old_map, new_map = dict(old), dict(new)
        flag = old_map.get("flag full-core-tests", [])
        if (len(flag) != 3 or not flag[0].startswith("  description: ")
                or flag[1:] != ["  default: False", "  manual: True"]
                or statuses.get("thc.cabal") != "M"):
            return None
        added = [header for header, _ in new if header not in old_map]
        if not added:
            return None
        owned, targets, prefixes = {"thc.cabal"}, [], []
        for header in added:
            match = re.fullmatch(r"test-suite ([a-z][a-z0-9-]*-full-core)", header)
            body = new_map[header]
            mains = [line[11:] for line in body if line.startswith("  main-is: ")]
            if (not match or len(mains) != 1
                    or not re.fullmatch(r"[A-Z][A-Za-z0-9_]*FullCore\.hs", mains[0])
                    or body != FULL_CORE_TEST_BODY.format(main=mains[0]).splitlines()):
                return None
            name = match[1]
            harness = "t/haskell-driver/" + mains[0]
            prefix = "t/fixtures/run-" + name.removesuffix("-full-core") + "/"
            if harness in owned or any(path.startswith(prefix) for path in old_paths):
                return None
            owned.add(harness)
            prefixes.append(prefix)
            targets.append("test:" + name)
        old_extra, new_extra = old_map["extra-source-files:"], new_map["extra-source-files:"]
        additions = [line for line in new_extra if line not in old_extra]
        if not additions or len(additions) != len(set(additions)):
            return None
        for line in additions:
            if not re.fullmatch(r"  t/fixtures/run-[a-z0-9-]+/[A-Za-z0-9_./-]+", line):
                return None
            path = line[2:]
            if (str(PurePosixPath(path)) != path or ".." in PurePosixPath(path).parts
                    or not any(path.startswith(prefix) for prefix in prefixes)):
                return None
            owned.add(path)
        for prefix in prefixes:
            if not {prefix + "cabal.project", prefix + prefix.split("/")[-2] + ".cabal",
                    prefix + "app/Main.hs"} <= owned:
                return None
        restored = [[header, old_extra if header == "extra-source-files:" else body]
                    for header, body in new if header not in added]
        if (restored != old or [line for line in new_extra if line not in additions] != old_extra
                or any(statuses.get(path) != "A" for path in owned - {"thc.cabal"})):
            return None
        return {"paths": owned, "targets": sorted(targets)}
    except (KeyError, ValueError):
        return None


def select(repo, base_ref, head_ref, *, cadence=None):
    repo = Path(repo).resolve()
    reasons = []
    def widen(code, path=None):
        value = dict(code=code)
        if path is not None:
            value["path"] = path
        if value not in reasons:
            reasons.append(value)
    base, head, checkout = resolve(repo, base_ref), resolve(repo, head_ref), resolve(repo, "HEAD")
    if base is None:
        widen("missing-base")
    if head is None:
        widen("missing-head")
    if head != checkout:
        widen("head-checkout-mismatch")
    inventory_commit = head or checkout
    records, files, texts, infos = [], {}, {}, {}
    inventory_complete = True
    polyglot_inventory_complete = True
    if inventory_commit:
        files = tree(repo, inventory_commit)
    inventory_paths = [path for path in files if junit_source(path) or polyglot_junit_source(path)
                       or path in (SCRIPT, POLICY)]
    blobs = batch_blobs(repo, [files[path][2] for path in inventory_paths
                               if files[path][0] in ("100644", "100755") and files[path][1] == "blob"])
    def text(path):
        if path not in texts:
            mode, kind, oid = files[path]
            if mode not in ("100644", "100755") or kind != "blob":
                raise SelectionError("nonregular source")
            raw = blobs.get(oid)
            if raw is None:
                raw = git(repo, "cat-file", "blob", oid)
            texts[path] = raw.decode("utf-8")
        return texts[path]
    classes, polyglot_classes = {}, {}
    python_files = sorted(path for path in files if python_test(path))
    for path in sorted(files):
        if junit_source(path):
            try:
                infos[path] = junit_info(text(path))
                for name in infos[path][0]:
                    if name in classes or name in polyglot_classes:
                        raise SelectionError("duplicate JUnit class")
                    classes[name] = path
            except (SelectionError, UnicodeError):
                widen("unresolved-junit-inventory", path)
                inventory_complete = False
        elif polyglot_junit_source(path):
            try:
                names, unsafe, _ = junit_info(text(path))
                # This lane runs every optional class, so members shared between
                # those classes do not make its class inventory incomplete.
                if set(unsafe) - {"shared-test-member"} or not names:
                    raise SelectionError("unresolved optional JUnit source")
                for name in names:
                    if name in classes or name in polyglot_classes:
                        raise SelectionError("duplicate optional JUnit class")
                    polyglot_classes[name] = path
            except (SelectionError, UnicodeError):
                widen("unresolved-polyglot-inventory", path)
                inventory_complete = False
                polyglot_inventory_complete = False
    if not classes:
        widen("empty-junit-inventory")
    if not python_files:
        widen("empty-python-inventory")
    # Read the actual policy and selector bytes, bind their paths into the digest,
    # and require them to match this checkout's committed execution input.
    policy_hash = None
    policy = None
    try:
        digest = hashlib.sha256()
        for path in (SCRIPT, POLICY):
            actual = (repo / path).read_bytes()
            digest.update(path.encode() + b"\x00" + str(len(actual)).encode() + b"\x00" + actual)
            if path not in files or actual != text(path).encode():
                widen("policy-checkout-mismatch", path)
        executed = Path(__file__).read_bytes()
        digest.update(b"executed-selector\x00" + str(len(executed)).encode() + b"\x00" + executed)
        if executed != (repo / SCRIPT).read_bytes():
            widen("executed-selector-mismatch")
        policy_hash = digest.hexdigest()
        policy = json.loads((repo / POLICY).read_text())
        if set(policy) != {"schema", "smoke", "leafSources", "owners", "primopFamilies", "automation", "cadence"} or type(policy["schema"]) is not int or policy["schema"] != 2:
            raise SelectionError("invalid policy schema")
        if any(not isinstance(policy[key], dict) for key in ("leafSources", "owners", "primopFamilies", "automation")):
            raise SelectionError("invalid ownership map")
        for group in [policy["smoke"], *policy["leafSources"].values(), *policy["owners"].values(),
                      *policy["primopFamilies"].values(), *policy["automation"].values()]:
            if not isinstance(group, dict) or set(group) not in ({"junit", "python"}, {"junit", "python", "haskell"}):
                raise SelectionError("invalid test group")
            for key, available in (("junit", classes), ("python", python_files)):
                values = group[key]
                if not isinstance(values, list) or any(not isinstance(v, str) or v not in available for v in values) or len(values) != len(set(values)):
                    raise SelectionError("nonexistent or duplicate selected test")
            if not group["junit"] and not group["python"]:
                if not group.get("haskell"):
                    raise SelectionError("empty test group")
            haskell = group.get("haskell", [])
            if not isinstance(haskell, list) or len(haskell) != len(set(haskell)) or any(
                    name not in HASKELL_TESTS for name in haskell):
                raise SelectionError("invalid Haskell test suite")
        if not policy["smoke"]["junit"] or not policy["smoke"]["python"]:
            raise SelectionError("empty smoke")
        scheduled = policy["cadence"]
        if not isinstance(scheduled, dict) or set(scheduled) not in (
                {"hourlyJunit", "nightlyFixtures"}, {"hourlyJunit", "nightlyFixtures", "partialJunit"}):
            raise SelectionError("invalid cadence policy")
        hourly, nightly = scheduled["hourlyJunit"], scheduled["nightlyFixtures"]
        if (not isinstance(hourly, list) or any(not isinstance(c, str) or c not in classes for c in hourly)
                or len(hourly) != len(set(hourly)) or set(hourly) & set(policy["smoke"]["junit"])
                or not isinstance(nightly, list) or any(not isinstance(n, str) or not re.fullmatch(r"[a-z][a-z0-9-]*", n) for n in nightly)
                or len(nightly) != len(set(nightly))):
            raise SelectionError("invalid scheduled tests")
        partial = scheduled.get("partialJunit", {})
        if not isinstance(partial, dict):
            raise SelectionError("invalid partial tests")
        for name, methods in partial.items():
            if (name not in classes or name in hourly or not isinstance(methods, list) or not methods
                    or any(not isinstance(m, str) or not re.fullmatch(r"[A-Za-z_$][\w$]*", m) for m in methods)
                    or len(methods) != len(set(methods))):
                raise SelectionError("invalid partial tests")
            source = code_only(text(classes[name]))
            if any(not re.search(r"@Test\s+(?:public\s+)?void\s+" + re.escape(m) + r"\s*\(\s*\)", source)
                   for m in methods):
                raise SelectionError("partial selection must name existing ordinary test methods")
        if any(path not in files or not path.startswith("src/main/") for path in policy["leafSources"]):
            raise SelectionError("nonexistent or nonproduction leaf source")
        if any(not isinstance(path, str) or not path or path.startswith("/") or ".." in PurePosixPath(path).parts
               or path in policy["leafSources"] for path in policy["owners"]):
            raise SelectionError("invalid owner path")
        if set(policy["primopFamilies"]) != {"bit-primops", "integer-primops", "signed-narrow-primops", "explicit64-primops", "simd-generated-primops"}:
            raise SelectionError("incomplete primop families")
        if any(not isinstance(path, str) or not path.startswith(".github/") for path in policy["automation"]):
            raise SelectionError("invalid automation path")
    except (OSError, ValueError, TypeError, KeyError, SelectionError):
        widen("invalid-selection-policy")
        policy = None
    if base and head:
        try:
            git(repo, "merge-base", "--is-ancestor", base, head)
        except SelectionError:
            widen("base-not-ancestor")
        records = paths_from_diff(git(repo, "diff", "--no-ext-diff", "--no-textconv", "--name-status", "-z", "--find-renames", base, head, "--"))
    if git(repo, "status", "--porcelain=v1", "-z", "--untracked-files=all"):
        widen("dirty-checkout")
    selected_junit = set(policy["smoke"]["junit"] if policy else [])
    selected_python = set(policy["smoke"]["python"] if policy else [])
    selected_haskell = set(policy["smoke"].get("haskell", []) if policy else [])
    affected_junit, affected_python, affected_haskell = set(), set(), set()
    additive_primop_paths = set()
    changed = {path for record in records for path in record["paths"]}
    full_core_paths, compile_targets = set(), []
    if policy and base and head and "thc.cabal" in changed:
        try:
            statuses = {record["paths"][0]: record["status"] for record in records if len(record["paths"]) == 1}
            proof = additive_full_core_tests(git(repo, "show", base + ":thc.cabal").decode("utf-8"),
                                            text("thc.cabal"), statuses, tree(repo, base))
            if proof and all(files[path][:2] in (("100644", "blob"), ("100755", "blob"))
                             for path in proof["paths"]):
                full_core_paths, compile_targets = proof["paths"], proof["targets"]
        except (KeyError, SelectionError, UnicodeError):
            pass
    simd_additions = None
    if (policy and base and head and SIMD_ADDITIVE <= changed and SIMD_GENERATOR not in changed
            and all(record["status"] == "M" for record in records if record["paths"][0] in SIMD_ADDITIVE)):
        try:
            old = {path: git(repo, "show", base + ":" + path).decode("utf-8") for path in SIMD_ADDITIVE}
            simd_additions = additive_simd_primops(
                old[SIMD_SPEC], text(SIMD_SPEC), old[CAPABILITIES], text(CAPABILITIES),
                old[BYTECODE_ROOT], text(BYTECODE_ROOT), old[BYTECODE_PROGRAM], text(BYTECODE_PROGRAM))
        except (SelectionError, UnicodeError, ValueError, TypeError, KeyError):
            pass
    for record in records:
        if record["status"] not in ("A", "M"):
            for path in record["paths"]:
                widen("deleted-renamed-or-typechanged", path)
        for path in record["paths"]:
            if path not in files:
                continue
            mode, kind, _ = files[path]
            if mode not in ("100644", "100755") or kind != "blob":
                widen("nonregular-changed-path", path)
            if path in full_core_paths:
                pass  # Exact opt-in addition; compile its harness below.
            elif policy and simd_additions and path in SIMD_ADDITIVE:
                group = policy["primopFamilies"]["simd-generated-primops"]
                affected_junit.update(group["junit"])
                affected_python.update(group["python"])
                affected_haskell.update(group.get("haskell", []))
                additive_primop_paths.add(path)
            elif policy and path in (SIMD_SPEC, SIMD_GENERATOR):
                widen("unverified-simd-generation-change", path)
            elif policy and path in (BYTECODE_ROOT, BYTECODE_PROGRAM) and path in changed and SIMD_SPEC in changed:
                widen("unverified-simd-generated-code", path)
            elif policy and path in policy["automation"]:
                group = policy["automation"][path]
                affected_junit.update(group["junit"])
                affected_python.update(group["python"])
                affected_haskell.update(group.get("haskell", []))
            elif policy and path in policy["owners"]:
                group = policy["owners"][path]
                affected_junit.update(group["junit"])
                affected_python.update(group["python"])
                affected_haskell.update(group.get("haskell", []))
            elif junit_source(path):
                info = infos.get(path)
                if not info or not info[0]:
                    widen("test-helper-or-unresolved-class", path)
                else:
                    affected_junit.update(info[0])
                    for why in info[1]:
                        widen(why, path)
                    if base and record["status"] == "M":
                        try:
                            previous = git(repo, "show", base + ":" + path).decode("utf-8")
                            if set(junit_info(previous)[0]) - set(info[0]):
                                widen("removed-junit-class", path)
                        except (SelectionError, UnicodeError):
                            widen("unresolved-prior-test", path)
                    for name in info[0]:
                        short = name.rsplit(".", 1)[-1]
                        if any(other != path and re.search(r"\b" + re.escape(short) + r"\b", other_info[2])
                               for other, other_info in infos.items()):
                            widen("test-class-used-as-helper", path)
            elif polyglot_junit_source(path):
                pass  # Its complete committed inventory runs in the optional JS lane.
            elif python_test(path):
                affected_python.add(path)
                try:
                    if not standalone_python_test(text(path)):
                        widen("python-test-helper-or-unknown-runner", path)
                    name = PurePosixPath(path).stem
                    if any(other != path and other.endswith(".py") and name in text(other) for other in files):
                        widen("python-test-used-as-helper", path)
                except (SelectionError, UnicodeError, SyntaxError):
                    widen("unresolved-python-test", path)
            elif policy and path in policy["leafSources"]:
                group = policy["leafSources"][path]
                affected_junit.update(group["junit"])
                affected_python.update(group["python"])
                affected_haskell.update(group.get("haskell", []))
            elif policy and base and record["status"] == "M" and path in (CAPABILITIES, PROGRAM, BYTECODE_PROGRAM):
                try:
                    before = git(repo, "show", base + ":" + path).decode("utf-8")
                    families = additive_capability_families(before, text(path)) if path == CAPABILITIES else None
                    if not families:
                        widen("shared-primop-registry-change", path)
                    else:
                        additive_primop_paths.add(path)
                        for family in families:
                            group = policy["primopFamilies"][family]
                            affected_junit.update(group["junit"])
                            affected_python.update(group["python"])
                            affected_haskell.update(group.get("haskell", []))
                except (SelectionError, UnicodeError, ValueError, TypeError):
                    widen("shared-primop-registry-change", path)
            elif (path.endswith(".md") and (path.startswith("docs/") or "/" not in path)):
                pass  # Explicit documentation-only lane still executes all smoke.
            else:
                widen("unmapped-source-or-configuration", path)
    selected_junit.update(affected_junit)
    selected_python.update(affected_python)
    selected_haskell.update(affected_haskell)
    changed_paths = {path for record in records for path in record["paths"]}
    uncertain_diff = (base is None or head is None or head != checkout
                      or any(reason["code"] == "base-not-ancestor" for reason in reasons))
    polyglot_required = any(path not in full_core_paths and path not in additive_primop_paths
                            and polyglot_input(path, policy["leafSources"] if policy else {})
                            for path in changed_paths) or (bool(polyglot_classes) and uncertain_diff)
    if polyglot_required and not polyglot_classes:
        widen("empty-polyglot-inventory")
    mode = "full" if reasons else "narrow"
    if mode == "full":
        selected_junit = set(classes)
        selected_python = set(python_files)
        selected_haskell = set(HASKELL_TESTS)
    if not selected_junit or not selected_python:
        widen("empty-selection")
        mode = "full"
    quarantined = set()
    if (repo / ".github/scripts/fast-fixtures.json").is_file():
        import fast_fixtures
        quarantined = fast_fixtures.quarantined_classes(repo)
    withheld = sorted(selected_junit & quarantined)
    selected_junit.difference_update(quarantined)
    # Validate current files too: a missing/symlinked test must never produce a
    # runnable success plan, even when the committed object still exists.
    selected_paths = set(selected_python) | {classes[name] for name in selected_junit}
    selected_paths.update(HASKELL_TESTS[name] for name in selected_haskell)
    if polyglot_required:
        selected_paths.update(polyglot_classes.values())
    existing = all((repo / path).is_file() and not (repo / path).is_symlink() for path in selected_paths)
    if not existing:
        widen("selected-test-file-missing-or-symlinked")
        mode = "full"
    result = dict(schema=1, mode=mode, quarantined=withheld,
                runnable=bool(selected_junit and selected_python and existing
                              and (not polyglot_required or (polyglot_classes and polyglot_inventory_complete))),
                base=base, head=head, requestedBase=base_ref, requestedHead=head_ref,
                inventoryCommit=inventory_commit, inventoryComplete=inventory_complete,
                changedPaths=sorted(changed_paths),
                changes=records, reasons=sorted(reasons, key=lambda r: (r["code"], r.get("path", ""))),
                policySha256=policy_hash,
                affected=dict(junit=sorted(affected_junit), python=sorted(affected_python),
                              haskell=sorted(affected_haskell)),
                junit=dict(patterns=["*"] if mode == "full" else sorted(selected_junit),
                           classes=sorted(selected_junit), sourceFiles=sorted({classes[name] for name in selected_junit}), count=len(selected_junit)),
                polyglot=dict(required=polyglot_required, classes=sorted(polyglot_classes) if polyglot_required else []),
                python=dict(commands=[["python3", path] for path in sorted(selected_python)],
                            files=sorted(selected_python), count=len(selected_python)),
                haskell=dict(suites=sorted(selected_haskell), count=len(selected_haskell), compileTargets=compile_targets))
    if cadence is None:
        return result
    unsafe = {"invalid-selection-policy", "policy-checkout-mismatch", "executed-selector-mismatch",
              "dirty-checkout", "head-checkout-mismatch", "missing-head"}
    if (cadence not in ("commit", "hourly", "nightly") or not result["runnable"]
            or not inventory_complete or policy is None or any(r["code"] in unsafe for r in reasons)):
        raise SelectionError("Cannot apply cadence to an invalid selection")
    import fast_fixtures
    manifest, owners = fast_fixtures._manifest(repo)
    if set(owners) != set(classes):
        raise SelectionError("Incomplete fixture ownership for cadence selection")
    assigned = cadence_assignments(manifest, owners, policy)
    partial = policy["cadence"].get("partialJunit", {})
    deferred = {scope: {"junit": sorted(c for c in selected_junit
                                      if assigned[c] == scope or scope == "nightly" and c in partial)}
                for scope in ("commit", "hourly", "nightly") if scope != cadence}
    if polyglot_required and cadence != "nightly":
        deferred["nightly"]["polyglot"] = sorted(polyglot_classes)
        result["polyglot"] = dict(required=False, classes=[])
    actual = sorted(c for c in selected_junit if assigned[c] == cadence or cadence == "nightly" and c in partial)
    if not actual:
        raise SelectionError("Empty cadence selection")
    result.update(mode="narrow", requestedMode=mode, cadence=cadence, deferred=deferred,
                  junit=dict(patterns=cadence_patterns(actual, policy, cadence), classes=actual,
                             sourceFiles=sorted({classes[c] for c in actual}), count=len(actual)))
    return result


def fixture_components(manifest):
    components = {name: {name} for name in manifest["groups"]}
    for name, group in manifest["groups"].items():
        for dep in group.get("requires", []):
            merged = components[name] | components[dep]
            for member in merged:
                components[member] = merged
    return components


def cadence_assignments(manifest, owners, policy):
    import fast_fixtures
    scheduled = policy["cadence"]
    nightly = set(scheduled["nightlyFixtures"])
    if nightly - set(manifest["groups"]):
        raise SelectionError("Unknown nightly fixture: " + repr(sorted(nightly - set(manifest["groups"]))))
    # Only consumers inherit an expensive prerequisite's cadence. A shared tool
    # must not pull its other consumers into nightly alongside one costly user.
    for name in fast_fixtures._group_order(manifest, manifest["groups"]):
        if nightly.intersection(manifest["groups"][name].get("requires", [])):
            nightly.add(name)
    assigned = {name: "nightly" if owner in nightly else
                "hourly" if name in scheduled["hourlyJunit"] else "commit"
                for name, owner in owners.items()}
    if any(assigned.get(name) != "commit" for name in policy["smoke"]["junit"]):
        raise SelectionError("Committed smoke must remain per-commit")
    if any(assigned.get(name) != "commit" for name in scheduled.get("partialJunit", {})):
        raise SelectionError("Partial suites must have per-commit fixture dependencies")
    return assigned


def cadence_patterns(classes, policy, cadence):
    partial = policy["cadence"].get("partialJunit", {}) if cadence == "commit" else {}
    return [pattern for name in classes
            for pattern in ([name + "." + method for method in partial[name]] if name in partial else [name])]


def groups(repo, *, system=None, cadence=None):
    """Partition the complete ordinary inventory using its sole fixture manifest."""
    import fast_fixtures
    inventory = select(repo, "", "HEAD")
    if not inventory["runnable"] or not inventory["inventoryComplete"]:
        raise SelectionError("Incomplete grouped JUnit inventory")
    if cadence is not None and any(r["code"] in {
            "invalid-selection-policy", "policy-checkout-mismatch", "executed-selector-mismatch",
            "dirty-checkout", "head-checkout-mismatch", "missing-head"} for r in inventory["reasons"]):
        raise SelectionError("Cannot apply cadence to an invalid inventory")
    manifest, owners = fast_fixtures._manifest(Path(repo))
    classes = set(inventory["junit"]["classes"])
    expected = set(owners) - fast_fixtures.quarantined_classes(repo)
    if classes != expected:
        raise SelectionError(f"Fixture ownership mismatch: unmapped={sorted(classes-expected)}, stale={sorted(expected-classes)}")
    # A shared expensive provider is acquired once for its connected consumers.
    components = fixture_components(manifest)
    if cadence is not None and cadence not in ("commit", "hourly", "nightly"):
        raise SelectionError("Unknown cadence: " + cadence)
    policy = json.loads((Path(repo) / POLICY).read_text())
    assigned = cadence_assignments(manifest, owners, policy)
    included = {c for c in classes if cadence is None or assigned[c] == cadence
                or cadence == "nightly" and c in policy["cadence"].get("partialJunit", {})}
    result = {min(component): sorted({c for name in component for c in manifest["groups"][name]["junit"] if c in included})
              for component in components.values()
              if system is None or any(system in manifest["groups"][name].get("ciPlatforms", [system])
                                       for name in component)}
    result = {name: tests for name, tests in result.items() if tests}
    free = sorted(set(manifest["fixtureFreeJunit"]) & included)
    for offset in range(0, len(free), 50):
        name = "fixture-free-" + free[offset].rsplit(".", 1)[-1].lower()
        if name in result:
            raise SelectionError("Duplicate CI group: " + name)
        result[name] = free[offset:offset+50]
    if cadence == "commit" and result:
        return {"commit": sorted({name for tests in result.values() for name in tests})}
    return result


def group_matrix(repo, *, cadence=None):
    """Share worker setup while keeping each original group independently runnable."""
    names = list(groups(repo, system=platform.system(), cadence=cadence))
    count = min(10 if cadence == "hourly" and platform.system() == "Darwin" else 20, len(names))
    return {"include": [{"batch": f"batch-{index+1:02d}", "groups": names[index::count]}
                        for index in range(count)]}


def group_selection(repo, name, *, cadence=None, exact_class=None):
    system = platform.system()
    selected = groups(repo, system=system if cadence == "commit" or exact_class is not None else None, cadence=cadence)
    if name not in selected:
        raise SelectionError("Unknown CI group: " + name)
    if exact_class is not None:
        if exact_class not in selected[name]:
            raise SelectionError("Select one exact class admitted in the chosen group/cadence/platform")
        import fast_fixtures
        manifest, owners = fast_fixtures._manifest(Path(repo))
        owner = owners[exact_class]
        if owner and system not in manifest["groups"][owner].get("ciPlatforms", [system]):
            raise SelectionError("Selected class is not admitted on this platform")
        selected[name] = [exact_class]
    # Repeat the process-mode proof, but run the full transport suite only in
    # its owning group.
    handoff = "thc.runtime.HandoffTest"
    classes = sorted(set(selected[name]) | {handoff})
    policy = json.loads((Path(repo) / POLICY).read_text())
    patterns = cadence_patterns(classes, policy, cadence)
    if handoff not in selected[name]:
        patterns = [p for p in patterns if p != handoff and not p.startswith(handoff + ".")]
        patterns.append(handoff + ".requestedModeReachesTestProcessAndContext")
    return dict(mode="narrow", runnable=True, junit=dict(classes=classes, patterns=patterns))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=ROOT)
    parser.add_argument("--matrix", action="store_true")
    parser.add_argument("--cadence", choices=("commit", "hourly", "nightly"))
    parser.add_argument("--base", default="")
    parser.add_argument("--head", default="HEAD")
    args = parser.parse_args()
    try:
        if args.matrix:
            print(json.dumps(group_matrix(args.repo, cadence=args.cadence)))
            return 0
        result = select(args.repo, args.base, args.head, cadence=args.cadence)
    except (OSError, ValueError, TypeError, KeyError, SelectionError) as error:
        # An unusable repository cannot safely produce test counts. Fail the job,
        # not a success-shaped empty selection. No exception changes to narrow.
        result = dict(schema=1, mode="full", runnable=False, requestedBase=args.base,
                      requestedHead=args.head, reasons=[dict(code="selection-error", detail=str(error))])
    print(json.dumps(result, sort_keys=True, ensure_ascii=True))
    return 0 if result["runnable"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
