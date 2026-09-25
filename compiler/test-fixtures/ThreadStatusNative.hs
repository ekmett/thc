-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import Control.Exception (evaluate)
import GHC.Exts (Int(I#))
import qualified ThreadStatusAudit as Audit

main :: IO ()
main = do
  results <- mapM evaluate
    [I# (Audit.selfStatus 0#), I# (Audit.maskedStatus 0#),
     I# (Audit.finishedStatus 0#), I# (Audit.diedStatus 0#),
     I# (Audit.blockedStatus 0# 0#), I# (Audit.blockedStatus 1# 0#)]
  if results == [0, 0, 16, 17, 1, 14]
    then mapM_ print results
    else error ("threadStatus# contract disagreed: " ++ show results)
