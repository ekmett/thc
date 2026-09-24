# Public Show Word and Show [Int]

This coverage uses the ordinary public `show` implementations for machine `Word`
and lists of machine `Int`. The whole pinned GHC 9.14.1 Show source supplies the
original `showWord`, `$fShowList_showl`, and `$fShowCallStack_itos'` workers.
The whole original CString module supplies `unpackCString#`, retained when empty
list formatting becomes the literal `"[]"`. No formatter, error body, constructor
or interface identity is replaced.

The preparer exports four public consumers before and after Tidy. Each observes
either a wrapping checksum or every character and the end of the result. Word
inputs include the high bit and maximum unsigned value; list inputs select empty,
singleton and three-element lists containing `n`, `n+1`, and `negate n`, with normal
machine-Int wrapping. An independent Python decimal/list model covers 530 inputs,
every bit boundary and decimal-width transition, machine extrema, internal zero
and repeated digits, and a fixed random sample. The 41,816 native rows include
negative-index and first-past-end sentinels independently of the checksum.

Strict preparation retains two negative public-interface-only audits: the exact
three Show workers and CString helper are missing. Positive audits supply complete
source modules, replacing the entire redundant Show-owned interface closure.
They require the real worker identities and `quotRemWord#` or `quotRemInt#` in
each reachable digit path. Unused original source definitions remain supplied;
only ordinary reachability determines the selected workload, as elsewhere.

`ShowWordListTest` checks the native rows and independent Java unsigned/list
formatting, then exercises AST and bytecode before/after compilation, with normal
inlining and residual calls. Every measured row requires compiled guest activity,
valid original and actual active targets, unchanged target identities, and empty
input/result loans. Compiler limits and warmup policy are unchanged. Default and
dense-handoff executions are separate gates; audit acceptance alone is not a
compiled-execution claim.

```sh
python3 scripts/prepare-show-word-list.py
python3 scripts/prepare-show-word-list.py --check-only
python3 scripts/test-show-word-list-model.py
./gradlew test --tests thc.runtime.ShowWordListTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew test --rerun --tests thc.runtime.ShowWordListTest
```

Fresh preparation and CI retain original source hashes, complete Core modules,
native requests/results, exact audits and provenance. This adds no runtime or
primitive capability. It does not close `arrEleBottom`, Typeable/fingerprint,
backtrace, general Integer formatting or `Numeric.showHex` dependencies.
