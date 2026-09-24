-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdWord32X4ByteArray where

import GHC.Exts

-- Unsigned scalar-root aliases: narrow modulo 2^32, then zero-extend.
-- vectorUnitCase accepts 0..3 and scalarUnitCase accepts 0..12 in 64 bytes.
-- All storage is initialized. No mutable operation follows unsafe freezing.
-- No vector crosses a function, closure or public aggregate boundary.

vectorUnitCase :: Int# -> Int# -> Int#
vectorUnitCase offset seed = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, a #) ->
  case initializeZero64 a s1 of { s2 ->
  case packWord32X4# (# wordToWord32# (int2Word# seed), wordToWord32# (int2Word# (seed +# 17#)), wordToWord32# (int2Word# (seed *# 3# -# 29#)), wordToWord32# (int2Word# (seed `xorI#` 0x55aa55aa#)) #) of { value ->
  case writeWord32X4Array# a offset value s2 of { s3 ->
  case readWord32X4Array# a offset s3 of { (# s4, before #) ->
  case writeWord32Array# a (offset *# 4# +# 1#) (wordToWord32# (int2Word# (seed `xorI#` 0x80000000#))) s4 of { s5 ->
  case readWord32X4Array# a offset s5 of { (# s6, after #) ->
  case unsafeFreezeByteArray# a s6 of { (# _, frozen #) ->
  case unpackWord32X4# before of { (# b0, b1, b2, b3 #) ->
  case unpackWord32X4# after of { (# a0, a1, a2, a3 #) ->
  case unpackWord32X4# (indexWord32X4Array# frozen offset) of { (# v0, v1, v2, v3 #) ->
  case unpackWord32X4# (indexWord32ArrayAsWord32X4# frozen (offset *# 4#)) of { (# c0, c1, c2, c3 #) ->
    (word2Int# (word32ToWord# b0) *# 3# +# word2Int# (word32ToWord# b1) *# 5# +# word2Int# (word32ToWord# b2) *# 7# +# word2Int# (word32ToWord# b3) *# 11#) +#
    (word2Int# (word32ToWord# a0) *# 3# +# word2Int# (word32ToWord# a1) *# 5# +# word2Int# (word32ToWord# a2) *# 7# +# word2Int# (word32ToWord# a3) *# 11#) *# 3# +#
    (word2Int# (word32ToWord# v0) *# 3# +# word2Int# (word32ToWord# v1) *# 5# +# word2Int# (word32ToWord# v2) *# 7# +# word2Int# (word32ToWord# v3) *# 11#) *# 5# +#
    (word2Int# (word32ToWord# c0) *# 3# +# word2Int# (word32ToWord# c1) *# 5# +# word2Int# (word32ToWord# c2) *# 7# +# word2Int# (word32ToWord# c3) *# 11#) *# 7#
  } } } } } } } } } } } })

scalarUnitCase :: Int# -> Int# -> Int#
scalarUnitCase offset seed = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, a #) ->
  case initializeZero64 a s1 of { s2 ->
  case packWord32X4# (# wordToWord32# (int2Word# seed), wordToWord32# (int2Word# (seed +# 17#)), wordToWord32# (int2Word# (seed *# 3# -# 29#)), wordToWord32# (int2Word# (seed `xorI#` 0x55aa55aa#)) #) of { value ->
  case writeWord32ArrayAsWord32X4# a offset value s2 of { s3 ->
  case readWord32ArrayAsWord32X4# a offset s3 of { (# s4, before #) ->
  case writeWord8Array# a (offset *# 4# +# 7#) (wordToWord8# (int2Word# (seed +# 101#))) s4 of { s5 ->
  case readWord32ArrayAsWord32X4# a offset s5 of { (# s6, after #) ->
  case unsafeFreezeByteArray# a s6 of { (# _, frozen #) ->
  case unpackWord32X4# before of { (# b0, b1, b2, b3 #) ->
  case unpackWord32X4# after of { (# a0, a1, a2, a3 #) ->
  case unpackWord32X4# (indexWord32ArrayAsWord32X4# frozen offset) of { (# c0, c1, c2, c3 #) ->
    (word2Int# (word32ToWord# b0) *# 3# +# word2Int# (word32ToWord# b1) *# 5# +# word2Int# (word32ToWord# b2) *# 7# +# word2Int# (word32ToWord# b3) *# 11#) +#
    (word2Int# (word32ToWord# a0) *# 3# +# word2Int# (word32ToWord# a1) *# 5# +# word2Int# (word32ToWord# a2) *# 7# +# word2Int# (word32ToWord# a3) *# 11#) *# 3# +#
    (word2Int# (word32ToWord# c0) *# 3# +# word2Int# (word32ToWord# c1) *# 5# +# word2Int# (word32ToWord# c2) *# 7# +# word2Int# (word32ToWord# c3) *# 11#) *# 5# +#
    (word2Int# (word32ToWord# (indexWord32Array# frozen offset)) *# 3# +#
     word2Int# (word32ToWord# (indexWord32Array# frozen (offset +# 1#))) *# 5# +#
     word2Int# (word32ToWord# (indexWord32Array# frozen (offset +# 2#))) *# 7# +#
     word2Int# (word32ToWord# (indexWord32Array# frozen (offset +# 3#))) *# 11#) *# 7#
  } } } } } } } } } } })

-- Initialization uses only scalar Word32 array primitives already supported.
{-# INLINE initialize64 #-}
initialize64 :: MutableByteArray# s -> State# s -> State# s
initialize64 a s0 =
  case writeWord32Array# a 0# (wordToWord32# (int2Word# 2309737967#)) s0 of { s1 ->
  case writeWord32Array# a 1# (wordToWord32# (int2Word# 2326713078#)) s1 of { s2 ->
  case writeWord32Array# a 2# (wordToWord32# (int2Word# 2343688189#)) s2 of { s3 ->
  case writeWord32Array# a 3# (wordToWord32# (int2Word# 2360663300#)) s3 of { s4 ->
  case writeWord32Array# a 4# (wordToWord32# (int2Word# 2377638411#)) s4 of { s5 ->
  case writeWord32Array# a 5# (wordToWord32# (int2Word# 2394613522#)) s5 of { s6 ->
  case writeWord32Array# a 6# (wordToWord32# (int2Word# 2411588633#)) s6 of { s7 ->
  case writeWord32Array# a 7# (wordToWord32# (int2Word# 2428563744#)) s7 of { s8 ->
  case writeWord32Array# a 8# (wordToWord32# (int2Word# 2445538855#)) s8 of { s9 ->
  case writeWord32Array# a 9# (wordToWord32# (int2Word# 2462513966#)) s9 of { s10 ->
  case writeWord32Array# a 10# (wordToWord32# (int2Word# 2479489077#)) s10 of { s11 ->
  case writeWord32Array# a 11# (wordToWord32# (int2Word# 2496464188#)) s11 of { s12 ->
  case writeWord32Array# a 12# (wordToWord32# (int2Word# 2513439299#)) s12 of { s13 ->
  case writeWord32Array# a 13# (wordToWord32# (int2Word# 2530414410#)) s13 of { s14 ->
  case writeWord32Array# a 14# (wordToWord32# (int2Word# 2547389521#)) s14 of { s15 ->
  case writeWord32Array# a 15# (wordToWord32# (int2Word# 2564364632#)) s15 of { s16 ->
  s16 } } } } } } } } } } } } } } } }

{-# INLINE initializeZero64 #-}
initializeZero64 :: MutableByteArray# s -> State# s -> State# s
initializeZero64 a s0 =
  case writeWord32Array# a 0# (wordToWord32# (int2Word# 0#)) s0 of { s1 ->
  case writeWord32Array# a 1# (wordToWord32# (int2Word# 0#)) s1 of { s2 ->
  case writeWord32Array# a 2# (wordToWord32# (int2Word# 0#)) s2 of { s3 ->
  case writeWord32Array# a 3# (wordToWord32# (int2Word# 0#)) s3 of { s4 ->
  case writeWord32Array# a 4# (wordToWord32# (int2Word# 0#)) s4 of { s5 ->
  case writeWord32Array# a 5# (wordToWord32# (int2Word# 0#)) s5 of { s6 ->
  case writeWord32Array# a 6# (wordToWord32# (int2Word# 0#)) s6 of { s7 ->
  case writeWord32Array# a 7# (wordToWord32# (int2Word# 0#)) s7 of { s8 ->
  case writeWord32Array# a 8# (wordToWord32# (int2Word# 0#)) s8 of { s9 ->
  case writeWord32Array# a 9# (wordToWord32# (int2Word# 0#)) s9 of { s10 ->
  case writeWord32Array# a 10# (wordToWord32# (int2Word# 0#)) s10 of { s11 ->
  case writeWord32Array# a 11# (wordToWord32# (int2Word# 0#)) s11 of { s12 ->
  case writeWord32Array# a 12# (wordToWord32# (int2Word# 0#)) s12 of { s13 ->
  case writeWord32Array# a 13# (wordToWord32# (int2Word# 0#)) s13 of { s14 ->
  case writeWord32Array# a 14# (wordToWord32# (int2Word# 0#)) s14 of { s15 ->
  case writeWord32Array# a 15# (wordToWord32# (int2Word# 0#)) s15 of { s16 ->
  s16 } } } } } } } } } } } } } } } }

{-# OPAQUE vectorIndexWorker #-}
vectorIndexWorker :: ByteArray# -> Int# -> Int#
vectorIndexWorker bytes offset = case unpackWord32X4# (indexWord32X4Array# bytes offset) of
  (# p0, p1, p2, p3 #) -> word2Int# (word32ToWord# p0) *# 3# +# word2Int# (word32ToWord# p1) *# 5# +# word2Int# (word32ToWord# p2) *# 7# +# word2Int# (word32ToWord# p3) *# 11#

{-# OPAQUE vectorReadWorker #-}
vectorReadWorker :: MutableByteArray# s -> Int# -> State# s -> (# State# s, Int# #)
vectorReadWorker bytes offset s0 = case readWord32X4Array# bytes offset s0 of
  (# s1, value #) -> case unpackWord32X4# value of
    (# p0, p1, p2, p3 #) -> (# s1, word2Int# (word32ToWord# p0) *# 3# +# word2Int# (word32ToWord# p1) *# 5# +# word2Int# (word32ToWord# p2) *# 7# +# word2Int# (word32ToWord# p3) *# 11# #)

{-# OPAQUE vectorWriteWorker #-}
vectorWriteWorker :: MutableByteArray# s -> Int# -> Int# -> Int# -> Int# -> Int# -> State# s -> (# State# s, Int# #)
vectorWriteWorker bytes offset x0 x1 x2 x3 s0 =
  case packWord32X4# (# wordToWord32# (int2Word# x0), wordToWord32# (int2Word# x1), wordToWord32# (int2Word# x2), wordToWord32# (int2Word# x3) #) of
    value -> case writeWord32X4Array# bytes offset value s0 of
      s1 -> (# s1, word2Int# (word32ToWord# (wordToWord32# (int2Word# x0))) *# 3# +# word2Int# (word32ToWord# (wordToWord32# (int2Word# x1))) *# 5# +# word2Int# (word32ToWord# (wordToWord32# (int2Word# x2))) *# 7# +# word2Int# (word32ToWord# (wordToWord32# (int2Word# x3))) *# 11# #)

-- The returned reference aliases the input storage. Freezing is the FINAL
-- effect; native and host callers must never mutate this array afterwards.
{-# OPAQUE vectorStoreGraph #-}
vectorStoreGraph :: MutableByteArray# RealWorld -> Int# -> Int# -> Int# -> Int# -> Int# -> State# RealWorld -> ByteArray#
vectorStoreGraph bytes offset x0 x1 x2 x3 s0 =
  case packWord32X4# (# wordToWord32# (int2Word# x0), wordToWord32# (int2Word# x1), wordToWord32# (int2Word# x2), wordToWord32# (int2Word# x3) #) of
    value -> case writeWord32X4Array# bytes offset value s0 of
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
    (word2Int# (word32ToWord# (wordToWord32# (int2Word# x0))) *# 3# +# word2Int# (word32ToWord# (wordToWord32# (int2Word# x1))) *# 5# +# word2Int# (word32ToWord# (wordToWord32# (int2Word# x2))) *# 7# +# word2Int# (word32ToWord# (wordToWord32# (int2Word# x3))) *# 11#) *# 257# +# word2Int# (word8ToWord# (indexWord8Array# frozen byte))
  } } })

{-# OPAQUE scalarIndexWorker #-}
scalarIndexWorker :: ByteArray# -> Int# -> Int#
scalarIndexWorker bytes offset = case unpackWord32X4# (indexWord32ArrayAsWord32X4# bytes offset) of
  (# p0, p1, p2, p3 #) -> word2Int# (word32ToWord# p0) *# 3# +# word2Int# (word32ToWord# p1) *# 5# +# word2Int# (word32ToWord# p2) *# 7# +# word2Int# (word32ToWord# p3) *# 11#

{-# OPAQUE scalarReadWorker #-}
scalarReadWorker :: MutableByteArray# s -> Int# -> State# s -> (# State# s, Int# #)
scalarReadWorker bytes offset s0 = case readWord32ArrayAsWord32X4# bytes offset s0 of
  (# s1, value #) -> case unpackWord32X4# value of
    (# p0, p1, p2, p3 #) -> (# s1, word2Int# (word32ToWord# p0) *# 3# +# word2Int# (word32ToWord# p1) *# 5# +# word2Int# (word32ToWord# p2) *# 7# +# word2Int# (word32ToWord# p3) *# 11# #)

{-# OPAQUE scalarWriteWorker #-}
scalarWriteWorker :: MutableByteArray# s -> Int# -> Int# -> Int# -> Int# -> Int# -> State# s -> (# State# s, Int# #)
scalarWriteWorker bytes offset x0 x1 x2 x3 s0 =
  case packWord32X4# (# wordToWord32# (int2Word# x0), wordToWord32# (int2Word# x1), wordToWord32# (int2Word# x2), wordToWord32# (int2Word# x3) #) of
    value -> case writeWord32ArrayAsWord32X4# bytes offset value s0 of
      s1 -> (# s1, word2Int# (word32ToWord# (wordToWord32# (int2Word# x0))) *# 3# +# word2Int# (word32ToWord# (wordToWord32# (int2Word# x1))) *# 5# +# word2Int# (word32ToWord# (wordToWord32# (int2Word# x2))) *# 7# +# word2Int# (word32ToWord# (wordToWord32# (int2Word# x3))) *# 11# #)

-- The returned reference aliases the input storage. Freezing is the FINAL
-- effect; native and host callers must never mutate this array afterwards.
{-# OPAQUE scalarStoreGraph #-}
scalarStoreGraph :: MutableByteArray# RealWorld -> Int# -> Int# -> Int# -> Int# -> Int# -> State# RealWorld -> ByteArray#
scalarStoreGraph bytes offset x0 x1 x2 x3 s0 =
  case packWord32X4# (# wordToWord32# (int2Word# x0), wordToWord32# (int2Word# x1), wordToWord32# (int2Word# x2), wordToWord32# (int2Word# x3) #) of
    value -> case writeWord32ArrayAsWord32X4# bytes offset value s0 of
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
    (word2Int# (word32ToWord# (wordToWord32# (int2Word# x0))) *# 3# +# word2Int# (word32ToWord# (wordToWord32# (int2Word# x1))) *# 5# +# word2Int# (word32ToWord# (wordToWord32# (int2Word# x2))) *# 7# +# word2Int# (word32ToWord# (wordToWord32# (int2Word# x3))) *# 11#) *# 257# +# word2Int# (word8ToWord# (indexWord8Array# frozen byte))
  } } })

{-# INLINE writeFour #-}
writeFour :: MutableByteArray# s -> Int# -> Int# -> Int# -> Int# -> Int# -> State# s -> State# s
writeFour bytes offset x0 x1 x2 x3 s0 =
  case writeWord32Array# bytes offset (wordToWord32# (int2Word# x0)) s0 of { s1 ->
  case writeWord32Array# bytes (offset +# 1#) (wordToWord32# (int2Word# x1)) s1 of { s2 ->
  case writeWord32Array# bytes (offset +# 2#) (wordToWord32# (int2Word# x2)) s2 of { s3 ->
  case writeWord32Array# bytes (offset +# 3#) (wordToWord32# (int2Word# x3)) s3 of { s4 -> s4
  } } } }

-- Genuine negative: memory support does not establish a vector formal ABI.
{-# OPAQUE vectorArgument #-}
vectorArgument :: Word32X4# -> Int#
vectorArgument value = case unpackWord32X4# value of
  (# p0, p1, p2, p3 #) -> word2Int# (word32ToWord# p0) *# 3# +# word2Int# (word32ToWord# p1) *# 5# +# word2Int# (word32ToWord# p2) *# 7# +# word2Int# (word32ToWord# p3) *# 11#

-- These compile natively but remain outside THC's immediate-local-read rule.
{-# OPAQUE readTupleEscape #-}
readTupleEscape :: MutableByteArray# s -> Int# -> State# s -> (# State# s, Word32X4# #)
readTupleEscape bytes offset s0 = readWord32X4Array# bytes offset s0

{-# OPAQUE readVectorEscape #-}
readVectorEscape :: MutableByteArray# s -> Int# -> State# s -> Word32X4#
readVectorEscape bytes offset s0 = case readWord32X4Array# bytes offset s0 of
  (# _, value #) -> value
