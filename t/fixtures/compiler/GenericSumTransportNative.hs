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
-- Native active-payload oracle for generic sum transport.
module Main where
import GHC.Exts
import GenericSumTransport

main :: IO ()
main = sequence_ [putStrLn (name ++ "\t" ++ show selector ++ "\t" ++ show bits ++ "\t" ++ show (I# (function s b)))
  | (name,function) <- [("addressCase",addressCase),("vectorCase",vectorCase),("vectorResultCase",vectorResultCase),("nestedCase",nestedCase),
      ("aroundCase",aroundCase),("heapCase",heapCase),("captureCase",captureCase),("residualCase",residualCase),
      ("papCase",papCase),("pairedCase",pairedCase)],
    selector@(I# s) <- [0..15], bits@(I# b) <- [minBound,-4294967297,-129,-1,0,1,127,4294967296,maxBound]]
