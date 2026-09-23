{-# LANGUAGE MagicHash #-}
module Main (main) where

import GHC.Exts (Int(I#), Int#)
import System.Environment (getArgs)
import System.Exit (die)
import Text.Read (readMaybe)
import qualified THC.IntMapPrimops as P
import qualified THC.IntMapWorkload as I
import qualified THC.SetWorkload as S

entries :: [(String, Int# -> Int#)]
entries =
  [ ("setAggregate", S.setAggregate)
  , ("intMapAggregate", I.intMapAggregate)
  , ("countLeadingZeros", P.countLeadingZeros)
  , ("unsignedLessThanZero", P.unsignedLessThanZero)
  , ("unsignedLessThanMaxSigned", P.unsignedLessThanMaxSigned)
  , ("unsignedLessThanSignBit", P.unsignedLessThanSignBit)
  , ("unsignedLessThanAllOnes", P.unsignedLessThanAllOnes)
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
