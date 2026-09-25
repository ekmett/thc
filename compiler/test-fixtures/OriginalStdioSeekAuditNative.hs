-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import Control.Exception (evaluate)
import Control.Monad (unless)
import GHC.Exts (Int(I#))
import GHC.Internal.Int (Int64(I64#))
import System.Environment (getArgs)
import System.Posix.IO (OpenMode(ReadOnly), closeFd, createPipe, defaultFileFlags, openFd)
import System.Posix.Types (Fd(..))
import qualified OriginalStdioSeekAudit as Original

main :: IO ()
main = do
  [entry, scenario, privatePath, resultPath] <- getArgs
  (fd, release) <- case scenario of
    "invalid" -> pure (Fd (-1), pure ())
    "pipe" -> do
      (reader, writer) <- createPipe
      pure (reader, closeFd reader >> closeFd writer)
    _ -> do
      writeFile privatePath "abcdef"
      opened <- openFd privatePath ReadOnly defaultFileFlags
      pure (opened, closeFd opened)
  let number = case fd of Fd value -> fromIntegral value :: Int
      (displacement, whence) = case scenario of
        "set" -> (2, 0)
        "cur" -> (2, 1)
        "end" -> (-2, 2)
        "beyond" -> (10, 0)
        "negative" -> (-1, 0)
        "bad-whence" -> (0, 9)
        "invalid" -> (0, 0)
        "pipe" -> (0, 0)
        _ -> error "unknown seek scenario"
      seek :: Int -> Int64 -> Int -> Int64
      seek (I# rawFd) (I64# rawDisplacement) (I# rawWhence) =
        I64# (Original.originalSeek rawFd rawDisplacement rawWhence)
      errno :: Int -> Int64 -> Int -> Int
      errno (I# rawFd) (I64# rawDisplacement) (I# rawWhence) =
        I# (Original.originalSeekErrno rawFd rawDisplacement rawWhence)
  if scenario == "cur" then do
    primed <- evaluate (seek number 3 0)
    unless (primed == 3) (error "unable to prime private file position")
  else pure ()
  result <- evaluate $ case entry of
    "originalSeek" -> fromIntegral (seek number displacement whence) :: Int
    "originalSeekErrno" -> errno number displacement whence
    _ -> error "unknown original seek entry"
  release
  writeFile resultPath (show result ++ "\n")
