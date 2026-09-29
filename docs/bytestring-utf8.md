# Original ByteString UTF-8 validation

THC admits the original ByteString 0.12.2.0 pointer declarations of
`bytestring_is_valid_utf8`: safe or unsafe `ccall`, `Ptr Word8 -> CSize -> IO CInt`.
The original installed unit ID is retained. Other versions, ByteArray# operands,
interruptible calls, changed representations and locally defined foreign heads
reject before execution.

Linux x86_64 builds execute the unchanged, source-hashed upstream C through
Sulong. The explicit `__STDC_NO_ATOMICS__=1` macro selects its portable fallback;
the build verifies the exported validator and absence of native `memcpy`
dependencies. Native access is required, including empty calls.

Each invocation borrows read-only views of the existing managed, pinned,
literal or owned malloc storage. Range, context and lifetime checks run before
C, and storage locks/borrows last until completion. No staging copy or native
address projection occurs. Null is accepted only for zero length; negative or
out-of-bounds lengths, opaque pointer cells and unowned addresses reject.

Safe calls poll for guest async delivery after C completes and its result is
saved. Resumption consumes that saved result without replaying C. Unsafe calls
do not add a return poll. Neither declaration interrupts host/native execution.
