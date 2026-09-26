-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- Each example has an explicit strict State# thread. The continuation resumes
-- the suffix, not the whole action; MutVars deliberately remain shared.
module DelimitedContinuations where

import GHC.Exts

{-# OPAQUE resumedJoin #-}
resumedJoin :: Int# -> Int#
resumedJoin n = runRW# $ \s0 -> case newPromptTag# s0 of
  (# s1, tag #) -> case prompt# tag (\s2 ->
    let go count s = case count of
          0# -> (# s, I# (17# +# n) #)
          3# -> case control0# tag (\k st -> k (\sx -> (# sx, 2# #)) st) s of
            (# st, next #) -> go next st
          _ -> go (count -# 1#) s
    in go 3# s2) s1 of
      (# _, I# answer #) -> answer +# 100#

{-# OPAQUE tailWorker #-}
tailWorker :: PromptTag# Int -> Int# -> State# RealWorld -> (# State# RealWorld, Int #)
tailWorker tag count s = case count of
  0# -> (# s, I# 17# #)
  3# -> case control0# tag (\k st -> k (\sx -> (# sx, 2# #)) st) s of
    (# st, next #) -> tailWorker tag next st
  _ -> tailWorker tag (count -# 1#) s

{-# OPAQUE resumedTail #-}
resumedTail :: Int# -> Int#
resumedTail n = runRW# $ \s0 -> case newPromptTag# s0 of
  (# s1, tag #) -> case prompt# tag (\s2 -> case tailWorker tag 3# s2 of
    (# s3, I# value #) -> (# s3, I# (value +# 100#) #)) s1 of
    (# _, I# answer #) -> answer +# n

data Saved = Answer Int | Suspended
  ((State# RealWorld -> (# State# RealWorld, Int# #)) -> State# RealWorld -> (# State# RealWorld, Saved #))

{-# OPAQUE escapedResume #-}
escapedResume :: Int# -> Int#
escapedResume n = runRW# $ \s0 -> case newPromptTag# s0 of
  (# s1, tag #) -> case prompt# tag (\s2 ->
    case control0# tag (\k s -> (# s, Suspended k #)) s2 of
      (# s3, value #) -> (# s3, Answer (I# (value +# n)) #)) s1 of
        (# s4, result #) -> case result of
          Answer _ -> -999#
          Suspended k -> case k (\s -> (# s, 5# #)) s4 of
            (# s5, firstResult #) -> case firstResult of
              Suspended _ -> -999#
              Answer (I# first) -> case k (\s -> (# s, 9# #)) s5 of
                (# _, secondResult #) -> case secondResult of
                  Suspended _ -> -999#
                  Answer (I# second) -> first +# second

{-# OPAQUE ambientMask #-}
ambientMask :: Int# -> Int#
ambientMask n = runRW# $ \s0 -> case newPromptTag# s0 of
  (# s1, tag #) -> case maskAsyncExceptions# (\s2 ->
    prompt# tag (\s3 -> control0# tag (\k s4 -> unmaskAsyncExceptions#
      (\s5 -> k (\s6 -> case getMaskingState# s6 of
        (# s7, mask #) -> (# s7, I# (mask +# n) #)) s5) s4) s3) s2) s1 of
          (# _, I# answer #) -> answer

{-# OPAQUE promptPure #-}
promptPure :: Int# -> Int#
promptPure n = runRW# $ \s0 -> case newPromptTag# s0 of
  (# s1, tag #) -> case prompt# tag (\s -> (# s, I# (n +# 7#) #)) s1 of
    (# _, I# answer #) -> answer

{-# OPAQUE abortSuffix #-}
abortSuffix :: Int# -> Int#
abortSuffix n = runRW# $ \s0 -> case newPromptTag# s0 of
  (# s1, tag #) -> case prompt# tag (\s2 ->
    case control0# tag (\_ s -> (# s, I# n #)) s2 of
      (# s3, x #) -> (# s3, I# (x +# 1000#) #)) s1 of
        (# _, I# answer #) -> answer

{-# OPAQUE resumeTwice #-}
resumeTwice :: Int# -> Int#
resumeTwice n = runRW# $ \s0 -> case newMutVar# (I# 0#) s0 of
  (# s1, counter #) -> case newPromptTag# s1 of
    (# s2, tag #) -> case prompt# tag (\s3 ->
      case writeMutVar# counter (I# 1#) s3 of
        s4 -> case control0# tag (\k s5 ->
          case k (\s -> (# s, n #)) s5 of
            (# s6, I# first #) -> case k (\s -> (# s, n +# 2# #)) s6 of
              (# s7, I# second #) -> (# s7, I# (first *# 100# +# second) #)) s4 of
                (# s8, value #) -> case readMutVar# counter s8 of
                  (# s9, I# count #) -> case writeMutVar# counter (I# (count +# 1#)) s9 of
                    s10 -> (# s10, I# (value +# count) #)) s2 of
                      (# s11, I# answer #) -> case readMutVar# counter s11 of
                        (# _, I# count #) -> answer *# 10# +# count

{-# OPAQUE nestedPrompts #-}
nestedPrompts :: Int# -> Int#
nestedPrompts n = runRW# $ \s0 -> case newPromptTag# s0 of
  (# s1, outer #) -> case newPromptTag# s1 of
    (# s2, inner #) -> case prompt# outer (\s3 ->
      case prompt# inner (\s4 ->
        case control0# outer (\k s5 -> k (\s -> (# s, n #)) s5) s4 of
          (# s6, x #) -> case control0# inner (\_ s -> (# s, I# (x +# 11#) #)) s6 of
            (# s7, y #) -> (# s7, I# (y +# 1000#) #)) s3 of
              (# s8, I# value #) -> (# s8, I# (value +# 100#) #)) s2 of
                (# _, I# answer #) -> answer

{-# OPAQUE sameTagNearest #-}
sameTagNearest :: Int# -> Int#
sameTagNearest n = runRW# $ \s0 -> case newPromptTag# s0 of
  (# s1, tag #) -> case prompt# tag (\s2 ->
    case prompt# tag (\s3 -> control0# tag (\_ s -> (# s, I# n #)) s3) s2 of
      (# s4, I# value #) -> (# s4, I# (value +# 100#) #)) s1 of
        (# _, I# answer #) -> answer

{-# OPAQUE capturedCatch #-}
capturedCatch :: Int# -> Int#
capturedCatch n = runRW# $ \s0 -> case newPromptTag# s0 of
  (# s1, tag #) -> case prompt# tag (\s2 -> catch#
    (\s3 -> case control0# tag (\k s4 -> k (\s -> raiseIO# (I# n) s) s4) s3 of
      (# s5, value #) -> (# s5, value #))
    (\(I# value) s -> (# s, I# (value +# 17#) #)) s2) s1 of
      (# _, I# answer #) -> answer

{-# OPAQUE capturedMask #-}
capturedMask :: Int# -> Int#
capturedMask n = runRW# $ \s0 -> case newPromptTag# s0 of
  (# s1, tag #) -> case prompt# tag (\s2 -> maskAsyncExceptions#
    (\s3 -> case control0# tag (\k s4 -> maskUninterruptible#
      (\s5 -> case k (\s6 -> case getMaskingState# s6 of
        (# s7, mask #) -> (# s7, I# mask #)) s5 of
          (# s8, I# inside #) -> case getMaskingState# s8 of
            (# s9, outside #) -> (# s9, I# (inside *# 10# +# outside +# n) #)) s4) s3 of
              (# s10, value #) -> (# s10, value #)) s2) s1 of
                (# _, I# answer #) -> answer
