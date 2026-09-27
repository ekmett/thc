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
-- Native GHC observer for the interface fixture.
module Main where
import GHC.Exts
import InterfaceLibrary
import CBVCoercionAudit (coercionEntry)
main :: IO ()
main = mapM_ row [-10..10]
  where row value@(I# n) = putStrLn $ unwords
          [show value, show (I# (opaqueEntry n)), show (I# (inlineEntry n)), show (I# (recursiveEntry n)), show (I# (coercionEntry n)), show (I# (wrapperEntry n))]
