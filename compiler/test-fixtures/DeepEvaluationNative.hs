-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell with the imported library dependencies
--
-- Native GHC observer for the deep evaluation fixture.
module Main where
import DeepEvaluation (boxedProbe)

main :: IO ()
main = mapM_ (\n -> putStrLn (show n ++ "\t" ++ show (boxedProbe n)))
  [0, 100, 1000, 5000, 20000]
