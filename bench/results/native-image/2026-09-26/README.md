# Native Image feasibility evidence

See the [investigation report](../../../../docs/native-image-feasibility.md).
Source base: `3f9e3c64fe7caf94f738018a106140f47e7f6251`; later probes include
only the recorded constant-class-token split in `DataLayout.buildShape`.

**The first-phase bundle has no linked image.** Build timings are bounded compatibility diagnostics
on a shared host, not performance comparisons. Runtime guest JIT, native startup,
executable size and native Sulong/FFI execution remain unverified.

The [compressed evidence](probe-logs.tar.gz) contains:

- Native Image commands, complete output and `/usr/bin/time -v` results,
  including the runtime-compilation method list and call tree.
- Native Image `svm_err` analysis reports; `images/` contains reports, not binaries.
- Tool versions, SHA-256 hashes of the Core, oracle, JARs, downloaded source
  archives and modified source; the separate exact-base artifact manifest.
- Native GHC control results (`sumLoop 100 = 5050`, `caseList 20 = 210`) from
  `examples/NativeOracle.hs` and the original `THC.Fixtures` definitions.
- Corresponding JVM scalar/array-storage results and stderr.
- The successful rebuild and `ClassOwnedLayoutTest` output/XML: five tests each
  under `testDefault` and `testDense`, no failures or skips.

Extract into a fresh temporary directory to inspect it:

```sh
probe_evidence=$(mktemp -d)
tar -xzf bench/results/native-image/2026-09-26/probe-logs.tar.gz -C "$probe_evidence"
```

The ignored `build/native-image/` directory in the investigation worktree retains
the larger local inputs. Dependency binaries and third-party source archives are
not included in this committed evidence bundle. Exact official source versions,
scope, reproduction steps, failures and follow-up acceptance gates are recorded
in the report; scratch initialization lists are not production configuration.

## Second phase: lazy-fork constructor boundary

The [second-phase bundle](constructor-boundary-logs.tar.gz) covers probes 14–30
on `1df1ef5b` plus the `ForkActionRoot.execute` interpreter-transition correction.
It includes read-only Java probes of the hosted Native Image Feature ABI and
Truffle compiler behavior, command/output logs, image error reports, original
Haskell fixture receipts, input hashes, and 58 passing JVM test cases across
default/dense handoff modes. The temporary loader-boundary experiment was
reverted; its failed image result is retained.

The real THC image passes the constructor assertion and reaches native method
compilation, then fails an unprepared-deoptimization-method invariant. It still
does not link. Separate Truffle-only controls do link, and the initialized-root
control executes installed guest code (`42` interpreted, `43` compiled). Those
controls are explicitly not Haskell acceptance workloads. The exact commands,
class initialization distinction and remaining limits are in the report.
