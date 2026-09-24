# Independent read-only MD5 dispatch and port review

Reviewer `/root/int8x16_fixtures_xhigh`, recipient `/root`, eak-quartus.
Root `/home/ekmett/ai/thc-pinned-addresses-01a0cdeb`, observed HEAD
`53f40c4b3b5013d49203c5ea5e7ae95d531fdf75` plus ongoing dirty integration changes.
No source edits, builds, native calls or JVM execution were performed. This is a
source/proof review, not a claim that the new JVM tests ran or public Fingerprint
execution succeeded. This handoff is the only requested durable review write.

## Actionable finding: reject resolved or malformed foreign variable heads

At review time `Program.kt:1305–1307` and `BytecodeProgram.kt:841–844` selected the
MD5 adapter after validating the descriptor but checked the function only with
`fn[0] == "var"`. They never validated the ID or resolved it against lexical
bindings, joins, tuple locals or module globals. The newly edited auditor similarly
skipped walking the function when it recognized the MD5 descriptor.

An otherwise exact MD5Init descriptor attached to a call of a real Haskell
`doNothing :: Addr# -> State# s -> (# State# s #)` therefore bypasses that body and
initializes the context instead. Even a malformed `['var', null]` head can reach
this branch because its ID is never consumed. This is a concrete malformed-proof
admission path visible in the implementation, not a failure of valid GHC-exported
foreign calls; no guest reproduction was executed by this reviewer.

Require a well-formed string variable identity with an exact scalar closure proof
and reject an identity present in lexical locals/joins/tuples or module globals,
consistently in both loaders and the auditor. Genuine FCallIds are unresolved
foreign identities, not Haskell definitions. This guard needs no synthetic-name
parsing. Add negative controls for resolved global/local/join heads, malformed IDs
and wrong function proofs while retaining the genuine unresolved call shape.

## Scoped approval of the remaining reviewed semantics

- The descriptor selects only the closed three-symbol adapter; no ordinary
  Haskell Fingerprint/Typeable body is explicitly replaced. Missing descriptors
  and unknown symbols follow ordinary resolution/frontier handling. The finding
  above is the outstanding loophole in this boundary.
- AST evaluates operands once, validates the final State token, then calls the
  managed operation. Bytecode evaluates ordered operands and validates State in
  each operation before invoking ManagedMd5. Invalid State wins over UPDATE's
  invalid length and precedes all MD5 writes.
- The operation result remains a logical singleton-State unboxed tuple with zero
  physical fields. AST `executeTuple` writes no payload and returns only after
  success; bytecode emits a void operation through the tuple destination path.
  Existing tuple-case lowering binds the logical State only after the call. No
  aggregate payload or new generic vector/foreign ABI is introduced.
- Complete context/input/output ranges and literal destination mutability are
  checked before mutations. ManagedAddress validates full Long ranges before
  narrowing. UPDATE accepts only the explicitly bounded nonnegative CInt domain,
  rejects oversized host Long values, and does not silently truncate them.
- UPDATE preflights every planned memcpy overlap before incrementing counts or
  performing earlier copies. It does not snapshot aliased input: defined changes
  to shared backing remain visible in the same order as C. FINAL rejects overlap
  with its actual source buf[4], permits defined output overlap with other context
  bytes, copies the digest, then zeros all 88 context bytes as the pinned C does.
- Native-endian 32-bit context loads/stores, explicit little-endian byteSwap,
  unsigned count carry, padding boundaries, appended bit counts and context
  clearing agree with the pinned C source. An independent read-only source check
  matched all 64 C MD5STEP rounds against the Kotlin loop: round/function order,
  permuted word indices, constants and rotations. Wrapping Int arithmetic and
  `rotateLeft` match the C uint32_t steps.

## Tests and limits

`Md5ForeignCallTest` honestly labels itself synthetic ABI execution. It compares
dispatch results with direct ManagedMd5 calls; that is a dispatch test, not an
independent algorithm oracle. Independent pinned-C context/output comparisons
belong to the separate ManagedMd5Test/native corpus. The reviewed direct dispatch
test covers interpreted/forced-compiled AST and bytecode, exact +1 compiled-entry
counts, last-tier validity, pool/reference release, invalid State without effects,
and invalid UPDATE lengths. It also rejects main-unit and missing descriptors.

This review did not execute those tests, revalidate the saved native corpus, or
exercise big-endian hardware. Original public Fingerprint source execution remains
the parent's separate acceptance step. These are evidence boundaries, not newly
identified algorithm bugs. During inspection a few guessed file paths were absent
(ManagedAddress lives in LiteralAddresses.kt); these were read-path misses, not
product/test failures.

## Reviewed file SHA256 identities

- ManagedMd5.kt: `365c385d44d12d62e5ba87476295ca3262d428df9411e49c788bf2d4aa1eec03`
- Md5ForeignExpression.kt: `efe5e04d68713a7b53e9197d4c24f719810fbfe7718a8235cd3f3b48eb73d625`
- Program.kt: `e7e15c195b553cf7358263e1646d62333d21aec99ce2b6a65ab6b7fba55c922b`
- BytecodeProgram.kt: `c65dbe42c503cc02f89485f2f5053bedef99c13421675f0bd61b548cd0ac808b`
- BytecodeRoot.java: `28e692614a7e4fd8f7c273e34d08c9c98e33900d3745fd3dd5641d2888c05225`
- Md5ForeignCallTest.kt: `db18b8f8aca0bdfc50bdff78ca67aa84284a229398ae79e2f41c00eb84ee187b`
- Pinned md5.c: `2e5d9dff69905bdf1d8bacbc00f9fb75dc6a2559f0276794f3f36fc8f7857554`
- Pinned md5.h: `be128d81cad7364be65ce3b6d86f246cb4addfc0b7b7fe32b50af8876999cd15`

The root was actively integrating; these hashes identify the exact inspected
versions and do not sign off later modifications.
