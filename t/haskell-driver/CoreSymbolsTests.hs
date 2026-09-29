-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- | Binary publication, authenticated summaries and cache repair controls.
module CoreSymbolsTests (tests) where

import Control.Exception (IOException, try)
import Control.Monad (forM_)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (Value(..), encode, object, toJSON, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Either (isLeft)
import Numeric (showHex)
import System.Directory (getModificationTime)
import System.FilePath ((</>))
import Test.HUnit (Test(..), assertBool, assertEqual)
import THC.Compact.Module (encodeModuleValue, readModuleMetadata)
import THC.Driver.CoreSymbols (publishCoreUnit)
import THC.Driver.Zip (encodeZip)
import TestSupport

tests :: Env -> Test
tests env = TestLabel "CBD-only unit publication" $ TestList
  [ TestCase $ withFixtureNamed env "t/fixtures/run-pure" "unit publication" $ \directory -> do
      let binding key body = object ["id" .= (key :: String),"arity" .= (0 :: Int),"expr" .= body]
          expression words' = toJSON (map String words' ++ [object []])
          moduleValue name bindings extra = object $
            ["schema" .= (2 :: Int),"ghc" .= ("9.14.1" :: String),"unit" .= ("test-unit" :: String),
             "module" .= (name :: String),"boundary" .= ("post-tidy" :: String),
             "bindings" .= bindings,"constructors" .= ([] :: [Value])] ++ extra
          first = moduleValue "A" [binding "test-unit:A.answer" (expression ["lit","int","42"])] []
          second = moduleValue "B" [binding "test-unit:B.control" (expression ["prim","control0#"]),
             binding "main::Main.main" (expression ["lit","int","0"])]
            ["foreign" .= object ["schema" .= (1 :: Int),"execution" .= ("not-linked" :: String),
              "files" .= ([] :: [Value]),"stubs" .= object ["header" .= ("" :: String),"source" .= ("" :: String),
              "initializers" .= [object ["isInitializer" .= True,"unit" .= ("test-unit" :: String),
                "module" .= ("B" :: String),"name" .= ("init" :: String)]],"finalizers" .= ([] :: [Value])]]]
      bodies <- mapM encodeModuleValue [first,second]
      let ref name path body = object ["name" .= (name :: String),"path" .= (path :: String),
            "boundary" .= ("post-tidy" :: String),"sha256" .= digest body]
          refs = zipWith3 ref ["A","B"] ["core/a.cbd","core/b.cbd"] bodies
          layout = object ["wordSize" .= (8 :: Int)]
          compiler = object ["id" .= ("ghc-9.14.1" :: String)]
          inputs = encoded (object ["compiler" .= compiler,"targetLayout" .= layout])
          inner = object ["unit" .= ("test-unit" :: String),"modules" .= refs,"targetLayout" .= layout,
            "buildInputs" .= object ["path" .= ("inplace-manifest.json" :: String),"sha256" .= digest inputs]]
          source = directory </> "source.zip"
          cache = directory </> "cache"
      archive <- either fail pure (encodeZip (("manifest.json",encoded inner) :
        ("inplace-manifest.json",inputs) : zip ["core/a.cbd","core/b.cbd"] bodies))
      BL.writeFile source archive
      let unit = object ["id" .= ("test-unit" :: String),"modules" .= refs,
            "bundle" .= object ["path" .= source,"sha256" .= digest (BL.toStrict archive)]]
      published <- publishCoreUnit cache False unit
      forM_ ["bundle","json","symbols"] $ \key ->
        assertEqual "no legacy executable payload" Null (field published key)
      let records = array (field published "modules")
      forM_ (zip records bodies) $ \(record,body) -> do
        let compact = field record "compact"
        actual <- BS.readFile (string (field compact "path"))
        assertEqual "exact final CBD retained" body actual
        assertEqual "container digest" (digest actual) (string (field compact "sha256"))
        assertEqual "container format" (String "thc-cbd-v1") (field compact "format")
        (_,metadata) <- either fail pure (readModuleMetadata actual)
        assertEqual "module identity" (field record "name") (field metadata "module")
        forM_ ["start","end","metadataStart","index"] $ \key ->
          assertEqual "no JSON positioning protocol" Null (field record key)
      assertEqual "actual control summaries" [False,True] (map (bool . (`field` "containsDelimitedControl")) records)
      assertEqual "actual startup metadata" [False,True] (map (bool . (`field` "registrationObligations")) records)
      assertEqual "fixed GHC main alias belongs to original module" [False,True] (map (bool . (`field` "mainAlias")) records)
      assertEqual "original target layout" layout (field (field published "targetLayout") "layout")
      let path = string (field (field (records !! 0) "compact") "path")
          original = bodies !! 0
      time <- getModificationTime path
      assertEqual "warm identity" published =<< publishCoreUnit cache False unit
      assertEqual "warm retains immutable file" time =<< getModificationTime path
      BS.writeFile path ("!" <> BS.drop 1 original)
      _ <- publishCoreUnit cache False unit
      damaged <- BS.readFile path
      assertEqual "default does not hash unselected bytes" 33 (BS.index damaged 0)
      assertEqual "verified repair identity" published =<< publishCoreUnit cache True unit
      assertEqual "verified repair restores CBD" original =<< BS.readFile path
  , TestCase $ withFixtureNamed env "t/fixtures/run-pure" "JSON rejection" $ \directory -> do
      let body = encoded (object ["schema" .= (1 :: Int),"bindings" .= ([] :: [Value])])
          ref = object ["name" .= ("A" :: String),"path" .= ("core/a.json" :: String),"sha256" .= digest body]
          inner = object ["unit" .= ("test-unit" :: String),"modules" .= [ref]]
          source = directory </> "legacy.zip"
      archive <- either fail pure (encodeZip [("manifest.json",encoded inner),("core/a.json",body)])
      BL.writeFile source archive
      let unit = object ["id" .= ("test-unit" :: String),"modules" .= [ref],
            "bundle" .= object ["path" .= source,"sha256" .= digest (BL.toStrict archive)]]
      result <- try (publishCoreUnit (directory </> "cache") False unit) :: IO (Either IOException Value)
      assertBool "JSON source cannot enter publication by fallback" (isLeft result)
  , TestCase $ withFixtureNamed env "t/fixtures/run-pure" "empty native-only unit" $ \directory -> do
      let unit = object ["id" .= ("native-only" :: String),"modules" .= ([] :: [Value]),
            "bundle" .= object ["path" .= ("not-an-executable-payload" :: String)]]
      result <- publishCoreUnit (directory </> "cache") False unit
      assertEqual "native-only units have no legacy execution route" Null (field result "bundle")
      assertEqual "native-only identity survives" (field unit "id") (field result "id")
  ]
  where
    encoded = BL.toStrict . encode
    digest = concatMap (\byte -> let digits = showHex byte "" in if length digits == 1 then '0':digits else digits) . BS.unpack . SHA.hash
