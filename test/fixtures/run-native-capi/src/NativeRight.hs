-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CApiFFI, ForeignFunctionInterface, MagicHash #-}
module NativeRight (rightHeader, firstAgain, second, rightProbe#, firstAgainProbe#, secondProbe#) where

import GHC.Exts (Int#, int2Word#, wordToWord64#, word64ToWord#, word2Int#)
import GHC.Word (Word64(W64#))

foreign import capi unsafe "right.h private_helper"
  rightHeader :: Word64 -> Word64
foreign import ccall unsafe "native_first"
  firstAgain :: Word64 -> Word64
foreign import ccall unsafe "native_second"
  second :: Word64 -> Word64

rightProbe# :: Int# -> Int#
rightProbe# value = case rightHeader (W64# (wordToWord64# (int2Word# value))) of
  W64# result -> word2Int# (word64ToWord# result)
firstAgainProbe# :: Int# -> Int#
firstAgainProbe# value = case firstAgain (W64# (wordToWord64# (int2Word# value))) of
  W64# result -> word2Int# (word64ToWord# result)
secondProbe# :: Int# -> Int#
secondProbe# value = case second (W64# (wordToWord64# (int2Word# value))) of
  W64# result -> word2Int# (word64ToWord# result)
