-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module StableNames (sameLifted, sameUnlifted, differentUnlifted, unevaluatedName) where

import GHC.Exts

-- Names compare by identity, while their integer hashes need only agree for
-- equal names. In particular, the test does not prescribe native hash values.
sameLifted :: Int# -> Int#
sameLifted n = runRW# (\s0 ->
  let value = I# n
  in case makeStableName# value s0 of
    (# s1, a #) -> case makeStableName# value s1 of
      (# _, b #) -> eqStableName# a b +#
        (stableNameToInt# a ==# stableNameToInt# b))

sameUnlifted :: Int# -> Int#
sameUnlifted n = runRW# (\s0 ->
  case newMutVar# (I# n) s0 of
    (# s1, cell #) -> case makeStableName# cell s1 of
      (# s2, a #) -> case makeStableName# cell s2 of
        (# _, b #) -> eqStableName# a b +#
          (stableNameToInt# a ==# stableNameToInt# b))

differentUnlifted :: Int# -> Int#
differentUnlifted n = runRW# (\s0 ->
  case newMutVar# (I# n) s0 of
    (# s1, first #) -> case newMutVar# (I# n) s1 of
      (# s2, second #) -> case makeStableName# first s2 of
        (# s3, a #) -> case makeStableName# second s3 of
          (# _, b #) -> eqStableName# a b)

unevaluatedName :: Int# -> Int#
unevaluatedName n = runRW# (\s0 ->
  let loop :: Int
      loop = loop
  in case makeStableName# loop s0 of
    (# s1, a #) -> case makeStableName# loop s1 of
      (# _, b #) -> if isTrue# (eqStableName# a b) then n else 0#)
