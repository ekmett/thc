-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where

import Control.Monad (unless)
import GHC.Internal.Conc.Sync (myThreadId)
import GHC.Internal.Weak (deRefWeak)
import System.Mem (performGC)
import TopHandlerMainThreadAbi (registerCurrent)

main :: IO ()
main = do
  weak <- registerCurrent
  performGC
  observed <- deRefWeak weak
  thread <- myThreadId
  unless (observed == Just thread) (fail "live main-thread weak key disappeared")
  putStrLn "registered"
