-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
-- |
-- Module      : JavaArrays
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : THC runtime
--
-- Direct Java-array loops: runtime species, masked tails and gather/scatter.
module JavaArrays where
import GHC.Exts
import GHC.IO (IO(..))
import Control.Exception (catch)
import THC.Exception (ForeignException)
import THC.Prim

-- This function has no scalar-array or ordinary interop operation which could
-- accidentally select the exception bridge for the vector-only path.
caughtVectorBounds :: JavaIntArray# RealWorld -> State# RealWorld -> (# State# RealWorld, Int# #)
caughtVectorBounds array state = case catch action handler of
  IO run -> case run state of (# next, I# result #) -> (# next, result #)
  where
    action = IO $ \s -> case readJavaIntVector# (int32Species# 128#) array 0# s of
      (# next, vector #) -> (# next, I# (int32ToInt# (vecInt32Lane# vector 0#)) #)
    handler :: ForeignException -> IO Int
    handler _ = pure 731

multiply :: Int# -> Int# -> JavaFloatArray# s -> JavaFloatArray# s -> JavaFloatArray# s -> State# s -> State# s
multiply width count left right result state = case floatSpecies# width of
  species -> let
    loop index token = case index <# count of
      0# -> token
      _ -> case speciesIndexInRange# species index count of
        mask -> case readJavaFloatVectorMasked# species left index mask token of
          (# s1, a #) -> case readJavaFloatVectorMasked# species right index mask s1 of
            (# s2, b #) -> case writeJavaFloatVectorMasked# result index (vecMul# a b) mask s2 of
              s3 -> loop (index +# speciesLength# species) s3
    in loop 0# state

gatherScatter :: Int# -> JavaIntArray# s -> JavaIntArray# s -> JavaIntArray# s -> JavaBooleanArray# s -> State# s -> State# s
gatherScatter width source result indices active state = case int32Species# width of
  species -> case readJavaMask# species active 0# state of
    (# s1, mask #) -> case readJavaIntVectorIndexedMasked# species source 0# indices 0# mask s1 of
      (# s2, values #) -> writeJavaIntVectorIndexedMasked# result 0# values indices 0# mask s2

shuffleCopy :: Int# -> JavaIntArray# s -> JavaIntArray# s -> JavaIntArray# s -> State# s -> State# s
shuffleCopy width source result indices state = case int32Species# width of
  species -> case readJavaShuffle# species indices 0# state of
    (# s1, shuffle #) -> case readJavaIntVector# species source 0# s1 of
      (# s2, values #) -> writeJavaIntVector# result 0# (vecRearrange# values shuffle) s2

cycleBoolean :: Int# -> State# s -> (# State# s, Int# #)
cycleBoolean value state = case newJavaBooleanArray# 3# state of
  (# s1, array #) -> case writeJavaBooleanArray# array 0# value s1 of
    s2 -> case copyJavaBooleanArray# array 0# array 1# 2# s2 of
      s3 -> readJavaBooleanArray# array 1# s3

cycleByte :: Int8# -> State# s -> (# State# s, Int8# #)
cycleByte value state = case newJavaByteArray# 3# state of
  (# s1, array #) -> case writeJavaByteArray# array 0# value s1 of
    s2 -> case copyJavaByteArray# array 0# array 1# 2# s2 of
      s3 -> readJavaByteArray# array 1# s3

cycleShort :: Int16# -> State# s -> (# State# s, Int16# #)
cycleShort value state = case newJavaShortArray# 3# state of
  (# s1, array #) -> case writeJavaShortArray# array 0# value s1 of
    s2 -> case copyJavaShortArray# array 0# array 1# 2# s2 of
      s3 -> readJavaShortArray# array 1# s3

cycleChar :: Word16# -> State# s -> (# State# s, Word16# #)
cycleChar value state = case newJavaCharArray# 3# state of
  (# s1, array #) -> case writeJavaCharArray# array 0# value s1 of
    s2 -> case copyJavaCharArray# array 0# array 1# 2# s2 of
      s3 -> readJavaCharArray# array 1# s3

cycleInt :: Int32# -> State# s -> (# State# s, Int32# #)
cycleInt value state = case newJavaIntArray# 3# state of
  (# s1, array #) -> case writeJavaIntArray# array 0# value s1 of
    s2 -> case copyJavaIntArray# array 0# array 1# 2# s2 of
      s3 -> readJavaIntArray# array 1# s3

cycleLong :: Int64# -> State# s -> (# State# s, Int64# #)
cycleLong value state = case newJavaLongArray# 3# state of
  (# s1, array #) -> case writeJavaLongArray# array 0# value s1 of
    s2 -> case copyJavaLongArray# array 0# array 1# 2# s2 of
      s3 -> readJavaLongArray# array 1# s3

cycleFloat :: Float# -> State# s -> (# State# s, Float# #)
cycleFloat value state = case newJavaFloatArray# 3# state of
  (# s1, array #) -> case writeJavaFloatArray# array 0# value s1 of
    s2 -> case copyJavaFloatArray# array 0# array 1# 2# s2 of
      s3 -> readJavaFloatArray# array 1# s3

cycleDouble :: Double# -> State# s -> (# State# s, Double# #)
cycleDouble value state = case newJavaDoubleArray# 3# state of
  (# s1, array #) -> case writeJavaDoubleArray# array 0# value s1 of
    s2 -> case copyJavaDoubleArray# array 0# array 1# 2# s2 of
      s3 -> readJavaDoubleArray# array 1# s3
