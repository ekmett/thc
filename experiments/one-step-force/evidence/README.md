# One-step Force: isolated Linux comparison

Replacing the forcing loop with one state dispatch removes five artificial loops from the compiled Map worker. Throughput differences were small: −0.47% with the current Agnostic policy and −0.86% with Default. Exact Agnostic allocation fell 0.37%; worker code fell 10.30%, while materialized thunk sites remained unchanged. These results support a bounded simplification, not a large performance claim. The candidate is isolated from main pending broader semantic coverage and rollout review.

| Policy | Existing loop ms | One-step ms | GHC ms | Candidate/control | Candidate/GHC |
| --- | ---: | ---: | ---: | ---: | ---: |
| [Agnostic](agnostic/README.md) | 1.587093 | 1.579622 | 1.340949 | 0.995293 | 1.177989 |
| [Default](default/README.md) | 1.588273 | 1.574656 | 1.341233 | 0.991426 | 1.174036 |

Each policy has a separate three-process comparison with 45 raw windows, at least 45 seconds and 30,000 guest warmup calls, native checksums, installed-code checks and no measured/final Truffle events. Compare each row internally; the two policy rows were separate runs. All windows and fork medians remain available, including the two slower Default candidate windows. Native oracle and control/candidate preflights passed all 18 inputs both before and after compilation on both AST and bytecode. All 179 unit tests passed; XMLs and hashes are retained.

The only changed decompressed runtime JAR entry is `thc/runtime/Force.class`. The candidate is based on main `2844d484e2a6c697b889067bd029321fc2306999`; control JAR SHA256 `5af987892c35fedc0f170d245574ad704609e9747c7993f970991191d71a773c`, candidate `c2c2d12e10beb636c830f45c77f7b8998fbb98109af7a74263d64835695b52d2`. Core, dependencies, GHC native executable, options and workload are held fixed within each comparison. Caller-demand remains absent/off. The [invariant audit](force-invariant-audit.md) explains why a successful memoized value cannot be another Thunk; two added tests cover rejection and reentrant forcing. Future forwarding/selector states must preserve this invariant explicitly.

[Actual graph/allocation evidence](diagnostics/README.md) includes raw selected BGVs, CFG/SSA dataflow, JFR profiles and an eight-phase independent archive reparse. It is separate from the measured throughput windows. No additional policy or performance sweep was run after the user redirected work to broader GHC correctness coverage.

Run `python3 tools/verify.py` to independently validate both comparisons and the diagnostic archive using Python 3.10+ without JVM or network. The archived capture drivers retain exact commands and hashes. This is a bounded Map Int Int result on the recorded i9-12900K Linux host, with Mac-exported frozen Core and a Linux-built GHC 9.14.1 native oracle using unmodified containers-0.8; it does not establish general Haskell compatibility or a universal speed improvement.
