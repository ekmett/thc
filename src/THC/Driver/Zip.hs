-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.Driver.Zip
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; bytestring and zip-archive
--
-- Encode and validate deterministic, path-safe ZIP bundles.
module THC.Driver.Zip (encodeZip, decodeZip) where

import Codec.Archive.Zip (Archive(..), Entry(..), emptyArchive, fromArchive,
                          fromEntry, toArchiveOrFail, toEntry)
import Control.Exception (SomeException, evaluate, try)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.List (nub)

-- | Encode members with a fixed timestamp, preserving their order. Reject
-- duplicate names, absolute paths and traversal components before encoding.
encodeZip :: [(String, BS.ByteString)] -> Either String BL.ByteString
encodeZip files
  | any (not . safeName . fst) files = Left "invalid ZIP member name"
  | length names /= length (nub names) = Left "duplicate ZIP member name"
  | otherwise = Right $ fromArchive emptyArchive
      { zEntries = [toEntry name 0 (BL.fromStrict bytes) | (name, bytes) <- files] }
  where names = map fst files

-- | Decode and force every member in IO, rejecting corrupt archives and unsafe
-- or duplicate names. This reads an archive; it does not extract files to disk.
decodeZip :: BS.ByteString -> IO (Either String [(String, BS.ByteString)])
decodeZip bytes = do
  parsed <- try (evaluate (decode bytes)) :: IO (Either SomeException (Either String [(String, BS.ByteString)]))
  pure $ either (const (Left "invalid Core ZIP")) id parsed
  where
    decode contents = do
      archive <- toArchiveOrFail (BL.fromStrict contents)
      let entries = zEntries archive
          names = map eRelativePath entries
      if any (not . safeName) names || length names /= length (nub names)
        then Left "invalid Core ZIP member names"
        else let decoded = [(eRelativePath entry, BL.toStrict (fromEntry entry)) | entry <- entries]
             in foldr (\(_, body) result -> BS.length body `seq` result) (Right decoded) decoded

safeName :: String -> Bool
safeName name = case name of
  [] -> False
  '/':_ -> False
  _ -> all (\c -> c /= '\\' && c /= '\0' && fromEnum c < 128) name &&
       all (\part -> not (null part) && part /= "." && part /= "..") (split name)
  where
    split value = case break (== '/') value of
      (part, []) -> [part]
      (part, _:rest) -> part : split rest
