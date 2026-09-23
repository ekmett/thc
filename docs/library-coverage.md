# Real containers coverage beyond Map

The library workload sources use ordinary public containers APIs. They are
compiled from the same unmodified, SHA256-verified containers-0.8 source archive
as the Map example. No library operation is a JVM builtin.

## Data.Set Int: explicit unsupported frontier

`THC.SetWorkload.setAggregate` builds mixed-sign sets with duplicate insertions,
deletes present and absent keys, combines sets with union/intersection/difference,
queries membership, and folds in ascending order with an order-sensitive checksum.
Empty and negative workloads are defined. Native GHC 9.14.1 agreed with an
independent Python set model for all 1,041 inputs from -16 through 1024.

This is **not currently an executable THC coverage claim**. Its strict reachable
audit exposes these gaps:

- `reallyUnsafePtrEquality#` is used by insertion, deletion, union and intersection.
- Deletion reaches unboxed pairs through `glue` and min/max extraction workers.
  Union/difference use pairs in split workers; intersection uses triples in
  `splitMember`. These must retain their unboxed representation when supported.
- Difference references `main:Data.Set.Internal.merge_$smerge1`, while the source
  export contains private specialized merge bindings with other identities.
  No alias is inferred from similar names.
- Existing cold exception paths additionally reach `readMutVar#` and missing
  exception-construction, backtrace and call-stack bindings.

The initial pre-Tidy source export has 71 reachable bindings, four missing global
definitions and 109 capability issues. Counts can change as the exporter learns
more precise diagnostics; rejection does not count as execution support.

The full source and its strict diagnostic are retained so aggregate lowering,
pointer identity and source/interface identity work can be tested against an
ordinary library program. No synthetic boxed tuples or ad hoc name substitutions
are used to make this frontier appear supported.
