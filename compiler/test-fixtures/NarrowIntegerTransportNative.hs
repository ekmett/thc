-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC 9.14.1
module Main (main) where
import NarrowIntegerTransport (entries)

main :: IO ()
main = mapM_ (\(name, f) -> mapM_ (\x -> putStrLn
  (name ++ "\t" ++ show x ++ "\t" ++ show (f x))) inputs) entries
  where
    inputs = [minBound,-4294967297,-2147483649,-65537,-32769,-129,-1,0,1,127,
      128,255,256,32767,65535,2147483647,2147483648,4294967295,4294967296,maxBound]
