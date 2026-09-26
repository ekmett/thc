-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main (main) where
import StableNames
import GHC.Exts (Int(I#))

main :: IO ()
main = mapM_ (\(I# n) -> mapM_ print
  [I# (sameLifted n), I# (sameUnlifted n), I# (differentUnlifted n), I# (unevaluatedName n)])
  [minBound, -1, 0, 1, maxBound]
