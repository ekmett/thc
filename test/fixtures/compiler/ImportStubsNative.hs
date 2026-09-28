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
-- Native GHC observer for the import stubs fixture.
module Main where
import ForeignImportStubs
import GHC.Exts (Int(I#))
main :: IO ()
main = do
  a <- first (-8)
  b <- second (-9)
  c <- direct (-11)
  print (I# (probe 5#), a, b, seekSet, c)
