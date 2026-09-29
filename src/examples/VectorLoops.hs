-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : VectorLoops
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : THC with the JDK Vector API
--
-- Vector loops over native-order byte arrays. Width 0 selects the host's
-- preferred species; 64, 128, 256 and 512 select an explicit bit width.
-- Arrays must contain at least the requested number of elements.
--
-- These Haskell implementations illustrate the array-multiply example in
-- the OpenJDK Vector API package documentation and the sum-of-squares formula
-- in JEP 338. They use the same algorithms, with THC's state-threaded memory
-- operations instead of Java arrays. No OpenJDK implementation code is copied.
--
-- <https://docs.oracle.com/en/java/javase/25/docs/api/jdk.incubator.vector/jdk/incubator/vector/package-summary.html>
-- <https://openjdk.org/jeps/338>
module VectorLoops
  ( multiplyFloat, negativeSquares, selectInt32, reverseBlocksInt32
  , floatSpeciesProperties, selectedLaneCount, convertedSum, sumMutableFloat
  ) where

import GHC.Exts
import THC.Prim

-- | Multiply two Float arrays. Every iteration constructs a bounds mask;
-- the final iteration may contain fewer lanes than the selected species.
multiplyFloat :: Int# -> Int# -> ByteArray# -> ByteArray# -> MutableByteArray# s -> State# s -> State# s
multiplyFloat width count a b out initial = case floatSpecies# width of
  species -> loop species 0# initial
  where
    loop species offset state = case offset <# count of
      0# -> state
      _ -> case speciesIndexInRange# species offset count of
        active -> case indexFloatVector# species a offset active of
          va -> case indexFloatVector# species b offset active of
            vb -> case writeFloatVector# out offset (vecMul# va vb) active state of
              next -> loop species (offset +# speciesLength# species) next

-- | Compute @-(a*a + b*b)@. Use the unmasked interior and one masked tail;
-- 'speciesLoopBound#' works even when a platform's width is not a power of two.
negativeSquares :: Int# -> Int# -> ByteArray# -> ByteArray# -> MutableByteArray# s -> State# s -> State# s
negativeSquares width count a b out initial = case floatSpecies# width of
  species -> loop species (speciesLoopBound# species count) 0# initial
  where
    step species offset active state =
      case indexFloatVector# species a offset active of
        va -> case indexFloatVector# species b offset active of
          vb -> writeFloatVector# out offset
            (vecNeg# (vecAdd# (vecMul# va va) (vecMul# vb vb))) active state
    loop species bound offset state = case offset <# bound of
      0# -> case offset <# count of
        0# -> state
        _ -> step species offset (speciesIndexInRange# species offset count) state
      _ -> case step species offset (speciesMaskAll# species 1#) state of
        next -> loop species bound (offset +# speciesLength# species) next

-- | Negate only lanes whose input is above a run-time threshold /and/ whose
-- lane bit is set. Bits repeat per vector, so callers can select non-contiguous
-- lanes. Other values are copied unchanged; the final store is bounds-masked.
selectInt32 :: Int# -> Int# -> Int32# -> Word# -> ByteArray# -> MutableByteArray# s -> State# s -> State# s
selectInt32 width count threshold bits input out initial = case int32Species# width of
  species -> loop species (broadcastInt32# species threshold) (maskFromBits# species bits) 0# initial
  where
    loop species limit chosen offset state = case offset <# count of
      0# -> state
      _ -> case speciesIndexInRange# species offset count of
        active -> case indexInt32Vector# species input offset active of
          value -> case maskAnd# chosen (vecGt# value limit) of
            selected -> case writeInt32Vector# out offset (vecBlend# value (vecNeg# value) selected) active state of
              next -> loop species limit chosen (offset +# speciesLength# species) next

-- | Reverse each complete vector using a shuffle constructed from its queried
-- width. Copy the incomplete final block unchanged.
reverseBlocksInt32 :: Int# -> Int# -> ByteArray# -> MutableByteArray# s -> State# s -> State# s
reverseBlocksInt32 width count input out initial = case int32Species# width of
  species -> case speciesLength# species of
    lanes -> loop species (shuffleIota# species (lanes -# 1#) (-1#) 0#)
      (speciesLoopBound# species count) 0# initial
  where
    loop species permutation bound offset state = case offset <# bound of
      0# -> case offset <# count of
        0# -> state
        _ -> case speciesIndexInRange# species offset count of
          active -> writeInt32Vector# out offset (indexInt32Vector# species input offset active) active state
      _ -> case speciesMaskAll# species 1# of
        active -> case indexInt32Vector# species input offset active of
          value -> case writeInt32Vector# out offset (vecRearrange# value permutation) active state of
            next -> loop species permutation bound (offset +# speciesLength# species) next

-- | Observe the platform's chosen lane count, element width, vector width and
-- byte size without assuming a particular CPU or species.
floatSpeciesProperties :: Int# -> (# Int#, Int#, Int#, Int# #)
floatSpeciesProperties width = case floatSpecies# width of
  species -> (# speciesLength# species, speciesElementBits# species,
                speciesVectorBits# species, speciesVectorBytes# species #)

-- | Intersect caller bits with a dynamic tail and inspect the resulting mask.
selectedLaneCount :: Int# -> Int# -> Word# -> Int#
selectedLaneCount width remaining bits = case int32Species# width of
  species -> maskTrueCount# (maskAnd# (maskFromBits# species bits)
    (speciesIndexInRange# species 0# remaining))

-- | Convert Int32 lanes to Float lanes of the same shape and reduce the active
-- prefix. Both conversion and reduction use JDK Vector API semantics.
convertedSum :: Int# -> Int# -> ByteArray# -> Float#
convertedSum width count input = case int32Species# width of
  ints -> case floatSpecies# width of
    floats -> case speciesIndexInRange# ints 0# count of
      active -> vecFloatReduceAdd#
        (vecConvert# (indexInt32Vector# ints input 0# active) floats 0#)
        (maskCast# active floats)

-- | Sum mutable Float elements in a state thread. Each read returns its vector
-- in the ordinary unboxed tuple; no Haskell vector wrapper is allocated.
sumMutableFloat :: Int# -> Int# -> MutableByteArray# s -> State# s -> (# State# s, Float# #)
sumMutableFloat width count input initial = case floatSpecies# width of
  species -> loop species 0# 0.0# initial
  where
    loop species offset total state = case offset <# count of
      0# -> (# state, total #)
      _ -> case speciesIndexInRange# species offset count of
        active -> case readFloatVector# species input offset active state of
          (# next, value #) -> loop species (offset +# speciesLength# species)
            (plusFloat# total (vecFloatReduceAdd# value active)) next
