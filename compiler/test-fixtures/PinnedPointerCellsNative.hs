-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts (Int(I#))
import qualified PinnedPointerCellsAudit as Cells

main :: IO ()
main = getContents >>= mapM_ answer . lines
  where
    answer text = case read text of
      I# raw -> putStrLn (text ++ "\t" ++ show (I# (Cells.pointerRoundtrip raw)))
