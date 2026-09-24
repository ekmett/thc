-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module THC.UnboxedWordArrays where

import GHC.Exts
import Data.Array.Unboxed (UArray, accumArray, (!))
import Data.Array.ST (runSTUArray, newArray, readArray, writeArray)

-- Machine Word (WordRep), not Word64Rep. All arithmetic wraps modulo 2^64
-- under the preparation contract. Fixed checked indices avoid cold index errors.
unboxedWordAccum :: Int# -> Int#
unboxedWordAccum raw =
  let x = fromIntegral (I# raw) :: Word
      a = accumArray (+) x (-3,4) [(-3,3),(0,5),(-3,maxBound-1),(4,x)] :: UArray Int Word
  in case (a!(-3))*7 + (a!0)*11 + (a!4)*13 of
       W# answer -> word2Int# answer

unboxedWordST :: Int# -> Int#
unboxedWordST raw =
  let x = fromIntegral (I# raw) :: Word
      a :: UArray Int Word
      a = runSTUArray $ do
        m <- newArray (-3,4) x
        before <- readArray m (-3)
        writeArray m 0 (before+7)
        after <- readArray m 0
        writeArray m 4 (3*after-x)
        pure m
  in case (a!(-3))*7 + (a!0)*11 + (a!4)*13 of
       W# answer -> word2Int# answer
