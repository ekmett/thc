-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main (main) where
import GHC.Exts (Int(..))
import StableForeign
main :: IO ()
main = mapM_ (\(name, invoke) -> mapM_ (\value -> putStrLn
  (name ++ "\t" ++ show value ++ "\t" ++ show (invoke value))) [-100,-1,0,1,42,100000])
  [("stableRoundtrip", \(I# n) -> I# (stableRoundtrip n)),
   ("stableLazy", \(I# n) -> I# (stableLazy n))]
