-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : OriginalDirectoryStreamsAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for original directory streams audit Core and metadata.
module OriginalDirectoryStreamsAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import Foreign.C.Types (CChar)

type OpenCall = Addr# -> State# RealWorld -> (# State# RealWorld, Addr# #)
type FdOpenCall = Int32# -> State# RealWorld -> (# State# RealWorld, Addr# #)
type CloseCall = Addr# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type ReadCall = Addr# -> Addr# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type FreeCall = Addr# -> State# RealWorld -> (# State# RealWorld #)

directoryOpen :: OpenCall -> Addr# -> Addr#
directoryOpen call path = case call path realWorld# of (# _, pointer #) -> pointer
directoryFdOpen :: FdOpenCall -> Int# -> Addr#
directoryFdOpen call fd = case call (intToInt32# fd) realWorld# of (# _, pointer #) -> pointer
directoryClose :: CloseCall -> Addr# -> Int#
directoryClose call pointer = case call pointer realWorld# of (# _, status #) -> int32ToInt# status
directoryRead :: ReadCall -> Addr# -> Addr# -> Int#
directoryRead call pointer output = case call pointer output realWorld# of (# _, status #) -> int32ToInt# status
directoryName :: OpenCall -> Addr# -> Addr#
directoryName call pointer = case call pointer realWorld# of (# _, name #) -> name
directoryFree :: FreeCall -> Addr# -> Int#
directoryFree call pointer = case call pointer realWorld# of (# _ #) -> 0#

nativeDirectoryOpen :: OpenCall -> Ptr CChar -> IO (Ptr ())
nativeDirectoryOpen call (Ptr path) = IO (\state ->
  case call path state of (# next, pointer #) -> (# next, Ptr pointer #))
nativeDirectoryFdOpen :: FdOpenCall -> Int -> IO (Ptr ())
nativeDirectoryFdOpen call (I# fd) = IO (\state ->
  case call (intToInt32# fd) state of (# next, pointer #) -> (# next, Ptr pointer #))
nativeDirectoryClose :: CloseCall -> Ptr () -> IO Int
nativeDirectoryClose call (Ptr pointer) = IO (\state ->
  case call pointer state of (# next, status #) -> (# next, I# (int32ToInt# status) #))
nativeDirectoryRead :: ReadCall -> Ptr () -> Ptr (Ptr ()) -> IO Int
nativeDirectoryRead call (Ptr pointer) (Ptr output) = IO (\state ->
  case call pointer output state of (# next, status #) -> (# next, I# (int32ToInt# status) #))
nativeDirectoryName :: OpenCall -> Ptr () -> IO (Ptr CChar)
nativeDirectoryName call (Ptr pointer) = IO (\state ->
  case call pointer state of (# next, name #) -> (# next, Ptr name #))
nativeDirectoryFree :: FreeCall -> Ptr () -> IO ()
nativeDirectoryFree call (Ptr pointer) = IO (\state ->
  case call pointer state of (# next #) -> (# next, () #))
