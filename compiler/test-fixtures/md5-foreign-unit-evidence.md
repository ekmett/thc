# Synthetic MD5 descriptor-test grounding

The Kotlin `CoreMd5ForeignTest` and Python `test-core-md5-foreign.py` construct
explicitly synthetic proof maps. They are not renamed genuine exports, do not
alter the main-unit `ForeignCallAudit` descriptors, and do not execute Fingerprint.
Main-unit metadata must be rejected by the closed runtime contract.

The target unit and signatures are grounded in the original installed GHC 9.14.1
`GHC/Internal/Fingerprint.hi`, inspected read-only with pinned `ghc --show-iface`.
Its SHA256 is
`2e413f06303947cac8f9e8e0775d14a4908320f8092da17153d64a6282f89334`.
The installed file is under
`/home/ekmett/thc-benchmarks/2026-09-23-083914/toolchains/ghc-9.14.1-installed/lib/ghc-9.14.1/lib/x86_64-linux-ghc-9.14.1-d8ec/ghc-internal-9.1401.0-56a8/`.

Actual resource-gate output is retained at
`/home/ekmett/.codex/worktrees/98d0/cult/build-agent-logs/20260923-235903-3h1dfnzj/output.log`,
SHA256 `cf93e16155f2e06beb4fed221f4dce716b3fa820c74b187b5de1271ec2812d5e`.
Lines 296, 303 and 338 directly show unsafe static ccall targets in the exact
`ghc-internal` unit for `__hsbase_MD5Init`, `__hsbase_MD5Update` and
`__hsbase_MD5Final`. Their machine arguments are respectively Addr/State,
Addr/Addr/Int32/State and Addr/Addr/State; all return a singleton-State tuple.

This text documents primary evidence; production validation does not parse
printed interface strings or foreign-variable names. The exporter obtains its
structured descriptor from GHC APIs. Full original-source export and guest ABI
execution remain the integration owner's separate acceptance steps.
