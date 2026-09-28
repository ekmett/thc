-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Native GHC observer for the hint trace fixture.
module Main where
import GHC.Exts
import Control.Monad (forM_)
import qualified HintTraceAudit as P
main :: IO ()
main = forM_ [-3,0,1,37,999] $ \x@(I# n) -> do
  putStrLn ("hints\t" ++ show x ++ "\t" ++ show (I# (P.hints n)))
  putStrLn ("traces\t" ++ show x ++ "\t" ++ show (I# (P.traces n)))
