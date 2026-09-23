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
import qualified THC.SequenceWorkload as Q

entries :: [(String, Int# -> Int#)]
entries =
  [ ("setAggregate", S.setAggregate)
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
  , ("sequenceBuild", Q.sequenceBuild)
  , ("sequenceEnds", Q.sequenceEnds)
  , ("sequenceAppend", Q.sequenceAppend)
  , ("sequenceSplit", Q.sequenceSplit)
  , ("sequenceIndexUpdate", Q.sequenceIndexUpdate)
  , ("sequenceAggregate", Q.sequenceAggregate)
  , ("sequenceLazyPayloads", Q.sequenceLazyPayloads)
  , ("sequenceBuildViews", Q.sequenceBuildViews)
  , ("sequenceDequeViews", Q.sequenceDequeViews)
  , ("sequenceAppendViews", Q.sequenceAppendViews)
  , ("sequenceLazyLength", Q.sequenceLazyLength)
  ]

main :: IO ()
main = do
  args <- getArgs
  case args of
    [name, input] -> case (lookup name entries, readMaybe input) of
      (Just f, Just (I# n)) ->
        putStrLn (name ++ "\t" ++ input ++ "\t" ++ show (I# (f n)))
      _ -> die "unknown entry or invalid machine Int"
    _ -> die "usage: library-oracle ENTRY INPUT"
