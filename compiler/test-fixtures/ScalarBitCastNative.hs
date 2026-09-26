-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main (main) where
import GHC.Exts
import qualified ScalarBitCastAudit as P

emit :: String -> (Int# -> Int#) -> Int -> IO ()
emit name function x@(I# raw) = putStrLn (name ++ "\t" ++ show x ++ "\t" ++ show (I# (function raw)))

dispatch :: [String] -> IO ()
dispatch [name,x] = case name of
  "floatRoundtrip" -> emit name P.floatRoundtrip (read x)
  "floatField" -> emit name P.floatField (read x)
  "floatCaptured" -> emit name P.floatCaptured (read x)
  "floatDecode" -> emit name P.floatDecode (read x)
  "floatEncode" -> emit name P.floatEncode (read x)
  "doubleRoundtrip" -> emit name P.doubleRoundtrip (read x)
  "doubleField" -> emit name P.doubleField (read x)
  "doubleCaptured" -> emit name P.doubleCaptured (read x)
  "doubleDecode" -> emit name P.doubleDecode (read x)
  "doubleEncode" -> emit name P.doubleEncode (read x)
  _ -> error "Unknown scalar bitcast entry"
dispatch _ = error "Malformed scalar bitcast input"

main :: IO ()
main = getContents >>= mapM_ (dispatch . words) . lines
