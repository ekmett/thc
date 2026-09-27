-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : CoreSymbolsTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; HUnit and host filesystem services
--
-- Exact-byte unit publication, metadata summaries, and cache repair controls.
module CoreSymbolsTests (tests) where

import Control.Monad (forM_)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (Value(..), eitherDecodeStrict', encode, object, toJSON, (.=))
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Either (isLeft)
import Data.List (sort)
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Numeric (showHex)
import System.Directory (getModificationTime)
import System.FilePath ((</>))
import Test.HUnit (Test(..), assertBool, assertEqual)
import THC.Driver.CoreSymbols (bindingPositions, publishCoreUnit)
import THC.CoreSymbols (symbolDigest, symbolFormat, encodeMd5Symbols)
import THC.Driver.Zip (encodeZip)
import TestSupport

tests :: Env -> Test
tests env = TestLabel "direct unit Core publication" $ TestList
  [ TestCase $ do
      forM_ [("main:Main.main", "201ac5924113112a846d82b090d8458a"),
             ("main:M.é😀", "23415231b60de428eeaf32979e1cb8ce")] $ \(key, expected) ->
        assertEqual "canonical MD5 of exact UTF-8 bytes (independent md5sum vectors)" expected . hex =<< symbolDigest (utf8 key)
      a <- symbolDigest "main:A.a"
      b <- symbolDigest "main:B.b"
      let input = [(b, 0x0102030405060708), (a, 0)]
      encodedRows <- either fail pure (encodeMd5Symbols input)
      let rows = chunks encodedRows
      assertEqual "fixed 24-byte rows with unsigned digest ordering" (sort [a,b]) (map (BS.take 16) rows)
      assertEqual "little-endian full-width offset" (sort input)
        [(BS.take 16 row, littleEndian (BS.drop 16 row)) | row <- rows]
      assertEqual "empty symbol inventory" (Right BS.empty) (encodeMd5Symbols [])
      assertBool "duplicate binding ID rejected" (isLeft (encodeMd5Symbols [(a,0),(a,1)]))
      assertBool "malformed digest width rejected" (isLeft (encodeMd5Symbols [("short",0)]))
  , TestCase $ do
      let bytes = utf8 "{\"note\":\"雪\", \"bindings\" : [ {\"expr\":[\"lit\",\"}\\\"[\"],\"id\":\"u:A.x\\u0020y\"} ],\"tail\":true}\r\n"
      (rows, (start, end)) <- either fail pure (bindingPositions bytes)
      assertEqual "decoded exact ID, not escaped source" ["u:A.x y"] (map fst rows)
      assertEqual "binding begins at object" [123] [BS.index bytes (fromIntegral offset) | (_, offset) <- rows]
      assertEqual "array span includes brackets" (91, 93)
        (BS.index bytes (fromIntegral start), BS.index bytes (fromIntegral end - 1))
      forM_ ["{}", "{\"bindings\":null}", "{\"bindings\":[{}]}",
             "{\"bindings\":[{\"id\":\"a\",\"id\":\"b\"}]}", "{\"bindings\":["] $ \bad ->
        assertBool "invalid or ambiguous binding inventory fails" (isLeft (bindingPositions bad))
  , TestCase $ withFixtureNamed env "test/fixtures/run-pure" "unit publication" $ \directory -> do
      let first = utf8 "{\"schema\":1,\"ghc\":\"9.14.1\",\"unit\":\"test-unit\",\"module\":\"A\",\"boundary\":\"post-tidy\",\"sourceFiles\":[],\"bindings\":[{\"id\":\"test-unit:A.雪\",\"expr\":[\"lit\",\"string\",\"prompt#\"]}],\"constructors\":[]}\r\n"
          secondBase = utf8 "{\"schema\":2,\"ghc\":\"9.14.1\",\"unit\":\"test-unit\",\"module\":\"B\",\"boundary\":\"post-tidy\",\"bindings\":[{\"id\":\"test-unit:B.a space\",\"expr\":[\"prim\",\"control0#\"]}],\"foreign\":{\"files\":[],\"stubs\":{\"initializers\":[\"synthetic-init\"],\"finalizers\":[]}}}"
          -- Closed publication controls, not executable foreign provenance.
          -- Re-encoding also exercises final bytes after package amendment.
          second = case eitherDecodeStrict' secondBase of
            Right value@(Object fields) -> encoded (Object $ KM.insert "staticForeignImports"
              (object ["imports" .= [object ["synthetic" .= True]]]) $ KM.insert "bindings"
              (toJSON (objects value "bindings" ++ [object ["id" .= ("main::B.main" :: String),
                "expr" .= (["lit", "int", "0"] :: [String])]])) fields)
            _ -> error "invalid test module"
          bodies = [first, second]
          ref name path body = object ["name" .= (name :: String), "path" .= (path :: String),
            "boundary" .= ("post-tidy" :: String), "sha256" .= digest body]
          refs = [ref "A" "core/a.json" first, ref "B" "core/b.json" second]
          layout = object ["wordSize" .= (8 :: Int)]
          compiler = object ["id" .= ("ghc-9.14.1" :: String)]
          inputs = encoded (object ["compiler" .= compiler, "targetLayout" .= layout])
          inner = object ["unit" .= ("test-unit" :: String), "modules" .= refs, "targetLayout" .= layout,
            "buildInputs" .= object ["path" .= ("inplace-manifest.json" :: String), "sha256" .= digest inputs]]
          source = directory </> "source.zip"
          cache = directory </> "cache"
      archive <- either fail pure (encodeZip [("manifest.json", encoded inner),
        ("inplace-manifest.json", inputs), ("core/a.json", first), ("core/b.json", second)])
      BL.writeFile source archive
      let unit = object ["id" .= ("test-unit" :: String), "modules" .= refs,
            "bundle" .= object ["path" .= source, "sha256" .= digest (BL.toStrict archive)]]
      published <- publishCoreUnit cache False unit
      assertEqual "legacy bundle replaced only in publication" Null (field published "bundle")
      let jsonPath = string (field (field published "json") "path")
          symbolsPath = string (field (field published "symbols") "path")
          records = array (field published "modules")
      actual <- BS.readFile jsonPath
      assertEqual "hash includes metadata projections" (digest actual) (string (field (field published "json") "sha256"))
      forM_ (zip records bodies) $ \(record, body) -> do
        let start = number (field record "start")
            end = number (field record "end")
        assertEqual "module span preserves final bytes" body (BS.take (end - start) (BS.drop start actual))
        let a = number (field record "metadataStart")
            b = number (field record "metadataEnd")
        metadata <- either fail pure (eitherDecodeStrict' (BS.take (b - a) (BS.drop a actual)))
        assertEqual "selected metadata retains module identity" (field record "name") (field metadata "module")
        forM_ ["bindings", "groups", "sourceCore", "sourceFiles", "sourceSpans"] $ \key ->
          assertEqual "non-admission fields absent from hot metadata" Null (field metadata key)
      assertEqual "literal spelling is not a control primop" [False, True]
        (map (bool . (`field` "containsDelimitedControl")) records)
      assertEqual "actual startup metadata" [False, True]
        (map (bool . (`field` "registrationObligations")) records)
      assertEqual "actual alias presence" [False, True] (map (bool . (`field` "mainAlias")) records)
      assertEqual "actual declaration provider" [False, True]
        (map (bool . (`field` "packageScalarDeclarations")) records)
      assertEqual "canonical original layout" layout (field (field published "targetLayout") "layout")
      assertEqual "canonical original compiler" compiler (field (field published "targetLayout") "compiler")
      assertEqual "explicit unit symbol format" symbolFormat (string (field (field published "symbols") "format"))
      rows <- chunks <$> BS.readFile symbolsPath
      assertEqual "three fixed-width bindings" 3 (length rows)
      assertBool "no variable-length names in directory" (all ((== 24) . BS.length) rows)
      assertEqual "unsigned digest order" (sort (map (BS.take 16) rows)) (map (BS.take 16) rows)
      expectedKeys <- mapM symbolDigest [utf8 "test-unit:A.雪", "test-unit:B.a space", "main::B.main"]
      assertEqual "exact logical UTF-8 IDs hashed, including spaces" (sort expectedKeys) (map (BS.take 16) rows)
      forM_ rows $ \row -> do
        let offset = fromIntegral (littleEndian (BS.drop 16 row))
        assertEqual "absolute offset targets object" 123 (BS.index actual offset)
      time <- getModificationTime jsonPath
      warm <- publishCoreUnit cache False unit
      assertEqual "warm identity" published warm
      assertEqual "warm leaves publication untouched" time =<< getModificationTime jsonPath
      BS.writeFile jsonPath ("!" <> BS.drop 1 actual)
      _ <- publishCoreUnit cache False unit
      assertEqual "default does not scan whole-file content" 33 . BS.head =<< BS.readFile jsonPath
      repaired <- publishCoreUnit cache True unit
      assertEqual "verified repair identity" published repaired
      assertEqual "verified corrupt-cache repair" actual =<< BS.readFile jsonPath
  ]
  where
    utf8 = Text.encodeUtf8 . Text.pack
    encoded = BL.toStrict . encode
    hex = concatMap (\byte -> let digits = showHex byte "" in if length digits == 1 then '0':digits else digits) . BS.unpack
    digest = hex . SHA.hash
    chunks bytes | BS.null bytes = []
                 | otherwise = BS.take 24 bytes : chunks (BS.drop 24 bytes)
    littleEndian = foldr (\byte rest -> fromIntegral byte + 256 * rest) 0 . BS.unpack
