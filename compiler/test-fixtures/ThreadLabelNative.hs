-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import Control.Exception (evaluate)
import GHC.Exts (Int(I#))
import qualified ThreadLabelAudit as A

main :: IO ()
main = mapM_ check [0, 65, 127]
  where
    checksum seed = foldl (\acc byte -> acc * 31 + byte) 4 [206, 187, 0, seed `mod` 128]
    check seed@(I# raw) = do
      actual <- mapM evaluate [I# (A.selfLabel raw), I# (A.overwriteLabel raw),
        I# (A.emptyLabel raw), I# (A.deadLabel raw), I# (A.deadOverwrite raw)]
      let expected = [checksum seed, checksum (seed + 1), 0, checksum seed, checksum seed]
      if actual == expected then mapM_ print actual
        else error ("Thread label mismatch: " ++ show (seed, expected, actual))
