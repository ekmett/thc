{-# LANGUAGE MagicHash, UnboxedTuples #-}
module FloatArrayAudit where

import GHC.Exts

-- Retain actual floating memory operations and the State#/Float# result tuple,
-- without adding a floating argument ABI or fencing public library examples.
{-# OPAQUE readFloatSlot #-}
readFloatSlot :: MutableByteArray# s -> State# s -> (# State# s, Float# #)
readFloatSlot a s = readFloatArray# a 0# s

{-# OPAQUE indexFloatSlot #-}
indexFloatSlot :: ByteArray# -> Float#
indexFloatSlot a = indexFloatArray# a 0#

-- A signed64 host seed supplies low32 raw bits; the result zero-extends the
-- moved Word32 bits. No FP arithmetic occurs. Exact movement excludes sNaNs.
moveFloatBits :: Int# -> Int#
moveFloatBits bits = runRW# (\s0 ->
  case newByteArray# 4# s0 of { (# s1, a #) ->
  case writeWord32Array# a 0# (wordToWord32# (int2Word# bits)) s1 of { s2 ->
  case readFloatSlot a s2 of { (# s3, value #) ->
  case newByteArray# 4# s3 of { (# s4, b #) ->
  case writeFloatArray# b 0# value s4 of { s5 ->
  case unsafeFreezeByteArray# b s5 of { (# _, frozen #) ->
    word2Int# (word32ToWord# (indexWord32Array# frozen 0#))
  } } } } } })

indexFloatBits :: Int# -> Int#
indexFloatBits bits = runRW# (\s0 ->
  case newByteArray# 4# s0 of { (# s1, a #) ->
  case writeWord32Array# a 0# (wordToWord32# (int2Word# bits)) s1 of { s2 ->
  case unsafeFreezeByteArray# a s2 of { (# s3, frozen #) ->
  case indexFloatSlot frozen of { value ->
  case newByteArray# 4# s3 of { (# s4, b #) ->
  case writeFloatArray# b 0# value s4 of { s5 ->
  case unsafeFreezeByteArray# b s5 of { (# _, moved #) ->
    word2Int# (word32ToWord# (indexWord32Array# moved 0#))
  } } } } } } })
