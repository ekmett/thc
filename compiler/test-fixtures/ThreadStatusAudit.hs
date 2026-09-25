-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module ThreadStatusAudit where

import GHC.Exts

data Box = Box Int#

-- Capability numbers depend on the scheduler, but must be nonnegative. fork#
-- does not set TSO_LOCKED. Consume all three Int# fields of the exact tuple.
{-# OPAQUE observe #-}
observe :: ThreadId# -> State# RealWorld -> (# State# RealWorld, Int# #)
observe tid s = case threadStatus# tid s of
  (# s1, status, capability, locked #) ->
    case (capability >=# 0#) `andI#` (locked ==# 0#) of
      1# -> (# s1, status #)
      _ -> (# s1, -999# #)

{-# OPAQUE selfStatus #-}
selfStatus :: Int# -> Int#
selfStatus token = runRW# (\s -> case myThreadId# s of
  (# s1, tid #) -> case observe tid s1 of (# _, status #) -> token +# status)

-- Masking is independent of status and of the capability's TSO_LOCKED bit.
{-# OPAQUE maskedStatus #-}
maskedStatus :: Int# -> Int#
maskedStatus token = case maskUninterruptible# (\s ->
  case myThreadId# s of { (# s1, tid #) ->
  case observe tid s1 of { (# s2, status #) -> (# s2, Box status #) } }) realWorld# of
    (# _, Box status #) -> token +# status

{-# OPAQUE awaitStatus #-}
awaitStatus :: ThreadId# -> Int# -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
awaitStatus tid wanted fuel s = case observe tid s of
  (# s1, status #) -> case status ==# wanted of
    1# -> (# s1, status #)
    _ -> case fuel ># 0# of
      1# -> case yield# s1 of s2 -> awaitStatus tid wanted (fuel -# 1#) s2
      _ -> (# s1, -999# #)

{-# OPAQUE finishedStatus #-}
finishedStatus :: Int# -> Int#
finishedStatus token = case fork# (\s -> (# s, () #)) realWorld# of
  (# s1, tid #) -> case awaitStatus tid 16# 1000000# s1 of
    (# _, status #) -> token +# status

-- Raw fork# has no forkIO exception-reporting wrapper to consume this failure.
{-# OPAQUE diedChild #-}
diedChild :: State# RealWorld -> (# State# RealWorld, () #)
diedChild s = raiseIO# (Box 7#) s

{-# OPAQUE diedStatus #-}
diedStatus :: Int# -> Int#
diedStatus token = case fork# diedChild realWorld# of
  (# s1, tid #) -> case awaitStatus tid 17# 1000000# s1 of
    (# _, status #) -> token +# status

{-# OPAQUE blockedStatus #-}
blockedStatus :: Int# -> Int# -> Int#
blockedStatus reader token =
  case newMVar# realWorld# of { (# s1, gate #) ->
  case fork# (\s -> case reader of
    0# -> case takeMVar# gate s of (# s2, Box _ #) -> (# s2, () #)
    _ -> case readMVar# gate s of (# s2, Box _ #) -> (# s2, () #)) s1 of { (# s2, tid #) ->
  case awaitStatus tid (case reader of { 0# -> 1#; _ -> 14# }) 1000000# s2 of { (# s3, blocked #) ->
  case putMVar# gate (Box 42#) s3 of { s4 ->
  case awaitStatus tid 16# 1000000# s4 of { (# _, finished #) ->
    case finished ==# 16# of { 1# -> token +# blocked; _ -> -999# }
  } } } } }
