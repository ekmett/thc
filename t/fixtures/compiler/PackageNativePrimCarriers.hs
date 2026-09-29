-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE GHCForeignImportPrim, MagicHash, UnboxedTuples, UnliftedFFITypes #-}

-- Compile-only declaration controls. These are genuine stock GHC prim imports,
-- not native adapters; the fixture never manufactures or dereferences a stack.
module PackageNativePrimCarriers where

import GHC.Exts

foreign import prim "getWordzh"
  stackWord :: StackSnapshot# -> Word# -> Word#
foreign import prim "getInfoTableAddrszh"
  stackAddresses :: StackSnapshot# -> Word# -> (# Addr#, Addr# #)
foreign import prim "advanceStackFrameLocationzh"
  stackAdvance :: StackSnapshot# -> Word# -> (# StackSnapshot#, Word#, Int# #)
foreign import prim "stg_sendCloneStackMessagezh"
  stackMessage :: ThreadId# -> StablePtr# (MVar# RealWorld Any) -> State# RealWorld
    -> (# State# RealWorld, (# #) #)
