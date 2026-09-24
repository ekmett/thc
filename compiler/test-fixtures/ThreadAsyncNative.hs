-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts (Int(I#))
import Control.Exception (evaluate)
import qualified ThreadAsyncAudit as Audit

main :: IO ()
main = do
  first <- evaluate (I# (Audit.forkAndThrow 0#))
  second <- evaluate (I# (Audit.forkAndThrow 1#))
  case (first, second) of
    (43, 44) -> putStr "43\n44\n"
    other -> error ("public thread interruption failed: " ++ show other)
