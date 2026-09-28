-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Native GHC observer for the managed address read fixture.
module Main where
import GHC.Exts (Int(I#))
import qualified ManagedAddressReadAudit as A

run :: [String] -> IO ()
run tokens@[name, raw, base, offset] = do
  let result = case (read raw, read base, read offset) of
        (I# x, I# b, I# o) -> case name of
          "word32Read" -> I# (A.word32Read x b o)
          "wordRead" -> I# (A.wordRead x b o)
          "int32Read" -> I# (A.int32Read x b o)
          "intRead" -> I# (A.intRead x b o)
          _ -> error "unknown address-read fixture"
  putStrLn (concatMap (++ "\t") tokens ++ show result)
run _ = error "invalid address-read request"

main :: IO ()
main = getContents >>= mapM_ (run . words) . lines
