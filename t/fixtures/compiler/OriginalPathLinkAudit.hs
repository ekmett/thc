-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : OriginalPathLinkAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for original path link audit Core and metadata.
module OriginalPathLinkAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import GHC.Word (Word64(W64#))
import Foreign.C.Types (CChar)

type SymlinkCall = Addr# -> Addr# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type ReadlinkCall = Addr# -> Addr# -> Word64# -> State# RealWorld -> (# State# RealWorld, Int32# #)

-- These distinct formals must match the installed declarations exactly. In
-- particular Unix readlink returns CInt, although native ssize_t is wider.
pathSymlink :: SymlinkCall -> Addr# -> Addr# -> Int#
pathSymlink call target path = case call target path realWorld# of
  (# _, status #) -> int32ToInt# status

pathRename :: SymlinkCall -> Addr# -> Addr# -> Int#
pathRename = pathSymlink

pathReadlink :: ReadlinkCall -> Addr# -> Addr# -> Word64# -> Int#
pathReadlink call path buffer capacity = case call path buffer capacity realWorld# of
  (# _, status #) -> int32ToInt# status

nativePathSymlink :: SymlinkCall -> Ptr CChar -> Ptr CChar -> IO Int
nativePathSymlink call (Ptr target) (Ptr path) = IO (\state ->
  case call target path state of
    (# next, status #) -> (# next, I# (int32ToInt# status) #))

nativePathRename :: SymlinkCall -> Ptr CChar -> Ptr CChar -> IO Int
nativePathRename = nativePathSymlink

nativePathReadlink :: ReadlinkCall -> Ptr CChar -> Ptr () -> Word64 -> IO Int
nativePathReadlink call (Ptr path) (Ptr buffer) (W64# capacity) = IO (\state ->
  case call path buffer capacity state of
    (# next, status #) -> (# next, I# (int32ToInt# status) #))
