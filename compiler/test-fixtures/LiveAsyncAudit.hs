-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module LiveAsyncAudit where
import GHC.Exts

data Box = Box Int#
data Cells = Cells (MVar# RealWorld Box) (MVar# RealWorld Box)
  (MVar# RealWorld Box) (MutVar# RealWorld Box)

{-# OPAQUE asyncPayload #-}
asyncPayload :: Box
asyncPayload = Box 7#

-- The same lifted closure owns both the readiness gate and prefix counter.
-- There is no noDuplicate# or private checkpoint inserted into this source.
{-# OPAQUE cells #-}
cells :: Cells
cells = case newMVar# realWorld# of { (# s1, ready #) ->
  case newMVar# s1 of { (# s2, gate #) ->
  case newMVar# s2 of { (# s3, running #) ->
  case newMutVar# (Box 0#) s3 of { (# _, count #) -> Cells ready gate running count } } } }

-- Readiness occurs after a thousand scalar iterations. The MVar wait makes
-- native throwTo deterministic: a tight unboxed loop alone need not reach a
-- GHC RTS async delivery point before finishing. The remaining loop is finite.
{-# OPAQUE longLoop #-}
longLoop :: MVar# RealWorld Box -> MVar# RealWorld Box -> MVar# RealWorld Box -> Int# -> Int# -> Int#
longLoop ready gate running n acc = case n ==# 0# of
  1# -> acc
  _ -> case n ==# 9999000# of
    1# -> case putMVar# ready (Box acc) realWorld# of { s1 ->
      case takeMVar# gate s1 of { (# s2, _ #) ->
      case putMVar# running (Box acc) s2 of { _ ->
        longLoop ready gate running (n -# 1#) (acc +# 1#) } } }
    _ -> longLoop ready gate running (n -# 1#) (acc +# 1#)

-- Warm and compile the actual loop callee without evaluating `shared` or
-- consuming either signal. Inputs below the gate threshold exercise only the
-- scalar recursion; the JVM test then reuses the same active call target.
{-# OPAQUE warmLoop #-}
warmLoop :: Int# -> Int#
warmLoop n = case cells of { Cells ready gate running _ ->
  longLoop ready gate running n 7# }

-- This single shared thunk must keep its evaluation continuation after the
-- first thread catches ThreadKilled. Re-running its prefix would make the
-- counter two; the native oracle observes one instead.
{-# OPAQUE shared #-}
shared :: Box
shared = case cells of { Cells ready gate running counter ->
  case readMutVar# counter realWorld# of { (# s1, Box old #) ->
  case writeMutVar# counter (Box (old +# 1#)) s1 of { s2 ->
  case getMaskingState# s2 of { (# _, mask #) ->
  case longLoop ready gate running 10000000# (mask +# 7#) of result -> Box result } } } }

-- Each call is a fresh catch# action, while the `shared` CAF remains identical.
-- A dynamic token prevents the native driver's call from becoming its own CAF.
{-# OPAQUE forceShared #-}
forceShared :: Int# -> Int#
forceShared token =
  case catch# (\s -> case shared of { Box value -> (# s, Box value #) })
              (\_ s -> (# s, Box (-1#) #)) realWorld# of
    (# _, Box value #) -> value +# token

{-# OPAQUE takeReady #-}
takeReady :: Int# -> Int#
takeReady token = case cells of { Cells ready _ _ _ ->
  case takeMVar# ready realWorld# of { (# _, Box value #) -> value +# token } }

-- In a fresh context, releaseGate may run before forceShared. A sender then
-- awaits this signal to know the finite scalar loop has passed its MVar gate.
{-# OPAQUE takeRunning #-}
takeRunning :: Int# -> Int#
takeRunning token = case cells of { Cells _ _ running _ ->
  case takeMVar# running realWorld# of { (# _, Box value #) -> value +# token } }

{-# OPAQUE releaseGate #-}
releaseGate :: Int# -> Int#
releaseGate token = case cells of { Cells _ gate _ _ ->
  case putMVar# gate (Box 1#) realWorld# of { _ -> token +# 1# } }

{-# OPAQUE prefixCount #-}
prefixCount :: Int# -> Int#
prefixCount token = case cells of { Cells _ _ _ counter ->
  case readMutVar# counter realWorld# of { (# _, Box value #) -> value +# token } }
