-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import InterfaceLibrary
main :: IO ()
main = mapM_ row [-10..10]
  where row value@(I# n) = putStrLn $ unwords
          [show value, show (I# (opaqueEntry n)), show (I# (inlineEntry n)), show (I# (recursiveEntry n))]
