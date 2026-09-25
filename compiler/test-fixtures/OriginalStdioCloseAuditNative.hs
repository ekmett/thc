-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import Control.Exception (evaluate)
import Control.Monad (unless)
import GHC.Exts (Int(I#))
import System.Environment (getArgs)
import System.Posix.IO (OpenMode(ReadOnly), defaultFileFlags, openFd)
import System.Posix.Types (Fd(..))
import qualified OriginalStdioCloseAudit as Original

-- The only successful close consumes a private regular-file descriptor. A
-- second original close proves it is gone before the result file is opened.
main :: IO ()
main = do
  [entry, scenario, privatePath, resultPath] <- getArgs
  fd <- case scenario of
    "valid" -> do
      writeFile privatePath "keep"
      openFd privatePath ReadOnly defaultFileFlags
    "invalid" -> pure (Fd (-1))
    _ -> error "unknown close scenario"
  let run (I# raw) = I# (case entry of
        "originalClose" -> Original.originalClose raw
        "originalCloseErrno" -> Original.originalCloseErrno raw
        _ -> error "unknown original close entry")
      number = case fd of Fd raw -> fromIntegral raw :: Int
  result <- evaluate (run number)
  if scenario == "valid" then do
    second <- evaluate $ case number of I# raw -> I# (Original.originalClose raw)
    unless (second == -1) (error "original close did not retire the private descriptor")
  else pure ()
  writeFile resultPath (show result ++ "\n")
