-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import DelimitedContinuations
import GHC.Exts (Int(I#))
main :: IO ()
main = mapM_ row [-2, 0, 7]
  where
    row (I# n) = mapM_ print
      [I# (promptPure n), I# (abortSuffix n), I# (resumeTwice n), I# (nestedPrompts n),
       I# (sameTagNearest n), I# (capturedCatch n), I# (capturedMask n), I# (escapedResume n), I# (ambientMask n)]
