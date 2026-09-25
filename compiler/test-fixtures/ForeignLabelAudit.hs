-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, UnboxedTuples #-}
module ForeignLabelAudit (poolRelease, backtraceFree, freeFunction, enabledCapabilities,
  WeakBox(..), makePlain, attachPool, finalizePlain) where

import Foreign.Ptr (FunPtr, Ptr)
import Data.Word (Word32)
import GHC.Exts (Int(I#), Weak#, addCFinalizerToWeak#, finalizeWeak#,
  mkWeakNoFinalizer#, nullAddr#)
import GHC.IO (IO(..))
import GHC.Ptr (FunPtr(..), Ptr(..))

foreign import ccall unsafe "&libdwPoolRelease" poolRelease :: FunPtr (Ptr () -> IO ())
foreign import ccall unsafe "&backtraceFree" backtraceFree :: FunPtr (Ptr () -> IO ())
foreign import ccall unsafe "stdlib.h &free" freeFunction :: FunPtr (Ptr () -> IO ())
foreign import ccall unsafe "&enabled_capabilities" enabledCapabilities :: Ptr Word32

data WeakBox = WeakBox (Weak# ())

{-# NOINLINE makePlain #-}
makePlain :: a -> IO WeakBox
makePlain key = IO $ \s -> case mkWeakNoFinalizer# key () s of
  (# s', weak #) -> (# s', WeakBox weak #)

{-# NOINLINE attachPool #-}
attachPool :: WeakBox -> Ptr () -> IO Int
attachPool (WeakBox weak) (Ptr object) = case poolRelease of
  FunPtr function -> IO $ \s ->
    case addCFinalizerToWeak# function object 0# nullAddr# weak s of
      (# s', flag #) -> (# s', I# flag #)

{-# NOINLINE finalizePlain #-}
finalizePlain :: WeakBox -> IO Int
finalizePlain (WeakBox weak) = IO $ \s -> case finalizeWeak# weak s of
  (# s', flag, _ #) -> (# s', I# flag #)
