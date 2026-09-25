-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import Control.Exception (evaluate)
import Control.Monad (when)
import Data.Word (Word8)
import Foreign.Marshal.Array (withArray, peekArray)
import GHC.Exts (Int(I#), Word(W#), Ptr(Ptr))
import Numeric (showHex)
import System.Environment (getArgs)
import System.IO (SeekMode(AbsoluteSeek))
import System.Posix.IO (OpenMode(ReadOnly), closeFd, defaultFileFlags,
                        dupTo, fdSeek, openFd, stdInput)
import qualified OriginalStdioReadAudit as Original

hex :: Word8 -> String
hex byte = let digits = showHex byte "" in replicate (2 - length digits) '0' ++ digits

main :: IO ()
main = do
  [entry, fdText, offsetText, countText, positionText, inputPath, resultPath] <- getArgs
  input <- openFd inputPath ReadOnly defaultFileFlags
  _ <- fdSeek input AbsoluteSeek (read positionText)
  _ <- dupTo input stdInput
  -- With a closed child stdin, openFd may return 0; dupTo 0 0 is a no-op.
  when (input /= stdInput) (closeFd input)
  withArray (replicate 16 0xa5 :: [Word8]) $ \address -> do
    result <- evaluate $ case (read fdText, read offsetText, read countText) of
      (I# fd, I# offset, W# count) -> case entry of
        "originalRead" -> I# (Original.originalRead fd (case address of Ptr raw -> raw) offset count)
        "originalSafeRead" -> I# (Original.originalSafeRead fd (case address of Ptr raw -> raw) offset count)
        "originalReadErrno" -> I# (Original.originalReadErrno fd (case address of Ptr raw -> raw) offset count)
        "originalSafeReadErrno" -> I# (Original.originalSafeReadErrno fd (case address of Ptr raw -> raw) offset count)
        _ -> error "unknown original read entry"
    bytes <- peekArray 16 address
    writeFile resultPath (show result ++ "\n" ++ concatMap hex bytes ++ "\n")
