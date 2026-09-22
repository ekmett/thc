# Reachable Core audit

Run the capability/link audit against the exporter's exact module manifest:

```sh
python3 scripts/audit-core.py --entry mapAggregate \
  --module-list build/map/modules.txt --output build/map/audit.json
python3 scripts/test-audit-core.py
```

The command exits zero only when every syntactically reachable dependency is supplied and every observed feature fits `scripts/core-capabilities.json`. A nonzero audit still writes the full report. Entries may be exact global IDs or unambiguous occurrence names; repeat `--entry` to audit several roots. Individual JSON files and directories are also accepted, but the manifest avoids accidentally including stale exports. Relative paths inside a manifest are relative to that manifest.

The report includes every missing global with its reference sites and an entry-to-caller chain, all dependency edges, primitive arities, literal forms, and constructor representation/strictness metadata. Lambda parameters, recursive and nonrecursive local bindings, and case/alternative binders have their actual lexical scopes. All alternatives and local right-hand sides are inspected, including lazy exception paths. Dead top-level definitions do not add requirements. No external ID alias, primitive operation, representation, or source definition is inferred.

`core-capabilities.json` is an explicit contract reviewed against `Program.kt` and `DataValues.kt`. Update it only when corresponding runtime support is implemented and verified. `--capabilities PATH` can check a different declared profile. Runtime-provided external bindings are listed separately from exported Core definitions. Passing the audit checks linking and declared feature coverage; it does not prove termination, branch feasibility, or semantic correctness. The native differential harness and compiled guest tests supply that separate evidence.
