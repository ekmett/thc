-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
-- Keep the work before the State# lambda in the shared action head.
{-# OPTIONS_GHC -fno-do-lambda-eta-expansion #-}
module LazyForkAudit where

import GHC.Exts

data Box = Box Int#
data Action = Action (State# RealWorld -> (# State# RealWorld, Box #))

{-# OPAQUE actionHead #-}
actionHead :: MVar# RealWorld Box -> MVar# RealWorld Box -> MVar# RealWorld Box
           -> MutVar# RealWorld Box -> State# RealWorld -> (# State# RealWorld, Box #)
actionHead ready gate done count =
  case readMutVar# count realWorld# of { (# s1, Box old #) ->
  case writeMutVar# count (Box (old +# 1#)) s1 of { s2 ->
  case putMVar# ready (Box 1#) s2 of { s3 ->
  case takeMVar# gate s3 of { (# _, _ #) ->
  \s -> case putMVar# done (Box 42#) s of { s' -> (# s', Box 7# #) }
  } } } }

{-# OPAQUE makeAction #-}
makeAction :: MVar# RealWorld Box -> MVar# RealWorld Box -> MVar# RealWorld Box
           -> MutVar# RealWorld Box -> Action
makeAction ready gate done count = Action (actionHead ready gate done count)

-- The parent cannot enter actionHead: it must return from fork# before it
-- can observe ready or fill gate. The first child is interrupted inside the
-- shared head; the parent then resumes exactly that head and sees one prefix.
{-# OPAQUE lazyFork #-}
lazyFork :: Int# -> Int#
lazyFork token =
  case newMVar# realWorld# of { (# s1, ready #) ->
  case newMVar# s1 of { (# s2, gate #) ->
  case newMVar# s2 of { (# s3, done #) ->
  case newMutVar# (Box 0#) s3 of { (# s4, count #) ->
  let shared = makeAction ready gate done count in
  case shared of { Action action ->
  case fork# action s4 of { (# s5, tid #) ->
  case takeMVar# ready s5 of { (# s6, Box signal #) ->
  case killThread# tid (Box 99#) s6 of { s7 ->
  case putMVar# gate (Box 1#) s7 of { s8 ->
  case action s8 of { (# s9, Box result #) ->
  case takeMVar# done s9 of { (# s10, Box published #) ->
  case readMutVar# count s10 of { (# _, Box prefixes #) ->
    case (signal ==# 1#) `andI#` (result ==# 7#)
           `andI#` (published ==# 42#) `andI#` (prefixes ==# 1#) of
      1# -> token +# 52#
      _  -> -999#
  } } } } } } } } } } } }
