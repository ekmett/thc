-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts
import Data.Bits (finiteBitSize)
import SimdInt8X16

emit2 :: String -> (Int# -> Int# -> Int#) -> Int -> Int -> IO ()
emit2 name function a@(I# x) b@(I# y) =
  putStrLn (name ++ "\t" ++ show a ++ "\t" ++ show b ++ "\t" ++ show (I# (function x y)))

emitLane :: Int -> Int -> Int -> Int -> IO ()
emitLane operation@(I# op) lane@(I# k) a@(I# x) b@(I# y) =
  putStrLn ("laneCase\t" ++ show operation ++ "\t" ++ show lane ++ "\t"
    ++ show a ++ "\t" ++ show b ++ "\t" ++ show (I# (laneCase op k x y)))

dispatch :: [String] -> IO ()
dispatch [name, a, b] = case name of
  "plusCase" -> emit2 name plusCase (read a) (read b)
  "minusCase" -> emit2 name minusCase (read a) (read b)
  "timesCase" -> emit2 name timesCase (read a) (read b)
  "negateCase" -> emit2 name negateCase (read a) (read b)
  "packCase" -> emit2 name packCase (read a) (read b)
  "broadcastCase" -> emit2 name broadcastCase (read a) (read b)
  "scalarHelperCase" -> emit2 name scalarHelperCase (read a) (read b)
  "tupleHelperCase" -> emit2 name tupleHelperCase (read a) (read b)
  _ -> error "unknown scalar entry"
dispatch ["laneCase", operation, lane, a, b] = emitLane (read operation) (read lane) (read a) (read b)
dispatch _ = error "invalid input"

main :: IO ()
main = if finiteBitSize (0 :: Int) /= 64 then error "Requires 64-bit Int"
       else getContents >>= mapM_ (dispatch . words) . lines
