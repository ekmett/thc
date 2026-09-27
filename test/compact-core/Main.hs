-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; native binary/HUnit controls
--
-- Manual primitive vectors, typed records and independent ZIP controls.
module Main (main) where

import Control.Monad (forM_)
import Data.Aeson (FromJSON(..), eitherDecodeStrict', withObject, (.:))
import Data.Binary.Put (runPut)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Char (digitToInt, isHexDigit, isSpace)
import Data.Either (isLeft)
import Data.Int (Int64)
import Data.Word (Word64)
import System.Exit (exitFailure)
import Test.HUnit
import THC.Compact.Wire
import SemanticTests (semanticTests)
import DebugTests (debugTests)
import CompressionTests (compressionTests)
import CbdTests (cbdTests)

data Vector = Vector String String String
instance FromJSON Vector where
  parseJSON = withObject "manual compact integer vector" $ \fields ->
    Vector <$> fields .: "encoding" <*> fields .: "value" <*> fields .: "hex"

main :: IO ()
main = do
  vectors <- BS.readFile "test/compact-core/golden/integers-v1.json" >>= either fail pure . eitherDecodeStrict'
  golden <- readFile "test/compact-core/golden/cbd-header-v1.hex" >>= either fail pure . hexBytes
  result <- runTestTT (TestList [tests vectors, cbdTests golden, semanticTests, debugTests, compressionTests])
  if errors result + failures result == 0 then pure () else exitFailure

tests :: [Vector] -> Test
tests vectors = TestList
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
      forM_ [[],[128],[128,0],[129,0],replicate 10 128,replicate 9 255 ++ [2],replicate 10 255 ++ [1],[0,0]] $ \bytes ->
        assertBool (show bytes) (isLeft (decodeExact getUVar (BS.pack bytes)))
  , TestLabel "relative spans preserve uint64 boundaries" $ TestCase $ do
      assertEqual "manual direct span" (BS.pack [128,1,255,1]) (putBytes (putSpan (Span 128 255)))
      assertEqual "roundtrip" (Right (Span 128 255)) (decodeExact getSpan (BS.pack [128,1,255,1]))
      assertEqual "empty end" (Right ()) (checkedSpan maxBound (Span maxBound 0))
      assertBool "overflow" (isLeft (checkedSpan maxBound (Span maxBound 1)))
      assertBool "outside" (isLeft (checkedSpan 3 (Span 4 0)))
  ]
  where putBytes = BL.toStrict . runPut

hexBytes :: String -> Either String BS.ByteString
hexBytes = fmap BS.pack . pairs . filter (not . isSpace)
  where
    pairs [] = Right []
    pairs (a:b:rest) | isHexDigit a && isHexDigit b =
      (fromIntegral (digitToInt a*16+digitToInt b) :) <$> pairs rest
    pairs _ = Left "Malformed manual hex vector"
