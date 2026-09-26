-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main where
import DeepEvaluation (boxedProbe)

main :: IO ()
main = mapM_ (\n -> putStrLn (show n ++ "\t" ++ show (boxedProbe n)))
  [0, 100, 1000, 5000, 20000]
