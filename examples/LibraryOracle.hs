-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main (main) where

import GHC.Exts (Int(I#), Int#)
import System.Environment (getArgs)
import System.Exit (die)
import Text.Read (readMaybe)
import qualified THC.IntMapPrimops as P
import qualified THC.IntMapWorkload as I
import qualified THC.IntSetPrimops as SP
import qualified THC.IntSetWorkload as IS
import qualified THC.SetWorkload as S
import qualified THC.SequenceWorkload as SQ
import qualified THC.GraphWorkload as G

entries :: [(String, Int# -> Int#)]
entries =
  [ ("graphChecksum", G.graphChecksum)
  , ("graphReachable", G.graphReachable)
  , ("graphDistanceTotal", G.graphDistanceTotal)
  , ("graphDistanceAt", G.graphDistanceAt)
  , ("graphControl", G.graphControl)
  , ("setAggregate", S.setAggregate)
  , ("intMapAggregate", I.intMapAggregate)
  , ("countLeadingZeros", P.countLeadingZeros)
  , ("unsignedLessThanZero", P.unsignedLessThanZero)
  , ("unsignedLessThanMaxSigned", P.unsignedLessThanMaxSigned)
  , ("unsignedLessThanSignBit", P.unsignedLessThanSignBit)
  , ("unsignedLessThanAllOnes", P.unsignedLessThanAllOnes)
  , ("intSetAggregate", IS.intSetAggregate)
  , ("populationCount", SP.populationCount)
  , ("countTrailingZeros", SP.countTrailingZeros)
  , ("unsignedLessEqualZero", SP.unsignedLessEqualZero)
  , ("unsignedLessEqualMaxSigned", SP.unsignedLessEqualMaxSigned)
  , ("unsignedLessEqualSignBit", SP.unsignedLessEqualSignBit)
  , ("unsignedLessEqualAllOnes", SP.unsignedLessEqualAllOnes)
  , ("sequenceBuild", SQ.sequenceBuild)
  , ("sequenceEnds", SQ.sequenceEnds)
  , ("sequenceAppend", SQ.sequenceAppend)
  , ("sequenceSplit", SQ.sequenceSplit)
  , ("sequenceIndexUpdate", SQ.sequenceIndexUpdate)
  , ("sequenceAggregate", SQ.sequenceAggregate)
  , ("sequenceLazyPayloads", SQ.sequenceLazyPayloads)
  ]

main :: IO ()
main = do
  args <- getArgs
  case args of
    ["--batch"] -> getContents >>= mapM_ (emit . words) . lines
    _ -> emit args
  where
    emit [name, input] = case (lookup name entries, readMaybe input) of
      (Just f, Just (I# n)) ->
        putStrLn (name ++ "\t" ++ input ++ "\t" ++ show (I# (f n)))
      _ -> die "unknown entry or invalid machine Int"
    emit _ = die "usage: library-oracle ENTRY INPUT | --batch (ENTRY INPUT lines on stdin)"
