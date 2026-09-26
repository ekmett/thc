-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts (Int(I#), Int#)
import qualified SumJoinAudit as S

emit :: String -> (Int# -> Int#) -> [Int] -> IO ()
emit name f = mapM_ (\x@(I# n) -> putStrLn (name ++ "\t" ++ show x ++ "\t" ++ show (I# (f n))))
main :: IO ()
main = do
  emit "forwardCase" S.forwardCase [-4097,-1,0,1,4097,3000000000]
  emit "recursiveCase" S.recursiveCase [-4097,-1,0,1,4097,20000]
  emit "nestedCase" S.nestedCase [-4097,-1,0,1,4097,3000000000]
