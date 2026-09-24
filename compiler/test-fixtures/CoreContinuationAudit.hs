-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module CoreContinuationAudit where

import GHC.Exts

data Box = Box Int#

{-# OPAQUE asyncPayload #-}
asyncPayload :: Box
asyncPayload = Box 7#

{-# OPAQUE delayed #-}
delayed :: Int# -> Box
delayed input = case noDuplicate# realWorld# of _ -> Box (input +# 1#)

{-# OPAQUE checkpointValue #-}
checkpointValue :: Box
checkpointValue = case noDuplicate# realWorld# of _ -> Box 8#

-- A separately called root has no captured caller segment. It must stay rejected.
{-# OPAQUE uncaptured #-}
uncaptured :: Box
uncaptured = delayed 7#

-- Work remains after this saturated call, so its caller must retain a segment.
{-# OPAQUE applicationAnswer #-}
applicationAnswer :: Int
applicationAnswer = case delayed 7# of Box result -> I# (200# +# result)

-- The private checkpoint may suspend an original catch# IO action before it
-- produces its unboxed tuple. The handler remains a genuine GHC Core handler.
{-# OPAQUE catchActionAnswer #-}
catchActionAnswer :: Int
catchActionAnswer =
  case catch# (\s0 -> case noDuplicate# s0 of { s1 ->
                 case noDuplicate# s1 of { s2 -> (# s2, Box 42# #) } })
              (\(Box value) s3 -> (# s3, Box (value +# 100#) #)) realWorld# of
    (# _, Box result #) -> I# result

{-# OPAQUE catchActionFailure #-}
catchActionFailure :: Int
catchActionFailure =
  case catch# (\s0 -> case noDuplicate# s0 of s1 -> raiseIO# (Box 7#) s1)
              (\(Box value) s2 -> (# s2, Box (value +# 70#) #)) realWorld# of
    (# _, Box result #) -> I# result

-- The inner handler is the nearest continuation cut. Its action can later
-- resume from the same saved checkpoint after the handler has completed.
{-# OPAQUE nestedCatchAction #-}
nestedCatchAction :: Int
nestedCatchAction =
  case catch# (\s0 ->
         case catch# (\s1 -> case noDuplicate# s1 of s2 -> (# s2, Box 42# #))
                     (\(Box inner) s3 -> (# s3, Box (inner +# 70#) #)) s0 of
           (# s4, Box innerResult #) -> (# s4, Box (innerResult +# 1#) #))
              (\(Box outer) s5 -> (# s5, Box (outer +# 1000#) #)) realWorld# of
    (# _, Box result #) -> I# result

-- A nested lambda owns this yield, not the directly called function root.
{-# OPAQUE nestedDelayed #-}
nestedDelayed :: Int# -> Box
nestedDelayed input = runRW# (\state ->
  case noDuplicate# state of _ -> Box (input +# 1#))

{-# OPAQUE nestedApplication #-}
nestedApplication :: Int
nestedApplication = case nestedDelayed 7# of Box result -> I# (200# +# result)

{-# OPAQUE sharedAnswer #-}
sharedAnswer :: Int
sharedAnswer =
  case newMutVar# checkpointValue realWorld# of
    (# state1, cell #) -> case getMaskingState# state1 of
      (# state2, mask #) -> case mask of
        0# -> case readMutVar# cell state2 of
          (# _, value #) -> case value of Box result -> I# (100# +# mask +# result)
        _ -> I# 0#

-- A saturated ordinary call returns nested typed tuple fields. The caller
-- has a checkpoint before the call and work after all fields are projected.
{-# OPAQUE tupleDelayed #-}
tupleDelayed :: Int# -> Box -> State# RealWorld -> (# State# RealWorld, Int#, (# State# RealWorld, Box #) #)
tupleDelayed input box s0 =
  case noDuplicate# s0 of { s1 ->
    case noDuplicate# s1 of { s2 -> (# s2, input +# 1#, (# s2, box #) #) } }

{-# OPAQUE tupleApplicationAnswer #-}
tupleApplicationAnswer :: Int
tupleApplicationAnswer =
  case noDuplicate# realWorld# of { s0 ->
    case tupleDelayed 6# (Box 7#) s0 of
      (# _, left, (# _, Box right #) #) -> I# (100# +# left +# right) }

{-# OPAQUE tupleApplicationFailure #-}
tupleApplicationFailure :: Int
tupleApplicationFailure =
  case tupleDelayed 6# (Box 7#) realWorld# of
    (# _, left, (# _, Box right #) #) ->
      case left ==# 7# of
        1# -> raise# (Box 9#)
        _  -> I# (100# +# left +# right)

-- A zero-width tuple argument selects the compact argument transport while
-- the result retains the same nested two-field physical tuple shape.
{-# OPAQUE tupleDelayedCompact #-}
tupleDelayedCompact :: (# #) -> Int# -> Box -> State# RealWorld -> (# State# RealWorld, Int#, (# State# RealWorld, Box #) #)
tupleDelayedCompact _ input box s0 =
  case noDuplicate# s0 of { s1 -> (# s1, input +# 1#, (# s1, box #) #) }

{-# OPAQUE tupleCompactAnswer #-}
tupleCompactAnswer :: Int
tupleCompactAnswer =
  case tupleDelayedCompact (# #) 6# (Box 7#) realWorld# of
    (# _, left, (# _, Box right #) #) -> I# (100# +# left +# right)

{-# OPAQUE tupleDelayedRaise #-}
tupleDelayedRaise :: Int# -> Box -> State# RealWorld -> (# State# RealWorld, Int#, (# State# RealWorld, Box #) #)
tupleDelayedRaise input box s0 =
  case noDuplicate# s0 of { s1 ->
    case input ==# 0# of
      1# -> raise# (Box 9#)
      _  -> (# s1, input +# 1#, (# s1, box #) #) }

{-# OPAQUE tupleRaiseAnswer #-}
tupleRaiseAnswer :: Int
tupleRaiseAnswer =
  case tupleDelayedRaise 0# (Box 7#) realWorld# of
    (# _, left, (# _, Box right #) #) -> I# (100# +# left +# right)

-- The original catch# handler itself is a resumable tuple-producing callee.
-- Its masking state remains logical when a different host thread resumes it.
{-# OPAQUE catchHandlerAnswer #-}
catchHandlerAnswer :: Int
catchHandlerAnswer =
  case catch# (\s0 -> case noDuplicate# s0 of s1 -> raiseIO# (Box 7#) s1)
              (\(Box value) s2 -> case noDuplicate# s2 of { s3 ->
                case noDuplicate# s3 of { s4 ->
                  case getMaskingState# s4 of
                    (# s5, mask #) -> (# s5, Box (value +# 70# +# mask) #) } })
              realWorld# of
    (# s6, Box result #) -> case getMaskingState# s6 of
      (# _, outside #) -> I# (result +# 100# *# outside)

-- Each original mask primop owns a two-checkpoint IO action. A mask query
-- after the action proves the enclosing restoration is delayed until exit.
{-# OPAQUE maskedCheckpointAnswer #-}
maskedCheckpointAnswer :: Int
maskedCheckpointAnswer =
  case maskAsyncExceptions# (\s0 -> case noDuplicate# s0 of { s1 ->
    case noDuplicate# s1 of { s2 -> case getMaskingState# s2 of
      (# s3, inside #) -> (# s3, Box inside #) } }) realWorld# of
    (# s4, Box inside #) -> case getMaskingState# s4 of
      (# _, outside #) -> I# (inside +# 100# *# outside)

{-# OPAQUE unmaskedCheckpointAnswer #-}
unmaskedCheckpointAnswer :: Int
unmaskedCheckpointAnswer =
  case unmaskAsyncExceptions# (\s0 -> case noDuplicate# s0 of { s1 ->
    case noDuplicate# s1 of { s2 -> case getMaskingState# s2 of
      (# s3, inside #) -> (# s3, Box inside #) } }) realWorld# of
    (# s4, Box inside #) -> case getMaskingState# s4 of
      (# _, outside #) -> I# (inside +# 100# *# outside)

{-# OPAQUE uninterruptibleCheckpointAnswer #-}
uninterruptibleCheckpointAnswer :: Int
uninterruptibleCheckpointAnswer =
  case maskUninterruptible# (\s0 -> case noDuplicate# s0 of { s1 ->
    case noDuplicate# s1 of { s2 -> case getMaskingState# s2 of
      (# s3, inside #) -> (# s3, Box inside #) } }) realWorld# of
    (# s4, Box inside #) -> case getMaskingState# s4 of
      (# _, outside #) -> I# (inside +# 100# *# outside)

{-# OPAQUE delayedTwice #-}
delayedTwice :: Int# -> Box
delayedTwice input = case noDuplicate# realWorld# of { s1 ->
  case noDuplicate# s1 of _ -> Box (input +# 1#) }

-- A global application is a nonlocal, lazy operand of the outer case.
{-# OPAQUE delayedTwiceGlobal #-}
delayedTwiceGlobal :: Box
delayedTwiceGlobal = case delayedTwice 6# of Box result -> Box (result +# 1#)

{-# OPAQUE delayedTwiceWarm #-}
delayedTwiceWarm :: Box
delayedTwiceWarm = case delayedTwice 8# of Box result -> Box (result +# 1#)

{-# OPAQUE forceNonlocalAnswer #-}
forceNonlocalAnswer :: Int
forceNonlocalAnswer =
  case noDuplicate# realWorld# of { s0 ->
    case getMaskingState# s0 of
      (# _, mask #) -> case mask of
        0# -> case delayedTwiceGlobal of Box result -> I# (200# +# result)
        _ -> case delayedTwiceWarm of Box result -> I# (200# +# result) }
