{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts
import Data.Bits (finiteBitSize)
import SimdInt32X4Multiply

emit2 :: String -> (Int# -> Int# -> Int#) -> Int -> Int -> IO ()
emit2 name function a@(I# x) b@(I# y) =
  putStrLn (name ++ "\t" ++ show a ++ "\t" ++ show b ++ "\t" ++ show (I# (function x y)))

emitLane :: Int -> Int -> Int -> IO ()
emitLane lane@(I# k) a@(I# x) b@(I# y) =
  putStrLn ("laneCase\t" ++ show lane ++ "\t" ++ show a ++ "\t" ++ show b ++ "\t" ++ show (I# (laneCase k x y)))

dispatch :: [String] -> IO ()
dispatch [name, a, b] = case name of
  "timesCase" -> emit2 name timesCase (read a) (read b)
  "scalarHelperCase" -> emit2 name scalarHelperCase (read a) (read b)
  "tupleHelperCase" -> emit2 name tupleHelperCase (read a) (read b)
  _ -> error "unknown scalar entry"
dispatch ["laneCase", lane, a, b] = emitLane (read lane) (read a) (read b)
dispatch _ = error "invalid input"

main :: IO ()
main = if finiteBitSize (0 :: Int) /= 64 then error "Requires 64-bit Int"
       else getContents >>= mapM_ (dispatch . words) . lines
