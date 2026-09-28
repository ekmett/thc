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
-- Native observer of fixed scalar exception and mask results.
module Main (main) where

import GHC.Exts (Int(I#))
import ScalarExceptionResultsAudit

main :: IO ()
main = mapM_ emit
  [("normalInt", \(I# n) -> I# (normalInt n)),
   ("normalWord", \(I# n) -> I# (normalWord n)),
   ("normalAddr", \(I# n) -> I# (normalAddr n)),
   ("throwInt", \(I# n) -> I# (throwInt n)),
   ("throwWord", \(I# n) -> I# (throwWord n)),
   ("throwAddr", \(I# n) -> I# (throwAddr n)),
   ("interruptInt", \(I# n) -> I# (interruptInt n)),
   ("interruptWord", \(I# n) -> I# (interruptWord n)),
   ("interruptAddr", \(I# n) -> I# (interruptAddr n))]
  where
    emit (name, run) = mapM_ (\n -> putStrLn (name ++ "\t" ++ show n ++ "\t" ++ show (run n)))
      [-129, -17, 0, 23]
