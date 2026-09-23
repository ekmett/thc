#!/usr/bin/env python3
"""Verify this exact checkpoint without rebuilding or measuring it."""
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import xml.etree.ElementTree as ET

checkpoint = Path(__file__).resolve().parent
repo = checkpoint.parents[1]
evidence = checkpoint / "evidence"
index = json.loads((checkpoint / "evidence-manifest.json").read_text())["files"]
actual = {p.relative_to(evidence).as_posix() for p in evidence.rglob("*") if p.is_file()}
assert actual == set(index), "Evidence inventory differs"
for name, entry in index.items():
    data = (evidence / name).read_bytes()
    assert len(data) == entry["bytes"], name
    assert hashlib.sha256(data).hexdigest() == entry["sha256"], name
print(f"PASS: {len(index)} original evidence files match their archived hashes.")

for version in ("v2", "v3"):
    report = evidence / f"noninline-call-abi-review-{version}"
    manifest = json.loads((report / "complete-overlay-manifest.json").read_text())
    with tarfile.open(report / "complete-source-overlay.tar.gz") as archive:
        members = [member for member in archive.getmembers() if member.isfile()]
        assert {member.name for member in members} == set(manifest["files"]), version
        for member in members:
            data = archive.extractfile(member).read()
            assert hashlib.sha256(data).hexdigest() == manifest["files"][member.name], member.name
    print(f"PASS: {version} complete source archive matches its manifest.")

report = evidence / "noninline-call-abi-review-v3"
manifest = json.loads((report / "complete-overlay-manifest.json").read_text())
for name, expected in manifest["files"].items():
    assert hashlib.sha256((repo / name).read_bytes()).hexdigest() == expected, name
tracked = subprocess.check_output(
    ["git", "ls-files", "-z", "--", *manifest["replaceDirectories"]], cwd=repo
).decode().rstrip("\0").split("\0")
assert set(tracked) == set(manifest["files"]), "Tracked source inventory differs"
print(f"PASS: all {len(tracked)} tracked source files match the tested v3 overlay.")

totals = defaultdict(Counter)
with tarfile.open(report / "test-results.tar.gz") as archive:
    for member in archive.getmembers():
        if member.isfile() and member.name.endswith(".xml"):
            root = ET.parse(archive.extractfile(member)).getroot()
            totals[Path(member.name).parts[0]].update({
                key: int(root.attrib.get(key, 0))
                for key in ("tests", "failures", "errors", "skipped")
            })
summary = json.loads((report / "validation-summary.json").read_text())
assert set(totals) == {"reference-v3", "reference-v3-default"}
for group, mode in (("reference-v3", "enabled"), ("reference-v3-default", "default")):
    assert dict(totals[group]) == summary[mode]
    assert dict(totals[group]) == {"tests": 186, "failures": 0, "errors": 0, "skipped": 0}
assert summary["mainIntegration"] is False
print("PASS: archived test XML records 186/186 enabled and 186/186 default; no main integration claim.")

smoke = json.loads((report / "reference-smoke.json").read_text())
assert smoke and all(row["smoke"] and row["validSteadyState"] is False
                     and row["validationStatus"] == "smoke-only" for row in smoke)
print("PASS: reference measurements retain their smoke-only status.")
