-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module THC.UnboxedDoubleArrays where

import GHC.Exts
import Data.Array.Unboxed (UArray, accumArray, (!))
import Data.Array.ST (runSTUArray, newArray, readArray, writeArray)

-- Ordinary checked public APIs, with bounds/indices that GHC can prove safe.
-- The raw Int is bounded before conversion. All arithmetic is exact dyadic
-- arithmetic and the final conversion is finite/in-range for every Int input.
unboxedDoubleAccum :: Int# -> Int#
unboxedDoubleAccum raw =
  let x = D# (int2Double# (andI# raw 65535#) -## 32768.0##)
      a = accumArray (+) x (-3,4)
            [(-3,3.25),(0,5.5),(-3,-2.0),(4,x*0.5)] :: UArray Int Double
  in case ((a!(-3))*7 + (a!0)*11 + (a!4)*13)*4 of
       D# answer -> double2Int# answer

unboxedDoubleST :: Int# -> Int#
unboxedDoubleST raw =
  let x = D# (int2Double# (andI# raw 65535#) -## 32768.0##)
      a :: UArray Int Double
      a = runSTUArray $ do
        m <- newArray (-3,4) x
        before <- readArray m (-3)
        writeArray m 0 (before+0.25)
        after <- readArray m 0
        writeArray m 4 (3*after-x+0.5)
        pure m
  in case ((a!(-3))*7 + (a!0)*11 + (a!4)*13)*4 of
       D# answer -> double2Int# answer
