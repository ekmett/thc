-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import CompactRegionsAudit
import GHC.Exts

main :: IO ()
main = mapM_ observe [(name, function, input) |
  (name, function) <- [("ordinary", ordinary), ("sharing", sharing), ("cycleCase", cycleCase),
    ("rejectedObjects", rejectedObjects), ("frozenArray", frozenArray)],
  input <- [-31, 0, 17, 4097]]
  where
    observe (name, function, input@(I# n)) =
      putStrLn (name ++ "\t" ++ show input ++ "\t" ++ show (I# (function n)))
