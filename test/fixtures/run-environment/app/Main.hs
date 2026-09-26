-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where

import Control.Exception (IOException, try)
import Control.Monad (unless)
import System.Environment (getEnv, getEnvironment, lookupEnv, setEnv, unsetEnv)
import System.IO (hFlush, stdout)

main :: IO ()
main = do
  let name = "THC_ENVIRONMENT_FIXTURE_01A0CDEB"
      check label condition = unless condition (fail label)
  unsetEnv name
  absent <- lookupEnv name
  check "missing variable" (absent == Nothing)
  setEnv name "first"
  first <- getEnv name
  check "first value" (first == "first")
  setEnv name "lambda-\x03bb\x1d11e=value"
  value <- getEnv name
  check "replacement and UTF-8" (value == "lambda-\x03bb\x1d11e=value")
  environment <- getEnvironment
  check "environment enumeration" (filter ((== name) . fst) environment == [(name, value)])
  setEnv name ""
  empty <- lookupEnv name
  check "empty set removes" (empty == Nothing)
  setEnv (name ++ "\NULignored") "before\NULafter"
  truncated <- getEnv name
  check "original setEnv NUL handling" (truncated == "before")
  invalid <- try (setEnv "invalid=name" "value") :: IO (Either IOException ())
  check "invalid name" (either (const True) (const False) invalid)
  unsetEnv name
  unsetEnv name
  missing <- try (getEnv name) :: IO (Either IOException String)
  check "missing getEnv IOException" (either (const True) (const False) missing)
  putStrLn "environment ok"
  hFlush stdout
