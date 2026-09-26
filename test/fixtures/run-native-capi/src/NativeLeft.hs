-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CApiFFI, ForeignFunctionInterface, MagicHash #-}
module NativeLeft (leftHeader, first, leftProbe#, firstProbe#) where

import GHC.Exts (Int#, int2Word#, wordToWord64#, word64ToWord#, word2Int#)
import GHC.Word (Word64(W64#))

foreign import capi unsafe "left.h private_helper"
  leftHeader :: Word64 -> Word64
foreign import ccall unsafe "native_first"
  first :: Word64 -> Word64

leftProbe# :: Int# -> Int#
leftProbe# value = case leftHeader (W64# (wordToWord64# (int2Word# value))) of
  W64# result -> word2Int# (word64ToWord# result)
firstProbe# :: Int# -> Int#
firstProbe# value = case first (W64# (wordToWord64# (int2Word# value))) of
  W64# result -> word2Int# (word64ToWord# result)
