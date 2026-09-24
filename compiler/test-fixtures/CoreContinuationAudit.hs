-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module CoreContinuationAudit where

import GHC.Exts

data Box = Box Int#

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
