-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : WindowsDirectoryAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Windows; GHC FFI, Windows SDK and hsc2hs
--
-- Compiler fixture for windows directory audit Core and metadata.
module WindowsDirectoryAudit where

#include <windows.h>
import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import Data.Word (Word16)

-- Typed consumers only. The producer supplies real original Win32 FCallIds.
type FirstCall = Addr## -> Addr## -> State## RealWorld -> (## State## RealWorld, Addr## ##)
type NextCall = Addr## -> Addr## -> State## RealWorld -> (## State## RealWorld, Int## ##)
type CloseCall = Addr## -> State## RealWorld -> (## State## RealWorld, Int## ##)
type ErrorCall = State## RealWorld -> (## State## RealWorld, Word32## ##)

directoryFirst :: FirstCall -> Addr## -> Addr## -> Addr##
directoryFirst call path buffer = case call path buffer realWorld## of (## _, handle ##) -> handle
directoryNext :: NextCall -> Addr## -> Addr## -> Int##
directoryNext call handle buffer = case call handle buffer realWorld## of (## _, result ##) -> result
directoryClose :: CloseCall -> Addr## -> Int##
directoryClose call handle = case call handle realWorld## of (## _, result ##) -> result
directoryError :: ErrorCall -> Int## -> Word##
directoryError call _ = case call realWorld## of (## _, result ##) -> word32ToWord## result

nativeDirectoryFirst :: FirstCall -> Ptr Word16 -> Ptr () -> IO (Ptr ())
nativeDirectoryFirst call (Ptr path) (Ptr buffer) = IO (\state ->
  case call path buffer state of (## next, handle ##) -> (## next, Ptr handle ##))
nativeDirectoryNext :: NextCall -> Ptr () -> Ptr () -> IO Int
nativeDirectoryNext call (Ptr handle) (Ptr buffer) = IO (\state ->
  case call handle buffer state of (## next, result ##) -> (## next, I## result ##))
nativeDirectoryClose :: CloseCall -> Ptr () -> IO Int
nativeDirectoryClose call (Ptr handle) = IO (\state ->
  case call handle state of (## next, result ##) -> (## next, I## result ##))
nativeDirectoryError :: ErrorCall -> IO Word
nativeDirectoryError call = IO (\state ->
  case call state of (## next, result ##) -> (## next, W## (word32ToWord## result) ##))

nativeLayout :: (Int, Int, Int, Int)
nativeLayout = (#{size WIN32_FIND_DATAW}, #{offset WIN32_FIND_DATAW, cFileName},
                #{const MAX_PATH}, #{const ERROR_NO_MORE_FILES})
