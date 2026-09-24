-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import Control.Exception (evaluate)
import Data.Word (Word8)
import Foreign.Marshal.Array (withArray)
import GHC.Exts (Int(I#), Word(W#), Ptr(Ptr))
import System.Environment (getArgs)
import qualified OriginalStdioAudit as Original

-- One invocation per process: stdout/stderr contain ONLY the original write's
-- binary payload. A separate result file cannot corrupt either byte stream.
main :: IO ()
main = do
  [entry, fdText, offsetText, countText, resultPath] <- getArgs
  withArray ([0..255] :: [Word8]) $ \(Ptr address) -> do
    result <- evaluate $ case (read fdText, read offsetText, read countText) of
      (I# fd, I# offset, W# count) -> case entry of
        "originalWrite" -> I# (Original.originalWrite fd address offset count)
        "originalSafeWrite" -> I# (Original.originalSafeWrite fd address offset count)
        "originalWriteErrno" -> I# (Original.originalWriteErrno fd address offset count)
        "originalSafeWriteErrno" -> I# (Original.originalSafeWriteErrno fd address offset count)
        _ -> error "unknown original stdio entry"
    writeFile resultPath (show result ++ "\n")
