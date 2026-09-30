-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : WindowsSharedCAFCalls
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1; native Windows interfaces
--
-- Typed consumers of the unchanged pinned Windows shared-CAF declarations.
module WindowsSharedCAFCalls where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))

-- The producer supplies the actual interface FCallId, never a replacement.
type StoreCall = Addr# -> State# RealWorld -> (# State# RealWorld, Addr# #)

pendingDelays, ioManagerThread, prodding :: StoreCall -> Addr# -> Addr#
pendingDelays call pointer = case call pointer realWorld# of (# _, result #) -> result
ioManagerThread = pendingDelays
prodding = pendingDelays

nativePendingDelays, nativeIoManagerThread, nativeProdding :: StoreCall -> Ptr () -> IO (Ptr ())
nativePendingDelays call (Ptr pointer) = IO (\state ->
  case call pointer state of (# next, result #) -> (# next, Ptr result #))
nativeIoManagerThread = nativePendingDelays
nativeProdding = nativePendingDelays
