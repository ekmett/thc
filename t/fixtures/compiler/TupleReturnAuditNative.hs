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
-- Native GHC observer for the tuple return audit fixture.
module Main where
import TupleReturnAudit
import GHC.Exts (Int(I#), Int#)

entries :: [(String, Int# -> Int#)]
entries = [("emptyCase", emptyCase), ("singleIntCase", singleIntCase),
           ("singleBoxCase", singleBoxCase), ("singleClosureCase", singleClosureCase),
           ("nestedCase", nestedCase), ("unliftedBoxedLeafCase", unliftedBoxedLeafCase),
           ("selfTailCase", selfTailCase), ("mutualTailCase", mutualTailCase),
           ("nonTailCase", nonTailCase), ("papCase", papCase),
           ("overapplicationCase", overapplicationCase)]

inputs :: [Int]
inputs = [minBound, -4097, -1, 0, 1, 4097, 3000000000, maxBound]

row :: String -> (Int# -> Int#) -> Int -> IO ()
row name f (I# x) = putStrLn (name ++ "\t" ++ show (I# x) ++ "\t" ++ show (I# (f x)))

main :: IO ()
main = do
  mapM_ (\(name, f) -> mapM_ (row name f) inputs) entries
  mapM_ (row "selfTailDepth" selfTailDepth) [0, 1, 20000]
  mapM_ (row "mutualTailDepth" mutualTailDepth) [0, 1, 20001]
