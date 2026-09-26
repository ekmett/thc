-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where

import Control.Exception (IOException, try)
import Control.Monad (unless)
import System.Directory (removeFile)
import System.Environment (getArgs)
import System.IO (hFlush, stdout)
import System.IO.Error (isDoesNotExistError)

main :: IO ()
main = do
  arguments <- getArgs
  path <- case arguments of
    [value] -> pure value
    _ -> fail "expected one fixture-owned file path"
  writeFile path "original directory.removeFile fixture"
  removeFile path
  missing <- try (removeFile path) :: IO (Either IOException ())
  unless (either isDoesNotExistError (const False) missing) (fail "expected ENOENT")
  putStrLn "unlink ok"
  hFlush stdout
