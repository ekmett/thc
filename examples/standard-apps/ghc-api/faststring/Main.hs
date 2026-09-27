-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Exercise the installed GHC FastString API from a command-line input.
module Main (main) where

import GHC.Data.FastString (mkFastString, unpackFS)
import System.Environment (getArgs)

main :: IO ()
main = do
  input <- unwords <$> getArgs
  let value = mkFastString input
      same = mkFastString (reverse (reverse input))
  putStrLn (unpackFS value)
  print (value == same)
