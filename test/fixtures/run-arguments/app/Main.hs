-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell base; host program-name and argument APIs
--
-- Executable for the @run-arguments@ integration fixture.
module Main (main) where

import Control.Exception (IOException, catch, throwIO)
import System.Environment (getArgs, getProgName, withArgs, withProgName)
import System.IO (hFlush, stdout)

observe :: String -> IO ()
observe label = do
  name <- getProgName
  args <- getArgs
  print (label, name, args)

main :: IO ()
main = do
  observe "initial"
  withProgName "renamed" $ withArgs ["nested", "", "lambda-\x03bb"] $ observe "nested"
  (withArgs ["temporary"] (throwIO (userError "restore arguments")))
    `catch` recover
  observe "restored"
  hFlush stdout
  where
    recover :: IOException -> IO ()
    recover _ = pure ()
