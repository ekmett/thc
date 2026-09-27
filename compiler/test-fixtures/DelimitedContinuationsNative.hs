-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Native GHC observer for the delimited continuations fixture.
module Main where
import DelimitedContinuations
import GHC.Exts (Int(I#))
main :: IO ()
main = mapM_ row [-2, 0, 7]
  where
    row (I# n) = mapM_ print
      [I# (promptPure n), I# (abortSuffix n), I# (resumeTwice n), I# (nestedPrompts n),
       I# (sameTagNearest n), I# (capturedCatch n), I# (capturedMask n), I# (escapedResume n), I# (ambientMask n),
       I# (resumedTail n), I# (resumedJoin n), I# (resumedScalar n), I# (recapturedMask n), I# (resumedApplication n),
       I# (resumedScalarApplication n), I# (polymorphicApplications n), I# (polymorphicScalarApplications n)]
