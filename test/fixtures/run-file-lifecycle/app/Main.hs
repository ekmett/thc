-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Main (main) where

import Control.Exception (IOException, try)
import Control.Monad (unless)
import System.IO
  (IOMode (AppendMode, ReadMode, ReadWriteMode, WriteMode), SeekMode (AbsoluteSeek, SeekFromEnd),
   hFlush, hGetLine, hIsEOF, hPutStr, hSeek, hSetEncoding, utf8, withFile)
import System.IO.Error (isDoesNotExistError)

main :: IO ()
main = do
  let path = "lifecycle.txt"
      first = "λé"
      second = "\x1d11e"
  withFile path WriteMode $ \handle -> do
    hSetEncoding handle utf8
    hPutStr handle (first ++ "\n")
    hFlush handle
  withFile path ReadWriteMode $ \handle -> do
    hSetEncoding handle utf8
    hSeek handle AbsoluteSeek 0
    line <- hGetLine handle
    unless (line == first) (fail "UTF-8 write/read or absolute seek failed")
    hSeek handle SeekFromEnd 0
    eof <- hIsEOF handle
    unless eof (fail "seek to end did not reach EOF")
  withFile path AppendMode $ \handle -> do
    hSetEncoding handle utf8
    hPutStr handle (second ++ "\n")
  withFile path ReadMode $ \handle -> do
    hSetEncoding handle utf8
    linesRead <- sequence [hGetLine handle, hGetLine handle]
    eof <- hIsEOF handle
    unless (linesRead == [first, second] && eof)
      (fail "UTF-8 append/read or EOF failed")
  missing <- try (withFile "missing.txt" ReadMode hIsEOF) :: IO (Either IOException Bool)
  case missing of
    Left problem -> unless (isDoesNotExistError problem)
      (fail "missing-path open raised the wrong IOException")
    Right _ -> fail "opening a missing file unexpectedly succeeded"
  -- No newline: the executable shutdown path must flush GHC's stdout Handle.
  putStr "file lifecycle ok"
