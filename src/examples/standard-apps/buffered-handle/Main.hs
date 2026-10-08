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
-- Buffered output and file ownership through ordinary 'Handle' finalization.
module Main (main) where

import Control.Concurrent (threadDelay)
import Control.Exception (IOException, throwIO, try)
import Control.Monad (unless, void, when)
import System.Environment (getArgs)
import System.IO
  ( BufferMode (BlockBuffering), Handle, IOMode (ReadMode, WriteMode)
  , hClose, hGetContents', hIsClosed, hPutStr, hSetBuffering, openBinaryFile
  , withBinaryFile
  )
import System.IO.Error (isAlreadyInUseError)
import System.Mem (performGC)

-- | Compare explicit close with automatic cleanup in a caller-owned scratch
-- directory. Collection timing is deliberately unspecified.
main :: IO ()
main = do
  args <- getArgs
  directory <- case args of
    [path] -> pure path
    _ -> fail "usage: buffered-handle SCRATCH_DIRECTORY"
  let explicit = directory ++ "/explicit.txt"
      automatic = directory ++ "/automatic.txt"
  handle <- writeBuffered explicit
  hClose handle
  readClosed explicit >>= either throwIO (report "explicit")
  writeAutomatic automatic
  awaitClosed automatic >>= report "automatic"

contents :: String
contents = "buffered resource finalized\n"

readClosed :: FilePath -> IO (Either IOException String)
readClosed path = try (withBinaryFile path ReadMode hGetContents')

writeBuffered :: FilePath -> IO Handle
writeBuffered path = do
  handle <- openBinaryFile path WriteMode
  hSetBuffering handle (BlockBuffering (Just 4096))
  hPutStr handle contents
  performGC
  threadDelay 10000
  locked <- readClosed path
  case locked of
    Left err | isAlreadyInUseError err -> pure ()
    Left err -> throwIO err
    Right _ -> fail "writer lock released while the handle was live"
  closed <- hIsClosed handle
  when closed (fail "reachable writer was closed")
  pure handle

-- End the Handle's scope without closing it or flushing its buffered output.
{-# OPAQUE writeAutomatic #-}
writeAutomatic :: FilePath -> IO ()
writeAutomatic = void . writeBuffered

awaitClosed :: FilePath -> IO String
awaitClosed path = do
  result <- readClosed path
  case result of
    Right bytes -> pure bytes
    Left err | isAlreadyInUseError err -> do
      performGC
      threadDelay 10000
      awaitClosed path
    Left err -> throwIO err

report :: String -> String -> IO ()
report label bytes = do
  unless (bytes == contents) (fail "closing the handle did not flush its buffer")
  putStrLn (label ++ ": flushed and closed")
