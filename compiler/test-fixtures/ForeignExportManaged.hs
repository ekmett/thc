-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, UnboxedTuples, NoImplicitPrelude #-}
module ForeignExportManaged where

import GHC.Exts
import GHC.Int (Int32(I32#))
import GHC.Word (Word64(W64#))
import GHC.IO (IO(IO))

foreign export ccall "thc_add_one" addOne :: Int32 -> Int32
foreign export ccall "thc_add_alias" addOne :: Int32 -> Int32
{-# NOINLINE addOne #-}
addOne :: Int32 -> Int32
addOne (I32# value) = I32# (plusInt32# value (intToInt32# 1#))

foreign export ccall "thc_float" floatValue :: Float -> Float
floatValue :: Float -> Float
floatValue (F# value) = F# (plusFloat# value 1.0#)

foreign export ccall "thc_word64" wordValue :: Word64 -> Word64
wordValue :: Word64 -> Word64
wordValue (W64# value) = W64# (plusWord64# value (wordToWord64# 1##))

foreign export ccall "thc_double" doubleValue :: Double -> Double
doubleValue :: Double -> Double
doubleValue (D# value) = D# (value +## 2.0##)

foreign export ccall "thc_constant" constant :: Int32
constant :: Int32
constant = I32# (intToInt32# 17#)

data Counter = Counter (MutVar# RealWorld Int32)
{-# NOINLINE counter #-}
counter :: Counter
counter = runRW# (\state -> case newMutVar# (I32# (intToInt32# 0#)) state of
  (# _, variable #) -> Counter variable)

foreign export ccall "thc_next" next :: Int32 -> IO Int32
foreign export ccall "thc_next_alias" next :: Int32 -> IO Int32
{-# NOINLINE next #-}
next :: Int32 -> IO Int32
next (I32# increment) = IO (\state -> case counter of
  Counter variable -> case readMutVar# variable state of
    (# state1, I32# old #) -> case plusInt32# old increment of
      value -> case writeMutVar# variable (I32# value) state1 of
        state2 -> (# state2, I32# value #))

foreign export ccall "thc_unit" unit :: IO ()
unit :: IO ()
unit = IO (\state -> (# state, () #))
