#!/usr/bin/env python3
"""Run the pinned GHC API capture proof. This does not execute Core in THC."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def require(ok, message):
    if not ok:
        raise SystemExit(message)


def walk(value):
    yield value
    if isinstance(value, list):
        for child in value:
            yield from walk(child)
    elif isinstance(value, dict):
        for child in value.values():
            yield from walk(child)


def refs(document):
    return {v[1] for v in walk(document) if isinstance(v, list) and v and v[0] == "var"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ghc", default=os.environ.get("GHC", "ghc"))
    parser.add_argument("--output", type=Path, default=Path("build/interactive-proof"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = args.output.resolve()
    require(not out.exists(), f"Output already exists; choose a new directory: {out}")
    out.mkdir(parents=True)
    version = subprocess.check_output([args.ghc, "--numeric-version"], text=True).strip()
    require(version == "9.14.1", f"Expected GHC 9.14.1, got {version}")
    libdir = subprocess.check_output([args.ghc, "--print-libdir"], text=True).strip()
    executable = out / "capture-proof"
    commands = [
        [args.ghc, "--make", "-dynamic", "-package", "ghc", "-package", "exceptions",
         "-icompiler", "-odir", str(out / "objects"), "-hidir", str(out / "objects"),
         "compiler/interactive/CaptureProof.hs", "-o", str(executable)],
        [str(executable), libdir, str(out / "captures")],
    ]
    for name, command in zip(["compile", "capture"], commands):
        with (out / f"{name}.log").open("w") as log:
            subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
    captures = out / "captures"
    docs = {p.stem: json.loads(p.read_text()) for p in captures.glob("*.json")}
    statements = ["x-original", "f-retains-x", "x-shadow", "expression-print", "pattern-bindings",
                  "lazy-bottom", "io-action", "fixity-use", "instance-use", "loaded-expression"]
    require(set(docs) == set(statements + [s + "-desugared" for s in statements] +
                             ["declarations", "module-first", "module-reloaded"]), "Unexpected capture inventory")
    roots = []
    for name in statements:
        opt, raw = docs[name], docs[name + "-desugared"]
        require(opt["boundary"] == "optimized-Core-after-Tidy-before-CorePrep", name)
        require(raw["boundary"] == "desugared-interactive-Core", name)
        require(opt["definitionScope"] == raw["definitionScope"] == "interactive-statement", name)
        require(opt["interactiveBindings"] == raw["interactiveBindings"], f"Binding order changed: {name}")
        require(len(opt["bindings"]) == len(raw["bindings"]) == 1, name)
        roots.append(opt["bindings"][0]["id"])
    require(len(set(roots)) == len(roots), "Executable roots collide within a name epoch")
    old_x = docs["x-original"]["interactiveBindings"][0]["id"]
    new_x = docs["x-shadow"]["interactiveBindings"][0]["id"]
    require(old_x != new_x and old_x in refs(docs["f-retains-x"]), "Old closure identity lost")
    require(new_x in refs(docs["expression-print"]) and old_x not in refs(docs["expression-print"]),
            "Shadowed reference identity lost")
    require(len(docs["pattern-bindings"]["interactiveBindings"]) == 2, "Pattern results lost")
    for name in ["declarations", "module-first", "module-reloaded"]:
        d = docs[name]
        require(d["boundary"] == "optimized-Core-after-Tidy-before-CorePrep", name)
        require(d["interactiveBindings"] == [], name)
    require(docs["declarations"]["definitionScope"] == "interactive-declarations", "Declaration scope")
    require(any(c["name"] == ":*:" for c in docs["declarations"]["constructors"]), "Constructor lost")
    for name, value in [("module-first", "7"), ("module-reloaded", "8")]:
        d = docs[name]
        require(d["definitionScope"] == "complete-source-module", "Module scope")
        bindings = {b["name"]: b for b in d["bindings"]}
        require({"value", "dormant"} <= bindings.keys(), "Module body was pruned")
        require(any(isinstance(v, list) and v[:3] == ["lit", "int", value]
                    for v in walk(bindings["value"])), "Loaded value/reload mismatch")
        require(any("error" in ref for ref in refs(bindings["dormant"])), "Bottom body lost")
    require(not (captures / "USER-CODE-RAN").exists(), "User code ran")
    sources = sorted((root / "compiler/THC").glob("*.hs")) + [
        root / "compiler/interactive/CaptureProof.hs", Path(__file__).resolve()]
    summary = {"ghc": version, "commands": commands, "captureOnly": True,
               "captures": len(docs), "statementPairs": len(statements),
               "sourceHashes": {str(p.relative_to(root)): digest(p) for p in sources},
               "outputHashes": {str(p.relative_to(out)): digest(p) for p in sorted(out.rglob("*.json"))},
               "checks": ["GHC parsing/typechecking", "GHC expression simplification/Tidy", "shadowed Names",
                          "ordered result Ids", "type errors", "stale name epoch", "GHC print/IO wrappers",
                          "lazy bottom", "native execution guard", "declaration fixity/instance",
                          "GHC module load/reload post-Tidy", "complete retained module bodies"]}
    (out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(f"PASS: {len(docs)} genuine GHC captures; no THC evaluation or working REPL claimed")


if __name__ == "__main__":
    main()
