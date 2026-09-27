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
-- Native GHC observer for the empty join input audit fixture.
module Main where
import GHC.Exts
import qualified EmptyJoinInputAudit as E
inputs :: [Int]
inputs = [minBound,-4097,-1,0,1,4097,3000000000,maxBound]
main :: IO ()
main = do
  let emit name f xs = mapM_ (\x@(I# n) -> putStrLn (name ++ "\t" ++ show x ++ "\t" ++ show (I# (f n)))) xs
  emit "branchCase" E.branchCase inputs
  emit "swapCase" E.swapCase inputs
  emit "mutualCase" E.mutualCase inputs
  emit "nestedCase" E.nestedCase inputs
  emit "lazyCase" E.lazyCase inputs
  emit "effectCase" E.effectCase inputs
  emit "throwCase" E.throwCase [0,1,4097,maxBound]
  emit "swapDepth" E.swapDepth [0,1,20001]
  emit "mutualDepth" E.mutualDepth [0,1,20001]
