-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module THC.UnboxedArrays where

import GHC.Exts (Int(I#), Int#)
import Data.Array.Unboxed (UArray, accumArray, bounds, elems, (!))
import Data.Array.ST (runSTUArray, newArray, readArray, writeArray)

-- Genuine checked public APIs. Fixed mixed-sign bounds and indices allow GHC
-- to discharge the cold bounds-error branches without optimizer fences.
-- Duplicate accumulations are intentional; every value still depends on raw.
unboxedAccum :: Int# -> Int#
unboxedAccum raw =
  let a = accumArray (+) (I# raw) (-3,4) [(-3,3),(0,5),(-3,-2),(4,I# raw)] :: UArray Int Int
  in case (a!(-3))*7 + (a!0)*11 + (a!4)*13 of I# answer -> answer

-- An empty public array has no element to inspect. GHC may eliminate this
-- allocation; the preparation makes no primitive-retention claim for this root.
unboxedEmpty :: Int# -> Int#
unboxedEmpty raw =
  let a = accumArray (+) (I# raw) (1,0) [] :: UArray Int Int
      (lo,hi) = bounds a
  in case I# raw + length (elems a) + 7*lo + 11*hi of I# answer -> answer

-- Read-after-write feedback inside ST, followed by runSTUArray's no-copy
-- publication and checked immutable indexing. No freeze/thaw or FFI copies.
unboxedST :: Int# -> Int#
unboxedST raw =
  let a :: UArray Int Int
      a = runSTUArray $ do
        m <- newArray (-3,4) (I# raw)
        x <- readArray m (-3)
        writeArray m 0 (x+7)
        y <- readArray m 0
        writeArray m 4 (3*y-I# raw)
        pure m
  in case (a!(-3))*7 + (a!0)*11 + (a!4)*13 of I# answer -> answer
