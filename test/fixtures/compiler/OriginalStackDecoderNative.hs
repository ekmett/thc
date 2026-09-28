-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE GHCForeignImportPrim, MagicHash, UnboxedTuples, UnliftedFFITypes #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC FFI; declared foreign symbols required at link/run time
--
-- Native GHC observer for the original stack decoder fixture.
module Main (main) where
import Control.Monad (unless)
import GHC.Exts
import GHC.Internal.Stack.CloneStack (StackSnapshot(..))
import OriginalStackDecoder

-- Native-only control: these fresh declarations do not become guest aliases.
-- The JVM counterpart retains the genuine original FCallIds in
-- OriginalStackDecoderCallTest, including their ghc-internal unit identity.
foreign import prim "getWordzh" stackWord# :: StackSnapshot# -> Word# -> Word#
foreign import prim "getInfoTableAddrszh" frameInfo# :: StackSnapshot# -> Word# -> (# Addr#, Addr# #)
headerAgreement :: StackSnapshot -> Int#
headerAgreement (StackSnapshot snapshot#) = case frameInfo# snapshot# 0## of
  (# _, key# #) -> eqWord# (stackWord# snapshot# 0##) (int2Word# (addr2Int# key#))

main :: IO ()
main = do
  let snapshot = captureNamed 1#
      observe (I# probe#) = I# (observeSnapshot snapshot probe#)
      frames = observe (-1)
      stable = observe (-2)
      provenance = observe (-3)
      rendered = observe (-4)
      characters = observe (-5)
      header = I# (headerAgreement snapshot)
  unless (frames > 0 && stable == 1 && header == 1 &&
          provenance >= 0 && provenance <= frames && rendered >= 0 && rendered <= frames && characters >= 0)
    (fail "original decoder/header invariants failed")
  -- Stack sizes and native IPE presence are platform/optimizer dependent.
  putStrLn (unwords (map show [frames, stable, provenance, rendered, characters, header]))
