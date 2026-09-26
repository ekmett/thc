-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Main (main) where
import Control.Monad (forM_)
import GHC.Exts (Int(I#))
import GHC.IO (IO(..))
import SignalDispatchAudit

main :: IO ()
main = forM_ [1,2,3,15] $ \signal@(I# n) -> do
  installed <- IO $ \s -> case setupHandler n s of (# s1, result #) -> (# s1, I# result #)
  print installed
  dispatchNative signal
  observed <- IO $ \s -> case awaitHandler n s of (# s1, result #) -> (# s1, I# result #)
  print observed
