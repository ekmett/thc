-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main (main) where
import GHC.Exts
import GHC.Float (castWord32ToFloat, castWord64ToDouble)
import qualified FloatDecodeAudit as P
import qualified THC.FloatDecode as Example

emit :: String -> (Int# -> Int# -> Int#) -> Int -> IO ()
emit name function input@(I# raw) = do
  let m = I# (function raw 0#)
      e = I# (function raw 1#)
      public = if take 5 name == "float"
        then decodeFloat (castWord32ToFloat (fromIntegral input))
        else decodeFloat (castWord64ToDouble (fromIntegral input))
  if name `elem` ["floatExponent","doubleExponent","floatExampleExponent","doubleExampleExponent"] || public == (toInteger m,e)
    then putStrLn (name ++ "\t" ++ show input ++ "\t" ++ show m ++ "\t" ++ show e)
    else error "Primitive decode disagrees with public RealFloat.decodeFloat"

dispatch :: [String] -> IO ()
dispatch [name,input] = case name of
  "floatDirect" -> emit name P.floatDirect (read input)
  "doubleDirect" -> emit name P.doubleDirect (read input)
  "floatCall" -> emit name P.floatCall (read input)
  "doubleCall" -> emit name P.doubleCall (read input)
  "floatExponent" -> emit name P.floatExponent (read input)
  "doubleExponent" -> emit name P.doubleExponent (read input)
  "floatExampleExponent" -> emit name (\raw _ -> Example.floatExampleExponent raw) (read input)
  "doubleExampleExponent" -> emit name (\raw _ -> Example.doubleExampleExponent raw) (read input)
  _ -> error "Unknown floating decode entry"
dispatch _ = error "Malformed floating decode input"

main :: IO ()
main = getContents >>= mapM_ (dispatch . words) . lines
