-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts
import RecordFieldClient

main :: IO ()
main = mapM_ row [-100, -10, -1, 0, 1, 10, 100]
  where row value@(I# n) = putStrLn (unwords
          [show value, show (I# (fieldAlias n)), show (I# (duplicateFields n))])
