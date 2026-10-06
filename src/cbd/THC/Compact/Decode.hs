-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.Compact.Decode
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; bounded binary slices
--
-- Native selected-record decoder for round-trip controls and flat inspection.
-- Earlier shape definitions are addressed directly; no preceding Core tree is
-- decoded to find a selected binding. Runtime mmap ownership is independent.
module THC.Compact.Decode (decodeBindingAt, decodeBindingAtWithHostSignatures, decodeBindingAtWithFeatures, decodeExprAt, decodeRepAt, decodeFacts, decodeMetadata) where

import Control.Monad (replicateM, unless)
import Data.Binary.Get hiding (Decoder)
import Data.Bits (shiftL, (.|.))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Int (Int64)
import qualified Data.Set as Set
import qualified Data.Text.Encoding as Text
import qualified Data.Text as TextValue
import Text.Read (readMaybe)
import Data.Word (Word64)
import THC.Compact.Core
import THC.Compact.Types
import THC.Compact.Facts
import THC.Compact.Wire

data Decoder = Decoder
  { sourceData :: !BS.ByteString
  , sourceStrings :: !BS.ByteString
  , recordBase :: !Word64
  , activeShapes :: !(Set.Set Word64)
  , allowHostSignatures :: !Bool
  , allowRecoveryFacts :: !Bool
  }

-- | Return a selected typed binding and its end-exclusive relative byte offset.
decodeBindingAt :: BS.ByteString -> BS.ByteString -> Word64 -> Either String (Binding, Word64)
decodeBindingAt = decodeBindingAtWithHostSignatures True

-- | Container readers pass the declared feature bit; standalone record tests
-- can decode the complete current record vocabulary with 'decodeBindingAt'.
decodeBindingAtWithHostSignatures :: Bool -> BS.ByteString -> BS.ByteString -> Word64 -> Either String (Binding, Word64)
decodeBindingAtWithHostSignatures allow = decodeBindingAtWithFeatures allow True

-- | Container readers validate both independent executable feature bits.
decodeBindingAtWithFeatures :: Bool -> Bool -> BS.ByteString -> BS.ByteString -> Word64 -> Either String (Binding, Word64)
decodeBindingAtWithFeatures hosts recovery bytes strings offset =
  runAt bytes offset (binding (Decoder bytes strings offset Set.empty hosts recovery))

decodeExprAt :: BS.ByteString -> BS.ByteString -> Word64 -> Either String (Expr, Word64)
decodeExprAt bytes strings offset = runAt bytes offset (expression (Decoder bytes strings offset Set.empty True True))

decodeRepAt :: BS.ByteString -> BS.ByteString -> Word64 -> Either String (Rep, Word64)
decodeRepAt bytes strings offset = runAt bytes offset (representation (Decoder bytes strings offset Set.empty True True))

-- | Header facts decode independently of all executable and debug bytes.
decodeFacts :: BS.ByteString -> BS.ByteString -> Either String Facts
decodeFacts bytes strings = decodeExact (facts (Decoder bytes strings 0 Set.empty True True)) bytes

-- | The final header owns its string pool; no facts span refers to DATA's
-- strings member. The fixed 32-byte container prefix is excluded here.
decodeMetadata :: BS.ByteString -> Either String Facts
decodeMetadata bytes = do
  size <- decodeExact getWord64le (BS.take 8 bytes)
  let rest = BS.drop 8 bytes
  unless (size <= fromIntegral (BS.length rest)) (Left "Compact metadata strings exceed header")
  decodeFacts (BS.drop (fromIntegral size) rest) (BS.take (fromIntegral size) rest)

facts :: Decoder -> Get Facts
facts decoder = do
  base <- Facts <$> getUVar <*> string decoder <*> string decoder <*> string decoder <*> string decoder
    <*> present (list decoder (string decoder)) <*> present (targetLayout decoder)
    <*> list decoder (constructor decoder) <*> present (foreignArtifacts decoder)
    <*> present (exceptionBridge decoder) <*> present (string decoder)
    <*> mapM (present . provenance decoder) [0..length pendingProvenanceNames-1]
    <*> pure Nothing <*> pure Nothing <*> pure Nothing
  extensions base
  where
    extensions value = do
      done <- isEmpty
      if done then pure value else getWord8 >>= \kind -> case kind of
        1 | Nothing <- factsClosureProvenance value -> do
          proof <- closureProvenance decoder
          extensions value {factsClosureProvenance = Just proof}
        2 | Nothing <- factsBackendPolicy value -> do
          def <- getWord8 >>= \tag -> case tag of
            0 -> pure Nothing
            1 -> pure (Just AstBackend)
            2 -> pure (Just BytecodeBackend)
            _ -> fail "Invalid compact default backend"
          bindings <- list decoder ((,) <$> string decoder <*> backend)
          unless (and (zipWith (<) (map fst bindings) (drop 1 (map fst bindings))))
            (fail "Compact backend policy bindings must be strictly sorted")
          extensions value {factsBackendPolicy = Just (BackendPolicy def bindings)}
        3 | Nothing <- factsRecovery value -> do
          recovery <- framed decoder recoveryFacts
          extensions value {factsRecovery = Just recovery}
        _ -> fail "Unknown or duplicate compact header extension"
    backend = getWord8 >>= \tag -> case tag of
      1 -> pure AstBackend
      2 -> pure BytecodeBackend
      _ -> fail "Invalid compact binding backend"

closureProvenance :: Decoder -> Get ClosureProvenance
closureProvenance decoder = ClosureProvenance <$> present (list decoder (string decoder))
  <*> present (list decoder (string decoder))
  <*> present (list decoder (MissingDefinition <$> string decoder <*> string decoder <*> string decoder))
  <*> list decoder (BindingOrigin <$> string decoder <*> present (string decoder) <*> present (string decoder))

targetLayout :: Decoder -> Get TargetLayout
targetLayout decoder = do
  documentSchema <- getUVar
  compilerId <- string decoder
  abi <- string decoder
  platform <- string decoder
  way <- string decoder
  schema <- getUVar
  names <- either fail pure (targetNumberNamesFor schema)
  TargetLayout documentSchema compilerId abi platform way schema
    <$> boolean <*> getUVar <*> enumeration <*> string decoder <*> boolean
    <*> replicateM (length names) getUVar

foreignArtifacts :: Decoder -> Get ForeignArtifacts
foreignArtifacts decoder = ForeignArtifacts <$> getUVar <*> string decoder <*> present stubs <*> list decoder file
  where
    stubs = Stubs <$> string decoder <*> string decoder <*> list decoder labelRecord <*> list decoder labelRecord
    labelRecord = Label <$> boolean <*> string decoder <*> string decoder <*> string decoder
    file = ForeignFile <$> string decoder <*> string decoder <*> string decoder

exceptionBridge :: Decoder -> Get ExceptionBridge
exceptionBridge decoder = ExceptionBridge <$> getUVar <*> string decoder <*> string decoder
  <*> string decoder <*> string decoder <*> string decoder <*> string decoder

constructor :: Decoder -> Get Constructor
constructor decoder = Constructor <$> string decoder <*> getUVar <*> getUVar <*> enumeration
  <*> list decoder boolean <*> list decoder (arrayElement boolean)
  <*> list decoder (arrayElement (list decoder primRep)) <*> list decoder (inlineRepresentation decoder)
  <*> present getUVar <*> present (enumFamily decoder) <*> present (tagFamily decoder)
inlineRepresentation :: Decoder -> Get Rep
inlineRepresentation decoder = do
  layout <- inlineShape
  Rep layout <$> evaluation layout
  where inlineShape = shapeFields decoder inlineShape

provenance :: Decoder -> Int -> Get ModuleProvenance
provenance decoder slot = case slot of
  0 -> ForeignLinkRecord <$> foreignLink decoder
  1 -> ImportsRecord <$> importProof decoder
  2 -> ImportsRecord <$> importProof decoder
  3 -> ExportsRecord <$> exports decoder
  4 -> RegistrationRecord <$> registration decoder
  5 -> ScalarLinkRecord <$> scalarLink decoder
  6 -> NativeLinkRecord <$> nativeLink decoder
  7 -> NativeArchiveRecord <$> nativeArchive decoder
  _ -> fail "Unimplemented nonempty compact provenance record"

foreignLink :: Decoder -> Get ForeignLink
foreignLink decoder = ForeignLink <$> getUVar <*> string decoder <*> string decoder <*> string decoder
  <*> string decoder <*> string decoder <*> blob decoder <*> string decoder <*> list decoder (string decoder)
  <*> list decoder pair <*> present (list decoder pair)
  where pair = (,) <$> string decoder <*> string decoder

linkPayload :: Decoder -> Get LinkPayload
linkPayload decoder = LinkPayload <$> getUVar <*> string decoder <*> string decoder <*> string decoder
  <*> string decoder <*> string decoder <*> string decoder <*> blob decoder

scalarLink :: Decoder -> Get ScalarLink
scalarLink decoder = ScalarLink <$> linkPayload decoder <*> list decoder entry
  where entry = ScalarABI <$> string decoder <*> string decoder <*> list decoder (string decoder) <*> string decoder

blob :: Decoder -> Get BS.ByteString
blob decoder = count decoder >>= getByteString

nativeLink :: Decoder -> Get NativeLink
nativeLink decoder = do
  payload@(LinkPayload schema _ _ _ _ _ _ _) <- linkPayload decoder
  abi <- list decoder entry
  inputs <- getWord8 >>= \kind -> case kind of
    0 -> pure Missing
    1 -> pure Unknown
    2 -> Known <$> nativeBuildInputs decoder False False
    3 -> Known <$> nativeBuildInputs decoder True False
    4 -> Known <$> nativeBuildInputs decoder True True
    _ -> fail "Invalid compact native build-input tag"
  (companion,dataSymbols,components) <- getWord8 >>= \kind -> case kind of
    0 -> pure (Missing,Missing,Nothing)
    _ | kind == 3 || kind == 4 -> (,,) <$> present ((,) <$> string decoder <*> blob decoder)
             <*> present (list decoder (string decoder))
             <*> (if kind == 3 then pure Nothing else
               Just <$> ((,) <$> list decoder (string decoder) <*> list decoder (nativeComponent decoder)))
    _ -> fail "Retired or invalid compact native entry metadata"
  NativeLink payload abi inputs companion dataSymbols <$>
    (if schema == 2 then list decoder (string decoder) else pure []) <*> pure components
    <*> (if schema == 3 then Just <$> list decoder seed else pure Nothing)
  where entry = NativeABI <$> string decoder <*> string decoder <*> enumeration <*> enumeration
          <*> list decoder (string decoder) <*> string decoder
        seed = do
          name <- string decoder; digest <- string decoder; bytes <- blob decoder
          provider <- getWord8 >>= \kind -> case kind of
            0 -> pure Nothing
            1 -> Just <$> ((,,) <$> string decoder <*> string decoder <*> string decoder)
            _ -> fail "Invalid native call seed provider tag"
          pure (NativeCallSeed name digest bytes provider)

nativeComponent :: Decoder -> Get NativeComponent
nativeComponent decoder = NativeComponent <$> linkPayload decoder <*> list decoder (string decoder)
  <*> list decoder (nativeComponent decoder) <*> present ((,) <$> string decoder <*> blob decoder)

nativeBuildInputs :: Decoder -> Bool -> Bool -> Get NativeBuildInputs
nativeBuildInputs decoder extended current = do
  units <- list decoder group
  providers <- list decoder provider
  dependencies <- if current
    then ComponentBuildDependencies <$> present (list decoder dependencyRef) <*> pure Missing
    else ArchiveBuildDependencies <$> present (list decoder (nativeDependency decoder False))
  libraries <- list decoder library
  unresolved <- strings
  bridges <- list decoder bridge
  completed <- case dependencies of
    ComponentBuildDependencies records _ -> ComponentBuildDependencies records <$> present (nativeDependency decoder True)
    _ -> pure dependencies
  pure (NativeBuildInputs units providers completed libraries unresolved bridges)
  where
    strings = list decoder (string decoder)
    dependencyRef = NativeDependencyRef <$> strings <*> string decoder <*> string decoder <*> string decoder
    group = getWord8 >>= \kind -> case kind of
      0 -> SingleCompile <$> compileInput decoder
      1 -> GroupCompile <$> list decoder (compileInput decoder)
      _ -> fail "Unknown native compilation group tag"
    provider = NativeProvider <$> string decoder <*> strings <*> string decoder <*> string decoder
      <*> string decoder <*> compileInput decoder
    library = NativeLibrary <$> string decoder <*> strings <*> string decoder <*> string decoder <*> strings
      <*> extra strings <*> extra (string decoder) <*> extra (string decoder) <*> extra (list decoder strings)
    extra parser = if extended then present parser else pure Missing
    bridge = ArgumentBridge <$> string decoder <*> string decoder <*> string decoder <*> string decoder
      <*> list decoder strings

compileInput :: Decoder -> Get CompileInput
compileInput decoder = CompileInput <$> string decoder <*> string decoder <*> list decoder (string decoder)
  <*> present (string decoder) <*> string decoder <*> string decoder
  <*> list decoder ((,) <$> string decoder <*> string decoder)

nativeDependency :: Decoder -> Bool -> Get NativeDependency
nativeDependency decoder current = NativeDependency <$> string decoder <*> string decoder <*> sourceIdentity decoder current
  <*> string decoder <*> string decoder <*> list decoder archive <*> list decoder productRecord
  where
    archive = ArchiveProduct <$> string decoder <*> string decoder
      <*> list decoder ((,) <$> string decoder <*> string decoder)
    productRecord = NativeProduct <$> (NativePiece <$> string decoder <*> string decoder <*> string decoder
      <*> string decoder <*> string decoder <*> compileInput decoder) <*> string decoder

sourceIdentity :: Decoder -> Bool -> Get SourceIdentity
sourceIdentity decoder current = SourceIdentity <$> optionalString <*> present (list decoder (string decoder))
  <*> optionalString <*> optionalString <*> optionalString <*> optionalString
  <*> present (list decoder ((,) <$> string decoder <*> boolean))
  <*> optionalString <*> optionalString <*> optionalString
  <*> (if current then present location else pure Missing)
  where
    optionalString = present (string decoder)
    location = NativeSource <$> string decoder <*> optionalString <*> present ((,) <$> string decoder <*> string decoder)

nativeArchive :: Decoder -> Get NativeArchive
nativeArchive decoder = NativeArchive <$> getUVar <*> string decoder <*> string decoder <*> string decoder
  <*> string decoder <*> list decoder (emittedCall decoder) <*> present (string decoder)
  <*> list decoder (string decoder) <*> present (nativeLink decoder)
  <*> present (list decoder (emittedCall decoder)) <*
    (getWord8 >>= \kind -> unless (kind == 0) (fail "Retired compact native entry-resolution metadata"))

qualifiedName :: Decoder -> Get QualifiedName
qualifiedName decoder = QualifiedName <$> string decoder <*> string decoder <*> string decoder <*> string decoder

foreignType :: Decoder -> Get ForeignType
foreignType decoder = getWord8 >>= \kind -> case kind of
  0 -> ForeignTyCon <$> qualifiedName decoder <*> list decoder recurse
  1 -> ForeignApplication <$> recurse <*> recurse
  2 -> ForeignArrow <$> recurse <*> recurse <*> recurse
  3 -> ForeignVariable <$> getUVar
  4 -> ForeignForall <$> recurse <*> recurse
  _ -> fail "Unknown compact foreign type tag"
  where recurse = foreignType decoder

importProof :: Decoder -> Get ImportProof
importProof decoder = do
  schema <- getUVar
  ImportProof schema <$> string decoder <*> string decoder
    <*> string decoder <*> string decoder <*> string decoder <*> status schema
  where
    status schema = getWord8 >>= \kind -> case kind of
      0 -> ImportsUnclassified <$> string decoder
      1 -> ImportsRejected <$> string decoder
      2 -> ImportsVerified <$> getUVar <*> foreignArtifacts decoder <*> list decoder association
        <*> list decoder (foreignCallWith decoder (inlineRepresentation decoder))
        <*> (if schema >= 2 then list decoder address else pure [])
        <*> (if schema >= 3 then list decoder wrapper else pure [])
        <*> (if schema == 4 then Just <$> foreignArtifacts decoder else pure Nothing)
      _ -> fail "Unknown compact import provenance status"
    wrapper = WrapperAssociation <$> (ExportAssociation <$> qualifiedName decoder <*> string decoder <*> enumeration
      <*> foreignType decoder <*> foreignType decoder <*> string decoder <*> list decoder (foreignType decoder)
      <*> foreignType decoder <*> enumeration) <*> string decoder
    association = ImportAssociation <$> qualifiedName decoder <*> present (string decoder)
      <*> string decoder <*> present (string decoder) <*> boolean <*> enumeration <*> enumeration
      <*> foreignType decoder <*> foreignType decoder <*> string decoder <*> emittedCall decoder
    address = AddressAssociation <$> qualifiedName decoder <*> present (string decoder)
      <*> string decoder <*> boolean <*> enumeration <*> foreignType decoder <*> foreignType decoder
      <*> string decoder <*> (boolean >>= \known -> if known
        then Just <$> ((,) <$> list decoder (string decoder) <*> string decoder) else pure Nothing)

emittedCall :: Decoder -> Get EmittedCall
emittedCall decoder = EmittedCall <$> string decoder <*> present (string decoder) <*> enumeration <*> enumeration
  <*> list decoder (string decoder) <*> list decoder (string decoder)

exports :: Decoder -> Get Exports
exports decoder = Exports <$> getUVar <*> string decoder <*> string decoder <*> string decoder
  <*> string decoder <*> string decoder <*> list decoder association
  where
    association = ExportAssociation <$> qualifiedName decoder <*> string decoder <*> enumeration
      <*> foreignType decoder <*> foreignType decoder <*> string decoder <*> list decoder (foreignType decoder)
      <*> foreignType decoder <*> enumeration

registration :: Decoder -> Get Registration
registration decoder = Registration <$> getUVar <*> string decoder <*> string decoder <*> string decoder <*> status
  where
    status = getWord8 >>= \kind -> case kind of
      0 -> RegistrationUnclassified <$> string decoder
      1 -> RegistrationRejected <$> string decoder
      2 -> RegistrationVerified <$> list decoder (qualifiedName decoder) <*> getUVar
        <*> foreignArtifacts decoder <*> exports decoder
      _ -> fail "Unknown compact export registration status"

arrayElement :: Get a -> Get (Presence a)
arrayElement parser = do
  value <- present parser
  case value of
    Missing -> fail "Absent compact array element"
    _ -> pure value

runAt :: BS.ByteString -> Word64 -> Get a -> Either String (a, Word64)
runAt bytes offset parser = do
  checkedSpan (fromIntegral (BS.length bytes)) (Span offset 0)
  case runGetOrFail parser (BL.fromStrict (BS.drop (fromIntegral offset) bytes)) of
    Left (_,_,problem) -> Left problem
    Right (_,consumed,value) -> Right (value, offset+fromIntegral consumed)

boolean :: Get Bool
boolean = getWord8 >>= \value -> case value of
  0 -> pure False
  1 -> pure True
  _ -> fail "Invalid compact Boolean"

enumeration :: (Enum a, Bounded a) => Get a
enumeration = do
  ordinal <- fromIntegral <$> getWord8
  let value = toEnum ordinal
  unless (ordinal <= fromEnum (maxBound `asTypeOf` value)) (fail "Unknown compact enum tag")
  pure value

present :: Get a -> Get (Presence a)
present parser = getWord8 >>= \kind -> case kind of
  0 -> pure Missing
  1 -> pure Unknown
  2 -> Known <$> parser
  _ -> fail "Invalid compact optional-field discriminator"

count :: Decoder -> Get Int
count decoder = do
  value <- getUVar
  unless (value <= fromIntegral (BS.length (sourceData decoder))) (fail "Compact count exceeds selected data extent")
  pure (fromIntegral value)

list :: Decoder -> Get a -> Get [a]
list decoder parser = count decoder >>= flip replicateM parser

string :: Decoder -> Get BS.ByteString
string decoder = do
  span'@(Span start size) <- getSpan
  let bytes = sourceStrings decoder
  either fail pure (checkedSpan (fromIntegral (BS.length bytes)) span')
  let selected = BS.take (fromIntegral size) (BS.drop (fromIntegral start) bytes)
  either (fail . show) (const (pure selected)) (Text.decodeUtf8' selected)

identity :: Decoder -> Get Identity
identity decoder = getWord8 >>= \kind -> case kind of
  0 -> Global <$> string decoder
  1 -> Local <$> getUVar
  _ -> fail "Unknown compact identity tag"

binding :: Decoder -> Get Binding
binding decoder = do
  prefix <- lookAhead getWord8
  (callable,signature) <- case prefix of
    2 -> do
      unless (allowHostSignatures decoder) (fail "Compact host signature lacks header flag")
      _ <- getWord8
      value <- present (hostSignature decoder)
      unless (value /= Missing) (fail "Empty compact host-signature extension")
      pure (Missing,value)
    3 -> do
      unless (allowRecoveryFacts decoder) (fail "Compact callable fact lacks header flag")
      _ <- getWord8
      pair <- framed decoder $ \bounded -> (,) <$> present (typeTerm bounded) <*> present (inlineHostSignature bounded)
      unless (fst pair /= Missing) (fail "Empty compact callable extension")
      unless (snd pair == Missing || allowHostSignatures decoder) (fail "Compact host signature lacks header flag")
      pure pair
    _ -> pure (Missing,Missing)
  Binding <$> identity decoder <*> enumeration <*> present boolean <*> getUVar
    <*> present (representation decoder) <*> present (idInfo decoder)
    <*> present (list decoder boolean) <*> present (string decoder) <*> present getUVar
    <*> present (representation decoder) <*> pure callable <*> pure signature <*> expression decoder

hostSignature :: Decoder -> Get HostSignature
hostSignature decoder = HostSignature <$> list decoder hostType <*> hostType
  where hostType = HostType <$> representation decoder <*> list decoder enumeration

inlineHostSignature :: Decoder -> Get HostSignature
inlineHostSignature decoder = HostSignature <$> list decoder hostType <*> hostType
  where hostType = HostType <$> inlineRepresentation decoder <*> list decoder enumeration

-- Framed facts decode against their own bounds and shared string pool.
framed :: Decoder -> (Decoder -> Get a) -> Get a
framed decoder parser = do
  size <- getUVar
  unless (size <= fromIntegral (BS.length (sourceData decoder))) (fail "Recovery frame exceeds record extent")
  bytes <- getByteString (fromIntegral size)
  either fail pure (decodeExact (parser decoder {sourceData=bytes,recordBase=0}) bytes)

maybeValue :: Get a -> Get (Maybe a)
maybeValue parser = getWord8 >>= \kind -> case kind of
  0 -> pure Nothing
  1 -> Just <$> parser
  _ -> fail "Invalid recovery optional tag"

typeName :: Decoder -> Get TypeName
typeName decoder = do
  unit <- string decoder
  owner <- string decoder
  namespace <- getWord8
  unless (namespace <= 4) (fail "Invalid recovery Name namespace")
  TypeName unit owner namespace <$> maybeValue (string decoder) <*> string decoder

typeBinder :: Decoder -> Get TypeBinder
typeBinder decoder = TypeBinder <$> string decoder <*> boolean <*> typeTerm decoder

tyConSort :: Get TyConSort
tyConSort = getWord8 >>= \kind -> case kind of
  0 -> pure NormalTyCon
  1 -> TupleTyCon <$> getUVar <*> enumeration
  2 -> SumTyCon <$> getUVar
  3 -> pure EqualityTyCon
  _ -> fail "Invalid recovery TyCon sort"

typeArguments :: Decoder -> Get [TypeArgument]
typeArguments decoder = list decoder (TypeArgument <$> enumeration <*> typeTerm decoder)

typeTerm :: Decoder -> Get TypeTerm
typeTerm decoder = getWord8 >>= \kind -> case kind of
  0 -> TypeVar <$> string decoder
  1 -> TypeCon <$> typeName decoder <*> boolean <*> tyConSort <*> typeArguments decoder
  2 -> TypeApp <$> child <*> typeArguments decoder
  3 -> TypeFun <$> enumeration <*> child <*> child <*> child
  4 -> TypeForall <$> typeBinder decoder <*> enumeration <*> child
  5 -> TypeTuple <$> enumeration <*> boolean <*> typeArguments decoder
  6 -> do
    text <- string decoder
    case readMaybe (TextValue.unpack (Text.decodeUtf8 text)) of
      Just integer | text == Text.encodeUtf8 (TextValue.pack (show integer)) -> pure (TypeNat integer)
      _ -> fail "Noncanonical recovery integer"
  7 -> TypeSymbol <$> list decoder codePoint
  8 -> TypeChar <$> codePoint
  9 -> TypeCast <$> child <*> coTerm decoder
  10 -> TypeCoercion <$> coTerm decoder
  _ -> fail "Unknown recovery type term"
  where child = typeTerm decoder
        codePoint = do
          value <- getUVar
          unless (value <= 0x10ffff) (fail "Invalid recovery literal code point")
          pure (fromIntegral value)

coTerm :: Decoder -> Get CoTerm
coTerm decoder = getWord8 >>= \kind -> case kind of
  0 -> CoRefl <$> typeTerm decoder
  1 -> CoGRefl <$> enumeration <*> typeTerm decoder <*> maybeValue child
  2 -> CoFun <$> enumeration <*> child <*> child <*> child
  3 -> CoCon <$> enumeration <*> typeName decoder <*> boolean <*> tyConSort <*> children
  4 -> CoApp <$> child <*> child
  5 -> CoForall <$> typeBinder decoder <*> enumeration <*> enumeration <*> child <*> child
  6 -> CoVar <$> string decoder
  7 -> CoUniv <$> evidence <*> enumeration <*> typeTerm decoder <*> typeTerm decoder <*> children
  8 -> CoSym <$> child
  9 -> CoTrans <$> child <*> child
  10 -> CoSelect <$> selector <*> child
  11 -> getWord8 >>= \side -> case side of
    0 -> CoLeft <$> child
    1 -> CoRight <$> child
    _ -> fail "Invalid recovery coercion side"
  12 -> CoInst <$> child <*> child
  13 -> CoKind <$> child
  14 -> CoSub <$> child
  15 -> CoAxiom <$> rule <*> children
  _ -> fail "Unknown recovery coercion term"
  where child = coTerm decoder
        children = list decoder child
        evidence = getWord8 >>= \tag -> case tag of
          0 -> pure PhantomProvenance
          1 -> pure ProofIrrelevance
          2 -> PluginProvenance <$> string decoder
          _ -> fail "Invalid recovery coercion provenance"
        selector = getWord8 >>= \tag -> case tag of
          0 -> TyConSelector <$> getUVar <*> enumeration
          1 -> pure ForallSelector
          2 -> pure MultiplicitySelector
          3 -> pure ArgumentSelector
          4 -> pure ResultSelector
          _ -> fail "Invalid recovery coercion selector"
        rule = getWord8 >>= \tag -> case tag of
          0 -> BuiltinRule <$> string decoder
          1 -> UnbranchedRule <$> typeName decoder
          2 -> BranchedRule <$> typeName decoder <*> getUVar
          _ -> fail "Invalid recovery axiom rule"

typeParameter :: Decoder -> Get TypeParameter
typeParameter decoder = TypeParameter <$> typeBinder decoder <*> (getWord8 >>= \tag -> case tag of
  0 -> pure AnonymousParameter
  1 -> NamedParameter <$> enumeration
  _ -> fail "Invalid recovery parameter visibility")

typeAxiom :: Decoder -> Get AxiomFact
typeAxiom decoder = AxiomFact <$> typeName decoder <*> typeName decoder <*> enumeration
  <*> list decoder (AxiomBranch <$> list decoder (typeBinder decoder) <*> list decoder enumeration
    <*> list decoder (typeTerm decoder) <*> typeTerm decoder)

recoveryFacts :: Decoder -> Get RecoveryFacts
recoveryFacts decoder = RecoveryFacts <$> list decoder nominal <*> list decoder constructorFact <*> list decoder (typeAxiom decoder)
  where nominal = NominalFact <$> typeName decoder <*> list decoder (typeParameter decoder)
          <*> typeTerm decoder <*> list decoder enumeration <*> enumeration
          <*> maybeValue (typeTerm decoder) <*> maybeValue (typeName decoder) <*> list decoder (typeName decoder)
        constructorFact = ConstructorFact <$> typeName decoder <*> typeName decoder <*> typeName decoder
          <*> list decoder (typeBinder decoder) <*> list decoder (typeBinder decoder)
          <*> list decoder (ScaledType <$> typeTerm decoder <*> typeTerm decoder) <*> typeTerm decoder

binder :: Decoder -> Get Binder
binder decoder = Binder <$> getUVar <*> enumeration <*> present boolean <*> present boolean
  <*> present (representation decoder) <*> present (idInfo decoder)

idInfo :: Decoder -> Get IdInfo
idInfo decoder = IdInfo <$> present getUVar <*> present boolean <*> present (list decoder boolean)

expression :: Decoder -> Get Expr
expression decoder = do
  kind <- getWord8
  unless (kind <= 9) (fail "Unknown compact expression tag")
  metadata <- meta decoder
  case kind of
    0 -> Var metadata <$> identity decoder
    1 -> Prim metadata <$> string decoder
    2 -> Lit metadata <$> literal decoder
    3 -> Lam metadata <$> list decoder (binder decoder) <*> child
    4 -> Con metadata <$> string decoder <*> getUVar
    5 -> App metadata <$> child <*> list decoder child <*> list decoder (arrayElement boolean) <*> boolean <*> boolean
    6 -> Let metadata <$> boolean <*> list decoder (binding decoder) <*> child
    7 -> Case metadata <$> child <*> getUVar <*> present (binder decoder) <*> list decoder (alternative decoder)
    8 -> pure (Void metadata)
    _ -> Unsupported metadata <$> string decoder
  where
    child = expression decoder

meta :: Decoder -> Get Meta
meta decoder = Meta <$> present (representation decoder) <*> present (representation decoder)
  <*> present (list decoder boolean) <*> present (string decoder) <*> present (callDemand decoder)
  <*> present (foreignCall decoder) <*> present (exceptionPayload decoder)
  <*> present (enumFamily decoder) <*> present (tagFamily decoder) <*> present (string decoder)

callDemand :: Decoder -> Get CallDemand
callDemand decoder = CallDemand <$> getUVar <*> list decoder boolean

exceptionPayload :: Decoder -> Get ExceptionPayload
exceptionPayload decoder = ExceptionPayload <$> getUVar <*> string decoder

enumFamily :: Decoder -> Get EnumFamily
enumFamily decoder = EnumFamily <$> string decoder <*> list decoder (string decoder)

tagFamily :: Decoder -> Get TagFamily
tagFamily decoder = TagFamily <$> enumFamily decoder <*> getUVar <*> boolean

foreignCall :: Decoder -> Get ForeignCall
foreignCall decoder = foreignCallWith decoder (representation decoder)

foreignCallWith :: Decoder -> Get Rep -> Get ForeignCall
foreignCallWith decoder representationValue = do
  schema <- getUVar
  unless (schema == 1 || schema == 2) (fail "Unsupported compact foreign-call schema")
  ForeignCall schema <$> target <*> enumeration <*> enumeration
    <*> getUVar <*> getUVar <*> list decoder representationValue <*> representationValue
    <*> present (string decoder) <*> present (string decoder)
    <*> (if schema == 2 then present (list decoder (arrayElement (string decoder))) else pure Missing)
  where
    target = getWord8 >>= \kind -> case kind of
      0 -> StaticTarget <$> string decoder <*> present (string decoder) <*> boolean
      1 -> pure DynamicTarget
      _ -> fail "Unknown compact foreign-target tag"

alternative :: Decoder -> Get Alternative
alternative decoder = getWord8 >>= \kind -> case kind of
  0 -> DefaultAlt <$> parameters <*> body
  1 -> DataAlt <$> string decoder <*> parameters <*> body
  2 -> LiteralAlt <$> literal decoder <*> parameters <*> body
  _ -> fail "Unknown compact alternative tag"
  where parameters = list decoder (binder decoder)
        body = expression decoder

representation :: Decoder -> Get Rep
representation decoder = do
  layout <- shapeUse decoder
  Rep layout <$> evaluation layout

shapeUse :: Decoder -> Get Shape
shapeUse decoder = do
  consumed <- bytesRead
  let position = recordBase decoder + fromIntegral consumed
  kind <- getWord8
  case kind of
    0 -> shapeDefinition decoder { activeShapes = Set.insert position (activeShapes decoder) }
    1 -> do
      offset <- getUVar
      unless (offset < position && not (Set.member offset (activeShapes decoder)))
        (fail "Compact shape reference is forward or cyclic")
      let referenced = decoder { recordBase = offset, activeShapes = Set.insert offset (activeShapes decoder) }
          parseDefinition = do
            marker <- getWord8
            unless (marker == 0) (fail "Compact shape reference does not identify a definition")
            shapeDefinition referenced
      either fail (pure . fst) (runAt (sourceData decoder) offset parseDefinition)
    _ -> fail "Unknown compact shape-use tag"

shapeDefinition :: Decoder -> Get Shape
shapeDefinition decoder = shapeFields decoder (shapeUse decoder)

shapeFields :: Decoder -> Get Shape -> Get Shape
shapeFields decoder child = Shape <$> enumeration <*> present (list decoder primRep)
  <*> present vector <*> present enumeration
  <*> present (list decoder child) <*> present (list decoder child)
  <*> present getUVar <*> present (list decoder (list decoder getUVar))

evaluation :: Shape -> Get Evaluation
evaluation layout = Evaluation <$> present boolean <*> mapM evaluation (shapeChildren layout)

vector :: Get Vector
vector = Vector <$> getUVar <*> enumeration

primRep :: Get PrimRep
primRep = do
  kind <- getWord8
  case kind of
    0 -> pure IntRep; 1 -> pure WordRep
    2 -> pure Int8Rep; 3 -> pure Int16Rep; 4 -> pure Int32Rep; 5 -> pure Int64Rep
    6 -> pure Word8Rep; 7 -> pure Word16Rep; 8 -> pure Word32Rep; 9 -> pure Word64Rep
    10 -> pure FloatRep; 11 -> pure DoubleRep; 12 -> pure AddrRep
    13 -> pure BoxedUnknown; 14 -> pure BoxedLifted; 15 -> pure BoxedUnlifted
    16 -> VecRep <$> vector
    _ -> fail "Unknown compact PrimRep tag"

literal :: Decoder -> Get Literal
literal decoder = do
  kind <- getWord8
  case kind of
    0 -> LitInt <$> getSVar; 1 -> LitWord <$> getUVar
    2 -> LitInt8 <$> signed (-128) 127
    3 -> LitInt16 <$> signed (-32768) 32767
    4 -> LitInt32 <$> signed (-2147483648) 2147483647
    5 -> LitInt64 <$> getSVar
    6 -> LitWord8 <$> unsigned 255; 7 -> LitWord16 <$> unsigned 65535
    8 -> LitWord32 <$> unsigned 4294967295; 9 -> LitWord64 <$> getUVar
    10 -> do
      bytes <- raw
      unless (BS.null bytes || BS.last bytes /= 0) (fail "Noncanonical compact BigNat magnitude")
      pure (LitBigNat (BS.foldr (\byte rest -> fromIntegral byte .|. (rest `shiftL` 8)) 0 bytes))
    11 -> do
      value <- getUVar
      unless (value <= 0x10ffff) (fail "Char literal exceeds Unicode code-point range")
      pure (LitChar (fromIntegral value))
    12 -> LitBytes <$> raw
    13 -> LitFloatBits <$> getWord32le
    14 -> LitDoubleBits <$> getWord64le
    15 -> pure LitNullAddr
    16 -> pure LitRubbish
    17 -> LitFunctionAddr <$> string decoder
    18 -> LitDataAddr <$> string decoder
    19 -> LitUnsupported <$> string decoder
    _ -> fail "Unknown compact literal tag"
  where
    raw = count decoder >>= getByteString
    signed :: Int64 -> Int64 -> Get Int64
    signed lo hi = do
      value <- getSVar
      unless (lo <= value && value <= hi) (fail "Signed literal exceeds its declared width")
      pure value
    unsigned hi = do
      value <- getUVar
      unless (value <= hi) (fail "Unsigned literal exceeds its declared width")
      pure value
