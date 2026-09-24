-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where

import Control.Exception (evaluate)
import GHC.Exts (Int(I#))
import qualified LazyForkAudit as Audit

main :: IO ()
main = do
  first <- evaluate (I# (Audit.lazyFork 0#))
  second <- evaluate (I# (Audit.lazyFork 1#))
  case (first, second) of
    (52, 53) -> putStr "52\n53\n"
    other -> error ("lazy fork action disagreed: " ++ show other)
