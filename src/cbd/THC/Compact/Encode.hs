-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Compact.Encode
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; binary records and strict ordered maps
--
-- One-pass typed executable encoding. Shapes are interned independently of
-- occurrence states; strings append directly to their private auxiliary stream.
module THC.Compact.Encode
  ( Encoder, newEncoder, setRecordObserver, encodeBinding, encodeExpr, encodeRep, encodeFacts, internString, containsDelimitedControl, containsHostSignatures, containsRecoveryFacts ) where

import Control.Monad (forM_, unless, void, when)
import Data.Binary.Put
import Data.Bits ((.&.), (.|.), shiftR)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Builder as Builder
import qualified Data.ByteString.Lazy as BL
import Data.IORef
import qualified Data.Map.Strict as Map
import qualified Data.Text.Encoding as Text
import qualified Data.Text as TextValue
import Data.Word (Word8, Word32, Word64)
import THC.Compact.Core
import THC.Compact.Types
import THC.Compact.Annotations
import THC.Compact.Facts
import THC.Compact.Wire
import THC.Compact.Writer

data Encoder = Encoder !Streams !(IORef (Map.Map BS.ByteString Span)) !(IORef (Map.Map Shape Word64))
  !(Maybe (IORef Builder.Builder)) !(IORef Word32) !(IORef (Maybe RecordObserver))

newEncoder :: Streams -> IO Encoder
newEncoder streams = Encoder streams <$> newIORef Map.empty <*> newIORef Map.empty <*> pure Nothing <*> newIORef 0 <*> newIORef Nothing

-- | Attach optional display-only origin recording for subsequent DATA records.
setRecordObserver :: Encoder -> Maybe RecordObserver -> IO ()
setRecordObserver (Encoder _ _ _ _ _ observer) = writeIORef observer

observe :: Encoder -> RecordKind -> IO a -> IO a
observe (Encoder streams _ _ _ _ observer) kind action = do
  selected <- readIORef observer
  case selected of
    Nothing -> action
    Just callbacks -> do
      streamOffset streams ExecutableData >>= enterRecord callbacks kind
      result <- action
      streamOffset streams ExecutableData >>= leaveRecord callbacks
      pure result

-- | Derived during expression emission, without a separate Core-body walk.
containsDelimitedControl :: Encoder -> IO Bool
containsDelimitedControl (Encoder _ _ _ _ found _) = (/= 0) . (.&. 1) <$> readIORef found

containsHostSignatures :: Encoder -> IO Bool
containsHostSignatures (Encoder _ _ _ _ found _) = (/= 0) . (.&. 16) <$> readIORef found

containsRecoveryFacts :: Encoder -> IO Bool
containsRecoveryFacts (Encoder _ _ _ _ found _) = (/= 0) . (.&. 32) <$> readIORef found

-- | Encode only the bounded known-start record in memory, while appending its
-- strings to the auxiliary stream. Constructor shapes are inline: admitting
-- header facts never requires reading an executable-body shape definition.
encodeFacts :: Encoder -> Facts -> IO BS.ByteString
encodeFacts (Encoder streams strings shapes _ found observer) facts = do
  output <- newIORef mempty
  let encoder = Encoder streams strings shapes (Just output) found observer
  number encoder (factsSchema facts)
  mapM_ (string encoder) [factsGhc facts, factsUnit facts, factsModule facts, factsBoundary facts]
  present encoder (list encoder (string encoder)) (factsProvidedModules facts)
  present encoder (targetLayout encoder) (factsTargetLayout facts)
  list encoder (constructor encoder) (factsConstructors facts)
  present encoder (foreignArtifacts encoder) (factsForeign facts)
  present encoder (exceptionBridge encoder) (factsExceptionBridge facts)
  present encoder (string encoder) (factsExceptionBridgeUnit facts)
  unless (length (factsPendingProvenance facts) == length pendingProvenanceNames)
    (fail "Compact header requires eight provenance-presence slots")
  mapM_ (\(slot,value) -> present encoder (provenance encoder slot) value)
    (zip [0..] (factsPendingProvenance facts))
  forM_ (factsClosureProvenance facts) $ \(ClosureProvenance roots modules missing origins) -> do
    tag encoder 1
    present encoder (list encoder (string encoder)) roots
    present encoder (list encoder (string encoder)) modules
    present encoder (list encoder (\(MissingDefinition key ty reason) -> mapM_ (string encoder) [key,ty,reason])) missing
    list encoder (\(BindingOrigin key origin owner) -> do
      string encoder key
      present encoder (string encoder) origin
      present encoder (string encoder) owner) origins
  forM_ (factsBackendPolicy facts) $ \(BackendPolicy def bindings) -> do
    tag encoder 2
    tag encoder (maybe 0 backendTag def)
    unless (and (zipWith (<) (map fst bindings) (drop 1 (map fst bindings))))
      (fail "Compact backend policy bindings must be strictly sorted")
    list encoder (\(key,backend) -> string encoder key >> tag encoder (backendTag backend)) bindings
  forM_ (factsRecovery facts) $ \value -> tag encoder 3 >> framed encoder (\bounded -> recoveryFacts bounded value)
  BL.toStrict . Builder.toLazyByteString <$> readIORef output
  where
    backendTag AstBackend = 1
    backendTag BytecodeBackend = 2


targetLayout :: Encoder -> TargetLayout -> IO ()
targetLayout encoder value = do
  names <- either fail pure (targetNumberNamesFor (targetLayoutSchema value))
  unless (length (targetNumbers value) == length names) (fail "Incomplete compact target-layout numbers")
  number encoder (targetDocumentSchema value)
  mapM_ (string encoder) [targetCompilerId value,targetCompilerAbi value,targetCompilerPlatform value,targetCompilerWay value]
  number encoder (targetLayoutSchema value)
  boolean encoder (targetProfiled value)
  number encoder (targetWordBytes value)
  enumeration encoder (targetEndianness value)
  string encoder (targetPlatform value)
  boolean encoder (targetTablesNextToCode value)
  mapM_ (number encoder) (targetNumbers value)

foreignArtifacts :: Encoder -> ForeignArtifacts -> IO ()
foreignArtifacts encoder (ForeignArtifacts schema execution stubs files) = do
  number encoder schema
  string encoder execution
  present encoder putStubs stubs
  list encoder putFile files
  where
    putStubs (Stubs header source initializers finalizers) = do
      string encoder header
      string encoder source
      list encoder putLabel initializers
      list encoder putLabel finalizers
    putLabel (Label initializer unit moduleName name) = boolean encoder initializer >> mapM_ (string encoder) [unit,moduleName,name]
    putFile (ForeignFile language source extension) = mapM_ (string encoder) [language,source,extension]

exceptionBridge :: Encoder -> ExceptionBridge -> IO ()
exceptionBridge encoder (ExceptionBridge schema unit moduleName box project payload exception) =
  number encoder schema >> mapM_ (string encoder) [unit,moduleName,box,project,payload,exception]

constructor :: Encoder -> Constructor -> IO ()
constructor encoder value = do
  string encoder (constructorId value)
  number encoder (constructorArity value)
  number encoder (constructorTag value)
  enumeration encoder (constructorKind value)
  list encoder (boolean encoder) (constructorStrictFields value)
  unless (all (/= Missing) (constructorFieldLifted value) && all (/= Missing) (constructorFieldReps value))
    (fail "Absent compact constructor array element")
  list encoder (present encoder (boolean encoder)) (constructorFieldLifted value)
  list encoder (present encoder (list encoder (primRep encoder))) (constructorFieldReps value)
  list encoder (inlineRep encoder) (constructorFieldTypes value)
  present encoder (number encoder) (constructorSumArity value)
  present encoder (enumFamily encoder) (constructorEnumFamily value)
  present encoder (tagFamily encoder) (constructorTagFamily value)
inlineRep :: Encoder -> Rep -> IO ()
inlineRep encoder (Rep layout state) = inlineShape layout >> evaluation encoder layout state
  where
    inlineShape shape = do
      enumeration encoder (shapeKind shape)
      present encoder (list encoder (primRep encoder)) (shapePrimReps shape)
      present encoder (vector encoder) (shapeVector shape)
      present encoder (enumeration encoder) (shapeAggregate shape)
      present encoder (list encoder inlineShape) (shapeComponents shape)
      present encoder (list encoder inlineShape) (shapeAlternatives shape)
      present encoder (number encoder) (shapeTagSlot shape)
      present encoder (list encoder (list encoder (number encoder))) (shapeAlternativeSlots shape)

provenance :: Encoder -> Int -> ModuleProvenance -> IO ()
provenance encoder slot value = case (slot,value) of
  (0,ForeignLinkRecord proof) -> foreignLink encoder proof
  (1,ImportsRecord proof) -> importProof encoder proof
  (2,ImportsRecord proof) -> importProof encoder proof
  (3,ExportsRecord proof) -> exports encoder proof
  (4,RegistrationRecord proof) -> registration encoder proof
  (5,ScalarLinkRecord proof) -> scalarLink encoder proof
  (6,NativeLinkRecord proof) -> nativeLink encoder proof
  (7,NativeArchiveRecord proof) -> nativeArchive encoder proof
  _ -> fail "Unimplemented or mismatched compact provenance slot"

foreignLink :: Encoder -> ForeignLink -> IO ()
foreignLink encoder (ForeignLink schema format unit moduleName sourceSha bitcodeSha bytes target symbols abi headers) = do
  number encoder schema
  mapM_ (string encoder) [format,unit,moduleName,sourceSha,bitcodeSha]
  blob encoder bytes
  string encoder target
  list encoder (string encoder) symbols
  list encoder pair abi
  present encoder (list encoder pair) headers
  where pair (name,digest) = string encoder name >> string encoder digest

linkPayload :: Encoder -> LinkPayload -> IO ()
linkPayload encoder (LinkPayload schema format profile unit target componentSha bitcodeSha bytes) = do
  number encoder schema
  mapM_ (string encoder) [format,profile,unit,target,componentSha,bitcodeSha]
  blob encoder bytes

scalarLink :: Encoder -> ScalarLink -> IO ()
scalarLink encoder (ScalarLink payload abi) = linkPayload encoder payload >> list encoder entry abi
  where entry (ScalarABI symbol name arguments result) = do
          string encoder symbol
          string encoder name
          list encoder (string encoder) arguments
          string encoder result

blob :: Encoder -> BS.ByteString -> IO ()
blob encoder bytes = number encoder (fromIntegral (BS.length bytes)) >> emit encoder (putByteString bytes)

nativeLink :: Encoder -> NativeLink -> IO ()
nativeLink encoder (NativeLink payload@(LinkPayload schema _ _ _ _ _ _ _) abi inputs companion dataSymbols finalizers components seeds) = do
  linkPayload encoder payload
  list encoder entry abi
  case inputs of
    Missing -> tag encoder 0
    Unknown -> tag encoder 1
    Known value@(NativeBuildInputs _ _ dependencies libraries _ _) -> do
      let extended (NativeLibrary _ _ _ _ _ Missing Missing Missing Missing) = False
          extended _ = True
          current = case dependencies of ComponentBuildDependencies {} -> True; _ -> False
          extra = current || any extended libraries
      tag encoder (if current then 4 else if extra then 3 else 2)
      nativeBuildInputs encoder extra value
  case (companion,dataSymbols,components) of
    (Missing,Missing,Nothing) -> tag encoder 0
    _ -> do
      tag encoder (case components of Nothing -> 3; Just _ -> 4)
      present encoder (\(digest,bytes) -> string encoder digest >> blob encoder bytes) companion
      present encoder (list encoder (string encoder)) dataSymbols
      forM_ components $ \(publicSymbols,dependencies) -> do
        list encoder (string encoder) publicSymbols
        list encoder (nativeComponent encoder) dependencies
  when (schema == 2) (list encoder (string encoder) finalizers)
  when (schema == 3) $ case seeds of
    Just values -> list encoder seed values
    Nothing -> fail "Native schema3 requires call seeds"
  where entry (NativeABI symbol name convention safety arguments result) = do
          string encoder symbol
          string encoder name
          enumeration encoder convention
          enumeration encoder safety
          list encoder (string encoder) arguments
          string encoder result
        seed (NativeCallSeed entryName digest bytes provider) = do
          string encoder entryName; string encoder digest; blob encoder bytes
          case provider of
            Nothing -> tag encoder 0
            Just (unit,component,symbol) -> do
              tag encoder 1; string encoder unit; string encoder component; string encoder symbol

nativeComponent :: Encoder -> NativeComponent -> IO ()
nativeComponent encoder (NativeComponent payload publicSymbols dependencies companion) = do
  linkPayload encoder payload
  list encoder (string encoder) publicSymbols
  list encoder (nativeComponent encoder) dependencies
  present encoder (\(digest,bytes) -> string encoder digest >> blob encoder bytes) companion

nativeBuildInputs :: Encoder -> Bool -> NativeBuildInputs -> IO ()
nativeBuildInputs encoder extended (NativeBuildInputs units providers dependencies libraries unresolved bridges) = do
  list encoder group units
  list encoder provider providers
  case dependencies of
    ArchiveBuildDependencies records -> present encoder (list encoder (nativeDependency encoder False)) records
    ComponentBuildDependencies records _ -> present encoder (list encoder dependencyRef) records
  list encoder library libraries
  strings unresolved
  list encoder bridge bridges
  case dependencies of
    ArchiveBuildDependencies _ -> pure ()
    ComponentBuildDependencies _ productRecord -> present encoder (nativeDependency encoder True) productRecord
  where
    strings = list encoder (string encoder)
    dependencyRef (NativeDependencyRef path unit component bitcode) = do
      strings path
      mapM_ (string encoder) [unit,component,bitcode]
    group (SingleCompile input) = tag encoder 0 >> compileInput encoder input
    group (GroupCompile inputs) = tag encoder 1 >> list encoder (compileInput encoder) inputs
    provider (NativeProvider name symbols path digest target input) = do
      string encoder name
      strings symbols
      mapM_ (string encoder) [path,digest,target]
      compileInput encoder input
    library (NativeLibrary name symbols compiler digest arguments dependencyArguments objcopy objcopySha objcopyArguments) = do
      string encoder name
      strings symbols
      string encoder compiler
      string encoder digest
      strings arguments
      when extended $ do
        present encoder strings dependencyArguments
        present encoder (string encoder) objcopy
        present encoder (string encoder) objcopySha
        present encoder (list encoder strings) objcopyArguments
    bridge (ArgumentBridge profile source sourceSha inputSha definitions) = do
      mapM_ (string encoder) [profile,source,sourceSha,inputSha]
      list encoder strings definitions

compileInput :: Encoder -> CompileInput -> IO ()
compileInput encoder (CompileInput compiler clang arguments language nativeTarget target files) = do
  string encoder compiler
  string encoder clang
  list encoder (string encoder) arguments
  present encoder (string encoder) language
  string encoder nativeTarget
  string encoder target
  list encoder (\(path,digest) -> string encoder path >> string encoder digest) files

nativeDependency :: Encoder -> Bool -> NativeDependency -> IO ()
nativeDependency encoder current (NativeDependency profile unit source registrationText digest archives products) = do
  string encoder profile
  string encoder unit
  sourceIdentity encoder current source
  string encoder registrationText
  string encoder digest
  list encoder archive archives
  list encoder productRecord products
  where
    archive (ArchiveProduct path hash members) = do
      string encoder path
      string encoder hash
      list encoder (\(name,value) -> string encoder name >> string encoder value) members
    productRecord (NativeProduct (NativePiece root path hash bitcode target input) bitcodeSha) = do
      mapM_ (string encoder) [root,path,hash,bitcode,target]
      compileInput encoder input
      string encoder bitcodeSha

sourceIdentity :: Encoder -> Bool -> SourceIdentity -> IO ()
sourceIdentity encoder current (SourceIdentity unit depends kind style name version flags component sourceSha cabalSha source) = do
  optionalString unit
  present encoder (list encoder (string encoder)) depends
  mapM_ optionalString [kind,style,name,version]
  present encoder (list encoder (\(key,value) -> string encoder key >> boolean encoder value)) flags
  mapM_ optionalString [component,sourceSha,cabalSha]
  if current then present encoder location source
    else unless (source == Missing) (fail "Legacy native source identity cannot contain a source location")
  where
    optionalString = present encoder (string encoder)
    location (NativeSource sourceType path repo) = do
      string encoder sourceType
      optionalString path
      present encoder (\(scheme,uri) -> string encoder scheme >> string encoder uri) repo

nativeArchive :: Encoder -> NativeArchive -> IO ()
nativeArchive encoder (NativeArchive schema profile execution unit moduleName unsupported reason unresolved artifact conflicts) = do
  number encoder schema
  mapM_ (string encoder) [profile,execution,unit,moduleName]
  list encoder (emittedCall encoder) unsupported
  present encoder (string encoder) reason
  list encoder (string encoder) unresolved
  present encoder (nativeLink encoder) artifact
  present encoder (list encoder (emittedCall encoder)) conflicts
  tag encoder 0 -- Reserved empty slot for the retired partial-entry protocol.

qualifiedName :: Encoder -> QualifiedName -> IO ()
qualifiedName encoder (QualifiedName unit moduleName occurrence namespace) =
  mapM_ (string encoder) [unit,moduleName,occurrence,namespace]

foreignType :: Encoder -> ForeignType -> IO ()
foreignType encoder value = case value of
  ForeignTyCon name arguments -> tag encoder 0 >> qualifiedName encoder name >> list encoder recurse arguments
  ForeignApplication function argument -> tag encoder 1 >> recurse function >> recurse argument
  ForeignArrow multiplicity argument result -> tag encoder 2 >> recurse multiplicity >> recurse argument >> recurse result
  ForeignVariable index -> tag encoder 3 >> number encoder index
  ForeignForall kind body -> tag encoder 4 >> recurse kind >> recurse body
  where recurse = foreignType encoder

importProof :: Encoder -> ImportProof -> IO ()
importProof encoder (ImportProof schema scope execution profile unit moduleName status) = do
  number encoder schema
  mapM_ (string encoder) [scope,execution,profile,unit,moduleName]
  case status of
    ImportsUnclassified reason -> tag encoder 0 >> string encoder reason
    ImportsRejected reason -> tag encoder 1 >> string encoder reason
    ImportsVerified wordBits original associations calls addresses wrappers partition -> do
      tag encoder 2
      number encoder wordBits
      foreignArtifacts encoder original
      list encoder association associations
      list encoder (foreignCallWith encoder (inlineRep encoder)) calls
      when (schema >= 2) (list encoder address addresses)
      when (schema >= 3) (list encoder wrapper wrappers)
      case (schema,partition) of
        (4,Just product') -> foreignArtifacts encoder product'
        (4,Nothing) -> fail "Mixed import proof lacks its stock import partition"
        (_,Nothing) -> pure ()
        _ -> fail "Import partition requires schema 4"
  where
    wrapper (WrapperAssociation (ExportAssociation binderName helper convention declared normalized role arguments result effect) encoding) = do
      qualifiedName encoder binderName
      string encoder helper
      enumeration encoder convention
      foreignType encoder declared
      foreignType encoder normalized
      string encoder role
      list encoder (foreignType encoder) arguments
      foreignType encoder result
      enumeration encoder effect
      string encoder encoding
    address (AddressAssociation binderName header symbol function convention declared normalized role callback) = do
      qualifiedName encoder binderName
      present encoder (string encoder) header
      string encoder symbol
      boolean encoder function
      enumeration encoder convention
      foreignType encoder declared
      foreignType encoder normalized
      string encoder role
      boolean encoder (case callback of Just _ -> True; Nothing -> False)
      case callback of
        Nothing -> pure ()
        Just (arguments,result) -> list encoder (string encoder) arguments >> string encoder result
    association (ImportAssociation binderName header symbol owner function convention safety declared normalized role emitted) = do
      qualifiedName encoder binderName
      present encoder (string encoder) header
      string encoder symbol
      present encoder (string encoder) owner
      boolean encoder function
      enumeration encoder convention
      enumeration encoder safety
      foreignType encoder declared
      foreignType encoder normalized
      string encoder role
      emittedCall encoder emitted

emittedCall :: Encoder -> EmittedCall -> IO ()
emittedCall encoder (EmittedCall symbol unit convention safety arguments result) = do
  string encoder symbol
  present encoder (string encoder) unit
  enumeration encoder convention
  enumeration encoder safety
  list encoder (string encoder) arguments
  list encoder (string encoder) result

exports :: Encoder -> Exports -> IO ()
exports encoder (Exports schema producer scope execution unit moduleName associations) = do
  number encoder schema
  mapM_ (string encoder) [producer,scope,execution,unit,moduleName]
  list encoder association associations
  where
    association (ExportAssociation binderName symbol convention declared normalized role arguments result effect) = do
      qualifiedName encoder binderName
      string encoder symbol
      enumeration encoder convention
      foreignType encoder declared
      foreignType encoder normalized
      string encoder role
      list encoder (foreignType encoder) arguments
      foreignType encoder result
      enumeration encoder effect

registration :: Encoder -> Registration -> IO ()
registration encoder (Registration schema scope execution profile status) = do
  number encoder schema
  mapM_ (string encoder) [scope,execution,profile]
  case status of
    RegistrationUnclassified reason -> tag encoder 0 >> string encoder reason
    RegistrationRejected reason -> tag encoder 1 >> string encoder reason
    RegistrationVerified roots wordBits original expected -> do
      tag encoder 2
      list encoder (qualifiedName encoder) roots
      number encoder wordBits
      foreignArtifacts encoder original
      exports encoder expected

-- | Intern semantic UTF-8, never literal raw bytes. Referenced string spans are
-- direct byte positions, with no separate string-ID table.
internString :: Encoder -> BS.ByteString -> IO Span
internString (Encoder streams strings _ _ _ _) bytes = do
  table <- readIORef strings
  case Map.lookup bytes table of
    Just ref -> pure ref
    Nothing -> do
      either (fail . show) (const (pure ())) (Text.decodeUtf8' bytes)
      start <- appendBytes streams CommonStrings bytes
      let ref = Span start (fromIntegral (BS.length bytes))
      modifyIORef' strings (Map.insert (BS.copy bytes) ref)
      pure ref

emit :: Encoder -> Put -> IO ()
emit (Encoder streams _ _ Nothing _ _) = void . appendRecord streams ExecutableData
emit (Encoder _ _ _ (Just output) _ _) = \record ->
  modifyIORef' output (<> Builder.lazyByteString (runPut record))

tag :: Encoder -> Word8 -> IO ()
tag encoder = emit encoder . putWord8

number :: Encoder -> Word64 -> IO ()
number encoder = emit encoder . putUVar

boolean :: Encoder -> Bool -> IO ()
boolean encoder value = tag encoder (if value then 1 else 0)

enumeration :: Enum a => Encoder -> a -> IO ()
enumeration encoder = tag encoder . fromIntegral . fromEnum

string :: Encoder -> BS.ByteString -> IO ()
string encoder bytes = internString encoder bytes >>= emit encoder . putSpan

present :: Encoder -> (a -> IO ()) -> Presence a -> IO ()
present encoder _ Missing = tag encoder 0
present encoder _ Unknown = tag encoder 1
present encoder action (Known value) = tag encoder 2 >> action value

list :: Encoder -> (a -> IO ()) -> [a] -> IO ()
list encoder action values = number encoder (fromIntegral (length values)) >> mapM_ action values

identity :: Encoder -> Identity -> IO ()
identity encoder value = case value of
  Global key -> tag encoder 0 >> string encoder key
  Local ordinal -> tag encoder 1 >> number encoder ordinal

encodeBinding :: Encoder -> Binding -> IO Word64
encodeBinding encoder@(Encoder streams _ _ _ found _) binding = observe encoder (BindingRecord (bindingIdentity binding)) $ do
  start <- streamOffset streams ExecutableData
  case bindingCallable binding of
    Missing -> case bindingHostSignature binding of
      Missing -> pure ()
      signature -> do
        modifyIORef' found (.|. 16)
        tag encoder 2 -- legacy host-only prefix uses ordinary Shape references
        present encoder (hostSignature encoder) signature
    callable -> do
      modifyIORef' found (.|. 32)
      unless (bindingHostSignature binding == Missing) (modifyIORef' found (.|. 16))
      tag encoder 3
      framed encoder $ \bounded -> do
        present bounded (typeTerm bounded) callable
        present bounded (inlineHostSignature bounded) (bindingHostSignature binding)
  identity encoder (bindingIdentity binding)
  enumeration encoder (bindingEntryType binding)
  present encoder (boolean encoder) (bindingLifted binding)
  number encoder (bindingArity binding)
  present encoder (encodeRep encoder) (bindingRep binding)
  present encoder (idInfo encoder) (bindingInfo binding)
  present encoder (list encoder (boolean encoder)) (bindingEntryStrict binding)
  present encoder (string encoder) (bindingEntryStrictSource binding)
  present encoder (number encoder) (bindingJoinValueArity binding)
  present encoder (encodeRep encoder) (bindingJoinResultRep binding)
  encodeExpr encoder (bindingExpr binding)
  pure start

hostSignature :: Encoder -> HostSignature -> IO ()
hostSignature encoder (HostSignature inputs result) = list encoder hostType inputs >> hostType result
  where
    hostType (HostType proof carriers) = encodeRep encoder proof >> list encoder (enumeration encoder) carriers

inlineHostSignature :: Encoder -> HostSignature -> IO ()
inlineHostSignature encoder (HostSignature inputs result) = list encoder hostType inputs >> hostType result
  where hostType (HostType proof carriers) = inlineRep encoder proof >> list encoder (enumeration encoder) carriers

-- Recovery terms are inline and have no DATA Shape references.
typeName :: Encoder -> TypeName -> IO ()
typeName encoder (TypeName unit owner namespace parent occurrence) = do
  mapM_ (string encoder) [unit,owner]
  unless (namespace <= 4) (fail "Invalid recovery Name namespace")
  tag encoder namespace
  maybeValue encoder (string encoder) parent
  string encoder occurrence

maybeValue :: Encoder -> (a -> IO ()) -> Maybe a -> IO ()
maybeValue encoder action value = case value of
  Nothing -> tag encoder 0
  Just found -> tag encoder 1 >> action found

typeBinder :: Encoder -> TypeBinder -> IO ()
typeBinder encoder (TypeBinder name coercion kind) = string encoder name >> boolean encoder coercion >> typeTerm encoder kind

typeArguments :: Encoder -> [TypeArgument] -> IO ()
typeArguments encoder = list encoder (\(TypeArgument visibility ty) -> enumeration encoder visibility >> typeTerm encoder ty)

tyConSort :: Encoder -> TyConSort -> IO ()
tyConSort encoder value = case value of
  NormalTyCon -> tag encoder 0
  TupleTyCon arity sort -> tag encoder 1 >> number encoder arity >> enumeration encoder sort
  SumTyCon arity -> tag encoder 2 >> number encoder arity
  EqualityTyCon -> tag encoder 3

typeTerm :: Encoder -> TypeTerm -> IO ()
typeTerm encoder value = case value of
  TypeVar name -> tag encoder 0 >> string encoder name
  TypeCon name promoted sort args -> tag encoder 1 >> typeName encoder name >> boolean encoder promoted >> tyConSort encoder sort >> typeArguments encoder args
  TypeApp headType args -> tag encoder 2 >> typeTerm encoder headType >> typeArguments encoder args
  TypeFun flag multiplicity argument result -> tag encoder 3 >> enumeration encoder flag >> mapM_ (typeTerm encoder) [multiplicity,argument,result]
  TypeForall quantified visibility body -> tag encoder 4 >> typeBinder encoder quantified >> enumeration encoder visibility >> typeTerm encoder body
  TypeTuple sort promoted args -> tag encoder 5 >> enumeration encoder sort >> boolean encoder promoted >> typeArguments encoder args
  TypeNat value' -> tag encoder 6 >> string encoder (Text.encodeUtf8 (TextValue.pack (show value')))
  TypeSymbol points -> tag encoder 7 >> list encoder codePoint points
  TypeChar point -> tag encoder 8 >> codePoint point
  TypeCast ty co -> tag encoder 9 >> typeTerm encoder ty >> coTerm encoder co
  TypeCoercion co -> tag encoder 10 >> coTerm encoder co
  where codePoint point = do
          unless (point <= 0x10ffff) (fail "Invalid recovery literal code point")
          number encoder (fromIntegral point)

coTerm :: Encoder -> CoTerm -> IO ()
coTerm encoder value = case value of
  CoRefl ty -> tag encoder 0 >> typeTerm encoder ty
  CoGRefl role ty co -> tag encoder 1 >> enumeration encoder role >> typeTerm encoder ty >> maybeValue encoder child co
  CoFun role mult arg res -> tag encoder 2 >> enumeration encoder role >> mapM_ child [mult,arg,res]
  CoCon role name promoted sort args -> tag encoder 3 >> enumeration encoder role >> typeName encoder name >> boolean encoder promoted >> tyConSort encoder sort >> children args
  CoApp a b -> binary 4 a b
  CoForall quantified vl vr kind body -> tag encoder 5 >> typeBinder encoder quantified >> enumeration encoder vl >> enumeration encoder vr >> child kind >> child body
  CoVar name -> tag encoder 6 >> string encoder name
  CoUniv evidence role a b args -> do
    tag encoder 7
    case evidence of
      PhantomProvenance -> tag encoder 0
      ProofIrrelevance -> tag encoder 1
      PluginProvenance name -> tag encoder 2 >> string encoder name
    enumeration encoder role >> typeTerm encoder a >> typeTerm encoder b >> children args
  CoSym co -> unary 8 co
  CoTrans a b -> binary 9 a b
  CoSelect selector co -> do
    tag encoder 10
    case selector of
      TyConSelector index role -> tag encoder 0 >> number encoder index >> enumeration encoder role
      ForallSelector -> tag encoder 1
      MultiplicitySelector -> tag encoder 2
      ArgumentSelector -> tag encoder 3
      ResultSelector -> tag encoder 4
    child co
  CoLeft co -> tag encoder 11 >> tag encoder 0 >> child co
  CoRight co -> tag encoder 11 >> tag encoder 1 >> child co
  CoInst a b -> binary 12 a b
  CoKind co -> unary 13 co
  CoSub co -> unary 14 co
  CoAxiom rule args -> do
    tag encoder 15
    case rule of
      BuiltinRule name -> tag encoder 0 >> string encoder name
      UnbranchedRule name -> tag encoder 1 >> typeName encoder name
      BranchedRule name branch -> tag encoder 2 >> typeName encoder name >> number encoder branch
    children args
  where child = coTerm encoder
        children = list encoder child
        unary code co = tag encoder code >> child co
        binary code a b = tag encoder code >> child a >> child b

typeParameter :: Encoder -> TypeParameter -> IO ()
typeParameter encoder (TypeParameter quantified visibility) = do
  typeBinder encoder quantified
  case visibility of
    AnonymousParameter -> tag encoder 0
    NamedParameter flag -> tag encoder 1 >> enumeration encoder flag

typeAxiom :: Encoder -> AxiomFact -> IO ()
typeAxiom encoder (AxiomFact name tycon role branches) = do
  typeName encoder name >> typeName encoder tycon >> enumeration encoder role
  list encoder (\(AxiomBranch binders roles lhs rhs) -> do
    list encoder (typeBinder encoder) binders
    list encoder (enumeration encoder) roles
    list encoder (typeTerm encoder) lhs
    typeTerm encoder rhs) branches

recoveryFacts :: Encoder -> RecoveryFacts -> IO ()
recoveryFacts encoder (RecoveryFacts nominal constructors axioms) = do
  list encoder (\(NominalFact name parameters result roles form rhs axiom selectors) -> do
    typeName encoder name
    list encoder (typeParameter encoder) parameters
    typeTerm encoder result
    list encoder (enumeration encoder) roles
    enumeration encoder form
    maybeValue encoder (typeTerm encoder) rhs
    maybeValue encoder (typeName encoder) axiom
    list encoder (typeName encoder) selectors) nominal
  list encoder (\(ConstructorFact name parent worker universal existential fields workerType) -> do
    mapM_ (typeName encoder) [name,parent,worker]
    list encoder (typeBinder encoder) universal
    list encoder (typeBinder encoder) existential
    list encoder (\(ScaledType multiplicity ty) -> typeTerm encoder multiplicity >> typeTerm encoder ty) fields
    typeTerm encoder workerType) constructors
  list encoder (typeAxiom encoder) axioms

framed :: Encoder -> (Encoder -> IO ()) -> IO ()
framed encoder@(Encoder streams strings shapes _ found observer) action = do
  output <- newIORef mempty
  action (Encoder streams strings shapes (Just output) found observer)
  bytes <- BL.toStrict . Builder.toLazyByteString <$> readIORef output
  number encoder (fromIntegral (BS.length bytes))
  emit encoder (putByteString bytes)

binder :: Encoder -> Binder -> IO ()
binder encoder value = observe encoder (BinderRecord (binderOrdinal value)) $ do
  number encoder (binderOrdinal value)
  enumeration encoder (binderEntryType value)
  present encoder (boolean encoder) (binderLifted value)
  present encoder (boolean encoder) (binderCoercion value)
  present encoder (encodeRep encoder) (binderRep value)
  present encoder (idInfo encoder) (binderInfo value)

idInfo :: Encoder -> IdInfo -> IO ()
idInfo encoder (IdInfo joinArity cbvEligible marks) = do
  present encoder (number encoder) joinArity
  present encoder (boolean encoder) cbvEligible
  present encoder (list encoder (boolean encoder)) marks

encodeExpr :: Encoder -> Expr -> IO ()
encodeExpr encoder@(Encoder _ _ _ _ found _) expression = observe encoder ExpressionRecord $ case expression of
  Var metadata key -> prefix 0 metadata >> identity encoder key
  Prim metadata name -> do
    when (name == "prompt#" || name == "control0#")
      (modifyIORef' found (.|. 1))
    prefix 1 metadata >> string encoder name
  Lit metadata value -> prefix 2 metadata >> literal encoder value
  Lam metadata parameters body -> prefix 3 metadata >> list encoder (binder encoder) parameters >> child body
  Con metadata key arity -> prefix 4 metadata >> string encoder key >> number encoder arity
  App metadata function arguments lifted hnf speculate -> do
    unless (all (/= Missing) lifted) (fail "Absent compact argument-lifted array element")
    prefix 5 metadata
    child function
    list encoder child arguments
    list encoder (present encoder (boolean encoder)) lifted
    boolean encoder hnf
    boolean encoder speculate
  Let metadata recursive bindings body -> do
    prefix 6 metadata
    boolean encoder recursive
    list encoder (void . encodeBinding encoder) bindings
    child body
  Case metadata scrutinee ordinal information alternatives -> do
    prefix 7 metadata
    child scrutinee
    number encoder ordinal
    present encoder (binder encoder) information
    list encoder (alternative encoder) alternatives
  Void metadata -> prefix 8 metadata
  Unsupported metadata diagnostic -> prefix 9 metadata >> string encoder diagnostic
  where
    prefix kind metadata = tag encoder kind >> meta encoder metadata
    child = encodeExpr encoder

meta :: Encoder -> Meta -> IO ()
meta encoder value = do
  present encoder (encodeRep encoder) (metaRep value)
  present encoder (encodeRep encoder) (metaResultRep value)
  present encoder (list encoder (boolean encoder)) (metaEntryStrict value)
  present encoder (string encoder) (metaEntryStrictSource value)
  present encoder (callDemand encoder) (metaCallDemand value)
  present encoder (foreignCall encoder) (metaForeignCall value)
  present encoder (exceptionPayload encoder) (metaExceptionPayload value)
  present encoder (enumFamily encoder) (metaEnumFamily value)
  present encoder (tagFamily encoder) (metaTagFamily value)
  present encoder (string encoder) (metaUnsafeEqualityCase value)

callDemand :: Encoder -> CallDemand -> IO ()
callDemand encoder (CallDemand arity marks) = number encoder arity >> list encoder (boolean encoder) marks

exceptionPayload :: Encoder -> ExceptionPayload -> IO ()
exceptionPayload encoder (ExceptionPayload schema nominal) = number encoder schema >> string encoder nominal

enumFamily :: Encoder -> EnumFamily -> IO ()
enumFamily encoder (EnumFamily nominal constructors) = string encoder nominal >> list encoder (string encoder) constructors

tagFamily :: Encoder -> TagFamily -> IO ()
tagFamily encoder (TagFamily family limit small) = enumFamily encoder family >> number encoder limit >> boolean encoder small

foreignCall :: Encoder -> ForeignCall -> IO ()
foreignCall encoder = foreignCallWith encoder (encodeRep encoder)

foreignCallWith :: Encoder -> (Rep -> IO ()) -> ForeignCall -> IO ()
foreignCallWith encoder representation value = do
  unless (case (foreignSchema value,foreignArgumentTypes value) of
      (1,Missing) -> True
      (2,Known types) -> fromIntegral (length types) == foreignArity value &&
        any (/= Unknown) types && all (`elem` [Unknown,Known "ByteArray#",Known "MutableByteArray#"]) types
      _ -> False) (fail "Foreign argument types disagree with schema or arity")
  number encoder (foreignSchema value)
  case foreignTarget value of
    StaticTarget symbol unit isFunction -> do
      tag encoder 0
      string encoder symbol
      present encoder (string encoder) unit
      boolean encoder isFunction
    DynamicTarget -> tag encoder 1
  enumeration encoder (foreignConvention value)
  enumeration encoder (foreignSafety value)
  number encoder (foreignArity value)
  number encoder (foreignSuppliedArity value)
  list encoder representation (foreignArgumentReps value)
  representation (foreignResultRep value)
  present encoder (string encoder) (foreignIntrinsic value)
  present encoder (string encoder) (foreignJavaScriptSource value)
  when (foreignSchema value == 2) $
    present encoder (list encoder (present encoder (string encoder))) (foreignArgumentTypes value)

alternative :: Encoder -> Alternative -> IO ()
alternative encoder value = case value of
  DefaultAlt binders body -> tag encoder 0 >> suffix binders body
  DataAlt constructorKey binders body -> tag encoder 1 >> string encoder constructorKey >> suffix binders body
  LiteralAlt discriminator binders body -> tag encoder 2 >> literal encoder discriminator >> suffix binders body
  where suffix binders body = list encoder (binder encoder) binders >> encodeExpr encoder body

encodeRep :: Encoder -> Rep -> IO ()
encodeRep encoder (Rep layout state) = shapeUse encoder layout >> evaluation encoder layout state

shapeUse :: Encoder -> Shape -> IO ()
shapeUse encoder@(Encoder streams _ shapes _ _ _) layout = do
  table <- readIORef shapes
  case Map.lookup layout table of
    Just offset -> tag encoder 1 >> number encoder offset
    Nothing -> do
      start <- streamOffset streams ExecutableData
      tag encoder 0
      enumeration encoder (shapeKind layout)
      present encoder (list encoder (primRep encoder)) (shapePrimReps layout)
      present encoder (vector encoder) (shapeVector layout)
      present encoder (enumeration encoder) (shapeAggregate layout)
      present encoder (list encoder (shapeUse encoder)) (shapeComponents layout)
      present encoder (list encoder (shapeUse encoder)) (shapeAlternatives layout)
      present encoder (number encoder) (shapeTagSlot layout)
      present encoder (list encoder (list encoder (number encoder))) (shapeAlternativeSlots layout)
      modifyIORef' shapes (Map.insert layout start)

evaluation :: Encoder -> Shape -> Evaluation -> IO ()
evaluation encoder layout (Evaluation evaluated children) = do
  let layouts = shapeChildren layout
  unless (length layouts == length children) $ fail "Representation occurrence does not match its shape"
  present encoder (boolean encoder) evaluated
  sequence_ (zipWith (evaluation encoder) layouts children)

vector :: Encoder -> Vector -> IO ()
vector encoder (Vector lanes element) = number encoder lanes >> enumeration encoder element

primRep :: Encoder -> PrimRep -> IO ()
primRep encoder value = case value of
  IntRep -> tag encoder 0; WordRep -> tag encoder 1
  Int8Rep -> tag encoder 2; Int16Rep -> tag encoder 3; Int32Rep -> tag encoder 4; Int64Rep -> tag encoder 5
  Word8Rep -> tag encoder 6; Word16Rep -> tag encoder 7; Word32Rep -> tag encoder 8; Word64Rep -> tag encoder 9
  FloatRep -> tag encoder 10; DoubleRep -> tag encoder 11; AddrRep -> tag encoder 12
  BoxedUnknown -> tag encoder 13; BoxedLifted -> tag encoder 14; BoxedUnlifted -> tag encoder 15
  VecRep value' -> tag encoder 16 >> vector encoder value'

literal :: Encoder -> Literal -> IO ()
literal encoder value = case value of
  LitInt n -> signed 0 minBound maxBound n
  LitWord n -> unsigned 1 maxBound n
  LitInt8 n -> signed 2 (-128) 127 n
  LitInt16 n -> signed 3 (-32768) 32767 n
  LitInt32 n -> signed 4 (-2147483648) 2147483647 n
  LitInt64 n -> signed 5 minBound maxBound n
  LitWord8 n -> unsigned 6 255 n
  LitWord16 n -> unsigned 7 65535 n
  LitWord32 n -> unsigned 8 4294967295 n
  LitWord64 n -> unsigned 9 maxBound n
  LitBigNat n -> do
    unless (n >= 0) (fail "Negative BigNat literal")
    raw 10 (BS.pack (magnitude n))
  LitChar c -> do
    unless (c <= 0x10ffff) (fail "Char literal exceeds Unicode code-point range")
    tag encoder 11 >> number encoder (fromIntegral c)
  LitBytes bytes -> raw 12 bytes
  LitFloatBits bits -> tag encoder 13 >> emit encoder (putWord32le bits)
  LitDoubleBits bits -> tag encoder 14 >> emit encoder (putWord64le bits)
  LitNullAddr -> tag encoder 15
  LitRubbish -> tag encoder 16
  LitFunctionAddr symbol -> tag encoder 17 >> string encoder symbol
  LitDataAddr symbol -> tag encoder 18 >> string encoder symbol
  LitUnsupported diagnostic -> tag encoder 19 >> string encoder diagnostic
  where
    signed kind lo hi n = do
      unless (lo <= n && n <= hi) (fail "Signed literal exceeds its declared width")
      tag encoder kind >> emit encoder (putSVar n)
    unsigned kind hi n = do
      unless (n <= hi) (fail "Unsigned literal exceeds its declared width")
      tag encoder kind >> number encoder n
    raw kind bytes = tag encoder kind >> number encoder (fromIntegral (BS.length bytes)) >> emit encoder (putByteString bytes)
    magnitude 0 = []
    magnitude n = fromIntegral (n .&. 255) : magnitude (n `shiftR` 8)
