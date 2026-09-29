-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module ParkedControl where

import GHC.Exts

{-# OPAQUE walk #-}
walk :: PromptTag# Int -> MutVar# RealWorld Int -> MutVar# RealWorld Int
     -> Int# -> State# RealWorld -> (# State# RealWorld, Int #)
walk tag prefixes suffixes depth s0 = case depth of
  0# -> case control0# tag (\k s1 ->
    case k (\s -> (# s, 11# #)) s1 of
      (# s2, I# first #) -> case k (\s -> (# s, 23# #)) s2 of
        (# s3, I# second #) -> (# s3, I# (first *# 1000000000# +# second *# 1000000#) #)) s0 of
          (# s4, value #) -> (# s4, I# value #)
  _ -> case readMutVar# prefixes s0 of
    (# s1, I# count #) -> case writeMutVar# prefixes (I# (count +# 1#)) s1 of
      s2 -> case walk tag prefixes suffixes (depth -# 1#) s2 of
        (# s3, I# value #) -> case readMutVar# suffixes s3 of
          (# s4, I# after #) -> case writeMutVar# suffixes (I# (after +# 1#)) s4 of
            s5 -> (# s5, I# (value +# 1#) #)

{-# OPAQUE observe #-}
observe :: Int# -> Int#
observe depth = runRW# $ \s0 -> case newMutVar# (I# 0#) s0 of
  (# s1, prefixes #) -> case newMutVar# (I# 0#) s1 of
    (# s2, suffixes #) -> case newPromptTag# s2 of
      (# s3, tag #) -> case prompt# tag (walk tag prefixes suffixes depth) s3 of
        (# s4, I# answer #) -> case readMutVar# prefixes s4 of
          (# s5, I# before #) -> case readMutVar# suffixes s5 of
            (# _, I# after #) -> answer +# before *# 1000# +# after
