#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -eu
cd "$(dirname "$0")/.."
. compiler/toolchain.sh
root=$(pwd)
out="$root/build/pinned-pointer-cells"
mkdir -p "$out/native"
"$GHC" --make -O2 -dynamic -fforce-recomp -dcore-lint \
  -i./compiler/test-fixtures -odir "$out/native" -hidir "$out/native" \
  compiler/test-fixtures/PinnedPointerCellsNative.hs -o "$out/native/oracle"
printf '0\n1\n17\n127\n255\n256\n-1\n' | "$out/native/oracle" > "$out/oracle.tsv"
for stage in pre post; do
  mkdir -p "$out/$stage/core" "$out/$stage/ghc"
  if [ "$stage" = post ]; then set -- -fplugin-opt=THC.Plugin:post-tidy; else set --; fi
  THC_CORE_OUT="$out/$stage/core" THC_GHC_OUT="$out/$stage/ghc" \
    compiler/export.sh "$@" -fplugin-opt=THC.Plugin:closure=pointerRoundtrip \
    compiler/test-fixtures/PinnedPointerCellsAudit.hs
  python3 scripts/audit-core.py --entry pointerRoundtrip --output "$out/$stage/audit.json" \
    "$out/$stage/core/PinnedPointerCellsAudit.json" "$out/$stage/core/THC.InterfaceClosure.json"
done
python3 - "$root" "$out" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

root, out = map(Path, sys.argv[1:])
inputs = [
    "compiler/test-fixtures/PinnedPointerCellsAudit.hs",
    "compiler/test-fixtures/PinnedPointerCellsNative.hs",
    "scripts/prepare-pinned-pointer-cells.sh", "scripts/audit-core.py",
    "scripts/core-capabilities.json", "compiler/export.sh", "compiler/build.sh",
    "compiler/toolchain.sh", "compiler/plugin.py", "thc.cabal", "cabal.project",
]
inputs += [str(path.relative_to(root)) for path in sorted((root / "compiler/THC").glob("*.hs"))]
inputs += [str(path.relative_to(root)) for path in sorted((root / "scripts").glob("core_*.py"))]
outputs = ["oracle.tsv"] + [f"{stage}/{name}" for stage in ("pre", "post") for name in (
    "audit.json", "core/PinnedPointerCellsAudit.json", "core/THC.InterfaceClosure.json")]
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = {
    "schema": 1,
    "ghc": subprocess.check_output([os.environ["GHC"], "--numeric-version"], text=True).strip(),
    "inputHashes": {name: digest(root / name) for name in inputs},
    "artifactHashes": {str((out / name).relative_to(root)): digest(out / name) for name in outputs},
}
(out / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
PY
