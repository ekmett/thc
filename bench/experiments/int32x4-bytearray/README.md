# Int32X4 caller-ByteArray graph controls

This harness selects four genuine exported `SimdInt32X4ByteArray` roots from
`build/simd-int32x4-bytearray`: `vectorIndexWorker`, `scalarIndexWorker`,
`vectorStoreGraph`, and `scalarStoreGraph`. Pre/post-Tidy Core on AST/bytecode
produces sixteen first-attempt captures. Index roots take a caller `byte[]` and
an `Int#` offset; store roots take a mutable caller `byte[]`, offset, four scalar
`Int#` lane seeds and erased State. The store's final action freezes and returns
that same array. No vector argument or result crosses a function boundary.

The two offset families use 16-byte vector units and 4-byte scalar-Int32 units.
Each access moves sixteen bytes. Native byte order, signed low-32-bit lanes,
full-width bounds before scaling and state-before-effects are runtime contracts;
this graph control does not broaden their supported domain. Reads returning
`(# State#, Int32X4# #)` remain immediate local intrinsics only, not a general
vector-containing tuple transport ABI. The selected load roots use immutable
indexing; separate semantic tests cover mutable reads/local destructuring.

## Execution and input evidence

After native fixture preparation and `installDist`, with the pinned Graal JDK:

```sh
bash bench/experiments/int32x4-bytearray/run-runtime.sh build/int32x4-bytearray-runtime-capture
```

Reserve the shared build/runtime resource gate around that entire command. The
runner does not build or generate native inputs. An existing output directory
is rejected. Every command, log and status is retained, and any failure stops
the run. There is no retry, settling call, altered compiler limit or relaxed
compiled-entry condition: forty interpreted corpus passes, one explicit compile
of the actual active guest target, then two complete checked passes. Before and
after every measured call, that exact target must remain active and valid in
the last tier. Instrumented semantic tests separately check compiled counters;
these uninstrumented graphs do not claim a counter measurement.

There are 36 cases per selected root: each of four lanes visits nine signed
boundaries, all safe vector/scalar offsets are covered, and other lanes carry
distinct sentinels. Sixteen captures therefore check 1,152 installed-entry
invocations. Every invocation, including warmup, gets a fresh caller-owned
64-byte array. The probe independently constructs native-order little-endian
bytes and the signed checksum with weights 3/5/7/11. Index calls must preserve
all 64 input bytes. Stores must preserve the 48 outside bytes, update all 16
selected bytes, and return the identical reference; no mutation follows freeze.

Input preparation verifies all source/artifact hashes, the actual pinned GHC
binary hash, strict pre/post audit acceptance and exact one-lambda graph roots.
The retained 9,666 native rows must exactly match the separate byte model.
The graph reader and Java probe also independently check their selected cases:
one native scalar checksum per index case, all 64 native byte-selector results
per store case. Native pointer identity is not claimed; identity is checked in
the JVM. The native corpus is specifically little-endian x86-64, not big-endian
or AArch64 GHC evidence.

## Graph and allocated-LIR contract

The selected HighTier graph must contain exactly one live fixed plain
`ReadNode` or `WriteNode`, with `Array: byte` identity and no reference barrier.
Its `OffsetAddressNode` base must trace through type refinements to caller
argument zero in the original guest `Object[]`; its dynamic offset must depend
on the offset argument. It must dominate the public return. An arbitrary
`byte[]` reference, constant array, private payload, off-heap address, scalar
memory substitute, dead access or store/load-forwarded result cannot pass.

The load is exactly 128 bits. Its four `i32` cuts must each feed exact signed
32-to-64 widening and the public checksum. The store is exactly 128 bits and
must pack all four corresponding host lane inputs, each narrowed 64-to-32,
through the exact broadcast/insert construction. The result is the original
caller array. Equal-width byte/dword `ReinterpretNode` views are permitted;
wider/narrower conversions are not. Native execution establishes byte order,
offset units and checksum weights in addition to these graph relationships.

Pinned Graal sources in JDK `src.zip` explain this gate:

- `VectorAPILoadNode.expand` creates a force-fixed plain `ReadNode` and explicitly
  has no constant-fold callback; `VectorAPIStoreNode.expand` creates `WriteNode`.
- `VectorAPIUtils.memoryLocationIdentity` identifies primitive-array storage.
- `AMD64VectorMove.VectorLoadOp`/`VectorStoreOp` emit the selected move using a
  physical vector register, memory address and `AVXSize`.
- `LIRInstructionClass` prints their class-derived `VECTORLOAD`/`VECTORSTORE`
  opcode and separate `op` data field. The reader requires a full allocated
  XMM line with `VMOVDQU32`/`VMOVDQU`, not a mnemonic substring or comment.
- `LIRKind.toString` marks compressed references with `_`; HotSpot address
  lowering may use a `DWORD[_]` oop index scaled by eight alongside a QWORD
  offset. This exact physical addressing form is accepted, not arbitrary
  DWORD operands, virtual registers or stack-slot substitutes.
- `ObjectState.createEscapeObjectState` replaces default-valued entries with
  null. Omitted virtual-array entries therefore reconstruct as zero; they
  are not unknown byte values.

The durable `Int32X4` carrier is four primitive fields. The runtime loads/stores
through transient `ByteVector.SPECIES_128` and an equal-width IntVector view;
the selected compiled paths must remove carrier/vector/private-array allocation,
field traffic, calls and lane boxes. Caller backing memory is intentional, not
a payload-elimination failure. Host Object-array argument reads and exact Long
unboxes remain; index roots may allocate one public Long result, store roots
none. Eliminated `FrameWithoutBoxing.indexedTags` records require the complete
owner/field/sibling/deopt-only-use chain. Every snapshot must agree with its
Object/Long/Illegal tags (0/1/7), including default-zero omissions.
Constant interpreter bytecode arrays are allowed only in the exact generated
vector handler and `continueAt` FrameState local slots with the same receiver
and validated frame owner. Constant IntArray destination layouts are allowed
only in `Vector32Unpack.executeTuple`'s exact deopt parameter slot. These are
metadata recognizers, not permission for private payload access, escaping
references, carrier reconstruction records or materialization. Live allocation,
field traffic and calls are rejected before checking any metadata exception.
AST stores may additionally retain the host bloom-header Long unbox from
argument slot zero. It must feed only a constant-mask OR and the validated
frame's primitive slot-zero deoptimization record, matching `FunctionRoot.execute`.
It cannot supply the address, packed lanes, result or any other frame slot;
the ordinary offset and four lane unboxes remain separately required.

## Reproducibility and limits

Runtime sources, probe/runner, installed JARs and JDK identities are frozen
before capture and rehashed afterward, as are native provenance and artifacts.
Each result retains the raw BGV/CFG, selected parsed graph, final allocated LIR,
command, log and status. Exactly one selected HighTier graph and one final LIR
are required. Repeated identical compilation headers are not retries; different
compilation identities fail. Original readers are archived before execution.
An explicit offline correction may change only the reader and parser tests,
must retain the original failure, and cannot rerun guests or change other inputs.

```sh
python3 bench/experiments/int32x4-bytearray/test-runtime-audit.py
python3 -O bench/experiments/int32x4-bytearray/test-runtime-audit.py
```

These parser mutations are not hardware evidence. They include the four retained
signed Int32X4 multiplication graphs as arithmetic-only negative controls: each
fails specifically for having no packed caller-array memory access. Those
historical graphs are not new byte-array native results. No successful memory
capture is claimed merely by adding this harness. A different legitimate
compiler graph/LIR shape requires a reviewed, narrowly tested reader correction.
Any eventual passing package proves only its selected paths, not throughput,
no-spill behavior, a globally allocation-free ABI, all alignments/platforms, or
general vector function/join/capture/constructor/tuple ABI support.

The [retained x86 checkpoint](evidence-x86_64/README.md) has sixteen accepted
packed-memory graphs after the bounds-error deoptimization fix. It also retains
the original sixteen genuinely rejected compiled-exception graphs and both
original checker failures. The final bloom-reader correction was offline only;
no guest execution, runtime source or installed JAR changed for that correction.
