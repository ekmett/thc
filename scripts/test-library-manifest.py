#!/usr/bin/env python3
"""Exercise installed LibraryCheck validation guards without loading guest Core."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory


ROOT = Path(__file__).resolve().parent.parent
ENTRIES = {
    "set": ["setAggregate"],
    "intmap": ["intMapAggregate"],
    "intmap-primops": [
        "countLeadingZeros", "unsignedLessThanZero", "unsignedLessThanMaxSigned",
        "unsignedLessThanSignBit", "unsignedLessThanAllOnes",
    ],
    "intset": ["intSetAggregate"],
    "intset-primops": [
        "populationCount", "countTrailingZeros", "unsignedLessEqualZero",
        "unsignedLessEqualMaxSigned", "unsignedLessEqualSignBit", "unsignedLessEqualAllOnes",
    ],
    "sequence": [
        "sequenceBuild", "sequenceEnds", "sequenceAppend", "sequenceSplit",
        "sequenceIndexUpdate", "sequenceAggregate", "sequenceLazyPayloads",
        "sequenceBuildViews", "sequenceDequeViews", "sequenceAppendViews", "sequenceLazyLength",
    ],
}
EXECUTION_PREFIXES = (
    "VERIFIED_LIBRARY\t", "LIBRARY_UNSUPPORTED\t", "LIBRARY_STRICT_REJECTION\t",
    "LIBRARY_DIAGNOSTICS\t",
)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    java = Path(os.environ["JAVA_HOME"]) / "bin/java"
    classpath = ROOT / "build/install/thc/lib/*"
    assert java.is_file(), java
    assert list(classpath.parent.glob("*.jar")), classpath
    with TemporaryDirectory(prefix="thc-sequence-validation-") as directory:
        root = Path(directory)
        input_file = root / "fixture-input.txt"
        input_file.write_text("Isolated validation-guard fixture; no guest program.\n")
        # A valid report stops at this missing audit, before CoreModules.request
        # or a guest context can be reached. It is also our positive guard control.
        sentinel = root / "must-not-reach-guest-load.audit.json"
        groups = []
        rows = []
        for group_id, names in ENTRIES.items():
            entries = []
            for name in names:
                entry = {"name": name, "warm": [[0, 0]], "cold": [[1, 1]]}
                entries.append(entry)
                rows.extend(f"{name}\t{value}\t{expected}" for value, expected in
                            entry["warm"] + entry["cold"])
            groups.append({"id": group_id, "entries": entries, "execution": "frontier",
                           "modules": [str(root / "never-loaded-core.json")],
                           "audit": str(sentinel)})
        oracle = root / "oracle.tsv"
        oracle.write_text("\n".join(rows) + "\n")
        validation_file = root / "oracle-validation.json"
        cases_file = root / "cases.json"
        valid = {"compiler": "9.14.1", "nativeRows": len(rows),
                 "allNativeResultsMatchIndependentModels": True, "staticSupportViolations": []}

        def run(report, backend):
            validation_file.write_text(json.dumps(report))
            cases_file.write_text(json.dumps({
                "schema": 1, "groups": groups,
                "inputHashes": {str(input_file): digest(input_file)},
                "artifactHashes": {str(path): digest(path) for path in (oracle, validation_file)},
            }))
            result = subprocess.run([
                str(java), "--enable-native-access=ALL-UNNAMED", "-Xss2m",
                "-XX:+UseCompactObjectHeaders", "-cp", str(classpath),
                "thc.LibraryCheckKt", str(cases_file), backend,
            ], cwd=ROOT, text=True, capture_output=True, timeout=30)
            output = result.stdout + result.stderr
            assert result.returncode != 0, output
            assert not any(line.startswith(EXECUTION_PREFIXES) for line in output.splitlines()), output
            return output

        negatives = [
            ("static-support-violations", {"staticSupportViolations": ["injected"]},
             "Library preparation recorded static support violations"),
            ("wrong-native-row-count", {"nativeRows": len(rows) + 1},
             "java.lang.IllegalArgumentException: Failed requirement."),
            ("independent-model-false", {"allNativeResultsMatchIndependentModels": False},
             "java.lang.IllegalArgumentException: Failed requirement."),
        ]
        for backend in ("ast", "bytecode"):
            control = run(valid, backend)
            assert "java.io.FileNotFoundException" in control and str(sentinel) in control, control
            print(f"PASS {backend} valid-report reaches pre-guest sentinel ({len(rows)} rows)")
            for label, changes, expected in negatives:
                output = run(valid | changes, backend)
                assert expected in output, output
                assert str(sentinel) not in output, output
                print(f"PASS {backend} {label}: rejected before guest loading")


if __name__ == "__main__":
    main()
