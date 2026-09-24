# C pointers through Sulong

This small probe checks the native boundary needed for THC's future `cbits`
support. It runs the same C functions natively and through Sulong: signed
64-bit arguments and results, allocation, byte writes, pointer offsets,
unsigned byte reads, and explicit release. Pointer values pass back through
interop unchanged; they are never converted to integers.

```sh
bench/experiments/sulong-cbits/run.sh
```

Use THC's pinned GraalVM and a compatible Clang (`THC_CLANG` overrides `clang`).
The optional Gradle project resolves `llvm-community:25.3.4.1` into the usual
shared cache. It does not add LLVM to ordinary THC builds. Outputs go to
`build/sulong-cbits/`.

The probe checks eight native oracle rows with compilation disabled, then with
synchronous compilation of the LLVM interop entries. Varied inputs populate their
profiles; the compilation trace must show all six interop entries compiled at
tier two with more than the initial deoptimization stub, without a subsequent
invalidation. This establishes compiled interop call roots; it does not require
each C function body to have its own separately installed root. There is no
warmup spin loop. This is a correctness probe, not a performance measurement.

Compilation is restricted to `thc_` functions. Forcing *every* Sulong root to
compile on its first call currently fails in `LoadModulesNode.loadModule` with
a virtual-frame materialization bailout on the pinned version. Module loading
does not belong to the compiled call boundary being tested here.

This does not yet connect GHC foreign declarations to THC. The runtime currently
represents `Addr#` literals as a managed byte array plus an offset. Native
addresses need a separate carrier with context and allocation ownership, a
lifetime shared by offset views, and an explicit release policy. Managed
storage also needs an interop adapter or stable native allocation before a
native C function can retain its address. A JVM object reference is not a C
pointer. Supporting tuple transport alone does not provide that bridge.

See the upstream [embedding example](https://www.graalvm.org/latest/reference-manual/llvm/Interoperability/)
and [native memory rules](https://www.graalvm.org/latest/reference-manual/llvm/NativeExecution/).
