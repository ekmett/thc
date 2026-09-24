-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module ScalarBitCastAudit where
import GHC.Exts
{-# OPAQUE toFloat #-}
toFloat :: Word32# -> Float#
toFloat x = keepFloat (castWord32ToFloat# x)
{-# OPAQUE fromFloat #-}
fromFloat :: Float# -> Word32#
fromFloat x = keepWord32 (castFloatToWord32# x)
{-# OPAQUE toDouble #-}
toDouble :: Word64# -> Double#
toDouble x = keepDouble (castWord64ToDouble# x)
{-# OPAQUE fromDouble #-}
fromDouble :: Double# -> Word64#
fromDouble x = keepWord64 (castDoubleToWord64# x)
floatRoundtrip :: Int# -> Int#
floatRoundtrip x = word2Int# (word32ToWord# (fromFloat (toFloat (wordToWord32# (int2Word# x)))))
doubleRoundtrip :: Int# -> Int#
doubleRoundtrip x = word2Int# (word64ToWord# (fromDouble (toDouble (wordToWord64# (int2Word# x)))))

-- The neighbour is genuine bottom but neither storage nor extraction demands it.
data BitsFloatBox = BitsFloatBox Float# ()
data BitsDoubleBox = BitsDoubleBox Double# ()
bottom :: ()
bottom = bottom
{-# OPAQUE floatBox #-}
floatBox :: Float# -> BitsFloatBox
floatBox value = BitsFloatBox value bottom
{-# OPAQUE doubleBox #-}
doubleBox :: Double# -> BitsDoubleBox
doubleBox value = BitsDoubleBox value bottom
floatField :: Int# -> Int#
floatField raw = case floatBox (toFloat (wordToWord32# (int2Word# raw))) of
  BitsFloatBox value _ -> word2Int# (word32ToWord# (fromFloat value))
doubleField :: Int# -> Int#
doubleField raw = case doubleBox (toDouble (wordToWord64# (int2Word# raw))) of
  BitsDoubleBox value _ -> word2Int# (word64ToWord# (fromDouble value))

{-# OPAQUE callFloat #-}
callFloat :: (Int# -> Word32#) -> Int# -> Word32#
callFloat function x = function x
{-# OPAQUE callDouble #-}
callDouble :: (Int# -> Word64#) -> Int# -> Word64#
callDouble function x = function x
floatCaptured :: Int# -> Int#
floatCaptured raw = case toFloat (wordToWord32# (int2Word# raw)) of
  value -> word2Int# (word32ToWord# (callFloat (\_ -> fromFloat value) raw))
doubleCaptured :: Int# -> Int#
doubleCaptured raw = case toDouble (wordToWord64# (int2Word# raw)) of
  value -> word2Int# (word64ToWord# (callDouble (\_ -> fromDouble value) raw))

-- Observe each direction independently through existing native-endian storage.
-- A mutually inverse bug in the two new casts cannot satisfy these controls.
floatDecode :: Int# -> Int#
floatDecode raw = runRW# (\s0 -> case newByteArray# 4# s0 of
  (# s1, bytes #) -> case writeFloatArray# bytes 0# (toFloat (wordToWord32# (int2Word# raw))) s1 of
    s2 -> case unsafeFreezeByteArray# bytes s2 of
      (# _, frozen #) -> word2Int# (word32ToWord# (indexWord32Array# frozen 0#)))
floatEncode :: Int# -> Int#
floatEncode raw = runRW# (\s0 -> case newByteArray# 4# s0 of
  (# s1, bytes #) -> case writeWord32Array# bytes 0# (wordToWord32# (int2Word# raw)) s1 of
    s2 -> case unsafeFreezeByteArray# bytes s2 of
      (# _, frozen #) -> word2Int# (word32ToWord# (fromFloat (indexFloatArray# frozen 0#))))
doubleDecode :: Int# -> Int#
doubleDecode raw = runRW# (\s0 -> case newByteArray# 8# s0 of
  (# s1, bytes #) -> case writeDoubleArray# bytes 0# (toDouble (wordToWord64# (int2Word# raw))) s1 of
    s2 -> case unsafeFreezeByteArray# bytes s2 of
      (# _, frozen #) -> word2Int# (indexWordArray# frozen 0#))
doubleEncode :: Int# -> Int#
doubleEncode raw = runRW# (\s0 -> case newByteArray# 8# s0 of
  (# s1, bytes #) -> case writeWordArray# bytes 0# (int2Word# raw) s1 of
    s2 -> case unsafeFreezeByteArray# bytes s2 of
      (# _, frozen #) -> word2Int# (word64ToWord# (fromDouble (indexDoubleArray# frozen 0#))))

-- Keep a saturated primitive expression instead of an eta-reduced bare primop.
{-# OPAQUE keepFloat #-}
keepFloat :: Float# -> Float#
keepFloat x = x
{-# OPAQUE keepDouble #-}
keepDouble :: Double# -> Double#
keepDouble x = x
{-# OPAQUE keepWord32 #-}
keepWord32 :: Word32# -> Word32#
keepWord32 x = x
{-# OPAQUE keepWord64 #-}
keepWord64 :: Word64# -> Word64#
keepWord64 x = x
