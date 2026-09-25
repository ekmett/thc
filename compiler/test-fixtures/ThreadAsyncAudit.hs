-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
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
