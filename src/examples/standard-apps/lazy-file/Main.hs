-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell2010
--
-- Preview lazy file input while its unread tail owns the original Handle.
module Main (main) where

import Control.Concurrent (threadDelay)
import Control.Exception (IOException, throwIO, try)
import Control.Monad (unless)
import System.Environment (getArgs)
import System.IO (IOMode (AppendMode), hPutStr, withFile)
import System.IO.Error (isAlreadyInUseError)
import System.Mem (performGC)

tryAppend :: FilePath -> String -> IO (Either IOException ())
tryAppend path bytes = try (withFile path AppendMode (`hPutStr` bytes))

-- The returned String, rather than an explicit Handle, owns the delayed reads.
{-# OPAQUE openInput #-}
openInput :: FilePath -> IO String
openInput = readFile

{-# OPAQUE preview #-}
preview :: FilePath -> IO ()
preview path = do
  input <- openInput path
  let (first, remainder) = break (== '\n') input
  unless (first == "first record") (fail "first preview changed")
  putStrLn ("Preview: " ++ first)
  performGC
  threadDelay 10000
  attempt <- tryAppend path ""
  case attempt of
    Left err | isAlreadyInUseError err -> pure ()
    Left err -> throwIO err
    Right _ -> fail "the live lazy tail lost its reader lock"
  let second = takeWhile (/= '\n') (drop 1 remainder)
  unless (second == "second record") (fail "delayed preview changed")
  putStrLn ("After collection: " ++ second)
  -- Return without demanding the remaining records or closing the input.

awaitAppend :: FilePath -> IO ()
awaitAppend path = do
  attempt <- tryAppend path "appended after preview\n"
  case attempt of
    Right () -> pure ()
    Left err | isAlreadyInUseError err -> do
      performGC
      threadDelay 10000
      awaitAppend path
    Left err -> throwIO err

-- | Use a caller-owned scratch directory. Collection timing is unspecified.
main :: IO ()
main = do
  args <- getArgs
  directory <- case args of
    [path] -> pure path
    _ -> fail "usage: lazy-file SCRATCH_DIRECTORY"
  let path = directory ++ "/records.txt"
  writeFile path "first record\nsecond record\nunread record\n"
  preview path
  awaitAppend path
  putStrLn "Discarded tail: writer reopened."
