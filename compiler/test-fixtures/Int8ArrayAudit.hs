{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Int8ArrayAudit where
import GHC.Exts

-- Signed and unsigned byte views share storage; no alignment or byte-order
-- reinterpretation is needed. Observe the mutable read before overwriting it.
aliasBytes :: Int# -> Int#
aliasBytes seed = runRW# (\s0 ->
  case newByteArray# 2# s0 of { (# s1, a #) ->
  case writeInt8Array# a 0# (intToInt8# seed) s1 of { s2 ->
  case writeWord8Array# a 1# (wordToWord8# (int2Word# (seed `xorI#` 0x55#))) s2 of { s3 ->
  case readInt8Array# a 0# s3 of { (# s4, before #) ->
  case readWord8Array# a 0# s4 of { (# s5, beforeU #) ->
  case writeWord8Array# a 0# (wordToWord8# (int2Word# (seed +# 101#))) s5 of { s6 ->
  case writeInt8Array# a 1# (intToInt8# (seed +# 37#)) s6 of { s7 ->
  case readInt8Array# a 0# s7 of { (# s8, first #) ->
  case readWord8Array# a 1# s8 of { (# s9, second #) ->
  case unsafeFreezeByteArray# a s9 of { (# _, frozen #) ->
    int8ToInt# before *# 3# +# word2Int# (word8ToWord# beforeU) *# 5# +#
    int8ToInt# first *# 7# +# word2Int# (word8ToWord# second) *# 11# +#
    int8ToInt# (indexInt8Array# frozen 0#) *# 13# +#
    int8ToInt# (indexInt8Array# frozen 1#) *# 17# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 0#)) *# 19# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 1#)) *# 23#
  } } } } } } } } } })

emptyBytes :: Int# -> Int#
emptyBytes seed = runRW# (\s0 ->
  case newByteArray# 0# s0 of { (# s1, a #) ->
  case unsafeFreezeByteArray# a s1 of { (# _, frozen #) ->
    seed +# sizeofByteArray# frozen
  } })

-- The THC host observes these raw narrow results before any widening operation.
-- Native GHC widens them only in its driver, so conversion cannot hide a
-- noncanonical signed/unsigned Long carrier in either runtime backend.
rawSignedRead :: Int# -> Int8#
rawSignedRead seed = runRW# (\s0 ->
  case newByteArray# 1# s0 of { (# s1, a #) ->
  case writeInt8Array# a 0# (intToInt8# seed) s1 of { s2 ->
  case readInt8Array# a 0# s2 of { (# _, value #) -> value
  } } })

rawUnsignedRead :: Int# -> Word8#
rawUnsignedRead seed = runRW# (\s0 ->
  case newByteArray# 1# s0 of { (# s1, a #) ->
  case writeInt8Array# a 0# (intToInt8# seed) s1 of { s2 ->
  case readWord8Array# a 0# s2 of { (# _, value #) -> value
  } } })

rawSignedIndex :: Int# -> Int8#
rawSignedIndex seed = runRW# (\s0 ->
  case newByteArray# 1# s0 of { (# s1, a #) ->
  case writeInt8Array# a 0# (intToInt8# seed) s1 of { s2 ->
  case unsafeFreezeByteArray# a s2 of { (# _, frozen #) ->
    indexInt8Array# frozen 0#
  } } })
