-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module OriginalSplitmixEntry (sample) where

import GHC.Exts (State#, RealWorld, Word64#)
import GHC.IO (IO(..))
import GHC.Word (Word64(W64#))
import System.Random.SplitMix (initSMGen, nextWord64)

-- Scalar host observation of the original public IO action. The original
-- package's safe initializer and C buffer remain unchanged.
{-# NOINLINE sample #-}
sample :: State# RealWorld -> Word64#
sample state = case initSMGen of
  IO action -> case action state of
    (# _, generator #) -> case nextWord64 generator of
      (word, _) -> case word of W64# bits -> bits
