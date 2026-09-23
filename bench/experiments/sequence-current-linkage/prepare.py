#!/usr/bin/env python3
"""Prepare the bounded build-only lifetime control; do not modify runtime sources."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prefix_cases", type=Path)
    parser.add_argument("sequence_root", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    repo = here.parents[2]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    prefix = args.prefix_cases.resolve()
    prefix_repo = prefix.parents[2]
    sequence = args.sequence_root.resolve()
    cases = json.loads(prefix.read_text())
    original_path = sequence / "build/libraries/cases.json"
    assert digest(original_path) == "b9a7e8cda32b448cb9d5e54af08bec112eab4c112b1a8a63a23b32b6dad4179a"
    original = json.loads(original_path.read_text())
    assert [g["id"] for g in cases["groups"]] == [
        "set", "intmap", "intmap-primops", "intset", "intset-primops"]
    recovered = []
    for kind in ("inputHashes", "artifactHashes"):
        for name, expected in list(cases[kind].items()):
            path = Path(name)
            if path.is_file() and digest(path) == expected:
                continue
            # Preserve provenance from the preparation, even when its checkout moved.
            # Only these two known source files can be recovered; artifact drift fails.
            relative = str(path.relative_to(prefix_repo))
            assert kind == "inputHashes" and relative in (
                "scripts/audit-core.py", "scripts/core-capabilities.json"), name
            revisions = subprocess.check_output([
                "git", "-C", str(prefix_repo), "log", "--all", "--format=%H", "--", relative
            ], text=True).splitlines()
            for revision in revisions:
                payload = subprocess.check_output([
                    "git", "-C", str(prefix_repo), "show", f"{revision}:{relative}"])
                if hashlib.sha256(payload).hexdigest() == expected:
                    target = output / "frozen-prefix-inputs" / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(payload)
                    del cases[kind][name]
                    cases[kind][str(target)] = expected
                    recovered.append(dict(original=name, snapshot=str(target),
                                          gitRevision=revision, sha256=expected))
                    break
            else:
                raise ValueError(f"Cannot recover exact prepared source: {name}")
    prefix_oracle = prefix.with_name("oracle.tsv").read_text()
    assert digest(sequence / "build/libraries/oracle.tsv") == "01fabd9fdc09bcf68a7fac03ecf0c93504ac3ef458a8c0ca521a460348ac2cd7"
    assert (sequence / "build/libraries/oracle.tsv").read_text().startswith(prefix_oracle)
    group = original["groups"][-1]
    assert group["id"] == "sequence"
    group["entries"] = group["entries"][:8]
    assert [e["name"] for e in group["entries"]] == [
        "sequenceBuild", "sequenceEnds", "sequenceAppend", "sequenceSplit",
        "sequenceIndexUpdate", "sequenceAggregate", "sequenceLazyPayloads", "sequenceBuildViews"]
    # Rebase the preserved original module paths without changing their bytes.
    modules = []
    for name in group["modules"]:
        path = sequence / "build/libraries" / name.split("/build/libraries/", 1)[1]
        assert digest(path) == original["artifactHashes"][name], name
        modules.append(str(path))
    group["modules"] = modules
    group["execution"] = "supported"  # checker.patch handles the seven load-only positions.
    cases["groups"].append(group)
    for path in group["modules"]:
        cases["artifactHashes"][path] = digest(Path(path))
    rows = "".join(f'{entry["name"]}\t{x}\t{y}\n'
                   for g in cases["groups"] for entry in g["entries"]
                   for phase in ("warm", "cold") for x, y in entry[phase])
    oracle = output / "oracle.tsv"
    oracle.write_text(rows)
    cases["artifactHashes"][str(oracle)] = digest(oracle)
    (output / "lifetime-cases.json").write_text(json.dumps(cases, indent=2) + "\n")
    source = repo / "src/main/kotlin/thc/LibraryCheck.kt"
    assert digest(source) == "6972150be4500d6f439d127848f9da0a4cdebdbcefd5c372f94e30d5348e16f5"
    src = output / "lifetime-src"
    src.mkdir(exist_ok=True)
    (src / "LibraryCheck.kt").write_bytes(source.read_bytes())
    subprocess.run(["patch", "-p0", "-i", str(here / "checker.patch")], cwd=src, check=True)
    record = dict(prefixCases=str(prefix), prefixCasesSha256=digest(prefix),
                  originalCases=str(original_path), originalCasesSha256=digest(original_path),
                  prefixNativeRowsEqual=True, recoveredSources=recovered,
                  checkerSha256=digest(src / "LibraryCheck.kt"))
    (output / "preparation.json").write_text(json.dumps(record, indent=2) + "\n")
    print(output / "lifetime-cases.json")


if __name__ == "__main__":
    main()
