-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
-- |
-- Module      : CbdTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; native ZIP interoperability
--
-- Manual CBD header, independent standard ZIP decoding, and atomic lifecycle.
module CbdTests (cbdTests) where

import qualified Codec.Archive.Zip as Zip
import Control.Exception (IOException, try)
import Control.Monad (foldM, forM_)
import Data.Aeson (eitherDecodeStrict')
import Data.Binary.Put (runPut)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Either (isLeft)
import System.Directory (listDirectory)
import System.FilePath ((</>))
import System.IO.Temp (withSystemTempDirectory)
import Test.HUnit
import THC.Compact.Compression
import THC.Compact.Inspect (unpackContainer, inspectContainer)
import THC.Compact.JSON (parseModuleWithDebug, parseModuleWithoutDebug)
import THC.Compact.Module (writeModuleWithDebugCompressed)
import THC.Compact.Wire
import THC.Compact.Writer
import THC.Compact.Zip

cbdTests :: BS.ByteString -> Test
cbdTests headerGolden = TestList
  [ TestLabel "manual CBD header" $ TestCase $ do
      assertEqual "32 bytes" 32 (BS.length headerGolden)
      assertEqual "independent specified bytes" headerGolden (putBytes (putHeader header))
      assertEqual "manual decode" (Right header) (decodeExact getHeader headerGolden)
      assertEqual "logical lengths" (Right ()) (validateContainer header [2,3,0,0,0,24])
  , TestLabel "header and member invariants" $ TestCase $ do
      forM_ [BS.empty,BS.init headerGolden,replace 0 0 headerGolden,
        replace 8 2 headerGolden,replace 10 0 headerGolden,replace 12 32 headerGolden,
        replace 24 8 headerGolden,replace 28 1 headerGolden] $ \bad ->
          assertBool "malformed header" (isLeft (decodeExact getHeader bad))
      assertBool "count mismatch" (isLeft (validateContainer header (replicate 6 0)))
      assertBool "debug mismatch" (isLeft (validateContainer header [0,0,1,0,0,24]))
      assertBool "overflow cannot wrap" (isLeft (validateContainer header {headerBindingCount=maxBound} [0,0,0,0,0,24]))
      assertEqual "empty optional members" (Right ()) (validateContainer (Header 1 1 0 0 0) (replicate 6 0))
  , TestLabel "stored deflated mixed ZIP retains bytes and replaces deterministically" $ TestCase $
      withSystemTempDirectory "cbd-methods" $ \directory -> do
        forM_ [[],["9"],["data=1","strings=9","header=6"]] $ \options -> do
          policy <- either fail pure (foldM (flip setCompression) defaultCompression options)
          let destination = directory </> "module.cbd"
              facts = "known typed facts"
              payloads = ["\1\2\3",BS.replicate 131073 65,"names","files","lines",BS.replicate 24 0]
              produce streams = do
                forM_ (zip [ExecutableData .. Fingerprints] payloads) $ \(segment,bytes) -> do
                  start <- appendBytes streams segment bytes
                  assertEqual "member-relative" 0 start
                pure (1,10)
          BS.writeFile destination (BS.replicate 262144 88)
          container <- writeContainerStreamedWith policy destination (const (pure facts)) produce
          bytes <- BS.readFile destination
          (actualHeader,actualFacts,actualPayloads) <- either fail pure (unpackContainer bytes)
          assertEqual "same metadata" (containerHeader container) actualHeader
          assertEqual "all debug members present" 7 (headerDebugFlags actualHeader)
          assertEqual "private empty metadata string pool and facts" (BS.replicate 8 0 <> facts) actualFacts
          assertEqual "unchanged payloads" payloads actualPayloads
          archive <- either fail pure (Zip.toArchiveOrFail (BL.fromStrict bytes))
          let entries = Zip.zEntries archive
          assertEqual "standard ZIP inventory"
            ["data","strings","names","filenames","line-columns","symbols","header"] (map Zip.eRelativePath entries)
          assertEqual "independent standard decompression"
            (payloads ++ [putBytes (putHeader actualHeader) <> BS.replicate 8 0 <> facts]) (map (BL.toStrict . Zip.fromEntry) entries)
          forM_ entries $ \entry -> do
            member <- maybe (fail "Unknown member") pure (lookup (Zip.eRelativePath entry)
              [(memberName member,member) | member <- [minBound..maxBound]])
            assertEqual "actual method follows policy"
              (if compressionLevel member policy == 0 then Zip.NoCompression else Zip.Deflate) (Zip.eCompressionMethod entry)
          _ <- writeContainerStreamedWith policy destination (const (pure facts)) produce
          assertEqual "deterministic successful replacement" bytes =<< BS.readFile destination
          assertEqual "owned temps cleaned" ["module.cbd"] =<< listDirectory directory
          assertBool "corrupt member rejected" (isLeft (readZip (replace 40 255 bytes)))
  , TestLabel "producer failure preserves destination and removes owned temps" $ TestCase $
      withSystemTempDirectory "cbd-failure" $ \directory -> do
        let destination = directory </> "module.cbd"
        BS.writeFile destination "retained original"
        failure <- try (writeContainer destination "" 0 $ \streams -> do
          _ <- appendBytes streams ExecutableData "partial"
          fail "controlled failure") :: IO (Either IOException Container)
        assertBool "producer error propagated" (isLeft failure)
        assertEqual "old output intact" "retained original" =<< BS.readFile destination
        mismatch <- try (writeContainer destination "" 0 (const (pure 1))) :: IO (Either IOException Container)
        assertBool "bad count rejected" (isLeft mismatch)
        assertEqual "validation failure preserves output" "retained original" =<< BS.readFile destination
        assertEqual "all temporary files cleaned" ["module.cbd"] =<< listDirectory directory
  , TestLabel "empty members remain present with either ZIP method" $ TestCase $
      withSystemTempDirectory "cbd-empty" $ \directory ->
        forM_ ["0","9"] $ \level -> do
          policy <- either fail pure (setCompression level defaultCompression)
          let destination = directory </> "empty.cbd"
          _ <- writeContainerStreamedWith policy destination (const (pure BS.empty)) (const (pure (0,0)))
          bytes <- BS.readFile destination
          assertEqual "six empty payload members" (Right (Header 1 1 0 0 0,BS.replicate 8 0,replicate 6 BS.empty))
            (unpackContainer bytes)
  , TestLabel "shared typed module archive controls preserve expected model" $ TestCase $
      withSystemTempDirectory "cbd-golden" $ \directory -> do
        original <- BS.readFile "t/compact-core/golden/cbd-module-v1.json" >>= either fail pure . eitherDecodeStrict'
        (facts,bindings,annotations) <- either fail pure (parseModuleWithDebug original)
        forM_ [("stored",[]),("deflated",["9"]),("mixed",["9","data=0","symbols=0"])] $ \(name,options) -> do
          policy <- either fail pure (foldM (flip setCompression) defaultCompression options)
          let destination = directory </> "golden.cbd"
          _ <- writeModuleWithDebugCompressed policy destination facts bindings annotations
          bytes <- BS.readFile destination
          actual <- either fail pure (inspectContainer bytes)
          assertEqual "independent JSON semantic model" (parseModuleWithoutDebug original) (parseModuleWithoutDebug actual)
          golden <- BS.readFile ("t/compact-core/golden/cbd-module-v1-" ++ name ++ ".cbd")
          assertEqual "shared fixture uncompressed members" (readZip bytes) (readZip golden)
  , TestLabel "ZIP64 field widths without giant allocation" $ TestCase $ do
      let local = putBytes (zipLocalHeader "data" 0 0 0xffffffff 0xffffffff)
          central = putBytes (zipCentralHeader "data" 0 0 0xffffffff 0xffffffff 0x100000000)
          ending = putBytes (zipEnd 7 0x100000000 123)
      assertEqual "local ZIP64 paired sizes" (BS.pack [1,0,16,0,255,255,255,255,0,0,0,0,255,255,255,255,0,0,0,0]) (BS.drop 34 local)
      assertEqual "central three u64 extras" 78 (BS.length central)
      assertEqual "ZIP64 end + locator + EOCD" 98 (BS.length ending)
      assertEqual "ZIP64 end signature" "PK\6\6" (BS.take 4 ending)
  ]
  where
    header = Header 1 1 10 1 0
    putBytes = BL.toStrict . runPut
    replace at value bytes = BS.take at bytes <> BS.singleton value <> BS.drop (at+1) bytes
