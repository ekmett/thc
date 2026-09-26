-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- Each example has an explicit strict State# thread. The continuation resumes
-- the suffix, not the whole action; MutVars deliberately remain shared.
module DelimitedContinuations where

import GHC.Exts

{-# OPAQUE scalarApplicationWorker #-}
scalarApplicationWorker :: PromptTag# Int -> State# RealWorld -> Int# -> Int#
scalarApplicationWorker tag s = case control0# tag (\k st -> k (\sx -> (# sx, 17# #)) st) s of
  (# _, value #) -> \n -> value +# n

{-# OPAQUE scalarApplicationWorker2 #-}
scalarApplicationWorker2 :: PromptTag# Int -> State# RealWorld -> Int# -> Int#
scalarApplicationWorker2 tag s = case control0# tag (\k st -> k (\sx -> (# sx, 18# #)) st) s of
  (# _, value #) -> \n -> value +# n

{-# OPAQUE scalarApplicationWorker3 #-}
scalarApplicationWorker3 :: PromptTag# Int -> State# RealWorld -> Int# -> Int#
scalarApplicationWorker3 tag s = case control0# tag (\k st -> k (\sx -> (# sx, 19# #)) st) s of
  (# _, value #) -> \n -> value +# n

{-# OPAQUE scalarApplicationWorker4 #-}
scalarApplicationWorker4 :: PromptTag# Int -> State# RealWorld -> Int# -> Int#
scalarApplicationWorker4 tag s = case control0# tag (\k st -> k (\sx -> (# sx, 20# #)) st) s of
  (# _, value #) -> \n -> value +# n

{-# OPAQUE applyScalarWorker #-}
applyScalarWorker :: (PromptTag# Int -> State# RealWorld -> Int# -> Int#)
  -> PromptTag# Int -> Int# -> State# RealWorld -> (# State# RealWorld, Int #)
applyScalarWorker worker tag n s = case worker tag s n of value -> (# s, I# value #)

{-# OPAQUE polymorphicScalarApplications #-}
polymorphicScalarApplications :: Int# -> Int#
polymorphicScalarApplications n = runRW# $ \s0 -> case newPromptTag# s0 of
  (# s1, tag #) -> case prompt# tag (applyScalarWorker scalarApplicationWorker tag n) s1 of
    (# s2, I# a #) -> case prompt# tag (applyScalarWorker scalarApplicationWorker2 tag n) s2 of
      (# s3, I# b #) -> case prompt# tag (applyScalarWorker scalarApplicationWorker3 tag n) s3 of
        (# s4, I# c #) -> case prompt# tag (applyScalarWorker scalarApplicationWorker4 tag n) s4 of
          (# _, I# d #) -> a +# b +# c +# d +# 100#

{-# OPAQUE resumedScalarApplication #-}
resumedScalarApplication :: Int# -> Int#
resumedScalarApplication n = runRW# $ \s0 -> case newPromptTag# s0 of
  (# s1, tag #) -> case prompt# tag (\s2 -> case scalarApplicationWorker tag s2 n of
    value -> (# s2, I# (value +# 100#) #)) s1 of
      (# _, I# answer #) -> answer

{-# OPAQUE applicationWorker2 #-}
applicationWorker2 :: PromptTag# Int -> State# RealWorld -> Int# -> (# State# RealWorld, Int #)
applicationWorker2 tag s = case control0# tag (\k st -> k (\sx -> (# sx, 18# #)) st) s of
  (# st, value #) -> \n -> (# st, I# (value +# n) #)

{-# OPAQUE applicationWorker3 #-}
applicationWorker3 :: PromptTag# Int -> State# RealWorld -> Int# -> (# State# RealWorld, Int #)
applicationWorker3 tag s = case control0# tag (\k st -> k (\sx -> (# sx, 19# #)) st) s of
  (# st, value #) -> \n -> (# st, I# (value +# n) #)

{-# OPAQUE applicationWorker4 #-}
applicationWorker4 :: PromptTag# Int -> State# RealWorld -> Int# -> (# State# RealWorld, Int #)
applicationWorker4 tag s = case control0# tag (\k st -> k (\sx -> (# sx, 20# #)) st) s of
  (# st, value #) -> \n -> (# st, I# (value +# n) #)

{-# OPAQUE applyWorker #-}
applyWorker :: (PromptTag# Int -> State# RealWorld -> Int# -> (# State# RealWorld, Int #))
  -> PromptTag# Int -> Int# -> State# RealWorld -> (# State# RealWorld, Int #)
applyWorker worker tag n s = worker tag s n

{-# OPAQUE polymorphicApplications #-}
polymorphicApplications :: Int# -> Int#
polymorphicApplications n = runRW# $ \s0 -> case newPromptTag# s0 of
  (# s1, tag #) -> case prompt# tag (applyWorker applicationWorker tag n) s1 of
    (# s2, I# a #) -> case prompt# tag (applyWorker applicationWorker2 tag n) s2 of
      (# s3, I# b #) -> case prompt# tag (applyWorker applicationWorker3 tag n) s3 of
        (# s4, I# c #) -> case prompt# tag (applyWorker applicationWorker4 tag n) s4 of
          (# _, I# d #) -> a +# b +# c +# d +# 100#

{-# OPAQUE applicationWorker #-}
applicationWorker :: PromptTag# Int -> State# RealWorld -> Int# -> (# State# RealWorld, Int #)
applicationWorker tag s = case control0# tag (\k st -> k (\sx -> (# sx, 17# #)) st) s of
  (# st, value #) -> \n -> (# st, I# (value +# n) #)

{-# OPAQUE resumedApplication #-}
resumedApplication :: Int# -> Int#
resumedApplication n = runRW# $ \s0 -> case newPromptTag# s0 of
  (# s1, tag #) -> case prompt# tag (\s2 -> case applicationWorker tag s2 n of
    (# s3, I# value #) -> (# s3, I# (value +# 100#) #)) s1 of
      (# _, I# answer #) -> answer

-- An unlifted result cannot acquire an update frame. The strict call consumes
-- the same State# thread as the enclosing prompt; no nested runRW#/unsafe IO.
{-# OPAQUE scalarWorker #-}
scalarWorker :: PromptTag# Int -> Int# -> State# RealWorld -> Int#
scalarWorker tag count s = case count of
  0# -> 17#
  3# -> case control0# tag (\k st -> k (\sx -> (# sx, 2# #)) st) s of
    (# st, next #) -> scalarWorker tag next st
  _ -> scalarWorker tag (count -# 1#) s

{-# OPAQUE resumedScalar #-}
resumedScalar :: Int# -> Int#
resumedScalar n = runRW# $ \s0 -> case newPromptTag# s0 of
  (# s1, tag #) -> case prompt# tag (\s2 -> case scalarWorker tag 3# s2 of
    value -> (# s2, I# (value +# 100#) #)) s1 of
      (# _, I# answer #) -> answer +# n

-- The second capture crosses a mask return restored by the first resumption.
-- Its saved prior state is now the first resumer's state (1), not the state (0)
-- that surrounded the original prompt.
{-# OPAQUE recapturedMask #-}
recapturedMask :: Int# -> Int#
recapturedMask n = runRW# $ \s0 -> case newPromptTag# s0 of
  (# s1, outer #) -> case newPromptTag# s1 of
    (# s2, inner #) -> case prompt# outer (\s3 -> prompt# inner
      (\s4 -> case maskAsyncExceptions# (\s5 ->
        case control0# inner (\k s6 -> maskUninterruptible#
          (\s7 -> k (\s8 -> (# s8, 0# #)) s7) s6) s5 of
            (# s9, _ #) -> control0# outer (\k s10 -> k
              (\s11 -> (# s11, I# 0# #)) s10) s9) s4 of
                (# s12, _ #) -> case getMaskingState# s12 of
                  (# s13, mask #) -> (# s13, I# (n +# mask) #)) s3) s2 of
                    (# _, I# answer #) -> answer

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
