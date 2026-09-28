-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, UnboxedSums, ScopedTypeVariables #-}
{-# LANGUAGE UnliftedDatatypes, StandaloneKindSignatures #-}

-- |
-- Module      : ExceptionResultLayoutsAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Concrete result layouts, independently of the exception payload levity.
-- GHC's continuation RTS does not promise multi-register return transport;
-- the native driver retains observations separately from a scalar model.
module ExceptionResultLayoutsAudit where

import GHC.Exts

data Payload = Payload Int#
type Product :: UnliftedType
data Product = Product Int# Payload

{-# OPAQUE bottom #-}
bottom :: Payload
bottom = bottom

-- Each entry counts its completed prefix, observes handler masking and outer
-- restoration, and leaves a sentinel write after self-delivery unreachable.

{-# OPAQUE int8Result #-}
int8Result :: Int# -> Int# -> Int#
int8Result mode n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskAsyncExceptions# (\s3 ->
    maskUninterruptible# (\s4 -> unmaskAsyncExceptions# (\s5 ->
      case (case readMutVar# count s5 of { (# s5a, Payload completed #) ->
        writeMutVar# count (Payload (completed +# 1#)) s5a }) of { s6 ->
        case mode of {
          1# -> raiseIO# (Payload n) s6;
          2# -> case myThreadId# s6 of { (# s7, tid #) ->
            case killThread# tid (Payload n) s7 of { s8 ->
            case writeMutVar# count (Payload 999#) s8 of { s9 ->
              case n of { v -> (# s9, intToInt8# v #) } } } };
          _ -> case n +# 7# of { v -> (# s6, intToInt8# v #) } }
      }) s4) s3) s2)
    (\(Payload caught) s10 -> case getMaskingState# s10 of {
      (# s11, mask #) -> case caught +# mask *# 17# of { v -> (# s11, intToInt8# v #) } }) s1 of {
    (# s12, value #) -> case readMutVar# count s12 of { (# s13, Payload writes #) ->
    case getMaskingState# s13 of { (# _, outside #) ->
      (int8ToInt# value) +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE word8Result #-}
word8Result :: Int# -> Int# -> Int#
word8Result mode n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskAsyncExceptions# (\s3 ->
    maskUninterruptible# (\s4 -> unmaskAsyncExceptions# (\s5 ->
      case (case readMutVar# count s5 of { (# s5a, Payload completed #) ->
        writeMutVar# count (Payload (completed +# 1#)) s5a }) of { s6 ->
        case mode of {
          1# -> raiseIO# (Payload n) s6;
          2# -> case myThreadId# s6 of { (# s7, tid #) ->
            case killThread# tid (Payload n) s7 of { s8 ->
            case writeMutVar# count (Payload 999#) s8 of { s9 ->
              case n of { v -> (# s9, wordToWord8# (int2Word# v) #) } } } };
          _ -> case n +# 7# of { v -> (# s6, wordToWord8# (int2Word# v) #) } }
      }) s4) s3) s2)
    (\(Payload caught) s10 -> case getMaskingState# s10 of {
      (# s11, mask #) -> case caught +# mask *# 17# of { v -> (# s11, wordToWord8# (int2Word# v) #) } }) s1 of {
    (# s12, value #) -> case readMutVar# count s12 of { (# s13, Payload writes #) ->
    case getMaskingState# s13 of { (# _, outside #) ->
      (word2Int# (word8ToWord# value)) +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE int16Result #-}
int16Result :: Int# -> Int# -> Int#
int16Result mode n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskAsyncExceptions# (\s3 ->
    maskUninterruptible# (\s4 -> unmaskAsyncExceptions# (\s5 ->
      case (case readMutVar# count s5 of { (# s5a, Payload completed #) ->
        writeMutVar# count (Payload (completed +# 1#)) s5a }) of { s6 ->
        case mode of {
          1# -> raiseIO# (Payload n) s6;
          2# -> case myThreadId# s6 of { (# s7, tid #) ->
            case killThread# tid (Payload n) s7 of { s8 ->
            case writeMutVar# count (Payload 999#) s8 of { s9 ->
              case n of { v -> (# s9, intToInt16# v #) } } } };
          _ -> case n +# 7# of { v -> (# s6, intToInt16# v #) } }
      }) s4) s3) s2)
    (\(Payload caught) s10 -> case getMaskingState# s10 of {
      (# s11, mask #) -> case caught +# mask *# 17# of { v -> (# s11, intToInt16# v #) } }) s1 of {
    (# s12, value #) -> case readMutVar# count s12 of { (# s13, Payload writes #) ->
    case getMaskingState# s13 of { (# _, outside #) ->
      (int16ToInt# value) +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE word16Result #-}
word16Result :: Int# -> Int# -> Int#
word16Result mode n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskAsyncExceptions# (\s3 ->
    maskUninterruptible# (\s4 -> unmaskAsyncExceptions# (\s5 ->
      case (case readMutVar# count s5 of { (# s5a, Payload completed #) ->
        writeMutVar# count (Payload (completed +# 1#)) s5a }) of { s6 ->
        case mode of {
          1# -> raiseIO# (Payload n) s6;
          2# -> case myThreadId# s6 of { (# s7, tid #) ->
            case killThread# tid (Payload n) s7 of { s8 ->
            case writeMutVar# count (Payload 999#) s8 of { s9 ->
              case n of { v -> (# s9, wordToWord16# (int2Word# v) #) } } } };
          _ -> case n +# 7# of { v -> (# s6, wordToWord16# (int2Word# v) #) } }
      }) s4) s3) s2)
    (\(Payload caught) s10 -> case getMaskingState# s10 of {
      (# s11, mask #) -> case caught +# mask *# 17# of { v -> (# s11, wordToWord16# (int2Word# v) #) } }) s1 of {
    (# s12, value #) -> case readMutVar# count s12 of { (# s13, Payload writes #) ->
    case getMaskingState# s13 of { (# _, outside #) ->
      (word2Int# (word16ToWord# value)) +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE int32Result #-}
int32Result :: Int# -> Int# -> Int#
int32Result mode n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskAsyncExceptions# (\s3 ->
    maskUninterruptible# (\s4 -> unmaskAsyncExceptions# (\s5 ->
      case (case readMutVar# count s5 of { (# s5a, Payload completed #) ->
        writeMutVar# count (Payload (completed +# 1#)) s5a }) of { s6 ->
        case mode of {
          1# -> raiseIO# (Payload n) s6;
          2# -> case myThreadId# s6 of { (# s7, tid #) ->
            case killThread# tid (Payload n) s7 of { s8 ->
            case writeMutVar# count (Payload 999#) s8 of { s9 ->
              case n of { v -> (# s9, intToInt32# v #) } } } };
          _ -> case n +# 7# of { v -> (# s6, intToInt32# v #) } }
      }) s4) s3) s2)
    (\(Payload caught) s10 -> case getMaskingState# s10 of {
      (# s11, mask #) -> case caught +# mask *# 17# of { v -> (# s11, intToInt32# v #) } }) s1 of {
    (# s12, value #) -> case readMutVar# count s12 of { (# s13, Payload writes #) ->
    case getMaskingState# s13 of { (# _, outside #) ->
      (int32ToInt# value) +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE word32Result #-}
word32Result :: Int# -> Int# -> Int#
word32Result mode n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskAsyncExceptions# (\s3 ->
    maskUninterruptible# (\s4 -> unmaskAsyncExceptions# (\s5 ->
      case (case readMutVar# count s5 of { (# s5a, Payload completed #) ->
        writeMutVar# count (Payload (completed +# 1#)) s5a }) of { s6 ->
        case mode of {
          1# -> raiseIO# (Payload n) s6;
          2# -> case myThreadId# s6 of { (# s7, tid #) ->
            case killThread# tid (Payload n) s7 of { s8 ->
            case writeMutVar# count (Payload 999#) s8 of { s9 ->
              case n of { v -> (# s9, wordToWord32# (int2Word# v) #) } } } };
          _ -> case n +# 7# of { v -> (# s6, wordToWord32# (int2Word# v) #) } }
      }) s4) s3) s2)
    (\(Payload caught) s10 -> case getMaskingState# s10 of {
      (# s11, mask #) -> case caught +# mask *# 17# of { v -> (# s11, wordToWord32# (int2Word# v) #) } }) s1 of {
    (# s12, value #) -> case readMutVar# count s12 of { (# s13, Payload writes #) ->
    case getMaskingState# s13 of { (# _, outside #) ->
      (word2Int# (word32ToWord# value)) +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE int64Result #-}
int64Result :: Int# -> Int# -> Int#
int64Result mode n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskAsyncExceptions# (\s3 ->
    maskUninterruptible# (\s4 -> unmaskAsyncExceptions# (\s5 ->
      case (case readMutVar# count s5 of { (# s5a, Payload completed #) ->
        writeMutVar# count (Payload (completed +# 1#)) s5a }) of { s6 ->
        case mode of {
          1# -> raiseIO# (Payload n) s6;
          2# -> case myThreadId# s6 of { (# s7, tid #) ->
            case killThread# tid (Payload n) s7 of { s8 ->
            case writeMutVar# count (Payload 999#) s8 of { s9 ->
              case n of { v -> (# s9, intToInt64# v #) } } } };
          _ -> case n +# 7# of { v -> (# s6, intToInt64# v #) } }
      }) s4) s3) s2)
    (\(Payload caught) s10 -> case getMaskingState# s10 of {
      (# s11, mask #) -> case caught +# mask *# 17# of { v -> (# s11, intToInt64# v #) } }) s1 of {
    (# s12, value #) -> case readMutVar# count s12 of { (# s13, Payload writes #) ->
    case getMaskingState# s13 of { (# _, outside #) ->
      (int64ToInt# value) +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE word64Result #-}
word64Result :: Int# -> Int# -> Int#
word64Result mode n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskAsyncExceptions# (\s3 ->
    maskUninterruptible# (\s4 -> unmaskAsyncExceptions# (\s5 ->
      case (case readMutVar# count s5 of { (# s5a, Payload completed #) ->
        writeMutVar# count (Payload (completed +# 1#)) s5a }) of { s6 ->
        case mode of {
          1# -> raiseIO# (Payload n) s6;
          2# -> case myThreadId# s6 of { (# s7, tid #) ->
            case killThread# tid (Payload n) s7 of { s8 ->
            case writeMutVar# count (Payload 999#) s8 of { s9 ->
              case n of { v -> (# s9, wordToWord64# (int2Word# v) #) } } } };
          _ -> case n +# 7# of { v -> (# s6, wordToWord64# (int2Word# v) #) } }
      }) s4) s3) s2)
    (\(Payload caught) s10 -> case getMaskingState# s10 of {
      (# s11, mask #) -> case caught +# mask *# 17# of { v -> (# s11, wordToWord64# (int2Word# v) #) } }) s1 of {
    (# s12, value #) -> case readMutVar# count s12 of { (# s13, Payload writes #) ->
    case getMaskingState# s13 of { (# _, outside #) ->
      (word2Int# (word64ToWord# value)) +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE floatResult #-}
floatResult :: Int# -> Int# -> Int#
floatResult mode n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskAsyncExceptions# (\s3 ->
    maskUninterruptible# (\s4 -> unmaskAsyncExceptions# (\s5 ->
      case (case readMutVar# count s5 of { (# s5a, Payload completed #) ->
        writeMutVar# count (Payload (completed +# 1#)) s5a }) of { s6 ->
        case mode of {
          1# -> raiseIO# (Payload n) s6;
          2# -> case myThreadId# s6 of { (# s7, tid #) ->
            case killThread# tid (Payload n) s7 of { s8 ->
            case writeMutVar# count (Payload 999#) s8 of { s9 ->
              case n of { v -> (# s9, int2Float# v #) } } } };
          _ -> case n +# 7# of { v -> (# s6, int2Float# v #) } }
      }) s4) s3) s2)
    (\(Payload caught) s10 -> case getMaskingState# s10 of {
      (# s11, mask #) -> case caught +# mask *# 17# of { v -> (# s11, int2Float# v #) } }) s1 of {
    (# s12, value #) -> case readMutVar# count s12 of { (# s13, Payload writes #) ->
    case getMaskingState# s13 of { (# _, outside #) ->
      (float2Int# value) +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE doubleResult #-}
doubleResult :: Int# -> Int# -> Int#
doubleResult mode n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskAsyncExceptions# (\s3 ->
    maskUninterruptible# (\s4 -> unmaskAsyncExceptions# (\s5 ->
      case (case readMutVar# count s5 of { (# s5a, Payload completed #) ->
        writeMutVar# count (Payload (completed +# 1#)) s5a }) of { s6 ->
        case mode of {
          1# -> raiseIO# (Payload n) s6;
          2# -> case myThreadId# s6 of { (# s7, tid #) ->
            case killThread# tid (Payload n) s7 of { s8 ->
            case writeMutVar# count (Payload 999#) s8 of { s9 ->
              case n of { v -> (# s9, int2Double# v #) } } } };
          _ -> case n +# 7# of { v -> (# s6, int2Double# v #) } }
      }) s4) s3) s2)
    (\(Payload caught) s10 -> case getMaskingState# s10 of {
      (# s11, mask #) -> case caught +# mask *# 17# of { v -> (# s11, int2Double# v #) } }) s1 of {
    (# s12, value #) -> case readMutVar# count s12 of { (# s13, Payload writes #) ->
    case getMaskingState# s13 of { (# _, outside #) ->
      (double2Int# value) +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE emptyResult #-}
emptyResult :: Int# -> Int# -> Int#
emptyResult mode n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskAsyncExceptions# (\s3 ->
    maskUninterruptible# (\s4 -> unmaskAsyncExceptions# (\s5 ->
      case (case readMutVar# count s5 of { (# s5a, Payload completed #) ->
        writeMutVar# count (Payload (completed +# 1#)) s5a }) of { s6 ->
        case mode of {
          1# -> raiseIO# (Payload n) s6;
          2# -> case myThreadId# s6 of { (# s7, tid #) ->
            case killThread# tid (Payload n) s7 of { s8 ->
            case writeMutVar# count (Payload 999#) s8 of { s9 ->
              case n of { v -> (# s9, (# #) #) } } } };
          _ -> case n +# 7# of { v -> (# s6, (# #) #) } }
      }) s4) s3) s2)
    (\(Payload caught) s10 -> case getMaskingState# s10 of {
      (# s11, mask #) -> case caught +# mask *# 17# of { v -> (# s11, (# #) #) } }) s1 of {
    (# s12, value #) -> case readMutVar# count s12 of { (# s13, Payload writes #) ->
    case getMaskingState# s13 of { (# _, outside #) ->
      (case value of { (# #) -> 0# }) +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE nestedResult #-}
nestedResult :: Int# -> Int# -> Int#
nestedResult mode n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskAsyncExceptions# (\s3 ->
    maskUninterruptible# (\s4 -> unmaskAsyncExceptions# (\s5 ->
      case (case readMutVar# count s5 of { (# s5a, Payload completed #) ->
        writeMutVar# count (Payload (completed +# 1#)) s5a }) of { s6 ->
        case mode of {
          1# -> raiseIO# (Payload n) s6;
          2# -> case myThreadId# s6 of { (# s7, tid #) ->
            case killThread# tid (Payload n) s7 of { s8 ->
            case writeMutVar# count (Payload 999#) s8 of { s9 ->
              case n of { v -> (# s9, (# v, (# int2Float# (v +# 1#), (# #) #) #) #) } } } };
          _ -> case n +# 7# of { v -> (# s6, (# v, (# int2Float# (v +# 1#), (# #) #) #) #) } }
      }) s4) s3) s2)
    (\(Payload caught) s10 -> case getMaskingState# s10 of {
      (# s11, mask #) -> case caught +# mask *# 17# of { v -> (# s11, (# v, (# int2Float# (v +# 1#), (# #) #) #) #) } }) s1 of {
    (# s12, value #) -> case readMutVar# count s12 of { (# s13, Payload writes #) ->
    case getMaskingState# s13 of { (# _, outside #) ->
      (case value of { (# a, (# b, (# #) #) #) -> a +# float2Int# b }) +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE sumResult #-}
sumResult :: Int# -> Int# -> Int#
sumResult mode n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskAsyncExceptions# (\s3 ->
    maskUninterruptible# (\s4 -> unmaskAsyncExceptions# (\s5 ->
      case (case readMutVar# count s5 of { (# s5a, Payload completed #) ->
        writeMutVar# count (Payload (completed +# 1#)) s5a }) of { s6 ->
        case mode of {
          1# -> raiseIO# (Payload n) s6;
          2# -> case myThreadId# s6 of { (# s7, tid #) ->
            case killThread# tid (Payload n) s7 of { s8 ->
            case writeMutVar# count (Payload 999#) s8 of { s9 ->
              case n of { v -> (# s9, (case v <# 0# of { 1# -> (# v | #); _ -> (# | int2Float# v #) } :: (# Int# | Float# #)) #) } } } };
          _ -> case n +# 7# of { v -> (# s6, (case v <# 0# of { 1# -> (# v | #); _ -> (# | int2Float# v #) } :: (# Int# | Float# #)) #) } }
      }) s4) s3) s2)
    (\(Payload caught) s10 -> case getMaskingState# s10 of {
      (# s11, mask #) -> case caught +# mask *# 17# of { v -> (# s11, (case v <# 0# of { 1# -> (# v | #); _ -> (# | int2Float# v #) } :: (# Int# | Float# #)) #) } }) s1 of {
    (# s12, value #) -> case readMutVar# count s12 of { (# s13, Payload writes #) ->
    case getMaskingState# s13 of { (# _, outside #) ->
      (case value of { (# a | #) -> a; (# | b #) -> float2Int# b }) +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE vectorResult #-}
vectorResult :: Int# -> Int# -> Int#
vectorResult mode n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskAsyncExceptions# (\s3 ->
    maskUninterruptible# (\s4 -> unmaskAsyncExceptions# (\s5 ->
      case (case readMutVar# count s5 of { (# s5a, Payload completed #) ->
        writeMutVar# count (Payload (completed +# 1#)) s5a }) of { s6 ->
        case mode of {
          1# -> raiseIO# (Payload n) s6;
          2# -> case myThreadId# s6 of { (# s7, tid #) ->
            case killThread# tid (Payload n) s7 of { s8 ->
            case writeMutVar# count (Payload 999#) s8 of { s9 ->
              case n of { v -> (# s9, broadcastInt32X4# (intToInt32# v) #) } } } };
          _ -> case n +# 7# of { v -> (# s6, broadcastInt32X4# (intToInt32# v) #) } }
      }) s4) s3) s2)
    (\(Payload caught) s10 -> case getMaskingState# s10 of {
      (# s11, mask #) -> case caught +# mask *# 17# of { v -> (# s11, broadcastInt32X4# (intToInt32# v) #) } }) s1 of {
    (# s12, value #) -> case readMutVar# count s12 of { (# s13, Payload writes #) ->
    case getMaskingState# s13 of { (# _, outside #) ->
      (case unpackInt32X4# value of { (# a, b, c, d #) -> int32ToInt# a +# int32ToInt# b +# int32ToInt# c +# int32ToInt# d }) +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE unliftedResult #-}
unliftedResult :: Int# -> Int# -> Int#
unliftedResult mode n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskAsyncExceptions# (\s3 ->
    maskUninterruptible# (\s4 -> unmaskAsyncExceptions# (\s5 ->
      case (case readMutVar# count s5 of { (# s5a, Payload completed #) ->
        writeMutVar# count (Payload (completed +# 1#)) s5a }) of { s6 ->
        case mode of {
          1# -> raiseIO# (Payload n) s6;
          2# -> case myThreadId# s6 of { (# s7, tid #) ->
            case killThread# tid (Payload n) s7 of { s8 ->
            case writeMutVar# count (Payload 999#) s8 of { s9 ->
              case n of { v -> (# s9, Product v bottom #) } } } };
          _ -> case n +# 7# of { v -> (# s6, Product v bottom #) } }
      }) s4) s3) s2)
    (\(Payload caught) s10 -> case getMaskingState# s10 of {
      (# s11, mask #) -> case caught +# mask *# 17# of { v -> (# s11, Product v bottom #) } }) s1 of {
    (# s12, value #) -> case readMutVar# count s12 of { (# s13, Payload writes #) ->
    case getMaskingState# s13 of { (# _, outside #) ->
      (case value of { Product a _ -> a }) +# writes *# 101# +# outside *# 1009# } } } })

{-# OPAQUE unliftedPayloadResult #-}
unliftedPayloadResult :: Int# -> Int# -> Int#
unliftedPayloadResult mode n = runRW# (\s0 ->
  case newMutVar# (Payload 0#) s0 of { (# s1, count #) ->
  case catch# (\s2 -> maskAsyncExceptions# (\s3 ->
    maskUninterruptible# (\s4 -> unmaskAsyncExceptions# (\s5 ->
      case (case readMutVar# count s5 of { (# s5a, Payload completed #) ->
        writeMutVar# count (Payload (completed +# 1#)) s5a }) of { s6 ->
        case mode of {
          1# -> raiseIO# (Product n bottom) s6;
          _ -> case n +# 7# of { v -> (# s6, v #) } }
      }) s4) s3) s2)
    (\(Product caught _) s10 -> case getMaskingState# s10 of {
      (# s11, mask #) -> case caught +# mask *# 17# of { v -> (# s11, v #) } }) s1 of {
    (# s12, value #) -> case readMutVar# count s12 of { (# s13, Payload writes #) ->
    case getMaskingState# s13 of { (# _, outside #) ->
      (value) +# writes *# 101# +# outside *# 1009# } } } })
