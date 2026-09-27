# Removing avoidable call packets

This historical report is preserved in
[the research archive](../research/call-packets.md). Its implementation sequence,
test counts, compiler graphs and measurements describe the recorded revisions,
not current capability or performance. The original evidence files are unchanged.

For current calling conventions and ownership rules, see
[residual call boundaries](call-boundaries.md), [dense handoff](handoff-slabs.md)
and [typed execution and tail cycles](typed-tail.md).

## Current graph audit

[The packet audit tool](../tools/audit-call-packets.py) reads retained graph JSON
to report packet arrays, Long boxing, other explicit allocations and
null-result guards. Its provenance fields describe graph structure, not
an observed branch outcome.

The report also inventories explicit after-mid allocation nodes beyond the
Object-array and Long subsets, including guest constructors, primitive arrays
and frame objects. Zero packet or Long sites does not mean zero allocations;
residual calls can allocate outside the inspected graph. Before-high null-result
guards include their condition, accepted/rejected value, source and paths from
call results through aliases. These paths do not prove which arm executed or
caused a recorded deoptimization. Match a failing run's compilation and trace
before drawing that conclusion. The existing strict lookup gate is unchanged.
