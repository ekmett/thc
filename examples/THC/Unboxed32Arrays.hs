-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module THC.Unboxed32Arrays where

import GHC.Exts
import Data.Int (Int32)
import Data.Word (Word32)
import Data.Array.Unboxed (UArray, accumArray, (!))
import Data.Array.ST (runSTUArray, newArray, readArray, writeArray)

-- Genuine checked public APIs with fixed safe indices. Arithmetic in each
-- array wraps at 32 bits; each observed cell widens before the Int checksum.
unboxedInt32Accum :: Int# -> Int#
unboxedInt32Accum raw =
  let x = fromIntegral (I# raw) :: Int32
      a = accumArray (+) x (-3,4) [(-3,3),(0,5),(-3,-2),(4,x)] :: UArray Int Int32
  in case (fromIntegral (a!(-3))*7 + fromIntegral (a!0)*11 + fromIntegral (a!4)*13 :: Int) of
       I# answer -> answer

unboxedInt32ST :: Int# -> Int#
unboxedInt32ST raw =
  let x = fromIntegral (I# raw) :: Int32
      a :: UArray Int Int32
      a = runSTUArray $ do
        m <- newArray (-3,4) x
        before <- readArray m (-3)
        writeArray m 0 (before+7)
        after <- readArray m 0
        writeArray m 4 (3*after-x)
        pure m
  in case (fromIntegral (a!(-3))*7 + fromIntegral (a!0)*11 + fromIntegral (a!4)*13 :: Int) of
       I# answer -> answer

unboxedWord32Accum :: Int# -> Int#
unboxedWord32Accum raw =
  let x = fromIntegral (I# raw) :: Word32
      -- Explicitly convert the negative Int input to exercise Word32 wraparound.
      a = accumArray (+) x (-3,4) [(-3,3),(0,5),(-3,fromIntegral (-2 :: Int)),(4,x)] :: UArray Int Word32
  in case (fromIntegral (a!(-3))*7 + fromIntegral (a!0)*11 + fromIntegral (a!4)*13 :: Int) of
       I# answer -> answer

unboxedWord32ST :: Int# -> Int#
unboxedWord32ST raw =
  let x = fromIntegral (I# raw) :: Word32
      a :: UArray Int Word32
      a = runSTUArray $ do
        m <- newArray (-3,4) x
        before <- readArray m (-3)
        writeArray m 0 (before+7)
        after <- readArray m 0
        writeArray m 4 (3*after-x)
        pure m
  in case (fromIntegral (a!(-3))*7 + fromIntegral (a!0)*11 + fromIntegral (a!4)*13 :: Int) of
       I# answer -> answer
