-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import ScalarMemoryUtilities

emit :: String -> [Int] -> Int -> IO ()
emit name arguments result = putStrLn (unwords (name : map show (arguments ++ [result])))

main :: IO ()
main = do
  sequence_ [emit "memoryCase" [from,to,count,value,selected] (I# (memoryCase a b c d e))
    | (from@(I# a),to@(I# b),count@(I# c)) <- [(0,4,16),(4,0,16),(8,16,8),(0,0,32),(32,32,0)],
      value@(I# d) <- [-257,-1,0,1,256,511], selected@(I# e) <- [0,8,15,23,31]]
  sequence_ [emit "pinCase" [mode] (I# (pinCase x)) | mode@(I# x) <- [0..2]]
  sequence_ [emit "thawCase" [value] (I# (thawCase x)) | value@(I# x) <- [-257,-1,0,1,127,255,256,511]]
  sequence_ [emit "shrinkCase" [size] (I# (shrinkCase x)) | size@(I# x) <- [0..8]]
  sequence_ [emit "differenceCase" [left,right] (I# (differenceCase x y))
    | left@(I# x) <- [0,1,31,64], right@(I# y) <- [0,1,31,64]]
  sequence_ [emit "remainderCase" [offset,divisor] (I# (remainderCase x y))
    | offset@(I# x) <- [0,1,7,31,64], divisor@(I# y) <- [1,2,8,16,64]]
  sequence_ [emit "numericDifference" [left,right] (I# (numericDifference x y))
    | left@(I# x) <- [minBound,-1,0,1,maxBound], right@(I# y) <- [minBound,-1,0,1,maxBound]]
  sequence_ [emit "numericRemainder" [bits,divisor] (I# (numericRemainder x y))
    | bits@(I# x) <- [minBound,-1,0,1,maxBound], divisor@(I# y) <- [minBound,-7,-1,1,2,7,maxBound]]
