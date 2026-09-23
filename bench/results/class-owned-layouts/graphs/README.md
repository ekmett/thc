# Class-owned layout graph review

These are actual latest compiled **bytecode** roots from the same frozen JAR and Core, with compact headers enabled in both captures. Class ownership is the only changed JVM flag; constructor-class identity, boxed caching, and unchecked StaticShape access are disabled. Diagnostic runs are not timings. `audit.json` records SHA-256 hashes for selected raw BGVs, parsed phases, input/configuration records, and the exact frozen runtime sources. `audit.py` reproduces the review from already-parsed graphs without running a JVM.

| Root | Compilation off → on | Layout reads after mid tier | Code bytes | Residual guest-call / packet sites |
|---|---|---:|---:|---:|
| lookup (`Internal.hs:649`) | 3293 → 3395 | 4 → 0 | 2,244 → 1,980 | 0/0 → 0/0 |
| fold (`Internal.hs:3426`) | 3310 → 3411 | 21 → 0 | 6,368 → 4,288 | 1/1 → 1/1 |
| worker (`lambda ww`, unique root in run) | 3343 → 3422 | 173 → 0 | 145,792 → 159,080 | 76/75 → 75/75 |

Before high-tier lowering the corresponding layout-read counts are 5→0, 26→0, and 173→0. The frozen fieldless implementation eliminates all selected roots' layout loads. Every residual call target in both inspected phases is `OptimizedCallTarget.callBoundary`; there are no residual `ClassValue` lookups or cold `DataValue.layout` calls. This is a check of actual call targets and memory locations, not a text search of inlined source stacks.

## Match proof reaches field access

In the off fold, before-high node **549** reads `LayoutDataValue.layout`, and **552** compares its identity. The root also retains generated-storage `InstanceOf` checks such as **4601**, and fallback `LayoutDataValue` checks such as **4623**. These are the two independently represented facts in the old path.

In the on fold, before-high **544** tests the exact `Bin` carrier (`GeneratedStaticObject$$4`). Its branch **626**, true successor **629**, and exact-carrier **Pi 4552** feed **784**, the direct `Bin.field__0` long load. The other Bin field loads share that same Pi. Thus the successful constructor test proves the representation needed by the generated storage access; no second layout check is needed. None of the three on roots retains the old nonexact generated-storage or `LayoutDataValue` subtype checks in this phase.

The on fold still tests the boxed key/value loaded from generic Object fields. For example, **1074** loads key field__1; **5576 → guard 5453 → Pi 5507** establishes its I# carrier before payload access. Likewise **1298 → 5593 → guard 2803** checks the value from field__2. These are warranted by the generic Map field representation. Removing them would require a stronger representation/specialization contract, not simply another spelling of Case.

## Do not count deoptimization bookkeeping as another branch

The on fold's apparent second I# check **1872** already receives exact I# Pi **5507**. It feeds **1873 → VirtualObjectState 4584**, not control flow. After mid tier the related chain is **6024 (LoadHubOrNull) → 6025 → 6490 → VirtualObjectState 4584 → FrameState**. It has no consumer on a guest branch or memory access. This remaining state-only chain is not evidence of another guest type-test branch; the late graph still needs values that reconstruct interpreter state on deoptimization. Machine-code analysis would be required to assign it an instruction cost.

## Remaining call costs and bounds of the comparison

The fold retains the same recursive frontier at depth six and one residual guest boundary (**5862 off / 5424 on** before high tier; **7519 / 5726** after mid tier). Its on graph retains one Object-array packet and two Long-box materialization sites. The worker still has 75 residual call-packet sites. Constructor ownership removes layout work, not the generic guest call ABI.

The worker's inlining/materialization shape changes: static Bin materialization sites are 133→213, Long sites 35→40, and its machine code grows despite its after-mid node count falling 17,362→16,925. These are static sites, **not dynamic allocation counts**. They neither contradict the separately measured allocation reduction nor prove a throughput win. Avoid adopting a wider recursive inlining budget based only on these graphs.

No new correctness flaw is apparent in these selected paths. The next bounded call-path investigation is whether a cheap terminal constructor arm can avoid a remaining guest call while preserving its entry strictness and field construction semantics; this report does not claim that optimization is implemented or profitable.
