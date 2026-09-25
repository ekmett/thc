# GMP transport proof

This is a bounded transport experiment, **not original GMP FFI admission**.
It checks whether Sulong can interpret an embedded LLVM adapter that calls a
real native GMP symbol while THC keeps its original managed byte-array storage.
No `BigInteger` replacement or managed-address-to-native-pointer cast is used.

The C adapter copies a checked source byte region into a `malloc` allocation,
calls external `__gmpn_add_1`, copies its output back into the destination view,
then frees the allocation. Only native allocations cross the GMP boundary.
The prototype deliberately aborts on invalid scalar inputs/allocation failure;
it is not production error handling and is never exposed to arbitrary guest Core.

The ELF file contains both `.llvmbc` and a native GMP dependency. Loading its
bytes as an LLVM source preserves dependency resolution. A volatile function
pointer prevents the compiler replacing this particular call with the inline
implementation supplied by `gmp.h`; `readelf` confirms the external symbol.

The Java driver uses the existing THC `CbitsBuffer` interop view. Ten fixed
cases check carry propagation, read-only inputs, exact source/destination alias,
and surrounding canary bytes. Expected values are explicit fixed controls,
not a native GHC oracle. LLVM guest compilation is disabled in this transport
probe: there is no compiled-Core or throughput claim.

Verified on Linux x86_64 with clang 20, native GMP and Graal 25.3.4.1. The source
requires 64-bit GMP limbs and `mp_size_t`, without nail bits. Other platforms
are not established. GMP's [low-level contracts](https://gmplib.org/manual/Low_002dlevel-Functions)
must be checked independently for each additional operation; overlap and zero
length rules are not interchangeable between operations.

Run under the host build/JVM resource gate with an already-built THC runtime:

```sh
THC_RUNTIME_LIB=/absolute/path/to/thc/build/install/thc/lib \
  bash bench/experiments/gmp-sulong/run.sh
```

Next: a provider-independent checked limb-region contract, native allocation
failure/lifetime handling, exact original FCallId recognition, native Haskell
oracles and first-installed AST/bytecode tests. This experiment adds no
capability-table entries and does not load hello or lens.
