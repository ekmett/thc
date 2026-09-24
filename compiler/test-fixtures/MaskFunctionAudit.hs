-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module MaskFunctionAudit where

import GHC.Exts

data Payload = Payload Int#
type Action = State# RealWorld -> (# State# RealWorld, Payload #)

{-# OPAQUE observe #-}
observe :: Int# -> Action
observe raw s = case getMaskingState# s of
  (# s1, during #) -> (# s1, Payload (raw +# during *# 10#) #)

-- The opaque boundary retains the function argument. Observing its WHNF must
-- not enter its mask or force its captured action. Only the later state-token
-- application executes the masked action, and the caller's mask is restored.
{-# OPAQUE applyLater #-}
applyLater :: Action -> Action
applyLater action s = case action of
  ready -> case getMaskingState# s of
    (# s1, before #) -> case ready s1 of
      (# s2, Payload result #) -> case getMaskingState# s2 of
        (# s3, after #) -> (# s3, Payload (before +# result +# after *# 100#) #)

{-# OPAQUE maskedFunction #-}
maskedFunction :: Int# -> Int#
maskedFunction raw = runRW# (\s ->
  case maskUninterruptible# (\s1 -> applyLater (maskAsyncExceptions# (observe raw)) s1) s of
    (# _, Payload result #) -> result)

{-# OPAQUE unmaskedFunction #-}
unmaskedFunction :: Int# -> Int#
unmaskedFunction raw = runRW# (\s ->
  case maskUninterruptible# (\s1 -> applyLater (unmaskAsyncExceptions# (observe raw)) s1) s of
    (# _, Payload result #) -> result)

{-# OPAQUE uninterruptibleFunction #-}
uninterruptibleFunction :: Int# -> Int#
uninterruptibleFunction raw = runRW# (\s ->
  case maskAsyncExceptions# (\s1 -> applyLater (maskUninterruptible# (observe raw)) s1) s of
    (# _, Payload result #) -> result)

{-# OPAQUE discardLater #-}
discardLater :: Action -> Int# -> Int#
discardLater action raw = case raw ==# 10000# of
  0# -> action `seq` raw
  _ -> runRW# (\s -> case action s of (# _, Payload result #) -> result)

-- These partial primitives are functions even with bottom captured as action.
{-# OPAQUE lazyFunctions #-}
lazyFunctions :: Int# -> Int#
lazyFunctions raw =
  discardLater (maskAsyncExceptions# (raise# (Payload 1#)))
    (discardLater (unmaskAsyncExceptions# (raise# (Payload 2#)))
      (discardLater (maskUninterruptible# (raise# (Payload 3#))) raw))

{-# OPAQUE applyMask #-}
applyMask :: (Action -> Action) -> Action -> Action
applyMask mask action s = mask action s

-- Also keep the type-instantiated primitive itself as a higher-order value.
{-# OPAQUE bareMasks #-}
bareMasks :: Int# -> Int#
bareMasks raw = runRW# (\s ->
  case applyMask maskAsyncExceptions#
    (applyMask unmaskAsyncExceptions#
      (applyMask maskUninterruptible# (observe raw))) s of
    (# _, Payload result #) -> result)
