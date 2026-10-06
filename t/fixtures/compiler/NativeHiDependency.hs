-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ConstraintKinds #-}
{-# LANGUAGE GADTs #-}
{-# LANGUAGE ForeignFunctionInterface #-}
{-# LANGUAGE UnliftedFFITypes #-}
{-# LANGUAGE MagicHash #-}
{-# LANGUAGE UnboxedTuples #-}
-- A separately compiled retained module makes native .hi execution resolve a
-- state-token call and recover an address field. Consumed with NativeHiScalar.hi; no plugin or CBD.
module NativeHiDependency (marker, exchange, measured, advanceAndMeasure, foreignValue) where

import GHC.Exts (Int(I#), Int#, Addr#, State#, (+#), andI#, indexWord8OffAddr#, word8ToWord#, word2Int#, newMutVar#, writeMutVar#, readMutVar#, indexIntOffAddr#)
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import NativeHiClasses (Measure(..), Advance(..), Evidence(..))

data AddrRef = AddrRef Addr#

-- Preserve the constructor boundary so the consumer really reads an Addr# field.
{-# OPAQUE bytes #-}
bytes :: Int# -> AddrRef
bytes _ = AddrRef "A\0\xCE\xBB"#

{-# OPAQUE marker #-}
marker :: Int# -> State# s -> (# State# s, Int# #)
-- Primitive literals accept bytes only: UTF-8 A/NUL/lambda is 65, 0, 206, 187.
marker x state = case bytes x of
  AddrRef address -> (# state, x +# 7# +# word2Int# (word8ToWord# (indexWord8OffAddr# address (andI# x 3#))) #)

-- Input: two values of any lifted type. Output: an IO action returning the second.
-- The mutable cell makes executing the action observably different from loading it;
-- the imported IO declaration and polymorphic element types use ordinary type substitution.
{-# OPAQUE exchange #-}
exchange :: a -> a -> IO a
exchange before after = IO (\state ->
  case newMutVar# before state of
    (# allocated, cell #) -> case writeMutVar# cell after allocated of
      written -> readMutVar# cell written)

-- Keep imported dictionary selectors and polymorphic values across the module
-- boundary; the class provider intentionally carries declarations without Core.
{-# OPAQUE measured #-}
measured :: Measure a => a -> Int#
measured = measure

-- A stored constraint tuple keeps superclass projection inside an opaque call.
{-# OPAQUE measuredEvidence #-}
measuredEvidence :: Evidence (Measure a, Advance a) -> a -> Int#
measuredEvidence Evidence value = measured (advance value)

{-# OPAQUE advanceAndMeasure #-}
advanceAndMeasure :: Advance a => a -> IO Int
advanceAndMeasure before = case exchange before (advance before) of
  IO step -> IO (\state -> case step state of
    (# next, value #) -> (# next, I# (measuredEvidence Evidence value) #))

-- Ordinary package cbits and a static data label exercise the same native ABI
-- used by either Core input format, with no THC replacement for the C function.
foreign import ccall unsafe "native_hi_step" nativeStep :: Int# -> Int#
foreign import ccall unsafe "&native_hi_constant" nativeConstant :: Ptr ()

{-# OPAQUE foreignValue #-}
foreignValue :: Int# -> Int#
foreignValue value = case nativeConstant of
  Ptr address -> nativeStep value +# indexIntOffAddr# address 0#
