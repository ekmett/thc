-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts (Int(I#), Int#)
import qualified TupleJoinAudit as T

inputs :: [Int]
inputs = [minBound,-4097,-1,0,1,4097,3000000000,maxBound]
emit :: String -> (Int# -> Int#) -> [Int] -> IO ()
emit name f = mapM_ (\x@(I# n) -> putStrLn (name ++ "\t" ++ show x ++ "\t" ++ show (I# (f n))))
main :: IO ()
main = do
  emit "forwardCase" T.forwardCase inputs
  emit "recursiveCase" T.recursiveCase inputs
  emit "mutualCase" T.mutualCase inputs
  emit "nestedForwardCase" T.nestedForwardCase inputs
  emit "emptyCase" T.emptyCase inputs
  emit "nestedCase" T.nestedCase inputs
  emit "capturePairCase" T.capturePairCase inputs
  emit "recursiveDepth" T.recursiveDepth [0,1,20000]
  emit "mutualDepth" T.mutualDepth [0,1,20001]
