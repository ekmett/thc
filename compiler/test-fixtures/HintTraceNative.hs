-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import Control.Monad (forM_)
import qualified HintTraceAudit as P
main :: IO ()
main = forM_ [-3,0,1,37,999] $ \x@(I# n) -> do
  putStrLn ("hints\t" ++ show x ++ "\t" ++ show (I# (P.hints n)))
  putStrLn ("traces\t" ++ show x ++ "\t" ++ show (I# (P.traces n)))
