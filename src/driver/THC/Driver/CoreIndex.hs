-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Driver.CoreIndex
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; bytestring and the portable JSON index FFI
--
-- Pair final Core bytes with optional, source-bound navigation sidecars.
module THC.Driver.CoreIndex
  ( indexedModules, modulePaths, moduleEntries, indexFormat ) where

import Control.Monad (guard)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (FromJSON, Value(..), fromJSON, Result(..), object, (.=))
import Data.Aeson.Key (Key)
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import Data.Char (isAscii, isControl, isAlpha)
import Data.List (nub, sort)
import Numeric (showHex)
import THC.JsonIndex (encodeSidecar, validateSidecar)
import THC.JsonIndex.Scanner (Backend(Automatic))

-- | The output contract participates in exporter cache identities. The linked
-- driver/plugin artifact hashes cover the actual encoder implementation.
indexFormat :: Value
indexFormat = object ["magic" .= ("THCJSIX1" :: String), "version" .= (2 :: Int)]

-- | Input bytes must already contain every native/ABI linking amendment.
-- Preserve them exactly and place each sidecar immediately after its JSON.
indexedModules :: [(String, FilePath, BS.ByteString)] -> IO ([Value], [(FilePath, BS.ByteString)])
indexedModules modules = do
  pairs <- mapM indexed modules
  let entries = concatMap snd pairs
      paths = map fst entries
  if all safeMember paths && length paths == length (nub paths)
    then pure (map fst pairs, entries)
    else fail "invalid or duplicate Core/index member paths"
  where
    indexed (name, path, bytes) = do
      index <- encodeSidecar Automatic bytes
      let indexPath = path ++ ".idx"
          ref = object ["name" .= name,
            "boundary" .= ("optimized-Core-after-Tidy-before-CorePrep" :: String),
            "path" .= path, "sha256" .= digest bytes,
            "index" .= object ["path" .= indexPath, "sha256" .= digest index]]
      pure (ref, [(path, bytes), (indexPath, index)])

-- | All declared payload paths, including a strictly shaped optional sidecar.
-- Legacy records without an index remain valid. An explicit malformed/null
-- index is rejected rather than interpreted as a legacy record.
modulePaths :: Value -> Maybe [FilePath]
modulePaths item = do
  path <- field item "path"
  guard (safeMember path)
  optional <- indexReference item
  let paths = path : [indexPath | Just (indexPath, _) <- [optional]]
  guard (length paths == length (nub paths))
  pure paths

-- | Check the declared pair before retaining it in a cache hit or projection.
-- This checks envelope binding; the navigation consumer validates its records.
moduleEntries :: Value -> [(FilePath, BS.ByteString)] -> Maybe [(FilePath, BS.ByteString)]
moduleEntries item entries = do
  paths <- modulePaths item
  path <- field item "path"
  expected <- field item "sha256"
  bytes <- lookup path entries
  guard (digest bytes == expected)
  optional <- indexReference item
  case optional of
    Nothing -> pure [(path, bytes)]
    Just (indexPath, indexHash) -> do
      index <- lookup indexPath entries
      guard (digest index == indexHash && validateSidecar bytes index == Right ())
      guard (paths == [path, indexPath])
      pure [(path, bytes), (indexPath, index)]

indexReference :: Value -> Maybe (Maybe (FilePath, String))
indexReference (Object fields) = case KeyMap.lookup "index" fields of
  Nothing -> Just Nothing
  Just value@(Object index) -> do
    guard (sort (KeyMap.keys index) == ["path", "sha256"])
    path <- field value "path"
    expected <- field value "sha256"
    guard (safeMember path)
    pure (Just (path, expected))
  _ -> Nothing
indexReference _ = Nothing

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
