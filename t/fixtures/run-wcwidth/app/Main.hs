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
-- Executable for the @run-wcwidth@ integration fixture.
module Main (main) where
import GHC.Exts
import Width

main :: IO ()
main = mapM_ observe [0,9,10,32,65,0x301,0x4e00,0x1f642,0xd800,0x110000,-1,0x7fffffff]
  where observe (I# value) = putStrLn $ show (I# value) ++ "\t" ++ show (I# (rawWidth value)) ++
          "\t" ++ show (I# (displayWidth value))
