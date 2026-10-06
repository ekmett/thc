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
module THC.Compact.Inspect (inspectContainer, inspectName, inspectSource, inspectSources, unpackContainer, unpackContainerWithMethods, moduleJSON) where

import Data.Aeson
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import Data.Binary.Get (getByteString, getWord64le)
import Data.Bits ((.&.))
import qualified Data.ByteString as BS
import Data.List (sortOn)
import qualified Data.Set as Set
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.Word (Word16, Word64)
import Numeric (showHex)
import THC.Compact.Core
import THC.Compact.Decode
import THC.Compact.Debug
import THC.Compact.Facts
import THC.Compact.Types.JSON ()
import THC.Compact.Wire
import THC.Compact.Zip (readZipWithMethods)

-- | Decode original data order, not digest order. The caller explicitly reads
-- the one container to inspect; normal runtime demand loading is independent.
inspectContainer :: BS.ByteString -> Either String Value
inspectContainer bytes = do
  (header,factsBytes,segments) <- unpackContainer bytes
  case segments of
    [payload,strings,_,_,_,directory] -> do
      facts <- decodeMetadata factsBytes
      rows <- mapM (\start -> decodeExact ((,) <$> getByteString 16 <*> getWord64le)
        (BS.take 24 (BS.drop start directory))) [0,24..BS.length directory-1]
      let hasSignatures = headerSummaries header .&. 16 /= 0
      bindings <- mapM (\(_,offset) -> fst <$> decodeBindingAtWithFeatures hasSignatures (headerSummaries header .&. 32 /= 0) payload strings offset) (sortOn snd rows)
      pure (moduleJSON facts bindings)
    _ -> Left "Compact container requires six segments"

-- | Inspect only the selected display-name entry. A constructor uses the
-- reserved max-uint64 scope and its one-based header slot.
inspectName :: BS.ByteString -> Word64 -> Word64 -> Either String Value
inspectName bytes scope slot = do
  (_,_,segments) <- unpackContainer bytes
  case segments of
    [_,_,names,_,_,_] -> maybe Null str <$> nameAt names scope slot
    _ -> Left "Compact container requires six segments"

-- | Inspect one source location by immutable DATA position, without decoding
-- any executable record or unselected debug-name entry.
inspectSource :: BS.ByteString -> Word64 -> Either String Value
inspectSource bytes position = do
  (_,_,segments) <- unpackContainer bytes
  case segments of
    [payload,strings,_,filenames,positions,_] -> do
      let dataSize = fromIntegral (BS.length payload)
      location <- locationAt filenames positions strings dataSize position
      pure $ case location of
        Nothing -> Null
        Just (SourceLocation primary notes) -> object
          ["primaryIndex" .= primary,"notes" .= map note notes]
    _ -> Left "Compact container requires six segments"
  where
    note (SourceFile fileId path content,SourcePosition identifier label sl sc el ec index size) = object $
      [("id",str identifier),("file",str fileId),("path",str path),
       ("startLine",toJSON sl),("startColumn",toJSON sc),("endLine",toJSON el),("endColumn",toJSON ec)]
      ++ p "content" str content ++ p "label" str label ++ p "charIndex" toJSON index ++ p "charLength" toJSON size

-- | Explicit offline source-table view, independent of executable inspection.
-- Every record comes from the persisted debug tables and keeps its DATA range.
inspectSources :: BS.ByteString -> Either String Value
inspectSources bytes = do
  (_,_,segments) <- unpackContainer bytes
  case segments of
    [payload,strings,_,filenames,positions,_] -> do
      let dataSize = fromIntegral (BS.length payload)
      files <- sourceFiles filenames strings dataSize
      locations <- sourceLocations filenames positions strings dataSize
      let spans = Set.toAscList (Set.fromList
            [(fileId,position) | (_,_,SourceLocation _ notes) <- locations,
              (SourceFile fileId _ _,position) <- notes])
      pure $ object ["dataSize" .= dataSize,"sourceFiles" .= map file files,
        "sourceSpans" .= map spanRecord spans,"locations" .= map location locations]
    _ -> Left "Compact container requires six segments"
  where
    file (SourceFile identifier path content) = object $
      [("id",str identifier),("path",str path)] ++ p "content" str content
    spanRecord (fileId,SourcePosition identifier label sl sc el ec index size) = object $
      [("id",str identifier),("file",str fileId),("startLine",toJSON sl),("startColumn",toJSON sc),
       ("endLine",toJSON el),("endColumn",toJSON ec)]
      ++ p "label" str label ++ p "charIndex" toJSON index ++ p "charLength" toJSON size
    location (start,end,SourceLocation primary notes) = object
      ["start" .= start,"end" .= end,"primaryIndex" .= primary,
       "source" .= positionId (snd (notes !! fromIntegral primary)),
       "sourceNotes" .= map (positionId . snd) notes]
    positionId (SourcePosition key _ _ _ _ _ _ _) = str key

-- | Explicit offline archive inspection, including CRC/inflation checks. The
-- returned payloads retain their original member-relative coordinate systems.
unpackContainer :: BS.ByteString -> Either String (Header,BS.ByteString,[BS.ByteString])
unpackContainer bytes = do
  (header,facts,segments,_) <- unpackContainerWithMethods bytes
  pure (header,facts,segments)

unpackContainerWithMethods :: BS.ByteString -> Either String (Header,BS.ByteString,[BS.ByteString],[(String,Word16)])
unpackContainerWithMethods bytes = do
  entries <- readZipWithMethods bytes
  let members = [(name,payload) | (name,_,payload) <- entries]
  let member key = maybe (Left ("Missing CBD member: " ++ key)) Right (lookup key members)
  headerBytes <- member "header"
  header <- decodeExact getHeader (BS.take 32 headerBytes)
  segments <- mapM member ["data","strings","names","filenames","line-columns","symbols"]
  validateContainer header (map (fromIntegral . BS.length) segments)
  pure (header,BS.drop 32 headerBytes,segments,[(name,method) | (name,method,_) <- entries])

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
  ++ p "hostSignature" hostSignature (bindingHostSignature value)

hostSignature :: HostSignature -> Value
hostSignature (HostSignature inputs result) = object [("inputs",arr hostType inputs),("result",hostType result)]
  where
    hostType (HostType proof carriers) = object [("rep",rep proof),("carriers",arr carrier carriers)]
    carrier HostPlain = Null
    carrier HostObject = String "object"
    carrier HostInteropLibrary = String "interop-library"

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
  Unsupported m diagnostic -> node "unsupported" m [str diagnostic]
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
  ++ p "argumentTypes" (arr (\value' -> case value' of Known name -> str name; _ -> Null)) (foreignArgumentTypes value)
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
  LitRubbish -> (String "rubbish",Null)
  LitFunctionAddr symbol -> (String "function-addr",str symbol)
  LitDataAddr symbol -> (String "data-addr",str symbol)
  LitUnsupported diagnostic -> (String "unsupported",str diagnostic)
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
  "constructors" .= arr constructor (factsConstructors facts),"bindings" .= arr originalBinding bindings]
  ++ p "providedModules" (arr str) (factsProvidedModules facts) ++ p "targetLayout" targetLayout (factsTargetLayout facts)
  ++ p "foreign" foreignArtifacts (factsForeign facts) ++ p "foreignExceptionBridge" exceptionBridge (factsExceptionBridge facts)
  ++ p "foreignExceptionBridgeUnit" str (factsExceptionBridgeUnit facts)
  ++ concat (zipWith (\key -> p (Key.fromText (Text.decodeUtf8 key)) provenance)
       pendingProvenanceNames (factsPendingProvenance facts))
  ++ maybe [] closureFields (factsClosureProvenance facts)
  ++ maybe [] (\(BackendPolicy def overrides) -> ["backendPolicy" .= object
       (maybe [] (\backend -> ["default" .= backendName backend]) def ++
        ["bindings" .= object [Key.fromText (Text.decodeUtf8 key) .= backendName backend | (key,backend) <- overrides]])])
       (factsBackendPolicy facts)

  where
    backendName AstBackend = String "ast"
    backendName BytecodeBackend = String "bytecode"
    closureFields (ClosureProvenance roots modules missing _) =
      p "roots" (arr str) roots ++ p "sourceModules" (arr str) modules ++
      p "missingDefinitions" (arr (\(MissingDefinition key ty reason) -> object
        ["id" .= str key,"type" .= str ty,"reason" .= str reason])) missing
    originalBinding value = case (bindingIdentity value,binding value,factsClosureProvenance facts) of
      (Global key,Object fields,Just (ClosureProvenance _ _ _ origins)) -> Object $
        foldr (\(BindingOrigin owner origin ownerModule) result -> if key /= owner then result else
          foldr (uncurry KM.insert) result (p "origin" str origin ++ p "originModule" str ownerModule)) fields origins
      (_,result,_) -> result

provenance :: ModuleProvenance -> Value
provenance (ImportsRecord proof) = importProof proof
provenance (ExportsRecord proof) = exports proof
provenance (RegistrationRecord proof) = registration proof
provenance (ForeignLinkRecord proof) = foreignLink proof
provenance (ScalarLinkRecord proof) = scalarLink proof
provenance (NativeLinkRecord proof) = nativeLink proof
provenance (NativeArchiveRecord proof) = nativeArchive proof

foreignLink :: ForeignLink -> Value
foreignLink (ForeignLink schema format unit moduleName sourceSha bitcodeSha bytes target symbols abi headers) = object $
  ["schema" .= schema,"format" .= str format,"unit" .= str unit,"module" .= str moduleName,
   "sourceSha256" .= str sourceSha,"bitcodeSha256" .= str bitcodeSha,"bitcodeHex" .= hexBytes bytes,
   "target" .= str target,"symbols" .= arr str symbols,"abi" .= arr (stringPair "symbol" "kind") abi]
  ++ p "headerHashes" (arr (stringPair "name" "sha256")) headers

linkPayload :: LinkPayload -> [Pair]
linkPayload (LinkPayload schema format profile unit target componentSha bitcodeSha bytes) =
  ["schema" .= schema,"format" .= str format,"profile" .= str profile,"unit" .= str unit,
   "target" .= str target,"componentSha256" .= str componentSha,"bitcodeSha256" .= str bitcodeSha,
   "bitcodeHex" .= hexBytes bytes]

scalarLink :: ScalarLink -> Value
scalarLink (ScalarLink payload abi) = object $ linkPayload payload ++ ["abi" .= arr entry abi]
  where entry (ScalarABI symbol name arguments result) = object
          ["symbol" .= str symbol,"entry" .= str name,"arguments" .= arr str arguments,"result" .= str result]

stringPair :: Key.Key -> Key.Key -> (BS.ByteString,BS.ByteString) -> Value
stringPair first second (a,b) = object [first .= str a,second .= str b]

hexBytes :: BS.ByteString -> Value
hexBytes = toJSON . concatMap (\byte -> let digits = showHex byte "" in replicate (2-length digits) '0' ++ digits) . BS.unpack

nativeLink :: NativeLink -> Value
nativeLink (NativeLink payload@(LinkPayload schema _ _ _ _ _ _ _) abi inputs companion dataSymbols finalizers components seeds) = object $ linkPayload payload ++ ["abi" .= arr entry abi]
  ++ ["finalizers" .= arr str finalizers | schema == 2]
  ++ p "buildInputs" nativeBuildInputs inputs
  ++ p "nativeLibrary" (\(digest,bytes) -> object ["sha256" .= str digest,"hex" .= hexBytes bytes]) companion
  ++ p "dataSymbols" (arr str) dataSymbols
  ++ maybe [] (\(publicSymbols,dependencies) -> ["exports" .= arr str publicSymbols,"dependencies" .= arr nativeComponent dependencies]) components
  ++ maybe [] (\values -> ["callSeeds" .= arr seed values]) seeds
  where entry (NativeABI symbol name convention safety arguments result) = object
          ["symbol" .= str symbol,"entry" .= str name,
           "convention" .= tagName ["ccall","capi","stdcall","prim","javascript"] convention,
           "safety" .= tagName ["unsafe","safe","interruptible"] safety,
           "arguments" .= arr str arguments,"result" .= str result]
        seed (NativeCallSeed name digest bytes provider) = object
          ["entry" .= str name,"bitcodeSha256" .= str digest,"bitcodeHex" .= hexBytes bytes,
           "providerUnit" .= maybe Null (\(unit,_,_) -> str unit) provider,
           "providerComponentSha256" .= maybe Null (\(_,component,_) -> str component) provider,
           "providerSymbol" .= maybe Null (\(_,_,symbol) -> str symbol) provider]

nativeComponent :: NativeComponent -> Value
nativeComponent (NativeComponent payload publicSymbols dependencies companion) = object $ linkPayload payload ++
  ["exports" .= arr str publicSymbols,"dependencies" .= arr nativeComponent dependencies] ++
  p "nativeLibrary" (\(digest,bytes) -> object ["sha256" .= str digest,"hex" .= hexBytes bytes]) companion

nativeBuildInputs :: NativeBuildInputs -> Value
nativeBuildInputs (NativeBuildInputs units providers dependencies libraries unresolved bridges) = object $
  ["translationUnits" .= arr group units,"providers" .= arr provider providers,
   "nativeLibraries" .= arr library libraries,"unresolved" .= arr str unresolved,"argumentBridges" .= arr bridge bridges]
  ++ case dependencies of
    ArchiveBuildDependencies records -> p "dependencies" (arr nativeDependency) records
    ComponentBuildDependencies records productRecord ->
      p "dependencies" (arr dependencyRef) records ++ p "nativeProduct" nativeDependency productRecord
  where
    dependencyRef (NativeDependencyRef path unit component bitcode) = object
      ["declaredPath" .= arr str path,"unit" .= str unit,"componentSha256" .= str component,"bitcodeSha256" .= str bitcode]
    group (SingleCompile input) = compileInput input
    group (GroupCompile inputs) = arr compileInput inputs
    provider (NativeProvider name symbols path digest target input) = object
      ["provider" .= str name,"symbols" .= arr str symbols,"bitcode" .= str path,
       "bitcodeSha256" .= str digest,"target" .= str target,"inputs" .= compileInput input]
    library (NativeLibrary name symbols compiler digest arguments dependencyArguments objcopy objcopySha objcopyArguments) = object $
      ["provider" .= str name,"symbols" .= arr str symbols,"compiler" .= str compiler,
       "compilerSha256" .= str digest,"arguments" .= arr str arguments]
      ++ p "dependencyArguments" (arr str) dependencyArguments ++ p "objcopy" str objcopy
      ++ p "objcopySha256" str objcopySha ++ p "objcopyArguments" (arr (arr str)) objcopyArguments
    bridge (ArgumentBridge profile source sourceSha inputSha definitions) = object
      ["profile" .= str profile,"source" .= str source,"sourceSha256" .= str sourceSha,
       "inputBitcodeSha256" .= str inputSha,"definitions" .= arr (arr str) definitions]

compileInput :: CompileInput -> Value
compileInput (CompileInput compiler clang arguments language nativeTarget target files) = object $
  ["compiler" .= str compiler,"clang" .= str clang,"arguments" .= arr str arguments,
   "nativeTarget" .= str nativeTarget,"target" .= str target,"files" .= arr (stringPair "path" "sha256") files]
  ++ p "language" str language

nativeDependency :: NativeDependency -> Value
nativeDependency (NativeDependency profile unit source registrationText digest archives products) = object
  ["profile" .= str profile,"unit" .= str unit,"sourceIdentity" .= sourceIdentity source,
   "registration" .= str registrationText,"registrationSha256" .= str digest,
   "archives" .= arr archive archives,"translationUnits" .= arr productRecord products]
  where
    archive (ArchiveProduct path hash members) = object
      ["path" .= str path,"sha256" .= str hash,"members" .= arr (stringPair "name" "sha256") members]
    productRecord (NativeProduct (NativePiece root path hash bitcode target input) bitcodeSha) = object
      ["receipt" .= object ["root" .= str root,"object" .= str path,"objectSha256" .= str hash,
         "bitcode" .= str bitcode,"target" .= str target,"inputs" .= compileInput input],"bitcodeSha256" .= str bitcodeSha]

sourceIdentity :: SourceIdentity -> Value
sourceIdentity (SourceIdentity unit depends kind style name version flags component sourceSha cabalSha source) = object $
  p "id" str unit ++ p "depends" (arr str) depends ++ p "type" str kind ++ p "style" str style
  ++ p "pkg-name" str name ++ p "pkg-version" str version
  ++ p "flags" (object . map (\(key,value) -> Key.fromText (Text.decodeUtf8 key) .= value)) flags
  ++ p "component-name" str component ++ p "pkg-src-sha256" str sourceSha ++ p "pkg-cabal-sha256" str cabalSha
  ++ p "pkg-src" nativeSource source

nativeSource :: NativeSource -> Value
nativeSource (NativeSource kind path repo) = object $ ["type" .= str kind] ++ p "path" str path
  ++ p "repo" (\(scheme,uri) -> object ["type" .= str scheme,"uri" .= str uri]) repo

nativeArchive :: NativeArchive -> Value
nativeArchive (NativeArchive schema profile execution unit moduleName unsupported reason unresolved artifact conflicts) = object $
  ["schema" .= schema,"profile" .= str profile,"execution" .= str execution,"unit" .= str unit,
   "module" .= str moduleName,"unsupportedImports" .= arr emittedCall unsupported,"unresolvedSymbols" .= arr str unresolved]
  ++ p "unclassifiedReason" str reason ++ p "artifact" nativeLink artifact
  ++ p "conflictingImports" (arr emittedCall) conflicts

qualifiedName :: QualifiedName -> Value
qualifiedName (QualifiedName unit moduleName occurrence namespace) = object
  ["unit" .= str unit,"module" .= str moduleName,"occurrence" .= str occurrence,"namespace" .= str namespace]

foreignType :: ForeignType -> Value
foreignType value = object $ case value of
  ForeignTyCon name arguments -> ["kind" .= String "tycon","name" .= qualifiedName name,"arguments" .= arr foreignType arguments]
  ForeignApplication function argument -> ["kind" .= String "application","function" .= foreignType function,"argument" .= foreignType argument]
  ForeignArrow multiplicity argument result -> ["kind" .= String "function","multiplicity" .= foreignType multiplicity,
    "argument" .= foreignType argument,"result" .= foreignType result]
  ForeignVariable index -> ["kind" .= String "bound-variable","index" .= index]
  ForeignForall kind body -> ["kind" .= String "forall","binderKind" .= foreignType kind,"body" .= foreignType body]

importProof :: ImportProof -> Value
importProof (ImportProof schema scope execution profile unit moduleName status) = object $
  ["schema" .= schema,"scope" .= str scope,"execution" .= str execution,"profile" .= str profile,
   "unit" .= str unit,"module" .= str moduleName] ++ case status of
    ImportsUnclassified reason -> ["status" .= String "unclassified","reason" .= str reason]
    ImportsRejected reason -> ["status" .= String "rejected","reason" .= str reason]
    ImportsVerified wordBits original associations calls addresses wrappers partition -> ["status" .= String "verified","wordBits" .= wordBits,
      "expectedForeign" .= foreignArtifacts original,"imports" .= arr association associations,"expectedCalls" .= arr foreignCall calls] ++
      ["addresses" .= arr address addresses | schema >= 2] ++ ["wrappers" .= arr wrapper wrappers | schema >= 3] ++
      ["importForeign" .= foreignArtifacts product' | Just product' <- [partition]]
  where
    wrapper (WrapperAssociation (ExportAssociation binderName helper convention declared normalized role arguments result effect) encoding) = object
      ["binder" .= qualifiedName binderName,"helper" .= str helper,
       "convention" .= tagName ["ccall","capi","stdcall","prim","javascript"] convention,
       "declaredType" .= foreignType declared,"normalizedType" .= foreignType normalized,"normalizationRole" .= str role,
       "arguments" .= arr foreignType arguments,"result" .= foreignType result,"effect" .= tagName ["pure","io"] effect,
       "typeString" .= str encoding]
    address (AddressAssociation binderName header symbol function convention declared normalized role callback) = object $
      ["binder" .= qualifiedName binderName,"symbol" .= str symbol,"isFunction" .= function,
       "convention" .= tagName ["ccall","capi","stdcall","prim","javascript"] convention,
       "declaredType" .= foreignType declared,"normalizedType" .= foreignType normalized,
       "normalizationRole" .= str role,"callback" .= maybe Null (\(arguments,result) ->
         object ["arguments" .= arr str arguments,"result" .= str result]) callback] ++ p "header" str header
    association (ImportAssociation binderName header symbol unitName function convention safety declared normalized role emitted) = object $
      ["binder" .= qualifiedName binderName,"symbol" .= str symbol,"isFunction" .= function,
       "convention" .= tagName ["ccall","capi","stdcall","prim","javascript"] convention,
       "safety" .= tagName ["unsafe","safe","interruptible"] safety,"declaredType" .= foreignType declared,
       "normalizedType" .= foreignType normalized,"normalizationRole" .= str role,"emitted" .= emittedCall emitted]
      ++ p "header" str header ++ p "unit" str unitName

emittedCall :: EmittedCall -> Value
emittedCall (EmittedCall symbol unit convention safety arguments result) = object $
  ["symbol" .= str symbol,"convention" .= tagName ["ccall","capi","stdcall","prim","javascript"] convention,
   "safety" .= tagName ["unsafe","safe","interruptible"] safety,"arguments" .= arr str arguments,"result" .= arr str result]
  ++ p "unit" str unit

exports :: Exports -> Value
exports (Exports schema producer scope execution unit moduleName associations) = object
  ["schema" .= schema,"producer" .= str producer,"scope" .= str scope,"execution" .= str execution,
   "unit" .= str unit,"module" .= str moduleName,"exports" .= arr association associations]
  where
    association (ExportAssociation binderName symbol convention declared normalized role arguments result effect) = object
      ["binder" .= qualifiedName binderName,"symbol" .= str symbol,
       "convention" .= tagName ["ccall","capi","stdcall","prim","javascript"] convention,
       "declaredType" .= foreignType declared,"normalizedType" .= foreignType normalized,"normalizationRole" .= str role,
       "arguments" .= arr foreignType arguments,"result" .= foreignType result,"effect" .= tagName ["pure","io"] effect]

registration :: Registration -> Value
registration (Registration schema scope execution profile status) = object $
  ["schema" .= schema,"scope" .= str scope,"execution" .= str execution,"profile" .= str profile] ++ case status of
    RegistrationUnclassified reason -> ["status" .= String "unclassified","reason" .= str reason]
    RegistrationRejected reason -> ["status" .= String "rejected","reason" .= str reason]
    RegistrationVerified roots wordBits original expected -> ["status" .= String "verified","roots" .= arr qualifiedName roots,
      "wordBits" .= wordBits,"expectedForeign" .= foreignArtifacts original,"expectedExports" .= exports expected]

targetLayout :: TargetLayout -> Value
targetLayout value = object ["format" .= String "thc-target-layout","schema" .= targetDocumentSchema value,
  "compiler" .= object ["id" .= str (targetCompilerId value),"abi" .= str (targetCompilerAbi value),
    "platform" .= str (targetCompilerPlatform value),"way" .= str (targetCompilerWay value)],
  "layout" .= object (["schema" .= targetLayoutSchema value,"profiled" .= targetProfiled value,
    "wordBytes" .= targetWordBytes value,"endianness" .= tagName ["little","big"] (targetEndianness value),
    "targetPlatform" .= str (targetPlatform value),"tablesNextToCode" .= targetTablesNextToCode value]
    ++ zipWith (\key n -> (Key.fromText (Text.decodeUtf8 key),toJSON n)) names (targetNumbers value))]
  where
    names = either (const targetNumberNames) id (targetNumberNamesFor (targetLayoutSchema value))

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
