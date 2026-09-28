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
-- Native GHC observer for the original stack formatter fixture.
module Main (main) where

import GHC.Exts (Int(I#))
import OriginalStackFormatter (formatOriginal)

main :: IO ()
main = mapM_ row [0..5]
  where
    row number@(I# n) = mapM_ (observe number n) [0..100]
    observe number n index@(I# i) =
      putStrLn (show number ++ "\t" ++ show index ++ "\t" ++ show (I# (formatOriginal n i)))
