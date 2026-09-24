-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts (Int(I#))
import Control.Exception (evaluate)
import System.Environment (getArgs)
import qualified ThreadAsyncAudit as Audit

main :: IO ()
main = do
  args <- getArgs
  case args of
    [] -> do
      first <- evaluate (I# (Audit.forkAndThrow 0#))
      second <- evaluate (I# (Audit.forkAndThrow 1#))
      case (first, second) of
        (43, 44) -> putStr "43\n44\n"
        other -> error ("public thread interruption failed: " ++ show other)
    ["extras"] -> do
      uncaught <- evaluate (I# (Audit.killUncaught 0#))
      self <- evaluate (I# (Audit.selfThrow 0#))
      masked <- evaluate (I# (Audit.maskedUnmaskSelf 0#))
      case (uncaught, self, masked) of
        (5, -1, -1) -> putStr "5\n-1\n-1\n"
        other -> error ("public thread extra delivery failed: " ++ show other)
    _ -> error "Usage: ThreadAsyncNative [extras]"
