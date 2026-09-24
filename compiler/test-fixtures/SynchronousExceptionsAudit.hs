-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, ScopedTypeVariables #-}
module SynchronousExceptionsAudit where

import GHC.Exts

data Payload = Payload Int#

{-# OPAQUE bottomPayload #-}
bottomPayload :: Payload
bottomPayload = bottomPayload

{-# OPAQUE preciseCatch #-}
preciseCatch :: Int# -> Int#
preciseCatch raw = runRW# (\s ->
  case catch# (\s0 -> raiseIO# (Payload raw) s0)
              (\(Payload value) s1 -> (# s1, Payload (value +# 17#) #)) s of
    (# _, Payload result #) -> result)

-- catch# installs its handler before evaluating the action closure. This is
-- intentionally not the head-strict GHC.Internal.IO.catchException wrapper.
{-# OPAQUE actionHeadCatch #-}
actionHeadCatch :: Int# -> Int#
actionHeadCatch raw = runRW# (\s ->
  case catch# (raise# (Payload raw))
              (\(Payload value) s1 -> (# s1, Payload (value +# 19#) #)) s of
    (# _, Payload result #) -> result)

-- Passing a lifted exception closure never forces it merely to enter a handler.
{-# OPAQUE ignoredBottomPayload #-}
ignoredBottomPayload :: Int# -> Int#
ignoredBottomPayload raw = runRW# (\s ->
  case catch# (\s0 -> raiseIO# bottomPayload s0)
              (\(_ :: Payload) s1 -> (# s1, Payload (raw +# 23#) #)) s of
    (# _, Payload result #) -> result)

-- The handler is outside its own catch frame: a rethrow reaches the outer one.
{-# OPAQUE nestedRethrow #-}
nestedRethrow :: Int# -> Int#
nestedRethrow raw = runRW# (\s ->
  case catch# (\s0 -> catch# (\s1 -> raiseIO# (Payload raw) s1)
                             (\(Payload value) s2 -> raiseIO# (Payload (value +# 29#)) s2) s0)
              (\(Payload value) s3 -> (# s3, Payload (value *# 3#) #)) s of
    (# _, Payload result #) -> result)

-- A successful action must not enter its handler, even when that handler is bottom.
{-# OPAQUE unusedHandler #-}
unusedHandler :: Int# -> Int#
unusedHandler raw = runRW# (\s ->
  case catch# (\s0 -> (# s0, Payload (raw +# 31#) #))
              (raise# bottomPayload :: Payload -> State# RealWorld -> (# State# RealWorld, Payload #)) s of
    (# _, Payload result #) -> result)

-- A value returned lazily by the action is demanded *outside* its catch frame.
-- The inner handler's sentinel must never be selected by the later demand.
{-# OPAQUE lazyResultBoundary #-}
lazyResultBoundary :: Int# -> Int#
lazyResultBoundary raw = runRW# (\s ->
  case catch# (\s0 ->
                case catch# (\s1 -> (# s1, raise# (Payload raw) #))
                            (\(_ :: Payload) s2 -> (# s2, Payload 999# #)) s0 of
                  (# s3, result #) -> case result of
                    Payload value -> (# s3, Payload (value +# 1#) #))
              (\(Payload value) s4 -> (# s4, Payload (value +# 37#) #)) s of
    (# _, Payload result #) -> result)

-- Handle-style synchronous restoration only. No claim that this is mask/bracket
-- or safe against asynchronous exceptions: those require additional primitives.
{-# OPAQUE restoreAndRethrow #-}
restoreAndRethrow :: Int# -> Int#
restoreAndRethrow raw = runRW# (\s0 ->
  case newMVar# s0 of { (# s1, cell #) ->
  case putMVar# cell (Payload raw) s1 of { s2 ->
  case takeMVar# cell s2 of { (# s3, original #) ->
  case catch# (\s4 -> catch# (\s5 -> raiseIO# (Payload (raw +# 41#)) s5)
                             (\exception s6 -> case putMVar# cell original s6 of
                               s7 -> raiseIO# (exception :: Payload) s7) s4)
              (\(Payload exception) s8 ->
                case readMVar# cell s8 of { (# s9, Payload restored #) ->
                case tryPutMVar# cell bottomPayload s9 of { (# s10, full #) ->
                  (# s10, Payload (restored *# 257# +# exception +# full *# 65537#) #)
                } }) s3 of
    (# _, Payload result #) -> result
  } } })

-- Native-only masking observation, exported separately as an unsupported frontier.
-- Even synchronous catch handlers run masked-interruptible when entered unmasked.
{-# OPAQUE handlerMaskState #-}
handlerMaskState :: Int# -> Int#
handlerMaskState raw = runRW# (\s0 ->
  case unmaskAsyncExceptions# (\s1 ->
    case getMaskingState# s1 of { (# s2, before #) ->
    case catch# (\s3 -> raiseIO# (Payload raw) s3)
                (\(_ :: Payload) s4 -> case getMaskingState# s4 of
                  (# s5, during #) -> (# s5, Payload during #)) s2 of {
      (# s6, Payload during #) ->
    case getMaskingState# s6 of { (# s7, after #) ->
      (# s7, Payload (before +# during *# 17# +# after *# 257#) #)
    } } }) s0 of
      (# _, Payload result #) -> result)

-- Raw maskAsyncExceptions# inside maskUninterruptible# sets interruptible
-- masking, unlike the high-level mask wrapper. The base-3 digits record
-- states before, during, and after the nested actions.
{-# OPAQUE maskNested #-}
maskNested :: Int# -> Int#
maskNested raw = runRW# (\s0 ->
  case maskAsyncExceptions# (\s1 ->
    case getMaskingState# s1 of { (# s2, outer #) ->
    case maskUninterruptible# (\s3 ->
      case getMaskingState# s3 of { (# s4, inner #) ->
      case maskAsyncExceptions# (\s5 ->
        case getMaskingState# s5 of { (# s6, deepest #) ->
          (# s6, Payload (raw +# outer +# inner *# 3# +# deepest *# 9#) #)
        }) s4 of { (# s7, Payload partial #) ->
      case getMaskingState# s7 of { (# s8, afterDeep #) ->
        (# s8, Payload (partial +# afterDeep *# 27#) #)
      } } }) s2 of { (# s9, Payload partial #) ->
    case getMaskingState# s9 of { (# s10, afterInner #) ->
      (# s10, Payload (partial +# afterInner *# 81#) #)
    } } }) s0 of { (# s11, Payload partial #) ->
  case getMaskingState# s11 of { (# _, afterOuter #) ->
    partial +# afterOuter *# 243#
  } })

-- Unwinding an uninterruptible mask restores the prior state before catch#
-- enters its handler; a handler entered from unmasked runs interruptibly masked.
{-# OPAQUE maskRethrowRestore #-}
maskRethrowRestore :: Int# -> Int#
maskRethrowRestore raw = runRW# (\s ->
  case catch# (\s0 -> maskUninterruptible# (\s1 ->
        case getMaskingState# s1 of { (# s2, inside #) ->
          raiseIO# (Payload (raw +# inside)) s2
        }) s0)
      (\(Payload value) s3 -> case getMaskingState# s3 of
        (# s4, during #) -> (# s4, Payload (value +# during *# 3#) #)) s of
    (# s5, Payload result #) -> case getMaskingState# s5 of
      (# _, after #) -> result +# after *# 9#)

-- Force's exclusive thunk ownership supplies noDuplicate#'s guarantee; this
-- source probe retains GHC's actual state-token primop in Core.
{-# OPAQUE noDuplicateProbe #-}
noDuplicateProbe :: Int# -> Int#
noDuplicateProbe raw = runRW# (\s ->
  case noDuplicate# s of _ -> raw +# 5#)
