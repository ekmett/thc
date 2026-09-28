-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : CoreIndexTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; HUnit
--
-- Byte-preserving bundle entries and stale/corrupt source rejection controls.
module CoreIndexTests (tests) where

import Control.Exception (IOException, try)
import Control.Monad (forM_)
import Codec.Archive.Zip (Archive(..), Entry(..), emptyArchive, fromArchive, toEntry)
import Data.Aeson (Value(..), object, toJSON, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Either (isLeft)
import Test.HUnit (Test(..), assertBool, assertEqual)
import THC.Driver.CoreIndex
import THC.Driver.Zip (decodeZip, decodeZipMembers, readZipMember)

tests :: Test
tests = TestLabel "Core package members" $ TestList
  [ TestCase $ do
      let source = "{ \"name\": \"non-ASCII: \195\169\", \"body\": [1, true, null] }\r\n"
      (refs, entries) <- packageModules [("Example", "core/0.json", source)]
      assertEqual "JSON-only inventory" ["core/0.json"] (map fst entries)
      assertEqual "JSON is preserved byte for byte" (Just source) (lookup "core/0.json" entries)
      assertEqual "declared source survives projection" (Just entries) (moduleEntries (first refs) entries)
      assertEqual "amended final bytes cannot reuse stale source identity" Nothing
        (moduleEntries (first refs) [("core/0.json", BS.init source <> " ")])
  , TestCase $ do
      (refs, entries) <- packageModules [("A", "a.json", "{}"), ("B", "b.json", "[]")]
      assertEqual "stable supplied order" ["a.json", "b.json"] (map fst entries)
      assertEqual "selected projection retains only its source" (Just [first entries]) (moduleEntries (first refs) entries)
  , TestCase $ do
      (refs, entries) <- packageModules [("A", "a.json", "{}")]
      let ref = first refs
      forM_ [Null, object [], object ["path" .= ("a.json.idx" :: String), "sha256" .= (replicate 64 '0')]] $ \value ->
        assertEqual "retired index records must be regenerated" Nothing
          (moduleEntries (change (KeyMap.insert "index" value) ref) entries)
      assertEqual "missing source rejected" Nothing (moduleEntries ref [])
      assertEqual "wrong source digest rejected" Nothing (moduleEntries ref [("a.json", "[]")])
      forM_ ["../escape", "/absolute", "C:/absolute", "a\\b", "manifest.json", "inplace-manifest.json"] $ \path ->
        assertEqual ("reject unsafe source " ++ path) Nothing $ modulePaths
          (change (KeyMap.insert "path" (toJSON path)) ref)
  , TestCase $ forM_ [[("A", "x.json", "{}"), ("B", "x.json", "[]")],
                      [("A", "../x.json", "{}")]] $ \modules -> do
      result <- try (packageModules modules) :: IO (Either IOException ([Value], [(FilePath, BS.ByteString)]))
      assertBool "producer rejects collisions and unsafe paths" (isLeft result)
  , TestCase $ do
      let corrupt = "bad deflate"
          cold = (toEntry "cold.json" 0 (BL.replicate 100000 65))
            {eCompressedData = corrupt, eCompressedSize = fromIntegral (BL.length corrupt)}
          bytes = BL.toStrict (fromArchive emptyArchive {zEntries = [toEntry "selected.json" 0 "{}", cold]})
      entries <- either fail pure =<< decodeZipMembers bytes
      selected <- maybe (fail "missing selected member") readZipMember (lookup "selected.json" entries)
      assertEqual "directory admission does not inflate a cold member" "{}" selected
      rejected <- try (maybe (fail "missing cold member") readZipMember (lookup "cold.json" entries))
        :: IO (Either IOException BS.ByteString)
      assertBool "selected malformed compression fails at access" (isLeft rejected)
      assertBool "legacy exhaustive decoder still rejects the whole archive" . isLeft =<< decodeZip bytes
  , TestCase $ forM_ [["../escape"], ["same", "same"]] $ \names -> do
      let bytes = BL.toStrict (fromArchive emptyArchive {zEntries = [toEntry name 0 "{}" | name <- names]})
      assertBool "member-at-a-time reader preserves directory safety" . isLeft =<< decodeZipMembers bytes
  ]
  where
    first (value:_) = value
    first [] = error "test setup did not produce a module pair"
    change f (Object fields) = Object (f fields)
    change _ value = value
