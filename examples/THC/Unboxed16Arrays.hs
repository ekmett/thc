{-# LANGUAGE MagicHash #-}
module THC.Unboxed16Arrays where

import GHC.Exts
import Data.Int (Int16)
import Data.Word (Word16)
import Data.Array.Unboxed (UArray, accumArray, (!))
import Data.Array.ST (runSTUArray, newArray, readArray, writeArray)

-- Genuine checked public APIs with fixed safe indices. Arithmetic in each
-- array wraps at 16 bits; each observed cell widens before the Int checksum.
unboxedInt16Accum :: Int# -> Int#
unboxedInt16Accum raw =
  let x = fromIntegral (I# raw) :: Int16
      a = accumArray (+) x (-3,4) [(-3,3),(0,5),(-3,-2),(4,x)] :: UArray Int Int16
  in case (fromIntegral (a!(-3))*7 + fromIntegral (a!0)*11 + fromIntegral (a!4)*13 :: Int) of
       I# answer -> answer

unboxedInt16ST :: Int# -> Int#
unboxedInt16ST raw =
  let x = fromIntegral (I# raw) :: Int16
      a :: UArray Int Int16
      a = runSTUArray $ do
        m <- newArray (-3,4) x
        before <- readArray m (-3)
        writeArray m 0 (before+7)
        after <- readArray m 0
        writeArray m 4 (3*after-x)
        pure m
  in case (fromIntegral (a!(-3))*7 + fromIntegral (a!0)*11 + fromIntegral (a!4)*13 :: Int) of
       I# answer -> answer

unboxedWord16Accum :: Int# -> Int#
unboxedWord16Accum raw =
  let x = fromIntegral (I# raw) :: Word16
      -- Explicitly convert the negative Int input to exercise Word16 wraparound.
      a = accumArray (+) x (-3,4) [(-3,3),(0,5),(-3,fromIntegral (-2 :: Int)),(4,x)] :: UArray Int Word16
  in case (fromIntegral (a!(-3))*7 + fromIntegral (a!0)*11 + fromIntegral (a!4)*13 :: Int) of
       I# answer -> answer

unboxedWord16ST :: Int# -> Int#
unboxedWord16ST raw =
  let x = fromIntegral (I# raw) :: Word16
      a :: UArray Int Word16
      a = runSTUArray $ do
        m <- newArray (-3,4) x
        before <- readArray m (-3)
        writeArray m 0 (before+7)
        after <- readArray m 0
        writeArray m 4 (3*after-x)
        pure m
  in case (fromIntegral (a!(-3))*7 + fromIntegral (a!0)*11 + fromIntegral (a!4)*13 :: Int) of
       I# answer -> answer
