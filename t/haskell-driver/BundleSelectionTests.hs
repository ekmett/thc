-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : BundleSelectionTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; HUnit and host filesystem services
--
-- Exercise the real upstream selection readers before direct-unit publication.
-- Controlled archives are cache tests, not native/compiler provenance fixtures.
module BundleSelectionTests (tests) where

import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (Value(..), encode, object, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import qualified Data.Map.Strict as Map
import Data.Either (isLeft)
import Data.Maybe (isNothing)
import Numeric (showHex)
import System.Directory (getModificationTime, removeFile, setModificationTime)
import System.FilePath ((</>))
import System.IO.Error (tryIOError)
import Test.HUnit (Test(..), assertBool, assertEqual)
import THC.Driver.CoreSymbols (publishCoreUnit)
import THC.Driver.Project (Bundle(..), BundleReceipt(..), readGlobalBundle, readBundle,
  exceptionBridgeModules, projectWindowsWiredBundle, readCapturedStoreBundles)
import THC.Driver.Zip (encodeZip)
import TestSupport (Env, withFixtureNamed)

tests :: Env -> Test
tests env = TestLabel "upstream successful Core selections" $ TestList
  [ TestCase $ scratch "global" $ \directory -> do
      let path = directory </> "global.zip"
          readSelected verify = readGlobalBundle verify path "test-unit" [] "build" "export"
      _ <- archive path "test-unit" [("A", core "test-unit" "A")] Nothing []
      first <- requireBundle =<< readSelected False
      published <- publishCoreUnit (directory </> "cache") False (unitRecord "test-unit" first)
      corruptUnobserved path
      warm <- requireBundle =<< readSelected False
      assertEqual "original selection identity is replayed" (snapshot first) (snapshot warm)
      assertEqual "upstream read plus final publication does not open unchanged payloads" published
        =<< publishCoreUnit (directory </> "cache") False (unitRecord "test-unit" warm)
      assertBool "explicit verification reads and rejects the corrupt archive" . isNothing =<< readSelected True
      assertBool "changed dependencies do not replay old selection" . isNothing
        =<< readGlobalBundle False path "test-unit" ["new-dependency"] "build" "export"
      assertBool "changed export key takes cold path" . isNothing
        =<< readGlobalBundle False path "test-unit" [] "build" "new-export"
      removeFile (path ++ ".selection.json")
      assertBool "missing selection receipt takes cold path" . isNothing =<< readSelected False
  , TestCase $ scratch "captured" $ \directory -> do
      let path = directory </> "captured.zip"
          manifest = directory </> "captured.json"
          identity = object ["unit" .= ("test-unit" :: String), "depends" .= ([] :: [String]),
            "configuration" .= object ["style" .= ("global" :: String)]]
          request = object ["compiler" .= ("ghc-9.14.1" :: String),
            "units" .= [identity], "inputs" .= object []]
      bundle <- archive path "test-unit" [("A", core "test-unit" "A")] Nothing []
      let row = object ["unit" .= ("test-unit" :: String), "path" .= path,
            "sha256" .= bundleHash bundle, "buildKey" .= ("build" :: String), "exportKey" .= ("export" :: String)]
          supplied :: [Value] -> Value
          supplied rows = object ["format" .= ("thc-captured-store-bundles" :: String),
            "schema" .= (1 :: Int), "request" .= request, "bundles" .= rows]
          load expected = readCapturedStoreBundles True expected [("test-unit",[])] manifest
      BS.writeFile manifest (encoded (supplied [row]))
      selected <- load request
      assertEqual "explicit capture keeps the original producer identity and path"
        (Just (snapshot bundle)) (snapshot <$> Map.lookup "test-unit" selected)
      assertBool "different local/native input snapshot rejects retained capture" . isLeft =<<
        tryIOError (load (object ["compiler" .= ("ghc-9.14.1" :: String),
          "units" .= [identity], "inputs" .= object ["changed" .= True]]))
      BS.writeFile manifest (encoded (supplied [row,row]))
      assertBool "duplicate supplied owners are rejected" . isLeft =<< tryIOError (load request)
      BS.writeFile manifest (encoded (supplied []))
      assertBool "missing owner cannot silently trigger a whole capture" . isLeft =<< tryIOError (load request)
      BS.writeFile manifest (encoded (supplied [row]))
      BS.appendFile path "changed"
      assertBool "supplied digest is checked against the validated archive" . isLeft =<< tryIOError (load request)
  , TestCase $ scratch "configured" $ \directory -> do
      let path = directory </> "configured.zip"
          inputs = object ["unit" .= ("test-unit" :: String), "component" .= ("original" :: String)]
          readSelected verify = readBundle verify PlainBundle path "test-unit" "build" "export" inputs ["A"]
      _ <- archive path "test-unit" [("A", core "test-unit" "A")] (Just inputs) []
      first <- requireBundle =<< readSelected False
      published <- publishCoreUnit (directory </> "cache") False (unitRecord "test-unit" first)
      corruptUnobserved path
      warm <- requireBundle =<< readSelected False
      assertEqual "configured selection preserves complete module records" (snapshot first) (snapshot warm)
      assertEqual "configured read plus publication skips payload" published
        =<< publishCoreUnit (directory </> "cache") False (unitRecord "test-unit" warm)
      assertBool "verify still performs original configured validation" . isNothing =<< readSelected True
      assertBool "changed full build inputs miss" . isNothing
        =<< readBundle False PlainBundle path "test-unit" "build" "export" (object []) ["A"]
      assertBool "changed expected module inventory misses" . isNothing
        =<< readBundle False PlainBundle path "test-unit" "build" "export" inputs ["B"]
      BS.appendFile path "changed-size"
      assertBool "changed file observations miss" . isNothing =<< readSelected False
  , TestCase $ scratch "receipt" $ \directory -> do
      let path = directory </> "receipt.zip"
          readSelected = readGlobalBundle False path "test-unit" [] "build" "export"
      _ <- archive path "test-unit" [("A", core "test-unit" "A")] Nothing []
      _ <- requireBundle =<< readSelected
      BS.writeFile (path ++ ".selection.json") "broken receipt"
      _ <- requireBundle =<< readSelected
      corruptUnobserved path
      -- A rebuilt successful descriptor is useful; a malformed one is never
      -- permission to advertise an unread/invalid payload.
      _ <- requireBundle =<< readSelected
      BS.writeFile (path ++ ".selection.json") "broken receipt"
      assertBool "corrupt receipt falls back to actual archive validation" . isNothing =<< readSelected
      removeFile path
      assertBool "missing payload is not satisfied by metadata" . isLeft =<< tryIOError readSelected
  , TestCase $ scratch "bridge" $ \directory -> do
      let path = directory </> "bridge.zip"
          values = [(name, core "test-unit" name) | name <- ["THC.Exception", "THC.Internal.Exception", "Cold"]]
      bundle <- archive path "test-unit" values Nothing []
      let record = unitRecord "test-unit" bundle
      first <- exceptionBridgeModules False [record]
      assertEqual "only the two actual bridge modules are selected" (map snd (take 2 values)) first
      corruptUnobserved path
      assertEqual "unchanged bridge selection preserves original metadata" first =<< exceptionBridgeModules False [record]
      assertBool "explicit bridge verification reads original bundle" . isLeft
        =<< tryIOError (exceptionBridgeModules True [record])
      let different = unitRecord "other-unit" bundle
      assertBool "bridge owner is an input, not inferred from cached names" . isLeft
        =<< tryIOError (exceptionBridgeModules False [different])
      BS.writeFile (path ++ ".bridge.json") "broken receipt"
      assertBool "corrupt bridge receipt falls back to archive" . isLeft
        =<< tryIOError (exceptionBridgeModules False [record])
  , TestCase $ scratch "Windows projection" $ \directory -> do
      let path = directory </> "full.zip"
          name = "GHC.Internal.Conc.Bound"
          excluded = [object ["module" .= name]]
          specification = object ["archiveOnlyModules" .= excluded]
          registration = case core "ghc-internal" name of
            Object fields -> Object (KM.insert "foreign" (object ["stubs" .=
              object ["initializers" .= (["control-only-initializer"] :: [String])]]) fields)
            _ -> error "object expected"
          inputs = object ["compiler" .= object [], "component" .= object ["generatedSources" .= ([] :: [String])],
            "generatedSources" .= ([] :: [Value])]
      full <- archive path "ghc-internal" [("A", core "ghc-internal" "A"), (name, registration)]
        (Just inputs) [("targetLayout", object [])]
      selected <- projectWindowsWiredBundle False directory full specification
      corruptUnobserved path
      corruptUnobserved (bundlePath selected)
      warm <- projectWindowsWiredBundle False directory full specification
      assertEqual "projection hit occurs before either full or projected archive opens"
        (snapshot selected) (snapshot warm)
      assertBool "explicit projection verification reads complete original source" . isLeft
        =<< tryIOError (projectWindowsWiredBundle True directory full specification)
      assertBool "changed exclusion specification takes cold source path" . isLeft
        =<< tryIOError (projectWindowsWiredBundle False directory full (object ["archiveOnlyModules" .= ([] :: [Value])]))
  ]
  where
    scratch name = withFixtureNamed env "t/fixtures/run-pure" ("selection " ++ name)

core :: String -> String -> Value
core unit name = object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
  "unit" .= unit, "module" .= name, "boundary" .= boundary,
  "bindings" .= [object ["id" .= (unit ++ ":" ++ name ++ ".value"),
    "expr" .= (["lit", "int", "7"] :: [String])]], "constructors" .= ([] :: [Value])]

boundary :: String
boundary = "optimized-Core-after-Tidy-before-CorePrep"

archive :: FilePath -> String -> [(String, Value)] -> Maybe Value -> [(String, Value)] -> IO Bundle
archive path owner values inputs extra = do
  let bodies = [(name, "core/" ++ show index ++ ".json", encoded value)
        | (index, (name, value)) <- zip [0 :: Int ..] values]
      refs = [object ["name" .= name, "path" .= member, "sha256" .= digest body, "boundary" .= boundary]
        | (name, member, body) <- bodies]
      base = object ["format" .= ("thc-core-bundle" :: String), "schema" .= (1 :: Int),
        "unit" .= owner, "buildKey" .= ("build" :: String), "exportKey" .= ("export" :: String), "modules" .= refs]
      insert (key, value) (Object fields) = Object (KM.insert (Key.fromString key) value fields)
      insert _ _ = error "object expected"
      additions = extra ++ [ ("buildInputs", object ["path" .= ("inplace-manifest.json" :: String),
        "sha256" .= digest (encoded value)]) | Just value <- [inputs]]
      inner = foldr insert base additions
      members = ("manifest.json", encoded inner) : [(member, body) | (_, member, body) <- bodies] ++
        [("inplace-manifest.json", encoded value) | Just value <- [inputs]]
  bytes <- BL.toStrict <$> either fail pure (encodeZip members)
  BS.writeFile path bytes
  pure (Bundle path (digest bytes) refs "build" [])

unitRecord :: String -> Bundle -> Value
unitRecord owner bundle = object ["id" .= owner, "depends" .= ([] :: [String]), "modules" .= bundleModules bundle,
  "bundle" .= object ["path" .= bundlePath bundle, "sha256" .= bundleHash bundle]]

requireBundle :: Maybe Bundle -> IO Bundle
requireBundle = maybe (fail "expected validated bundle selection") pure

snapshot :: Bundle -> (FilePath, String, [Value], String, [(String, String, String)])
snapshot bundle = (bundlePath bundle, bundleHash bundle, bundleModules bundle, bundleBuildKey bundle, bundleReexports bundle)

-- Deliberately defeat the cheap observations: a warm default read must not
-- touch these corrupt bytes. Explicit verification must still reject them.
corruptUnobserved :: FilePath -> IO ()
corruptUnobserved path = do
  modified <- getModificationTime path
  bytes <- BS.readFile path
  BS.writeFile path (BS.replicate (BS.length bytes) 0)
  setModificationTime path modified

encoded :: Value -> BS.ByteString
encoded = BL.toStrict . encode

digest :: BS.ByteString -> String
digest = concatMap (\byte -> let value = showHex byte "" in replicate (2 - length value) '0' ++ value) . BS.unpack . SHA.hash
