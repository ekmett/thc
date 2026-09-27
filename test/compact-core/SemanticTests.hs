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
import Data.Foldable (toList)
import Data.Aeson (Value(..), toJSON)
import qualified Data.Aeson.KeyMap as KM
import Data.Binary.Get (getByteString, getWord64le)
import Data.IORef
import Data.List (sort)
import qualified Data.Text.Encoding as Text
import qualified Data.Text as Text
import Data.Word (Word64)
import Numeric (readHex)
import System.FilePath ((</>))
import System.IO.Temp (withSystemTempDirectory)
import Test.HUnit hiding (Label)
import THC.Compact.Core
import THC.Compact.Decode
import THC.Compact.Encode
import THC.Compact.Facts
import THC.Compact.Module (writeModule)
import THC.Compact.JSON (parseModuleWithoutDebug)
import THC.Compact.Inspect (moduleJSON, inspectContainer)
import THC.CoreSymbols (symbolDigest)
import THC.Compact.Wire
import THC.Compact.Writer

semanticTests :: Test
semanticTests = TestList
  [ TestLabel "independent nested shared-shape wire golden" $ TestCase $ do
      tokens <- words <$> readFile "test/compact-core/golden/nested-shared-rep-v1.hex"
      golden <- BS.pack <$> mapM (\token -> case readHex token of
        [(value,"")] | value <= (255::Integer) -> pure (fromInteger value)
        _ -> fail "Invalid manual nested representation golden") tokens
      let leaf = scalar LongKind [IntRep]
          inner = Shape UnknownKind (Known [IntRep]) Missing (Known TupleAggregate)
            (Known [leaf]) Missing Missing Missing
          outer = Shape UnknownKind (Known [IntRep,IntRep]) Missing (Known TupleAggregate)
            (Known [inner,inner]) Missing Missing Missing
          state root left leftChild right rightChild = Evaluation (Known root)
            [Evaluation (Known left) [Evaluation (Known leftChild) []],
             Evaluation (Known right) [Evaluation (Known rightChild) []]]
          first = Rep outer (state False True False False True)
          second = Rep outer (state True False True True False)
      assertEqual "manually specified length" 62 (BS.length golden)
      assertEqual "independent first tree" (Right (first,50)) (decodeRepAt golden BS.empty 0)
      assertEqual "direct shared outer shape, different recursive states" (Right (second,62))
        (decodeRepAt golden BS.empty 50)
      withEncoded (\_ encoder -> encodeRep encoder first >> encodeRep encoder second) $ \bytes _ _ ->
        assertEqual "encoder matches independently specified bytes" golden bytes
  , TestLabel "all literal kinds retain exact semantic payloads" $ TestCase $
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
  , TestLabel "known-start facts need no executable bytes and share raw strings" $ TestCase $
      withSystemTempDirectory "compact-header" $ \directory -> do
        encoderSlot <- newIORef Nothing
        let destination = directory </> "header.thcc"
            prepare streams = do
              encoder <- newEncoder streams
              writeIORef encoderSlot (Just encoder)
              encodeFacts encoder completeFacts
            produce _ = do
              encoder <- readIORef encoderSlot >>= maybe (fail "Missing encoder") pure
              _ <- encodeBinding encoder completeBinding
              pure 0
        footer <- writeContainerPrepared destination prepare 0 produce
        file <- BS.readFile destination
        header <- either fail pure (decodeExact getHeader (BS.take 24 file))
        let factBytes = BS.take (fromIntegral (headerFactsLength header)) (BS.drop 24 file)
        case footerSegments footer of
          _ : Span stringStart stringLength : _ -> do
            let strings = BS.take (fromIntegral stringLength) (BS.drop (fromIntegral stringStart) file)
            assertEqual "header-only decode" (Right completeFacts) (decodeFacts factBytes strings)
            assertBool "header includes inline constructor shape" (not (BS.null factBytes))
          _ -> assertFailure "Missing strings"
  , TestLabel "unmapped present provenance cannot disappear during preparation" $ TestCase $
      withSystemTempDirectory "compact-header-rejection" $ \directory -> do
        failure <- try (writeContainerPrepared (directory </> "bad.thcc")
          (\streams -> newEncoder streams >>= \encoder -> encodeFacts encoder
            completeFacts {factsPendingProvenance=Known () : replicate 7 Missing}) 0 (const (pure 0)))
          :: IO (Either IOException Footer)
        assertBool "unmapped known record rejected" (isLeft failure)
  , TestLabel "module directory captures actual data-relative binding positions" $ TestCase $
      withSystemTempDirectory "compact-module" $ \directory -> do
        let values = [completeBinding, completeBinding {bindingIdentity=Global "main::Typed.main"},
              completeBinding {bindingIdentity=Global "main:Typed.control", bindingExpr=Prim emptyMeta "prompt#"}]
            destination = directory </> "module.thcc"
        footer <- writeModule destination completeFacts values
        assertEqual "all bindings indexed" 3 (footerBindingCount footer)
        assertEqual "actual control/registration/alias, no scalar declarations" 7 (footerSummaries footer)
        assertEqual "debug-free producer" 0 (footerDebugFlags footer)
        file <- BS.readFile destination
        case footerSegments footer of
          [dataSpan,stringSpan,_,_,_,indexSpan] -> do
            let slice (Span start size) = BS.take (fromIntegral size) (BS.drop (fromIntegral start) file)
                bytes = slice dataSpan
                strings = slice stringSpan
                rows = slice indexSpan
            assertEqual "fixed24 per binding" 72 (BS.length rows)
            decoded <- forM [0,24,48] $ \start ->
              either fail pure (decodeExact ((,) <$> getByteString 16 <*> getWord64le) (BS.take 24 (BS.drop start rows)))
            assertEqual "unsigned digest ordering" (sort (map fst decoded)) (map fst decoded)
            forM_ decoded $ \(digest,offset) -> do
              (value,_) <- either fail pure (decodeBindingAt bytes strings offset)
              assertBool "exact selected original binding" (value `elem` values)
              case bindingIdentity value of
                Global key -> assertEqual "canonical logical UTF8 MD5" digest =<< symbolDigest key
                Local _ -> assertFailure "Published local identity"
          _ -> assertFailure "Missing six segments"
        failure <- try (writeModule destination completeFacts [completeBinding,completeBinding]) :: IO (Either IOException Footer)
        assertBool "duplicate digest rejects publication" (isLeft failure)
        assertEqual "valid original container preserved" file =<< BS.readFile destination
        inspected <- either fail pure (inspectContainer file)
        assertEqual "explicit flat inspection preserves typed semantics" (Right (completeFacts,values))
          (parseModuleWithoutDebug inspected)
  , TestLabel "flat semantic JSON normalizes locals without losing fields or IEEE bits" $ TestCase $ do
      assertEqual "all typed record fields" (Right (completeFacts,[completeBinding]))
        (parseModuleWithoutDebug (moduleJSON completeFacts [completeBinding]))
      forM_ literals $ \lit -> do
        let value = completeBinding {bindingExpr=Lit emptyMeta lit}
        assertEqual (show lit) (Right (completeFacts,[value]))
          (parseModuleWithoutDebug (moduleJSON completeFacts [value]))
  , TestLabel "unknown semantic JSON and nonempty provenance fail explicitly" $ TestCase $ do
      let original = moduleJSON completeFacts [completeBinding]
          add key value = case original of Object fields -> Object (KM.insert key value fields); _ -> original
      assertBool "unknown operative field" (isLeft (parseModuleWithoutDebug (add "newSemanticFact" (Bool True))))
      assertBool "not-yet-typed provenance" (isLeft (parseModuleWithoutDebug (add "packageNativeLink" (Object KM.empty))))
      let badLiteral kind payload = case moduleJSON completeFacts [completeBinding] of
            Object fields -> case KM.lookup "bindings" fields of
              Just (Array bindings) -> Object (KM.insert "bindings" (toJSON (map (\value -> case value of
                Object b -> Object (KM.insert "expr" (toJSON [String "lit",String kind,String payload,Object KM.empty]) b)
                other -> other) (toList bindings))) fields)
              _ -> original
            _ -> original
      assertBool "negative Word does not wrap" (isLeft (parseModuleWithoutDebug (badLiteral "word" "-1")))
      assertBool "overflowing Int does not wrap" (isLeft (parseModuleWithoutDebug (badLiteral "int" "9223372036854775808")))
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

completeFacts :: Facts
completeFacts = Facts 2 "9.14.1" "main" "Typed" "optimized-Core-before-Tidy" (Known ["Typed"])
  (Known (TargetLayout 1 "ghc-9.14.1" "actual-abi" "actual-target" "vanilla"
    1 False 8 LittleEndian "actual-target" True [1..fromIntegral (length targetNumberNames)]))
  [Constructor "main:Typed.Box" 2 1 BoxedConstructor [True,False]
    [Known False,Known True] [Known [IntRep],Known [BoxedLifted]] [longRep,tupleCold]
    Missing (Known (EnumFamily "main:Typed.Box" ["main:Typed.Box"])) Unknown]
  (Known (ForeignArtifacts 1 "not-linked" (Known (Stubs "header" "source"
    [Label True "main" "Typed" "init"] [Label False "main" "Typed" "done"]))
    [ForeignFile "C" "foreign source" ".c"]))
  (Known (ExceptionBridge 1 "main" "Typed" "main:Typed.box" "main:Typed.project"
    "main:Typed.Payload" "ghc-internal:GHC.Internal.Exception.Type.SomeException"))
  (Known "main") [Missing,Unknown,Missing,Unknown,Missing,Missing,Missing,Missing]
