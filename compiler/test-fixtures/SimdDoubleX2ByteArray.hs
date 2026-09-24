{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdDoubleX2ByteArray where

import GHC.Exts

-- Every signed Int# bit pattern is a raw64 encoding, never a numeric Double.
-- Existing Int/Double array views supply the raw-bit witness without bitcasts.
-- Portable rows exclude signaling NaNs. No array is mutated after freezing.
-- The four finite graph roots deliberately have no scratch alias arrays.

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
    s16
  } } } } } } } } } } } } } } } }

{-# INLINE writeTwoBits #-}
writeTwoBits :: MutableByteArray# s -> Int# -> Int# -> Int# -> State# s -> State# s
writeTwoBits a offset x0 x1 s0 =
  case writeIntArray# a offset x0 s0 of { s1 ->
  case writeIntArray# a (offset +# 1#) x1 s1 of { s2 ->
    s2
  } }

{-# INLINE writeTwoDoubles #-}
writeTwoDoubles :: MutableByteArray# s -> Int# -> Double# -> Double# -> State# s -> State# s
writeTwoDoubles a offset p0 p1 s0 =
  case writeDoubleArray# a offset p0 s0 of { s1 ->
  case writeDoubleArray# a (offset +# 1#) p1 s1 of { s2 ->
    s2
  } }

vectorUnitCase :: Int# -> Int# -> Int# -> Int# -> Int#
vectorUnitCase offset x0 x1 lane = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeTwoBits bytes (offset *# 2#) x0 x1 s2 of { s3 ->
  case readDoubleX2Array# bytes offset s3 of { (# s4, before #) ->
  case writeIntArray# bytes ((offset *# 2#) +# 1#) (x1 `xorI#` (1# `uncheckedIShiftL#` 63#)) s4 of { s5 ->
  case readDoubleX2Array# bytes offset s5 of { (# s6, after #) ->
  case newByteArray# 32# s6 of { (# s7, snapshots #) ->
  case writeDoubleX2Array# snapshots 0# before s7 of { s8 ->
  case writeDoubleX2Array# snapshots 1# after s8 of { s9 ->
  case unsafeFreezeByteArray# snapshots s9 of { (# _, frozen #) ->
    indexIntArray# frozen lane
  } } } } } } } } } })

{-# OPAQUE vectorIndexWorker #-}
vectorIndexWorker :: ByteArray# -> Int# -> Int# -> Int#
vectorIndexWorker bytes offset lane = runRW# (\s0 ->
  case unpackDoubleX2# (indexDoubleX2Array# bytes offset) of { (# p0, p1 #) ->
  case newByteArray# 16# s0 of { (# s1, scratch #) ->
  case writeTwoDoubles scratch 0# p0 p1 s1 of { s2 ->
  case unsafeFreezeByteArray# scratch s2 of { (# _, frozen #) ->
    indexIntArray# frozen lane
  } } } })

{-# OPAQUE vectorReadWorker #-}
vectorReadWorker :: MutableByteArray# s -> Int# -> Int# -> State# s -> (# State# s, Int# #)
vectorReadWorker bytes offset lane s0 =
  case readDoubleX2Array# bytes offset s0 of { (# s1, value #) ->
  case unpackDoubleX2# value of { (# p0, p1 #) ->
  case newByteArray# 16# s1 of { (# s2, scratch #) ->
  case writeTwoDoubles scratch 0# p0 p1 s2 of { s3 ->
  case readIntArray# scratch lane s3 of { (# s4, bits #) ->
    (# s4, bits #)
  } } } } }

{-# OPAQUE vectorWriteWorker #-}
vectorWriteWorker :: MutableByteArray# s -> Int# -> Int# -> Int# -> State# s -> (# State# s, Int# #)
vectorWriteWorker bytes offset x0 x1 s0 =
  case newByteArray# 16# s0 of { (# s1, scratch #) ->
  case writeTwoBits scratch 0# x0 x1 s1 of { s2 ->
  case readDoubleArray# scratch 0# s2 of { (# s3, p0 #) ->
  case readDoubleArray# scratch 1# s3 of { (# s4, p1 #) ->
  case writeDoubleX2Array# bytes offset (packDoubleX2# (# p0, p1 #)) s4 of { s5 ->
    (# s5, x0 `xorI#` x1 #)
  } } } } }

{-# OPAQUE vectorIndexGraph #-}
vectorIndexGraph :: ByteArray# -> Int# -> Int#
vectorIndexGraph bytes offset = case unpackDoubleX2# (indexDoubleX2Array# bytes offset) of
  (# p0, p1 #) -> double2Int# ((p0 *## 3.0##) +## (p1 *## 5.0##))

{-# OPAQUE vectorStoreGraph #-}
vectorStoreGraph :: MutableByteArray# RealWorld -> Int# -> Int# -> Int# -> State# RealWorld -> ByteArray#
vectorStoreGraph bytes offset x0 x1 s0 =
  case writeDoubleX2Array# bytes offset (packDoubleX2# (# int2Double# x0, int2Double# x1 #)) s0 of { s1 ->
  case unsafeFreezeByteArray# bytes s1 of { (# _, frozen #) ->
    frozen
  } }

vectorIndexCase :: Int# -> Int# -> Int# -> Int# -> Int#
vectorIndexCase offset x0 x1 selector = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeTwoBits bytes (offset *# 2#) x0 x1 s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of { (# _, frozen #) ->
    vectorIndexWorker frozen offset selector
  } } } })

vectorReadCase :: Int# -> Int# -> Int# -> Int# -> Int#
vectorReadCase offset x0 x1 selector = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeTwoBits bytes (offset *# 2#) x0 x1 s2 of { s3 ->
  case vectorReadWorker bytes offset selector s3 of { (# _, answer #) ->
    answer
  } } } })

vectorWriteCase :: Int# -> Int# -> Int# -> Int# -> Int#
vectorWriteCase offset x0 x1 selector = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case vectorWriteWorker bytes offset x0 x1 s2 of { (# s3, digest #) ->
  case unsafeFreezeByteArray# bytes s3 of { (# _, frozen #) ->
    digest `xorI#` word2Int# (word8ToWord# (indexWord8Array# frozen selector))
  } } } })

vectorGraphIndexCase :: Int# -> Int# -> Int# -> Int#
vectorGraphIndexCase offset x0 x1 = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeTwoDoubles bytes (offset *# 2#) (int2Double# x0) (int2Double# x1) s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of { (# _, frozen #) ->
    vectorIndexGraph frozen offset
  } } } })

vectorGraphStoreCase :: Int# -> Int# -> Int# -> Int# -> Int#
vectorGraphStoreCase offset x0 x1 selector = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case vectorStoreGraph bytes offset x0 x1 s2 of { frozen ->
    (x0 *# 3# +# x1 *# 5#) *# 257# +# word2Int# (word8ToWord# (indexWord8Array# frozen selector))
  } } })

scalarUnitCase :: Int# -> Int# -> Int# -> Int# -> Int#
scalarUnitCase offset x0 x1 lane = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeTwoBits bytes offset x0 x1 s2 of { s3 ->
  case readDoubleArrayAsDoubleX2# bytes offset s3 of { (# s4, before #) ->
  case writeWord8Array# bytes (offset *# 8# +# 15#) (wordToWord8# (int2Word# ((x1 `uncheckedIShiftRL#` 56#) `xorI#` 128#))) s4 of { s5 ->
  case readDoubleArrayAsDoubleX2# bytes offset s5 of { (# s6, after #) ->
  case newByteArray# 32# s6 of { (# s7, snapshots #) ->
  case writeDoubleX2Array# snapshots 0# before s7 of { s8 ->
  case writeDoubleX2Array# snapshots 1# after s8 of { s9 ->
  case unsafeFreezeByteArray# snapshots s9 of { (# _, frozen #) ->
    indexIntArray# frozen lane
  } } } } } } } } } })

{-# OPAQUE scalarIndexWorker #-}
scalarIndexWorker :: ByteArray# -> Int# -> Int# -> Int#
scalarIndexWorker bytes offset lane = runRW# (\s0 ->
  case unpackDoubleX2# (indexDoubleArrayAsDoubleX2# bytes offset) of { (# p0, p1 #) ->
  case newByteArray# 16# s0 of { (# s1, scratch #) ->
  case writeTwoDoubles scratch 0# p0 p1 s1 of { s2 ->
  case unsafeFreezeByteArray# scratch s2 of { (# _, frozen #) ->
    indexIntArray# frozen lane
  } } } })

{-# OPAQUE scalarReadWorker #-}
scalarReadWorker :: MutableByteArray# s -> Int# -> Int# -> State# s -> (# State# s, Int# #)
scalarReadWorker bytes offset lane s0 =
  case readDoubleArrayAsDoubleX2# bytes offset s0 of { (# s1, value #) ->
  case unpackDoubleX2# value of { (# p0, p1 #) ->
  case newByteArray# 16# s1 of { (# s2, scratch #) ->
  case writeTwoDoubles scratch 0# p0 p1 s2 of { s3 ->
  case readIntArray# scratch lane s3 of { (# s4, bits #) ->
    (# s4, bits #)
  } } } } }

{-# OPAQUE scalarWriteWorker #-}
scalarWriteWorker :: MutableByteArray# s -> Int# -> Int# -> Int# -> State# s -> (# State# s, Int# #)
scalarWriteWorker bytes offset x0 x1 s0 =
  case newByteArray# 16# s0 of { (# s1, scratch #) ->
  case writeTwoBits scratch 0# x0 x1 s1 of { s2 ->
  case readDoubleArray# scratch 0# s2 of { (# s3, p0 #) ->
  case readDoubleArray# scratch 1# s3 of { (# s4, p1 #) ->
  case writeDoubleArrayAsDoubleX2# bytes offset (packDoubleX2# (# p0, p1 #)) s4 of { s5 ->
    (# s5, x0 `xorI#` x1 #)
  } } } } }

{-# OPAQUE scalarIndexGraph #-}
scalarIndexGraph :: ByteArray# -> Int# -> Int#
scalarIndexGraph bytes offset = case unpackDoubleX2# (indexDoubleArrayAsDoubleX2# bytes offset) of
  (# p0, p1 #) -> double2Int# ((p0 *## 3.0##) +## (p1 *## 5.0##))

{-# OPAQUE scalarStoreGraph #-}
scalarStoreGraph :: MutableByteArray# RealWorld -> Int# -> Int# -> Int# -> State# RealWorld -> ByteArray#
scalarStoreGraph bytes offset x0 x1 s0 =
  case writeDoubleArrayAsDoubleX2# bytes offset (packDoubleX2# (# int2Double# x0, int2Double# x1 #)) s0 of { s1 ->
  case unsafeFreezeByteArray# bytes s1 of { (# _, frozen #) ->
    frozen
  } }

scalarIndexCase :: Int# -> Int# -> Int# -> Int# -> Int#
scalarIndexCase offset x0 x1 selector = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeTwoBits bytes offset x0 x1 s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of { (# _, frozen #) ->
    scalarIndexWorker frozen offset selector
  } } } })

scalarReadCase :: Int# -> Int# -> Int# -> Int# -> Int#
scalarReadCase offset x0 x1 selector = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeTwoBits bytes offset x0 x1 s2 of { s3 ->
  case scalarReadWorker bytes offset selector s3 of { (# _, answer #) ->
    answer
  } } } })

scalarWriteCase :: Int# -> Int# -> Int# -> Int# -> Int#
scalarWriteCase offset x0 x1 selector = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case scalarWriteWorker bytes offset x0 x1 s2 of { (# s3, digest #) ->
  case unsafeFreezeByteArray# bytes s3 of { (# _, frozen #) ->
    digest `xorI#` word2Int# (word8ToWord# (indexWord8Array# frozen selector))
  } } } })

scalarGraphIndexCase :: Int# -> Int# -> Int# -> Int#
scalarGraphIndexCase offset x0 x1 = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeTwoDoubles bytes offset (int2Double# x0) (int2Double# x1) s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of { (# _, frozen #) ->
    scalarIndexGraph frozen offset
  } } } })

scalarGraphStoreCase :: Int# -> Int# -> Int# -> Int# -> Int#
scalarGraphStoreCase offset x0 x1 selector = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case scalarStoreGraph bytes offset x0 x1 s2 of { frozen ->
    (x0 *# 3# +# x1 *# 5#) *# 257# +# word2Int# (word8ToWord# (indexWord8Array# frozen selector))
  } } })

-- Deliberately unsupported vector and whole State/vector result boundaries.
{-# OPAQUE vectorArgument #-}
vectorArgument :: DoubleX2# -> Int#
vectorArgument value = case unpackDoubleX2# value of
  (# p0, p1 #) -> double2Int# ((p0 *## 3.0##) +## (p1 *## 5.0##))

{-# OPAQUE readTupleEscape #-}
readTupleEscape :: MutableByteArray# s -> Int# -> State# s -> (# State# s, DoubleX2# #)
readTupleEscape bytes offset s0 = readDoubleX2Array# bytes offset s0

{-# OPAQUE readVectorEscape #-}
readVectorEscape :: MutableByteArray# s -> Int# -> State# s -> DoubleX2#
readVectorEscape bytes offset s0 = case readDoubleX2Array# bytes offset s0 of
  (# _, value #) -> value
