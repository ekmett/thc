-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : portable Haskell
--
-- Exact sharing/inspection controls and cross-language binary test vectors.
module Main (main) where

import Control.Monad (foldM, unless)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Builder as BB
import qualified Data.ByteString.Lazy as BL
import qualified Crypto.Hash.SHA256 as SHA256
import Data.Foldable (toList)
import Data.Int (Int64)
import qualified Data.Sequence as Seq
import Numeric (showHex)
import System.Directory (createDirectoryIfMissing)
import System.Environment (getArgs)
import System.Exit (exitFailure)
import System.FilePath ((</>))
import Test.HUnit
import THC.CoreStore.Binary
import THC.CoreStore.Inspect
import THC.CoreStore.Decode
import THC.CoreStore.Validate
import THC.CoreStore.Model
import THC.JSON (J(..),jsonBytes)

right :: Either String a -> IO a
right = either (ioError . userError) pure

rejected :: Either String a -> Assertion
rejected (Left _) = pure ()
rejected (Right _) = assertFailure "expected rejection"

main :: IO ()
main = getArgs >>= \arguments -> case arguments of
  ["--goldens",directory] -> do
    createDirectoryIfMissing True directory
    mapM_ (writeGolden directory) [("null",intern Null),("typed",typed), ("pages",pages)]
  ["--compare-export",legacyPath,inspectionPath] -> do
    legacy <- BS.readFile legacyPath
    inspected <- BS.readFile inspectionPath
    expected <- maybe (ioError (userError "unexpected original exporter envelope")) pure
      (BS.stripPrefix "{\"schema\":1,\"status\":\"loaded\",\"core\":" legacy >>= BS.stripSuffix "\n}\n")
    unless (expected == inspected) $ do
      let commonPrefix = length (takeWhile id (BS.zipWith (==) expected inspected))
      ioError (userError ("Core inspection differs at byte " ++ show commonPrefix ++
        "; lengths " ++ show (BS.length expected,BS.length inspected)))
    putStrLn ("Exact ordered Core bytes match: " ++ show (BS.length expected))
  ["--inspect-binary",binaryPath,inspectionPath] -> do
    encoded <- BS.readFile binaryPath
    store <- right (decodeStore Nothing encoded)
    right (inspectBytes Nothing store) >>= BS.writeFile inspectionPath
  ["--validate-binary",binaryPath] -> do
    encoded <- BS.readFile binaryPath
    store <- right (decodeStore Nothing encoded)
    checked <- right (validateStore store)
    putStrLn ("Validated shared records: " ++ show (validatedNodeCount checked))
  [] -> do
    outcome <- runTestTT tests
    unless (errors outcome == 0 && failures outcome == 0) exitFailure
  _ -> ioError (userError "usage: core-store-tests [--goldens DIRECTORY | --compare-export LEGACY INSPECTION]")

writeGolden :: FilePath -> (String,Build Ref) -> IO ()
writeGolden directory (name,action) = do
  store <- right (runBuild action)
  encoded <- right (encodeStore store)
  writeEncoded (directory </> name ++ ".thc-core") encoded
  BS.writeFile (directory </> name ++ ".json") =<< right (inspectBytes (Just 100000) store)
  writeFile (directory </> name ++ ".index.sha256")
    (concatMap hex (BS.unpack (encodedIndexSHA256 encoded)) ++ "\n")
  writeFile (directory </> name ++ ".records") (unlines
    (["root " ++ show (storeRoot store)] ++
     [show index ++ " " ++ show node | (index,node) <- zip [0::Int ..] (toList (storeNodes store))]))
  where hex byte = let value = showHex byte "" in replicate (2 - length value) '0' ++ value

tests :: Test
tests = TestList
  [ "ordered evidence roundtrip" ~: TestCase $ do
      let value = O [("empty",A [Z,B True,B False,A [],O [],S ""]),
            ("λ雪🙂",S "\NUL\t\n\"\\"),("numbers",A (map N
              [0,-1,toInteger (minBound::Int64),toInteger (maxBound::Int64),2^(200::Int),negate (2^(200::Int))])),
            ("duplicate",N 1),("duplicate",N 2)]
      store <- right (runBuild (internMetadata value))
      actual <- right (inspectBytes Nothing store)
      assertEqual "byte-exact ordered JSON" (jsonBytes value) actual
  , "layout identity excludes only outer evaluated occurrence" ~: TestCase $ do
      let representation flag = O [("primReps",A [S "IntRep"]),("kind",S "scalar"),("evaluated",B flag),
            ("nested",O [("primReps",A []),("kind",S "void"),("evaluated",B False)])]
      store <- right (runBuild (mapM (internMetadata . representation) [False,True] >>= internVector))
      case Seq.index (storeNodes store) (unRef (storeRoot store)) of
        Vector [left,rightRef] -> case (Seq.index (storeNodes store) (unRef left),Seq.index (storeNodes store) (unRef rightRef)) of
          (Representation layoutA (Unevaluated 2),Representation layoutB (Evaluated 2)) ->
            assertEqual "shared canonical layout" layoutA layoutB
          other -> assertFailure (show other)
        other -> assertFailure (show other)
      actual <- right (inspectBytes Nothing store)
      assertEqual "proofs remain distinct" (jsonBytes (A (map representation [False,True]))) actual
  , "structural sharing does not expand on encoding" ~: TestCase $ do
      store <- right (runBuild (intern (Integer 7) >>= \initial ->
        foldM (\previous _ -> internVector [previous,previous]) initial [1..80::Int]))
      assertEqual "unique records" 81 (Seq.length (storeNodes store))
      assertEqual "stored edges" 160 (sum (map (length . children) (toList (storeNodes store))))
      encoded <- right (encodeStore store)
      assertBool "small shared encoding" (encodedLength encoded < 4096)
      rejected (inspectBytes (Just 1000) store)
  , "backward structural and distinct logical references" ~: TestCase $ do
      rejected (runBuild (intern (Vector [Ref 0])))
      rejected (runBuild (newScope (Scope 1) >> intern Null))
      rejected (runBuild (intern (Variable (Scope 0) (Ref 0) (Local (Binder 0)))))
      store <- right (runBuild typed)
      assertBool "logical binders indexed" (Seq.length (storeBinders store) >= 3)
      _ <- right (inspectBytes (Just 10000) store)
      pure ()
  , "canonical LEB boundary bytes" ~: TestCase $ do
      let encode = BL.toStrict . BB.toLazyByteString
      assertEqual "ULEB127" [127] (BS.unpack (encode (unsignedLEB 127)))
      assertEqual "ULEB128" [128,1] (BS.unpack (encode (unsignedLEB 128)))
      assertEqual "SLEB-1" [127] (BS.unpack (encode (signedLEB (-1))))
      assertEqual "SLEB64" [192,0] (BS.unpack (encode (signedLEB 64)))
      assertEqual "SLEB-65" [191,127] (BS.unpack (encode (signedLEB (-65))))
  , "deterministic pages and bounded payload spans" ~: TestCase $ do
      store <- right (runBuild pages)
      first <- right (encodeStore store)
      second <- right (encodeStore store)
      assertEqual "deterministic complete bytes" (encodedBytes first) (encodedBytes second)
      assertBool "every block bounded" (all ((<=65536) . BS.length) (encodedBlocks first))
      assertBool "multiple node and payload pages" (length (encodedBlocks first) >= 6)
      decoded <- right (decodeStore (Just (encodedIndexSHA256 first)) (encodedBytes first))
      assertEqual "binary records and IDs roundtrip without expansion" store decoded
  , "typed binary wire roundtrip" ~: TestCase $ do
      store <- right (runBuild typed)
      encoded <- right (encodeStore store)
      decoded <- right (decodeStore (Just (encodedIndexSHA256 encoded)) (encodedBytes encoded))
      assertEqual "all typed fields" store decoded
  , "hash, truncation, UTF8 and backward references are checked" ~: TestCase $ do
      store <- right (runBuild (intern Null >>= \zero -> internVector [zero]))
      encoded <- right (encodeStore store)
      rejected (decodeStore (Just (BS.replicate 32 0)) (encodedBytes encoded))
      rejected (decodeStore Nothing (BS.init (encodedBytes encoded)))
      blockValue <- case encodedBlocks encoded of
        [value] -> pure value
        _ -> ioError (userError "small corruption control unexpectedly spans pages")
      let changed = BS.init blockValue <> BS.singleton 1
          index = encodedHeaderIndex encoded
          digestAt = BS.length index - 32
          resigned = BS.take digestAt index <> SHA256.hash changed <> changed
      rejected (decodeStore Nothing resigned)
      invalid <- right (runBuild (intern (String (BS.pack [0xc0,0x80])))) >>= right . encodeStore
      rejected (decodeStore Nothing (encodedBytes invalid))
  , "scope validation checks incoming edges and recursive RHS policy" ~: TestCase $ do
      recursive <- right (runBuild (scopedLet True))
      _ <- right (validateStore recursive)
      nonrecursive <- right (runBuild (scopedLet False))
      rejected (validateStore nonrecursive)
      -- The variable's own scope is valid, but the lambda body edge may not
      -- impersonate that unrelated lexical extent.
      sibling <- right (runBuild escapedLambda)
      rejected (validateStore sibling)
  , "full validation rejects unreachable duplicate metadata keys" ~: TestCase $ do
      store <- right (runBuild $ do
        one <- intern (Integer 1)
        _ <- internObject [("id",one),("id",one)]
        intern Null)
      rejected (validateStore store)
  ]

scopedLet :: Bool -> Build Ref
scopedLet recursive = do
  outerName <- internString "u:M.main"
  outerMeta <- internObject [("id",outerName)]
  outer <- newBinder (Scope 0) outerName outerMeta
  bodyScope <- newScope (Scope 0)
  name <- internString "u:M.loop_1"
  metadata <- internObject [("id",name)]
  identity <- newBinder bodyScope name metadata
  use <- intern (Variable bodyScope metadata (Local identity))
  definition <- intern (Definition identity use metadata 1)
  definitions <- internVector [definition]
  expression <- intern (Let (Scope 0) outerMeta recursive bodyScope definitions use)
  top <- intern (Definition outer expression outerMeta 1)
  addSymbol outerName top
  values <- internVector [top]
  internObject [("bindings",values)]

escapedLambda :: Build Ref
escapedLambda = do
  first <- newScope (Scope 0)
  second <- newScope (Scope 0)
  name <- internString "u:M.local_1"
  metadata <- internObject [("id",name)]
  identity <- newBinder second name metadata
  body <- intern (Variable second metadata (Local identity))
  empty <- intern (BinderVector [])
  intern (Lambda (Scope 0) metadata first empty body)

pages :: Build Ref
pages = do
  values <- mapM (intern . Integer) [0..599]
  huge <- internString (replicate 140000 'λ')
  edgeVector <- internVector values
  internVector [edgeVector,huge]

typed :: Build Ref
typed = do
  meta <- internObject []
  rootName <- internString "unit:Module.entry"
  rootBinder <- newBinder (Scope 0) rootName meta
  scope <- newScope (Scope 0)
  xName <- internString "unit:Module.x_1"
  x <- newBinder scope xName meta
  parameters <- intern (BinderVector [x])
  variable <- intern (Variable scope meta (Local x))
  globalName <- internString "ghc-internal:GHC.Internal.Base.id"
  global <- intern (Variable scope meta (Global globalName))
  primName <- internString "+#"
  primitive <- intern (Primitive scope meta primName)
  conName <- internString "ghc-prim:GHC.Types.I#"
  constructor <- intern (Constructor scope meta conName 1)
  kind <- internString "int"
  payload <- internString "42"
  literal <- intern (Literal scope meta kind payload)
  arguments <- internVector [variable,literal]
  lifted <- internMetadata (A [B False,B False])
  application <- intern (Application scope meta primitive arguments lifted False True)
  child <- newScope scope
  localName <- internString "unit:Module.local_2"
  local <- newBinder child localName meta
  fields <- internObject [("id",localName)]
  definition <- intern (Definition local application fields 1)
  definitions <- internVector [definition]
  localUse <- intern (Variable child meta (Local local))
  letExpression <- intern (Let scope meta False child definitions localUse)
  branch <- newScope scope
  caseName <- internString "unit:Module.case_3"
  caseBinder <- newBinder branch caseName meta
  alternativeScope <- newScope branch
  noBinders <- intern (BinderVector [])
  discriminator <- intern Null
  branchBody <- intern (Void alternativeScope meta)
  alternative <- intern (Alternative alternativeScope DefaultAlt discriminator noBinders branchBody meta)
  alternatives <- internVector [alternative]
  caseExpression <- intern (Case scope meta branch caseBinder variable alternatives)
  void <- intern (Void scope meta)
  reason <- internString "type-as-value"
  unsupported <- intern (Unsupported scope reason)
  -- Unused records are intentional: full-wire validation must check them too.
  _ <- internVector [global,constructor,letExpression,caseExpression,void,unsupported]
  lambda <- intern (Lambda (Scope 0) meta scope parameters application)
  rootFields <- internObject [("id",rootName)]
  rootDefinition <- intern (Definition rootBinder lambda rootFields 1)
  addSymbol rootName rootDefinition
  definitionsRoot <- internVector [rootDefinition]
  internObject [("bindings",definitionsRoot)]
