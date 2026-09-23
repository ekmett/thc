# One-step Force experiment

Base: `2844d484e2a6c697b889067bd029321fc2306999`; release control JAR `5af987892c35fedc0f170d245574ad704609e9747c7993f970991191d71a773c`.

The runtime's only successful `Thunk.state = 2` publication is in `Force.execute`, after it rejects any result that is itself a `Thunk`. It publishes the answer, clears target/environment, then writes state 2. Failed guest/runtime evaluations publish state 3; unexpected host failures reset state to 0 and retain the suspension for retry. **VALUE/state 2 is terminal non-Thunk WHNF.** No other runtime path or test directly publishes an indirection chain by assigning state 2. Future selector `FORWARD` or `SELECT_PENDING` states must be distinct and explicitly dispatched; they must not reuse VALUE or fall through the ordinary unevaluated case.

The isolated implementation replaces `while (value is Thunk)` with one type check and one state dispatch. Memoized answers and newly evaluated answers return directly. Existing checks, tail-bounce handling, counters, retained failure identity, blackhole detection, retry ordering, target/environment clearing and the explicit returned-Thunk rejection remain. Manually forged state-2 chains are outside the supported internal invariant, as reviewed by the root agent; no fallback loop was added for them.

Two added tests cover the contract that makes this legal:

- A target returning a Thunk is rejected without forcing the inner thunk, memoizes the same RuntimeFault, releases the outer suspension and retains the untouched inner suspension.
- Reentrant forcing through the same Force node updates two independent thunks once, shares their separate answers and preserves hit/evaluation counters and retention behavior.

All 179 tests pass (177 existing plus these two); the source fixture/native-oracle inputs are recorded in `agnostic/provenance/test-fixture-inputs.json`. The decompressed candidate JAR differs from the release in **only `thc/runtime/Force.class`**. Exact per-entry hashes are in `agnostic/provenance/jar-entry-diff.json`; the new JAR is `c2c2d12e10beb636c830f45c77f7b8998fbb98109af7a74263d64835695b52d2`.

## Original actual graph evidence

`original-default-force-loop-audit.json` extracts the existing Linux `Default` control worker (compilation 3479, raw BGV SHA `0aab27c4d42c306a4715a200564cd2a06d299e60bc8bf6681f8ffcfbc8a57314`). This is pre-experiment evidence, not a candidate graph or a claim about Agnostic.

Before high-tier lowering it retains five Force loops: LoopBegin nodes **3059, 4385, 19052, 27137, 38443**. Loop 27137 has phi **27138**, which merges a newly materialized Thunk **41485**, the generic memoized answer load **27180**, and several constructed or returned `Bin` values. The fresh thunk's only operational use is this phi; its other use is a materialized-object deoptimization state. This explains a plausible escape-analysis obstacle: the forcing loop merges the suspension with its answer and feeds that generic object back through thunk classification. The other two simple loop phis start with the same constant CAF thunk.

The experiment tests whether removing those artificial backedges and phis reduces graph/runtime cost. It does **not** assume scalar replacement or a speedup. The first controlled comparison uses explicit **Agnostic**, matching the runtime's current default, because a benefit under Default cannot establish a safe rollout under Agnostic. Caller-demand remains absent/off; Core, dependencies, flags and native binary stay identical. No new representation or call-transport mechanism is involved.

The completed matched Agnostic evidence is in `diagnostics/`; both throughput policies and exact options are retained in `agnostic/` and `default/`. The original Default graph above remains hypothesis provenance, not the matched Agnostic graph.
