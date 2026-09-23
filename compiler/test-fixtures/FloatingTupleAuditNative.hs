{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Main where
import GHC.Exts
import GHC.Float (castFloatToWord32, castDoubleToWord64)
import qualified FloatingTupleAudit as F
import System.Environment (getArgs)

inputs :: [Int]
inputs = [-128, -31, -7, -1, 0, 1, 7, 31, 128]
row :: String -> (Int# -> Int#) -> Int -> IO ()
row name f n@(I# x) = putStrLn (name ++ "\t" ++ show n ++ "\t" ++ show (I# (f x)))
bits :: Int -> IO ()
bits n@(I# x) = case F.ieeePair x of
  (# f, d #) -> putStrLn (show n ++ "\t" ++ show (castFloatToWord32 (F# f))
                       ++ "\t" ++ show (castDoubleToWord64 (D# d)))
main :: IO ()
main = do
  args <- getArgs
  if args == ["bits"] then mapM_ bits [0..7] else do
    mapM_ (\(name, f) -> mapM_ (row name f) inputs)
      [("complexFloatCase", F.complexFloatCase), ("complexDoubleCase", F.complexDoubleCase),
       ("mixedCase", F.mixedCase), ("joinedCase", F.joinedCase)]
    mapM_ (row "ieeeCase" F.ieeeCase) [0..7]
