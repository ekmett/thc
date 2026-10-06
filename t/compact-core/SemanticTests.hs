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
module SemanticTests (semanticTests, nativeProvenanceFacts) where

import Control.Exception (IOException, try)
import Control.Monad (forM, forM_)
import qualified Data.ByteString as BS
import Data.Either (isLeft)
import Data.Foldable (toList)
import Data.Aeson (Value(..), toJSON, object, (.=))
import qualified Data.Aeson.KeyMap as KM
import Data.Binary.Get (getByteString, getWord64le)
import Data.Bits (testBit)
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
import THC.Compact.Module (writeModule, writeModuleValue, encodeModuleValue, readModuleValue, finalizeModuleMetadata)
import THC.Compact.JSON (parseModuleWithoutDebug)
import THC.Compact.Inspect (moduleJSON, inspectContainer, unpackContainer)
import THC.CoreSymbols (symbolDigest)
import THC.Compact.Wire
import THC.Compact.Writer

semanticTests :: Test
semanticTests = TestList
  [ TestLabel "target layout schema selects exact unframed numeric vector" $ TestCase $ do
      let legacyLayout = TargetLayout 1 "same" "same" "same" "same" 1 False 8 LittleEndian "same" True [1..57]
          legacy = Facts 1 "same" "same" "same" "same" Missing (Known legacyLayout) []
            Missing Missing Missing (replicate 8 Missing) Nothing (Just (BackendPolicy (Just AstBackend) [])) Nothing
          spanBytes = [0,4]
          manual = BS.pack ([1] ++ concat (replicate 4 spanBytes) ++ [0,2,1] ++
            concat (replicate 4 spanBytes) ++ [1,0,8,0] ++ spanBytes ++ [1] ++ [1..57] ++ replicate 12 0 ++ [2,1,0])
      withEncoded (\_ encoder -> encodeFacts encoder legacy) $ \_ strings bytes -> do
        assertEqual "v1 exact original unframed bytes" manual bytes
        assertEqual "v1 original bytes decode independently" (Right legacy) (decodeFacts manual strings)
      forM_ [(1,57),(2,63)] $ \(schema,count) -> do
        let layout = TargetLayout 1 "ghc-9.14.1" "selected-abi" "selected-platform" "selected-way"
              schema False 8 LittleEndian "selected-platform" True [1..count]
            facts = completeFacts {factsTargetLayout=Known layout,
              factsBackendPolicy=Just (BackendPolicy (Just AstBackend) [])}
            document = moduleJSON facts []
        assertEqual "JSON keeps exact field names and next metadata" (Right (facts,[]))
          (parseModuleWithoutDebug document)
        withEncoded (\_ encoder -> encodeFacts encoder facts) $ \_ strings bytes -> do
          assertEqual "wire count leaves following policy intact" (Right facts) (decodeFacts bytes strings)
          assertBool "truncated wire rejected" (isLeft (decodeFacts (BS.init bytes) strings))
        forM_ [count-1,count+1] $ \badCount -> do
          let bad = facts {factsTargetLayout=Known layout {targetNumbers=[1..badCount]}}
          failure <- try (withEncoded (\_ encoder -> encodeFacts encoder bad) (\_ _ _ -> pure ())) :: IO (Either IOException ())
          assertBool "encoder rejects wrong count before publication" (isLeft failure)
      let unknown = completeFacts {factsTargetLayout=Known
            (TargetLayout 1 "ghc-9.14.1" "abi" "platform" "way" 3 False 8 LittleEndian "platform" True [1..57])}
      assertBool "JSON rejects unknown layout schema" (isLeft (parseModuleWithoutDebug (moduleJSON unknown [])))
      failure <- try (withEncoded (\_ encoder -> encodeFacts encoder unknown) (\_ _ _ -> pure ())) :: IO (Either IOException ())
      assertBool "wire encoder rejects unknown layout schema" (isLeft failure)
  , TestLabel "optional backend policy and header extension rejection" $ TestCase $ do
      let policy = BackendPolicy (Just AstBackend) [("main:Typed.answer",BytecodeBackend)]
          both = completeFacts {factsBackendPolicy=Just policy,
            factsClosureProvenance=Just (ClosureProvenance Missing Missing Missing [])}
      withEncoded (\_ encoder -> encodeFacts encoder both) $ \_ strings bytes ->
        assertEqual "closure and policy coexist" (Right both) (decodeFacts bytes strings)
      withEncoded (\_ encoder -> encodeFacts encoder completeFacts) $ \_ strings bytes -> do
        assertEqual "old header has no required extension" (Right completeFacts) (decodeFacts bytes strings)
        forM_ [ [3], [1,0,0,0,0,1,0,0,0,0], [2,1,0,2,1,0], [2,3,0] ] $ \suffix ->
          assertBool "unknown, duplicate or invalid backend extension" (isLeft (decodeFacts (bytes <> BS.pack suffix) strings))
      original <- case moduleJSON completeFacts [completeBinding] of
        Object fields -> pure fields
        _ -> fail "expected module object"
      forM_ [object ["default" .= ("other" :: String),"bindings" .= object []],
             object ["vectorize" .= True,"bindings" .= object []]] $ \invalid ->
        assertBool "unknown policy rejected" (isLeft (parseModuleWithoutDebug (Object (KM.insert "backendPolicy" invalid original))))
      let document = moduleJSON both [completeBinding]
      encoded <- encodeModuleValue document
      decoded <- either fail pure (readModuleValue encoded)
      assertEqual "policy inspection" (case document of Object fields -> KM.lookup "backendPolicy" fields; _ -> Nothing)
        (case decoded of Object fields -> KM.lookup "backendPolicy" fields; _ -> Nothing)
      withSystemTempDirectory "backend-policy-finalize" $ \directory -> do
        let destination = directory </> "module.cbd"
            originalModule = moduleJSON completeFacts [completeBinding]
            selected = moduleJSON completeFacts {factsBackendPolicy=Just policy} [completeBinding]
        _ <- writeModuleValue destination originalModule
        before <- BS.readFile destination
        finalizeModuleMetadata destination selected
        after <- BS.readFile destination
        (_,_,beforeSegments) <- either fail pure (unpackContainer before)
        (_,_,afterSegments) <- either fail pure (unpackContainer after)
        assertEqual "policy amendment preserves all payload/debug segments" beforeSegments afterSegments
        finalizeModuleMetadata destination originalModule
        retained <- BS.readFile destination
        assertEqual "later linkage metadata preserves root policy" (Right selected) (readModuleValue retained)
  , TestLabel "independent nested shared-shape wire golden"
 $ TestCase $ do
      tokens <- words <$> readFile "t/compact-core/golden/nested-shared-rep-v1.hex"
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
  , TestLabel "rubbish has no payload and retains each occurrence representation" $ TestCase $ do
      let unknownBoxed = Rep (scalar ObjectKind [BoxedUnknown]) (Evaluation (Known True) [])
          shapes = [Missing,Unknown] ++ map Known (longRep : unknownBoxed : tupleCold : tupleHot : representations)
      forM_ shapes $ \proof -> do
        let expression = Lit (emptyMeta {metaRep=proof}) LitRubbish
            binding = completeBinding {bindingExpr=expression}
            inspected = moduleJSON completeFacts [binding]
            legacy (Array values) = case toList values of
              String "lit" : String "rubbish" : _ : rest -> toJSON ([String "lit",String "rubbish",String "IntRep"] ++ rest)
              _ -> Array (fmap legacy values)
            legacy (Object fields) = Object (fmap legacy fields)
            legacy value = value
        assertEqual "inspection uses null and preserves full metadata" (Right (completeFacts,[binding]))
          (parseModuleWithoutDebug inspected)
        assertBool "legacy scalar payload is not silently coerced" (isLeft (parseModuleWithoutDebug (legacy inspected)))
        withEncoded (\streams encoder -> do
          encodeExpr encoder expression
          next <- streamOffset streams ExecutableData
          encodeExpr encoder (Lit emptyMeta (LitInt 41))
          pure next) $ \bytes strings next -> do
            assertEqual "rubbish ends at its tag, without another representation" 16 (BS.index bytes (fromIntegral next - 1))
            assertEqual "representation belongs to this exact occurrence" (Right (expression,next))
              (decodeExprAt bytes strings 0)
            assertEqual "next expression is not consumed as a literal payload" (Right (Lit emptyMeta (LitInt 41)))
              (fst <$> decodeExprAt bytes strings next)
  , TestLabel "nine expression tags and calling facts roundtrip" $ TestCase $
      withEncoded (\_ encoder -> encodeBinding encoder completeBinding) $ \bytes strings offset ->
        assertEqual "all fields" (Right (completeBinding,fromIntegral (BS.length bytes)))
          (decodeBindingAt bytes strings offset)
  , TestLabel "explicit unsupported nodes preserve their diagnostic and representation" $ TestCase $ do
      let metadata = emptyMeta {metaRep=Known (Rep (scalar ObjectKind [BoxedLifted]) (Evaluation (Known False) []))}
      forM_ [Lit metadata (LitUnsupported "RUBBISH(LiftedRep)"),Unsupported metadata "type-as-value"] $ \expression -> do
        let value = completeBinding {bindingExpr=expression}
        assertEqual "inspection preserves unsupported identity, not supported rubbish"
          (Right (completeFacts,[value])) (parseModuleWithoutDebug (moduleJSON completeFacts [value]))
        withEncoded (\_ encoder -> encodeBinding encoder value) $ \bytes strings offset ->
          assertEqual "selected binary record retains exact original metadata"
            (Right value) (fst <$> decodeBindingAt bytes strings offset)
  , TestLabel "optional declared host signature preserves old binding records" $ TestCase $ do
      let raw = HostType (Rep (scalar ObjectKind [BoxedUnlifted]) (Evaluation (Known True) [])) [HostObject]
          library = case raw of HostType proof _ -> HostType proof [HostInteropLibrary]
          signature = HostSignature [raw] library
      forM_ [Missing,Unknown,Known signature] $ \proof -> do
        let binding = completeBinding {bindingHostSignature=proof}
            original = moduleJSON completeFacts [binding]
        assertEqual "JSON preserves optional nominal declaration" (Right (completeFacts,[binding])) (parseModuleWithoutDebug original)
        withEncoded (\_ encoder -> encodeBinding encoder binding) $ \bytes strings offset -> do
          assertEqual "selected record retains declaration" (Right binding) (fst <$> decodeBindingAt bytes strings offset)
          if proof == Missing
            then assertEqual "old record needs no feature bit" (Right binding)
              (fst <$> decodeBindingAtWithHostSignatures False bytes strings offset)
            else assertBool "extension requires feature bit"
              (isLeft (decodeBindingAtWithHostSignatures False bytes strings offset))
  , TestLabel "foreign byte-array argument identity survives selected wire records" $ TestCase $
      forM_ ["ByteArray#", "MutableByteArray#"] $ \arrayType -> do
        let amend (Object fields)
              | KM.member "argumentReps" fields = Object (KM.insert "schema" (Number 2)
                  (KM.insert "argumentTypes" (toJSON [Just arrayType :: Maybe Text.Text]) fields))
              | otherwise = Object (fmap amend fields)
            amend (Array values) = Array (fmap amend values)
            amend value = value
            original = amend (moduleJSON completeFacts [completeBinding])
        (facts, bindings) <- either fail pure (parseModuleWithoutDebug original)
        assertEqual "JSON retains the actual array mutability" original (moduleJSON facts bindings)
        case bindings of
          [binding] -> withEncoded (\_ encoder -> encodeBinding encoder binding) $ \bytes strings offset ->
            assertEqual "selected bytecode record retains array identity" (Right binding)
              (fst <$> decodeBindingAt bytes strings offset)
          _ -> assertFailure "Expected one unchanged binding"
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
  , TestLabel "known-start facts have independent metadata and executable strings" $ TestCase $
      withSystemTempDirectory "compact-header" $ \directory -> do
        let destination = directory </> "header.cbd"
            prepare streams = do
              encoder <- newEncoder streams
              encodeFacts encoder completeFacts
            produce streams = do
              encoder <- newEncoder streams
              _ <- encodeBinding encoder completeBinding
              pure 0
        _ <- writeContainerPrepared destination prepare 0 produce
        file <- BS.readFile destination
        (_,factBytes,segments) <- either fail pure (unpackContainer file)
        case segments of
          payload : strings : _ -> do
            assertEqual "header-only decode" (Right completeFacts) (decodeMetadata factBytes)
            assertEqual "execution has its own strings" (Right completeBinding) (fst <$> decodeBindingAt payload strings 0)
            assertBool "header includes inline constructor shape" (not (BS.null factBytes))
          _ -> assertFailure "Missing strings"
  , TestLabel "unmapped present provenance cannot disappear during preparation" $ TestCase $
      withSystemTempDirectory "compact-header-rejection" $ \directory -> do
        failure <- try (writeContainerPrepared (directory </> "bad.cbd")
          (\streams -> newEncoder streams >>= \encoder -> encodeFacts encoder
            completeFacts {factsPendingProvenance=Known (ImportsRecord completeImports) : replicate 7 Missing}) 0 (const (pure 0)))
          :: IO (Either IOException Container)
        assertBool "unmapped known record rejected" (isLeft failure)
  , TestLabel "module directory captures actual data-relative binding positions" $ TestCase $
      withSystemTempDirectory "compact-module" $ \directory -> do
        let values = [completeBinding, completeBinding {bindingIdentity=Global "main::Main.main"},
              completeBinding {bindingIdentity=Global "main:Typed.control", bindingExpr=Prim emptyMeta "prompt#"}]
            destination = directory </> "module.cbd"
        footer <- writeModule destination completeFacts values
        assertEqual "all bindings indexed" 3 (headerBindingCount (containerHeader footer))
        assertEqual "actual control/registration/alias, no scalar declarations" 7 (headerSummaries (containerHeader footer))
        assertEqual "debug-free producer" 0 (headerDebugFlags (containerHeader footer))
        file <- BS.readFile destination
        (_,_,segments) <- either fail pure (unpackContainer file)
        case segments of
          [bytes,strings,_,_,_,rows] -> do
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
        failure <- try (writeModule destination completeFacts [completeBinding,completeBinding]) :: IO (Either IOException Container)
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
        let destination = directory </> "provenance.cbd"
        footer <- writeModule destination facts []
        assertEqual "actual registration and declaration provider, no bindings" 10 (headerSummaries (containerHeader footer))
        bytes <- BS.readFile destination
        (_,factBytes,segments) <- either fail pure (unpackContainer bytes)
        case segments of
          payload:_:_ -> do
            assertEqual "header provenance has no executable shape references" BS.empty payload
            assertEqual "all scoped types, safety and original expected calls preserved"
              (Right facts) (decodeMetadata factBytes)
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
      let facts = nativeProvenanceFacts
      assertEqual "all original link inputs and rejected closure evidence survive JSON inspection"
        (Right (facts,[])) (parseModuleWithoutDebug (moduleJSON facts []))
      withEncoded (\_ encoder -> encodeFacts encoder facts) $ \payload strings bytes -> do
        assertEqual "metadata-only link records have no executable body" BS.empty payload
        assertEqual "native blob and every nested original recipe survive typed bytes" (Right facts) (decodeFacts bytes strings)
        forM_ [0,BS.length bytes-1] $ \size ->
          assertBool "truncated native record rejects" (isLeft (decodeFacts (BS.take size bytes) strings))
  , TestLabel "native source flags have explicit ascending name order" $ TestCase $ do
      let reversed = nativeFactsWithFlags [("z-config",False),("a-config",True)]
          ordered = nativeFactsWithFlags [("a-config",True),("z-config",False)]
      assertEqual "Cabal flag object order does not leak into typed records" (Right (ordered,[]))
        (parseModuleWithoutDebug (moduleJSON reversed []))
  , TestLabel "installed native component is discoverable without source declarations" $ TestCase $
      withSystemTempDirectory "compact-installed-native" $ \directory -> do
        let facts = completeFacts {factsForeign = Missing, factsPendingProvenance =
              [Missing,Missing,Missing,Missing,Missing,Missing,Known (NativeLinkRecord completeNativeLink),Missing]}
        footer <- writeModule (directory </> "module.cbd") facts []
        assertEqual "foreign-owner lookup sees the compiled component in the cold directory"
          8 (headerSummaries (containerHeader footer))
  , TestLabel "GHC main wrapper summary is independent of source module" $ TestCase $
      withSystemTempDirectory "compact-named-main" $ \directory ->
        forM_ ["Main", "NamedMain"] $ \name ->
          forM_ ["main::Main.main", "main::NamedMain.main"] $ \key -> do
            footer <- writeModule (directory </> "module.cbd") completeFacts {factsModule = name}
              [completeBinding {bindingIdentity = Global key}]
            assertEqual "only the real GHC CLI wrapper sets the alias bit"
              (key == "main::Main.main") (testBit (headerSummaries (containerHeader footer)) 2)
  , TestLabel "typed package address and finalizer facts preserve schema-one prefixes" $ TestCase $ do
      ImportProof _ scope execution profile owner name (ImportsVerified wordBits productRecord imports calls _ _ _) <- pure completeImports
      let address = AddressAssociation qualified (Known "original.h") "original_finalizer" True CApi
            nominal nominal "representational" (Just (["AddrRep"],"void"))
          imports2 = ImportProof 2 scope execution profile owner name
            (ImportsVerified wordBits productRecord imports calls [address] [] Nothing)
          NativeLink (LinkPayload _ format linkProfile unit target component digest artifact) abi inputs companion dataSymbols _ components _ = completeNativeLink
          linked2 = NativeLink (LinkPayload 2 format linkProfile unit target component digest artifact) abi inputs companion dataSymbols ["adapter"] components Nothing
          facts = completeFacts {factsPendingProvenance =
            [Missing,Known (ImportsRecord imports2),Known (ImportsRecord imports2),Missing,Missing,Missing,
             Known (NativeLinkRecord linked2),Missing]}
      assertEqual "new evidence survives JSON without becoming a call" (Right (facts,[]))
        (parseModuleWithoutDebug (moduleJSON facts []))
      withEncoded (\_ encoder -> encodeFacts encoder facts) $ \payload strings bytes -> do
        assertEqual "callback inventory is metadata, not executable DATA" BS.empty payload
        assertEqual "versioned address identity, type and native roots survive" (Right facts) (decodeFacts bytes strings)
  , TestLabel "native companion bytes and data entries survive compact metadata" $ TestCase $ do
      let original = moduleJSON completeFacts {factsPendingProvenance =
            [Missing,Missing,Missing,Missing,Missing,Missing,Known (NativeLinkRecord completeNativeLink),Missing]} []
          amend (Object fields) | Just (Object link) <- KM.lookup "packageNativeLink" fields =
            Object (KM.insert "packageNativeLink" (Object $ KM.insert "dataSymbols" (toJSON (["data_adapter"] :: [String])) $
              KM.insert "nativeLibrary" (object ["sha256" .= (replicate 64 'a'), "hex" .= ("00ff427f" :: String)]) $
              KM.delete "availableEntries" link) fields)
          amend value = value
          extras (Object fields) | KM.lookup "provider" fields == Just (String "native-libc") =
            Object $ KM.insert "dependencyArguments" (toJSON (["-shared","dependency.a"] :: [String])) $
              KM.insert "objcopy" (String "llvm-objcopy") $ KM.insert "objcopySha256" (String "tool-hash") $
              KM.insert "objcopyArguments" (toJSON ([["--remove-section=.llvmbc","dependencies.so"]] :: [[String]])) fields
          extras (Object fields) = Object (fmap extras fields)
          extras (Array values) = Array (fmap extras values)
          extras value = value
          expected = extras (amend original)
      (facts, bindings) <- either fail pure (parseModuleWithoutDebug expected)
      assertEqual "no companion bytes or data entry identity lost" expected (moduleJSON facts bindings)
      withEncoded (\_ encoder -> encodeFacts encoder facts) $ \_ strings payload ->
        assertEqual "native metadata survives typed wire encoding" (Right facts) (decodeFacts payload strings)
  , TestLabel "actual native producer build inputs survive final metadata attachment" $ TestCase $
      withSystemTempDirectory "compact-native-inputs" $ \directory -> do
        let original = moduleJSON completeFacts {factsPendingProvenance =
              [Missing,Missing,Missing,Missing,Missing,Missing,Known (NativeLinkRecord completeNativeLink),Missing]} [completeBinding]
            at key (Object fields) = maybe Null id (KM.lookup key fields)
            at _ _ = Null
            set key value (Object fields) = Object (KM.insert key value fields)
            set _ _ value = value
            link = at "packageNativeLink" original
            inputs = at "buildInputs" link
            proof = case at "dependencies" inputs of
              Array values -> case toList values of
                [value] -> set "profile" (String "resolved-native-archive-products-v1") value
                _ -> error "expected one native proof"
              _ -> error "missing native proof"
            edge = object ["declaredPath" .= (["facade","dependency"] :: [String]),"unit" .= ("dependency" :: String),
              "componentSha256" .= ("component-hash" :: String),"bitcodeSha256" .= ("bitcode-hash" :: String)]
            source kind fields = object ("type" .= (kind :: String) : fields)
            products = [Null,proof] ++ [set "sourceIdentity" (set "pkg-src" location (at "sourceIdentity" proof)) proof |
              location <- [Null,source "local" ["path" .= ("/actual/source" :: String)],
                source "repo-tar" ["repo" .= object ["type" .= ("secure-repo" :: String),"uri" .= ("https://hackage.haskell.org/" :: String)]]]]
            attach productRecord dependencies = set "packageNativeLink"
              (set "buildInputs" (set "nativeProduct" productRecord (set "dependencies" (toJSON dependencies) inputs)) link) original
        forM_ [(productRecord,dependencies) | productRecord <- products, dependencies <- [[],[edge]]] $ \(productRecord,dependencies) -> do
          let expected = attach productRecord dependencies
              staging = directory </> "module.cbd"
          (facts,bindings) <- either fail pure (parseModuleWithoutDebug expected)
          assertEqual "actual producer fields have exact typed representation" expected (moduleJSON facts bindings)
          bytes <- encodeModuleValue expected
          assertEqual "native product and declared dependency path survive wire" (Right expected) (readModuleValue bytes)
          _ <- writeModuleValue staging original
          before <- BS.readFile staging
          finalizeModuleMetadata staging expected
          after <- BS.readFile staging
          assertEqual "native finalization retains complete new metadata" (Right expected) (readModuleValue after)
          (_,_,oldSegments) <- either fail pure (unpackContainer before)
          (_,_,newSegments) <- either fail pure (unpackContainer after)
          assertEqual "native metadata does not change any execution/debug segment" oldSegments newSegments
        assertBool "unmapped dependency fields still fail" (isLeft (parseModuleWithoutDebug
          (attach Null [set "invented" (Bool True) edge])))
        assertBool "unmapped native product fields still fail" (isLeft (parseModuleWithoutDebug
          (attach (set "invented" (Bool True) proof) [edge])))
        assertBool "unmapped Cabal source fields still fail" (isLeft (parseModuleWithoutDebug
          (attach (set "sourceIdentity" (set "pkg-src" (source "local" ["invented" .= True])
            (at "sourceIdentity" proof)) proof) [edge])))
  , TestLabel "retired partial native protocols cannot become executable metadata" $ TestCase $ do
      let original = moduleJSON completeFacts {factsPendingProvenance =
            [Missing,Missing,Missing,Missing,Missing,Missing,Known (NativeLinkRecord completeNativeLink),Missing]} []
          amend (Object fields) | Just (Object link) <- KM.lookup "packageNativeLink" fields =
            Object (KM.insert "packageNativeLink" (Object $ KM.insert "availableEntries" (toJSON (["adapter"] :: [String])) link) fields)
          amend value = value
      assertBool "retired selected-entry proof rejected" (isLeft (parseModuleWithoutDebug (amend original)))
  , TestLabel "native demand seeds are a typed schema3 extension" $ TestCase $ do
      let original = moduleJSON completeFacts {factsPendingProvenance =
            [Missing,Missing,Missing,Missing,Missing,Missing,Known (NativeLinkRecord completeNativeLink),Missing]} []
          seed = object ["entry" .= ("adapter"::String),"bitcodeHex" .= ("00ff42"::String),
            "bitcodeSha256" .= ("seed-sha"::String),"providerUnit" .= Null,
            "providerComponentSha256" .= Null,"providerSymbol" .= Null]
          amend value (Object fields) | Just (Object link) <- KM.lookup "packageNativeLink" fields =
            Object (KM.insert "packageNativeLink" (Object $ KM.insert "callSeeds" value $
              KM.insert "profile" "thc-package-c-ffi-demand-v1" $ KM.insert "schema" (Number 3) link) fields)
          amend _ value = value
          requested = amend (toJSON [seed]) original
      (facts,bindings) <- either assertFailure pure (parseModuleWithoutDebug requested)
      assertEqual "every seed identity and absent provider survive typed inspection" requested (moduleJSON facts bindings)
      encoded <- encodeModuleValue requested
      assertEqual "normal CBD transport preserves the immutable seed catalogue" (Right requested) (readModuleValue encoded)
      assertBool "unknown seed fields cannot become implicit native permissions" (isLeft (parseModuleWithoutDebug
        (amend (toJSON [case seed of Object fields -> Object (KM.insert "invented" Null fields); _ -> seed]) original)))
  , TestLabel "mixed native dependency components retain exact independent payloads" $ TestCase $ do
      let component owner dependencies = NativeComponent
            (LinkPayload 1 "llvm-bitcode" "thc-package-native-component-v1" owner "actual-target"
              "component-sha" "bitcode-sha" (BS.pack [66,67,192,222,0,255]))
            ["public_function","public_data"] dependencies (Known ("native-sha",BS.pack [127,69,76,70]))
          leaf = component "native-provider" []
          middle = component "mixed-provider" [leaf]
          NativeLink payload abi inputs companion symbols finalizers _ seeds = completeNativeLink
          link = NativeLink payload abi inputs companion symbols finalizers (Just (["own_export"],[middle,leaf])) seeds
          facts = completeFacts {factsPendingProvenance =
            [Missing,Missing,Missing,Missing,Missing,Missing,Known (NativeLinkRecord link),Missing]}
          original = moduleJSON facts []
      assertEqual "public exports and recursive provider identity survive inspection" (Right (facts,[]))
        (parseModuleWithoutDebug original)
      withEncoded (\_ encoder -> encodeFacts encoder facts) $ \_ strings bytes ->
        assertEqual "dependency payload and companion are unchanged, without fabricated ABI"
          (Right facts) (decodeFacts bytes strings)
      let unpaired (Object fields) = Object (fmap (\value -> case value of
            Object linkFields | KM.member "abi" linkFields -> Object (KM.delete "dependencies" linkFields)
            other -> other) fields)
          unpaired value = value
      assertBool "paired metadata cannot be silently dropped" (isLeft (parseModuleWithoutDebug (unpaired original)))
  , TestLabel "callback wrapper association survives compact metadata transport" $ TestCase $ do
      ImportProof _ scope execution profile owner name (ImportsVerified wordBits productRecord imports calls addresses _ _) <- pure completeImports
      let wrapper = WrapperAssociation (ExportAssociation qualified "actual_helper" CCall nominal nominal
            "representational" [nominal] nominal IOExport) "W"
          proof = ImportProof 3 scope execution profile owner name
            (ImportsVerified wordBits productRecord imports calls addresses [wrapper] Nothing)
          facts = completeFacts {factsPendingProvenance = [Missing,Known (ImportsRecord proof),Known (ImportsRecord proof),Missing,Missing,Missing,Missing,Missing]}
      assertEqual "callback ABI and emitted helper survive JSON" (Right (facts,[]))
        (parseModuleWithoutDebug (moduleJSON facts []))
      withEncoded (\_ encoder -> encodeFacts encoder facts) $ \_ strings bytes ->
        assertEqual "callback ABI and helper survive selected wire decoding" (Right facts) (decodeFacts bytes strings)
  , TestLabel "mixed import and static export product partition survives compact metadata" $ TestCase $ do
      let original = moduleJSON completeFacts {factsPendingProvenance =
            [Missing,Known (ImportsRecord completeImports),Known (ImportsRecord completeImports),Missing,Missing,Missing,Missing,Missing]} []
          partition = object ["schema" .= (1::Int),"execution" .= ("not-linked"::String),"stubs" .= Null,"files" .= ([]::[Value])]
          amend (Object fields) = Object (fmap change fields)
          amend value = value
          change (Object proof) | KM.member "expectedForeign" proof = Object $
            KM.insert "schema" (toJSON (4::Int)) $ KM.insert "addresses" (toJSON ([]::[Value])) $
            KM.insert "wrappers" (toJSON ([]::[Value])) $ KM.insert "importForeign" partition proof
          change value = value
          expected = amend original
      (facts,bindings) <- either fail pure (parseModuleWithoutDebug expected)
      assertEqual "original product and import-only product are distinct" expected (moduleJSON facts bindings)
      withEncoded (\_ encoder -> encodeFacts encoder facts) $ \_ strings bytes ->
        assertEqual "schema 4 partition survives typed bytes" (Right facts) (decodeFacts bytes strings)
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
  let destination = directory </> "control.cbd"
  _ <- writeContainer destination BS.empty 0 $ \streams -> do
    encoder <- newEncoder streams
    value <- produce streams encoder
    writeIORef result (Just value)
    pure 0
  bytes <- BS.readFile destination
  (_,_,segments) <- either fail pure (unpackContainer bytes)
  case segments of
    payload : strings : _ -> do
      value <- readIORef result >>= maybe (fail "Missing typed control result") pure
      inspect payload strings value
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
  , LitNullAddr, LitRubbish, LitFunctionAddr "foreign_fn", LitDataAddr "foreign_data"
  , LitUnsupported "RUBBISH(LiftedRep)"
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
  (Known 0) (Known longRep) Missing Missing body
  where
    local = Binding (Local 1) OtherEntry (Known True) 0 Unknown Missing (Known []) Missing
      Missing Missing Missing Missing (Lit emptyMeta (LitInt 41))
    info = emptyMeta
      { metaRep = Known longRep, metaResultRep = Known tupleHot
      , metaEntryStrict = Known [True], metaEntryStrictSource = Known "ghc-cbv"
      , metaCallDemand = Known (CallDemand 1 [True])
      , metaForeignCall = Known (ForeignCall 1 (StaticTarget "original_fn" (Known "real-unit") True)
          CApi SafeCall 1 1 [longRep] longRep Unknown (Known "return $1") Missing)
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
  (Known "main") [Missing,Unknown,Missing,Unknown,Missing,Missing,Missing,Missing] Nothing Nothing Nothing

completeImports :: ImportProof
completeImports = ImportProof 1 "retained-static-import-products" "not-linked"
  "ghc-9.14.1-thc-only-static-c-imports-v1" "main" "Typed" (ImportsVerified 64 originalProducts
    [ImportAssociation qualified Unknown "original_fn" (Known "main") True CApi InterruptibleCall
      nominal (ForeignApplication nominal (ForeignVariable 0)) "representational"
      (EmittedCall "original_fn" (Known "main") CApi InterruptibleCall ["IntRep","void"] ["void","IntRep"])]
    [expected,expected] [] [] Nothing)
  where
    expected = ForeignCall 1 (StaticTarget "original_fn" (Known "main") True) CApi InterruptibleCall
      2 2 [longRep,tupleCold] tupleHot Unknown Missing Missing

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

-- | Synthetic codec model for independent readers, not an executable native
-- artifact or proof of any machine observation. All eight header slots occur.
nativeProvenanceFacts :: Facts
nativeProvenanceFacts = completeFacts {factsPendingProvenance =
  [Known (ForeignLinkRecord completeForeignLink),Known (ImportsRecord completeImports),
   Known (ImportsRecord completeImports),Known (ExportsRecord completeExports),
   Known (RegistrationRecord completeRegistration),Known (ScalarLinkRecord completeScalarLink),
   Known (NativeLinkRecord completeNativeLink),Known (NativeArchiveRecord completeNativeArchive)]}

nativeFactsWithFlags :: [(BS.ByteString,Bool)] -> Facts
nativeFactsWithFlags flags = nativeProvenanceFacts
  {factsPendingProvenance=map replace (factsPendingProvenance nativeProvenanceFacts)}
  where
    replace (Known (NativeLinkRecord (NativeLink payload abi (Known (NativeBuildInputs units providers (ArchiveBuildDependencies (Known dependencies)) libraries unresolved bridges)) companion dataSymbols finalizers components seeds))) =
      Known (NativeLinkRecord (NativeLink payload abi (Known (NativeBuildInputs units providers (ArchiveBuildDependencies (Known (map dependency dependencies))) libraries unresolved bridges)) companion dataSymbols finalizers components seeds))
    replace value = value
    dependency (NativeDependency profile unit (SourceIdentity identifier depends kind style name version _ component sourceSha cabalSha source)
        registrationText digest archives products) = NativeDependency profile unit
          (SourceIdentity identifier depends kind style name version (Known flags) component sourceSha cabalSha source)
          registrationText digest archives products

completeLinkPayload :: LinkPayload
completeLinkPayload = LinkPayload 1 "llvm-bitcode" "thc-package-c-ffi-v1" "main" "actual-target"
  "actual-component-hash" "actual-bitcode-hash" (BS.pack [66,67,192,222,0,255])

completeScalarLink :: ScalarLink
completeScalarLink = ScalarLink completeLinkPayload [ScalarABI "original" "adapter" ["Int32Rep","DoubleRep"] "DoubleRep"]

completeNativeLink :: NativeLink
completeNativeLink = NativeLink completeLinkPayload [NativeABI "original" "adapter" CApi SafeCall ["AddrRep","IntRep"] "void"]
  (Known (NativeBuildInputs [GroupCompile [input],SingleCompile input]
    [NativeProvider "actual-provider" ["original"] "provider.bc" "provider-sha" "actual-target" input]
    (ArchiveBuildDependencies (Known [NativeDependency "resolved-c-only-archive-products-v1" "dependency"
      (SourceIdentity (Known "dependency") (Known []) (Known "configured") (Known "global")
        (Known "libyaml-clib") (Known "0.2.5") (Known [("external-libyaml",False)])
        (Known "lib") (Known "source-sha") Unknown Missing)
      "actual registration\n" "registration-sha" [ArchiveProduct "lib.a" "archive-sha" [("api.o","object-sha")]]
      [NativeProduct (NativePiece "/source" "api.o" "object-sha" "api.bc" "actual-target" input) "bitcode-sha"]]))
    [NativeLibrary "native-libc" ["free"] "clang" "compiler-sha" ["-lc"] Missing Missing Missing Missing] ["unknown"]
    [ArgumentBridge "actual-integer-width-bridge" "actual LLVM\n" "source-sha" "input-sha" [["define caller","define callee"]]]))
  Missing Missing [] Nothing Nothing
  where input = CompileInput "ghc" "clang" ["-c","api.c"] (Known "c") "native-target" "actual-target"
          [("api.c","actual-source-sha"),("yaml.h","actual-header-sha")]

completeNativeArchive :: NativeArchive
completeNativeArchive = NativeArchive 1 "thc-package-native-archive-v1" "not-linked" "main" "Typed"
  [call] Unknown ["unsupported"] (Known completeNativeLink) (Known [call,call])
  where call = EmittedCall "original" (Known "main") CApi SafeCall ["AddrRep"] ["void"]
