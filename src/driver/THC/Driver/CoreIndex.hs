-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Driver.CoreIndex
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; bytestring and SHA-256
--
-- Preserve final Core bytes and validate their package member references.
module THC.Driver.CoreIndex
  ( packageModules, modulePaths, moduleEntries ) where

import Control.Monad (guard)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (FromJSON, Value(..), fromJSON, Result(..), object, (.=))
import Data.Aeson.Key (Key)
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import Data.Char (isAscii, isControl, isAlpha)
import Data.List (nub)
import Numeric (showHex)

-- | Input bytes must already contain every native/ABI linking amendment.
-- Preserve them exactly in supplied order.
packageModules :: [(String, FilePath, BS.ByteString)] -> IO ([Value], [(FilePath, BS.ByteString)])
packageModules modules =
  if all safeMember paths && length paths == length (nub paths)
    then pure (refs, entries)
    else fail "invalid or duplicate Core member paths"
  where
    paths = [path | (_, path, _) <- modules]
    entries = [(path, bytes) | (_, path, bytes) <- modules]
    refs = [object ["name" .= name,
      "boundary" .= ("optimized-Core-after-Tidy-before-CorePrep" :: String),
      "path" .= path, "sha256" .= digest bytes] | (name, path, bytes) <- modules]

-- | Sidecar records are retired; stale cache records must be regenerated.
modulePaths :: Value -> Maybe [FilePath]
modulePaths item@(Object fields) = do
  guard (not (KeyMap.member "index" fields))
  path <- field item "path"
  guard (safeMember path)
  pure [path]
modulePaths _ = Nothing

-- | Validate source identity before retaining a cache hit or projection.
moduleEntries :: Value -> [(FilePath, BS.ByteString)] -> Maybe [(FilePath, BS.ByteString)]
moduleEntries item entries = do
  paths <- modulePaths item
  path <- field item "path"
  expected <- field item "sha256"
  bytes <- lookup path entries
  guard (paths == [path] && digest bytes == expected)
  pure [(path, bytes)]

field :: FromJSON a => Value -> Key -> Maybe a
field (Object fields) key = KeyMap.lookup key fields >>= \value -> case fromJSON value of
  Success result -> Just result
  Error _ -> Nothing
field _ _ = Nothing

safeMember :: FilePath -> Bool
safeMember [] = False
safeMember ('/':_) = False
safeMember path =
  path `notElem` ["manifest.json", "inplace-manifest.json"] &&
  not (case path of drive:':':_ -> isAlpha drive; _ -> False) &&
  all (\c -> isAscii c && not (isControl c) && c /= '\\') path &&
  all (`notElem` ["", ".", ".."]) (parts path)
  where
    parts value = case break (== '/') value of
      (part, []) -> [part]
      (part, _:rest) -> part : parts rest

digest :: BS.ByteString -> String
digest = concatMap (\byte -> let hex = showHex byte "" in if length hex == 1 then '0':hex else hex) . BS.unpack . SHA.hash
