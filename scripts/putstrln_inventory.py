#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
"""Inventory a successful source-export snapshot without promoting capabilities."""

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path

from putstrln_export import ROOT, digest, read, recipe_hashes, require, verify_hashes, verify_plugin, write


def inventory(core, audit, metadata_available):
    reachable = {b["id"]: b["reachableVia"] for b in audit["reachableBindings"]}
    declarations, literals = {}, defaultdict(set)

    def walk(value, owner):
        if isinstance(value, list):
            if len(value) >= 3 and value[0] == "lit" and value[1] in ("null-addr", "unsupported"):
                literals[(value[1], str(value[2]))].add(owner)
            for child in value:
                walk(child, owner)
        elif isinstance(value, dict):
            call = value.get("foreignCall")
            if isinstance(call, dict):
                key = json.dumps(call, sort_keys=True)
                record = declarations.setdefault(key, {"declaration": call, "owners": set()})
                record["owners"].add(owner)
            for key, child in value.items():
                if key != "foreignCall":
                    walk(child, owner)

    for binding in core["bindings"]:
        if binding["id"] in reachable:
            walk(binding["expr"], binding["id"])
    foreign = []
    for _, record in sorted(declarations.items()):
        record["owners"] = sorted(record["owners"])
        record["exampleChain"] = reachable[record["owners"][0]]
        foreign.append(record)
    return {"summary": audit["summary"],
            "issueCounts": dict(Counter(i["code"] for i in audit["issues"])),
            "unsupportedPrimitives": sorted({i["detail"] for i in audit["issues"] if i["code"] == "unsupported-primitive"}),
            "foreignMetadataStatus": "available-not-an-ABI-proof" if metadata_available else "unavailable-exporter-prerequisite",
            "foreignDeclarations": foreign if metadata_available else None,
            "missingGlobals": audit["missingGlobals"],
            "addressAndUnsupportedLiterals": [{"kind": k, "value": v, "owners": sorted(owners)}
                                             for (k, v), owners in sorted(literals.items())],
            "limits": ["Conservative syntactic reachability includes cold/error/dictionary/finalization paths.",
                       "An export is not runnable THC IO support; strict audit failures remain failures.",
                       "Source-only optimization is not identical installed optimized Core.",
                       "No FFI signature is reconstructed from pretty-printed Core or generated names."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    state = read(args.directory / "export-state.json")
    require("recipeInputHashes" in state and "pluginBuild" in state, "Old export lacks bound plugin provenance; create a fresh export")
    verify_hashes(state["recipeInputHashes"])
    require(recipe_hashes(ROOT) == state["recipeInputHashes"], "Inventory must use the recorded recipe/auditor checkout and inputs")
    verify_plugin(state["pluginBuild"])
    require(state["foreignMetadataExporterAvailable"] == state["pluginBuild"]["foreignMetadataExporterAvailable"],
            "Inventory capability differs from the built plugin")
    if "generatedManifest" in state:
        manifest = state["generatedManifest"]
        require(digest(manifest["path"]) == manifest["sha256"], "Changed hsc2hs provenance manifest")
    require(sorted(state["compiled"]) == state["auditedModules"], "Export stopped after compilation but before a matching audit")
    require(digest(args.directory / "merged-core.json") == state["mergedSha256"] and
            digest(args.directory / "latest-audit.json") == state["auditSha256"], "Changed merged/audited snapshot")
    for module, record in state["compiled"].items():
        require(record["exit"] == 0 and digest(record["source"]) == record["sha256"], "Changed source: " + module)
        for path, expected in record["exportHashes"].items():
            require(digest(path) == expected, "Changed admitted export: " + path)
        if "configurationSource" in record:
            require(digest(record["configurationSource"]) == record["configurationSha256"], "Changed configured source")
        if "hsc2hsProvenance" in record:
            generated = record["hsc2hsProvenance"]
            require(digest(generated["source"]) == generated["sourceSha256"] and
                    digest(generated["output"]) == generated["outputSha256"], "Changed generated source")
        if module != "Main":
            relative = Path(*module.split("."))
            interface = (args.directory / "overlay" / relative).with_suffix(".hi")
            original = (Path(state["installedInterfaces"]) / relative).with_suffix(".dyn_hi")
            require(interface.is_symlink() and original.is_file() and interface.resolve() == original.resolve(),
                    "Installed dependency interface was not restored: " + module)
    core, audit = (read(args.directory / name) for name in ("merged-core.json", "latest-audit.json"))
    result = inventory(core, audit, state["foreignMetadataExporterAvailable"])
    result.update(thcRevision=state["thcRevision"], ghcSourceRevision=state["sourceCommit"],
                  pluginBuild=state["pluginBuild"], recipeInputHashes=state["recipeInputHashes"],
                  successfulSourceModules=sorted(state["compiled"]), failedSourceModules=state["failed"],
                  requestedNextModules=state["requestedNextModules"], recipe=state["recipe"])
    write(args.directory / "frontier-summary.json", result)
    print(json.dumps({key: result[key] for key in ("summary", "foreignMetadataStatus", "issueCounts")}, indent=2))
    return int(bool(state["failed"]))


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError) as error:
        raise SystemExit(str(error))
