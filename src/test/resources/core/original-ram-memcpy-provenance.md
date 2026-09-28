The adjacent `original-ram-memcpy-descriptor.json` is the unchanged `foreignCall`
object from GHC 9.14.1 original package Core, not a declaration with its unit
rewritten. The installed unit is
`ram-0.22.1-2a51185094480d094013d0b33470033e58dd5b33488d175039d80ae995cb3c24`.
The boundary is optimized Core after Tidy and before CorePrep, x86_64-linux.

The retained ram bundle SHA-256 is
`4a9cb6ee9de8b291e3a4cf9b566a4ebb08d68f2650887a822e7cf582c977f9a1`.
Its `Data.ByteArray.Bytes` member `core/1.json` has SHA-256
`d73df88824dcf4d72e593bbe6df5dfa0f27af2155b91e1027009b5a865853699`;
`Data.ByteArray.Types`, `core/12.json`, has SHA-256
`c5da7116324b60d26c1564e24c10c93cb3c15f95b3f34b80725ab54ee1609562`.
The original FCallIds identify unsafe static `ccall` `memcpy` from that ram
unit with `Addr# -> Addr# -> Word64# -> State# RealWorld ->
(# State# RealWorld, Addr# #)`. The four calls in `Data.ByteArray.Bytes`
and one in `Data.ByteArray.Types` retain an identical descriptor. Its file
SHA-256 is `bab61fb46a725c20fd2d15e608a628a851ff9d81bcc8db65c43f08bb0d0b2b36`.

`OriginalMemcpyTest` uses explicitly synthetic callers of this retained
descriptor. The adjacent `original-memcpy-native.tsv` contains 36 genuine
GHC/libc observations, including empty/interior copies, returned destination
identity, destination bytes and unchanged source bytes. Regenerate from the
repository root with pinned GHC 9.14.1:

```sh
mkdir -p build/ram-memcpy/native
ghc --make -O2 -dynamic -fforce-recomp -dcore-lint -dstg-lint \
  -odir build/ram-memcpy/native -hidir build/ram-memcpy/native \
  t/fixtures/compiler/OriginalMemcpyNative.hs -o build/ram-memcpy/native/oracle
build/ram-memcpy/native/oracle > src/test/resources/core/original-memcpy-native.tsv
```

Producer SHA-256: `8f8bf6f4b490445fa4241460f5e55b24a8c8ad3941442a36f376bc41c36928d4`.
Oracle TSV SHA-256: `462d975a2acad4d88c015435bf39fa128ece805427dd4cf6e998841b3551a8f5`.
The native oracle exercises the matching libc declaration. It does not execute
the ram package closure, establish its archive-only native obligations, or grant
raw numeric pointers access to guest storage. Those remain separate checks.
