{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Int16ArrayAudit where

import GHC.Exts

-- noinline erasure deliberately drops type certificates on retained operands.
-- Narrow literal syntax still proves the exact rep, independently of metadata.
{-# OPAQUE literalInt16Worker #-}
literalInt16Worker :: Int# -> Int16# -> Int#
literalInt16Worker x value = x +# int16ToInt# value

{-# OPAQUE literalWord16Worker #-}
literalWord16Worker :: Int# -> Word16# -> Int#
literalWord16Worker x value = x +# word2Int# (word16ToWord# value)

noinlineInt16Literal :: Int# -> Int#
noinlineInt16Literal x = noinline literalInt16Worker x (intToInt16# (-32768#))

noinlineWord16Literal :: Int# -> Int#
noinlineWord16Literal x = noinline literalWord16Worker x (wordToWord16# 65535##)

-- Typed indices are 2-byte elements; Word8 indices are bytes. The byte writes
-- straddle element offsets 1/2, and subsequent typed reads share that storage.
-- Explicit narrowing uses the low 16 bits; the Int16 path sign-extends them.
aliasInt16Bytes :: Int# -> Int#
aliasInt16Bytes seed = runRW# (\s0 ->
  case newByteArray# 4# s0 of { (# s1, a #) ->
  case writeInt16Array# a 0# (intToInt16# seed) s1 of { s2 ->
  case writeInt16Array# a 1# (intToInt16# (seed `xorI#` 0x55aa#)) s2 of { s3 ->
  case readInt16Array# a 0# s3 of { (# s4, before #) ->
  case writeWord8Array# a 1# (wordToWord8# (int2Word# (seed +# 101#))) s4 of { s5 ->
  case writeWord8Array# a 2# (wordToWord8# (int2Word# (seed +# 37#))) s5 of { s6 ->
  case readInt16Array# a 0# s6 of { (# s7, first #) ->
  case readInt16Array# a 1# s7 of { (# s8, second #) ->
  case unsafeFreezeByteArray# a s8 of { (# _, frozen #) ->
    int16ToInt# before *# 3# +# int16ToInt# first *# 5# +# int16ToInt# second *# 7# +#
    int16ToInt# (indexInt16Array# frozen 0#) *# 11# +#
    int16ToInt# (indexInt16Array# frozen 1#) *# 13# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 0#)) *# 17# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 1#)) *# 19# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 2#)) *# 23# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 3#)) *# 29#
  } } } } } } } } })

-- Identical raw storage and writes, but Word16 reads/indexing zero-extend.
aliasWord16Bytes :: Int# -> Int#
aliasWord16Bytes seed = runRW# (\s0 ->
  case newByteArray# 4# s0 of { (# s1, a #) ->
  case writeWord16Array# a 0# (wordToWord16# (int2Word# seed)) s1 of { s2 ->
  case writeWord16Array# a 1# (wordToWord16# (int2Word# (seed `xorI#` 0x55aa#))) s2 of { s3 ->
  case readWord16Array# a 0# s3 of { (# s4, before #) ->
  case writeWord8Array# a 1# (wordToWord8# (int2Word# (seed +# 101#))) s4 of { s5 ->
  case writeWord8Array# a 2# (wordToWord8# (int2Word# (seed +# 37#))) s5 of { s6 ->
  case readWord16Array# a 0# s6 of { (# s7, first #) ->
  case readWord16Array# a 1# s7 of { (# s8, second #) ->
  case unsafeFreezeByteArray# a s8 of { (# _, frozen #) ->
    word2Int# (word16ToWord# before) *# 3# +#
    word2Int# (word16ToWord# first) *# 5# +# word2Int# (word16ToWord# second) *# 7# +#
    word2Int# (word16ToWord# (indexWord16Array# frozen 0#)) *# 11# +#
    word2Int# (word16ToWord# (indexWord16Array# frozen 1#)) *# 13# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 0#)) *# 17# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 1#)) *# 19# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 2#)) *# 23# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 3#)) *# 29#
  } } } } } } } } })
