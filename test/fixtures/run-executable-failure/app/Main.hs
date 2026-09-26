-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Main (main) where

import System.IO (IOMode (WriteMode), hPutStr, withFile)

main :: IO ()
main = withFile "failure.txt" WriteMode $ \handle -> do
  hPutStr handle "file before failure"
  putStr "stdout before failure"
  ioError (userError "THC expected uncaught failure")
