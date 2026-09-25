#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Conservative fast-CI selection, not a replacement for the canonical Build.

The runner must honor mode=full, run both handoff modes, and verify fresh JUnit
coverage for EVERY selected class (not merely a nonempty aggregate report).
Commands are argv arrays, never shell source. No budget can remove changed tests.
"""
import argparse
import ast
import difflib
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ".github/scripts/fast_select.py"
POLICY = ".github/scripts/fast-tests.json"
CAPABILITIES = "scripts/core-capabilities.json"
PROGRAM = "src/main/kotlin/thc/runtime/Program.kt"
BYTECODE_PROGRAM = "src/main/kotlin/thc/runtime/BytecodeProgram.kt"
BYTECODE_ROOT = "src/main/java/thc/runtime/BytecodeRoot.java"
SIMD_SPEC = "scripts/simd-families.json"
SIMD_GENERATOR = "scripts/generate-simd-families.py"
SIMD_ADDITIVE = {CAPABILITIES, BYTECODE_PROGRAM, BYTECODE_ROOT, SIMD_SPEC}
POLYGLOT_TEST_ROOT = "src/polyglotTest/"
HASKELL_TESTS = {"driver-tests": "test/haskell-driver/Main.hs"}
POLYGLOT_EXACT_INPUTS = {
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew",
    "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
    "Makefile", "thc.cabal", "cabal.project", "Setup.hs", "compiler/plugin.py",
    "compiler/toolchain.sh", "scripts/audit-core.py",
    "scripts/polyglot-demo.sh", "scripts/javascript-demo.sh",
    "compiler/build.sh", "compiler/export.sh", "compiler/test-javascript-ffi.py",
    "src/main/kotlin/thc/Language.kt", "src/main/kotlin/thc/Json.kt",
    "src/main/kotlin/thc/PolyglotDemo.kt",
    "src/main/java/thc/runtime/Calls.java", "src/main/java/thc/runtime/BytecodeRoot.java",
    "src/main/java/thc/runtime/RuntimeTypes.java",
    "src/main/resources/thc/polyglot-abi.json",
}
POLYGLOT_INPUT_PREFIXES = (
    POLYGLOT_TEST_ROOT, "compiler/THC/", "examples/THC/Polyglot",
    "examples/THC/JavaScript", "src/main/kotlin/thc/runtime/",
)
TEST_ANNOTATION = r"@\s*(?:org\.junit\.(?:jupiter\.api|jupiter\.params)\.)?(?:Test|TestFactory|TestTemplate|ParameterizedTest|RepeatedTest)\b"
LIFECYCLE = r"@\s*(?:org\.junit\.jupiter\.api\.)?(?:BeforeEach|AfterEach|BeforeAll|AfterAll)\b"
DECLARATION = re.compile(r"\b(class|object|interface|fun|val|var|typealias)\s+"
                         r"(?:<[A-Za-z_]\w*(?:\s*,\s*[A-Za-z_]\w*)*>\s+)?([A-Za-z_]\w*)")
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
    historical = path.startswith("bench/results/") or any(
        part.startswith("evidence-") for part in PurePosixPath(path).parts)
    return not historical and name.endswith(".py") and (name.startswith(("test-", "test_")) or name.endswith("_test.py"))


def junit_source(path):
    return path.startswith("src/test/") and path.endswith((".kt", ".java"))


def polyglot_junit_source(path):
    return path.startswith(POLYGLOT_TEST_ROOT) and path.endswith((".kt", ".java"))


def polyglot_input(path, leaf_sources):
    # The common Core loader/lowering can affect JavaScript even when the
    # normal test selector widens. Reviewed scalar/SIMD leaf files cannot.
    return path in POLYGLOT_EXACT_INPUTS or (
        path not in leaf_sources and path.startswith(POLYGLOT_INPUT_PREFIXES))


def lexical_source(source, comments_only=False):
    """Mask Kotlin/Java lexical regions without mistaking strings for comments.

    Structural discovery masks strings and preserves offsets. The comment-only
    comparison retains string bytes and replaces each comment with a stable
    delimiter, so a changed literal or non-comment token cannot pass as prose.
    """
    result = []
    index = 0
    previous = 0
    while index < len(source):
        token = SOURCE_SPECIAL.search(source, index)
        if token is None:
            break
        index = token.start()
        start = index
        kind = None
        if source.startswith("//", index):
            kind = "line"
            end = source.find("\n", index)
            index = len(source) if end < 0 else end
        elif source.startswith("/*", index):
            kind = "block"
            depth = 1
            index += 2
            while index < len(source) and depth:
                if source.startswith("/*", index):
                    depth += 1; index += 2
                elif source.startswith("*/", index):
                    depth -= 1; index += 2
                else:
                    index += 1
            if depth:
                raise SelectionError("unclosed block comment")
        elif source.startswith('"""', index):
            end = source.find('"""', index + 3)
            if end < 0:
                raise SelectionError("unclosed raw string")
            index = end + 3
        elif source[index] in "\"'":
            quote = source[index]
            index += 1
            while index < len(source):
                if source[index] == "\\":
                    index += 2
                elif source[index] == quote:
                    index += 1
                    break
                else:
                    index += 1
            else:
                raise SelectionError("unclosed string")
        else:
            raise SelectionError("unexpected source token")
        result.append(source[previous:start])
        span = source[start:index]
        if comments_only and kind is None and source[start] == '"':
            # A quoted token inside ${...} would terminate this lightweight
            # scan before the outer string actually ends. Admit only simple
            # interpolation paths/calls; otherwise the change stays full.
            for interpolation in re.finditer(r"\$\{", span):
                if not re.match(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*(?:\(\))?)*\}",
                                span[interpolation.end():]):
                    raise SelectionError("complex string interpolation")
        if comments_only and kind == "line":
            result.append("//")
        elif comments_only and kind == "block":
            result.append("/*" + "\n" * span.count("\n") + "*/")
        elif comments_only:
            result.append(span)
        else:
            result.append("".join("\n" if c == "\n" else " " for c in span))
        previous = index
    result.append(source[previous:])
    return "".join(result)


def code_only(source):
    """Mask comments and strings for structural discovery, preserving offsets."""
    return lexical_source(source)


def junit_info(source):
    code = code_only(source)
    packages = re.findall(r"^\s*package\s+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\s*;?\s*$", code, re.M)
    depths = []
    depth = 0
    for char in code:
        depths.append(depth)
        depth += (char == "{") - (char == "}")
        if depth < 0:
            raise SelectionError("unbalanced test source")
    if depth:
        raise SelectionError("unbalanced test source")
    declarations = list(DECLARATION.finditer(code))
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
            if ":" in code[item.end():start] or "extends" in code[item.end():start]:
                unsafe.append("inherited-test-class")
    if re.search(TEST_ANNOTATION, code) and not classes:
        raise SelectionError("unresolved JUnit declaration")
    if re.search(r"@\s*Nested\b", code):
        unsafe.append("nested-test-class")
    if re.search(r"\bcompanion\s+object\b", code):
        unsafe.append("shared-test-companion")
    if "`" in code:
        unsafe.append("backtick-test-declaration")
    # Public top-level helpers (including extension/context helpers) and public
    # non-test members may be consumed by other tests. Never silently omit them.
    def private_at(index):
        # Anchor to the declaration, not its physical line: a preceding private
        # declaration on that same line must not hide a public shared helper.
        return re.search(r"\bprivate(?:\s+(?:inline|tailrec|suspend|operator|infix|const|lateinit|data|sealed|open|abstract|inner|enum|actual|expect|external|override|final))*\s*$",
                         code[max(0, index - 256):index]) is not None
    test_starts = {item.start() for item, _, _ in ranges}
    declaration_starts = {item.start() for item in declarations}
    for item in re.finditer(r"(?<!:)\b(?:class|object|interface|fun|val|var|typealias)\b", code):
        if depths[item.start()] in (0, 1) and item.start() not in declaration_starts:
            unsafe.append("unresolved-test-declaration")
    for item in declarations:
        if depths[item.start()] == 0 and item.start() not in test_starts:
            if not private_at(item.start()):
                unsafe.append("shared-test-helper")
    for _, start, end in ranges:
        private_constructors = []
        for item in declarations:
            if not start < item.start() < end or depths[item.start()] != 1 or item[1] != "class" or not private_at(item.start()):
                continue
            opener = re.match(r"\s*\(", code[item.end():])
            if opener is None:
                continue
            first = item.end() + opener.end() - 1
            parens = 0
            for position in range(first, end):
                char = code[position]
                if char in "{}":
                    break  # Complex constructor: retain conservative widening.
                parens += (char == "(") - (char == ")")
                if parens == 0:
                    private_constructors.append((first, position))
                    break
        previous = start + 1
        for item in declarations:
            if not start < item.start() < end or depths[item.start()] != 1:
                continue
            if item[1] in ("val", "var") and any(first < item.start() < last for first, last in private_constructors):
                previous = item.end()
                continue
            prefix = "".join(code[i] if depths[i] == 1 else " " for i in range(previous, item.start()))
            injected_directory = (item[1] == "var" and re.search(
                r"@\s*(?:org\.junit\.jupiter\.api\.io\.)?TempDir\s+lateinit\s*$", prefix))
            if not private_at(item.start()) and not injected_directory and not (
                    item[1] == "fun" and re.search(TEST_ANNOTATION + "|" + LIFECYCLE, prefix)):
                unsafe.append("shared-test-member")
            previous = item.end()
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


def additive_program_families(before, after):
    """Allow only whole new primitive arms; existing dispatch must stay byte-identical."""
    old_lines, new_lines = before.splitlines(keepends=True), after.splitlines(keepends=True)
    added = []
    for tag, first, last, start, end in difflib.SequenceMatcher(None, old_lines, new_lines, autojunk=False).get_opcodes():
        if tag == "equal":
            continue
        if tag != "insert":
            return None
        added.extend(range(start, end))
    if not added:
        return None
    families = set()
    for index in added:
        prefix = "".join(new_lines[:index])
        word = prefix.rfind("internal fun narrowWordPrimitiveMask(")
        signed = prefix.rfind("internal fun narrowIntPrimitiveShift(")
        primitive = prefix.rfind("private class Primitive(")
        arity = prefix.rfind("val arity = when (operation)")
        execute = prefix.rfind("return when (operation)")
        start = max(word, signed, arity, execute)
        if start < 0 or "else ->" in prefix[start:]:
            return None
        line = new_lines[index].strip()
        match = re.fullmatch(r'("[A-Za-z0-9]+#"(?:,\s*"[A-Za-z0-9]+#")*)\s*->\s*(.+)', line)
        if not match:
            return None
        names = re.findall(r'"([A-Za-z0-9]+#)"', match[1])
        for name in names:
            family = primop_family(name)
            if start in (word, signed):
                width = re.search(r'(?:Word|Int)(8|16|32)#$', name)
                expected = ({"8": "0xffL", "16": "0xffffL", "32": "0xffff_ffffL"} if start == word
                            else {"8": "56", "16": "48", "32": "32"}).get(width[1] if width else "")
                if match[2] != expected or family != ("integer-primops" if start == word else "signed-narrow-primops"):
                    return None
            elif primitive < 0 or start <= primitive or family is None:
                return None
            elif start == arity and match[2] not in ("1", "2"):
                return None
            elif start == execute and any(token in match[2] for token in ("{", "}", ";")):
                return None
            families.add(family)
    return families


def additive_bytecode_families(before, after):
    """Only new name-to-existing-operation arms in the scalar bytecode dispatch."""
    old_lines, new_lines = before.splitlines(keepends=True), after.splitlines(keepends=True)
    added = []
    for tag, first, last, start, end in difflib.SequenceMatcher(None, old_lines, new_lines, autojunk=False).get_opcodes():
        if tag == "equal":
            continue
        if tag != "insert":
            return None
        added.extend(range(start, end))
    if not added:
        return None
    marker = "val operation = when (scalar64PrimitiveOperation(name))"
    old_start = before.find(marker)
    old_end = before.find("else -> throw UnsupportedCore", old_start)
    if old_start < 0 or old_end < 0:
        return None
    operations = set(re.findall(r'->\s*"([A-Za-z][A-Za-z0-9]*)"', before[old_start:old_end]))
    families = set()
    for index in added:
        prefix = "".join(new_lines[:index])
        start = prefix.rfind(marker)
        if start < 0 or "else ->" in prefix[start:]:
            return None
        line = new_lines[index].strip()
        match = re.fullmatch(r'("[A-Za-z0-9]+#"(?:,\s*"[A-Za-z0-9]+#")*)\s*->\s*"([A-Za-z][A-Za-z0-9]*)"', line)
        if not match or match[2] not in operations:
            return None
        for name in re.findall(r'"([A-Za-z0-9]+#)"', match[1]):
            family = primop_family(name)
            if family is None:
                return None
            families.add(family)
    return families


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
            f'        "{op}{name}#" -> ProvenExpression(Expression {{ e ->\n'
            "            val b = e.builder\n"
            f"            b.begin{node}(); operands.forEach {{ it.emit(e) }}; b.end{node}()\n"
            f"        }}, GeneratedVectors.proof{name})\n")
    if (not generated_simd_region(old_root, new_root, root_blocks)
            or not generated_simd_region(old_program, new_program, program_blocks)):
        return None
    return names


def select(repo, base_ref, head_ref):
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
                if unsafe or not names:
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
        if set(policy) != {"schema", "smoke", "leafSources", "owners", "primopFamilies", "automation"} or type(policy["schema"]) is not int or policy["schema"] != 2:
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
    comment_only_paths = set()
    changed = {path for record in records for path in record["paths"]}
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
            if policy and simd_additions and path in SIMD_ADDITIVE:
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
                if path.endswith(".java"):
                    widen("non-kotlin-test-source", path)
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
                    families = (additive_capability_families(before, text(path)) if path == CAPABILITIES
                                else additive_program_families(before, text(path)) if path == PROGRAM
                                else additive_bytecode_families(before, text(path)))
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
            elif path.endswith(".md") and (path.startswith("docs/") or "/" not in path):
                pass  # Explicit documentation-only lane still executes all smoke.
            elif base and record["status"] == "M" and path.startswith("src/main/") and path.endswith(".kt"):
                try:
                    previous = git(repo, "show", base + ":" + path).decode("utf-8")
                    if lexical_source(previous, comments_only=True) == lexical_source(text(path), comments_only=True):
                        comment_only_paths.add(path)
                    else:
                        widen("unmapped-source-or-configuration", path)
                except (SelectionError, UnicodeError):
                    widen("unmapped-source-or-configuration", path)
            else:
                widen("unmapped-source-or-configuration", path)
    selected_junit.update(affected_junit)
    selected_python.update(affected_python)
    selected_haskell.update(affected_haskell)
    changed_paths = {path for record in records for path in record["paths"]}
    uncertain_diff = (base is None or head is None or head != checkout
                      or any(reason["code"] == "base-not-ancestor" for reason in reasons))
    polyglot_required = any(path not in additive_primop_paths and path not in comment_only_paths
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
    return dict(schema=1, mode=mode,
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
                haskell=dict(suites=sorted(selected_haskell), count=len(selected_haskell)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=ROOT)
    parser.add_argument("--base", default="")
    parser.add_argument("--head", default="HEAD")
    args = parser.parse_args()
    try:
        result = select(args.repo, args.base, args.head)
    except (OSError, ValueError, TypeError, KeyError, SelectionError) as error:
        # An unusable repository cannot safely produce test counts. Fail the job,
        # not a success-shaped empty selection. No exception changes to narrow.
        result = dict(schema=1, mode="full", runnable=False, requestedBase=args.base,
                      requestedHead=args.head, reasons=[dict(code="selection-error", detail=str(error))])
    print(json.dumps(result, sort_keys=True, ensure_ascii=True))
    return 0 if result["runnable"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
