# Native Image feasibility evidence

See the [investigation report](../../../../docs/native-image-feasibility.md).
Source base: `3f9e3c64fe7caf94f738018a106140f47e7f6251`; later probes include
only the recorded constant-class-token split in `DataLayout.buildShape`.

**No image linked or ran.** Build timings are bounded compatibility diagnostics
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
