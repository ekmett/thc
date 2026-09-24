{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdFloatX4ByteArray where

import GHC.Exts

-- Raw Float bits travel through scalar Word32/Float array views, never bitcasts.
-- Portable corpus excludes signaling NaNs; native-only diagnostics are separate.
-- No mutation follows freezing. Graph roots are deliberately scratch-array free.

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

{-# INLINE writeFourBits #-}
writeFourBits :: MutableByteArray# s -> Int# -> Int# -> Int# -> Int# -> Int# -> State# s -> State# s
writeFourBits a offset x0 x1 x2 x3 s0 =
  case writeWord32Array# a (offset) (wordToWord32# (int2Word# x0)) s0 of { s1 ->
  case writeWord32Array# a (offset +# 1#) (wordToWord32# (int2Word# x1)) s1 of { s2 ->
  case writeWord32Array# a (offset +# 2#) (wordToWord32# (int2Word# x2)) s2 of { s3 ->
  case writeWord32Array# a (offset +# 3#) (wordToWord32# (int2Word# x3)) s3 of { s4 ->
    s4
  } } } }

{-# INLINE writeFourFloats #-}
writeFourFloats :: MutableByteArray# s -> Int# -> Float# -> Float# -> Float# -> Float# -> State# s -> State# s
writeFourFloats a offset p0 p1 p2 p3 s0 =
  case writeFloatArray# a (offset) p0 s0 of { s1 ->
  case writeFloatArray# a (offset +# 1#) p1 s1 of { s2 ->
  case writeFloatArray# a (offset +# 2#) p2 s2 of { s3 ->
  case writeFloatArray# a (offset +# 3#) p3 s3 of { s4 ->
    s4
  } } } }

vectorUnitCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int#
vectorUnitCase offset x0 x1 x2 x3 lane = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeFourBits bytes (offset *# 4#) x0 x1 x2 x3 s2 of { s3 ->
  case readFloatX4Array# bytes offset s3 of { (# s4, before #) ->
  case writeWord32Array# bytes ((offset *# 4#) +# 1#) (wordToWord32# (int2Word# (x1 `xorI#` 0x80000000#))) s4 of { s5 ->
  case readFloatX4Array# bytes offset s5 of { (# s6, after #) ->
  case newByteArray# 32# s6 of { (# s7, snapshots #) ->
  case writeFloatX4Array# snapshots 0# before s7 of { s8 ->
  case writeFloatX4Array# snapshots 1# after s8 of { s9 ->
  case unsafeFreezeByteArray# snapshots s9 of { (# _, frozen #) ->
    word2Int# (word32ToWord# (indexWord32Array# frozen lane))
  } } } } } } } } } } )

{-# OPAQUE vectorIndexWorker #-}
vectorIndexWorker :: ByteArray# -> Int# -> Int# -> Int#
vectorIndexWorker bytes offset lane = runRW# (\s0 ->
  case unpackFloatX4# (indexFloatX4Array# bytes offset) of { (# p0, p1, p2, p3 #) ->
  case newByteArray# 16# s0 of { (# s1, scratch #) ->
  case writeFourFloats scratch 0# p0 p1 p2 p3 s1 of { s2 ->
  case unsafeFreezeByteArray# scratch s2 of { (# _, frozen #) ->
    word2Int# (word32ToWord# (indexWord32Array# frozen lane))
  } } } } )

{-# OPAQUE vectorReadWorker #-}
vectorReadWorker :: MutableByteArray# s -> Int# -> Int# -> State# s -> (# State# s, Int# #)
vectorReadWorker bytes offset lane s0 =
  case readFloatX4Array# bytes offset s0 of { (# s1, value #) ->
  case unpackFloatX4# value of { (# p0, p1, p2, p3 #) ->
  case newByteArray# 16# s1 of { (# s2, scratch #) ->
  case writeFourFloats scratch 0# p0 p1 p2 p3 s2 of { s3 ->
  case readWord32Array# scratch lane s3 of { (# s4, bits #) ->
     (# s4, word2Int# (word32ToWord# bits) #)
  } } } } }

{-# OPAQUE vectorWriteWorker #-}
vectorWriteWorker :: MutableByteArray# s -> Int# -> Int# -> Int# -> Int# -> Int# -> State# s -> (# State# s, Int# #)
vectorWriteWorker bytes offset x0 x1 x2 x3 s0 =
  case newByteArray# 16# s0 of { (# s1, scratch #) ->
  case writeFourBits scratch 0# x0 x1 x2 x3 s1 of { s2 ->
  case readFloatArray# scratch 0# s2 of { (# s3, p0 #) ->
  case readFloatArray# scratch 1# s3 of { (# s4, p1 #) ->
  case readFloatArray# scratch 2# s4 of { (# s5, p2 #) ->
  case readFloatArray# scratch 3# s5 of { (# s6, p3 #) ->
  case writeFloatX4Array# bytes offset (packFloatX4# (# p0, p1, p2, p3 #)) s6 of { s7 ->
    (# s7, x0 *# 3# +# x1 *# 5# +# x2 *# 7# +# x3 *# 11# #)
  } } } } } } }

{-# OPAQUE vectorIndexGraph #-}
vectorIndexGraph :: ByteArray# -> Int# -> Int#
vectorIndexGraph bytes offset = case unpackFloatX4# (indexFloatX4Array# bytes offset) of
  (# p0, p1, p2, p3 #) -> float2Int# ((((p0 `timesFloat#` 3.0#) `plusFloat#` (p1 `timesFloat#` 5.0#)) `plusFloat#` (p2 `timesFloat#` 7.0#)) `plusFloat#` (p3 `timesFloat#` 11.0#))

{-# OPAQUE vectorStoreGraph #-}
vectorStoreGraph :: MutableByteArray# RealWorld -> Int# -> Int# -> Int# -> Int# -> Int# -> State# RealWorld -> ByteArray#
vectorStoreGraph bytes offset x0 x1 x2 x3 s0 =
  case writeFloatX4Array# bytes offset (packFloatX4# (# int2Float# x0, int2Float# x1, int2Float# x2, int2Float# x3 #)) s0 of { s1 ->
  case unsafeFreezeByteArray# bytes s1 of { (# _, frozen #) ->
    frozen
  } }

vectorIndexCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int#
vectorIndexCase offset x0 x1 x2 x3 selector = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeFourBits bytes (offset *# 4#) x0 x1 x2 x3 s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of { (# _, frozen #) ->
    vectorIndexWorker frozen offset selector
  } } } } )

vectorReadCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int#
vectorReadCase offset x0 x1 x2 x3 selector = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeFourBits bytes (offset *# 4#) x0 x1 x2 x3 s2 of { s3 ->
  case vectorReadWorker bytes offset selector s3 of { (# _, answer #) ->
    answer
  } } } } )

vectorWriteCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int#
vectorWriteCase offset x0 x1 x2 x3 selector = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case vectorWriteWorker bytes offset x0 x1 x2 x3 s2 of { (# s3, score #) ->
  case unsafeFreezeByteArray# bytes s3 of { (# _, frozen #) ->
    score *# 257# +# word2Int# (word8ToWord# (indexWord8Array# frozen selector))
  } } } } )

vectorGraphIndexCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
vectorGraphIndexCase offset x0 x1 x2 x3 = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeFourFloats bytes (offset *# 4#) (int2Float# x0) (int2Float# x1) (int2Float# x2) (int2Float# x3) s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of { (# _, frozen #) ->
    vectorIndexGraph frozen offset
  } } } } )

vectorGraphStoreCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int#
vectorGraphStoreCase offset x0 x1 x2 x3 selector = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case vectorStoreGraph bytes offset x0 x1 x2 x3 s2 of { frozen ->
    (x0 *# 3# +# x1 *# 5# +# x2 *# 7# +# x3 *# 11#) *# 257# +# word2Int# (word8ToWord# (indexWord8Array# frozen selector))
  } } } )

scalarUnitCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int#
scalarUnitCase offset x0 x1 x2 x3 lane = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeFourBits bytes offset x0 x1 x2 x3 s2 of { s3 ->
  case readFloatArrayAsFloatX4# bytes offset s3 of { (# s4, before #) ->
  case writeWord8Array# bytes (offset *# 4# +# 7#) (wordToWord8# (int2Word# ((x1 `uncheckedIShiftRL#` 24#) `xorI#` 128#))) s4 of { s5 ->
  case readFloatArrayAsFloatX4# bytes offset s5 of { (# s6, after #) ->
  case newByteArray# 32# s6 of { (# s7, snapshots #) ->
  case writeFloatX4Array# snapshots 0# before s7 of { s8 ->
  case writeFloatX4Array# snapshots 1# after s8 of { s9 ->
  case unsafeFreezeByteArray# snapshots s9 of { (# _, frozen #) ->
    word2Int# (word32ToWord# (indexWord32Array# frozen lane))
  } } } } } } } } } } )

{-# OPAQUE scalarIndexWorker #-}
scalarIndexWorker :: ByteArray# -> Int# -> Int# -> Int#
scalarIndexWorker bytes offset lane = runRW# (\s0 ->
  case unpackFloatX4# (indexFloatArrayAsFloatX4# bytes offset) of { (# p0, p1, p2, p3 #) ->
  case newByteArray# 16# s0 of { (# s1, scratch #) ->
  case writeFourFloats scratch 0# p0 p1 p2 p3 s1 of { s2 ->
  case unsafeFreezeByteArray# scratch s2 of { (# _, frozen #) ->
    word2Int# (word32ToWord# (indexWord32Array# frozen lane))
  } } } } )

{-# OPAQUE scalarReadWorker #-}
scalarReadWorker :: MutableByteArray# s -> Int# -> Int# -> State# s -> (# State# s, Int# #)
scalarReadWorker bytes offset lane s0 =
  case readFloatArrayAsFloatX4# bytes offset s0 of { (# s1, value #) ->
  case unpackFloatX4# value of { (# p0, p1, p2, p3 #) ->
  case newByteArray# 16# s1 of { (# s2, scratch #) ->
  case writeFourFloats scratch 0# p0 p1 p2 p3 s2 of { s3 ->
  case readWord32Array# scratch lane s3 of { (# s4, bits #) ->
     (# s4, word2Int# (word32ToWord# bits) #)
  } } } } }

{-# OPAQUE scalarWriteWorker #-}
scalarWriteWorker :: MutableByteArray# s -> Int# -> Int# -> Int# -> Int# -> Int# -> State# s -> (# State# s, Int# #)
scalarWriteWorker bytes offset x0 x1 x2 x3 s0 =
  case newByteArray# 16# s0 of { (# s1, scratch #) ->
  case writeFourBits scratch 0# x0 x1 x2 x3 s1 of { s2 ->
  case readFloatArray# scratch 0# s2 of { (# s3, p0 #) ->
  case readFloatArray# scratch 1# s3 of { (# s4, p1 #) ->
  case readFloatArray# scratch 2# s4 of { (# s5, p2 #) ->
  case readFloatArray# scratch 3# s5 of { (# s6, p3 #) ->
  case writeFloatArrayAsFloatX4# bytes offset (packFloatX4# (# p0, p1, p2, p3 #)) s6 of { s7 ->
    (# s7, x0 *# 3# +# x1 *# 5# +# x2 *# 7# +# x3 *# 11# #)
  } } } } } } }

{-# OPAQUE scalarIndexGraph #-}
scalarIndexGraph :: ByteArray# -> Int# -> Int#
scalarIndexGraph bytes offset = case unpackFloatX4# (indexFloatArrayAsFloatX4# bytes offset) of
  (# p0, p1, p2, p3 #) -> float2Int# ((((p0 `timesFloat#` 3.0#) `plusFloat#` (p1 `timesFloat#` 5.0#)) `plusFloat#` (p2 `timesFloat#` 7.0#)) `plusFloat#` (p3 `timesFloat#` 11.0#))

{-# OPAQUE scalarStoreGraph #-}
scalarStoreGraph :: MutableByteArray# RealWorld -> Int# -> Int# -> Int# -> Int# -> Int# -> State# RealWorld -> ByteArray#
scalarStoreGraph bytes offset x0 x1 x2 x3 s0 =
  case writeFloatArrayAsFloatX4# bytes offset (packFloatX4# (# int2Float# x0, int2Float# x1, int2Float# x2, int2Float# x3 #)) s0 of { s1 ->
  case unsafeFreezeByteArray# bytes s1 of { (# _, frozen #) ->
    frozen
  } }

scalarIndexCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int#
scalarIndexCase offset x0 x1 x2 x3 selector = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeFourBits bytes offset x0 x1 x2 x3 s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of { (# _, frozen #) ->
    scalarIndexWorker frozen offset selector
  } } } } )

scalarReadCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int#
scalarReadCase offset x0 x1 x2 x3 selector = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeFourBits bytes offset x0 x1 x2 x3 s2 of { s3 ->
  case scalarReadWorker bytes offset selector s3 of { (# _, answer #) ->
    answer
  } } } } )

scalarWriteCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int#
scalarWriteCase offset x0 x1 x2 x3 selector = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case scalarWriteWorker bytes offset x0 x1 x2 x3 s2 of { (# s3, score #) ->
  case unsafeFreezeByteArray# bytes s3 of { (# _, frozen #) ->
    score *# 257# +# word2Int# (word8ToWord# (indexWord8Array# frozen selector))
  } } } } )

scalarGraphIndexCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
scalarGraphIndexCase offset x0 x1 x2 x3 = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case writeFourFloats bytes offset (int2Float# x0) (int2Float# x1) (int2Float# x2) (int2Float# x3) s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of { (# _, frozen #) ->
    scalarIndexGraph frozen offset
  } } } } )

scalarGraphStoreCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int# -> Int#
scalarGraphStoreCase offset x0 x1 x2 x3 selector = runRW# (\s0 ->
  case newByteArray# 64# s0 of { (# s1, bytes #) ->
  case initialize64 bytes s1 of { s2 ->
  case scalarStoreGraph bytes offset x0 x1 x2 x3 s2 of { frozen ->
    (x0 *# 3# +# x1 *# 5# +# x2 *# 7# +# x3 *# 11#) *# 257# +# word2Int# (word8ToWord# (indexWord8Array# frozen selector))
  } } } )

-- Deliberately unsupported vector and whole State/vector result boundaries.
{-# OPAQUE vectorArgument #-}
vectorArgument :: FloatX4# -> Int#
vectorArgument value = case unpackFloatX4# value of
  (# p0, p1, p2, p3 #) -> float2Int# ((((p0 `timesFloat#` 3.0#) `plusFloat#` (p1 `timesFloat#` 5.0#)) `plusFloat#` (p2 `timesFloat#` 7.0#)) `plusFloat#` (p3 `timesFloat#` 11.0#))

{-# OPAQUE readTupleEscape #-}
readTupleEscape :: MutableByteArray# s -> Int# -> State# s -> (# State# s, FloatX4# #)
readTupleEscape bytes offset s0 = readFloatX4Array# bytes offset s0

{-# OPAQUE readVectorEscape #-}
readVectorEscape :: MutableByteArray# s -> Int# -> State# s -> FloatX4#
readVectorEscape bytes offset s0 = case readFloatX4Array# bytes offset s0 of
  (# _, value #) -> value
