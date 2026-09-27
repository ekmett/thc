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
  ( Encoder, newEncoder, setRecordObserver, encodeBinding, encodeExpr, encodeRep, encodeFacts, internString, containsDelimitedControl ) where

import Control.Monad (unless, void, when)
import Data.Binary.Put
import Data.Bits ((.&.), shiftR)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Builder as Builder
import qualified Data.ByteString.Lazy as BL
import Data.IORef
import qualified Data.Map.Strict as Map
import qualified Data.Text.Encoding as Text
import Data.Word (Word8, Word64)
import THC.Compact.Core
import THC.Compact.Annotations
import THC.Compact.Facts
import THC.Compact.Wire
import THC.Compact.Writer

data Encoder = Encoder !Streams !(IORef (Map.Map BS.ByteString Span)) !(IORef (Map.Map Shape Word64))
  !(Maybe (IORef Builder.Builder)) !(IORef Bool) !(IORef (Maybe RecordObserver))

newEncoder :: Streams -> IO Encoder
newEncoder streams = Encoder streams <$> newIORef Map.empty <*> newIORef Map.empty <*> pure Nothing <*> newIORef False <*> newIORef Nothing

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
containsDelimitedControl (Encoder _ _ _ _ found _) = readIORef found

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
  BL.toStrict . Builder.toLazyByteString <$> readIORef output

targetLayout :: Encoder -> TargetLayout -> IO ()
targetLayout encoder value = do
  number encoder (targetDocumentSchema value)
  mapM_ (string encoder) [targetCompilerId value,targetCompilerAbi value,targetCompilerPlatform value,targetCompilerWay value]
  number encoder (targetLayoutSchema value)
  boolean encoder (targetProfiled value)
  number encoder (targetWordBytes value)
  enumeration encoder (targetEndianness value)
  string encoder (targetPlatform value)
  boolean encoder (targetTablesNextToCode value)
  unless (length (targetNumbers value) == length targetNumberNames) (fail "Incomplete compact target-layout numbers")
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
  (1,ImportsRecord proof) -> importProof encoder proof
  (2,ImportsRecord proof) -> importProof encoder proof
  (3,ExportsRecord proof) -> exports encoder proof
  (4,RegistrationRecord proof) -> registration encoder proof
  _ -> fail "Unimplemented or mismatched compact provenance slot"

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
    ImportsVerified wordBits original associations calls -> do
      tag encoder 2
      number encoder wordBits
      foreignArtifacts encoder original
      list encoder association associations
      list encoder (foreignCallWith encoder (inlineRep encoder)) calls
  where
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
encodeBinding encoder@(Encoder streams _ _ _ _ _) binding = observe encoder (BindingRecord (bindingIdentity binding)) $ do
  start <- streamOffset streams ExecutableData
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
      (writeIORef found True)
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
  LitRubbish rep -> tag encoder 16 >> primRep encoder rep
  LitFunctionAddr symbol -> tag encoder 17 >> string encoder symbol
  LitDataAddr symbol -> tag encoder 18 >> string encoder symbol
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
