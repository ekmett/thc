-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC FFI and a C compiler; portable scalar fallback
--
-- Build a navigation sidecar for exact JSON bytes, without parsing/re-emitting
-- the document. This is not a Core admission audit or a full JSON validator.
module Main (main) where

import Control.Exception (bracketOnError, finally)
import Control.Monad (when)
import qualified Data.ByteString as BS
import System.Directory (canonicalizePath, removeFile, renameFile)
import System.Environment (getArgs)
import System.Exit (die)
import System.FilePath (takeDirectory, takeFileName)
import System.IO (hClose, openBinaryTempFile)
import THC.JsonIndex (writeSidecar)
import THC.JsonIndex.Scanner (Backend (..))

main :: IO ()
main = do
  args <- getArgs
  case args of
    ["--help"] -> putStrLn usage
    [source, output] -> build Automatic source output
    ["--backend", backend, source, output] -> case backend of
      "auto" -> build Automatic source output
      "scalar" -> build Scalar source output
      "avx2" -> build AVX2 source output
      "neon" -> build NEON source output
      _ -> die "Unknown backend: use auto, scalar, avx2, or neon"
    _ -> die usage
  where
    usage = "Usage: thc-json-index [--backend auto|scalar|avx2|neon] INPUT.json OUTPUT.idx"

build :: Backend -> FilePath -> FilePath -> IO ()
build backend input output = do
  inputPath <- canonicalizePath input
  outputPath <- canonicalizePath output
  when (inputPath == outputPath) $ die "The sidecar output must differ from its JSON source"
  source <- BS.readFile inputPath
  -- Same-directory temporary + rename keeps a failed build from replacing an
  -- existing sidecar, and avoids mutating the input even through a hard link.
  bracketOnError
    (openBinaryTempFile (takeDirectory outputPath) (takeFileName outputPath ++ ".tmp"))
    (\(temporary, handle) -> hClose handle `finally` removeFile temporary)
    (\(temporary, handle) -> do
      writeSidecar handle backend source
      hClose handle
      renameFile temporary outputPath)
