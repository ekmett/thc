-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main (main) where
import GHC.Exts (Int(I#), Int#, Word(W#), Word#)
import qualified FloatingAudit as F
import System.Environment (getArgs)
import System.Exit (die)
import Text.Read (readMaybe)

entries :: [(String, Int# -> Int#)]
entries =
  [ ("floatArithmetic", F.floatArithmetic), ("doubleArithmetic", F.doubleArithmetic)
  , ("floatRounding", F.floatRounding), ("doubleRounding", F.doubleRounding)
  , ("floatDoubleConversion", F.floatDoubleConversion), ("doubleFloatConversion", F.doubleFloatConversion)
  , ("floatComparisons", F.floatComparisons), ("doubleComparisons", F.doubleComparisons)
  , ("floatSignedZero", F.floatSignedZero), ("doubleSignedZero", F.doubleSignedZero)
  , ("floatingFields", F.floatingFields), ("floatingCaptures", F.floatingCaptures)
  , ("floatingLoop", F.floatingLoop), ("floatingJoinSwap", F.floatingJoinSwap)
  , ("floatingTupleFrontier", F.floatingTupleFrontier)
  ]

inputs :: [Int]
inputs = [-16777219, -16777217, -16777216, -16777215, -17, -8, -7, -3, -1]
         ++ [0 .. 9] ++ [15, 16, 17, 31, 32, 33, 16777215, 16777216, 16777217, 16777219]

row :: String -> (Int# -> Int#) -> Int -> IO ()
row name f input@(I# n) = putStrLn (name ++ "\t" ++ show input ++ "\t" ++ show (I# (f n)))

ordinary :: IO ()
ordinary = do
  mapM_ (\(name, f) -> mapM_ (row name f) inputs) entries
  -- Double's own rounding boundary; all resulting conversions remain defined.
  mapM_ (row "doubleRounding" F.doubleRounding)
    [-9007199254740993, -9007199254740992, -9007199254740991,
      9007199254740991, 9007199254740992, 9007199254740993]

fusedEntries :: Int -> [(String, Word# -> Word# -> Word# -> Word#)]
fusedEntries 32 = [("fusedFloatAdd",F.fusedFloatAdd),("fusedFloatSub",F.fusedFloatSub),
  ("fusedFloatNegAdd",F.fusedFloatNegAdd),("fusedFloatNegSub",F.fusedFloatNegSub)]
fusedEntries 64 = [("fusedDoubleAdd",F.fusedDoubleAdd),("fusedDoubleSub",F.fusedDoubleSub),
  ("fusedDoubleNegAdd",F.fusedDoubleNegAdd),("fusedDoubleNegSub",F.fusedDoubleNegSub)]
fusedEntries _ = []

fusedRow :: String -> IO ()
fusedRow line = case traverse readMaybe (words line) :: Maybe [Integer] of
  Just [width,x,y,z] | width `elem` [32,64], all (\n -> n >= 0 && n < 2^width) [x,y,z] ->
    case (fromInteger x, fromInteger y, fromInteger z) of
      (W# a,W# b,W# c) -> mapM_ (\(name,f) -> putStrLn
        (name ++ "\t" ++ show x ++ "\t" ++ show y ++ "\t" ++ show z ++ "\t" ++ show (W# (f a b c))))
        (fusedEntries (fromInteger width))
  _ -> die "Malformed fused-floating input"

main :: IO ()
main = do
  args <- getArgs
  case args of
    [] -> ordinary
    ["--fused"] -> getContents >>= mapM_ fusedRow . lines
    _ -> die "Usage: floating-oracle [--fused]"
