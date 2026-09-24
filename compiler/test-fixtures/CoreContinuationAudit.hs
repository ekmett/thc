-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module CoreContinuationAudit where

import GHC.Exts

data Box = Box Int#

{-# OPAQUE delayed #-}
delayed :: Int# -> Box
delayed input = runRW# (\state ->
  case noDuplicate# state of _ -> Box (input +# 1#))

{-# OPAQUE answer #-}
answer :: Int# -> Int#
answer input =
  let earlier = input +# 100#
      value = delayed input
  in case value of Box result -> earlier +# result

{-# OPAQUE sharedAnswer #-}
{-# OPAQUE checkpointValue #-}
checkpointValue :: Box
checkpointValue = case noDuplicate# realWorld# of _ -> Box 8#

-- A separately called root has no captured caller segment. It must stay rejected.
{-# OPAQUE uncaptured #-}
uncaptured :: Box
uncaptured = delayed 7#

sharedAnswer :: Int
sharedAnswer =
  case newMutVar# checkpointValue realWorld# of
    (# state1, cell #) -> case getMaskingState# state1 of
      (# state2, mask #) -> case mask of
        0# -> case readMutVar# cell state2 of
          (# _, value #) -> case value of Box result -> I# (100# +# mask +# result)
        _ -> I# 0#
