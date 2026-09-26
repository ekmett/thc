-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main (main) where
import GHC.Exts (Int(I#))
import qualified BoxedCasAudit as P
import qualified THC.BoxedCasCounter as C

call :: String -> Int -> Int
call name (I# n) = I# (case name of
  "arrayCas" -> P.arrayCas n
  "arrayCasUnlifted" -> P.arrayCasUnlifted n
  "smallCas" -> P.smallCas n
  "smallCasUnlifted" -> P.smallCasUnlifted n
  "varCas" -> P.varCas n
  "varCasUnlifted" -> P.varCasUnlifted n
  "modifyValue" -> P.modifyValue n
  "modifyLazy" -> P.modifyLazy n
  "modifyBottom" -> P.modifyBottom n
  "boxedCasCounter" -> C.boxedCasCounter n
  _ -> error "unknown boxed CAS entry")

main :: IO ()
main = mapM_ emit [(name,n) | name <- ["arrayCas","arrayCasUnlifted","smallCas","smallCasUnlifted","varCas","varCasUnlifted","modifyValue","modifyLazy","modifyBottom","boxedCasCounter"], n <- [minBound,-1000000,-17,-1,0,1,17,1000000,maxBound]]
  where emit (name,n) = putStrLn (name ++ "\t" ++ show n ++ "\t" ++ show (call name n))
