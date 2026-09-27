-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : OriginalPathModeAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for original path mode audit Core and metadata.
module OriginalPathModeAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import GHC.Word (Word32(W32#))
import Foreign.C.Types (CChar)

type ModeCall = Addr# -> Word32# -> State# RealWorld -> (# State# RealWorld, Int32# #)

-- The producer specializes both paths with unchanged installed FCallIds.
pathMkdir, pathChmod :: ModeCall -> Addr# -> Word32# -> Int#
pathMkdir call path mode = case call path mode realWorld# of
  (# _, status #) -> int32ToInt# status
pathChmod = pathMkdir

nativePathMkdir, nativePathChmod :: ModeCall -> Ptr CChar -> Word32 -> IO Int
nativePathMkdir call (Ptr path) (W32# mode) = IO (\state ->
  case call path mode state of
    (# next, status #) -> (# next, I# (int32ToInt# status) #))
nativePathChmod = nativePathMkdir
