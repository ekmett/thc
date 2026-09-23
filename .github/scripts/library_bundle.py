#!/usr/bin/env python3
"""Move fingerprinted library inputs between read-only jobs of one Build attempt.

Original Core, cases, native provenance and oracle bytes are never rewritten.
Consumers need the same workspace path and platform, but neither GHC nor an
executable native oracle. Only declared regular build/vendor files are restored.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import subprocess
import tarfile

CASES = "build/libraries/cases.json"
LIB = "build/install/thc/lib"
RECEIPT = "build/library-transfer-verified.json"
HEX = re.compile(r"[0-9a-f]{64}\Z")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest_stream(stream):
    result = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        result.update(block)
    return result.hexdigest()


def digest(path):
    require(path.is_file(), "Expected regular file: " + str(path))
    with path.open("rb") as stream:
        return digest_stream(stream)


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def identity(root):
    required = ("GITHUB_REPOSITORY", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT",
                "GITHUB_SHA", "GITHUB_WORKSPACE", "RUNNER_OS", "RUNNER_ARCH", "JAVA_HOME")
    require(all(os.environ.get(key) for key in required), "Missing Build identity environment")
    require(os.environ["GITHUB_RUN_ID"].isdigit() and os.environ["GITHUB_RUN_ATTEMPT"].isdigit(),
            "Invalid Build run/attempt")
    require(os.environ["GITHUB_WORKSPACE"] == str(root), "Workspace path differs from Build checkout")
    sha = git(root, "rev-parse", "HEAD")
    require(sha == os.environ["GITHUB_SHA"], "Checkout does not match Build source SHA")
    require(not git(root, "status", "--porcelain", "--untracked-files=no"), "Build source checkout is dirty")
    host = {"Linux": "Linux", "Darwin": "macOS"}.get(platform.system())
    arch = {"x86_64": "X64", "aarch64": "ARM64", "arm64": "ARM64"}.get(platform.machine())
    require(host == os.environ["RUNNER_OS"] and arch == os.environ["RUNNER_ARCH"],
            "Runner OS/architecture does not match this host")
    release = Path(os.environ["JAVA_HOME"]) / "release"
    require(release.is_file(), "JDK release is not a regular file")
    values = dict(line.split("=", 1) for line in release.read_text().splitlines() if "=" in line)
    require(values.get("GRAALVM_VERSION", "").strip('"') == "25.3.4.1"
            and values.get("JAVA_VERSION", "").strip('"').split(".")[0] == "25",
            "Library jobs require pinned GraalVM 25.3.4.1 / Java 25")
    return {"repository": os.environ["GITHUB_REPOSITORY"], "runId": os.environ["GITHUB_RUN_ID"],
            "runAttempt": os.environ["GITHUB_RUN_ATTEMPT"], "sourceSha": sha,
            "sourceTree": git(root, "rev-parse", "HEAD^{tree}"), "workspace": str(root),
            "runnerOS": host, "runnerArch": arch, "javaReleaseSha256": digest(release)}


def relative(name):
    require(isinstance(name, str), "Bundle path is not a string")
    path = PurePosixPath(name)
    require(name and not path.is_absolute()
            and "\\" not in name and all(part not in ("", ".", "..") for part in name.split("/")),
            "Unsafe bundle path: " + str(name))
    return path.as_posix()


def file_path(root, name):
    path = root / relative(name)
    require(path.resolve() == path, "Bundle path traverses a link: " + name)
    return path


def original_path(root, name):
    path = Path(name)
    require(path.is_absolute(), "Original library path must remain absolute: " + name)
    try:
        result = relative(path.relative_to(root).as_posix())
    except ValueError as error:
        raise RuntimeError("Original library path escapes checkout: " + name) from error
    require(str(root / result) == name, "Noncanonical original library path: " + name)
    return result


def inventory(root, cases, jars, cases_hash):
    require(cases.get("schema") == 1, "Unsupported library manifest schema")
    tracked = set(git(root, "ls-files", "-z").split("\0"))
    inputs, artifacts = cases["inputHashes"], cases["artifactHashes"]
    require(inputs and artifacts, "Library fingerprints must be nonempty")
    hashes = {}
    for section in (inputs, artifacts):
        for filename, expected in section.items():
            name = original_path(root, filename)
            require(HEX.fullmatch(expected), "Malformed library fingerprint: " + name)
            require(name not in hashes or hashes[name] == expected, "Conflicting library fingerprint: " + name)
            hashes[name] = expected
    # Every path consumed by LibraryCheck must be one of the original hashed files.
    consumed = [str(root / "build/libraries/oracle.tsv"),
                str(root / "build/libraries/oracle-validation.json"),
                str(root / "build/libraries/native/library-oracle")]
    for group in cases["groups"]:
        require(re.fullmatch(r"[a-z0-9-]+", group["id"]), "Invalid library group")
        consumed.extend([*group["modules"], group["audit"],
                         str(root / "build/libraries" / group["id"] / "provenance.json")])
        consumed.extend(entry["audit"] for entry in group["entries"] if "audit" in entry)
    require(all(name in artifacts for name in consumed), "Unfingerprinted library input/artifact reference")
    require(jars and any(Path(name).name.startswith("thc-") for name in jars), "Installed runtime JARs missing")
    for name, expected in jars.items():
        require(str(PurePosixPath(relative(name)).parent) == LIB and name.endswith(".jar")
                and HEX.fullmatch(expected), "Invalid runtime JAR record")
        require(name not in hashes, "Runtime JAR collides with library input")
    hashes.update(jars)
    require(CASES not in hashes and HEX.fullmatch(cases_hash), "Invalid original cases fingerprint")
    hashes[CASES] = cases_hash
    require(RECEIPT not in hashes, "Library payload collides with verification receipt")
    sources = {name: value for name, value in hashes.items() if name in tracked}
    payload = {name: value for name, value in hashes.items() if name not in tracked}
    require(all(PurePosixPath(name).parts[0] in ("build", "vendor") for name in payload),
            "Bundle would restore a file outside build/vendor")
    require(CASES in payload and all(name in payload for name in jars), "Build output overlaps tracked sources")
    return sources, payload


def check_files(root, hashes):
    for name, expected in hashes.items():
        path = file_path(root, name)
        require(path.is_file() and digest(path) == expected, "Stale bundle input: " + name)


def pack(root, output):
    build_identity = identity(root)
    cases_path = file_path(root, CASES)
    require(cases_path.is_file(), "Original cases manifest is not a regular file")
    raw_cases = cases_path.read_bytes()
    cases = json.loads(raw_cases)
    jars = {}
    for path in sorted((root / LIB).iterdir()):
        name = path.relative_to(root).as_posix()
        require(path.suffix == ".jar", "Unexpected installed runtime file: " + name)
        jars[name] = digest(file_path(root, name))
    sources, payload = inventory(root, cases, jars, hashlib.sha256(raw_cases).hexdigest())
    check_files(root, {**sources, **payload})
    require(json.loads((root / "build/libraries/oracle-validation.json").read_text())["compiler"] == "9.14.1",
            "Native library provenance must record GHC 9.14.1")
    manifest = {"schema": 1, "identity": build_identity, "sourceFiles": sources,
                "payloadFiles": payload, "runtimeJars": jars}
    output.parent.mkdir(parents=True, exist_ok=True)
    require(not output.exists(), "Bundle output already exists; use a fresh Build attempt")
    with tarfile.open(output, "w:gz", compresslevel=6) as archive:
        raw = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
        info = tarfile.TarInfo("bundle.json")
        info.size = len(raw)
        archive.addfile(info, io.BytesIO(raw))
        for name in sorted(payload):
            archive.add(file_path(root, name), arcname="files/" + name, recursive=False)
    print(f"Packed {len(payload)} files for {build_identity['sourceSha']} / {build_identity['runnerOS']}")
    return manifest


def restore(root, source):
    current = identity(root)
    receipt_path = file_path(root, RECEIPT)
    require(RECEIPT not in git(root, "ls-files", "-z").split("\0"),
            "Verification receipt would overwrite a tracked source")
    require(not receipt_path.exists(), "Verification receipt already exists; use a fresh consumer checkout")
    with tarfile.open(source, "r:gz") as archive:
        members = archive.getmembers()
        names = [member.name for member in members]
        require(len(names) == len(set(names)), "Duplicate bundle member")
        require(all(member.isfile() for member in members), "Bundle contains a link or nonregular file")
        require("bundle.json" in names, "Bundle identity manifest missing")
        manifest = json.load(archive.extractfile("bundle.json"))
        require(manifest.get("schema") == 1, "Unsupported bundle schema")
        require(manifest["identity"] == current,
                "Bundle source/run/attempt/workspace/platform/JDK mismatch; rerun all Build jobs")
        payload = manifest["payloadFiles"]
        for name in payload:
            relative(name)
        require(set(names) == {"bundle.json", *("files/" + name for name in payload)},
                "Bundle members differ from declared regular files")
        raw_cases = archive.extractfile("files/" + CASES).read()
        sources, expected_payload = inventory(root, json.loads(raw_cases), manifest["runtimeJars"],
                                              hashlib.sha256(raw_cases).hexdigest())
        require(sources == manifest["sourceFiles"] and expected_payload == payload,
                "Bundle inventory does not match the original library manifest")
        check_files(root, sources)
        existing_jars = set(path.relative_to(root).as_posix() for path in (root / LIB).glob("*"))
        require(existing_jars <= set(manifest["runtimeJars"]), "Unexpected installed runtime JAR")
        # Validate the entire archive and destinations before writing any payload.
        for name, expected in payload.items():
            path = file_path(root, name)
            if path.exists():
                require(path.is_file() and digest(path) == expected, "Conflicting existing bundle file: " + name)
            with archive.extractfile("files/" + name) as stream:
                require(digest_stream(stream) == expected, "Bundle payload hash mismatch: " + name)
        for name in payload:
            path = file_path(root, name)
            path.parent.mkdir(parents=True, exist_ok=True)
            if not path.exists():
                with archive.extractfile("files/" + name) as src, path.open("xb") as dst:
                    for block in iter(lambda: src.read(1024 * 1024), b""):
                        dst.write(block)
        check_files(root, payload)
    receipt = {"schema": 1, "identity": current, "bundleSha256": digest(source),
               "casesSha256": payload[CASES], "sourceFilesVerified": len(sources),
               "payloadFilesVerified": len(payload), "runtimeJars": manifest["runtimeJars"]}
    with receipt_path.open("x") as stream:
        stream.write(json.dumps(receipt, indent=2) + "\n")
    print(f"Verified original library inputs and runtime for {current['sourceSha']} / {current['runnerOS']}")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("pack").add_argument("--output", type=Path, required=True)
    commands.add_parser("restore").add_argument("--archive", type=Path, required=True)
    args = parser.parse_args()
    root = Path.cwd().resolve()
    if args.command == "pack":
        pack(root, args.output.resolve())
    else:
        restore(root, args.archive.resolve())


if __name__ == "__main__":
    main()
