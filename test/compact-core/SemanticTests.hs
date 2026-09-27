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
            completeFacts {factsPendingProvenance=Known (ImportsRecord completeImports) : replicate 7 Missing}) 0 (const (pure 0)))
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
  , TestLabel "retained typed import and export provenance needs no DATA scan" $ TestCase $
      withSystemTempDirectory "compact-provenance" $ \directory -> do
        let facts = completeFacts {factsPendingProvenance =
              [Missing,Known (ImportsRecord completeImports),Known (ImportsRecord completeImports),
               Known (ExportsRecord completeExports),Known (RegistrationRecord completeRegistration),Missing,Unknown,Missing]}
        assertEqual "exact nominal types and inventory multiplicity survive flat conversion" (Right (facts,[]))
          (parseModuleWithoutDebug (moduleJSON facts []))
        let destination = directory </> "provenance.thcc"
        footer <- writeModule destination facts []
        assertEqual "actual registration and declaration provider, no bindings" 10 (footerSummaries footer)
        bytes <- BS.readFile destination
        header <- either fail pure (decodeExact getHeader (BS.take 24 bytes))
        let slice (Span start size) = BS.take (fromIntegral size) (BS.drop (fromIntegral start) bytes)
        case map slice (footerSegments footer) of
          payload:strings:_ -> do
            assertEqual "header provenance has no executable shape references" BS.empty payload
            assertEqual "all scoped types, safety and original expected calls preserved"
              (Right facts) (decodeFacts (BS.take (fromIntegral (headerFactsLength header)) (BS.drop 24 bytes)) strings)
          _ -> assertFailure "Missing provenance container segments"
  , TestLabel "unclassified and rejected provenance remain non-verified records" $ TestCase $
      forM_ [(ImportsUnclassified "unknown original declaration",RegistrationUnclassified "unknown original product"),
             (ImportsRejected "rejected original declaration",RegistrationRejected "rejected original product")] $ \(imports,registration) -> do
        let proof = ImportProof 1 "retained-static-import-products" "not-linked" "original-profile" "main" "Typed" imports
            registered = Registration 2 "retained-foreign-products" "not-linked" "original-profile" registration
            facts = completeFacts {factsPendingProvenance =
              [Missing,Missing,Known (ImportsRecord proof),Missing,Known (RegistrationRecord registered),Missing,Missing,Missing]}
        assertEqual "status/reason survive JSON conversion without invented evidence" (Right (facts,[]))
          (parseModuleWithoutDebug (moduleJSON facts []))
        withEncoded (\_ encoder -> encodeFacts encoder facts) $ \_ strings bytes ->
          assertEqual "status/reason survive typed bytes" (Right facts) (decodeFacts bytes strings)
  , TestLabel "native link and partial archive records preserve exact typed provenance" $ TestCase $ do
      let facts = completeFacts {factsPendingProvenance =
            [Known (ForeignLinkRecord completeForeignLink),Missing,Missing,Missing,Missing,
             Known (ScalarLinkRecord completeScalarLink),Known (NativeLinkRecord completeNativeLink),
             Known (NativeArchiveRecord completeNativeArchive)]}
      assertEqual "all original link inputs and rejected closure evidence survive JSON inspection"
        (Right (facts,[])) (parseModuleWithoutDebug (moduleJSON facts []))
      withEncoded (\_ encoder -> encodeFacts encoder facts) $ \payload strings bytes -> do
        assertEqual "metadata-only link records have no executable body" BS.empty payload
        assertEqual "native blob and every nested original recipe survive typed bytes" (Right facts) (decodeFacts bytes strings)
        forM_ [0,BS.length bytes-1] $ \size ->
          assertBool "truncated native record rejects" (isLeft (decodeFacts (BS.take size bytes) strings))
  , TestLabel "native artifact conversion rejects malformed hex and unknown nested facts" $ TestCase $ do
      let original = moduleJSON completeFacts {factsPendingProvenance =
            [Missing,Missing,Missing,Missing,Missing,Known (ScalarLinkRecord completeScalarLink),Missing,Missing]} []
          amend change = case original of
            Object fields | Just (Object link) <- KM.lookup "packageScalarLink" fields ->
              Object (KM.insert "packageScalarLink" (Object (change link)) fields)
            _ -> original
      forM_ ["0","gg","AF"] $ \bad ->
        assertBool "noncanonical original bitcode hex rejected" (isLeft
          (parseModuleWithoutDebug (amend (KM.insert "bitcodeHex" (String bad)))))
      assertBool "unknown link fact never disappears" (isLeft
        (parseModuleWithoutDebug (amend (KM.insert "inventedProof" (Bool True)))))
  , TestLabel "unknown semantic JSON and malformed provenance fail explicitly" $ TestCase $ do
      let original = moduleJSON completeFacts [completeBinding]
          add key value = case original of Object fields -> Object (KM.insert key value fields); _ -> original
      assertBool "unknown operative field" (isLeft (parseModuleWithoutDebug (add "newSemanticFact" (Bool True))))
      assertBool "missing required native provenance fields" (isLeft (parseModuleWithoutDebug (add "packageNativeLink" (Object KM.empty))))
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

completeImports :: ImportProof
completeImports = ImportProof 1 "retained-static-import-products" "not-linked"
  "ghc-9.14.1-thc-only-static-c-imports-v1" "main" "Typed" (ImportsVerified 64 originalProducts
    [ImportAssociation qualified Unknown "original_fn" (Known "main") True CApi InterruptibleCall
      nominal (ForeignApplication nominal (ForeignVariable 0)) "representational"
      (EmittedCall "original_fn" (Known "main") CApi InterruptibleCall ["IntRep","void"] ["void","IntRep"])]
    [expected,expected])
  where
    expected = ForeignCall 1 (StaticTarget "original_fn" (Known "main") True) CApi InterruptibleCall
      2 2 [longRep,tupleCold] tupleHot Unknown Missing

qualified :: QualifiedName
qualified = QualifiedName "main" "Typed" "original" "value"

nominal :: ForeignType
nominal = ForeignForall (ForeignTyCon (QualifiedName "ghc-prim" "GHC.Types" "TYPE" "type") [])
  (ForeignArrow (ForeignTyCon (QualifiedName "ghc-prim" "GHC.Types" "Many" "data") [])
    (ForeignVariable 0) (ForeignTyCon qualified [ForeignVariable 0]))

originalProducts :: ForeignArtifacts
originalProducts = ForeignArtifacts 1 "not-linked" Unknown []

completeExports :: Exports
completeExports = Exports 1 "THC.Plugin/typeCheckResultAction" "static-export-associations" "not-linked" "main" "Typed"
  [ExportAssociation qualified "thc_original" CCall nominal nominal "representational" [nominal] nominal IOExport]

completeRegistration :: Registration
completeRegistration = Registration 2 "retained-foreign-products" "not-linked"
  "ghc-9.14.1-thc-only-native-static-ccall-imports-v2" (RegistrationVerified [qualified] 64 originalProducts completeExports)

completeForeignLink :: ForeignLink
completeForeignLink = ForeignLink 3 "llvm-bitcode" "main" "Typed" "source-hash" "bitcode-hash"
  (BS.pack [0,255,66,67,192]) "actual-target" ["clock","errno"] [("clock","time-clock-time"),("errno","errno")]
  (Known [("HsTime.h","selected-header-hash")])

completeLinkPayload :: LinkPayload
completeLinkPayload = LinkPayload 1 "llvm-bitcode" "thc-package-c-ffi-v1" "main" "actual-target"
  "actual-component-hash" "actual-bitcode-hash" (BS.pack [66,67,192,222,0,255])

completeScalarLink :: ScalarLink
completeScalarLink = ScalarLink completeLinkPayload [ScalarABI "original" "adapter" ["Int32Rep","DoubleRep"] "DoubleRep"]

completeNativeLink :: NativeLink
completeNativeLink = NativeLink completeLinkPayload [NativeABI "original" "adapter" CApi SafeCall ["AddrRep","IntRep"] "void"]
  (Known (NativeBuildInputs [GroupCompile [input],SingleCompile input]
    [NativeProvider "actual-provider" ["original"] "provider.bc" "provider-sha" "actual-target" input]
    (Known [NativeDependency "resolved-c-only-archive-products-v1" "dependency"
      (SourceIdentity (Known "dependency") (Known []) (Known "configured") (Known "global")
        (Known "libyaml-clib") (Known "0.2.5") (Known [("external-libyaml",False)])
        (Known "lib") (Known "source-sha") Unknown)
      "actual registration\n" "registration-sha" [ArchiveProduct "lib.a" "archive-sha" [("api.o","object-sha")]]
      [NativeProduct (NativePiece "/source" "api.o" "object-sha" "api.bc" "actual-target" input) "bitcode-sha"]])
    [NativeLibrary "native-libc" ["free"] "clang" "compiler-sha" ["-lc"]] ["unknown"]
    [ArgumentBridge "actual-integer-width-bridge" "actual LLVM\n" "source-sha" "input-sha" [["define caller","define callee"]]]))
  (Known ["adapter"])
  where input = CompileInput "ghc" "clang" ["-c","api.c"] (Known "c") "native-target" "actual-target"
          [("api.c","actual-source-sha"),("yaml.h","actual-header-sha")]

completeNativeArchive :: NativeArchive
completeNativeArchive = NativeArchive 1 "thc-package-native-archive-v1" "not-linked" "main" "Typed"
  [call] Unknown ["unsupported"] (Known completeNativeLink) (Known [call,call])
  (Known (EntryResolution 1 "llvm-globaldce-adapter-closures-v1" "input-hash"
    [EntryClosure "adapter" "closure-hash" [],EntryClosure "bad_adapter" "failed-closure-hash" ["unsupported"]]
    "output-hash" []))
  where call = EmittedCall "original" (Known "main") CApi SafeCall ["AddrRep"] ["void"]
