-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import ForeignImportStubs
import GHC.Exts (Int(I#))
main :: IO ()
main = do
  a <- first (-8)
  b <- second (-9)
  c <- direct (-11)
  print (I# (probe 5#), a, b, seekSet, c)
