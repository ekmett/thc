{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdInt32X4ByteArray where

import GHC.Exts

-- Scalar-root aliases retained from the original feasibility probe:
-- vectorUnitCase accepts 0..3 and scalarUnitCase accepts 0..12 in 64 bytes.
-- All storage is initialized. No mutable operation follows unsafe freezing.
-- No vector crosses a function, closure or public aggregate boundary.

vectorUnitCase :: Int# -> Int# -> Int#
vectorUnitCase offset seed = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, a #) ->
  case initializeZero64 a s1 of { s2 ->
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
  case initializeZero64 a s1 of { s2 ->
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

-- Initialization uses only scalar Int32 array primitives already supported.
{-# INLINE initialize64 #-}
initialize64 :: MutableByteArray# s -> State# s -> State# s
initialize64 a s0 =
  case writeInt32Array# a 0# (intToInt32# 270544960#) s0 of { s1 ->
  case writeInt32Array# a 1# (intToInt32# 287520071#) s1 of { s2 ->
  case writeInt32Array# a 2# (intToInt32# 304495182#) s2 of { s3 ->
  case writeInt32Array# a 3# (intToInt32# 321470293#) s3 of { s4 ->
  case writeInt32Array# a 4# (intToInt32# 338445404#) s4 of { s5 ->
  case writeInt32Array# a 5# (intToInt32# 355420515#) s5 of { s6 ->
  case writeInt32Array# a 6# (intToInt32# 372395626#) s6 of { s7 ->
  case writeInt32Array# a 7# (intToInt32# 389370737#) s7 of { s8 ->
  case writeInt32Array# a 8# (intToInt32# 406345848#) s8 of { s9 ->
  case writeInt32Array# a 9# (intToInt32# 423320959#) s9 of { s10 ->
  case writeInt32Array# a 10# (intToInt32# 440296070#) s10 of { s11 ->
  case writeInt32Array# a 11# (intToInt32# 457271181#) s11 of { s12 ->
  case writeInt32Array# a 12# (intToInt32# 474246292#) s12 of { s13 ->
  case writeInt32Array# a 13# (intToInt32# 491221403#) s13 of { s14 ->
  case writeInt32Array# a 14# (intToInt32# 508196514#) s14 of { s15 ->
  case writeInt32Array# a 15# (intToInt32# 525171625#) s15 of { s16 ->
  s16 } } } } } } } } } } } } } } } }

{-# INLINE initializeZero64 #-}
initializeZero64 :: MutableByteArray# s -> State# s -> State# s
initializeZero64 a s0 =
  case writeInt32Array# a 0# (intToInt32# 0#) s0 of { s1 ->
  case writeInt32Array# a 1# (intToInt32# 0#) s1 of { s2 ->
  case writeInt32Array# a 2# (intToInt32# 0#) s2 of { s3 ->
  case writeInt32Array# a 3# (intToInt32# 0#) s3 of { s4 ->
  case writeInt32Array# a 4# (intToInt32# 0#) s4 of { s5 ->
  case writeInt32Array# a 5# (intToInt32# 0#) s5 of { s6 ->
  case writeInt32Array# a 6# (intToInt32# 0#) s6 of { s7 ->
  case writeInt32Array# a 7# (intToInt32# 0#) s7 of { s8 ->
  case writeInt32Array# a 8# (intToInt32# 0#) s8 of { s9 ->
  case writeInt32Array# a 9# (intToInt32# 0#) s9 of { s10 ->
  case writeInt32Array# a 10# (intToInt32# 0#) s10 of { s11 ->
  case writeInt32Array# a 11# (intToInt32# 0#) s11 of { s12 ->
  case writeInt32Array# a 12# (intToInt32# 0#) s12 of { s13 ->
  case writeInt32Array# a 13# (intToInt32# 0#) s13 of { s14 ->
  case writeInt32Array# a 14# (intToInt32# 0#) s14 of { s15 ->
  case writeInt32Array# a 15# (intToInt32# 0#) s15 of { s16 ->
  s16 } } } } } } } } } } } } } } } }

{-# OPAQUE vectorIndexWorker #-}
vectorIndexWorker :: ByteArray# -> Int# -> Int#
vectorIndexWorker bytes offset = case unpackInt32X4# (indexInt32X4Array# bytes offset) of
  (# p0, p1, p2, p3 #) -> int32ToInt# p0 *# 3# +# int32ToInt# p1 *# 5# +# int32ToInt# p2 *# 7# +# int32ToInt# p3 *# 11#

{-# OPAQUE vectorReadWorker #-}
vectorReadWorker :: MutableByteArray# s -> Int# -> State# s -> (# State# s, Int# #)
vectorReadWorker bytes offset s0 = case readInt32X4Array# bytes offset s0 of
  (# s1, value #) -> case unpackInt32X4# value of
    (# p0, p1, p2, p3 #) -> (# s1, int32ToInt# p0 *# 3# +# int32ToInt# p1 *# 5# +# int32ToInt# p2 *# 7# +# int32ToInt# p3 *# 11# #)

{-# OPAQUE vectorWriteWorker #-}
vectorWriteWorker :: MutableByteArray# s -> Int# -> Int# -> Int# -> Int# -> Int# -> State# s -> (# State# s, Int# #)
vectorWriteWorker bytes offset x0 x1 x2 x3 s0 =
  case packInt32X4# (# intToInt32# x0, intToInt32# x1, intToInt32# x2, intToInt32# x3 #) of
    value -> case writeInt32X4Array# bytes offset value s0 of
      s1 -> (# s1, int32ToInt# (intToInt32# x0) *# 3# +# int32ToInt# (intToInt32# x1) *# 5# +# int32ToInt# (intToInt32# x2) *# 7# +# int32ToInt# (intToInt32# x3) *# 11# #)

-- The returned reference aliases the input storage. Freezing is the FINAL
-- effect; native and host callers must never mutate this array afterwards.
{-# OPAQUE vectorStoreGraph #-}
vectorStoreGraph :: MutableByteArray# RealWorld -> Int# -> Int# -> Int# -> Int# -> Int# -> State# RealWorld -> ByteArray#
vectorStoreGraph bytes offset x0 x1 x2 x3 s0 =
  case packInt32X4# (# intToInt32# x0, intToInt32# x1, intToInt32# x2, intToInt32# x3 #) of
    value -> case writeInt32X4Array# bytes offset value s0 of
      s1 -> case unsafeFreezeByteArray# bytes s1 of
        (# _, frozen #) -> frozen

vectorIndexCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
vectorIndexCase offset x0 x1 x2 x3 = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeFour bytes (offset *# 4#) x0 x1 x2 x3 s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of { (# _, frozen #) ->
    vectorIndexWorker frozen offset
  } } } })

vectorReadCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
vectorReadCase offset x0 x1 x2 x3 = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeFour bytes (offset *# 4#) x0 x1 x2 x3 s2 of { s3 ->
  case vectorReadWorker bytes offset s3 of { (# _, answer #) -> answer
  } } } })

vectorWriteCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int#
vectorWriteCase offset x0 x1 x2 x3 byte = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case vectorWriteWorker bytes offset x0 x1 x2 x3 s2 of { (# s3, score #) ->
  case unsafeFreezeByteArray# bytes s3 of { (# _, frozen #) ->
    score *# 257# +# word2Int# (word8ToWord# (indexWord8Array# frozen byte))
  } } } })

vectorStoreCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int#
vectorStoreCase offset x0 x1 x2 x3 byte = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case vectorStoreGraph bytes offset x0 x1 x2 x3 s2 of { frozen ->
    (int32ToInt# (intToInt32# x0) *# 3# +# int32ToInt# (intToInt32# x1) *# 5# +# int32ToInt# (intToInt32# x2) *# 7# +# int32ToInt# (intToInt32# x3) *# 11#) *# 257# +# word2Int# (word8ToWord# (indexWord8Array# frozen byte))
  } } })

{-# OPAQUE scalarIndexWorker #-}
scalarIndexWorker :: ByteArray# -> Int# -> Int#
scalarIndexWorker bytes offset = case unpackInt32X4# (indexInt32ArrayAsInt32X4# bytes offset) of
  (# p0, p1, p2, p3 #) -> int32ToInt# p0 *# 3# +# int32ToInt# p1 *# 5# +# int32ToInt# p2 *# 7# +# int32ToInt# p3 *# 11#

{-# OPAQUE scalarReadWorker #-}
scalarReadWorker :: MutableByteArray# s -> Int# -> State# s -> (# State# s, Int# #)
scalarReadWorker bytes offset s0 = case readInt32ArrayAsInt32X4# bytes offset s0 of
  (# s1, value #) -> case unpackInt32X4# value of
    (# p0, p1, p2, p3 #) -> (# s1, int32ToInt# p0 *# 3# +# int32ToInt# p1 *# 5# +# int32ToInt# p2 *# 7# +# int32ToInt# p3 *# 11# #)

{-# OPAQUE scalarWriteWorker #-}
scalarWriteWorker :: MutableByteArray# s -> Int# -> Int# -> Int# -> Int# -> Int# -> State# s -> (# State# s, Int# #)
scalarWriteWorker bytes offset x0 x1 x2 x3 s0 =
  case packInt32X4# (# intToInt32# x0, intToInt32# x1, intToInt32# x2, intToInt32# x3 #) of
    value -> case writeInt32ArrayAsInt32X4# bytes offset value s0 of
      s1 -> (# s1, int32ToInt# (intToInt32# x0) *# 3# +# int32ToInt# (intToInt32# x1) *# 5# +# int32ToInt# (intToInt32# x2) *# 7# +# int32ToInt# (intToInt32# x3) *# 11# #)

-- The returned reference aliases the input storage. Freezing is the FINAL
-- effect; native and host callers must never mutate this array afterwards.
{-# OPAQUE scalarStoreGraph #-}
scalarStoreGraph :: MutableByteArray# RealWorld -> Int# -> Int# -> Int# -> Int# -> Int# -> State# RealWorld -> ByteArray#
scalarStoreGraph bytes offset x0 x1 x2 x3 s0 =
  case packInt32X4# (# intToInt32# x0, intToInt32# x1, intToInt32# x2, intToInt32# x3 #) of
    value -> case writeInt32ArrayAsInt32X4# bytes offset value s0 of
      s1 -> case unsafeFreezeByteArray# bytes s1 of
        (# _, frozen #) -> frozen

scalarIndexCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
scalarIndexCase offset x0 x1 x2 x3 = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeFour bytes offset x0 x1 x2 x3 s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of { (# _, frozen #) ->
    scalarIndexWorker frozen offset
  } } } })

scalarReadCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
scalarReadCase offset x0 x1 x2 x3 = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeFour bytes offset x0 x1 x2 x3 s2 of { s3 ->
  case scalarReadWorker bytes offset s3 of { (# _, answer #) -> answer
  } } } })

scalarWriteCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int#
scalarWriteCase offset x0 x1 x2 x3 byte = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case scalarWriteWorker bytes offset x0 x1 x2 x3 s2 of { (# s3, score #) ->
  case unsafeFreezeByteArray# bytes s3 of { (# _, frozen #) ->
    score *# 257# +# word2Int# (word8ToWord# (indexWord8Array# frozen byte))
  } } } })

scalarStoreCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int#
scalarStoreCase offset x0 x1 x2 x3 byte = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case scalarStoreGraph bytes offset x0 x1 x2 x3 s2 of { frozen ->
    (int32ToInt# (intToInt32# x0) *# 3# +# int32ToInt# (intToInt32# x1) *# 5# +# int32ToInt# (intToInt32# x2) *# 7# +# int32ToInt# (intToInt32# x3) *# 11#) *# 257# +# word2Int# (word8ToWord# (indexWord8Array# frozen byte))
  } } })

{-# INLINE writeFour #-}
writeFour :: MutableByteArray# s -> Int# -> Int# -> Int# -> Int# -> Int# -> State# s -> State# s
writeFour bytes offset x0 x1 x2 x3 s0 =
  case writeInt32Array# bytes offset (intToInt32# x0) s0 of { s1 ->
  case writeInt32Array# bytes (offset +# 1#) (intToInt32# x1) s1 of { s2 ->
  case writeInt32Array# bytes (offset +# 2#) (intToInt32# x2) s2 of { s3 ->
  case writeInt32Array# bytes (offset +# 3#) (intToInt32# x3) s3 of { s4 -> s4
  } } } }

-- Genuine negative: memory support does not establish a vector formal ABI.
{-# OPAQUE vectorArgument #-}
vectorArgument :: Int32X4# -> Int#
vectorArgument value = case unpackInt32X4# value of
  (# p0, p1, p2, p3 #) -> int32ToInt# p0 *# 3# +# int32ToInt# p1 *# 5# +# int32ToInt# p2 *# 7# +# int32ToInt# p3 *# 11#

-- These compile natively but remain outside THC's immediate-local-read rule.
{-# OPAQUE readTupleEscape #-}
readTupleEscape :: MutableByteArray# s -> Int# -> State# s -> (# State# s, Int32X4# #)
readTupleEscape bytes offset s0 = readInt32X4Array# bytes offset s0

{-# OPAQUE readVectorEscape #-}
readVectorEscape :: MutableByteArray# s -> Int# -> State# s -> Int32X4#
readVectorEscape bytes offset s0 = case readInt32X4Array# bytes offset s0 of
  (# _, value #) -> value
