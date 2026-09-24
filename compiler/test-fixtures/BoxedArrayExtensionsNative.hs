-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main (main) where

import GHC.Exts (Int(I#))
import qualified BoxedArrayExtensionsAudit as P

call :: String -> Int -> Int -> Int -> Int -> Int -> Int
call name (I# seed) (I# n) (I# from) (I# to) (I# count) = I# (case name of
  "boxedExtSizes" -> P.boxedExtSizes seed n from to count
  "boxedExtClone" -> P.boxedExtClone seed n from to count
  "boxedExtCopy" -> P.boxedExtCopy seed n from to count
  "boxedExtMove" -> P.boxedExtMove seed n from to count
  "boxedExtThaw" -> P.boxedExtThaw seed n from to count
  "boxedExtLazy" -> P.boxedExtLazy seed n from to count
  _ -> error "unknown boxed array entry")

main :: IO ()
main = mapM_ emit [(name,seed,n,from,to,count)
  | name <- ["boxedExtSizes","boxedExtClone","boxedExtCopy","boxedExtMove","boxedExtThaw","boxedExtLazy"]
  , seed <- [minBound,-7,0,23,maxBound], n <- [0,1,4]
  , count <- [0..n], from <- [0..n-count], to <- [0..n-count]]
  where
    emit (name,seed,n,from,to,count) = putStrLn (name ++ concatMap (('\t':) . show)
      [seed,n,from,to,count,call name seed n from to count])
