#!/usr/bin/env python3
"""Validate the checked-out candidate and unchanged portable evidence."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

checkpoint = Path(__file__).resolve().parent
repo = checkpoint.parents[1]
evidence = checkpoint / "evidence"
manifest = json.loads((evidence / "agnostic/provenance/frozen-manifest.json").read_text())
files = [entry for entry in manifest["files"] if entry["path"].startswith("src/")]
for entry in files:
    path = repo / entry["path"]
    assert hashlib.sha256(path.read_bytes()).hexdigest() == entry["sha256"], entry["path"]
print(f"PASS: {len(files)} candidate source hashes match the frozen manifest.", flush=True)
subprocess.run([sys.executable, str(evidence / "tools/verify.py")],
               env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"}, check=True)
