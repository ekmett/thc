-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts
import qualified ArithmeticExceptionsAudit as A

emit :: String -> (Int# -> Int#) -> IO ()
emit name action = mapM_ (\value@(I# x) ->
  putStrLn (name ++ "\t" ++ show value ++ "\t" ++ show (I# (action x))))
  [minBound, -17, -1, 0, 1, 17, maxBound]

main :: IO ()
main = do
  emit "scalarDivZero" A.scalarDivZero
  emit "scalarOverflow" A.scalarOverflow
  emit "scalarUnderflow" A.scalarUnderflow
  emit "tupleDivZero" A.tupleDivZero
  emit "tupleOverflow" A.tupleOverflow
  emit "tupleUnderflow" A.tupleUnderflow
