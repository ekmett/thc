#!/usr/bin/env python3
"""Conservative fast-CI selection, not a replacement for the canonical Build.

The runner must honor mode=full, run both handoff modes, and verify fresh JUnit
coverage for EVERY selected class (not merely a nonempty aggregate report).
Commands are argv arrays, never shell source. No budget can remove changed tests.
"""
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ".github/scripts/fast_select.py"
POLICY = ".github/scripts/fast-tests.json"
TEST_ANNOTATION = r"@\s*(?:org\.junit\.(?:jupiter\.api|jupiter\.params)\.)?(?:Test|TestFactory|TestTemplate|ParameterizedTest|RepeatedTest)\b"
LIFECYCLE = r"@\s*(?:org\.junit\.jupiter\.api\.)?(?:BeforeEach|AfterEach|BeforeAll|AfterAll)\b"
DECLARATION = re.compile(r"\b(class|object|interface|fun|val|var|typealias)\s+([A-Za-z_]\w*)")


class SelectionError(Exception):
    pass


def git(repo, *args):
    process = subprocess.run(["git", "--no-replace-objects", "-C", str(repo), *args],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE,
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


def python_test(path):
    name = PurePosixPath(path).name
    # Historical snapshots are data, not runnable test sources. Changes to these
    # paths still widen through the ordinary unmapped-path rule.
    historical = path.startswith("bench/results/") or any(
        part.startswith("evidence-") for part in PurePosixPath(path).parts)
    return not historical and name.endswith(".py") and (name.startswith(("test-", "test_")) or name.endswith("_test.py"))


def junit_source(path):
    return path.startswith("src/test/") and path.endswith((".kt", ".java"))


def code_only(source):
    """Mask comments/strings before structural discovery; preserve offsets/newlines.

    Handles Kotlin nested block comments, raw strings and escaped character/string
    literals. Backtick identifiers stay visible and trigger conservative fallback.
    This is deliberately not a Kotlin semantic parser.
    """
    result = list(source)
    index = 0
    while index < len(source):
        start = index
        if source.startswith("//", index):
            end = source.find("\n", index)
            index = len(source) if end < 0 else end
        elif source.startswith("/*", index):
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
            index += 1
            continue
        result[start:index] = ["\n" if c == "\n" else " " for c in source[start:index]]
    return "".join(result)


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
    for item in re.finditer(r"\b(?:class|object|interface|fun|val|var|typealias)\b", code):
        if depths[item.start()] in (0, 1) and item.start() not in declaration_starts:
            unsafe.append("unresolved-test-declaration")
    for item in declarations:
        if depths[item.start()] == 0 and item.start() not in test_starts:
            if not private_at(item.start()):
                unsafe.append("shared-test-helper")
    for _, start, end in ranges:
        previous = start + 1
        for item in declarations:
            if not start < item.start() < end or depths[item.start()] != 1:
                continue
            prefix = "".join(code[i] if depths[i] == 1 else " " for i in range(previous, item.start()))
            if not private_at(item.start()) and not (
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
    if inventory_commit:
        files = tree(repo, inventory_commit)
    def text(path):
        if path not in texts:
            mode, kind, oid = files[path]
            if mode not in ("100644", "100755") or kind != "blob":
                raise SelectionError("nonregular source")
            texts[path] = git(repo, "cat-file", "blob", oid).decode("utf-8")
        return texts[path]
    classes = {}
    python_files = sorted(path for path in files if python_test(path))
    for path in sorted(files):
        if junit_source(path):
            try:
                infos[path] = junit_info(text(path))
                for name in infos[path][0]:
                    if name in classes:
                        raise SelectionError("duplicate JUnit class")
                    classes[name] = path
            except (SelectionError, UnicodeError):
                widen("unresolved-junit-inventory", path)
                inventory_complete = False
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
        if set(policy) != {"schema", "smoke", "leafSources"} or type(policy["schema"]) is not int or policy["schema"] != 1:
            raise SelectionError("invalid policy schema")
        if not isinstance(policy["leafSources"], dict):
            raise SelectionError("invalid leaf map")
        for group in [policy["smoke"], *policy["leafSources"].values()]:
            if not isinstance(group, dict) or set(group) != {"junit", "python"}:
                raise SelectionError("invalid test group")
            for key, available in (("junit", classes), ("python", python_files)):
                values = group[key]
                if not isinstance(values, list) or any(not isinstance(v, str) or v not in available for v in values) or len(values) != len(set(values)):
                    raise SelectionError("nonexistent or duplicate selected test")
            if not group["junit"] and not group["python"]:
                raise SelectionError("empty test group")
        if not policy["smoke"]["junit"] or not policy["smoke"]["python"]:
            raise SelectionError("empty smoke")
        if any(path not in files or not path.startswith("src/main/") for path in policy["leafSources"]):
            raise SelectionError("nonexistent or nonproduction leaf source")
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
    affected_junit, affected_python = set(), set()
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
            if path in (SCRIPT, POLICY, ".github/scripts/test_fast_select.py"):
                widen("selection-policy-changed", path)
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
            elif path.endswith(".md") and (path.startswith("docs/") or "/" not in path):
                pass  # Explicit documentation-only lane still executes all smoke.
            else:
                widen("unmapped-source-or-configuration", path)
    selected_junit.update(affected_junit)
    selected_python.update(affected_python)
    mode = "full" if reasons else "narrow"
    if mode == "full":
        selected_junit = set(classes)
        selected_python = set(python_files)
    if not selected_junit or not selected_python:
        widen("empty-selection")
        mode = "full"
    # Validate current files too: a missing/symlinked test must never produce a
    # runnable success plan, even when the committed object still exists.
    selected_paths = set(selected_python) | {classes[name] for name in selected_junit}
    existing = all((repo / path).is_file() and not (repo / path).is_symlink() for path in selected_paths)
    if not existing:
        widen("selected-test-file-missing-or-symlinked")
        mode = "full"
    return dict(schema=1, mode=mode, runnable=bool(selected_junit and selected_python and existing),
                base=base, head=head, requestedBase=base_ref, requestedHead=head_ref,
                inventoryCommit=inventory_commit, inventoryComplete=inventory_complete,
                changedPaths=sorted({path for record in records for path in record["paths"]}),
                changes=records, reasons=sorted(reasons, key=lambda r: (r["code"], r.get("path", ""))),
                policySha256=policy_hash,
                affected=dict(junit=sorted(affected_junit), python=sorted(affected_python)),
                junit=dict(patterns=["*"] if mode == "full" else sorted(selected_junit),
                           classes=sorted(selected_junit), sourceFiles=sorted({classes[name] for name in selected_junit}), count=len(selected_junit)),
                python=dict(commands=[["python3", path] for path in sorted(selected_python)],
                            files=sorted(selected_python), count=len(selected_python)))


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
