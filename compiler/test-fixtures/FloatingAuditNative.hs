{-# LANGUAGE MagicHash #-}
module Main (main) where
import GHC.Exts (Int(I#), Int#)
import qualified FloatingAudit as F

entries :: [(String, Int# -> Int#)]
entries =
  [ ("floatArithmetic", F.floatArithmetic), ("doubleArithmetic", F.doubleArithmetic)
  , ("floatRounding", F.floatRounding), ("doubleRounding", F.doubleRounding)
  , ("floatDoubleConversion", F.floatDoubleConversion), ("doubleFloatConversion", F.doubleFloatConversion)
  , ("floatComparisons", F.floatComparisons), ("doubleComparisons", F.doubleComparisons)
  , ("floatSignedZero", F.floatSignedZero), ("doubleSignedZero", F.doubleSignedZero)
  , ("floatingFields", F.floatingFields), ("floatingCaptures", F.floatingCaptures)
  , ("floatingLoop", F.floatingLoop), ("floatingJoinSwap", F.floatingJoinSwap)
  ]

inputs :: [Int]
inputs = [-16777219, -16777217, -16777216, -16777215, -17, -8, -7, -3, -1]
         ++ [0 .. 9] ++ [15, 16, 17, 31, 32, 33, 16777215, 16777216, 16777217, 16777219]

row :: String -> (Int# -> Int#) -> Int -> IO ()
row name f input@(I# n) = putStrLn (name ++ "\t" ++ show input ++ "\t" ++ show (I# (f n)))

main :: IO ()
main = do
  mapM_ (\(name, f) -> mapM_ (row name f) inputs) entries
  -- Double's own rounding boundary; all resulting conversions remain defined.
  mapM_ (row "doubleRounding" F.doubleRounding)
    [-9007199254740993, -9007199254740992, -9007199254740991,
      9007199254740991, 9007199254740992, 9007199254740993]
