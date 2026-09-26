-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import GhcBCO
import GHC.Exts (Int(I#))
main :: IO ()
main = mapM_ row [-2, 0, 7]
  where
    row (I# n) = mapM_ print
      [I# (bcoConstant n), I# (bcoApply n), I# (bcoApplyTwo n), I# (bcoFunction n),
       I# (bcoArithmetic n), I# (bcoBranch n), I# (bcoLargeOperand n), I# (bcoSharing n)]
