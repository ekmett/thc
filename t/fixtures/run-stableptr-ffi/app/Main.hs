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
-- Executable for the @run-stableptr-ffi@ integration fixture.
module Main (main) where
import GHC.Exts (Int(..))
import StableForeign
main :: IO ()
main = mapM_ (\(name, invoke) -> mapM_ (\value -> putStrLn
  (name ++ "\t" ++ show value ++ "\t" ++ show (invoke value))) [-100,-1,0,1,42,100000])
  [("stableRoundtrip", \(I# n) -> I# (stableRoundtrip n)),
   ("stableLazy", \(I# n) -> I# (stableLazy n))]
