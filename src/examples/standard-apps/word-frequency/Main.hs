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
-- Count case-folded, whitespace-separated words in UTF-8 files. Punctuation is
-- part of a word. Files are read one at a time; the word table remains in memory.
module Main (main) where

import Control.Exception (IOException, displayException, try)
import Control.Monad (foldM)
import qualified Data.ByteString as Bytes
import Data.List (foldl')
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import qualified Data.Text.IO as Text
import System.Environment (getArgs)
import System.Exit (die)

countFile :: Map.Map Text.Text Integer -> FilePath -> IO (Map.Map Text.Text Integer)
countFile counts path = do
  result <- try (Bytes.readFile path) :: IO (Either IOException Bytes.ByteString)
  bytes <- either (die . displayException) pure result
  content <- either (const (die (path ++ ": invalid UTF-8"))) pure (Text.decodeUtf8' bytes)
  pure (foldl' (\table word -> Map.insertWith (+) word 1 table) counts
        (Text.words (Text.toCaseFold content)))

-- | Print one count and word per line in lexical order, accumulating all named
-- files. Invalid input fails without publishing a partial frequency table.
main :: IO ()
main = do
  paths <- getArgs
  if null paths then die "usage: word-frequency FILE..." else do
    counts <- foldM countFile Map.empty paths
    mapM_ (\(word, count) -> Text.putStrLn (Text.pack (show count) <> Text.singleton '\t' <> word))
      (Map.toAscList counts)
