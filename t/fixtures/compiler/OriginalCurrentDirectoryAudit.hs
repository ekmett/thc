-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : OriginalCurrentDirectoryAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for original current directory audit Core and metadata.
module OriginalCurrentDirectoryAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import Foreign.C.Types (CChar)

type ChdirCall = Addr# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type GetCwdCall = Addr# -> Word64# -> State# RealWorld -> (# State# RealWorld, Addr# #)

pathChdir :: ChdirCall -> Addr# -> Int#
pathChdir call path = case call path realWorld# of
  (# _, status #) -> int32ToInt# status

pathGetCwd :: GetCwdCall -> Addr# -> Word# -> Addr#
pathGetCwd call destination capacity =
  case call destination (wordToWord64# capacity) realWorld# of
    (# _, address #) -> address

nativePathChdir :: ChdirCall -> Ptr CChar -> IO Int
nativePathChdir call (Ptr path) = IO (\state ->
  case call path state of
    (# next, status #) -> (# next, I# (int32ToInt# status) #))

nativePathGetCwd :: GetCwdCall -> Ptr CChar -> Word -> IO (Ptr CChar)
nativePathGetCwd call (Ptr destination) (W# capacity) = IO (\state ->
  case call destination (wordToWord64# capacity) state of
    (# next, address #) -> (# next, Ptr address #))
