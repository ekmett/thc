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
import Control.Monad (forM, forM_)
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
import THC.Compact.Module (encodeModuleValue, readModuleMetadata)
import THC.Driver.CoreSymbols (publishCoreUnit)
import THC.Driver.Project (Bundle(..), BundleReceipt(..), readGlobalBundle, readBundle,
  exceptionBridgeModules, projectWindowsWiredBundle, readCapturedStoreBundles,
  InstalledBundle(..), readCapturedInstalledBundles, installedRecords, componentCoreRecords)
import THC.Driver.Installed (InstalledUnit(InstalledUnit, registeredId, installedDepends, installedInterfaces))
import THC.Driver.Zip (encodeZip)
import THC.Driver.NativeDependencies (readNativeProduct)
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
  , TestCase $ scratch "captured installed" $ \directory -> do
      let path = directory </> "installed.zip"
          manifest = directory </> "captured.json"
          probe = directory </> "original-probe.json"
          compiler = object ["id" .= ("ghc-9.14.1" :: String), "abi" .= ("inplace" :: String),
            "platform" .= ("x86_64-linux" :: String), "way" .= ("dynamic-nonprofiling" :: String)]
          unit = InstalledUnit "registered-unit" "current registration" [] [("A","current/A.dyn_hi")] []
          provenance = object ["registeredUnit" .= registeredId unit, "compiler" .= compiler,
            "interfaces" .= [object ["module" .= ("A" :: String), "path" .= ("original/A.dyn_hi" :: String)]],
            "reexports" .= ([] :: [(String,String,String)]), "depends" .= ([] :: [String])]
          inputs = object ["format" .= ("thc-core-build-inputs" :: String), "schema" .= (1 :: Int),
            "unit" .= ("core-owner" :: String), "compiler" .= compiler,
            "component" .= object ["kind" .= ("installed-interface" :: String), "registration" .= provenance],
            "dependencies" .= ([] :: [String]), "buildKey" .= ("build" :: String), "exportKey" .= ("export" :: String)]
          withLayout (Object fields) = Object (KM.insert "targetLayout" layout fields)
          withLayout _ = error "object expected"
          load verify selected = readCapturedInstalledBundles verify compiler selected manifest
      bundle <- archive path "core-owner" [("A",core "core-owner" "A")]
        (Just (withLayout inputs)) [("targetLayout",layout)]
      let record = object ["identity" .= object ["registration" .= provenance],
            "inputs" .= inputs, "bundleSha256" .= bundleHash bundle]
          envelope = object ["record" .= record, "sha256" .= digest (encoded record)]
          row = object ["unit" .= registeredId unit, "path" .= path, "probe" .= probe]
          supplied rows = object ["format" .= ("thc-captured-store-bundles" :: String),
            "schema" .= (1 :: Int), "installed" .= (rows :: [Value])]
      BS.writeFile probe (encoded envelope)
      BS.writeFile manifest (encoded (supplied [row]))
      selected <- load False [unit]
      assertEqual "retained Core owner may differ from its registered alias"
        (Just ("core-owner", snapshot bundle))
        ((\(_,item) -> (installedOwner item,snapshot (installedBundle item))) <$> Map.lookup "registered-unit" selected)
      -- Two Main owners share one installed registration whose Core owner
      -- differs. Selection keeps the original real bundle, never the peer Main.
      let app name dependencies = object ["id" .= (name :: String),
            "depends" .= (dependencies :: [String]), "modules" .= ([] :: [Value])]
          firstApp = app "first:exe:same" [registeredId unit]
          secondApp = app "second:exe:same" [registeredId unit, "bridge"]
          bridge = app "bridge" [registeredId unit]
          suppliedRecords = installedRecords unit (InstalledBundle "core-owner" bundle) ++
            [firstApp, secondApp, bridge]
          owners = Map.singleton (registeredId unit) "core-owner"
          closure target = componentCoreRecords owners target suppliedRecords
      selectedFirst <- closure "first:exe:same"
      selectedSecond <- closure "second:exe:same"
      assertBool "first executable retains both registration and original Core bundle, excludes peer and its bridge"
        (all (`elem` selectedFirst) (installedRecords unit (InstalledBundle "core-owner" bundle)) &&
         firstApp `elem` selectedFirst && secondApp `notElem` selectedFirst && bridge `notElem` selectedFirst)
      assertBool "second executable keeps its own bridge and sharing without acquiring the other Main"
        (all (`elem` selectedSecond) (installedRecords unit (InstalledBundle "core-owner" bundle)) &&
         secondApp `elem` selectedSecond && bridge `elem` selectedSecond && firstApp `notElem` selectedSecond)
      assertBool "missing real owner is an error, not a silently incomplete manifest" . isLeft =<<
        tryIOError (componentCoreRecords (Map.singleton (registeredId unit) "missing-owner")
          "first:exe:same" suppliedRecords)
      assertBool "wrong compiler fails before reusing installed inputs" . isLeft =<< tryIOError
        (readCapturedInstalledBundles False (object []) [unit] manifest)
      assertBool "changed dependencies reject original receipt" . isLeft =<< tryIOError
        (load False [unit {installedDepends=["new-dependency"]}])
      assertBool "changed inventory rejects original receipt" . isLeft =<< tryIOError
        (load False [unit {installedInterfaces=[("B","current/B.dyn_hi")]}])
      BS.writeFile manifest (encoded (supplied [row,row]))
      assertBool "duplicate installed rows are rejected" . isLeft =<< tryIOError (load False [unit])
      BS.writeFile manifest (encoded (supplied []))
      assertBool "missing installed row cannot trigger acquisition" . isLeft =<< tryIOError (load False [unit])
      BS.writeFile manifest (encoded (supplied [row]))
      corruptUnobserved path
      _ <- load False [unit]
      assertBool "explicit verification still consumes and rejects corrupt payload" . isLeft =<< tryIOError (load True [unit])
      BS.writeFile probe (encoded (object ["record" .= record, "sha256" .= ("corrupt" :: String)]))
      assertBool "corrupt original probe is not a valid supplied selection" . isLeft =<< tryIOError (load False [unit])
  , TestCase $ scratch "setup library dependencies" $ \directory -> do
      let path = directory </> "registered.conf"
          unit = object ["id" .= ("empty-1-inplace" :: String),
            "components" .= object ["lib" .= object ["depends" .= (["base-4-inplace"] :: [String])]]]
      writeFile path (unlines ["name: empty", "version: 1", "id: empty-1-inplace",
        "key: empty-1-inplace", "depends: base-4-inplace", "exposed: True"])
      assertBool "resolved library dependencies do not require a flat Cabal plan row" . isNothing
        =<< readNativeProduct unit ["base-4-inplace"] path directory
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
      expected <- mapM (\(_, value) -> encodeModuleValue value >>= either fail (pure . snd) . readModuleMetadata)
        (take 2 values)
      assertEqual "only the two actual bridge modules are selected" expected first
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
  , TestCase $ forM_ [True, False] $ \wiredRecipe -> scratch "Windows projection" $ \directory -> do
      let path = directory </> "full.zip"
          name = "GHC.Internal.Conc.Bound"
          excluded = [object ["module" .= name]]
          specification = object ["archiveOnlyModules" .= excluded]
          registration = case core "ghc-internal" name of
            Object fields -> Object (KM.insert "foreign" (object ["schema" .= (1 :: Int),
              "execution" .= ("not-linked" :: String), "files" .= ([] :: [Value]), "stubs" .=
              object ["header" .= ("" :: String), "source" .= ("" :: String),
                "initializers" .= [object ["isInitializer" .= True, "unit" .= ("ghc-internal" :: String),
                  "module" .= name, "name" .= ("control-only-initializer" :: String)]],
                "finalizers" .= ([] :: [String])]]) fields)
            _ -> error "object expected"
          -- The ordinary installed recipe retains configured native inputs,
          -- not the former wired recipe's top-level generated-source list.
          inputs = object $ ["compiler" .= object [], "component" .= object ["generatedSources" .= ([] :: [String])]] ++
            ["generatedSources" .= ([] :: [Value]) | wiredRecipe]
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
    "arity" .= (0 :: Int), "expr" .= [String "lit", String "int", String "7", object []]]],
  "constructors" .= ([] :: [Value])]

boundary :: String
boundary = "optimized-Core-after-Tidy-before-CorePrep"

-- Closed reader control; no claim of a real compiler/native layout probe.
layout :: Value
layout = object $ ["schema" .= (1::Int), "profiled" .= False, "tablesNextToCode" .= True,
  "targetPlatform" .= ("x86_64-linux" :: String), "endianness" .= ("little" :: String), "wordBytes" .= (8::Int)] ++
  [Key.fromString name .= (0::Int) | name <-
    ["infoTableBytes","infoTablePtrsOffset","infoTablePtrsBytes","infoTableNptrsOffset","infoTableNptrsBytes",
     "infoTableTypeOffset","infoTableTypeBytes","infoTableSrtOffset","infoTableSrtBytes","infoProvEntBytes",
     "infoProvBytes","infoProvDescBytes","infoProvEntInfoOffset","infoProvEntProvOffset","infoProvNameOffset",
     "infoProvDescOffset","infoProvTyDescOffset","infoProvLabelOffset","infoProvUnitOffset","infoProvModuleOffset",
     "infoProvFileOffset","infoProvSpanOffset","stackHeaderBytes","stackCatchHandlerBytes","stackCatchFrameBytes",
     "stackUpdateeBytes","stackUpdateFrameBytes","stackAnnPayloadBytes","stackAnnFrameBytes","stackRetFunSizeBytes",
     "stackRetFunFunBytes","stackRetFunPayloadBytes","stackRetFunFrameBytes"]] ++
  [Key.fromString name .= (ordinal::Int) | (name,ordinal) <-
    [("closureRetBco",29),("closureRetSmall",30),("closureRetBig",31),("closureRetFun",32),
     ("closureStopFrame",36),("closureStack",53),("closureAnnFrame",65)]]

archive :: FilePath -> String -> [(String, Value)] -> Maybe Value -> [(String, Value)] -> IO Bundle
archive path owner values inputs extra = do
  bodies <- forM (zip [0 :: Int ..] values) $ \(index, (name, value)) -> do
    bytes <- encodeModuleValue value
    pure (name, "core/" ++ show index ++ ".cbd", bytes)
  let refs = [object ["name" .= name, "path" .= member, "sha256" .= digest body, "boundary" .= boundary]
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
