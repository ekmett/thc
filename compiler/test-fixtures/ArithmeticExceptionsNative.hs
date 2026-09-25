-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where

import Control.Exception (ArithException, evaluate, try)
import GHC.Exts (Int(I#), Int#)
import qualified ArithmeticExceptionsAudit as P

emit :: String -> (Int# -> Int#) -> Int -> IO ()
emit name action input@(I# raw) = do
  observed <- try (evaluate (I# (action raw))) :: IO (Either ArithException Int)
  putStrLn (name ++ "\t" ++ show input ++ "\t" ++ either show show observed)

dispatch :: [String] -> IO ()
dispatch [name, value] = case name of
  "divideOrAdd" -> emit name P.divideOrAdd input
  "underflowOrAdd" -> emit name P.underflowOrAdd input
  "overflowOrAdd" -> emit name P.overflowOrAdd input
  "catchDivide" -> emit name P.catchDivide input
  "catchUnderflow" -> emit name P.catchUnderflow input
  "catchOverflow" -> emit name P.catchOverflow input
  _ -> error "unknown arithmetic exception entry"
  where input = read value
dispatch _ = error "invalid arithmetic exception row"

main :: IO ()
main = getContents >>= mapM_ (dispatch . words) . lines
