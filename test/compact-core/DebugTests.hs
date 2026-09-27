-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : DebugTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; selected native debug-map controls
--
-- Exact name lookup and independently coalesced source intervals, including
-- explicit absence/restoration and malformed selected debug records.
module DebugTests (debugTests) where

import Control.Exception (IOException, try)
import Control.Monad (forM_, void)
import Data.Binary.Get (getWord64le)
import qualified Data.ByteString as BS
import Data.Either (isLeft)
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import System.FilePath ((</>))
import System.IO.Temp (withSystemTempDirectory)
import Test.HUnit
import THC.Compact.Core (Presence(..))
import THC.Compact.Debug
import THC.Compact.Encode (newEncoder, internString)
import THC.Compact.Wire
import THC.Compact.Writer

debugTests :: Test
debugTests = TestList
  [ TestLabel "exact scoped debug names never use predecessor or common strings" $ TestCase $
      withTables $ \_ names _ _ -> do
        assertEqual "UTF8 original name" (Right (Just unicodeName)) (nameAt names 0 0)
        assertEqual "local ordinal slot" (Right (Just "local")) (nameAt names 0 1)
        assertEqual "separate closure scope" (Right (Just "different")) (nameAt names 20 1)
        assertEqual "no predecessor fallback" (Right Nothing) (nameAt names 0 2)
        assertEqual "no closure fallback" (Right Nothing) (nameAt names 10 1)
  , TestLabel "source restoration and absence delimit independently coalesced tables" $ TestCase $
      withTables $ \strings _ filenames positions -> do
        forM_ [0..23] $ \offset -> do
          let expected | offset < 3 || offset >= 20 = Nothing
                       | offset >= 10 && offset < 12 = Just locationB
                       | otherwise = Just locationA
          assertEqual ("DATA " ++ show offset) (Right expected) (locationAt filenames positions strings 24 offset)
        assertEqual "no source past DATA" (Right Nothing) (locationAt filenames positions strings 24 24)
        assertEqual "filename component coalesces across coordinate changes" (Right 3) (rowCount filenames)
        assertEqual "line/column restoration remains distinct" (Right 5) (rowCount positions)
  , TestLabel "selected debug bounds and paired source counts reject locally" $ TestCase $
      withTables $ \strings names filenames positions -> do
        assertBool "truncated names directory" (isLeft (nameAt (BS.take 7 names) 0 0))
        assertBool "invalid selected name UTF8" (isLeft (nameAt (BS.cons 255 (BS.drop 1 names)) 0 0))
        assertEqual "unselected malformed name remains unread" (Right (Just "local"))
          (nameAt (BS.cons 255 (BS.drop 1 names)) 0 1)
        assertBool "filename spans require actual selected common bytes"
          (isLeft (locationAt filenames positions BS.empty 24 3))
        assertBool "one missing source component" (isLeft (locationAt filenames BS.empty strings 24 3))
        assertEqual "explicit no-source needs no string bytes" (Right Nothing)
          (locationAt filenames positions BS.empty 24 0)
  , TestLabel "optional absent debug and invalid publication preserve original" $ TestCase $
      withSystemTempDirectory "compact-debug-empty" $ \directory -> do
        let destination = directory </> "empty.thcc"
        footer <- writeContainer destination BS.empty 0 $ \streams -> do
          encoder <- newEncoder streams
          debug <- newDebugEncoder streams (internString encoder)
          void (appendBytes streams ExecutableData "body")
          recordLocation debug 0 Nothing
          finishDebug debug 4
          pure 0
        assertEqual "truly absent optional tables" 0 (footerDebugFlags footer)
        original <- BS.readFile destination
        failure <- try (writeContainer destination BS.empty 0 $ \streams -> do
          encoder <- newEncoder streams
          debug <- newDebugEncoder streams (internString encoder)
          recordName debug 0 0 "first"
          recordName debug 0 0 "duplicate"
          pure 0) :: IO (Either IOException Footer)
        assertBool "duplicate debug declaration rejected" (isLeft failure)
        assertEqual "original output preserved" original =<< BS.readFile destination
  ]

rowCount :: BS.ByteString -> Either String Integer
rowCount bytes = fromIntegral <$> decodeExact getWord64le (BS.drop (BS.length bytes-8) bytes)

withTables :: (BS.ByteString -> BS.ByteString -> BS.ByteString -> BS.ByteString -> Assertion) -> Assertion
withTables inspect = withSystemTempDirectory "compact-debug" $ \directory -> do
  let destination = directory </> "debug.thcc"
  footer <- writeContainer destination BS.empty 0 $ \streams -> do
    encoder <- newEncoder streams
    debug <- newDebugEncoder streams (internString encoder)
    void (appendBytes streams ExecutableData (BS.replicate 24 0))
    recordName debug 0 0 unicodeName
    recordName debug 0 1 "local"
    recordName debug 20 1 "different"
    recordLocation debug 3 (Just locationA)
    recordLocation debug 10 (Just locationB)
    recordLocation debug 12 (Just locationA)
    recordLocation debug 20 Nothing
    finishDebug debug 24
    pure 0
  assertEqual "all optional segments present" 7 (footerDebugFlags footer)
  bytes <- BS.readFile destination
  let slice (Span start size) = BS.take (fromIntegral size) (BS.drop (fromIntegral start) bytes)
  case map slice (footerSegments footer) of
    [_,strings,names,filenames,positions,_] -> inspect strings names filenames positions
    _ -> assertFailure "Missing compact debug segments"

unicodeName :: BS.ByteString
unicodeName = Text.encodeUtf8 (Text.pack "original é😀")

locationA, locationB :: SourceLocation
locationA = SourceLocation 0 [(file,positionA)]
locationB = SourceLocation 0 [(file,SourcePosition "spanB" Unknown 4 2 4 5 Unknown Missing)]

file :: SourceFile
file = SourceFile "f0" "MissingOriginal.hs" Unknown

positionA :: SourcePosition
positionA = SourcePosition "spanA" (Known "original label") 2 3 3 1 (Known 0) (Known 5)
