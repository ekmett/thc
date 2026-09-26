-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, UnliftedFFITypes #-}
module CompilerRtsAudit where

import GHC.Exts
import GHC.Ptr (Ptr(..))

-- Higher-order consumers are specialized with the genuine installed compiler
-- FCallIds by CompilerRtsFixtures. These are test consumers, not compiler bodies.
originalKeep :: (State# RealWorld -> (# State# RealWorld, Int# #)) -> Int# -> Int#
originalKeep call x = case call realWorld# of (# _, answer #) -> x +# answer

originalFast :: (Addr# -> State# RealWorld -> (# State# RealWorld, Addr# #)) -> Int# -> Int#
originalFast call x = case call nullAddr# realWorld# of
  (# s, before #) -> case eqAddr# before nullAddr# of
    0# -> case call nullAddr# s of (# _, after #) -> x +# eqAddr# before after
    _ -> case makeStablePtr# (I# x) s of
      (# s1, candidate #) -> case call (unsafeCoerce# candidate) s1 of
        (# s2, installed #) -> case call nullAddr# s2 of
          (# _, after #) -> x +# (eqAddr# installed after *# eqAddr# installed (unsafeCoerce# candidate))

nativeKeep :: (State# RealWorld -> (# State# RealWorld, Int# #)) -> Int -> Int
nativeKeep call (I# x) = I# (originalKeep call x)

nativeFast :: (Addr# -> State# RealWorld -> (# State# RealWorld, Addr# #)) -> Int -> Int
nativeFast call (I# x) = I# (originalFast call x)

foreign import ccall unsafe "&ghc_unique_counter64" counter :: Ptr Word
foreign import ccall unsafe "&ghc_unique_inc" increment :: Ptr Int

-- Advance the actual native compiler counter rather than reset/corrupt its
-- live unique supply. The observation is independent of its starting value.
uniqueCells :: Int# -> Int#
uniqueCells x = case counter of
  Ptr p -> case increment of
    Ptr q -> case readIntOffAddr# q 0# realWorld# of
      (# s0, step #) -> case fetchAddWordAddr# p 3## s0 of
        (# s1, before #) -> case atomicReadWordAddr# p s1 of
          (# _, after #) -> x +# word2Int# (minusWord# after before) +# step

nativeUnique :: Int -> Int
nativeUnique (I# x) = I# (uniqueCells x)
