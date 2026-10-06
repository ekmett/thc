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
-- Conversion of reference JSON into independent semantic and display records.
-- Unknown semantic fields fail conversion; pretty diagnostics are not executable
-- data. Omitting original display annotations requires an explicit entry point.
module THC.Compact.JSON (parseModuleWithoutDebug, parseModuleWithDebug, parseModuleFacts) where

import Control.Monad (unless, forM, when)
import Control.Monad.Trans.Class (lift)
import Control.Monad.Trans.State.Strict (StateT, runStateT, get, put, modify')
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
import THC.Compact.Annotations
import THC.Compact.Debug
import THC.Compact.Facts
import THC.Compact.Types.JSON ()

type Locals = Map.Map Text.Text Word64
data ConvertState = ConvertState !Word64 !Bool ![Annotation]
type Convert = StateT ConvertState Parser

parseModuleWithoutDebug :: Value -> Either String (Facts,[Binding])
parseModuleWithoutDebug value = do
  (facts,bindings) <- parseEither (moduleRecords False) value
  pure (facts,map fst bindings)

-- | Keep original names and source notes in a separate ordered annotation
-- stream. They do not enter semantic records or affect lexical resolution.
parseModuleWithDebug :: Value -> Either String (Facts,[(Binding,[Annotation])],ModuleAnnotations)
parseModuleWithDebug value = parseEither (\input -> do
  (facts,bindings) <- moduleRecords True input
  sources <- sourceCatalog input
  fields <- object input
  constructors <- fields .: "constructors" >>= array object
  names <- forM (zip [0..] constructors) $ \(index,record) -> case KM.lookup "name" record of
    Nothing -> pure []
    Just Null -> pure []
    Just name -> do
      original <- bytes name
      pure [(index,original)]
  pure (facts,bindings,ModuleAnnotations sources (concat names))) value

moduleRecords :: Bool -> Value -> Parser (Facts,[(Binding,[Annotation])])
moduleRecords debug = withObject "Core module" $ \fields -> do
  facts <- moduleFacts fields
  values <- fields .: "bindings" >>= array pure
  bindings <- forM values $ \value -> do
    (record,ConvertState _ _ annotations) <- runStateT (binding Map.empty Nothing value) (ConvertState 0 debug [])
    pure (record,reverse annotations)
  pure (facts,bindings)

-- | Header amendments do not need to traverse executable expressions.
parseModuleFacts :: Value -> Either String Facts
parseModuleFacts = parseEither (withObject "Core module" moduleFacts)

moduleFacts :: Object -> Parser Facts
moduleFacts fields = do
  checked fields (["schema","ghc","unit","module","boundary","providedModules","targetLayout",
    "constructors","bindings","foreign","foreignExceptionBridge","foreignExceptionBridgeUnit",
    "sourceCore","rules","groups","lowering","sourceFiles","sourceSpans",
    "roots","sourceModules","missingDefinitions","backendPolicy","recoveryFacts"] ++ map bytesKey pendingProvenanceNames)
  Facts <$> fields .: "schema" <*> bytesAt fields "ghc" <*> bytesAt fields "unit"
    <*> bytesAt fields "module" <*> bytesAt fields "boundary" <*> optional fields "providedModules" (array bytes)
    <*> optional fields "targetLayout" targetLayout <*> (fields .: "constructors" >>= array constructor)
    <*> optional fields "foreign" foreignArtifacts <*> optional fields "foreignExceptionBridge" exceptionBridge
    <*> optional fields "foreignExceptionBridgeUnit" bytes
    <*> mapM (\(slot,key) -> optional fields (bytesKey key) (provenance slot)) (zip [0..] pendingProvenanceNames)
    <*> closureProvenance fields
    <*> case KM.lookup "backendPolicy" fields of
      Nothing -> pure Nothing
      Just value -> do
        policy <- object value
        checked policy ["default","bindings"]
        def <- traverse backend (KM.lookup "default" policy)
        entries <- policy .: "bindings" >>= object
        bindings <- mapM (\(key,item) -> (,) (Text.encodeUtf8 (Key.toText key)) <$> backend item)
          (KM.toList entries)
        let ordered = Map.toAscList (Map.fromList bindings)
        pure (if def == Nothing && null ordered then Nothing else Just (BackendPolicy def ordered))
    <*> fields .:? "recoveryFacts"
  where
    backend = choice [("ast",AstBackend),("bytecode",BytecodeBackend)]

closureProvenance :: Object -> Parser (Maybe ClosureProvenance)
closureProvenance fields = do
  roots <- optional fields "roots" (array bytes)
  modules <- optional fields "sourceModules" (array bytes)
  missing <- optional fields "missingDefinitions" (array missingDefinition)
  records <- fields .: "bindings" >>= array object
  origins <- forM [record | record <- records, any (`KM.member` record) ["origin","originModule"]] $ \record ->
    BindingOrigin <$> bytesAt record "id" <*> optional record "origin" bytes <*> optional record "originModule" bytes
  pure $ if roots == Missing && modules == Missing && missing == Missing && null origins then Nothing
    else Just (ClosureProvenance roots modules missing origins)
  where
    missingDefinition = withObject "missing interface definition" $ \record -> do
      checked record ["id","type","reason"]
      MissingDefinition <$> bytesAt record "id" <*> bytesAt record "type" <*> bytesAt record "reason"

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
    ConvertState ordinal debug annotations <- get
    when (ordinal == maxBound) (fail "Too many lexical declarations")
    put (ConvertState (ordinal+1) debug annotations)
    pure (name,ordinal)
  pure (Map.union (Map.fromList pairs) scope)

object :: Value -> Parser Object
object = withObject "Core record" pure

annotate :: RecordKind -> Bool -> Object -> Convert ()
annotate kind named fields = do
  ConvertState _ enabled _ <- get
  when enabled $ do
    name <- if named then lift (maybeBytes "name") else pure Nothing
    source <- lift (maybeBytes "source")
    notes <- lift $ case KM.lookup "sourceNotes" fields of
      Nothing -> pure []
      Just Null -> pure []
      Just value -> array bytes value
    modify' (\(ConvertState ordinal debug annotations) ->
      ConvertState ordinal debug (Annotation kind name source notes:annotations))
  where
    maybeBytes key = case KM.lookup key fields of
      Nothing -> pure Nothing
      Just Null -> pure Nothing
      Just value -> Just <$> bytes value

sourceCatalog :: Value -> Parser SourceCatalog
sourceCatalog = withObject "Core source catalog" $ \fields -> do
  files <- defaultArray fields "sourceFiles" >>= mapM (withObject "source file" $ \file -> do
    checked file ["id","path","content"]
    identifier <- bytesAt file "id"
    value <- SourceFile identifier <$> bytesAt file "path" <*> optional file "content" bytes
    pure (identifier,value))
  fileMap <- unique "source file" files
  spans <- defaultArray fields "sourceSpans" >>= mapM (withObject "source span" $ \spanFields -> do
    checked spanFields ["id","file","label","startLine","startColumn","endLine","endColumn","charIndex","charLength"]
    identifier <- bytesAt spanFields "id"
    fileId <- bytesAt spanFields "file"
    file <- maybe (fail "Original source span references missing file") pure (Map.lookup fileId fileMap)
    position <- SourcePosition identifier <$> optional spanFields "label" bytes
      <*> spanFields .: "startLine" <*> spanFields .: "startColumn"
      <*> spanFields .: "endLine" <*> spanFields .: "endColumn"
      <*> optional spanFields "charIndex" parseJSON <*> optional spanFields "charLength" parseJSON
    pure (identifier,(file,position)))
  unique "source span" spans
  where
    defaultArray fields key = case KM.lookup key fields of
      Nothing -> pure []
      Just Null -> pure []
      Just value -> array pure value
    unique role entries = do
      let result = Map.fromList entries
      unless (Map.size result == length entries) (fail ("Duplicate original " ++ role ++ " identity"))
      pure result

binder :: Locals -> Value -> Convert Binder
binder scope value = do
  fields <- lift (object value)
  lift (checked fields ["id","name","type","lifted","coercion","rep","info","source"])
  key <- lift (fields .: "id")
  ordinal <- maybe (fail "Binder has no lexical declaration") pure (Map.lookup key scope)
  annotate (BinderRecord ordinal) True fields
  lift $ Binder ordinal <$> entryType fields <*> optional fields "lifted" parseJSON
    <*> optional fields "coercion" parseJSON <*> optional fields "rep" rep <*> optional fields "info" idInfo

binding :: Locals -> Maybe Locals -> Value -> Convert Binding
binding rhsScope declared value = do
  fields <- lift (object value)
  lift (checked fields ["id","name","type","lifted","arity","expr","rep","info","entryStrict",
    "entryStrictSource","joinValueArity","joinResultRep","callable","hostSignature","source","origin","originModule"])
  key <- lift (fields .: "id")
  identity <- case declared of
    Nothing -> pure (Global (Text.encodeUtf8 key))
    Just scope -> maybe (fail "Local binding has no lexical ordinal") (pure . Local) (Map.lookup key scope)
  annotate (BindingRecord identity) True fields
  Binding identity <$> lift (entryType fields) <*> lift (optional fields "lifted" parseJSON)
    <*> lift (fields .: "arity") <*> lift (optional fields "rep" rep) <*> lift (optional fields "info" idInfo)
    <*> lift (optional fields "entryStrict" (array parseJSON)) <*> lift (optional fields "entryStrictSource" bytes)
    <*> lift (optional fields "joinValueArity" parseJSON) <*> lift (optional fields "joinResultRep" rep)
    <*> lift (optional fields "callable" parseJSON)
    <*> lift (optional fields "hostSignature" hostSignature)
    <*> (lift (fields .: "expr") >>= expr rhsScope)

hostSignature :: Value -> Parser HostSignature
hostSignature = withObject "declared host signature" $ \fields -> do
  checked fields ["inputs","result"]
  HostSignature <$> (fields .: "inputs" >>= array hostType) <*> (fields .: "result" >>= hostType)
  where
    hostType = withObject "declared host type" $ \fields -> do
      checked fields ["rep","carriers"]
      HostType <$> (fields .: "rep" >>= rep) <*> (fields .: "carriers" >>= array carrier)
    carrier Null = pure HostPlain
    carrier value = choice [("object",HostObject),("interop-library",HostInteropLibrary)] value

expr :: Locals -> Value -> Convert Expr
expr scope value = do
  items <- lift (array pure value)
  case reverse items of
    Object metadata : _ -> annotate ExpressionRecord False metadata
    _ -> fail "Core expression lacks metadata"
  case items of
    [String "var",String key,m] -> Var <$> lift (meta m) <*> pure
      (maybe (Global (Text.encodeUtf8 key)) Local (Map.lookup key scope))
    [String "prim",name,m] -> Prim <$> lift (meta m) <*> lift (bytes name)
    [String "lit",kind,payload,m] -> Lit <$> lift (meta m) <*> lift (literal kind payload)
    [String "con",name,arity,m] -> Con <$> lift (meta m) <*> lift (bytes name) <*> lift (parseJSON arity)
    [String "void",m] -> Void <$> lift (meta m)
    [String "unsupported",diagnostic,m] -> Unsupported <$> lift (meta m) <*> lift (bytes diagnostic)
    [String "lam",parameters,body,m] -> do
      values <- lift (array pure parameters)
      names <- lift (mapM (\v -> object v >>= (.: "id")) values)
      inner <- allocate scope names
      Lam <$> lift (meta m) <*> mapM (binder inner) values <*> expr inner body
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
      information <- case KM.lookup "binder" metadata of
        Nothing -> pure Missing
        Just Null -> pure Unknown
        Just v -> Known <$> binder inner v
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
      parameters <- mapM (binder inner) binders
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
  checked fields ["schema","target","convention","safety","arity","suppliedArity","argumentReps","resultRep","intrinsic","javascriptSource","argumentTypes"]
  schema <- fields .: "schema"
  types <- optional fields "argumentTypes" (array (\value -> case value of
    Null -> pure Unknown
    String "ByteArray#" -> pure (Known "ByteArray#")
    String "MutableByteArray#" -> pure (Known "MutableByteArray#")
    _ -> fail "Foreign argument type must be a primitive byte array or null"))
  arity <- fields .: "arity"
  unless (case (schema,types) of
      (1,Missing) -> True
      (2,Known values) -> fromIntegral (length values) == (arity :: Word64) && any (/= Unknown) values
      _ -> False) (fail "Foreign argument types disagree with schema or arity")
  ForeignCall schema <$> (fields .: "target" >>= target)
    <*> (fields .: "convention" >>= parseConvention)
    <*> (fields .: "safety" >>= parseSafety)
    <*> pure arity <*> fields .: "suppliedArity" <*> (fields .: "argumentReps" >>= array rep)
    <*> (fields .: "resultRep" >>= rep) <*> optional fields "intrinsic" bytes <*> optional fields "javascriptSource" bytes
    <*> pure types
  where
    target = withObject "foreign target" $ \fields -> do
      kind <- fields .: "kind" :: Parser Text.Text
      case kind of
        "static" -> checked fields ["kind","symbol","unit","isFunction"] >>
          (StaticTarget <$> bytesAt fields "symbol" <*> optional fields "unit" bytes <*> fields .: "isFunction")
        "dynamic" -> checked fields ["kind"] >> pure DynamicTarget
        _ -> fail "Unknown foreign target"

parseConvention :: Value -> Parser Convention
parseConvention = choice (zip ["ccall","capi","stdcall","prim","javascript"] [minBound..maxBound])
parseSafety :: Value -> Parser Safety
parseSafety = choice (zip ["unsafe","safe","interruptible"] [minBound..maxBound])

provenance :: Int -> Value -> Parser ModuleProvenance
provenance slot value = case slot of
  0 -> ForeignLinkRecord <$> foreignLink value
  1 -> ImportsRecord <$> importProof value
  2 -> ImportsRecord <$> importProof value
  3 -> ExportsRecord <$> exports value
  4 -> RegistrationRecord <$> registration value
  5 -> ScalarLinkRecord <$> scalarLink value
  6 -> NativeLinkRecord <$> nativeLink value
  7 -> NativeArchiveRecord <$> nativeArchive value
  _ -> fail "Unmapped nonempty compact provenance slot"

foreignLink :: Value -> Parser ForeignLink
foreignLink = withObject "CAPI linked artifact" $ \fields -> do
  checked fields ["schema","format","unit","module","sourceSha256","bitcodeSha256","bitcodeHex",
    "target","symbols","abi","headerHashes"]
  ForeignLink <$> fields .: "schema" <*> bytesAt fields "format" <*> bytesAt fields "unit"
    <*> bytesAt fields "module" <*> bytesAt fields "sourceSha256" <*> bytesAt fields "bitcodeSha256"
    <*> (fields .: "bitcodeHex" >>= hexBytes) <*> bytesAt fields "target"
    <*> (fields .: "symbols" >>= array bytes) <*> (fields .: "abi" >>= array (stringPair "symbol" "kind"))
    <*> optional fields "headerHashes" (array (stringPair "name" "sha256"))

linkPayloadKeys :: [Key.Key]
linkPayloadKeys = ["schema","format","profile","unit","target","componentSha256","bitcodeSha256","bitcodeHex"]

linkPayload :: Object -> Parser LinkPayload
linkPayload fields = LinkPayload <$> fields .: "schema" <*> bytesAt fields "format" <*> bytesAt fields "profile"
  <*> bytesAt fields "unit" <*> bytesAt fields "target" <*> bytesAt fields "componentSha256"
  <*> bytesAt fields "bitcodeSha256" <*> (fields .: "bitcodeHex" >>= hexBytes)

scalarLink :: Value -> Parser ScalarLink
scalarLink = withObject "scalar linked artifact" $ \fields -> do
  checked fields (linkPayloadKeys ++ ["abi"])
  ScalarLink <$> linkPayload fields <*> (fields .: "abi" >>= array entry)
  where entry = withObject "scalar linked ABI" $ \fields -> do
          checked fields ["symbol","entry","arguments","result"]
          ScalarABI <$> bytesAt fields "symbol" <*> bytesAt fields "entry"
            <*> (fields .: "arguments" >>= array bytes) <*> bytesAt fields "result"

stringPair :: Key.Key -> Key.Key -> Value -> Parser (BS.ByteString,BS.ByteString)
stringPair first second = withObject "provenance pair" $ \fields -> do
  checked fields [first,second]
  (,) <$> bytesAt fields first <*> bytesAt fields second

hexBytes :: Value -> Parser BS.ByteString
hexBytes = withText "original artifact hex" $ \value -> do
  let raw = Text.encodeUtf8 value
  unless (even (BS.length raw) && BS.all valid raw) (fail "Malformed or noncanonical original artifact hex")
  pure (BS.unfoldr step raw)
  where
    valid n = (n >= 48 && n <= 57) || (n >= 97 && n <= 102)
    nibble n = if n <= 57 then n-48 else n-87
    step raw | BS.null raw = Nothing
             | otherwise = Just (16*nibble (BS.index raw 0)+nibble (BS.index raw 1),BS.drop 2 raw)

nativeLink :: Value -> Parser NativeLink
nativeLink = withObject "native linked artifact" $ \fields -> do
  schema <- fields .: "schema" :: Parser Word64
  unless (schema `elem` [1,2,3]) (fail "Unsupported native linked schema")
  checked fields (linkPayloadKeys ++ ["abi","buildInputs","nativeLibrary","dataSymbols","exports","dependencies"] ++ ["finalizers" | schema == 2] ++ ["callSeeds" | schema == 3])
  NativeLink <$> linkPayload fields <*> (fields .: "abi" >>= array entry)
    <*> optional fields "buildInputs" nativeBuildInputs <*> optional fields "nativeLibrary" nativeCompanion
    <*> optional fields "dataSymbols" (array bytes)
    <*> (if schema == 2 then fields .: "finalizers" >>= array bytes else pure [])
    <*> (case (KM.lookup "exports" fields,KM.lookup "dependencies" fields) of
      (Nothing,Nothing) -> pure Nothing
      (Just publicSymbols,Just dependencies) -> Just <$> ((,) <$> array bytes publicSymbols <*> array nativeComponent dependencies)
      _ -> fail "Native exports and dependencies must be present together")
    <*> (if schema == 3 then Just <$> (fields .: "callSeeds" >>= array seed) else pure Nothing)
  where entry = withObject "native linked ABI" $ \fields -> do
          checked fields ["symbol","entry","convention","safety","arguments","result"]
          NativeABI <$> bytesAt fields "symbol" <*> bytesAt fields "entry"
            <*> (fields .: "convention" >>= parseConvention) <*> (fields .: "safety" >>= parseSafety)
            <*> (fields .: "arguments" >>= array bytes) <*> bytesAt fields "result"
        seed = withObject "native call seed" $ \fields -> do
          checked fields ["entry","bitcodeHex","bitcodeSha256","providerUnit","providerComponentSha256","providerSymbol"]
          unit <- fields .: "providerUnit"; digest <- fields .: "providerComponentSha256"; symbol <- fields .: "providerSymbol"
          provider <- case (unit,digest,symbol) of
            (Null,Null,Null) -> pure Nothing
            (String _,String _,String _) -> Just <$> ((,,) <$> bytes unit <*> bytes digest <*> bytes symbol)
            _ -> fail "Native call seed provider identity must be complete or null"
          NativeCallSeed <$> bytesAt fields "entry" <*> bytesAt fields "bitcodeSha256"
            <*> (fields .: "bitcodeHex" >>= hexBytes) <*> pure provider

nativeCompanion :: Value -> Parser (BS.ByteString,BS.ByteString)
nativeCompanion = withObject "native library companion" $ \fields -> do
  checked fields ["sha256","hex"]
  (,) <$> bytesAt fields "sha256" <*> (fields .: "hex" >>= hexBytes)

nativeComponent :: Value -> Parser NativeComponent
nativeComponent = withObject "declared native component" $ \fields -> do
  checked fields (linkPayloadKeys ++ ["exports","dependencies","nativeLibrary"])
  unless (KM.lookup "schema" fields == Just (Number 1) &&
          KM.lookup "profile" fields == Just (String "thc-package-native-component-v1"))
    (fail "Unsupported native component descriptor")
  NativeComponent <$> linkPayload fields <*> (fields .: "exports" >>= array bytes)
    <*> (fields .: "dependencies" >>= array nativeComponent) <*> optional fields "nativeLibrary" nativeCompanion

nativeBuildInputs :: Value -> Parser NativeBuildInputs
nativeBuildInputs = withObject "native build inputs" $ \fields -> do
  checked fields ["translationUnits","providers","dependencies","nativeLibraries","unresolved","argumentBridges","nativeProduct"]
  dependencies <- if KM.member "nativeProduct" fields
    then ComponentBuildDependencies <$> optional fields "dependencies" (array dependencyRef)
      <*> optional fields "nativeProduct" (nativeDependency True)
    else ArchiveBuildDependencies <$> optional fields "dependencies" (array (nativeDependency False))
  NativeBuildInputs <$> (fields .: "translationUnits" >>= array group) <*> (fields .: "providers" >>= array provider)
    <*> pure dependencies <*> (fields .: "nativeLibraries" >>= array library)
    <*> (fields .: "unresolved" >>= array bytes) <*> (fields .: "argumentBridges" >>= array bridge)
  where
    group value@(Array _) = GroupCompile <$> array compileInput value
    group value = SingleCompile <$> compileInput value
    dependencyRef = withObject "declared native component dependency" $ \fields -> do
      checked fields ["declaredPath","unit","componentSha256","bitcodeSha256"]
      NativeDependencyRef <$> (fields .: "declaredPath" >>= array bytes) <*> bytesAt fields "unit"
        <*> bytesAt fields "componentSha256" <*> bytesAt fields "bitcodeSha256"
    provider = withObject "native source provider" $ \fields -> do
      checked fields ["provider","symbols","bitcode","bitcodeSha256","target","inputs"]
      NativeProvider <$> bytesAt fields "provider" <*> (fields .: "symbols" >>= array bytes)
        <*> bytesAt fields "bitcode" <*> bytesAt fields "bitcodeSha256" <*> bytesAt fields "target"
        <*> (fields .: "inputs" >>= compileInput)
    library = withObject "native library provider" $ \fields -> do
      checked fields ["provider","symbols","compiler","compilerSha256","arguments",
        "dependencyArguments","objcopy","objcopySha256","objcopyArguments"]
      NativeLibrary <$> bytesAt fields "provider" <*> (fields .: "symbols" >>= array bytes)
        <*> bytesAt fields "compiler" <*> bytesAt fields "compilerSha256" <*> (fields .: "arguments" >>= array bytes)
        <*> optional fields "dependencyArguments" (array bytes) <*> optional fields "objcopy" bytes
        <*> optional fields "objcopySha256" bytes <*> optional fields "objcopyArguments" (array (array bytes))
    bridge = withObject "native argument bridge" $ \fields -> do
      checked fields ["profile","source","sourceSha256","inputBitcodeSha256","definitions"]
      ArgumentBridge <$> bytesAt fields "profile" <*> bytesAt fields "source" <*> bytesAt fields "sourceSha256"
        <*> bytesAt fields "inputBitcodeSha256" <*> (fields .: "definitions" >>= array (array bytes))

compileInput :: Value -> Parser CompileInput
compileInput = withObject "native compilation inputs" $ \fields -> do
  checked fields ["compiler","clang","arguments","language","nativeTarget","target","files"]
  CompileInput <$> bytesAt fields "compiler" <*> bytesAt fields "clang" <*> (fields .: "arguments" >>= array bytes)
    <*> optional fields "language" bytes <*> bytesAt fields "nativeTarget" <*> bytesAt fields "target"
    <*> (fields .: "files" >>= array (stringPair "path" "sha256"))

nativeDependency :: Bool -> Value -> Parser NativeDependency
nativeDependency current = withObject "resolved native dependency" $ \fields -> do
  checked fields ["profile","unit","sourceIdentity","registration","registrationSha256","archives","translationUnits"]
  NativeDependency <$> bytesAt fields "profile" <*> bytesAt fields "unit" <*> (fields .: "sourceIdentity" >>= sourceIdentity current)
    <*> bytesAt fields "registration" <*> bytesAt fields "registrationSha256"
    <*> (fields .: "archives" >>= array archive) <*> (fields .: "translationUnits" >>= array productRecord)
  where
    archive = withObject "native archive product" $ \fields -> do
      checked fields ["path","sha256","members"]
      ArchiveProduct <$> bytesAt fields "path" <*> bytesAt fields "sha256"
        <*> (fields .: "members" >>= array (stringPair "name" "sha256"))
    productRecord = withObject "native translation product" $ \fields -> do
      checked fields ["receipt","bitcodeSha256"]
      NativeProduct <$> (fields .: "receipt" >>= piece) <*> bytesAt fields "bitcodeSha256"
    piece = withObject "native object receipt" $ \fields -> do
      checked fields ["root","object","objectSha256","bitcode","target","inputs"]
      NativePiece <$> bytesAt fields "root" <*> bytesAt fields "object" <*> bytesAt fields "objectSha256"
        <*> bytesAt fields "bitcode" <*> bytesAt fields "target" <*> (fields .: "inputs" >>= compileInput)

sourceIdentity :: Bool -> Value -> Parser SourceIdentity
sourceIdentity current = withObject "resolved native source identity" $ \fields -> do
  checked fields (["id","depends","type","style","pkg-name","pkg-version","flags","component-name","pkg-src-sha256","pkg-cabal-sha256"] ++ ["pkg-src" | current])
  SourceIdentity <$> optional fields "id" bytes <*> optional fields "depends" (array bytes)
    <*> optional fields "type" bytes <*> optional fields "style" bytes <*> optional fields "pkg-name" bytes
    <*> optional fields "pkg-version" bytes <*> optional fields "flags" flags
    <*> optional fields "component-name" bytes <*> optional fields "pkg-src-sha256" bytes <*> optional fields "pkg-cabal-sha256" bytes
    <*> optional fields "pkg-src" nativeSource
  where flags = withObject "Cabal configuration flags" $ \values ->
          mapM (\(key,value) -> (,) (Text.encodeUtf8 (Key.toText key)) <$> parseJSON value) (KM.toAscList values)

nativeSource :: Value -> Parser NativeSource
nativeSource = withObject "Cabal native source location" $ \fields -> do
  checked fields ["type","path","repo"]
  NativeSource <$> bytesAt fields "type" <*> optional fields "path" bytes
    <*> optional fields "repo" (withObject "Cabal source repository" $ \repo -> do
      checked repo ["type","uri"]
      (,) <$> bytesAt repo "type" <*> bytesAt repo "uri")

nativeArchive :: Value -> Parser NativeArchive
nativeArchive = withObject "unlinked native archive" $ \fields -> do
  checked fields ["schema","profile","execution","unit","module","unsupportedImports","unclassifiedReason",
    "unresolvedSymbols","artifact","conflictingImports"]
  NativeArchive <$> fields .: "schema" <*> bytesAt fields "profile" <*> bytesAt fields "execution"
    <*> bytesAt fields "unit" <*> bytesAt fields "module" <*> (fields .: "unsupportedImports" >>= array emittedCall)
    <*> optional fields "unclassifiedReason" bytes <*> (fields .: "unresolvedSymbols" >>= array bytes)
    <*> optional fields "artifact" nativeLink <*> optional fields "conflictingImports" (array emittedCall)

qualifiedName :: Value -> Parser QualifiedName
qualifiedName = withObject "qualified foreign identity" $ \fields -> do
  checked fields ["unit","module","occurrence","namespace"]
  QualifiedName <$> bytesAt fields "unit" <*> bytesAt fields "module" <*> bytesAt fields "occurrence" <*> bytesAt fields "namespace"

foreignType :: Value -> Parser ForeignType
foreignType = withObject "nominal foreign type" $ \fields -> do
  kind <- fields .: "kind" :: Parser Text.Text
  let recurse key = fields .: key >>= foreignType
  case kind of
    "tycon" -> checked fields ["kind","name","arguments"] >>
      (ForeignTyCon <$> (fields .: "name" >>= qualifiedName) <*> (fields .: "arguments" >>= array foreignType))
    "application" -> checked fields ["kind","function","argument"] >>
      (ForeignApplication <$> recurse "function" <*> recurse "argument")
    "function" -> checked fields ["kind","multiplicity","argument","result"] >>
      (ForeignArrow <$> recurse "multiplicity" <*> recurse "argument" <*> recurse "result")
    "bound-variable" -> checked fields ["kind","index"] >> (ForeignVariable <$> fields .: "index")
    "forall" -> checked fields ["kind","binderKind","body"] >>
      (ForeignForall <$> recurse "binderKind" <*> recurse "body")
    _ -> fail "Unknown nominal foreign type kind"

importProof :: Value -> Parser ImportProof
importProof = withObject "original import proof" $ \fields -> do
  schema <- fields .: "schema" :: Parser Word64
  status <- fields .: "status" :: Parser Text.Text
  let common = ["schema","scope","execution","profile","unit","module","status"]
  details <- case status of
    "unclassified" -> checked fields (common ++ ["reason"]) >> (ImportsUnclassified <$> bytesAt fields "reason")
    "rejected" -> checked fields (common ++ ["reason"]) >> (ImportsRejected <$> bytesAt fields "reason")
    "verified" -> checked fields (common ++ ["wordBits","expectedForeign","imports","expectedCalls"] ++ ["addresses" | schema >= 2] ++ ["wrappers" | schema >= 3] ++ ["importForeign" | schema == 4]) >>
      (ImportsVerified <$> fields .: "wordBits" <*> (fields .: "expectedForeign" >>= foreignArtifacts)
        <*> (fields .: "imports" >>= array association) <*> (fields .: "expectedCalls" >>= array foreignCall)
        <*> (if schema >= 2 then fields .: "addresses" >>= array address else pure [])
        <*> (if schema >= 3 then fields .: "wrappers" >>= array wrapper else pure [])
        <*> (if schema == 4 then Just <$> (fields .: "importForeign" >>= foreignArtifacts) else pure Nothing))
    _ -> fail "Unknown original import proof status"
  ImportProof <$> fields .: "schema" <*> bytesAt fields "scope" <*> bytesAt fields "execution"
    <*> bytesAt fields "profile" <*> bytesAt fields "unit" <*> bytesAt fields "module" <*> pure details
  where
    wrapper = withObject "original callback wrapper" $ \fields -> do
      checked fields ["binder","helper","convention","declaredType","normalizedType","normalizationRole","arguments","result","effect","typeString"]
      wrapperAssociation <- ExportAssociation <$> (fields .: "binder" >>= qualifiedName) <*> bytesAt fields "helper"
        <*> (fields .: "convention" >>= parseConvention) <*> (fields .: "declaredType" >>= foreignType)
        <*> (fields .: "normalizedType" >>= foreignType) <*> bytesAt fields "normalizationRole"
        <*> (fields .: "arguments" >>= array foreignType) <*> (fields .: "result" >>= foreignType)
        <*> (fields .: "effect" >>= choice [("pure",PureExport),("io",IOExport)])
      WrapperAssociation wrapperAssociation <$> bytesAt fields "typeString"
    address = withObject "original address association" $ \fields -> do
      checked fields ["binder","header","symbol","isFunction","convention","declaredType","normalizedType","normalizationRole","callback"]
      callback <- fields .: "callback"
      typed <- case callback of
        Null -> pure Nothing
        value -> Just <$> withObject "typed address callback" (\signature -> do
          checked signature ["arguments","result"]
          (,) <$> (signature .: "arguments" >>= array bytes) <*> bytesAt signature "result") value
      AddressAssociation <$> (fields .: "binder" >>= qualifiedName) <*> optional fields "header" bytes
        <*> bytesAt fields "symbol" <*> fields .: "isFunction" <*> (fields .: "convention" >>= parseConvention)
        <*> (fields .: "declaredType" >>= foreignType) <*> (fields .: "normalizedType" >>= foreignType)
        <*> bytesAt fields "normalizationRole" <*> pure typed
    association = withObject "original import association" $ \fields -> do
      checked fields ["binder","header","symbol","unit","isFunction","convention","safety",
        "declaredType","normalizedType","normalizationRole","emitted"]
      ImportAssociation <$> (fields .: "binder" >>= qualifiedName) <*> optional fields "header" bytes
        <*> bytesAt fields "symbol" <*> optional fields "unit" bytes <*> fields .: "isFunction"
        <*> (fields .: "convention" >>= parseConvention) <*> (fields .: "safety" >>= parseSafety)
        <*> (fields .: "declaredType" >>= foreignType) <*> (fields .: "normalizedType" >>= foreignType)
        <*> bytesAt fields "normalizationRole" <*> (fields .: "emitted" >>= emittedCall)

emittedCall :: Value -> Parser EmittedCall
emittedCall = withObject "emitted foreign ABI" $ \fields -> do
  checked fields ["symbol","unit","convention","safety","arguments","result"]
  EmittedCall <$> bytesAt fields "symbol" <*> optional fields "unit" bytes
    <*> (fields .: "convention" >>= parseConvention) <*> (fields .: "safety" >>= parseSafety)
    <*> (fields .: "arguments" >>= array bytes) <*> (fields .: "result" >>= array bytes)

exports :: Value -> Parser Exports
exports = withObject "original export inventory" $ \fields -> do
  checked fields ["schema","producer","scope","execution","unit","module","exports"]
  Exports <$> fields .: "schema" <*> bytesAt fields "producer" <*> bytesAt fields "scope"
    <*> bytesAt fields "execution" <*> bytesAt fields "unit" <*> bytesAt fields "module"
    <*> (fields .: "exports" >>= array association)
  where
    association = withObject "original export association" $ \fields -> do
      checked fields ["binder","symbol","convention","declaredType","normalizedType","normalizationRole","arguments","result","effect"]
      ExportAssociation <$> (fields .: "binder" >>= qualifiedName) <*> bytesAt fields "symbol"
        <*> (fields .: "convention" >>= parseConvention) <*> (fields .: "declaredType" >>= foreignType)
        <*> (fields .: "normalizedType" >>= foreignType) <*> bytesAt fields "normalizationRole"
        <*> (fields .: "arguments" >>= array foreignType) <*> (fields .: "result" >>= foreignType)
        <*> (fields .: "effect" >>= choice [("pure",PureExport),("io",IOExport)])

registration :: Value -> Parser Registration
registration = withObject "original export registration" $ \fields -> do
  status <- fields .: "status" :: Parser Text.Text
  let common = ["schema","scope","execution","profile","status"]
  details <- case status of
    "unclassified" -> checked fields (common ++ ["reason"]) >> (RegistrationUnclassified <$> bytesAt fields "reason")
    "rejected" -> checked fields (common ++ ["reason"]) >> (RegistrationRejected <$> bytesAt fields "reason")
    "verified" -> checked fields (common ++ ["roots","wordBits","expectedForeign","expectedExports"]) >>
      (RegistrationVerified <$> (fields .: "roots" >>= array qualifiedName) <*> fields .: "wordBits"
        <*> (fields .: "expectedForeign" >>= foreignArtifacts) <*> (fields .: "expectedExports" >>= exports))
    _ -> fail "Unknown original export registration status"
  Registration <$> fields .: "schema" <*> bytesAt fields "scope" <*> bytesAt fields "execution"
    <*> bytesAt fields "profile" <*> pure details

literal :: Value -> Value -> Parser Literal
literal (String "rubbish") Null = pure LitRubbish
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
  "unsupported" -> pure (LitUnsupported (Text.encodeUtf8 payload))
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
  schema <- layout .: "schema"
  names <- either fail pure (targetNumberNamesFor schema)
  checked layout (["schema","profiled","wordBytes","endianness","targetPlatform","tablesNextToCode"] ++ map bytesKey names)
  TargetLayout <$> fields .: "schema" <*> bytesAt compiler "id" <*> bytesAt compiler "abi"
    <*> bytesAt compiler "platform" <*> bytesAt compiler "way" <*> pure schema
    <*> layout .: "profiled" <*> layout .: "wordBytes"
    <*> (layout .: "endianness" >>= choice [("little",LittleEndian),("big",BigEndian)])
    <*> bytesAt layout "targetPlatform" <*> layout .: "tablesNextToCode"
    <*> mapM (\key -> layout .: bytesKey key) names

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
