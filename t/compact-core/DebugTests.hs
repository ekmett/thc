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
import Control.Monad (forM, forM_, void)
import Data.Aeson (Value(..), object, toJSON, (.=))
import qualified Data.Aeson.KeyMap as KM
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
import THC.Compact.JSON (parseModuleWithDebug, parseModuleWithoutDebug)
import THC.Compact.Module (writeModuleWithDebug, encodeModuleValue, readModuleValue, rewriteModuleFacts)
import THC.Compact.Inspect (inspectContainer, unpackContainer)
import THC.Compact.Wire
import THC.Compact.Writer

debugTests :: Test
debugTests = TestList
  [ TestLabel "direct CBD publication and metadata amendment preserve executable/debug bytes" $ TestCase $ do
      original <- encodeModuleValue originalModule
      value <- either fail pure (readModuleValue original)
      let amended = case value of
            Object fields -> Object (KM.insert "foreignExceptionBridgeUnit" (String "runtime-unit") fields)
            _ -> error "Expected module"
      changed <- rewriteModuleFacts original amended
      (_,_,before) <- either fail pure (unpackContainer original)
      (_,_,after) <- either fail pure (unpackContainer changed)
      assertEqual "DATA, debug maps and fingerprint positions survive metadata linking"
        (take 1 before ++ drop 2 before) (take 1 after ++ drop 2 after)
      assertBool "original string offsets survive appended header strings" (before !! 1 `BS.isPrefixOf` (after !! 1))
      assertEqual "new header fact" (Just (String "runtime-unit"))
        (case readModuleValue changed of Right (Object fields) -> KM.lookup "foreignExceptionBridgeUnit" fields; _ -> Nothing)
      assertEqual "unchanged amendment retains exact container" original =<< rewriteModuleFacts original value
      assertBool "JSON is not an accepted module payload" (isLeft (readModuleValue "{\"bindings\":[]}"))
  , TestLabel "exact scoped debug names never use predecessor or common strings" $ TestCase $
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
        let destination = directory </> "empty.cbd"
        footer <- writeContainer destination BS.empty 0 $ \streams -> do
          encoder <- newEncoder streams
          debug <- newDebugEncoder streams (internString encoder)
          void (appendBytes streams ExecutableData "body")
          recordLocation debug 0 Nothing
          finishDebug debug 4
          pure 0
        assertEqual "truly absent optional tables" 0 (headerDebugFlags (containerHeader footer))
        original <- BS.readFile destination
        failure <- try (writeContainer destination BS.empty 0 $ \streams -> do
          encoder <- newEncoder streams
          debug <- newDebugEncoder streams (internString encoder)
          recordName debug 0 0 "first"
          recordName debug 0 0 "duplicate"
          pure 0) :: IO (Either IOException Container)
        assertBool "duplicate debug declaration rejected" (isLeft failure)
        assertEqual "original output preserved" original =<< BS.readFile destination
  , TestLabel "original annotations follow emitted records without changing semantics" $ TestCase $
      withSystemTempDirectory "compact-annotations" $ \directory -> do
        (facts,bindings,annotations) <- either fail pure (parseModuleWithDebug originalModule)
        let destination = directory </> "annotated.cbd"
        _ <- writeModuleWithDebug destination facts bindings annotations
        bytes <- BS.readFile destination
        inspected <- either fail pure (inspectContainer bytes)
        assertEqual "semantic records independent of optional debug" (parseModuleWithoutDebug originalModule)
          (parseModuleWithoutDebug inspected)
        (_,_,segments) <- either fail pure (unpackContainer bytes)
        case segments of
          [payload,strings,names,filenames,positions,_] -> do
            assertEqual "original top-level display name" (Right (Just "originalFunction")) (nameAt names 0 0)
            assertEqual "original parameter display name" (Right (Just "originalParameter")) (nameAt names 0 1)
            assertEqual "header constructor has a separate exact-key namespace"
              (Right (Just "OriginalBox")) (nameAt names maxBound 1)
            locations <- forM [0..fromIntegral (BS.length payload)-1] $ \offset ->
              either fail pure (locationAt filenames positions strings (fromIntegral (BS.length payload)) offset)
            assertBool "inherited and explicit notes coexist with selected primary"
              (any (\value -> case value of Just (SourceLocation 1 notes) -> length notes == 2; _ -> False) locations)
            assertBool "original top-level location survives nested restoration"
              (any (\value -> case value of Just (SourceLocation 0 [_]) -> True; _ -> False) locations)
            assertEqual "following unannotated binding does not inherit preceding source" Nothing (last locations)
          _ -> assertFailure "Missing annotation segments"
        case bindings of
          (value,notes):rest -> do
            failure <- try (writeModuleWithDebug destination facts ((value,drop 1 notes):rest) annotations)
              :: IO (Either IOException Container)
            assertBool "annotation mismatch cannot publish misleading origins" (isLeft failure)
            assertEqual "original container preserved after mismatch" bytes =<< BS.readFile destination
          _ -> assertFailure "Missing modeled bindings"
  ]

rowCount :: BS.ByteString -> Either String Integer
rowCount bytes = fromIntegral <$> decodeExact getWord64le (BS.drop (BS.length bytes-8) bytes)

withTables :: (BS.ByteString -> BS.ByteString -> BS.ByteString -> BS.ByteString -> Assertion) -> Assertion
withTables inspect = withSystemTempDirectory "compact-debug" $ \directory -> do
  let destination = directory </> "debug.cbd"
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
  assertEqual "all optional segments present" 7 (headerDebugFlags (containerHeader footer))
  bytes <- BS.readFile destination
  (_,_,segments) <- either fail pure (unpackContainer bytes)
  case segments of
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

originalModule :: Value
originalModule = object
  [ "schema" .= (2::Int), "ghc" .= ("9.14.1"::String), "unit" .= ("main"::String)
  , "module" .= ("Debug"::String), "boundary" .= ("optimized-Core-before-Tidy"::String)
  , "constructors" .= [object
      [ "id" .= ("main:Debug.Box"::String), "name" .= ("OriginalBox"::String)
      , "arity" .= (0::Int), "tag" .= (1::Int), "kind" .= ("boxed"::String)
      , "strictFields" .= ([]::[Bool]), "fieldLifted" .= ([]::[Bool])
      , "fieldReps" .= ([]::[Value]), "fieldTypes" .= ([]::[Value]) ]]
  , "bindings" .=
      [ object ["id" .= ("main:Debug.f"::String), "name" .= ("originalFunction"::String),
          "arity" .= (1::Int), "source" .= ("spanA"::String), "expr" .= toJSON
            [String "lam",toJSON [object ["id" .= ("local0"::String), "name" .= ("originalParameter"::String)]],
             toJSON [String "lit",String "int",String "42",object
               ["source" .= ("spanB"::String),"sourceNotes" .= (["spanB"]::[String])]],object []]]
      , object ["id" .= ("main:Debug.g"::String), "name" .= ("unannotated"::String),
          "arity" .= (0::Int), "expr" .= toJSON [String "void",object []]]
      ]
  , "sourceFiles" .= [object ["id" .= ("f0"::String), "path" .= ("Original.hs"::String), "content" .= Null]]
  , "sourceSpans" .= [spanRecord "spanA" 2,spanRecord "spanB" 4]
  ]
  where
    spanRecord :: String -> Int -> Value
    spanRecord identifier line = object ["id" .= identifier,"file" .= ("f0"::String),
      "startLine" .= line,"startColumn" .= (1::Int),"endLine" .= line,"endColumn" .= (5::Int)]
