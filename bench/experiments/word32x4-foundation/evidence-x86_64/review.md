# Independent review scope

The fixture/model and graph-harness workers independently checked the pinned
GHC six-operation unsigned signature before implementation. The fixture worker
prepared fresh native evidence, tested export-only behavior and restored a full
native preparation; root then prepared the entire corpus independently.

The ultra reviewer independently reconstructed all 4,882 native/model answers
without importing the fixture model/preparer, including twenty complete
81-case lane domains and 732 unsigned high-bit observations. It checked exact
Core closure/root counts, all four Word32 tuple leaves, literal sites, native
provenance hashes and the actual signedness mutation issue multisets.

The xhigh reviewer read the runtime, representation checks, seven tests and
build/CI integration. It independently verified the focused report and all 198
full reports: archive membership, each original/retained XML, attributes, hashes,
481 unique testcase identities per mode, identical case sets, complete logs,
forced dense flag and execution of all twelve Gradle tasks.

Both reviewers verified all 73 source blobs against frozen runtime b048144,
eleven installed JAR and four JDK hashes after the forced rebuild, fresh native
provenance and the 84 capture artifact hashes. The ultra reviewer independently
traced all 48 output cuts through exact unsigned 32-to-64 extensions to the
public result, checked all twelve physical packed instructions, and confirmed
the precisely proved bytecode frame-tag metadata exception.

Retained parsed graphs, final LIR, commands/log/status files, both Core exports,
oracle and signed comparison package match their originals. Every Word32
execution gate passed first time; there was no reader correction or guest replay.
These checks involved no additional guest execution, builds or source edits.

Final README text, this review summary and SHA256SUMS were prepared by root after
those independent reviews and are explicitly outside the reviewers' sign-off.
Root checks the final manifest and documentation against actual observations.
This bounded review does not establish throughput, no spills, a globally
allocation-free runtime, vector calling conventions or cross-platform execution.
