"""Prepare only native fixtures needed by selected JUnit classes.

The persistent stamps are local acceleration hints. Every reuse checks both the
declared source bytes and every output byte; an unrecognised class runs the
complete preparation script instead of assuming it has no native inputs.
"""

import hashlib
import json
from pathlib import Path
import re


MANIFEST = Path(".github/scripts/fast-fixtures.json")
STAMP_DIR = Path("build/fast/fixtures")
COMMON_SOURCES = (
    "compiler/THC/**/*.hs",
    "compiler/build.sh",
    "compiler/export.sh",
    "compiler/toolchain.sh",
    "scripts/audit-core.py",
    "scripts/core_*.py",
    "scripts/core-capabilities.json",
    "scripts/generate-scalar-signatures.py",
    "src/main/resources/thc/scalar-primop-signatures.json",
)


def _relative(value):
    path = Path(value)
    if not isinstance(value, str) or not value or path.is_absolute() or ".." in path.parts or "\x00" in value:
        raise ValueError(f"Unsafe fixture path: {value!r}")
    return path


def _digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _manifest(root):
    data = json.loads((root / MANIFEST).read_text())
    if data.get("schema") != 1 or not isinstance(data.get("groups"), dict):
        raise ValueError("Invalid fast fixture manifest")
    owners = {}
    free = data.get("fixtureFreeJunit", [])
    if not isinstance(free, list):
        raise ValueError("Invalid fixture-free class inventory")
    for name in free:
        if not isinstance(name, str) or name in owners:
            raise ValueError(f"Duplicate/invalid fixture-free class: {name!r}")
        owners[name] = None
    for group_id, group in data["groups"].items():
        if not re.fullmatch(r"[a-z][a-z0-9-]*", group_id):
            raise ValueError(f"Invalid fixture group: {group_id!r}")
        if not all(isinstance(group.get(key), list) and group[key] for key in
                   ("junit", "commands", "outputs", "sources")):
            raise ValueError(f"Incomplete fixture group: {group_id}")
        for name in group["junit"]:
            if not isinstance(name, str) or name in owners:
                raise ValueError(f"Duplicate/invalid fixture class: {name!r}")
            owners[name] = group_id
        for path in [*group["outputs"], *group["sources"]]:
            _relative(path)
        for command in group["commands"]:
            argv = command.get("argv") if isinstance(command, dict) else None
            if not isinstance(argv, list) or not argv or not all(
                    isinstance(part, str) and part and "\x00" not in part for part in argv):
                raise ValueError(f"Invalid fixture command: {group_id}")
            if "stdout" in command:
                destination = _relative(command["stdout"])
                if not any(destination == _relative(output) or
                           _relative(output) in destination.parents for output in group["outputs"]):
                    raise ValueError(f"Undeclared fixture stdout: {group_id}")
    return data, owners


def _source_hashes(root, group):
    files = set()
    for pattern in (*COMMON_SOURCES, *group["sources"]):
        _relative(pattern)
        matches = list(root.glob(pattern))
        if not matches:
            raise RuntimeError(f"Missing fixture source: {pattern}")
        for path in matches:
            if path.is_symlink() or not path.is_file():
                raise RuntimeError(f"Unexpected fixture source: {path}")
            files.add(path.relative_to(root))
    return {str(path): _digest(root / path) for path in sorted(files)}


def cache_key(root, group_id, group, toolchain):
    """Return a source/toolchain identity without hashing the GHC executable."""
    root = Path(root)
    identity = {"schema": 1, "group": group_id, "definition": group,
                "sources": _source_hashes(root, group),
                "toolchain": toolchain}
    serialized = json.dumps(identity, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return hashlib.sha256(serialized.encode()).hexdigest()


def _output_hashes(root, group):
    files = set()
    for output in group["outputs"]:
        path = root / _relative(output)
        if path.is_symlink() or not path.exists():
            raise FileNotFoundError(f"Missing fixture output: {path}")
        if path.is_file():
            files.add(path.relative_to(root))
        elif path.is_dir():
            members = list(path.rglob("*"))
            if not members:
                raise FileNotFoundError(f"Empty fixture output: {path}")
            for member in members:
                if member.is_symlink() or not (member.is_file() or member.is_dir()):
                    raise RuntimeError(f"Unexpected fixture output: {member}")
                if member.is_file():
                    files.add(member.relative_to(root))
        else:
            raise RuntimeError(f"Unexpected fixture output: {path}")
    if not files:
        raise FileNotFoundError("Fixture outputs contain no files")
    return {str(path): _digest(root / path) for path in sorted(files)}


def _write_stamp(path, stamp):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(stamp, indent=2, sort_keys=True) + "\n")
    temporary.replace(path)


def prepare(root, selection, run, toolchain):
    """Prepare selected JUnit fixtures; return {mode, rebuilt, reused}.

    ``run(name, argv, stdout=None)`` runs a command from the repository root.
    ``stdout`` is an optional repository-relative output file. Its parent is
    created here before invoking the command.
    """
    root = Path(root)
    manifest, owners = _manifest(root)
    classes = selection["junit"]["classes"]
    if not isinstance(classes, list) or not classes or not all(isinstance(name, str) for name in classes):
        raise ValueError("Invalid selected JUnit classes")
    if selection.get("mode") == "full" or any(name not in owners for name in classes):
        run("fixtures-full", ["scripts/prepare-tests.sh"])
        return {"mode": "full", "rebuilt": ["full"], "reused": []}
    if selection.get("mode") != "narrow":
        raise ValueError("Invalid selected test mode")

    groups = sorted({owners[name] for name in classes if owners[name] is not None})
    def classify():
        state = []
        for group_id in groups:
            group = manifest["groups"][group_id]
            key = cache_key(root, group_id, group, toolchain)
            stamp_path = root / STAMP_DIR / (group_id + ".json")
            try:
                stamp = json.loads(stamp_path.read_text())
                reusable = (isinstance(stamp, dict) and stamp.get("schema") == 1 and
                            stamp.get("key") == key and stamp.get("outputs") == _output_hashes(root, group))
            except (FileNotFoundError, ValueError, OSError):
                reusable = False
            state.append((group_id, group, key, stamp_path, reusable))
        return state

    state = classify()
    if any(not reusable for _, _, _, _, reusable in state):
        run("fixture-scalar-signatures", ["python3", "scripts/generate-scalar-signatures.py"])
        run("fixture-compiler", ["compiler/build.sh"])
        # A preparatory command may have updated a declared source. Never skip
        # a previously reusable group on an identity calculated before it ran.
        state = classify()
    rebuilt, reused = [], []
    for group_id, group, key, stamp_path, reusable in state:
        if reusable:
            reused.append(group_id)
            continue
        stamp_path.unlink(missing_ok=True)
        for index, command in enumerate(group["commands"]):
            stdout = command.get("stdout")
            if stdout is not None:
                (root / _relative(stdout)).parent.mkdir(parents=True, exist_ok=True)
            run(f"fixture-{group_id}-{index:02d}", command["argv"], stdout=stdout)
        _write_stamp(stamp_path, {"schema": 1, "key": key,
                                  "outputs": _output_hashes(root, group)})
        rebuilt.append(group_id)
    return {"mode": "selected", "rebuilt": rebuilt, "reused": reused}
