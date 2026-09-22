#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
. "$root/compiler/toolchain.sh"
version=$("$GHC_PKG" field containers version --simple-output)
[ "$version" = 0.8 ] || { echo "Map source comparison requires installed containers 0.8, found $version" >&2; exit 1; }
archive=vendor/archives/containers-0.8.tar.gz
mkdir -p vendor/archives build/map
if [ ! -f "$archive" ]; then
  curl --fail --location --retry 2 https://hackage.haskell.org/package/containers-0.8/containers-0.8.tar.gz -o "$archive"
fi
python3 - <<'CHECK_SOURCE'
import hashlib, pathlib, tarfile
archive = pathlib.Path("vendor/archives/containers-0.8.tar.gz")
expected = "b1c1127ff57b6f844d0b30cea54a62c01ca146a49ed4953485be1af389a94bd8"
if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
    raise SystemExit("Pinned containers archive SHA256 mismatch")
with tarfile.open(archive) as source:
    if not pathlib.Path("vendor/containers-0.8").exists():
        source.extractall("vendor", filter="data")
    for member in source.getmembers():
        if member.isfile():
            path = pathlib.Path("vendor") / member.name
            if path.read_bytes() != source.extractfile(member).read():
                raise SystemExit("Vendored source differs from pinned archive: " + str(path))
CHECK_SOURCE
compiler/build.sh
THC_CORE_OUT="$root/build/map/core" THC_GHC_OUT="$root/build/map/ghc" \
  compiler/export.sh -i"$root/vendor/containers-0.8/src" -I"$root/vendor/containers-0.8/include" \
  -fplugin-opt=Thc.Plugin:closure=mapAggregate examples/THC/MapWorkload.hs
python3 compiler/export-boot.py
python3 - <<'MANIFEST'
import hashlib, json, os, pathlib, subprocess
root = pathlib.Path.cwd()
core = root / "build/map/core"
closure = json.loads((core / "THC.InterfaceClosure.json").read_text())
modules = [core / (name.split(":", 1)[1] + ".json") for name in closure["sourceModules"]]
modules.append(core / "THC.InterfaceClosure.json")
modules.extend(core / (name + ".json") for name in ["GHC.Internal.CString", "GHC.Internal.Err", "GHC.InterfaceClosure"])
(root / "build/map/modules.txt").write_text("".join(str(path) + "\n" for path in modules))
packages = {name: subprocess.check_output([os.environ["GHC_PKG"], "describe", name], text=True)
            for name in ["containers", "base", "ghc-internal", "ghc-prim"]}
provenance = {
    "source": "https://hackage.haskell.org/package/containers-0.8/containers-0.8.tar.gz",
    "sha256": "b1c1127ff57b6f844d0b30cea54a62c01ca146a49ed4953485be1af389a94bd8",
    "sourcePatches": [],
    "containersUnitPolicy": "Unmodified sources compiled with workload in the same main home unit",
    "compiler": subprocess.check_output([os.environ["GHC"], "--numeric-version"], text=True).strip(),
    "packages": packages,
    "interfacePolicy": "Only actual Core/DFun unfoldings; missing definitions require source export, never synthesized bodies",
    "modules": [{"path": str(path.relative_to(root)), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in modules],
    "workloadSha256": hashlib.sha256((root / "examples/THC/MapWorkload.hs").read_bytes()).hexdigest(),
    "initialMissingDefinitions": closure["missingDefinitions"],
    "bootExports": json.loads((root / "build/map/boot-provenance.json").read_text()),
    "status": "Bounded dependency frontier; run the strict reachable audit before claiming complete linkage",
}
(root / "build/map/provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
print("Map bundle:", root / "build/map/modules.txt")
print("Compatibility status: bounded frontier; inspect reachable audit for remaining gaps")
MANIFEST

python3 compiler/reproduction-provenance.py --fresh-export
