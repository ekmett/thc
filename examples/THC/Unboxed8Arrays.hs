{-# LANGUAGE MagicHash #-}
module THC.Unboxed8Arrays where

import GHC.Exts
import Data.Int (Int8)
import Data.Word (Word8)
import Data.Array.Unboxed (UArray, accumArray, (!))
import Data.Array.ST (runSTUArray, newArray, readArray, writeArray)

-- Genuine checked public APIs with fixed safe indices. Arithmetic in each
-- array wraps at 8 bits; each observed cell widens before the Int checksum.
unboxedInt8Accum :: Int# -> Int#
unboxedInt8Accum raw =
  let x = fromIntegral (I# raw) :: Int8
      a = accumArray (+) x (-3,4) [(-3,3),(0,5),(-3,-2),(4,x)] :: UArray Int Int8
  in case (fromIntegral (a!(-3))*7 + fromIntegral (a!0)*11 + fromIntegral (a!4)*13 :: Int) of
       I# answer -> answer

unboxedInt8ST :: Int# -> Int#
unboxedInt8ST raw =
  let x = fromIntegral (I# raw) :: Int8
      a :: UArray Int Int8
      a = runSTUArray $ do
        m <- newArray (-3,4) x
        before <- readArray m (-3)
        writeArray m 0 (before+7)
        after <- readArray m 0
        writeArray m 4 (3*after-x)
        pure m
  in case (fromIntegral (a!(-3))*7 + fromIntegral (a!0)*11 + fromIntegral (a!4)*13 :: Int) of
       I# answer -> answer

unboxedWord8Accum :: Int# -> Int#
unboxedWord8Accum raw =
  let x = fromIntegral (I# raw) :: Word8
      -- Explicitly convert the negative Int input to exercise Word8 wraparound.
      a = accumArray (+) x (-3,4) [(-3,3),(0,5),(-3,fromIntegral (-2 :: Int)),(4,x)] :: UArray Int Word8
  in case (fromIntegral (a!(-3))*7 + fromIntegral (a!0)*11 + fromIntegral (a!4)*13 :: Int) of
       I# answer -> answer

unboxedWord8ST :: Int# -> Int#
unboxedWord8ST raw =
  let x = fromIntegral (I# raw) :: Word8
      a :: UArray Int Word8
      a = runSTUArray $ do
        m <- newArray (-3,4) x
        before <- readArray m (-3)
        writeArray m 0 (before+7)
        after <- readArray m 0
        writeArray m 4 (3*after-x)
        pure m
  in case (fromIntegral (a!(-3))*7 + fromIntegral (a!0)*11 + fromIntegral (a!4)*13 :: Int) of
       I# answer -> answer
