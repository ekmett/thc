-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : CoreIndexTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; HUnit and the portable JSON index FFI
--
-- Byte-preserving bundle pairing and corrupt/stale sidecar rejection controls.
module CoreIndexTests (tests) where

import Control.Exception (IOException, try)
import Control.Monad (forM_)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (Value(..), object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import Data.Either (isLeft)
import Numeric (showHex)
import Test.HUnit (Test(..), assertBool, assertEqual)
import THC.Driver.CoreIndex
import THC.JsonIndex (validateSidecar)

tests :: Test
tests = TestLabel "source-bound Core index package pairs" $ TestList
  [ TestCase $ do
      let source = "{ \"name\": \"non-ASCII: \195\169\", \"body\": [1, true, null] }\r\n"
      (refs, entries) <- indexedModules [("Example", "core/0.json", source)]
      assertEqual "adjacent JSON/index inventory" ["core/0.json", "core/0.json.idx"] (map fst entries)
      assertEqual "JSON is preserved byte for byte" (Just source) (lookup "core/0.json" entries)
      assertEqual "declared pair survives projection" (Just entries) (moduleEntries (first refs) entries)
      let index = snd (entries !! 1)
      assertEqual "v2 envelope binds final bytes" (Right ()) (validateSidecar source index)
      assertBool "same-shape amended final bytes cannot reuse a stale index"
        (isLeft (validateSidecar (BS.init source <> " ") index))
  , TestCase $ do
      (refs, entries) <- indexedModules [("A", "a.json", "{}"), ("B", "b.json", "[]")]
      assertEqual "stable supplied order" ["a.json", "a.json.idx", "b.json", "b.json.idx"] (map fst entries)
      let ref = first refs
          legacy = change (KeyMap.delete "index") ref
      assertEqual "legacy no-index module" (Just [first entries]) (moduleEntries legacy entries)
      assertEqual "selected projection retains only its own pair" (Just (take 2 entries)) (moduleEntries ref entries)
  , TestCase $ do
      (refs, entries) <- indexedModules [("A", "a.json", "{}")]
      let ref = first refs
          malformed = [Null, object [], object ["path" .= ("a.json.idx" :: String)],
            object ["path" .= ("a.json.idx" :: String), "sha256" .= ("x" :: String), "extra" .= True]]
      forM_ malformed $ \value -> assertEqual "explicit malformed index never becomes legacy" Nothing
        (moduleEntries (change (KeyMap.insert "index" value) ref) entries)
      forM_ ["a.json", "../escape", "/absolute", "C:/absolute", "a\\b", "manifest.json", "inplace-manifest.json"] $ \path ->
        assertEqual ("reject unsafe/colliding index " ++ path) Nothing $ modulePaths
          (change (KeyMap.insert "index" (object ["path" .= path, "sha256" .= ("x" :: String)])) ref)
  , TestCase $ do
      (refs, entries) <- indexedModules [("A", "a.json", "{}")]
      let ref = first refs
          index = snd (entries !! 1)
          replace offset bytes = BS.take offset index <> bytes <> BS.drop (offset + BS.length bytes) index
          sealed body = BS.take (BS.length body - 32) body <> SHA.hash (BS.take (BS.length body - 32) body)
          corrupt = [BS.take 95 index, index <> "x", replace 8 "\1", replace 12 "\1",
            sealed (replace 16 "\3"), sealed (replace 24 "\255"), sealed (replace 32 (BS.replicate 32 0)),
            replace (BS.length index - 1) "x"]
      forM_ corrupt $ \bytes -> do
        let rewritten = change (KeyMap.insert "index" (object
              ["path" .= ("a.json.idx" :: String), "sha256" .= digest bytes])) ref
        assertEqual "hash-consistent malformed/stale index rejected" Nothing
          (moduleEntries rewritten [first entries, ("a.json.idx", bytes)])
      assertEqual "missing declared index rejected" Nothing (moduleEntries ref [first entries])
      assertEqual "wrong declared index digest rejected" Nothing (moduleEntries ref [first entries, ("a.json.idx", index <> "x")])
  , TestCase $ forM_ [[("A", "x.json", "{}"), ("B", "x.json", "[]")],
                      [("A", "x.json", "{}"), ("B", "x.json.idx", "[]")],
                      [("A", "../x.json", "{}")]] $ \modules -> do
      result <- try (indexedModules modules) :: IO (Either IOException ([Value], [(FilePath, BS.ByteString)]))
      assertBool "producer rejects collisions and unsafe paths" (isLeft result)
  ]
  where
    first (value:_) = value
    first [] = error "test setup did not produce a module pair"
    change f (Object fields) = Object (f fields)
    change _ value = value
    digest = concatMap (\byte -> let hex = showHex byte "" in if length hex == 1 then '0':hex else hex) . BS.unpack . SHA.hash
