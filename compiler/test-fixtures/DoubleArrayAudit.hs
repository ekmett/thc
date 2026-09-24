-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module DoubleArrayAudit where

import GHC.Exts

-- Retain genuine Double# memory operations and an actual State#/Double# tuple
-- return. These movement-only helpers deliberately have no floating arguments
-- or arithmetic; they do not fence the public library examples.
{-# OPAQUE readDoubleSlot #-}
readDoubleSlot :: MutableByteArray# s -> State# s -> (# State# s, Double# #)
readDoubleSlot a s = readDoubleArray# a 0# s

{-# OPAQUE indexDoubleSlot #-}
indexDoubleSlot :: ByteArray# -> Double#
indexDoubleSlot a = indexDoubleArray# a 0#

-- Int and Double views share the same native-endian bytes. Exact movement is
-- tested for finite encodings, signed zeros/infinities and quiet NaN payloads.
-- Signaling NaNs are excluded: mere floating copies may quiet them on a JVM.
moveDoubleBits :: Int# -> Int#
moveDoubleBits bits = runRW# (\s0 ->
  case newByteArray# 8# s0 of { (# s1, a #) ->
  case writeIntArray# a 0# bits s1 of { s2 ->
  case readDoubleSlot a s2 of { (# s3, value #) ->
  case newByteArray# 8# s3 of { (# s4, b #) ->
  case writeDoubleArray# b 0# value s4 of { s5 ->
  case unsafeFreezeByteArray# b s5 of { (# _, frozen #) ->
    indexIntArray# frozen 0#
  } } } } } })

indexDoubleBits :: Int# -> Int#
indexDoubleBits bits = runRW# (\s0 ->
  case newByteArray# 8# s0 of { (# s1, a #) ->
  case writeIntArray# a 0# bits s1 of { s2 ->
  case unsafeFreezeByteArray# a s2 of { (# s3, frozen #) ->
  case indexDoubleSlot frozen of { value ->
  case newByteArray# 8# s3 of { (# s4, b #) ->
  case writeDoubleArray# b 0# value s4 of { s5 ->
  case unsafeFreezeByteArray# b s5 of { (# _, moved #) ->
    indexIntArray# moved 0#
  } } } } } } })
