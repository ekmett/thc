-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

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
