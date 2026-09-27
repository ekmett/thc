-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Executable for the @run-benchmark@ integration fixture.
module Main (main) where
import GHC.Exts
import GHC.IO (IO(..))
import Check (answer)

{-# NOINLINE total #-}
total :: Int# -> Int# -> Int#
total n acc = case n ==# 0# of
  1# -> acc
  _ -> total (n -# 1#) (acc +# n)

{-# OPAQUE main #-}
main :: IO ()
main = IO $ \s0 ->
  case newMutVar# answer s0 of { (# s1, ref #) ->
  case readMutVar# ref s1 of { (# s2, I# start #) ->
  case total 1000# start ==# 500542# of {
    1# -> (# s2, () #);
    _ -> raise# (I# start)
  } } }
