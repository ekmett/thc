-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell base; writable host filesystem
--
-- Executable for the @run-executable-failure@ integration fixture.
module Main (main) where

import System.IO (IOMode (WriteMode), hPutStr, withFile)

main :: IO ()
main = withFile "failure.txt" WriteMode $ \handle -> do
  hPutStr handle "file before failure"
  putStr "stdout before failure"
  ioError (userError "THC expected uncaught failure")
