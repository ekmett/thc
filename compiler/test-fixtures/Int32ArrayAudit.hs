{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Int32ArrayAudit where

import GHC.Exts

-- noinline erasure deliberately drops type certificates on retained operands.
-- Narrow literal syntax still proves the exact rep, independently of metadata.
{-# OPAQUE literalInt32Worker #-}
literalInt32Worker :: Int# -> Int32# -> Int#
literalInt32Worker x value = x +# int32ToInt# value

{-# OPAQUE literalWord32Worker #-}
literalWord32Worker :: Int# -> Word32# -> Int#
literalWord32Worker x value = x +# word2Int# (word32ToWord# value)

noinlineInt32Literal :: Int# -> Int#
noinlineInt32Literal x = noinline literalInt32Worker x (intToInt32# (-2147483648#))

noinlineWord32Literal :: Int# -> Int#
noinlineWord32Literal x = noinline literalWord32Worker x (wordToWord32# 4294967295##)

-- Typed indices are 4-byte elements; Word8 indices are bytes. The byte writes
-- straddle element offsets 3/4, and subsequent typed reads share that storage.
-- Explicit narrowing uses the low 32 bits; the Int32 path sign-extends them.
aliasInt32Bytes :: Int# -> Int#
aliasInt32Bytes seed = runRW# (\s0 ->
  case newByteArray# 8# s0 of { (# s1, a #) ->
  case writeInt32Array# a 0# (intToInt32# seed) s1 of { s2 ->
  case writeInt32Array# a 1# (intToInt32# (seed `xorI#` 0x55aa55aa#)) s2 of { s3 ->
  case readInt32Array# a 0# s3 of { (# s4, before #) ->
  case writeWord8Array# a 3# (wordToWord8# (int2Word# (seed +# 101#))) s4 of { s5 ->
  case writeWord8Array# a 4# (wordToWord8# (int2Word# (seed +# 37#))) s5 of { s6 ->
  case readInt32Array# a 0# s6 of { (# s7, first #) ->
  case readInt32Array# a 1# s7 of { (# s8, second #) ->
  case unsafeFreezeByteArray# a s8 of { (# _, frozen #) ->
    int32ToInt# before *# 3# +# int32ToInt# first *# 5# +# int32ToInt# second *# 7# +#
    int32ToInt# (indexInt32Array# frozen 0#) *# 11# +#
    int32ToInt# (indexInt32Array# frozen 1#) *# 13# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 0#)) *# 17# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 3#)) *# 19# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 4#)) *# 23# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 7#)) *# 29#
  } } } } } } } } })

-- Identical raw storage and writes, but Word32 reads/indexing zero-extend.
aliasWord32Bytes :: Int# -> Int#
aliasWord32Bytes seed = runRW# (\s0 ->
  case newByteArray# 8# s0 of { (# s1, a #) ->
  case writeWord32Array# a 0# (wordToWord32# (int2Word# seed)) s1 of { s2 ->
  case writeWord32Array# a 1# (wordToWord32# (int2Word# (seed `xorI#` 0x55aa55aa#))) s2 of { s3 ->
  case readWord32Array# a 0# s3 of { (# s4, before #) ->
  case writeWord8Array# a 3# (wordToWord8# (int2Word# (seed +# 101#))) s4 of { s5 ->
  case writeWord8Array# a 4# (wordToWord8# (int2Word# (seed +# 37#))) s5 of { s6 ->
  case readWord32Array# a 0# s6 of { (# s7, first #) ->
  case readWord32Array# a 1# s7 of { (# s8, second #) ->
  case unsafeFreezeByteArray# a s8 of { (# _, frozen #) ->
    word2Int# (word32ToWord# before) *# 3# +#
    word2Int# (word32ToWord# first) *# 5# +# word2Int# (word32ToWord# second) *# 7# +#
    word2Int# (word32ToWord# (indexWord32Array# frozen 0#)) *# 11# +#
    word2Int# (word32ToWord# (indexWord32Array# frozen 1#)) *# 13# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 0#)) *# 17# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 3#)) *# 19# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 4#)) *# 23# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 7#)) *# 29#
  } } } } } } } } })
