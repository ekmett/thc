-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : POSIX; depends on the unix package
--
-- Native GHC observer for the original stdio truncate audit fixture.
module Main where

import Control.Exception (evaluate)
import GHC.Exts (Int(I#))
import GHC.Internal.Int (Int64(I64#))
import System.Environment (getArgs)
import System.IO (SeekMode(AbsoluteSeek, RelativeSeek))
import System.Posix.Files (fileSize, getFileStatus)
import System.Posix.IO (OpenMode(ReadOnly, ReadWrite), closeFd, createPipe, defaultFileFlags, fdSeek, openFd)
import System.Posix.Types (Fd(..))
import qualified OriginalStdioTruncateAudit as Original

main :: IO ()
main = getArgs >>= batch
  where
    batch [] = pure ()
    batch (entry : scenario : privatePath : resultPath : rest) =
      observe entry scenario privatePath resultPath >> batch rest
    batch _ = error "Expected groups of ENTRY SCENARIO PRIVATE_PATH RESULT_PATH"

observe :: String -> String -> FilePath -> FilePath -> IO ()
observe entry scenario privatePath resultPath = do
  (fd, release, privateFile) <- case scenario of
    "invalid" -> pure (Fd (-1), pure (), False)
    "pipe" -> do
      (reader, writer) <- createPipe
      pure (reader, closeFd reader >> closeFd writer, False)
    _ -> do
      writeFile privatePath "abcdef"
      opened <- openFd privatePath (if scenario == "readonly" then ReadOnly else ReadWrite) defaultFileFlags
      pure (opened, closeFd opened, True)
  if privateFile then do
    position <- fdSeek fd AbsoluteSeek 4
    if position == 4 then pure () else error "Failed to set native truncate cursor"
  else pure ()
  let number = case fd of Fd value -> fromIntegral value :: Int
      length = case scenario of
        "shrink" -> 3
        "same" -> 6
        "extend" -> 9
        "negative" -> -1
        "readonly" -> 3
        _ -> 0
      truncateFile :: Int -> Int64 -> Int
      truncateFile (I# rawFd) (I64# rawLength) = I# (Original.originalTruncate rawFd rawLength)
      errno :: Int -> Int64 -> Int
      errno (I# rawFd) (I64# rawLength) = I# (Original.originalTruncateErrno rawFd rawLength)
  result <- evaluate $ case entry of
    "originalTruncate" -> truncateFile number length
    "originalTruncateErrno" -> errno number length
    _ -> error "unknown original truncate entry"
  observedPosition <- if privateFile then fromIntegral <$> fdSeek fd RelativeSeek 0 else pure (-1 :: Integer)
  release
  observedSize <- if privateFile then fileSize <$> getFileStatus privatePath else pure (-1)
  writeFile resultPath (show result ++ "\n" ++ show observedSize ++ "\n" ++ show observedPosition ++ "\n")
