-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where

import System.Mem (performGC)
import TopHandlerMainThreadAbi (registerCurrent)

main :: IO ()
main = do
  registerCurrent
  performGC
  putStrLn "registered"
