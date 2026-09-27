-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : ThreadAsyncAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for thread async audit Core and metadata.
module ThreadAsyncAudit where

import GHC.Exts

data Box = Box Int#
data Shared = Shared Box
data ThreadBox = ThreadBox ThreadId#
data Cells = Cells (MVar# RealWorld Box) (MVar# RealWorld Box)
  (MVar# RealWorld Box) (MVar# RealWorld ThreadBox)
  (MutVar# RealWorld Box)

-- yield# consumes and returns exactly State#. The second root proves that
-- yielding does not change an uninterruptible Haskell mask.
{-# OPAQUE yieldProbe #-}
yieldProbe :: Int# -> Int#
yieldProbe token = runRW# (\s0 ->
  case yield# s0 of { s1 ->
  case getMaskingState# s1 of { (# _, mask #) -> token +# 37# +# mask } })

{-# OPAQUE yieldMasked #-}
yieldMasked :: Int# -> Int#
yieldMasked token =
  case maskUninterruptible# (\s0 ->
    case yield# s0 of { s1 ->
    case getMaskingState# s1 of { (# s2, mask #) ->
      (# s2, Box (token +# 38# +# mask) #) } }) realWorld# of
    (# _, Box answer #) -> answer

-- All cells are fresh for each call. The worker and the caller receive the
-- same lifted thunk, so an interruption must not replay its prefix.
{-# OPAQUE makeShared #-}
makeShared :: Cells -> Shared
makeShared (Cells ready gate _ _ count) = Shared $
  case readMutVar# count realWorld# of { (# s1, Box old #) ->
  case writeMutVar# count (Box (old +# 1#)) s1 of { s2 ->
  case putMVar# ready (Box 1#) s2 of { s3 ->
  case takeMVar# gate s3 of { (# _, _ #) -> Box 42# } } } }

{-# OPAQUE child #-}
child :: Shared -> Cells -> State# RealWorld -> (# State# RealWorld, () #)
child shared (Cells _ _ done identity _) s0 =
  case myThreadId# s0 of { (# s1, tid #) ->
  case putMVar# identity (ThreadBox tid) s1 of { s2 ->
  case catch# (\s -> case shared of { Shared (Box value) -> (# s, Box value #) })
              (\_ s -> (# s, Box (-1#) #)) s2 of { (# s3, result #) ->
  case putMVar# done result s3 of { s4 -> (# s4, () #) } } } }

-- fork# runs the action above, myThreadId# publishes its actual identity,
-- and killThread# delivers a lifted exception to that thread. The second
-- force of shared must return the same value with exactly one prefix write.
{-# OPAQUE forkAndThrow #-}
forkAndThrow :: Int# -> Int#
forkAndThrow token =
  case newMVar# realWorld# of { (# s1, ready #) ->
  case newMVar# s1 of { (# s2, gate #) ->
  case newMVar# s2 of { (# s3, done #) ->
  case newMVar# s3 of { (# s4, identity #) ->
  case newMutVar# (Box 0#) s4 of { (# s5, count #) ->
  let cells = Cells ready gate done identity count
      shared = makeShared cells
  in case fork# (child shared cells) s5 of { (# s6, _ #) ->
  case takeMVar# identity s6 of { (# s7, ThreadBox childTid #) ->
  case takeMVar# ready s7 of { (# s8, Box readyValue #) ->
  case killThread# childTid (Box 7#) s8 of { s9 ->
  case takeMVar# done s9 of { (# s10, Box caught #) ->
  case putMVar# gate (Box 1#) s10 of { s11 ->
  case shared of { Shared (Box value) ->
  case readMutVar# count s11 of { (# _, Box prefixes #) ->
    case (readyValue ==# 1#) `andI#` (caught ==# -1#)
           `andI#` (value ==# 42#) `andI#` (prefixes ==# 1#) of
      1# -> token +# 43#
      _  -> -999#
  } } } } } } } } } } } } }

-- The child deliberately has no catch#. killThread# must acknowledge an
-- uncaught asynchronous exception and let the sender continue; its blocked
-- MVar is never filled, so a surviving child cannot finish normally.
{-# OPAQUE uncaughtChild #-}
uncaughtChild :: MVar# RealWorld ThreadBox -> MVar# RealWorld Box
  -> State# RealWorld -> (# State# RealWorld, () #)
uncaughtChild identity gate s0 =
  case myThreadId# s0 of { (# s1, tid #) ->
  case putMVar# identity (ThreadBox tid) s1 of { s2 ->
  case takeMVar# gate s2 of { (# s3, _ #) -> (# s3, () #) } } }

{-# OPAQUE killUncaught #-}
killUncaught :: Int# -> Int#
killUncaught token =
  case newMVar# realWorld# of { (# s1, identity #) ->
  case newMVar# s1 of { (# s2, gate #) ->
  case fork# (uncaughtChild identity gate) s2 of { (# s3, _ #) ->
  case takeMVar# identity s3 of { (# s4, ThreadBox tid #) ->
  case killThread# tid (Box 7#) s4 of { _ -> token +# 5# } } } } }

-- Self-directed delivery is caught by the original catch# path. There is
-- no host sender or private runtime-thread API in either public root.
{-# OPAQUE selfThrow #-}
selfThrow :: Int# -> Int#
selfThrow token =
  case catch# (\s0 ->
    case myThreadId# s0 of { (# s1, tid #) ->
    case killThread# tid (Box 7#) s1 of { s2 -> (# s2, Box 99# #) } })
    (\_ s -> (# s, Box (-1#) #)) realWorld# of
      (# _, Box result #) -> token +# result

-- An outer uninterruptible mask cannot justify omitting the action's
-- continuation cut: its callee unmasks and delivers to this same thread.
{-# OPAQUE maskedUnmaskSelf #-}
maskedUnmaskSelf :: Int# -> Int#
maskedUnmaskSelf token =
  case catch# (\s0 ->
    case maskUninterruptible# (\s1 ->
      unmaskAsyncExceptions# (\s2 ->
        case myThreadId# s2 of { (# s3, tid #) ->
        case killThread# tid (Box 7#) s3 of { s4 -> (# s4, Box 99# #) } }) s1) s0 of
      (# s5, value #) -> (# s5, value #))
    (\_ s -> (# s, Box (-1#) #)) realWorld# of
      (# s6, Box result #) -> case getMaskingState# s6 of
        (# _, outside #) -> token +# result +# 100# *# outside

-- A real prompt selects delimited catch/mask lowering for these unchanged
-- self-delivery callees; neither wrapper captures a saved continuation.
{-# OPAQUE promptSelfThrow #-}
promptSelfThrow :: Int# -> Int#
promptSelfThrow token = runRW# (\s0 ->
  case newPromptTag# s0 of { (# s1, tag #) ->
  case prompt# tag (\s -> case selfThrow token of value -> (# s, Box value #)) s1 of
    (# _, Box result #) -> result })

{-# OPAQUE promptMaskedUnmaskSelf #-}
promptMaskedUnmaskSelf :: Int# -> Int#
promptMaskedUnmaskSelf token = runRW# (\s0 ->
  case newPromptTag# s0 of { (# s1, tag #) ->
  case prompt# tag (\s -> case maskedUnmaskSelf token of value -> (# s, Box value #)) s1 of
    (# _, Box result #) -> result })

{-# OPAQUE savedSelfPayload #-}
savedSelfPayload :: Int# -> Box
savedSelfPayload token = raise# (Box token)

-- The same saved catch runs twice. The prefix adds 100 exactly once, each
-- handler adds one, and neither handler evaluates the exception payload.
{-# OPAQUE savedSelf #-}
savedSelf :: Int# -> Int# -> Int#
savedSelf mode token = runRW# $ \s0 -> case newMutVar# (Box 0#) s0 of
  (# s1, counter #) -> case newPromptTag# s1 of
    (# s2, tag #) ->
      let deliver s = case myThreadId# s of
            (# st, self #) -> case killThread# self (savedSelfPayload token) st of
              next -> (# next, 0# #)
          capture s = case control0# tag (\k st ->
            case k (\sx -> case mode of
              2# -> (# sx, 0# #)
              _ -> deliver sx) st of
                (# st1, Box first #) -> case getMaskingState# st1 of
                  (# st2, outside #) -> case k (\sx -> case mode of
                    2# -> (# sx, 0# #)
                    _ -> deliver sx) st2 of
                      (# st3, Box second #) -> (# st3, Box (first *# 100# +# second *# 10# +# outside) #)) s of
                (# st, _ #) -> case deliver st of
                  (# next, value #) -> (# next, Box (value +# 10000#) #)
          masked s = case mode of
            1# -> maskUninterruptible# (\st -> unmaskAsyncExceptions# capture st) s
            _ -> capture s
          handler _ st = case readMutVar# counter st of
            (# st1, Box count #) -> case writeMutVar# counter (Box (count +# 1#)) st1 of
              st2 -> case getMaskingState# st2 of
                (# st3, mask #) -> (# st3, Box mask #)
          caught s = case mode of
            3# -> maskUninterruptible# (\st -> catch#
              (\sx -> unmaskAsyncExceptions# capture sx) handler st) s
            _ -> catch# masked handler s
      in case prompt# tag (\s3 -> case readMutVar# counter s3 of
        (# s4, Box before #) -> case writeMutVar# counter (Box (before +# 100#)) s4 of
          s5 -> caught s5) s2 of
                  (# s6, Box answer #) -> case readMutVar# counter s6 of
                    (# s7, Box count #) -> case getMaskingState# s7 of
                      (# _, outside #) -> answer *# 1000# +# count +# outside *# 1000000# +# token

{-# OPAQUE savedSelfThrow #-}
savedSelfThrow :: Int# -> Int#
savedSelfThrow = savedSelf 0#

{-# OPAQUE savedMaskedSelf #-}
savedMaskedSelf :: Int# -> Int#
savedMaskedSelf = savedSelf 1#

{-# OPAQUE savedSuffixSelf #-}
savedSuffixSelf :: Int# -> Int#
savedSuffixSelf = savedSelf 2#

{-# OPAQUE savedMaskCatchSelf #-}
savedMaskCatchSelf :: Int# -> Int#
savedMaskCatchSelf = savedSelf 3#

-- External throwTo uses MVar handshakes, not timing. The main thread remains
-- interruptibly masked, so each delivery reaches its empty-MVar wait. The
-- sender's acknowledgement is observed before the next use of the same image.
{-# OPAQUE externalSaved #-}
externalSaved :: Int# -> Int#
externalSaved token = runRW# $ \s0 -> case newMVar# s0 of
  (# s1, ready #) -> case newMVar# s1 of
    (# s2, blocked #) -> case newMVar# s2 of
      (# s3, acknowledged #) -> case newMutVar# (Box 0#) s3 of
        (# s4, counter #) -> case myThreadId# s4 of
          (# s5, target #) ->
            let add amount s = case readMutVar# counter s of
                  (# st, Box count #) -> writeMutVar# counter (Box (count +# amount)) st
                sender ordinal s = case takeMVar# ready s of
                  (# st, Box _ #) -> case killThread# target (savedSelfPayload token) st of
                    st1 -> case putMVar# acknowledged (Box ordinal) st1 of
                      st2 -> case ordinal of
                        1# -> sender 2# st2
                        _ -> (# st2, () #)
                wait s = case add 1000# s of
                  st -> case putMVar# ready (Box 1#) st of
                    st1 -> case takeMVar# blocked st1 of
                      (# st2, Box value #) -> (# st2, value #)
            in case fork# (sender 1#) s5 of
              (# s6, _ #) -> case newPromptTag# s6 of
                (# s7, tag #) -> case maskAsyncExceptions# (\s8 -> prompt# tag
                  (\s9 -> case add 100# s9 of
                    s10 -> case catch# (\s11 -> control0# tag (\k s12 ->
                      case k wait s12 of
                        (# s13, Box first #) -> case takeMVar# acknowledged s13 of
                          (# s14, Box firstAck #) -> case k wait s14 of
                            (# s15, Box second #) -> case takeMVar# acknowledged s15 of
                              (# s16, Box secondAck #) ->
                                (# s16, Box (first *# 1000# +# second *# 100# +# firstAck *# 10# +# secondAck) #)) s11)
                      (\_ st -> case add 10# st of
                        st1 -> case getMaskingState# st1 of
                          (# st2, mask #) -> (# st2, mask #)) s10 of
                            (# s17, value #) -> case add 1# s17 of
                              s18 -> (# s18, Box value #)) s8) s7 of
                                (# s19, Box answer #) -> case readMutVar# counter s19 of
                                  (# s20, Box count #) -> case getMaskingState# s20 of
                                    (# _, outside #) -> answer *# 10000# +# count +# outside *# 100000000# +# token
