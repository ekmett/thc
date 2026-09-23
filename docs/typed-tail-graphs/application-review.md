# Actual typed application graphs

Both synthetic fixtures run the frozen AST from `cb7488259e8418ee1fdff5c5d37569e40b44af29`, JAR `570901a5300dac18d63fb7f93177b76992108e65b37f998fe6f412610045d7f0`. Instrumentation is disabled; inputs vary from 0 through 15; checksums and installed guest code are verified. These are graph experiments, not throughput measurements.

`typedDirect` combines a direct lambda with a captured lambda. It exercises changing captures, nested cases, an unlifted local binding and a machine-integer minimum value. The root's inlining tree includes both `lambda direct` and `lambda delta`. Compilation 1891 has 29 nodes before high-tier lowering and no residual calls, Object[] allocations, closure allocations or captured-frame allocations.

Its two static Long allocation sites are alternative root returns, not intermediate application results. In the before-high graph, primitive Add1369 feeds Box1725 and Return1453 in B1; primitive Add1727 feeds Box1729 and Return1728 in B2. If1658 selects one branch. Each box's only value use is its ReturnNode. The lowered allocations1791 and1824 are the corresponding output boxes; normal execution reaches at most one, and the Long cache can avoid allocation for some values. The inlined applications' arithmetic remains primitive until this final Object-valued root return.

[Actual direct/captured CFG](applications/typedDirect/root-graphs/graph-00005-cfg.svg) contains all scheduled node labels from the selected graph. [Node-level proof](application-graph-proof.json) records primitive box inputs and every box use.

`typedPap` forms a partial application of a two-argument maker, then supplies its remaining argument and an extra argument to the returned captured lambda. Both maker and returned lambda inline. Compilation 1897 still contains one residual `Closure.pap(Object[], int, int)` call and one materialized Object[1] argument array. Commit2424 materializes array2121; AllocatedObject2425 carries it to MethodCallTarget454. The packet auditor classifies that path as a residual call ABI.

The cause is explicit: `Closure.pap` is a Truffle boundary. Its combined-prefix array and Closure allocations occur behind that boundary and are not counted among allocation nodes in the guest graph. It is therefore incorrect to describe this entire partial/overapplication path as allocation-free, or to count the single visible array as its complete allocation cost.

After that boundary, arithmetic reaches primitive Adds2448/2444 and alternative root-return boxes2439/2446. Both boxes feed only ReturnNodes. There are no residual guest callTarget calls. The remaining obstruction in this fixture is PAP construction, not failure to inline the two lambda bodies.

[Actual PAP/overapplication CFG](applications/typedPap/root-graphs/graph-00005-cfg.svg), [packet audit](applications/typedPap/packet-audit.json), and the [original focused BGVs](focused-applications-bgv.tar.xz) preserve this distinction. These captures do not validate subsequent thunk/writeback or PAP optimizations; those require their own frozen evidence.
