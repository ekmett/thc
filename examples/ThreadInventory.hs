-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
{-# OPTIONS_GHC -fno-do-lambda-eta-expansion #-}
module ThreadInventory where

import GHC.Exts

-- A snapshot is an ordinary Array# of unlifted identities. Do not assume a
-- stable ordering or that finished threads disappear immediately.
{-# OPAQUE occurrences #-}
occurrences :: ThreadId# -> Array# ThreadId# -> Int# -> Int#
occurrences wanted threads i = case i <# sizeofArray# threads of
  0# -> 0#
  _ -> case indexArray# threads i of
    (# tid #) -> reallyUnsafePtrEquality# wanted tid +# occurrences wanted threads (i +# 1#)

-- Useful scalar entry for the THC runner: ten means exactly one self identity
-- and an unbound guest. The token makes first-compiled observations distinct.
{-# OPAQUE selfInventory #-}
selfInventory :: Int# -> Int#
selfInventory token = runRW# (\s -> case myThreadId# s of
  (# s1, self #) -> case listThreads# s1 of
    (# s2, threads #) -> case isCurrentThreadBound# s2 of
      (# _, bound #) -> token +# 10# *# occurrences self threads 0# +# bound)

{-# OPAQUE boundQuery #-}
boundQuery :: Int# -> Int#
boundQuery token = runRW# (\s -> case isCurrentThreadBound# s of (# _, bound #) -> token +# bound)

{-# OPAQUE snapshotSize #-}
snapshotSize :: Int# -> Int#
snapshotSize token = runRW# (\s -> case listThreads# s of (# _, threads #) -> token +# sizeofArray# threads)

-- Both backends use real managed threads. The child stays
-- alive across acquisition; the same immutable snapshot remains usable after
-- it signals completion. Neither scheduling order nor the RTS's background thread count
-- is used as an oracle.
{-# OPAQUE forkSnapshot #-}
forkSnapshot :: Int# -> Int#
forkSnapshot token = runRW# (\s ->
  case newMVar# s of { (# s1, ready #) ->
  case newMVar# s1 of { (# s2, gate #) ->
  case newMVar# s2 of { (# s3, done #) ->
  case fork# (\s4 -> case putMVar# ready () s4 of { s5 ->
    case takeMVar# gate s5 of { (# s6, _ #) ->
    case putMVar# done () s6 of { s7 -> (# s7, () #) } } }) s3 of { (# s4, child #) ->
  case takeMVar# ready s4 of { (# s5, _ #) ->
  case myThreadId# s5 of { (# s6, self #) ->
  case listThreads# s6 of { (# s7, threads #) ->
  case occurrences self threads 0# +# 10# *# occurrences child threads 0# of { before ->
  case putMVar# gate () s7 of { s8 ->
  case takeMVar# done s8 of { (# _, _ #) ->
    token +# before +# 100# *# occurrences child threads 0#
  } } } } } } } } } })

data Box = Box Int#
data Action = Action (State# RealWorld -> (# State# RealWorld, Box #))

{-# OPAQUE awaitStatus #-}
awaitStatus :: ThreadId# -> Int# -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
awaitStatus tid wanted fuel s = wait (-1#) fuel s
  where
    -- An initial nonmatching observation means every invocation traverses both
    -- retry and completion, even when the child finishes before the first read.
    -- Compilation checks must not depend on winning a scheduling race.
    wait observed left state = case observed ==# wanted of
      1# -> (# state, observed #)
      _ -> case left ># 0# of
        1# -> case threadStatus# tid state of { (# s1, status, _, _ #) ->
          case yield# s1 of s2 -> wait status (left -# 1#) s2 }
        _ -> (# state, -999# #)

-- Work before the state lambda must run in the child, not in fork#'s caller.
-- The gate makes premature parent evaluation deadlock, and identity checks
-- exclude merely moving the work to another call on the parent's Java thread.
{-# OPAQUE actionHead #-}
actionHead :: ThreadId# -> MVar# RealWorld Box -> MVar# RealWorld Box
           -> MVar# RealWorld Box -> State# RealWorld -> (# State# RealWorld, Box #)
actionHead parent ready gate done =
  case myThreadId# realWorld# of { (# s1, self #) ->
  case putMVar# ready (Box (reallyUnsafePtrEquality# parent self)) s1 of { s2 ->
  case takeMVar# gate s2 of { (# _, _ #) ->
  \s -> case putMVar# done (Box 42#) s of { s' -> (# s', raise# (Box 99#) #) }
  } } }

{-# OPAQUE makeAction #-}
makeAction :: ThreadId# -> MVar# RealWorld Box -> MVar# RealWorld Box -> MVar# RealWorld Box -> Action
makeAction parent ready gate done = Action (actionHead parent ready gate done)

{-# OPAQUE lazyFork #-}
lazyFork :: Int# -> Int#
lazyFork token = runRW# (\s ->
  case myThreadId# s of { (# s1, parent #) ->
  case newMVar# s1 of { (# s2, ready #) ->
  case newMVar# s2 of { (# s3, gate #) ->
  case newMVar# s3 of { (# s4, done #) ->
  case makeAction parent ready gate done of { Action action ->
  case fork# action s4 of { (# s5, child #) ->
  case takeMVar# ready s5 of { (# s6, Box same #) ->
  case putMVar# gate (Box 1#) s6 of { s7 ->
  case takeMVar# done s7 of { (# s8, Box published #) ->
  case awaitStatus child 16# 1000000# s8 of { (# _, status #) ->
    case (same ==# 0#) `andI#` (status ==# 16#) of
      1# -> token +# published
      _ -> -999#
  } } } } } } } } } })

{-# OPAQUE forkMask #-}
forkMask :: State# RealWorld -> (# State# RealWorld, Box #)
forkMask s = case newMVar# s of { (# s1, done #) ->
  case fork# (\s2 -> case getMaskingState# s2 of { (# s3, mask #) ->
    case putMVar# done (Box mask) s3 of { s4 -> (# s4, () #) } }) s1 of { (# s2, child #) ->
  case takeMVar# done s2 of { (# s3, result #) ->
  case awaitStatus child 16# 1000000# s3 of { (# s4, _ #) -> (# s4, result #) }
  } } }

-- Capture at creation, not from the child's default thread-local mask.
{-# OPAQUE forkMasks #-}
forkMasks :: Int# -> Int#
forkMasks token = case unmaskAsyncExceptions# forkMask realWorld# of { (# s1, Box a #) ->
  case maskUninterruptible# forkMask s1 of { (# s2, Box b #) ->
  case maskAsyncExceptions# forkMask s2 of { (# _, Box c #) -> token +# a +# 10# *# b +# 100# *# c }
  } }

{-# OPAQUE selfKilledStatus #-}
selfKilledStatus :: Int# -> Int#
selfKilledStatus token = case fork# (\s -> case myThreadId# s of { (# s1, self #) ->
  case killThread# self (Box 7#) s1 of { s2 -> (# s2, () #) } }) realWorld# of
  (# s1, child #) -> case awaitStatus child 17# 1000000# s1 of (# _, status #) -> token +# status

-- Deliberately retained blocked child for embedding/context-close tests. This
-- entry has no guest cancellation claim; the embedding cancels its context.
{-# OPAQUE parkedFork #-}
parkedFork :: Int# -> Int#
parkedFork token = case newMVar# realWorld# of { (# s1, gate #) ->
  case fork# (\s -> case takeMVar# gate s of (# s2, Box _ #) -> (# s2, () #)) s1 of { (# s2, child #) ->
  case awaitStatus child 1# 1000000# s2 of { (# _, status #) -> token +# status }
  } }
