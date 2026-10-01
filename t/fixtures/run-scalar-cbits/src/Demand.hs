-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, ForeignFunctionInterface #-}

-- | Original ordinary imports for the canonical native-state demand proof.
module Demand (raw, rawSafe, next, nextSafe) where

import GHC.Exts
import GHC.IO (IO(..))

foreign import ccall unsafe "next" raw :: IO Int
foreign import ccall safe "next" rawSafe :: IO Int

next :: State# RealWorld -> Int#
next state = case raw of IO action -> case action state of (# _, I# value #) -> value

nextSafe :: State# RealWorld -> Int#
nextSafe state = case rawSafe of IO action -> case action state of (# _, I# value #) -> value
