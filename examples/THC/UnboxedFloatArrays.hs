{-# LANGUAGE MagicHash #-}
module THC.UnboxedFloatArrays where

import GHC.Exts
import Data.Array.Unboxed (UArray, accumArray, (!))
import Data.Array.ST (runSTUArray, newArray, readArray, writeArray)

-- Checked public APIs with provably safe constant bounds and indices. Bounded
-- input and dyadic constants keep every Float operation exact and the final
-- conversion finite/in-range. These examples have no optimizer fences.
unboxedFloatAccum :: Int# -> Int#
unboxedFloatAccum raw =
  let x = F# (minusFloat# (int2Float# (andI# raw 65535#)) 32768.0#)
      a = accumArray (+) x (-3,4)
            [(-3,3.25),(0,5.5),(-3,-2.0),(4,x*0.5)] :: UArray Int Float
  in case ((a!(-3))*7 + (a!0)*11 + (a!4)*13)*4 of
       F# answer -> float2Int# answer

unboxedFloatST :: Int# -> Int#
unboxedFloatST raw =
  let x = F# (minusFloat# (int2Float# (andI# raw 65535#)) 32768.0#)
      a :: UArray Int Float
      a = runSTUArray $ do
        m <- newArray (-3,4) x
        before <- readArray m (-3)
        writeArray m 0 (before+0.25)
        after <- readArray m 0
        writeArray m 4 (3*after-x+0.5)
        pure m
  in case ((a!(-3))*7 + (a!0)*11 + (a!4)*13)*4 of
       F# answer -> float2Int# answer
