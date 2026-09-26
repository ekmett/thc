The adjacent descriptor is the unchanged `foreignCall` JSON retained from
`GHC.Internal.IO.Handle.Text.hPutBuf2` in original GHC 9.14.1 Core, unit
`ghc-internal`, at the optimized-Core-after-Tidy-before-CorePrep boundary.
Its source declaration (Text.hs:1181) is:

```haskell
foreign import ccall unsafe "memcpy"
   memcpy :: Ptr a -> Ptr a -> CSize -> IO (Ptr ())
```

The retained module is `core/168.json`, SHA-256
`d7da1ff1bc04173421b623a7d8b8f72e76bc9bcc62e6177ed04fcba7c56227d8`.
The archive SHA-256 is
`cfb44fc5576275ed0ca4f14baf072cdc5eae180ae5f557e78c259fe5e422e00f`;
compiler: `ghc-9.14.1`, ABI `inplace`, platform `x86_64-linux`, way
`dynamic-nonprofiling`. Source Text.hs SHA-256:
`4b5263380b09431adbddb9cdbad9dd3447baab6938418798bc5d0d0f866a59d4`.

The binary-buffer integration failure reported five original `memcpy` calls
(three in `hPutBuf2`, two in `$wbufReadNonEmpty`). That fixture's temporary
archive was cleaned. This retained archive used the same driver/helper, and
all four retained candidate archives had byte-identical Text Core; the exact
archive selected by the failed fixture has not been established.

`OriginalMemcpyTest` uses this descriptor with explicitly synthetic callers to
test the leaf in both backends. It does not establish execution of the original
`hPutBuf`/`hGetBuf` closure; the ordinary binary-buffer integration test supplies
that separate gate.
