{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdInt32X4ByteArray where

import GHC.Exts

-- Initial feasibility probe only. Offsets are checked by the native driver:
-- vectorUnitCase accepts 0..3 and scalarUnitCase accepts 0..12 in 64 bytes.
-- All storage is initialized. No mutable operation follows unsafe freezing.
-- No vector crosses a function, closure or public aggregate boundary.

vectorUnitCase :: Int# -> Int# -> Int#
vectorUnitCase offset seed = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, a #) ->
  case setByteArray# a 0# 64# 0# s1 of { s2 ->
  case packInt32X4# (# intToInt32# seed, intToInt32# (seed +# 17#), intToInt32# (seed *# 3# -# 29#), intToInt32# (seed `xorI#` 0x55aa55aa#) #) of { value ->
  case writeInt32X4Array# a offset value s2 of { s3 ->
  case readInt32X4Array# a offset s3 of { (# s4, before #) ->
  case writeInt32Array# a (offset *# 4# +# 1#) (intToInt32# (seed `xorI#` 0x80000000#)) s4 of { s5 ->
  case readInt32X4Array# a offset s5 of { (# s6, after #) ->
  case unsafeFreezeByteArray# a s6 of { (# _, frozen #) ->
  case unpackInt32X4# before of { (# b0, b1, b2, b3 #) ->
  case unpackInt32X4# after of { (# a0, a1, a2, a3 #) ->
  case unpackInt32X4# (indexInt32X4Array# frozen offset) of { (# v0, v1, v2, v3 #) ->
  case unpackInt32X4# (indexInt32ArrayAsInt32X4# frozen (offset *# 4#)) of { (# c0, c1, c2, c3 #) ->
    (int32ToInt# b0 *# 3# +# int32ToInt# b1 *# 5# +# int32ToInt# b2 *# 7# +# int32ToInt# b3 *# 11#) +#
    (int32ToInt# a0 *# 3# +# int32ToInt# a1 *# 5# +# int32ToInt# a2 *# 7# +# int32ToInt# a3 *# 11#) *# 3# +#
    (int32ToInt# v0 *# 3# +# int32ToInt# v1 *# 5# +# int32ToInt# v2 *# 7# +# int32ToInt# v3 *# 11#) *# 5# +#
    (int32ToInt# c0 *# 3# +# int32ToInt# c1 *# 5# +# int32ToInt# c2 *# 7# +# int32ToInt# c3 *# 11#) *# 7#
  } } } } } } } } } } } })

scalarUnitCase :: Int# -> Int# -> Int#
scalarUnitCase offset seed = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, a #) ->
  case setByteArray# a 0# 64# 0# s1 of { s2 ->
  case packInt32X4# (# intToInt32# seed, intToInt32# (seed +# 17#), intToInt32# (seed *# 3# -# 29#), intToInt32# (seed `xorI#` 0x55aa55aa#) #) of { value ->
  case writeInt32ArrayAsInt32X4# a offset value s2 of { s3 ->
  case readInt32ArrayAsInt32X4# a offset s3 of { (# s4, before #) ->
  case writeWord8Array# a (offset *# 4# +# 7#) (wordToWord8# (int2Word# (seed +# 101#))) s4 of { s5 ->
  case readInt32ArrayAsInt32X4# a offset s5 of { (# s6, after #) ->
  case unsafeFreezeByteArray# a s6 of { (# _, frozen #) ->
  case unpackInt32X4# before of { (# b0, b1, b2, b3 #) ->
  case unpackInt32X4# after of { (# a0, a1, a2, a3 #) ->
  case unpackInt32X4# (indexInt32ArrayAsInt32X4# frozen offset) of { (# c0, c1, c2, c3 #) ->
    (int32ToInt# b0 *# 3# +# int32ToInt# b1 *# 5# +# int32ToInt# b2 *# 7# +# int32ToInt# b3 *# 11#) +#
    (int32ToInt# a0 *# 3# +# int32ToInt# a1 *# 5# +# int32ToInt# a2 *# 7# +# int32ToInt# a3 *# 11#) *# 3# +#
    (int32ToInt# c0 *# 3# +# int32ToInt# c1 *# 5# +# int32ToInt# c2 *# 7# +# int32ToInt# c3 *# 11#) *# 5# +#
    (int32ToInt# (indexInt32Array# frozen offset) *# 3# +#
     int32ToInt# (indexInt32Array# frozen (offset +# 1#)) *# 5# +#
     int32ToInt# (indexInt32Array# frozen (offset +# 2#)) *# 7# +#
     int32ToInt# (indexInt32Array# frozen (offset +# 3#)) *# 11#) *# 7#
  } } } } } } } } } } })
