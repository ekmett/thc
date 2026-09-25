-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main (main) where
import GHC.Exts (Int(..))
import Scalar
import ScalarAgain
main :: IO ()
main = mapM_ (\(name, invoke, values) -> mapM_ (\value -> putStrLn
  (name ++ "\t" ++ show value ++ "\t" ++ show (invoke value))) values) cases
  where
    cases =
      [("scalarInt32", \(I# x) -> I# (scalarInt32 x), [0, -1, 1, -2147483648, 2147483647]),
       ("scalarInt64", \(I# x) -> I# (scalarInt64 x), [0, -1, 1, minBound, maxBound]),
       ("scalarFloat", \(I# x) -> I# (scalarFloat x), [0, 2147483648, 1065353216, 3214934016, 2139095040, 4286578688, 2143289344]),
       ("scalarDouble", \(I# x) -> I# (scalarDouble x), [0, minBound, 4607182418800017408, -4616189618054758400, 9218868437227405312, -4503599627370496, 9221120237041090560]),
       ("scalarMixed", \(I# x) -> I# (scalarMixed x), [0, -17, 42]),
       ("repeatInt32", \(I# x) -> I# (repeatInt32 x), [0, -1, 17])]
