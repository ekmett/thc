-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Compact.JSON
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1; existing flat Core JSON schema
--
-- Explicit debug-free conversion of the reference JSON records. Unknown
-- semantic fields fail conversion; pretty diagnostics are not executable data.
-- This entry point deliberately requires a caller to choose omitted debug data.
module THC.Compact.JSON (parseModuleWithoutDebug) where

import Control.Monad (unless, forM, when)
import Control.Monad.Trans.Class (lift)
import Control.Monad.Trans.State.Strict (StateT, evalStateT, get, put)
import Data.Aeson hiding (object, pairs)
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import Data.Aeson.Types (Parser, parseEither)
import qualified Data.ByteString as BS
import Data.Char (digitToInt, isHexDigit)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import qualified Data.Vector as V
import Data.Word (Word64)
import GHC.Float (castFloatToWord32, castDoubleToWord64)
import Text.Read (readMaybe)
import THC.Compact.Core
import THC.Compact.Facts

type Locals = Map.Map Text.Text Word64
type Convert = StateT Word64 Parser

parseModuleWithoutDebug :: Value -> Either String (Facts,[Binding])
parseModuleWithoutDebug = parseEither $ withObject "Core module" $ \fields -> do
  checked fields (["schema","ghc","unit","module","boundary","providedModules","targetLayout",
    "constructors","bindings","foreign","foreignExceptionBridge","foreignExceptionBridgeUnit",
    "sourceCore","rules","groups","lowering","sourceFiles","sourceSpans"] ++ map bytesKey pendingProvenanceNames)
  facts <- Facts <$> fields .: "schema" <*> bytesAt fields "ghc" <*> bytesAt fields "unit"
    <*> bytesAt fields "module" <*> bytesAt fields "boundary" <*> optional fields "providedModules" (array bytes)
    <*> optional fields "targetLayout" targetLayout <*> (fields .: "constructors" >>= array constructor)
    <*> optional fields "foreign" foreignArtifacts <*> optional fields "foreignExceptionBridge" exceptionBridge
    <*> optional fields "foreignExceptionBridgeUnit" bytes
    <*> mapM (\key -> optional fields (bytesKey key) (const (fail ("Unmapped compact provenance field: " ++ show key)))) pendingProvenanceNames
  values <- fields .: "bindings" >>= array pure
  bindings <- mapM (\value -> evalStateT (binding Map.empty Nothing value) 0) values
  pure (facts,bindings)

checked :: Object -> [Key.Key] -> Parser ()
checked fields allowed = unless (all (`elem` allowed) (KM.keys fields)) $
  fail ("Unmapped Core fields: " ++ show (filter (`notElem` allowed) (KM.keys fields)))

bytesKey :: BS.ByteString -> Key.Key
bytesKey = Key.fromText . Text.decodeUtf8

bytes :: Value -> Parser BS.ByteString
bytes = withText "semantic UTF8 string" (pure . Text.encodeUtf8)

bytesAt :: Object -> Key.Key -> Parser BS.ByteString
bytesAt fields key = fields .: key >>= bytes

array :: (Value -> Parser a) -> Value -> Parser [a]
array parser = withArray "Core array" (mapM parser . V.toList)

optional :: Object -> Key.Key -> (Value -> Parser a) -> Parser (Presence a)
optional fields key parser = case KM.lookup key fields of
  Nothing -> pure Missing
  Just Null -> pure Unknown
  Just value -> Known <$> parser value

nullable :: (Value -> Parser a) -> Value -> Parser (Presence a)
nullable _ Null = pure Unknown
nullable parser value = Known <$> parser value

choice :: [(Text.Text,a)] -> Value -> Parser a
choice choices = withText "typed Core discriminator" $ \key ->
  maybe (fail ("Unknown Core discriminator: " ++ show key)) pure (lookup key choices)

entryType :: Object -> Parser EntryType
entryType fields = case KM.lookup "type" fields of
  Just (String "IO ()") -> pure IOUnit
  Just (String "State# RealWorld") -> pure StateRealWorld
  Just (String _) -> pure OtherEntry
  Nothing -> pure OtherEntry
  _ -> fail "Malformed Core type string"

rep :: Value -> Parser Rep
rep = withObject "representation proof" $ \fields -> do
  checked fields ["kind","primReps","vector","aggregate","components","alternatives","tagSlot","alternativeSlots","evaluated"]
  components <- optional fields "components" (array rep)
  alternatives <- optional fields "alternatives" (array rep)
  layout <- Shape <$> (fields .: "kind" >>= choice (zip
    ["long","float","double","address","void","data","closure","object","vector","unknown"] [minBound..maxBound]))
    <*> optional fields "primReps" (array primRep) <*> optional fields "vector" vector
    <*> optional fields "aggregate" (choice [("unboxed-tuple",TupleAggregate),("unboxed-sum",SumAggregate)])
    <*> pure (mapPresence repShape components) <*> pure (mapPresence repShape alternatives)
    <*> optional fields "tagSlot" parseJSON <*> optional fields "alternativeSlots" (array (array parseJSON))
  state <- Evaluation <$> optional fields "evaluated" parseJSON
    <*> pure (map repState (known components ++ known alternatives))
  pure (Rep layout state)
  where
    repShape (Rep layout _) = layout
    repState (Rep _ state) = state
    known (Known values) = values
    known _ = []

mapPresence :: (a -> b) -> Presence [a] -> Presence [b]
mapPresence _ Missing = Missing
mapPresence _ Unknown = Unknown
mapPresence action (Known values) = Known (map action values)

vector :: Value -> Parser Vector
vector = withObject "vector proof" $ \fields -> do
  checked fields ["lanes","element"]
  Vector <$> fields .: "lanes" <*> (fields .: "element" >>= element)

element :: Value -> Parser Element
element = choice (zip ["Int8ElemRep","Int16ElemRep","Int32ElemRep","Int64ElemRep",
  "Word8ElemRep","Word16ElemRep","Word32ElemRep","Word64ElemRep","FloatElemRep","DoubleElemRep"] [minBound..maxBound])

primRep :: Value -> Parser PrimRep
primRep value = withText "GHC PrimRep" parse value
  where
    parse name = case lookup name fixed of
      Just found -> pure found
      Nothing -> case Text.words name of
        ["VecRep",lanes,kind] -> case readMaybe (Text.unpack lanes) of
          Just count | count >= (0::Integer) && count <= toInteger (maxBound :: Word64) ->
            VecRep . Vector (fromInteger count) <$> element (String kind)
          Just _ -> fail "Vector lane count exceeds uint64"
          Nothing -> fail "Invalid vector lane count"
        _ -> fail ("Unknown GHC PrimRep: " ++ show name)
    fixed = zip ["IntRep","WordRep","Int8Rep","Int16Rep","Int32Rep","Int64Rep",
      "Word8Rep","Word16Rep","Word32Rep","Word64Rep","FloatRep","DoubleRep","AddrRep",
      "BoxedRep Nothing","BoxedRep (Just Lifted)","BoxedRep (Just Unlifted)"]
      [IntRep,WordRep,Int8Rep,Int16Rep,Int32Rep,Int64Rep,Word8Rep,Word16Rep,Word32Rep,Word64Rep,
       FloatRep,DoubleRep,AddrRep,BoxedUnknown,BoxedLifted,BoxedUnlifted]

idInfo :: Value -> Parser IdInfo
idInfo = withObject "IdInfo" $ \fields -> do
  checked fields ["joinArity","cbvEligible","cbvMarks","callArity","demand","strictness","cpr","occurrence","oneShot","inline"]
  IdInfo <$> optional fields "joinArity" parseJSON <*> optional fields "cbvEligible" parseJSON
    <*> optional fields "cbvMarks" (array parseJSON)

allocate :: Locals -> [Text.Text] -> Convert Locals
allocate scope names = do
  unless (Set.size (Set.fromList names) == length names) (fail "Duplicate lexical declaration in one group")
  pairs <- forM names $ \name -> do
    ordinal <- get
    when (ordinal == maxBound) (fail "Too many lexical declarations")
    put (ordinal+1)
    pure (name,ordinal)
  pure (Map.union (Map.fromList pairs) scope)

object :: Value -> Parser Object
object = withObject "Core record" pure

binder :: Locals -> Value -> Parser Binder
binder scope value = do
  fields <- object value
  checked fields ["id","name","type","lifted","coercion","rep","info","source"]
  key <- fields .: "id"
  ordinal <- maybe (fail "Binder has no lexical declaration") pure (Map.lookup key scope)
  Binder ordinal <$> entryType fields <*> optional fields "lifted" parseJSON
    <*> optional fields "coercion" parseJSON <*> optional fields "rep" rep <*> optional fields "info" idInfo

binding :: Locals -> Maybe Locals -> Value -> Convert Binding
binding rhsScope declared value = do
  fields <- lift (object value)
  lift (checked fields ["id","name","type","lifted","arity","expr","rep","info","entryStrict",
    "entryStrictSource","joinValueArity","joinResultRep","source"])
  key <- lift (fields .: "id")
  identity <- case declared of
    Nothing -> pure (Global (Text.encodeUtf8 key))
    Just scope -> maybe (fail "Local binding has no lexical ordinal") (pure . Local) (Map.lookup key scope)
  Binding identity <$> lift (entryType fields) <*> lift (optional fields "lifted" parseJSON)
    <*> lift (fields .: "arity") <*> lift (optional fields "rep" rep) <*> lift (optional fields "info" idInfo)
    <*> lift (optional fields "entryStrict" (array parseJSON)) <*> lift (optional fields "entryStrictSource" bytes)
    <*> lift (optional fields "joinValueArity" parseJSON) <*> lift (optional fields "joinResultRep" rep)
    <*> (lift (fields .: "expr") >>= expr rhsScope)

expr :: Locals -> Value -> Convert Expr
expr scope value = do
  items <- lift (array pure value)
  case items of
    [String "var",String key,m] -> Var <$> lift (meta m) <*> pure
      (maybe (Global (Text.encodeUtf8 key)) Local (Map.lookup key scope))
    [String "prim",name,m] -> Prim <$> lift (meta m) <*> lift (bytes name)
    [String "lit",kind,payload,m] -> Lit <$> lift (meta m) <*> lift (literal kind payload)
    [String "con",name,arity,m] -> Con <$> lift (meta m) <*> lift (bytes name) <*> lift (parseJSON arity)
    [String "void",m] -> Void <$> lift (meta m)
    [String "lam",parameters,body,m] -> do
      values <- lift (array pure parameters)
      names <- lift (mapM (\v -> object v >>= (.: "id")) values)
      inner <- allocate scope names
      Lam <$> lift (meta m) <*> lift (mapM (binder inner) values) <*> expr inner body
    [String "app",function,arguments,lifted,hnf,speculate,m] -> App <$> lift (meta m)
      <*> expr scope function <*> (lift (array pure arguments) >>= mapM (expr scope))
      <*> lift (array (nullable parseJSON) lifted) <*> lift (parseJSON hnf) <*> lift (parseJSON speculate)
    [String "let",recursive,bindings,body,m] -> do
      recursive' <- lift (parseJSON recursive)
      values <- lift (array pure bindings)
      unless (recursive' || length values == 1) (fail "Nonrecursive Core let must contain one binding")
      names <- lift (mapM (\v -> object v >>= (.: "id")) values)
      inner <- allocate scope names
      Let <$> lift (meta m) <*> pure recursive'
        <*> mapM (binding (if recursive' then inner else scope) (Just inner)) values <*> expr inner body
    [String "case",scrutinee,String name,alternatives,m] -> do
      metadata <- lift (object m)
      scrutinee' <- expr scope scrutinee
      inner <- allocate scope [name]
      ordinal <- maybe (fail "Missing case binder") pure (Map.lookup name inner)
      information <- lift (optional metadata "binder" (binder inner))
      case information of
        Known record -> unless (binderOrdinal record == ordinal) (fail "Case binder ID and metadata disagree")
        _ -> pure ()
      Case <$> lift (meta (Object (KM.delete "binder" metadata))) <*> pure scrutinee' <*> pure ordinal
        <*> pure information <*> (lift (array pure alternatives) >>= mapM (alternative inner))
    _ -> fail "Unsupported or malformed Core expression"

alternative :: Locals -> Value -> Convert Alternative
alternative scope value = do
  items <- lift (array pure value)
  case items of
    [String kind,discriminator,ids,body,metadata] -> do
      names <- lift (array parseJSON ids)
      fields <- lift (object metadata)
      lift (checked fields ["binders"])
      binders <- lift (fields .: "binders" >>= array pure)
      actual <- lift (mapM (\v -> object v >>= (.: "id")) binders)
      unless (names == actual) (fail "Alternative IDs and original binder metadata disagree")
      inner <- allocate scope names
      parameters <- lift (mapM (binder inner) binders)
      case kind of
        "default" | discriminator == Null -> DefaultAlt parameters <$> expr inner body
        "data" -> DataAlt <$> lift (bytes discriminator) <*> pure parameters <*> expr inner body
        "lit" -> do
          literalParts <- lift (array pure discriminator)
          lit <- case literalParts of [k,v] -> lift (literal k v); _ -> fail "Malformed literal alternative"
          LiteralAlt lit parameters <$> expr inner body
        _ -> fail "Unknown Core alternative"
    _ -> fail "Malformed Core alternative"

meta :: Value -> Parser Meta
meta = withObject "expression metadata" $ \fields -> do
  checked fields ["rep","resultRep","entryStrict","entryStrictSource","callDemand","foreignCall",
    "exceptionPayload","enumFamily","dataToTagFamily","unsafeEqualityCase","source","sourceNotes"]
  Meta <$> optional fields "rep" rep <*> optional fields "resultRep" rep
    <*> optional fields "entryStrict" (array parseJSON) <*> optional fields "entryStrictSource" bytes
    <*> optional fields "callDemand" callDemand <*> optional fields "foreignCall" foreignCall
    <*> optional fields "exceptionPayload" exceptionPayload <*> optional fields "enumFamily" enumFamily
    <*> optional fields "dataToTagFamily" tagFamily <*> optional fields "unsafeEqualityCase" bytes

callDemand :: Value -> Parser CallDemand
callDemand = withObject "call demand" $ \fields -> do
  checked fields ["arity","strictArgs"]
  CallDemand <$> fields .: "arity" <*> (fields .: "strictArgs" >>= array parseJSON)

exceptionPayload :: Value -> Parser ExceptionPayload
exceptionPayload = withObject "exception payload" $ \fields -> do
  checked fields ["schema","type"]
  ExceptionPayload <$> fields .: "schema" <*> bytesAt fields "type"

enumFamily :: Value -> Parser EnumFamily
enumFamily = withObject "nominal enum family" $ \fields -> do
  checked fields ["typeConstructor","constructors"]
  EnumFamily <$> bytesAt fields "typeConstructor" <*> (fields .: "constructors" >>= array bytes)

tagFamily :: Value -> Parser TagFamily
tagFamily = withObject "nominal data-to-tag family" $ \fields -> do
  checked fields ["typeConstructor","constructors","smallFamilyLimit","smallFamily"]
  family <- EnumFamily <$> bytesAt fields "typeConstructor" <*> (fields .: "constructors" >>= array bytes)
  TagFamily family <$> fields .: "smallFamilyLimit" <*> fields .: "smallFamily"

foreignCall :: Value -> Parser ForeignCall
foreignCall = withObject "foreign call" $ \fields -> do
  checked fields ["schema","target","convention","safety","arity","suppliedArity","argumentReps","resultRep","intrinsic","javascriptSource"]
  ForeignCall <$> fields .: "schema" <*> (fields .: "target" >>= target)
    <*> (fields .: "convention" >>= choice (zip ["ccall","capi","stdcall","prim","javascript"] [minBound..maxBound]))
    <*> (fields .: "safety" >>= choice (zip ["unsafe","safe","interruptible"] [minBound..maxBound]))
    <*> fields .: "arity" <*> fields .: "suppliedArity" <*> (fields .: "argumentReps" >>= array rep)
    <*> (fields .: "resultRep" >>= rep) <*> optional fields "intrinsic" bytes <*> optional fields "javascriptSource" bytes
  where
    target = withObject "foreign target" $ \fields -> do
      kind <- fields .: "kind" :: Parser Text.Text
      case kind of
        "static" -> checked fields ["kind","symbol","unit","isFunction"] >>
          (StaticTarget <$> bytesAt fields "symbol" <*> optional fields "unit" bytes <*> fields .: "isFunction")
        "dynamic" -> checked fields ["kind"] >> pure DynamicTarget
        _ -> fail "Unknown foreign target"

literal :: Value -> Value -> Parser Literal
literal (String kind) (String payload) = case kind of
  "int" -> LitInt <$> boundedNumber; "word" -> LitWord <$> boundedNumber
  "int8" -> LitInt8 <$> boundedNumber; "int16" -> LitInt16 <$> boundedNumber; "int32" -> LitInt32 <$> boundedNumber; "int64" -> LitInt64 <$> boundedNumber
  "word8" -> LitWord8 <$> boundedNumber; "word16" -> LitWord16 <$> boundedNumber; "word32" -> LitWord32 <$> boundedNumber; "word64" -> LitWord64 <$> boundedNumber
  "bignat" -> LitBigNat <$> numeric
  "char" -> LitChar <$> boundedNumber
  "string-bytes" -> LitBytes . BS.pack <$> hex (Text.unpack payload)
  "float" -> LitFloatBits . castFloatToWord32 <$> numeric
  "double" -> LitDoubleBits . castDoubleToWord64 <$> numeric
  "float-bits" -> LitFloatBits <$> boundedNumber
  "double-bits" -> LitDoubleBits <$> boundedNumber
  "null-addr" | payload == "0" -> pure LitNullAddr
  "rubbish" -> LitRubbish <$> primRep (String payload)
  "function-addr" -> pure (LitFunctionAddr (Text.encodeUtf8 payload))
  "data-addr" -> pure (LitDataAddr (Text.encodeUtf8 payload))
  _ -> fail ("Unsupported Core literal: " ++ show kind)
  where
    numeric :: Read a => Parser a
    numeric = maybe (fail "Malformed numeric literal") pure (readMaybe (Text.unpack payload))
    boundedNumber :: (Integral a, Bounded a) => Parser a
    boundedNumber = do
      n <- numeric :: Parser Integer
      let result = fromInteger n
      unless (toInteger (minBound `asTypeOf` result) <= n && n <= toInteger (maxBound `asTypeOf` result))
        (fail "Numeric literal exceeds its semantic integer width")
      pure result
    hex [] = pure []
    hex (a:b:rest) | isHexDigit a && isHexDigit b =
      (fromIntegral (16*digitToInt a+digitToInt b) :) <$> hex rest
    hex _ = fail "Malformed raw-byte literal hex"
literal _ _ = fail "Malformed Core literal record"

constructor :: Value -> Parser Constructor
constructor = withObject "constructor" $ \fields -> do
  checked fields ["id","name","arity","tag","kind","type","strictFields","fieldLifted","fieldReps","fieldTypes","sumArity","enumFamily","dataToTagFamily"]
  Constructor <$> bytesAt fields "id" <*> fields .: "arity" <*> fields .: "tag"
    <*> (fields .: "kind" >>= choice (zip ["boxed","unboxed-tuple","unboxed-sum","newtype"] [minBound..maxBound]))
    <*> (fields .: "strictFields" >>= array parseJSON) <*> (fields .: "fieldLifted" >>= array (nullable parseJSON))
    <*> (fields .: "fieldReps" >>= array (nullable (array primRep))) <*> (fields .: "fieldTypes" >>= array rep)
    <*> optional fields "sumArity" parseJSON <*> optional fields "enumFamily" enumFamily <*> optional fields "dataToTagFamily" tagFamily

targetLayout :: Value -> Parser TargetLayout
targetLayout = withObject "target layout document" $ \fields -> do
  checked fields ["format","schema","compiler","layout"]
  format <- fields .: "format" :: Parser Text.Text
  unless (format == "thc-target-layout") (fail "Unknown target layout document")
  compiler <- fields .: "compiler" >>= object
  checked compiler ["id","abi","platform","way"]
  layout <- fields .: "layout" >>= object
  checked layout (["schema","profiled","wordBytes","endianness","targetPlatform","tablesNextToCode"] ++ map bytesKey targetNumberNames)
  TargetLayout <$> fields .: "schema" <*> bytesAt compiler "id" <*> bytesAt compiler "abi"
    <*> bytesAt compiler "platform" <*> bytesAt compiler "way" <*> layout .: "schema"
    <*> layout .: "profiled" <*> layout .: "wordBytes"
    <*> (layout .: "endianness" >>= choice [("little",LittleEndian),("big",BigEndian)])
    <*> bytesAt layout "targetPlatform" <*> layout .: "tablesNextToCode"
    <*> mapM (\key -> layout .: bytesKey key) targetNumberNames

foreignArtifacts :: Value -> Parser ForeignArtifacts
foreignArtifacts = withObject "foreign artifacts" $ \fields -> do
  checked fields ["schema","execution","stubs","files"]
  ForeignArtifacts <$> fields .: "schema" <*> bytesAt fields "execution"
    <*> optional fields "stubs" stubs <*> (fields .: "files" >>= array file)
  where
    stubs = withObject "foreign stubs" $ \fields -> do
      checked fields ["header","source","initializers","finalizers"]
      Stubs <$> bytesAt fields "header" <*> bytesAt fields "source"
        <*> (fields .: "initializers" >>= array label) <*> (fields .: "finalizers" >>= array label)
    label = withObject "foreign label" $ \fields -> do
      checked fields ["isInitializer","unit","module","name"]
      Label <$> fields .: "isInitializer" <*> bytesAt fields "unit" <*> bytesAt fields "module" <*> bytesAt fields "name"
    file = withObject "foreign file" $ \fields -> do
      checked fields ["language","source","extension"]
      ForeignFile <$> bytesAt fields "language" <*> bytesAt fields "source" <*> bytesAt fields "extension"

exceptionBridge :: Value -> Parser ExceptionBridge
exceptionBridge = withObject "foreign exception bridge" $ \fields -> do
  checked fields ["schema","unit","module","box","project","payloadType","exceptionType"]
  ExceptionBridge <$> fields .: "schema" <*> bytesAt fields "unit" <*> bytesAt fields "module"
    <*> bytesAt fields "box" <*> bytesAt fields "project" <*> bytesAt fields "payloadType" <*> bytesAt fields "exceptionType"
