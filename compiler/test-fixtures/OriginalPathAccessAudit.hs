-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : OriginalPathAccessAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for original path access audit Core and metadata.
module OriginalPathAccessAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import Foreign.C.Types (CChar)

type AccessCall = Addr# -> Int32# -> State# RealWorld -> (# State# RealWorld, Int32# #)

-- Both consumers specialize the unchanged installed FCallId. The ordinary
-- outer Int narrows exactly as GHC's CInt wrapper does before the foreign call.
pathAccess :: AccessCall -> Addr# -> Int# -> Int#
pathAccess call path mode = case call path (intToInt32# mode) realWorld# of
  (# _, status #) -> int32ToInt# status

nativePathAccess :: AccessCall -> Ptr CChar -> Int -> IO Int
nativePathAccess call (Ptr path) (I# mode) = IO (\state ->
  case call path (intToInt32# mode) state of
    (# next, status #) -> (# next, I# (int32ToInt# status) #))
