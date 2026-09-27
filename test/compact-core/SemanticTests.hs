-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : SemanticTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; native typed-codec controls
--
-- Typed round trips and malformed selected-record controls. Synthetic controls
-- are not GHC capture evidence or claims of compact runtime execution.
module SemanticTests (semanticTests) where

import Control.Exception (IOException, try)
import Control.Monad (forM, forM_)
import qualified Data.ByteString as BS
import Data.Either (isLeft)
import Data.IORef
import qualified Data.Text.Encoding as Text
import qualified Data.Text as Text
import Data.Word (Word64)
import System.FilePath ((</>))
import System.IO.Temp (withSystemTempDirectory)
import Test.HUnit
import THC.Compact.Core
import THC.Compact.Decode
import THC.Compact.Encode
import THC.Compact.Wire
import THC.Compact.Writer

semanticTests :: Test
semanticTests = TestList
  [ TestLabel "all literal kinds retain exact semantic payloads" $ TestCase $
      withEncoded (\streams encoder -> forM literals $ \value -> do
        offset <- streamOffset streams ExecutableData
        encodeExpr encoder (Lit emptyMeta value)
        pure (offset,value)) $ \bytes strings records ->
          forM_ records $ \(offset,value) -> assertEqual (show value)
            (Right (Lit emptyMeta value)) (fst <$> decodeExprAt bytes strings offset)
  , TestLabel "nine expression tags and calling facts roundtrip" $ TestCase $
      withEncoded (\_ encoder -> encodeBinding encoder completeBinding) $ \bytes strings offset ->
        assertEqual "all fields" (Right (completeBinding,fromIntegral (BS.length bytes)))
          (decodeBindingAt bytes strings offset)
  , TestLabel "shape sharing never shares occurrence evaluatedness" $ TestCase $
      withEncoded (\streams encoder -> do
        encodeRep encoder tupleCold
        second <- streamOffset streams ExecutableData
        encodeRep encoder tupleHot
        pure second) $ \bytes strings second -> do
          assertEqual "same exact shape reuses a direct offset" 1 (BS.index bytes (fromIntegral second))
          assertEqual "cold parent and lifted child" (Right tupleCold) (fst <$> decodeRepAt bytes strings 0)
          assertEqual "independent hot parent and child" (Right tupleHot) (fst <$> decodeRepAt bytes strings second)
  , TestLabel "unknown vs absent vs empty physical and logical layouts" $ TestCase $
      withEncoded (\streams encoder -> forM representations $ \value -> do
        offset <- streamOffset streams ExecutableData
        encodeRep encoder value
        pure (offset,value)) $ \bytes strings records ->
          forM_ records $ \(offset,value) -> assertEqual (show value) (Right value)
            (fst <$> decodeRepAt bytes strings offset)
  , TestLabel "UTF8 semantic strings intern once and selected offsets remain direct" $ TestCase $
      withEncoded (\streams encoder -> do
        let name = Text.encodeUtf8 (Text.pack "main:M.é😀")
        encodeExpr encoder (Var emptyMeta (Global name))
        offset <- streamOffset streams ExecutableData
        encodeExpr encoder (Con emptyMeta name 0)
        pure (name,offset)) $ \bytes strings (name,offset) -> do
          assertEqual "one raw string, no ID table" name strings
          assertEqual "selected second node" (Right (Con emptyMeta name 0))
            (fst <$> decodeExprAt bytes strings offset)
  , TestLabel "malformed selected tags and shape cycles reject locally" $ TestCase $ do
      assertBool "unknown expression" (isLeft (decodeExprAt (BS.pack [255]) BS.empty 0))
      assertBool "self shape reference" (isLeft (decodeRepAt (BS.pack [1,0]) BS.empty 0))
      assertBool "forward shape reference" (isLeft (decodeRepAt (BS.pack [1,2,0]) BS.empty 0))
      assertBool "unknown kind" (isLeft (decodeRepAt (BS.pack [0,255]) BS.empty 0))
      let invalidLiteral = BS.pack (2 : replicate 10 0 ++ [6,128,2])
      assertBool "Word8 value256" (isLeft (decodeExprAt invalidLiteral BS.empty 0))
      let noncanonicalBigNat = BS.pack (2 : replicate 10 0 ++ [10,1,0])
      assertBool "BigNat trailing high zero" (isLeft (decodeExprAt noncanonicalBigNat BS.empty 0))
  , TestLabel "encoder rejects invalid typed states without publishing" $ TestCase $
      forM_ invalidValues $ \value -> do
        failure <- try (withEncoded (\_ encoder -> encodeExpr encoder value) (\_ _ _ -> pure ())) :: IO (Either IOException ())
        assertBool (show value) (isLeft failure)
  , TestLabel "selected record truncation and string bounds reject" $ TestCase $
      withEncoded (\_ encoder -> encodeExpr encoder (Prim emptyMeta "addInt#")) $ \bytes strings _ -> do
        forM_ [0 .. BS.length bytes-1] $ \size ->
          assertBool ("truncated at " ++ show size) (isLeft (decodeExprAt (BS.take size bytes) strings 0))
        assertBool "no full-string fallback" (isLeft (decodeExprAt bytes BS.empty 0))
        assertBool "invalid selected UTF8" (isLeft (decodeExprAt bytes (BS.replicate 7 255) 0))
  ]

withEncoded :: (Streams -> Encoder -> IO a) -> (BS.ByteString -> BS.ByteString -> a -> Assertion) -> Assertion
withEncoded produce inspect = withSystemTempDirectory "compact-typed" $ \directory -> do
  result <- newIORef Nothing
  let destination = directory </> "control.thcc"
  footer <- writeContainer destination BS.empty 0 $ \streams -> do
    encoder <- newEncoder streams
    value <- produce streams encoder
    writeIORef result (Just value)
    pure 0
  bytes <- BS.readFile destination
  let slice (Span start size) = BS.take (fromIntegral size) (BS.drop (fromIntegral start) bytes)
  case footerSegments footer of
    dataSpan : stringSpan : _ -> do
      value <- readIORef result >>= maybe (fail "Missing typed control result") pure
      inspect (slice dataSpan) (slice stringSpan) value
    _ -> assertFailure "Missing compact segments"

literals :: [Literal]
literals =
  [ LitInt minBound, LitInt maxBound, LitWord maxBound
  , LitInt8 (-128), LitInt8 127, LitInt16 (-32768), LitInt32 (-2147483648), LitInt64 minBound
  , LitWord8 255, LitWord16 65535, LitWord32 4294967295, LitWord64 maxBound
  , LitBigNat 0, LitBigNat (2^(257::Int)+257), LitChar 0x10ffff
  , LitBytes (BS.pack [0,255,128,13,10]), LitBytes BS.empty
  , LitFloatBits 0x80000000, LitFloatBits 0x7fc00017
  , LitDoubleBits 0x8000000000000000, LitDoubleBits 0x7ff8000000000017
  , LitNullAddr, LitRubbish BoxedUnlifted, LitFunctionAddr "foreign_fn", LitDataAddr "foreign_data"
  ]

scalar :: Kind -> [PrimRep] -> Shape
scalar kind reps = Shape kind (Known reps) Missing Missing Missing Missing Missing Missing

longRep :: Rep
longRep = Rep (scalar LongKind [IntRep]) (Evaluation (Known True) [])

objectShape :: Shape
objectShape = scalar ObjectKind [BoxedLifted]

tupleShape :: Shape
tupleShape = Shape UnknownKind (Known [IntRep,BoxedLifted]) Missing (Known TupleAggregate)
  (Known [scalar LongKind [IntRep],objectShape]) Missing Missing Missing

tupleCold, tupleHot :: Rep
tupleCold = Rep tupleShape (Evaluation (Known False) [Evaluation (Known True) [], Evaluation (Known False) []])
tupleHot = Rep tupleShape (Evaluation (Known True) [Evaluation (Known True) [], Evaluation (Known True) []])

representations :: [Rep]
representations =
  [ Rep (scalar VoidKind []) (Evaluation (Known True) [])
  , Rep (Shape UnknownKind (Known []) Missing (Known TupleAggregate) (Known []) Missing Missing Missing) (Evaluation (Known True) [])
  , Rep (Shape UnknownKind Unknown Missing (Known SumAggregate) Missing Unknown (Known 0) Unknown) (Evaluation Unknown [])
  , Rep (Shape UnknownKind Missing Missing Missing Missing Missing Missing Missing) (Evaluation Missing [])
  , Rep (Shape VectorKind (Known [VecRep (Vector 16 Word8Element)]) (Known (Vector 16 Word8Element)) Missing Missing Missing Missing Missing) (Evaluation (Known True) [])
  , Rep (Shape UnknownKind (Known [IntRep,Word64Rep]) Missing (Known SumAggregate) Missing
      (Known [scalar VoidKind [],scalar LongKind [Word64Rep]]) (Known 0) (Known [[],[1]]))
      (Evaluation (Known True) [Evaluation (Known True) [],Evaluation (Known False) []])
  ]

parameter :: Word64 -> Binder
parameter ordinal = Binder ordinal OtherEntry (Known False) (Known False) (Known longRep)
  (Known (IdInfo Unknown (Known False) (Known [])))

completeBinding :: Binding
completeBinding = Binding (Global "main:Typed.all") IOUnit (Known True) 1 (Known tupleCold)
  (Known (IdInfo (Known 0) (Known True) (Known [True]))) (Known [True]) (Known "ghc-cbv")
  (Known 0) (Known longRep) body
  where
    local = Binding (Local 1) OtherEntry (Known True) 0 Unknown Missing (Known []) Missing
      Missing Missing (Lit emptyMeta (LitInt 41))
    info = emptyMeta
      { metaRep = Known longRep, metaResultRep = Known tupleHot
      , metaEntryStrict = Known [True], metaEntryStrictSource = Known "ghc-cbv"
      , metaCallDemand = Known (CallDemand 1 [True])
      , metaForeignCall = Known (ForeignCall 1 (StaticTarget "original_fn" (Known "real-unit") True)
          CApi SafeCall 1 1 [longRep] longRep Unknown (Known "return $1"))
      , metaExceptionPayload = Known (ExceptionPayload 1 "ghc-internal:GHC.Internal.Exception.Type.SomeException")
      , metaEnumFamily = Known (EnumFamily "main:T" ["main:T.A","main:T.B"])
      , metaTagFamily = Known (TagFamily (EnumFamily "main:T" ["main:T.A"]) 7 True)
      , metaUnsafeEqualityCase = Known "GHC.Core.Utils.isUnsafeEqualityCase/CoreToStg"
      }
    body = Lam emptyMeta [parameter 0] (Let emptyMeta True [local]
      (Case info (App info (Prim emptyMeta "addInt#")
        [Var emptyMeta (Local 0),Var emptyMeta (Local 1)] [Known False,Unknown] False True)
        2 (Known (parameter 2))
        [ DefaultAlt [] (Void emptyMeta)
        , DataAlt "main:T.A" [parameter 3] (Con emptyMeta "main:T.B" 0)
        , LiteralAlt (LitInt 42) [] (Lit emptyMeta (LitBytes "answer"))]))

invalidValues :: [Expr]
invalidValues =
  [ Lit emptyMeta (LitInt8 128), Lit emptyMeta (LitWord8 256)
  , Lit emptyMeta (LitBigNat (-1)), Lit emptyMeta (LitChar 0x110000)
  , Var emptyMeta (Global (BS.pack [255]))
  , Void emptyMeta {metaRep=Known (Rep tupleShape (Evaluation (Known True) []))}
  , App emptyMeta (Prim emptyMeta "id") [] [Missing] False False
  ]
