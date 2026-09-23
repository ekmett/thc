# Pinned boundary linkage: source-level blocker

Successful guest compilation and restoration of the shared boundary stub do
**not** guarantee that the next ordinary public call enters compiled guest code.
This inspection identifies the missing link without another workload replay.
It does not claim a production fix or prove the exact native transition of the
preserved failing invocation.

## Three different states

| State | What establishes it | What it does not establish |
| --- | --- | --- |
| Specialized guest/host target code | Target compilation and last-tier validity | The route by which an ordinary caller will enter that target |
| Shared `callBoundary` default nmethod | Boundary method `hasCompiledCode()` / stub installation | Whether existing compiled callers have been relinked to it |
| A compiled caller's actual link | Native call-site linkage / runtime transition | Not exposed by either of the validity checks above |

THC's explicit compile operation covers the first two. The failed candidate's
preserved HotSpot log installs boundary6086 at90.646s, then records relevant
interpreter-linked caller sites being cleaned at90.647–90.659s. Its compiled-entry
counter remains zero. The separate root-only trace reproduces interpreted host
and guest root entry, with selected/actual guest identities matching. These are
separate runs: post-failure validity is not entry-time validity, and native
call-site receiver attribution remains incomplete.

## The current-call fallback rule

Pinned `OptimizedCallTarget` bytecode gives these exact branches:

| Method | Bytecode offsets | Effect |
| --- | --- | --- |
| `interpreterCall` | 18–33 | If target validity is true, invoke the repair hook and set local `bypassed=true`. |
| `interpreterCall` | 80–132 | Return true only after hotness-triggered compilation succeeds and `(AOT || !bypassed)`. |
| `callBoundary` | 1, 4, 9, 15 | A false result selects `profiledPERoot`; only true selects `doInvoke`. |
| `doInvoke` | 2 | Invoke `callBoundary` again through ordinary linkage. |

On the HotSpot-hosted, non-AOT runtime, a valid-on-entry bypass therefore forces
`interpreterCall` to return false, regardless of whether boundary restoration or
the hotness compilation succeeds. The same invocation continues through
`profiledPERoot`. It does not retry compiled target entry. This conclusion does
not depend on the current call count being below the compilation threshold.

`profiledPERoot` can itself be Java-JIT compiled; that is not Truffle guest-code
entry. Nested targets might separately enter compiled guest code, so this rule
alone does not imply a zero counter for every possible workload. The preserved
Sequence root trace is the evidence that all its observed roots were interpreted.

`HotSpotTruffleRuntime.bypassedInstalledCode` never reads its target argument.
After its initialization check it installs shared boundary methods and returns
void. `HotSpotTruffleCompilerImpl.compileAndInstallStub` returns immediately if
the method already has compiled code; otherwise it compiles and calls
`setDefaultCode`. Neither inspected path accepts a caller-link handle or redirects
an already-entered Java invocation. The final `HotSpotOptimizedCallTarget` inherits
the ordinary `doInvoke` implementation, not an independent installed-code route.

Validity is also not universally a pure field read: virtual dispatch can enter
`HotSpotNmethod.isValid`, which conditionally calls `updateHotSpotNmethod` when
`compileIdSnapshot != 0`, then delegates to the superclass entry-point check.
That refresh is not guest execution or an eager caller-link repair. Likewise,
JVMCI's `executeVarargs` documentation disclaims unconditional wrapped-nmethod
execution; it is not an automatic alternative guarantee, and no alternative
invocation mechanism is adopted here.

## Native adapter and field identity

In the exact pinned Linux x86-64 `libjvm.so`:

- `SharedRuntime::generate_i2c2i_adapters` at0xf69490 emits a check of
  `Method::_code` (offset0x48), then the eligible fixup call when code exists.
- The generator references `fixup_callers_callsite` at0xf69916 and emits its call
  at0xf69957. After restoring state and rearranging arguments, it emits a load
  from Method offset0x38 at0xf69a0b/0xf69a2d and a register jump at0xf69a3a.
- `fixup_callers_callsite` checks the current caller/code and eligible direct-call
  relocation. Its call at0xf539dd cleans that caller via
  `CompiledDirectCall::set_to_clean`, then returns. It is not an all-caller scan.

Offset0x38 is independently named by the VM's general serviceability VMStructs
table, although not exported by its smaller JVMCI field map:

| Method field | VMStruct row | Initializer instruction | Offset |
| --- | --- | --- | --- |
| `_code` | 0x172c400 | 0x32f4ac | 0x48 |
| `_i2i_entry` | 0x172c430 | 0x32f4b7 | 0x38 |
| `_from_compiled_entry` | 0x172c460 | 0x32f4c2 | 0x40 |

Relative relocations name the rows. `_GLOBAL__sub_I_vmStructs.cpp` writes their
offset values to row+0x20; the raw ELF offset cells initially contain zero.
The `.init_array` relocation at0x16422d8 names that initializer at0x32eab0.
Thus reading only raw table bytes would misreport the offsets. The generator's
post-fixup jump is to the interpreter entry, not the new compiled entry.

These are offline generator/control-flow facts, not a capture of one live
adapter executing. They corroborate lazy repair for later calls without filling
the remaining receiver/transition gap in the original failure.

## Reproduce the inspection

`inspect.sh` pins the VM binary, source archive and Truffle runtime/API JAR
hashes before using native address ranges. It requires Bash, GNU binutils,
`sha256sum`, `unzip`, `rg`, and the pinned JDK. From the repository root:

```sh
. /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
bash -n bench/experiments/sequence-boundary-linkage/inspect.sh
bash bench/experiments/sequence-boundary-linkage/inspect.sh \
  /home/ekmett/ai/thc-sequence-current-main-01a0cdeb/build/root-boundary-repair-validation/lib \
  build/source-linkage-inspection
```

The script writes disassembly, bytecode and installation-source inspection
outputs to that build directory. It compiles and runs only `VmMetadata`, a
read-only metadata helper: no Truffle context, call target or guest workload,
no attachment to another process, no native memory writes and no runtime patch.
The helper confirms `TruffleOptions.AOT=false`, `_code=0x48`, and
`_from_compiled_entry=0x40`. Using native-image libgraal as compiler does not make
the HotSpot-hosted Truffle runtime AOT.

Validation: pinned inspection exits0, shell syntax passes, missing-argument
guard exits2, and hash validation rejects a mismatched pin before inspection.
The inspector initially caught a transcription error in its API-JAR hash; the
pin was corrected from the measured artifact before the successful runs.
Full retained output remains local under `build/source-linkage-inspection/`;
vendor source/disassembly is not redistributed in this commit. Only original
inspection code and analysis are added under THC's existing license.
`SHA256SUMS` records the twelve retained inspection outputs; check it from the
output directory. Disassembly headers include the local JDK path, so a relocated
toolchain can change an output hash even when the input binary hash matches.

## Concrete remaining blocker

No inspected supported Truffle operation eagerly repairs every existing caller
or guarantees redirection of the current bypassed invocation. The source-level
THC repair is therefore insufficient to guarantee entry despite stale linkage.
Guaranteeing that behavior needs a runtime/linkage mechanism and a deterministic
native regression that reproduces the original zero-entry symptom. The existing
tiny regression proves stub restoration only; it does not reproduce that symptom.
Independent source/allocation fixes may change whether the condition arises;
their coverage must be tested separately against the unchanged strict gate.

This bounded inspection is exhausted at that API/linkage boundary. More passing
timing controls, primops, settling calls, compiler/collector switches or relaxed
counters would not establish the missing guarantee. Production code, strict
coverage, and the parent-owned boundary PR remain unchanged.
