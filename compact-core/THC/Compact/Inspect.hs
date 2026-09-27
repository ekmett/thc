-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Compact.Inspect
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; explicit offline JSON inspection
--
-- Inspect typed records as flat JSON without pretending synthetic local names
-- are original source spelling. IEEE bit literals retain every payload bit.
-- Reading a complete module here is an explicit inspection operation, never a
-- runtime startup or linking prerequisite.
module THC.Compact.Inspect (inspectContainer, moduleJSON) where

import Data.Aeson
import qualified Data.Aeson.Key as Key
import Data.Binary.Get (getByteString, getWord64le)
import qualified Data.ByteString as BS
import Data.List (sortOn)
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Numeric (showHex)
import THC.Compact.Core
import THC.Compact.Decode
import THC.Compact.Facts
import THC.Compact.Wire

-- | Decode original data order, not digest order. The caller explicitly reads
-- the one container to inspect; normal runtime demand loading is independent.
inspectContainer :: BS.ByteString -> Either String Value
inspectContainer bytes = do
  header <- decodeExact getHeader (BS.take 24 bytes)
  footer <- decodeExact getFooter (BS.drop (BS.length bytes-128) bytes)
  validateContainer (fromIntegral (BS.length bytes)) header footer
  case footerSegments footer of
    [dataSpan,stringSpan,_,_,_,indexSpan] -> do
      let slice (Span start size) = BS.take (fromIntegral size) (BS.drop (fromIntegral start) bytes)
          payload = slice dataSpan
          strings = slice stringSpan
          directory = slice indexSpan
          factsBytes = BS.take (fromIntegral (headerFactsLength header)) (BS.drop 24 bytes)
      facts <- decodeFacts factsBytes strings
      rows <- mapM (\start -> decodeExact ((,) <$> getByteString 16 <*> getWord64le)
        (BS.take 24 (BS.drop start directory))) [0,24..BS.length directory-1]
      bindings <- mapM (\(_,offset) -> fst <$> decodeBindingAt payload strings offset) (sortOn snd rows)
      pure (moduleJSON facts bindings)
    _ -> Left "Compact container requires six segments"

str :: BS.ByteString -> Value
str = String . Text.decodeUtf8

arr :: (a -> Value) -> [a] -> Value
arr render = toJSON . map render

p :: Key.Key -> (a -> Value) -> Presence a -> [Pair]
p _ _ Missing = []
p key _ Unknown = [(key,Null)]
p key render (Known value) = [(key,render value)]

type Pair = (Key.Key,Value)

nullable :: (a -> Value) -> Presence a -> Value
nullable _ Missing = error "Absent compact array element"
nullable _ Unknown = Null
nullable render (Known value) = render value

identity :: Identity -> Value
identity (Global key) = str key
identity (Local ordinal) = toJSON ("@local/" ++ show ordinal)

entryType :: EntryType -> [Pair]
entryType OtherEntry = []
entryType IOUnit = [("type",String "IO ()")]
entryType StateRealWorld = [("type",String "State# RealWorld")]

binding :: Binding -> Value
binding value = object $ [("id",identity (bindingIdentity value)),("name",identity (bindingIdentity value)),
    ("arity",toJSON (bindingArity value)),("expr",expr (bindingExpr value))]
  ++ entryType (bindingEntryType value) ++ p "lifted" toJSON (bindingLifted value)
  ++ p "rep" rep (bindingRep value) ++ p "info" idInfo (bindingInfo value)
  ++ p "entryStrict" toJSON (bindingEntryStrict value) ++ p "entryStrictSource" str (bindingEntryStrictSource value)
  ++ p "joinValueArity" toJSON (bindingJoinValueArity value) ++ p "joinResultRep" rep (bindingJoinResultRep value)

binder :: Binder -> Value
binder value = object $ [("id",identity (Local (binderOrdinal value))),("name",identity (Local (binderOrdinal value)))]
  ++ entryType (binderEntryType value) ++ p "lifted" toJSON (binderLifted value)
  ++ p "coercion" toJSON (binderCoercion value) ++ p "rep" rep (binderRep value) ++ p "info" idInfo (binderInfo value)

idInfo :: IdInfo -> Value
idInfo (IdInfo joinArity eligible marks) = object (p "joinArity" toJSON joinArity ++
  p "cbvEligible" toJSON eligible ++ p "cbvMarks" toJSON marks)

expr :: Expr -> Value
expr value = case value of
  Var m key -> node "var" m [identity key]
  Prim m name -> node "prim" m [str name]
  Lit m lit -> let (kind,payload) = literal lit in node "lit" m [kind,payload]
  Lam m parameters body -> node "lam" m [arr binder parameters,expr body]
  Con m key arity -> node "con" m [str key,toJSON arity]
  App m function arguments lifted hnf speculate -> node "app" m
    [expr function,arr expr arguments,arr (nullable toJSON) lifted,toJSON hnf,toJSON speculate]
  Let m recursive bindings body -> node "let" m [toJSON recursive,arr binding bindings,expr body]
  Case m scrutinee ordinal information alternatives -> toJSON
    ([String "case",expr scrutinee,identity (Local ordinal),arr alternative alternatives,
      object (meta m ++ p "binder" binder information)])
  Void m -> node "void" m []
  where node kind m payload = toJSON (String kind : payload ++ [object (meta m)])

alternative :: Alternative -> Value
alternative value = case value of
  DefaultAlt parameters body -> node "default" Null parameters body
  DataAlt key parameters body -> node "data" (str key) parameters body
  LiteralAlt lit parameters body -> let (kind,payload) = literal lit in node "lit" (toJSON [kind,payload]) parameters body
  where node kind discriminator parameters body = toJSON
          [String kind,discriminator,arr (identity . Local . binderOrdinal) parameters,expr body,
           object ["binders" .= arr binder parameters]]

meta :: Meta -> [Pair]
meta value = p "rep" rep (metaRep value) ++ p "resultRep" rep (metaResultRep value)
  ++ p "entryStrict" toJSON (metaEntryStrict value) ++ p "entryStrictSource" str (metaEntryStrictSource value)
  ++ p "callDemand" callDemand (metaCallDemand value) ++ p "foreignCall" foreignCall (metaForeignCall value)
  ++ p "exceptionPayload" exceptionPayload (metaExceptionPayload value) ++ p "enumFamily" enumFamily (metaEnumFamily value)
  ++ p "dataToTagFamily" tagFamily (metaTagFamily value) ++ p "unsafeEqualityCase" str (metaUnsafeEqualityCase value)

callDemand :: CallDemand -> Value
callDemand (CallDemand arity marks) = object ["arity" .= arity,"strictArgs" .= marks]
exceptionPayload :: ExceptionPayload -> Value
exceptionPayload (ExceptionPayload schema nominal) = object ["schema" .= schema,"type" .= str nominal]
enumFamily :: EnumFamily -> Value
enumFamily (EnumFamily nominal constructors) = object ["typeConstructor" .= str nominal,"constructors" .= arr str constructors]
tagFamily :: TagFamily -> Value
tagFamily (TagFamily (EnumFamily nominal constructors) limit small) = object
  ["typeConstructor" .= str nominal,"constructors" .= arr str constructors,"smallFamilyLimit" .= limit,"smallFamily" .= small]

foreignCall :: ForeignCall -> Value
foreignCall value = object $ ["schema" .= foreignSchema value,"target" .= target (foreignTarget value),
  "convention" .= tagName ["ccall","capi","stdcall","prim","javascript"] (foreignConvention value),
  "safety" .= tagName ["unsafe","safe","interruptible"] (foreignSafety value),
  "arity" .= foreignArity value,"suppliedArity" .= foreignSuppliedArity value,
  "argumentReps" .= arr rep (foreignArgumentReps value),"resultRep" .= rep (foreignResultRep value)]
  ++ p "intrinsic" str (foreignIntrinsic value) ++ p "javascriptSource" str (foreignJavaScriptSource value)
  where
    target DynamicTarget = object ["kind" .= String "dynamic"]
    target (StaticTarget symbol unit function) = object $
      ["kind" .= String "static","symbol" .= str symbol,"isFunction" .= function] ++ p "unit" str unit

tagName :: Enum a => [Text.Text] -> a -> Value
tagName names value = String (names !! fromEnum value)

rep :: Rep -> Value
rep (Rep layout (Evaluation evaluated children))
  | length children /= length (shapeChildren layout) = error "Invalid compact evaluation tree"
  | otherwise = object $ ["kind" .= tagName
      ["long","float","double","address","void","data","closure","object","vector","unknown"] (shapeKind layout)]
      ++ p "primReps" (arr primRep) (shapePrimReps layout) ++ p "vector" vector (shapeVector layout)
      ++ p "aggregate" (tagName ["unboxed-tuple","unboxed-sum"]) (shapeAggregate layout)
      ++ childrenField "components" (shapeComponents layout) componentStates
      ++ childrenField "alternatives" (shapeAlternatives layout) alternativeStates
      ++ p "tagSlot" toJSON (shapeTagSlot layout) ++ p "alternativeSlots" toJSON (shapeAlternativeSlots layout)
      ++ p "evaluated" toJSON evaluated
  where
    componentCount = case shapeComponents layout of Known values -> length values; _ -> 0
    (componentStates,alternativeStates) = splitAt componentCount children
    childrenField key values states = p key (arr rep . flip (zipWith Rep) states) values

vector :: Vector -> Value
vector (Vector lanes element) = object ["lanes" .= lanes,"element" .= elementName element]
elementName :: Element -> Value
elementName = tagName ["Int8ElemRep","Int16ElemRep","Int32ElemRep","Int64ElemRep","Word8ElemRep",
  "Word16ElemRep","Word32ElemRep","Word64ElemRep","FloatElemRep","DoubleElemRep"]

primRep :: PrimRep -> Value
primRep value = String $ case value of
  BoxedUnknown -> "BoxedRep Nothing"
  BoxedLifted -> "BoxedRep (Just Lifted)"
  BoxedUnlifted -> "BoxedRep (Just Unlifted)"
  VecRep (Vector lanes element) -> "VecRep " <> Text.pack (show lanes) <> " " <> case elementName element of
    String name -> name
    _ -> error "Expected vector element string"
  _ -> Text.pack (show value)

literal :: Literal -> (Value,Value)
literal value = case value of
  LitInt n -> numeric "int" n; LitWord n -> numeric "word" n
  LitInt8 n -> numeric "int8" n; LitInt16 n -> numeric "int16" n; LitInt32 n -> numeric "int32" n; LitInt64 n -> numeric "int64" n
  LitWord8 n -> numeric "word8" n; LitWord16 n -> numeric "word16" n; LitWord32 n -> numeric "word32" n; LitWord64 n -> numeric "word64" n
  LitBigNat n -> numeric "bignat" n; LitChar n -> numeric "char" n
  LitBytes bytes -> (String "string-bytes",toJSON (concatMap hex (BS.unpack bytes)))
  LitFloatBits bits -> numeric "float-bits" bits
  LitDoubleBits bits -> numeric "double-bits" bits
  LitNullAddr -> numeric "null-addr" (0::Int)
  LitRubbish proof -> (String "rubbish",primRep proof)
  LitFunctionAddr symbol -> (String "function-addr",str symbol)
  LitDataAddr symbol -> (String "data-addr",str symbol)
  where
    numeric kind number = (String kind,toJSON (show number))
    hex byte = let value' = showHex byte "" in replicate (2-length value') '0' ++ value'

constructor :: Constructor -> Value
constructor value = object $ ["id" .= str (constructorId value),"name" .= str (constructorId value),
  "arity" .= constructorArity value,"tag" .= constructorTag value,
  "kind" .= tagName ["boxed","unboxed-tuple","unboxed-sum","newtype"] (constructorKind value),
  "strictFields" .= constructorStrictFields value,"fieldLifted" .= arr (nullable toJSON) (constructorFieldLifted value),
  "fieldReps" .= arr (nullable (arr primRep)) (constructorFieldReps value),"fieldTypes" .= arr rep (constructorFieldTypes value)]
  ++ p "sumArity" toJSON (constructorSumArity value) ++ p "enumFamily" enumFamily (constructorEnumFamily value)
  ++ p "dataToTagFamily" tagFamily (constructorTagFamily value)

moduleJSON :: Facts -> [Binding] -> Value
moduleJSON facts bindings = object $ ["schema" .= factsSchema facts,"ghc" .= str (factsGhc facts),
  "unit" .= str (factsUnit facts),"module" .= str (factsModule facts),"boundary" .= str (factsBoundary facts),
  "constructors" .= arr constructor (factsConstructors facts),"bindings" .= arr binding bindings]
  ++ p "providedModules" (arr str) (factsProvidedModules facts) ++ p "targetLayout" targetLayout (factsTargetLayout facts)
  ++ p "foreign" foreignArtifacts (factsForeign facts) ++ p "foreignExceptionBridge" exceptionBridge (factsExceptionBridge facts)
  ++ p "foreignExceptionBridgeUnit" str (factsExceptionBridgeUnit facts)
  ++ concat (zipWith (\key -> p (Key.fromText (Text.decodeUtf8 key)) (const (error "Unmapped known provenance")))
       pendingProvenanceNames (factsPendingProvenance facts))

targetLayout :: TargetLayout -> Value
targetLayout value = object ["format" .= String "thc-target-layout","schema" .= targetDocumentSchema value,
  "compiler" .= object ["id" .= str (targetCompilerId value),"abi" .= str (targetCompilerAbi value),
    "platform" .= str (targetCompilerPlatform value),"way" .= str (targetCompilerWay value)],
  "layout" .= object (["schema" .= targetLayoutSchema value,"profiled" .= targetProfiled value,
    "wordBytes" .= targetWordBytes value,"endianness" .= tagName ["little","big"] (targetEndianness value),
    "targetPlatform" .= str (targetPlatform value),"tablesNextToCode" .= targetTablesNextToCode value]
    ++ zipWith (\key n -> (Key.fromText (Text.decodeUtf8 key),toJSON n)) targetNumberNames (targetNumbers value))]

foreignArtifacts :: ForeignArtifacts -> Value
foreignArtifacts (ForeignArtifacts schema execution stubs files) = object $
  ["schema" .= schema,"execution" .= str execution,"files" .= arr file files] ++ p "stubs" stub stubs
  where
    stub (Stubs header source initializers finalizers) = object ["header" .= str header,"source" .= str source,
      "initializers" .= arr label initializers,"finalizers" .= arr label finalizers]
    label (Label initializer unit moduleName name) = object ["isInitializer" .= initializer,"unit" .= str unit,
      "module" .= str moduleName,"name" .= str name]
    file (ForeignFile language source extension) = object ["language" .= str language,"source" .= str source,"extension" .= str extension]

exceptionBridge :: ExceptionBridge -> Value
exceptionBridge (ExceptionBridge schema unit moduleName box project payload exception) = object
  ["schema" .= schema,"unit" .= str unit,"module" .= str moduleName,"box" .= str box,"project" .= str project,
   "payloadType" .= str payload,"exceptionType" .= str exception]
