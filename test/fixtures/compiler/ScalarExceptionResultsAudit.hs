-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, ScopedTypeVariables #-}

-- |
-- Module      : ScalarExceptionResultsAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Fixed one-word results through the original exception/masking primops.
module ScalarExceptionResultsAudit where

import GHC.Exts

data Payload = Payload Int#

-- Every mask boundary returns the scalar itself, not a lifted wrapper. The
-- digits observe all three inner masks and restoration of the outer mask.
{-# OPAQUE normalInt #-}
normalInt :: Int# -> Int#
normalInt n = runRW# (\s0 ->
  case catch# (\s1 -> maskAsyncExceptions# (\s2 ->
    case getMaskingState# s2 of { (# s3, a #) ->
    case maskUninterruptible# (\s4 ->
      case getMaskingState# s4 of { (# s5, b #) ->
      unmaskAsyncExceptions# (\s6 ->
        case getMaskingState# s6 of { (# s7, c #) ->
          (# s7, n +# a +# b *# 3# +# c *# 9# #) }) s5 }) s3 of {
        (# s8, value #) -> case getMaskingState# s8 of {
          (# s9, restored #) -> (# s9, value +# restored *# 27# #) } }
    }) s1) (\(_ :: Payload) s -> (# s, -999# #)) s0 of {
      (# s10, value #) -> case getMaskingState# s10 of {
        (# _, outside #) -> value +# outside *# 81# } })

{-# OPAQUE normalWord #-}
normalWord :: Int# -> Int#
normalWord n = runRW# (\s0 ->
  case catch# (\s1 -> maskAsyncExceptions# (\s2 ->
    maskUninterruptible# (\s3 -> unmaskAsyncExceptions#
      (\s4 -> (# s4, plusWord# (int2Word# n) 57## #)) s3) s2) s1)
    (\(_ :: Payload) s -> (# s, 999## #)) s0 of {
      (# s5, value #) -> case getMaskingState# s5 of {
        (# _, outside #) -> word2Int# value +# outside *# 81# } })

{-# OPAQUE normalAddr #-}
normalAddr :: Int# -> Int#
normalAddr n = runRW# (\s0 ->
  case catch# (\s1 -> maskAsyncExceptions# (\s2 ->
    maskUninterruptible# (\s3 -> unmaskAsyncExceptions#
      (\s4 -> (# s4, plusAddr# "scalar-result"# (n +# 57#) #)) s3) s2) s1)
    (\(_ :: Payload) s -> (# s, "scalar-result"# #)) s0 of {
      (# s5, value #) -> case getMaskingState# s5 of {
        (# _, outside #) -> minusAddr# value "scalar-result"# +# outside *# 81# } })

-- Synchronous unwinding must restore the mask before the handler begins.
{-# OPAQUE throwInt #-}
throwInt :: Int# -> Int#
throwInt n = runRW# (\s0 ->
  case catch# (\s1 -> maskUninterruptible# (\s2 -> raiseIO# (Payload n) s2) s1)
    (\(Payload value) s3 -> case getMaskingState# s3 of {
      (# s4, mask #) -> (# s4, value +# mask *# 17# #) }) s0 of {
        (# s5, value #) -> case getMaskingState# s5 of {
          (# _, outside #) -> value +# outside *# 81# } })

{-# OPAQUE throwWord #-}
throwWord :: Int# -> Int#
throwWord n = runRW# (\s0 ->
  case catch# (\s1 -> maskUninterruptible# (\s2 -> raiseIO# (Payload n) s2) s1)
    (\(Payload value) s3 -> case getMaskingState# s3 of {
      (# s4, mask #) -> (# s4, int2Word# (value +# mask *# 17#) #) }) s0 of {
        (# s5, value #) -> case getMaskingState# s5 of {
          (# _, outside #) -> word2Int# value +# outside *# 81# } })

{-# OPAQUE throwAddr #-}
throwAddr :: Int# -> Int#
throwAddr n = runRW# (\s0 ->
  case catch# (\s1 -> maskUninterruptible# (\s2 -> raiseIO# (Payload n) s2) s1)
    (\(Payload value) s3 -> case getMaskingState# s3 of {
      (# s4, mask #) -> (# s4, plusAddr# "scalar-result"# (value +# mask *# 17#) #) }) s0 of {
        (# s5, value #) -> case getMaskingState# s5 of {
          (# _, outside #) -> minusAddr# value "scalar-result"# +# outside *# 81# } })

-- A deterministic self-directed asynchronous exception crosses a temporarily
-- unmasked scope inside an uninterruptible one. The prefix counter must be one
-- and the post-kill write must never execute, including on the compiled call.
{-# OPAQUE interruptInt #-}
interruptInt :: Int# -> Int#
interruptInt n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskUninterruptible# (\s3 -> unmaskAsyncExceptions# (\s4 ->
    case writeMutVar# count (Payload 1#) s4 of { s5 ->
    case myThreadId# s5 of { (# s6, tid #) ->
    case killThread# tid (Payload n) s6 of { s7 ->
    case writeMutVar# count (Payload 999#) s7 of { s8 -> (# s8, -999# #) } } } }) s3) s2)
    (\(Payload value) s9 -> case getMaskingState# s9 of {
      (# s10, mask #) -> (# s10, value +# mask *# 17# #) }) s1 of {
        (# s11, value #) -> case readMutVar# count s11 of { (# s12, Payload writes #) ->
        case getMaskingState# s12 of { (# _, outside #) ->
          value +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE interruptWord #-}
interruptWord :: Int# -> Int#
interruptWord n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskUninterruptible# (\s3 -> unmaskAsyncExceptions# (\s4 ->
    case writeMutVar# count (Payload 1#) s4 of { s5 ->
    case myThreadId# s5 of { (# s6, tid #) ->
    case killThread# tid (Payload n) s6 of { s7 ->
    case writeMutVar# count (Payload 999#) s7 of { s8 -> (# s8, 999## #) } } } }) s3) s2)
    (\(Payload value) s9 -> case getMaskingState# s9 of {
      (# s10, mask #) -> (# s10, int2Word# (value +# mask *# 17#) #) }) s1 of {
        (# s11, value #) -> case readMutVar# count s11 of { (# s12, Payload writes #) ->
        case getMaskingState# s12 of { (# _, outside #) ->
          word2Int# value +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE interruptAddr #-}
interruptAddr :: Int# -> Int#
interruptAddr n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskUninterruptible# (\s3 -> unmaskAsyncExceptions# (\s4 ->
    case writeMutVar# count (Payload 1#) s4 of { s5 ->
    case myThreadId# s5 of { (# s6, tid #) ->
    case killThread# tid (Payload n) s6 of { s7 ->
    case writeMutVar# count (Payload 999#) s7 of { s8 -> (# s8, "scalar-result"# #) } } } }) s3) s2)
    (\(Payload value) s9 -> case getMaskingState# s9 of {
      (# s10, mask #) -> (# s10, plusAddr# "scalar-result"# (value +# mask *# 17#) #) }) s1 of {
        (# s11, value #) -> case readMutVar# count s11 of { (# s12, Payload writes #) ->
        case getMaskingState# s12 of { (# _, outside #) ->
          minusAddr# value "scalar-result"# +# writes *# 101# +# outside *# 1009# } } } })
