-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; native binary/HUnit golden controls
--
-- Shared manual wire vectors and local malformed-container boundaries.
module Main (main) where

import Control.Monad (forM_)
import Control.Exception (IOException, try)
import Data.Aeson (FromJSON(..), eitherDecodeStrict', withObject, (.:))
import Data.Binary.Put (Put, runPut)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Char (digitToInt, isHexDigit, isSpace)
import Data.Either (isLeft)
import Data.Int (Int64)
import Data.Word (Word64)
import System.Exit (exitFailure)
import System.Directory (listDirectory)
import System.FilePath ((</>))
import System.IO.Temp (withSystemTempDirectory)
import Test.HUnit hiding (path)
import THC.Compact.Wire
import THC.Compact.Writer

data Vector = Vector String String String
instance FromJSON Vector where
  parseJSON = withObject "manual compact integer vector" $ \fields ->
    Vector <$> fields .: "encoding" <*> fields .: "value" <*> fields .: "hex"

main :: IO ()
main = do
  vectors <- BS.readFile "test/compact-core/golden/integers-v1.json" >>= either fail pure . eitherDecodeStrict'
  headerGolden <- readHex "test/compact-core/golden/header-v1.hex"
  footerGolden <- readHex "test/compact-core/golden/footer-v1.hex"
  result <- runTestTT (tests vectors headerGolden footerGolden)
  if errors result + failures result == 0 then pure () else exitFailure

tests :: [Vector] -> BS.ByteString -> BS.ByteString -> Test
tests vectors headerGolden footerGolden = TestList
  [ TestLabel "manual canonical integer vectors" $ TestCase $
      forM_ vectors $ \(Vector encoding value expectedHex) -> do
        expected <- either fail pure (hexBytes expectedHex)
        case encoding of
          "uvar" -> do
            let number = read value :: Word64
            assertEqual value expected (putBytes (putUVar number))
            assertEqual value (Right number) (decodeExact getUVar expected)
          "svar" -> do
            let number = read value :: Int64
            assertEqual value expected (putBytes (putSVar number))
            assertEqual value (Right number) (decodeExact getSVar expected)
          _ -> assertFailure "Unknown golden vector encoding"
  , TestLabel "integer boundaries roundtrip" $ TestCase $ do
      forM_ ([0,1,126,127,128,129,255,256,65535,65536,maxBound] :: [Word64]) $ \value ->
        assertEqual (show value) (Right value) (decodeExact getUVar (putBytes (putUVar value)))
      forM_ ([minBound,minBound+1,-65536,-129,-128,-127,-1,0,1,127,128,65536,maxBound] :: [Int64]) $ \value ->
        assertEqual (show value) (Right value) (decodeExact getSVar (putBytes (putSVar value)))
  , TestLabel "malformed compact integers" $ TestCase $
      forM_ [[],[128],[128,0],[129,0],replicate 10 128,
             replicate 9 255 ++ [2],replicate 10 255 ++ [1],[0,0]] $ \bytes ->
        assertBool (show bytes) (isLeft (decodeExact getUVar (BS.pack bytes)))
  , TestLabel "relative span bytes and overflow boundaries" $ TestCase $ do
      assertEqual "direct span" (BS.pack [128,1,255,1]) (putBytes (putSpan (Span 128 255)))
      assertEqual "roundtrip" (Right (Span 128 255)) (decodeExact getSpan (BS.pack [128,1,255,1]))
      assertEqual "empty end" (Right ()) (checkedSpan maxBound (Span maxBound 0))
      assertBool "overflow" (isLeft (checkedSpan maxBound (Span maxBound 1)))
      assertBool "start outside" (isLeft (checkedSpan 3 (Span 4 0)))
  , TestLabel "manual fixed header and footer vectors" $ TestCase $ do
      assertEqual "header length" 24 (BS.length headerGolden)
      assertEqual "footer length" 128 (BS.length footerGolden)
      assertEqual "header golden" headerGolden (putBytes (putHeader header))
      assertEqual "footer golden" footerGolden (putBytes (putFooter footer))
      assertEqual "header decode" (Right header) (decodeExact getHeader headerGolden)
      assertEqual "footer decode" (Right footer) (decodeExact getFooter footerGolden)
      assertEqual "bounded extent admission" (Right ()) (validateContainer 181 header footer)
  , TestLabel "framing rejects malformed local bytes" $ TestCase $ do
      forM_ [BS.empty,BS.init headerGolden,replace 0 0 headerGolden,
             replace 8 2 headerGolden,replace 10 1 headerGolden,replace 12 1 headerGolden] $ \bad ->
        assertBool "header rejection" (isLeft (decodeExact getHeader bad))
      forM_ [BS.empty,BS.init footerGolden,replace 0 0 footerGolden,
             replace 112 16 footerGolden,replace 116 8 footerGolden,replace 120 1 footerGolden] $ \bad ->
        assertBool "footer rejection" (isLeft (decodeExact getFooter bad))
  , TestLabel "no scans needed for malformed directory extents" $ TestCase $ do
      assertBool "truncated file" (isLeft (validateContainer 100 header footer))
      assertBool "hidden tail" (isLeft (validateContainer 182 header footer))
      assertBool "count mismatch" (isLeft (validateContainer 181 header footer {footerBindingCount=2}))
      assertBool "debug mismatch" (isLeft (validateContainer 181 header footer {footerDebugFlags=1}))
      assertBool "unknown summary" (isLeft (validateContainer 181 header footer {footerSummaries=16}))
      assertBool "header extent overflow" (isLeft (validateContainer maxBound header {headerFactsLength=maxBound} footer))
      assertBool "overlap" (isLeft (validateContainer 181 header footer {footerSegments=Span 23 2 : drop 1 (footerSegments footer)}))
  , TestLabel "empty optional streams" $ TestCase $ do
      let empty = Footer (replicate 6 (Span 24 0)) 0 0 0
      assertEqual "empty container framing" (Right ()) (validateContainer 152 header empty)
      assertEqual "preserved zero-length spans" (Right empty) (decodeExact getFooter (putBytes (putFooter empty)))
  , TestLabel "forward assembly preserves relative offsets and all streams" $ TestCase $
      withSystemTempDirectory "compact-writer" $ \directory -> do
        let destination = directory </> "module.thcc"
            facts = "known typed facts"
            strings = BS.replicate 131073 65
            payloads = ["\1\2\3", strings, "names", "files", "lines", BS.replicate 24 0]
        result <- writeContainer destination facts 10 $ \streams -> do
          first <- appendBytes streams ExecutableData "\1"
          second <- appendBytes streams ExecutableData "\2\3"
          assertEqual "first data-relative offset" 0 first
          assertEqual "second data-relative offset" 1 second
          forM_ (zip [CommonStrings .. Fingerprints] (drop 1 payloads)) $ \(segment, bytes) -> do
            start <- appendBytes streams segment bytes
            assertEqual "auxiliary relative offset" 0 start
          pure 1
        actual <- BS.readFile destination
        let framedHeader = Header 1 0 (fromIntegral (BS.length facts))
            expected = putBytes (putHeader framedHeader) <> facts <> BS.concat payloads <> putBytes (putFooter result)
        assertEqual "forward-only byte assembly" expected actual
        assertEqual "all three independent debug flags" 7 (footerDebugFlags result)
        assertEqual "checked final extents" (Right ()) (validateContainer (fromIntegral (BS.length actual)) framedHeader result)
        assertEqual "only final container survives" ["module.thcc"] =<< listDirectory directory
  , TestLabel "successful publication replaces existing destination and cleans temporary streams" $ TestCase $
      withSystemTempDirectory "compact-writer-replacement" $ \directory -> do
        let destination = directory </> "module.thcc"
            facts = "replacement facts"
            payloads = ["replacement body", "new strings", BS.replicate 24 0]
        -- A longer old file detects any retained suffix as well as failure to
        -- replace. Its handle is closed before publication, as callers require.
        BS.writeFile destination (BS.replicate 4096 88)
        result <- writeContainer destination facts 0 $ \streams -> do
          forM_ (zip [ExecutableData, CommonStrings, Fingerprints] payloads) $ \(segment, bytes) -> do
            start <- appendBytes streams segment bytes
            assertEqual "replacement segment-relative offset" 0 start
          pure 1
        actual <- BS.readFile destination
        let framedHeader = Header 1 0 (fromIntegral (BS.length facts))
            expected = putBytes (putHeader framedHeader) <> facts <> BS.concat payloads <> putBytes (putFooter result)
        assertEqual "old file replaced by exact complete container" expected actual
        assertEqual "replacement extents" (Right ()) (validateContainer (fromIntegral (BS.length actual)) framedHeader result)
        assertEqual "replacement leaves no owned temporary streams" ["module.thcc"] =<< listDirectory directory
  , TestLabel "producer failure preserves destination and cleans all temporary streams" $ TestCase $
      withSystemTempDirectory "compact-writer-failure" $ \directory -> do
        let destination = directory </> "module.thcc"
        BS.writeFile destination "retained original"
        failure <- try (writeContainer destination "" 0 $ \streams -> do
          _ <- appendBytes streams ExecutableData "partial"
          _ <- appendBytes streams CommonStrings "partial strings"
          fail "controlled producer failure") :: IO (Either IOException Footer)
        assertBool "producer error propagated" (isLeft failure)
        assertEqual "old output intact" "retained original" =<< BS.readFile destination
        assertEqual "failed owned temps removed" ["module.thcc"] =<< listDirectory directory
        mismatch <- try (writeContainer destination "" 0 (const (pure 1))) :: IO (Either IOException Footer)
        assertBool "binding-directory mismatch rejected before publication" (isLeft mismatch)
        assertEqual "validation failure preserves output" "retained original" =<< BS.readFile destination
        assertEqual "validation failure cleans temps" ["module.thcc"] =<< listDirectory directory
  ]
  where
    header = Header 1 0 0
    footer = Footer [Span 24 2,Span 26 3,Span 29 0,Span 29 0,Span 29 0,Span 29 24] 1 10 0
    replace at value bytes = BS.take at bytes <> BS.singleton value <> BS.drop (at+1) bytes

putBytes :: Put -> BS.ByteString
putBytes = BL.toStrict . runPut

readHex :: FilePath -> IO BS.ByteString
readHex path = readFile path >>= either fail pure . hexBytes

hexBytes :: String -> Either String BS.ByteString
hexBytes = fmap BS.pack . pairs . filter (not . isSpace)
  where
    pairs [] = Right []
    pairs (a:b:rest) | isHexDigit a && isHexDigit b =
      (fromIntegral (digitToInt a*16+digitToInt b) :) <$> pairs rest
    pairs _ = Left "Malformed manual hex vector"
