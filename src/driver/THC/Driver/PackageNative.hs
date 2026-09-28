-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Driver.PackageNative
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Host filesystem/process services and the configured native toolchain
--
-- Package-owned C/C++/CAPI acquisition. Compile the real configured source and
-- retained GHC wrappers while Cabal's headers exist; carry LLVM, not guesses
-- about native object layouts, into the immutable Core bundle.
module THC.Driver.PackageNative
  ( captureNativeObject, captureNativeComponent, capturePackageNative, finishPackageNative
  , finishPackageNativeWithDependencies
  , nativeSignatures, nativeFinalizers, archiveNativeModule, archiveNativeModules, nativeWrapperSource, nativeCompilerArguments, nativeObjectOwned
  ) where

import Control.Monad (filterM, forM, forM_, unless, when)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (FromJSON, Value(..), eitherDecodeStrict', encode, object, toJSON, (.=), fromJSON, Result(..))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Char (isAlpha, isAlphaNum)
import Data.List (groupBy, isInfixOf, isPrefixOf, nub, sort)
import qualified Data.Text as T
import qualified Data.Text.Encoding as T
import Numeric (showHex)
import System.Directory
import System.Environment (getEnvironment, lookupEnv)
import System.Exit (ExitCode(..))
import System.FilePath
import System.Process (CreateProcess(..), proc, readCreateProcessWithExitCode)
import THC.Driver.ScalarBitcode (parseDependencies, sulongScalarTarget)
import THC.Driver.NativeLibrarySources (zlibChecksumSources)
import THC.Driver.NativeArgumentBridge (nativeArgumentBridge)
import THC.Driver.NativeDependencies (COnlyProduct, cOnlyProductProof, cOnlyProductPieces, nativeLinkInputs)

-- (original emitted symbol, convention, safety, semantic carriers, result)
type Signature = (String, String, String, [String], String)

nativeSignatures :: String -> [Value] -> Either String [Signature]
nativeSignatures unit modules = do
  imports <- concat <$> mapM moduleImports modules
  called <- mapM (nativeSignature unit) imports
  finalizers <- nativeFinalizers unit modules
  let signatures = called ++ [(symbol,"ccall","unsafe",["AddrRep"],"void") | symbol <- finalizers]
  require (all supportedSignature signatures) "package native call has unsupported safety/carriers"
  let ordered = sort (nub signatures)
  forM_ (groupBy (\a b -> first a == first b) ordered) $ \variants -> do
    require (length (nub (map (nativeCAbi nativeIntegerAbi) variants)) == 1)
      ("conflicting package native signatures: " ++ show variants)
    when (length (nub (map (nativeCAbi id) variants)) /= 1) $ do
      let declarations = [entry | entry <- imports, Just emitted <- [member entry "emitted"],
            member emitted "symbol" `elem` map (Just . toJSON . first) variants]
          headers = [header | entry <- declarations, Just (String header) <- [member entry "header"]]
      require (all (\(_,convention,_,_,_) -> convention == "ccall") variants &&
        length headers == length declarations && length (nub headers) == 1 &&
        all (validHeader . T.unpack) headers)
        "package native signedness variants require one retained configured C header"
    -- Addr# and ByteArray# have the same C pointer ABI but distinct Core
    -- carriers. Do not collapse their typed adapters or pointer handling.
    -- ByteArray# and MutableByteArray# erase to the same Core shape; without
    -- further call-site proof, choosing a read/write policy would be ambiguous.
    require (length variants == length (nub (map coreAbi variants)))
      "ambiguous package native byte-array mutability variants"
  pure ordered
  where
    first (symbol,_,_,_,_) = symbol
    coreAbi (_,convention,safety,arguments,result) =
      (convention,safety,map (\value -> if value == "MutableByteArray#" then "ByteArray#" else value) arguments,result)
    moduleImports value = do
      imports <- nativeImports unit value
      case member value "packageNativeArchive" of
        Nothing -> do
          require (maybe True ((/= Just "unclassified") . (`member` "status"))
            (member value "staticForeignImports")) "package native imports lack verified typed provenance"
          pure imports
        Just archive -> do
          excluded <- field archive "unsupportedImports" :: Either String [Value]
          pure [entry | entry <- imports, member entry "emitted" `notElem` map Just excluded]

nativeCAbi :: (String -> String) -> Signature -> (String,String,[String],String)
nativeCAbi integer (_,convention,safety,arguments,result) =
  (convention,if safety == "safe" then "unsafe" else safety,map (integer . pointer) arguments,result)
  where pointer value | value `elem` ["ByteArray#","MutableByteArray#"] = "AddrRep"
                      | otherwise = value

nativeIntegerAbi :: String -> String
nativeIntegerAbi value | value `elem` ["IntRep","Int8Rep","Int16Rep","Int32Rep","Int64Rep"] = "Word" ++ drop 3 value
                       | otherwise = value

-- Classify only known semantic gaps. Invalid records, mismatched retained
-- products and compiler/linker failures are never converted into archives.
archiveNativeModule :: String -> Value -> Either String Value
archiveNativeModule unit value = do
  archived <- archiveNativeModules unit [value]
  case archived of
    [single] -> Right single
    _ -> Left "package native archive changed module count"

-- Different modules can genuinely declare one C symbol at incompatible widths.
-- Keep their exact emitted witnesses; never choose one ABI or invent a cast.
-- Only that symbol is excluded, so unrelated imports still get real adapters.
archiveNativeModules :: String -> [Value] -> Either String [Value]
archiveNativeModules unit values = do
  imports <- mapM (nativeImports unit) values
  signatures <- mapM (mapM (nativeSignature unit)) imports
  let first (symbol,_,_,_,_) = symbol
      supported = sort . nub . filter supportedSignature $ concat signatures
      conflicting = [first variant |
        variants@(variant:_) <- groupBy (\a b -> first a == first b) supported,
        length (nub (map (nativeCAbi nativeIntegerAbi) variants)) > 1]
      witnesses = nub [emitted | (entry,signature) <- zip (concat imports) (concat signatures),
        first signature `elem` conflicting, supportedSignature signature,
        Just emitted <- [member entry "emitted"]]
  forM (zip3 values imports signatures) $ \(value,entries,typed) -> do
    let names = nub [first signature | signature <- typed,
          supportedSignature signature, first signature `elem` conflicting]
        conflicts = [emitted | emitted <- witnesses, member emitted "symbol" `elem` map (Just . toJSON) names]
        excluded = [emitted | (entry,signature) <- zip entries typed,
          not (supportedSignature signature) || first signature `elem` conflicting,
          Just emitted <- [member entry "emitted"]]
        unknown = case member value "staticForeignImports" of
          Just proof | member proof "status" == Just "unclassified" -> member proof "reason"
          _ -> Nothing
    pure $ if null excluded && unknown == Nothing then value else
      setMember "packageNativeArchive" (archiveRecord value excluded unknown [] Nothing conflicts) value

archiveRecord :: Value -> [Value] -> Maybe Value -> [String] -> Maybe Value -> [Value] -> Value
archiveRecord value excluded unknown unresolved artifact conflicts = object $
  ["schema" .= (1::Int), "profile" .= ("thc-package-native-archive-v1"::String),
   "execution" .= ("not-linked"::String), "unit" .= member value "unit", "module" .= member value "module",
   "unsupportedImports" .= excluded, "unclassifiedReason" .= unknown,
   "unresolvedSymbols" .= unresolved, "artifact" .= artifact] ++
  ["conflictingImports" .= conflicts | not (null conflicts)]

nativeImports :: String -> Value -> Either String [Value]
nativeImports unit value = do
  require (member value "unit" == Just (toJSON unit)) "package native module owner differs"
  case member value "staticForeignImports" of
    Nothing -> Right []
    Just proof -> do
      require (member proof "schema" `elem` map (Just . toJSON) ([1,2]::[Int]) && member proof "unit" == Just (toJSON unit) &&
        member proof "module" == member value "module" && member proof "scope" == Just "retained-static-import-products" &&
        member proof "execution" == Just "not-linked" && member proof "profile" == Just "ghc-9.14.1-thc-only-static-c-imports-v1")
        "package native imports lack typed provenance identity"
      require (maybe True (== proof) (member value "staticForeignImportStubs"))
        "package native retained stub provenance differs"
      if member proof "status" == Just "unclassified" then do
        requireKeys proof ["schema","scope","execution","profile","unit","module","status","reason"]
        require (member proof "reason" == Just "non-static-c-import-declaration")
          "package native imports have an unrecognized unclassified producer"
        pure []
      else do
        requireKeys proof (["schema","scope","execution","profile","unit","module","status","wordBits","expectedForeign","expectedCalls","imports"] ++
          ["addresses" | member proof "schema" == Just (toJSON (2::Int))])
        require (member proof "status" == Just "verified" && member proof "wordBits" == Just (toJSON (64::Int)))
          "package native imports lack verified typed provenance"
        expected <- field proof "expectedForeign"
        require (maybe (emptyForeign expected) (== expected) (member value "foreign"))
          "package native retained foreign product differs from typed import provenance"
        require (member proof "expectedCalls" == Just (toJSON (calls value)))
          "package native Core calls differ from typed import provenance"
        imports <- field proof "imports"
        forM_ imports $ \entry -> do
          binder <- field entry "binder"
          require (member binder "unit" == Just (toJSON unit) && member binder "module" == member value "module")
            "package native import binder owner differs"
        _ <- nativeAddresses unit value
        pure imports

-- Address declarations carry their own nominal type and stock-emitter evidence.
-- They never become synthetic expectedCalls. Only the concrete one-pointer,
-- IO-unit profile is eligible for a C finalizer adapter.
nativeAddresses :: String -> Value -> Either String [Value]
nativeAddresses unit value = case member value "staticForeignImports" of
  Just proof | member proof "schema" == Just (toJSON (2::Int)), member proof "status" == Just "verified" -> do
    addresses <- field proof "addresses"
    require (not (null addresses) && length addresses == length (nub addresses)) "empty or duplicate native address inventory"
    require (length addresses == length (nub (map (`member` "binder") addresses))) "duplicate native address binder"
    forM_ addresses $ \entry -> do
      requireKeys entry ["binder","header","symbol","isFunction","convention","declaredType","normalizedType","normalizationRole","callback"]
      binder <- field entry "binder"
      nativeIdentity binder
      require (member binder "unit" == Just (toJSON unit) && member binder "module" == member value "module" &&
        member binder "namespace" == Just "value") "native address binder owner differs"
      symbol <- field entry "symbol"
      function <- field entry "isFunction" :: Either String Bool
      require (identifier symbol && member entry "convention" `elem` [Just "ccall",Just "capi"] &&
        member entry "normalizationRole" == Just "representational" &&
        (case member entry "header" of Just Null -> True; Just (String h) -> validHeader (T.unpack h); _ -> False))
        "invalid native address declaration"
      field entry "declaredType" >>= nativeType 0
      field entry "normalizedType" >>= nativeType 0
      require (member entry "callback" == Just Null || function &&
        maybe False finalizerType (member entry "normalizedType") && member entry "callback" ==
          Just (object ["arguments" .= (["AddrRep"]::[String]),"result" .= ("void"::String)]))
        "unsupported native callback proof"
    pure addresses
  _ -> Right []

-- The normalized nominal type is checked independently of the callback tag.
-- It is deliberately a pointer-to-IO-unit profile, not arbitrary indirect FFI.
finalizerType :: Value -> Bool
finalizerType value
  | member value "kind" == Just "forall" = maybe False finalizerType (member value "body")
  | Just [function] <- named value "GHC.Internal.Ptr" "FunPtr",
    member function "kind" == Just "function",
    Just argument <- member function "argument", Just [_] <- named argument "GHC.Internal.Ptr" "Ptr",
    Just result <- member function "result", Just [unit] <- named result "GHC.Internal.Types" "IO",
    Just [] <- named unit "GHC.Internal.Tuple" "Unit" = True
  | otherwise = False
  where
    named item modName occurrence
      | member item "kind" == Just "tycon", member item "name" == Just (object
          ["unit" .= ("ghc-internal"::String),"module" .= (modName::String),
           "occurrence" .= (occurrence::String),"namespace" .= ("type"::String)]),
        Just (Array args) <- member item "arguments" = Just (foldr (:) [] args)
      | otherwise = Nothing

nativeFinalizers :: String -> [Value] -> Either String [String]
nativeFinalizers unit modules = do
  mapM_ (nativeImports unit) modules
  addresses <- concat <$> mapM (nativeAddresses unit) modules
  sort . nub <$> mapM (\entry -> field entry "symbol")
    [entry | entry <- addresses, member entry "callback" /= Just Null,
      member entry "symbol" `notElem` [Just "free",Just "libdwPoolRelease",Just "backtraceFree"]]

nativeSignature :: String -> Value -> Either String Signature
nativeSignature unit entry = do
  requireKeys entry ["binder","header","symbol","unit","isFunction","convention","safety","declaredType","normalizedType","normalizationRole","emitted"]
  binder <- field entry "binder"
  nativeIdentity binder
  require (member binder "namespace" == Just "value") "package native import binder namespace differs"
  mapM_ (\key -> field entry key >>= nativeType 0) ["declaredType","normalizedType"]
  emitted <- field entry "emitted"
  requireKeys emitted ["symbol","unit","convention","safety","arguments","result"]
  symbol <- field emitted "symbol"
  convention <- field emitted "convention"
  safety <- field emitted "safety"
  arguments <- field emitted "arguments"
  results <- field emitted "result"
  declaredSymbol <- field entry "symbol" :: Either String String
  require (not (null declaredSymbol) && member entry "normalizationRole" == Just "representational" &&
    member entry "unit" `elem` [Just Null,Just (toJSON unit)] &&
    member entry "convention" == Just (toJSON convention) && member entry "safety" == Just (toJSON safety) &&
    (member entry "isFunction" == Just (Bool True) || convention == "capi" && member entry "isFunction" == Just (Bool False)) &&
    (convention /= "ccall" || declaredSymbol == symbol) &&
    (case member entry "header" of Just Null -> True; Just (String header) -> validHeader (T.unpack header); _ -> False))
    "package native declaration differs from emitted ABI"
  require (identifier symbol && member emitted "unit" == Just (toJSON unit) &&
    convention `elem` ["ccall", "capi"] && safety `elem` ["unsafe","safe","interruptible"] &&
    not (null arguments) && last arguments == "void" && all inputCarrier (init arguments))
    "package native call has malformed static C/CAPI metadata"
  result <- case results of
    ["void"] -> Right "void"
    ["void", result] | scalarCarrier result -> Right result
    _ -> Left "package native call requires State with zero or one scalar result"
  pure (symbol,convention,safety,init arguments,result)

requireKeys :: Value -> [String] -> Either String ()
requireKeys (Object value) keys = require (sort (map Key.toString (KM.keys value)) == sort keys)
  "package native provenance record fields differ"
requireKeys _ _ = Left "package native provenance record is not an object"

nativeIdentity :: Value -> Either String ()
nativeIdentity value = do
  requireKeys value ["unit","module","occurrence","namespace"]
  forM_ ["unit","module","occurrence","namespace"] $ \key -> do
    name <- field value key :: Either String String
    require (not (null name) && '\0' `notElem` name) "invalid package native name"
  require (member value "namespace" `elem` map (Just . toJSON) (["type","value","data"]::[String])) "invalid package native namespace"

nativeType :: Int -> Value -> Either String ()
nativeType depth value = case member value "kind" of
  Just "tycon" -> do
    requireKeys value ["kind","name","arguments"]
    field value "name" >>= nativeIdentity
    arguments <- field value "arguments" :: Either String [Value]
    mapM_ (nativeType depth) arguments
  Just "application" -> children ["function","argument"]
  Just "function" -> children ["multiplicity","argument","result"]
  Just "forall" -> do
    requireKeys value ["kind","binderKind","body"]
    field value "binderKind" >>= nativeType depth
    field value "body" >>= nativeType (depth + 1)
  Just "bound-variable" -> do
    requireKeys value ["kind","index"]
    index <- field value "index"
    require (index >= (0::Int) && index < depth) "free package native import type variable"
  _ -> Left "invalid package native import type"
  where children keys = do
          requireKeys value ("kind":keys)
          mapM_ (\key -> field value key >>= nativeType depth) keys

supportedSignature :: Signature -> Bool
-- Temporary user-selected execution policy: preserve the declared safety in
-- every proof/adapter, but use the existing unsafe boundary for safe imports.
-- This does not admit interruptible calls or broaden pointer lifetime rules.
supportedSignature (_,_,safety,_,_) = safety `elem` ["unsafe","safe"]

setMember :: String -> Value -> Value -> Value
setMember name value (Object fields) = Object (KM.insert (Key.fromString name) value fields)
setMember _ _ value = value

emptyForeign :: Value -> Bool
emptyForeign value = member value "schema" == Just (toJSON (1::Int)) &&
  member value "execution" == Just "not-linked" && member value "files" == Just (toJSON ([]::[Value])) &&
  case member value "stubs" of
    Just Null -> True
    Just stubs -> all (\key -> member stubs key == Just "") ["header","source"] &&
      all (\key -> member stubs key == Just (toJSON ([]::[Value]))) ["initializers","finalizers"]
    _ -> False

scalarCarrier :: String -> Bool
scalarCarrier value = value `elem`
  ["IntRep","WordRep","Int8Rep","Word8Rep","Int16Rep","Word16Rep","Int32Rep","Word32Rep",
   "Int64Rep","Word64Rep","FloatRep","DoubleRep","AddrRep"]
inputCarrier :: String -> Bool
inputCarrier value = scalarCarrier value || value `elem` ["ByteArray#","MutableByteArray#"]

validHeader :: String -> Bool
validHeader header = not (null header) && all (`notElem` ['\0','\n','\r','"','\\']) header

nativeWrapperSource :: [(Signature, String, Maybe String)] -> Either String String
nativeWrapperSource entries = fmap concat $ forM entries $ \((symbol,convention,_,arguments,result), entry, header) -> do
  require (identifier symbol && identifier entry && all inputCarrier arguments &&
    (result == "void" || scalarCarrier result) && maybe True validHeader header)
    "invalid native wrapper ABI"
  let types = map cType arguments
      parameters = if null types then "void" else join ", " [ty ++ " a" ++ show index | (index,ty) <- zip [0::Int ..] types]
      prototype = if null types then "void" else join ", " types
      args = join ", " ["a" ++ show index | index <- [0 .. length arguments - 1]]
  -- Retained CAPI definitions already provide their exact C prototypes (for
  -- example HsWord8*, while Core quite correctly lowers this to AddrRep).
  -- Signed/unsigned imports of one symbol retain distinct Haskell adapters.
  -- Its actual configured header supplies the callee prototype (including
  -- narrow integer extension); the C compiler performs the bit-width conversion.
  -- A ccall names a function, not a header macro of the same name.
  pure (maybe "" (\name -> "#include \"" ++ name ++ "\"\n#undef " ++ symbol ++ "\n") header ++
    (if convention == "capi" || header /= Nothing then "" else
      "extern " ++ cType result ++ " " ++ symbol ++ "(" ++ prototype ++ ");\n") ++
    cType result ++ " " ++ entry ++ "(" ++ parameters ++ ") { " ++
    (if result == "void" then "" else "return ") ++ symbol ++ "(" ++ args ++ "); }\n")
  where
    cType "void" = "void"
    cType "ByteArray#" = "void *"
    cType "MutableByteArray#" = "void *"
    cType "AddrRep" = "void *"
    cType value = "Hs" ++ take (length value - 3) value

-- Preserve the configured C include/preprocessor options and package database
-- inputs from the actual Haskell compile, without reusing Haskell-only flags.
nativeCompilerArguments :: [String] -> Either String [String]
nativeCompilerArguments = go
  where
    go [] = Right []
    go (flag:value:rest)
      | flag `elem` ["-I","-optc","-package-db","-package-id","-package"] =
          ((\tail' -> flag:value:tail') <$> go rest)
      | flag `elem` ["-odir","-hidir","-hiedir","-stubdir","-outputdir","-this-unit-id",
                    "-this-package-name","-main-is","-o","-osuf","-hisuf","-pgmc"] = go rest
    go (flag:rest)
      | flag `elem` ["-hide-all-packages","-no-user-package-db","-clear-package-db","-global-package-db"] ||
        any (`isPrefixOf` flag) ["-I","-optc","-package-env="] = (flag:) <$> go rest
      | otherwise = go rest

-- Called only after the corresponding native compiler invocation succeeded.
-- Ignore configure probes outside the current package and dynamic duplicates.
captureNativeComponent :: FilePath -> [String] -> IO ()
captureNativeComponent pieces arguments = when ("--make" `elem` arguments) $ do
  root <- getCurrentDirectory >>= canonicalizePath
  roots <- mapM canonicalizePath (nub [path | (flag,path) <- zip arguments (drop 1 arguments),
    flag `elem` ["-odir","-outputdir"]])
  unless (null roots) $ do
    let directory = pieces </> "components"
    createDirectoryIfMissing True directory
    writeJson (directory </> sha (BL.toStrict (encode (root,roots))) <.> "json")
      (object ["root" .= root,"roots" .= roots])

captureNativeObject :: FilePath -> FilePath -> [String] -> IO ()
captureNativeObject pieces compiler arguments = when ("-c" `elem` arguments && after "-osuf" arguments /= Just "dyn_o") $
  case [value | value <- arguments, takeExtension value `elem` [".c", ".cc", ".cpp", ".cxx"],
                not ("-" `isPrefixOf` value)] of
    [source] -> do
      root <- getCurrentDirectory >>= canonicalizePath
      sourcePath <- canonicalizePath source
      when (within root sourcePath) $ do
        let output = maybe (maybe "" id (after "-odir" arguments) </> replaceExtension source "o") id (after "-o" arguments)
        native <- canonicalizePath output
        exists <- doesFileExist native
        when exists $ do
          nativeHash <- sha <$> BS.readFile native
          let directory = pieces </> sha (T.encodeUtf8 (T.pack native))
          createDirectoryIfMissing True directory
          (bitcode,target,inputs) <- compileC compiler root arguments directory Nothing
          writeJson (directory </> "piece.json") (object
            ["root" .= root,"object" .= native,"objectSha256" .= nativeHash,
             "bitcode" .= bitcode,"target" .= target,"inputs" .= inputs])
    _ -> pure ()

-- Called while the package source and generated headers are still alive.
-- The JSON written by the late Core pass does not contain retained annotations;
-- hydrate exactly those interfaces which contain this unit's foreign calls.
capturePackageNative :: FilePath -> FilePath -> FilePath -> FilePath -> [String] -> String -> FilePath -> IO ()
capturePackageNative repository helper libdir compiler arguments unit directory = do
  let core = directory </> "core"
      objects = directory </> "objects"
  paths <- sort . filter ((== ".json") . takeExtension) <$> files core
  sourceValues <- mapM readJson paths
  let needed = [(path,value) | (path,value) <- zip paths sourceValues,
        any (owned unit) (calls value) || hasFunctionAddress value]
  unless (null needed) $ do
    root <- getCurrentDirectory >>= canonicalizePath
    configured <- either fail pure (nativeCompilerArguments arguments)
    hydrated <- forM needed $ \(_,source) -> do
      name <- get source "module"
      let interface = objects </> map (\c -> if c == '.' then pathSeparator else c) name <.> "hi"
          databases = [database | (flag,database) <- zip arguments (drop 1 arguments), flag == "-package-db"]
          way = if "-dynamic" `elem` arguments then "dynamic" else "vanilla"
      output <- command root helper (["--libdir",libdir,"--unit",unit,"--module",name,
        "--interface",interface,"--way",way,"--source-notes","--home-interfaces",objects] ++
        concatMap (\database -> ["--package-db",database]) databases)
      response <- either fail pure (eitherDecodeStrict' (T.encodeUtf8 (T.pack output)))
      check (member response "status" == Just "loaded") "package native interface has no retained full Core"
      value <- get response "core"
      check (member value "unit" == Just (toJSON unit) && member value "module" == Just (toJSON (name::String)))
        "package native retained interface identity differs"
      pure value
    retained <- either fail pure (archiveNativeModules unit hydrated)
    forM_ (zip needed retained) $ \((path,_),value) -> writeJson path value
    signatures <- either fail pure (nativeSignatures unit retained)
    finalizers <- either fail pure (nativeFinalizers unit retained)
    when (null signatures) $ writeJson (directory </> "native.json")
      (object ["unit" .= unit,"archiveOnly" .= True])
    unless (null signatures) $ do
      perModule <- mapM (either fail pure . nativeSignatures unit . (:[])) retained
      sources <- mapM (\(value,signatures') -> if null signatures' then pure "" else stubSource value)
        (zip retained perModule)
      let nativeDirectory = directory </> "native"
      imports <- concat <$> mapM (either fail pure . nativeImports unit) retained
      let signedVariants symbol = length (nub [map pointerAbi arguments' |
            (name,_,_,arguments',_) <- signatures, name == symbol]) > 1
          pointerAbi value | value `elem` ["ByteArray#","MutableByteArray#"] = "AddrRep"
                           | otherwise = value
          adaptedHeaders = nub [header | entry <- imports, Just emitted <- [member entry "emitted"],
            Just (String symbol) <- [member emitted "symbol"], signedVariants (T.unpack symbol),
            Just (String header) <- [member entry "header"]]
          -- Including one header also declares its other symbols. Use those
          -- same declarations for their pointer adapters rather than emitting
          -- conflicting void* prototypes later in this translation unit.
          wrapperHeader symbol = case
            [T.unpack header | entry <- imports, Just emitted <- [member entry "emitted"],
              member emitted "symbol" == Just (toJSON symbol), Just (String header) <- [member entry "header"],
              header `elem` adaptedHeaders] of
              header:_ -> Just header
              [] -> Nothing
      -- Capture candidate source providers while the configured package headers
      -- still exist. Final linking selects only actually unresolved symbols,
      -- so a package-owned implementation is never replaced by the provider.
      providers <- if any zlibChecksumImport imports then do
        implementations <- zlibChecksumSources repository
        forM implementations $ \(symbol, source) -> do
          let output = nativeDirectory </> "providers/zlib" </> symbol
          createDirectoryIfMissing True output
          (bitcode,target,inputs) <- compileC compiler root configured output (Just source)
          digest <- sha <$> BS.readFile bitcode
          pure (object ["provider" .= ("zlib-checksums-1.2.11"::String), "symbols" .= [symbol],
            "bitcode" .= bitcode,"bitcodeSha256" .= digest,"target" .= target,"inputs" .= inputs])
        else pure []
      let inputIdentity = object ["unit" .= unit,"compiler" .= compiler,"arguments" .= arguments,
            "sources" .= sources,"providers" .= providers,
            "imports" .= map (\(a,b,c,d,e) -> toJSON (a,b,c,d,e)) signatures,"finalizers" .= finalizers]
          provisional = sha (BL.toStrict (encode inputIdentity))
          makeEntries component = [(signature,"thc_native_" ++ component ++ "_" ++ show index) | (index,signature) <- zip [0::Int ..] signatures]
          -- GHC compiles each module's CAPI stubs as its own translation unit.
          -- Direct ccall adapters must not enter that CAPI header namespace:
          -- GHC emits those calls independently, and a header may name struct
          -- pointers or narrower C parameters than the emitted caller ABI.
          -- Preserve private helpers, macros and header include boundaries.
          -- Repeated direct ccall imports need just one component adapter.
          compileUnits component = forM
            [(index,convention,if convention == "capi" then source else "",entries) |
              (index,(source,ownedSignatures)) <- zip [0::Int ..] (zip sources perModule),
              convention <- ["ccall","capi"],
              let earlier = concat (take index perModule),
              let entries = [entry | entry@(signature@(_,callConvention,_,_,_),_) <- makeEntries component,
                    callConvention == convention,
                    signature `elem` ownedSignatures && signature `notElem` earlier],
              not (null entries)] $ \(index,convention,source,entries) -> do
                let output = nativeDirectory </> show index </> convention
                createDirectoryIfMissing True output
                wrappers <- either fail pure (nativeWrapperSource
                  [(signature,entry,wrapperHeader symbol) | (signature@(symbol,_,_,_,_),entry) <- entries])
                -- Direct ccall needs only the FFI scalar typedefs. Rts.h also
                -- imports unrelated libc prototypes (FILE*, etc.), which can
                -- conflict with GHC's otherwise valid opaque Addr# callers.
                let preamble = ["#include <Rts.h>\n" | convention == "capi"] ++ ["#include <HsFFI.h>\n"]
                (bitcode,target,inputs) <- compileC compiler root configured output
                  (Just (concat preamble ++ source ++ wrappers))
                headers <- headerInputs (output </> "wrappers.c") inputs
                pure (bitcode,target,inputs,headers)
      createDirectoryIfMissing True nativeDirectory
      discovered <- compileUnits provisional
      let dependencies = [headers | (_,_,_,headers) <- discovered]
      let component = sha (BL.toStrict (encode (inputIdentity, dependencies)))
          entries = makeEntries component
      compiled <- compileUnits component
      let currentDependencies = [headers | (_,_,_,headers) <- compiled]
          inputs = [input | (_,_,input,_) <- compiled]
      check (currentDependencies == dependencies) "package native headers changed during acquisition"
      target <- case nub [value | (_,value,_,_) <- compiled] of
        [value] -> pure value
        _ -> fail "package native wrapper targets differ"
      linker <- tool "THC_LLVM_LINK" "llvm-link"
      let bitcode = nativeDirectory </> "wrappers.bc"
      _ <- command root linker ([path | (path,_,_,_) <- compiled] ++ ["-o",bitcode])
      roots <- mapM canonicalizePath (nub [path | (flag,path) <- zip arguments (drop 1 arguments), flag `elem` ["-odir","-outputdir"]])
      writeJson (directory </> "native.json") (object
        ["unit" .= unit,"root" .= root,"objectRoots" .= roots,"bitcode" .= bitcode,"target" .= target,
         "componentSha256" .= component,"inputs" .= inputs,"sourceIdentity" .= inputIdentity,"providers" .= providers,
         "finalizers" .= [entry | ((symbol,"ccall","unsafe",["AddrRep"],"void"),entry) <- entries,
           symbol `elem` finalizers],
         "abi" .= [object ["symbol" .= symbol,"entry" .= entry,"convention" .= convention,"safety" .= safety,
           "arguments" .= arguments',"result" .= result] |
           ((symbol,convention,safety,arguments',result),entry) <- entries]])

hasFunctionAddress :: Value -> Bool
hasFunctionAddress (Array values) = case foldr (:) [] values of
  String "lit" : String "function-addr" : _ -> True
  items -> any hasFunctionAddress items
hasFunctionAddress (Object fields) = any hasFunctionAddress (KM.elems fields)
hasFunctionAddress _ = False

zlibChecksumImport :: Value -> Bool
zlibChecksumImport value = member value "header" == Just "zlib.h" &&
  maybe False (\emitted -> member emitted "symbol" `elem` [Just "adler32", Just "crc32"])
    (member value "emitted")

headerInputs :: FilePath -> Value -> IO [Value]
headerInputs generated value = do
  source <- canonicalizePath generated
  dependencies <- get value "files"
  pure [entry | entry <- dependencies, member entry "path" /= Just (toJSON source)]

stubSource :: Value -> IO String
stubSource value = case member value "foreign" of
  Nothing -> pure ""
  Just archive -> do
    check (member archive "execution" == Just "not-linked" && member archive "files" == Just (toJSON ([]::[Value])))
      "package native foreign files or existing execution obligations are unsupported"
    case member archive "stubs" of
      Nothing -> pure ""
      Just Null -> pure ""
      Just stubs -> do
        check (member stubs "header" == Just "" && member stubs "initializers" == Just (toJSON ([]::[Value])) &&
          member stubs "finalizers" == Just (toJSON ([]::[Value])))
          "package native callbacks, headers or initialization are unsupported"
        get stubs "source"

finishPackageNative :: FilePath -> FilePath -> String -> Maybe [FilePath] -> [(String,BS.ByteString)] -> IO [(String,BS.ByteString)]
finishPackageNative = finishPackageNativeWithDependencies []

-- | Additional products must belong to exact resolved C-only dependencies;
-- ordinary component/root selection remains unchanged for the requesting unit.
finishPackageNativeWithDependencies :: [COnlyProduct] -> FilePath -> FilePath -> String -> Maybe [FilePath] ->
  [(String,BS.ByteString)] -> IO [(String,BS.ByteString)]
finishPackageNativeWithDependencies cOnlyProducts pieces directory unit currentObjects modules = do
  let receipt = directory </> "native.json"
  exists <- doesFileExist receipt
  if not exists then pure modules else do
    record <- readJson receipt
    check (member record "unit" == Just (toJSON unit)) "package native receipt owner differs"
    if member record "archiveOnly" == Just (Bool True) then do
      check (case record of Object fields -> KM.size fields == 2; _ -> False) "invalid archive-only native receipt"
      pure modules
    else finish record
  where
   finish record = do
    roots <- get record "objectRoots" :: IO [FilePath]
    root <- get record "root"
    target <- get record "target"
    wrapper <- get record "bitcode"
    components <- mapM readJson =<< files (pieces </> "components")
    allRoots <- concat <$> mapM (\value -> get value "roots")
      [value | value <- components, member value "root" == Just (toJSON (root::String))]
    candidates <- filter ((== "piece.json") . takeFileName) <$> files pieces
    ownedNative <- filterM (\value -> do
      objectPath <- get value "object"
      pure (member value "root" == Just (toJSON (root::String)) &&
        maybe (nativeObjectOwned roots allRoots objectPath) (objectPath `elem`) currentObjects)) =<< mapM readJson candidates
    -- Local receipts survive runs; exact current Cabal membership excludes
    -- deleted/renamed C sources and sibling components. Private store receipts
    -- instead belong to this fresh acquisition, after unpacked objects vanish.
    case currentObjects of
      Nothing -> pure ()
      Just _ -> forM_ ownedNative $ \value -> do
        path <- get value "object"
        digest <- sha <$> BS.readFile path
        check (member value "objectSha256" == Just (toJSON digest)) "package native object receipt is stale"
    compilerArguments <- if null cOnlyProducts then pure [] else
      get record "sourceIdentity" >>= (`get` "arguments") :: IO [String]
    let declaredDependencies = [dependency | (flag,dependency) <- zip compilerArguments (drop 1 compilerArguments), flag == "-package-id"]
    forM_ cOnlyProducts $ \dependency -> do
      let proof = cOnlyProductProof dependency
      owner <- get proof "unit"
      check (owner /= unit && owner `elem` declaredDependencies)
        "native C-only product is not a declared direct dependency"
      products <- get proof "translationUnits" :: IO [Value]
      forM_ products $ \captured -> do
        piece <- get captured "receipt"
        path <- get piece "bitcode"
        observed <- sha <$> BS.readFile path
        check (member captured "bitcodeSha256" == Just (toJSON observed))
          "native C-only dependency bitcode changed after selection"
    let native = ownedNative ++ concatMap cOnlyProductPieces cOnlyProducts
    forM_ native $ \value -> check (member value "target" == Just (toJSON (target::String))) "package C object target differs"
    sourceInputs <- mapM (\value -> get value "inputs" :: IO Value) (record:native)
    bitcodes <- mapM (\value -> get value "bitcode") native
    abi <- get record "abi" :: IO [Value]
    finalizers <- maybe (pure []) (either fail pure . parseValue) (member record "finalizers") :: IO [String]
    entries <- mapM (\value -> get value "entry") abi
    link <- tool "THC_LLVM_LINK" "llvm-link"
    opt <- tool "THC_LLVM_OPT" "opt"
    nm <- tool "THC_LLVM_NM" "llvm-nm"
    let linked = directory </> "native/linked.bc"
        linkedIR = directory </> "native/linked.ll"
        final = directory </> "native/package.bc"
    _ <- command directory link (wrapper : bitcodes ++ ["-o",linked])
    _ <- command directory opt ["-S","-passes=verify",linked,"-o",linkedIR]
    linkedSource <- readFile linkedIR
    -- A typed Haskell address is not a C definition proof. Require the actual
    -- linked definition before allowing its namespaced one-pointer adapter.
    forM_ [value | value <- abi, member value "entry" `elem` map (Just . toJSON) finalizers] $ \value -> do
      symbol <- get value "symbol"
      let definitions = [line | line <- lines linkedSource, "define " `isPrefixOf` line,
            ("@" ++ symbol ++ "(") `isInfixOf` line]
          valid line = let (before,rest) = break (== '@') line
                           parameters = takeWhile (/= ')') (drop (length symbol + 2) rest)
                           ordinary = ["dso_local","noundef"]
                       in filter (`notElem` ordinary) (words before) == ["define","void"] &&
                         case words parameters of
                           "ptr":attributes -> not (',' `elem` parameters) && not (null attributes) &&
                             "%" `isPrefixOf` last attributes && all (`elem` ["noundef","nocapture","readonly","writeonly"])
                               (init attributes)
                           _ -> False
      check (length definitions == 1 && all valid definitions) ("package finalizer lacks an exact void(pointer) definition: " ++ symbol)
    -- Preserve actual constructor/destructor metadata. Sulong initializes each
    -- loaded component once and runs its destructors on normal context close.
    -- LLVM verification remains mandatory before and after trimming.
    bridges <- fmap concat $ forM abi $ \value -> do
      symbol <- get value "symbol"
      entry <- get value "entry"
      pure [bridge | member value "convention" == Just "ccall",
        Just bridge <- [nativeArgumentBridge target symbol entry linkedSource]]
    (prepared,bridgeInputs) <- if null bridges then pure (linked,[]) else do
      let source = unlines (nub [declaration | (declaration,_,_) <- bridges]) ++
            concat [body | (_,body,_) <- bridges]
          bridgeSource = directory </> "native/argument-bridges.ll"
          bridgeBitcode = directory </> "native/argument-bridges.bc"
          bridged = directory </> "native/bridged.bc"
      writeFile bridgeSource source
      _ <- command directory opt ["-passes=verify","--mtriple=" ++ target,bridgeSource,"-o",bridgeBitcode]
      _ <- command directory link [linked,"--override=" ++ bridgeBitcode,"-o",bridged]
      inputHash <- sha <$> BS.readFile linked
      let profile = if any (\(_,body,_) -> "zext i32 %r to i64" `isInfixOf` body) bridges
            then "x86_64-c-integer-slot-bridges-v2" else "x86_64-c-integer-argument-truncation-v1"
      pure (bridged,[object ["profile" .= (profile::String),
        "source" .= source,"sourceSha256" .= sha (T.encodeUtf8 (T.pack source)),
        "inputBitcodeSha256" .= inputHash,"definitions" .= [witnesses | (_,_,witnesses) <- bridges]]])
    let trim input = command directory opt ["-passes=internalize,globaldce",
          "-internalize-public-api-list=" ++ join "," entries,input,"-o",final]
        unresolved = do
          output <- command directory nm ["--undefined-only","--format=posix",final]
          pure [name | line <- lines output, name:_ <- [words line]]
    _ <- trim prepared
    initialExternals <- unresolved
    candidates' <- maybe (pure []) (either fail pure . parseValue) (member record "providers")
    providers <- filterM (\value -> any (`elem` initialExternals) <$> (get value "symbols" :: IO [String])) candidates'
    unless (null providers) $ do
      providerBitcodes <- forM providers $ \value -> do
        check (member value "target" == Just (toJSON (target::String))) "package native source provider target differs"
        path <- get value "bitcode"
        digest <- sha <$> BS.readFile path
        check (member value "bitcodeSha256" == Just (toJSON digest)) "package native source provider bitcode changed"
        pure path
      let resolved = directory </> "native/resolved.bc"
      _ <- command directory link (prepared : providerBitcodes ++ ["-o",resolved])
      _ <- trim resolved
      pure ()
    externals <- unresolved
    -- The configured C compiler and linker own native symbol resolution.
    -- Sulong consumes the embedded LLVM and native dependency list; there is
    -- no tested-symbol inventory or inferred library/ABI here.
    identity <- get record "sourceIdentity"
    compiler <- get identity "compiler"
    originalArguments <- get identity "arguments"
    libdir <- command directory compiler ["--print-libdir"] >>= \output -> case lines output of
      [path] -> pure path
      _ -> fail "native compiler did not report one library directory"
    linkArguments <- nativeLinkInputs compiler libdir root (Just unit) originalArguments
    clang <- tool "THC_CLANG" "clang"
    (artifact,format,libraries) <- if all ("llvm." `isPrefixOf`) externals
      then pure (final,"llvm-bitcode",[]) else do
        let darwin = "-darwin" `isInfixOf` target || "-apple-macosx" `isInfixOf` target
            artifact = directory </> if darwin then "native/final.dylib" else "native/final.so"
            format = if darwin then "llvm-embedded-mach-o" else "llvm-embedded-elf"
            -- Current Apple ld ignores -fembed-bitcode's legacy bundle flag.
            -- Sulong accepts raw bitcode in the Mach-O __LLVM,__bundle section.
            embedding = if darwin
              then concatMap (\argument -> ["-Xlinker",argument]) ["-sectcreate","__LLVM","__bundle",final]
              else ["-fembed-bitcode"]
            arguments = ["--target=" ++ target,"-shared","-fPIC",final] ++ embedding ++
              linkArguments ++ [if darwin then "-Wl,-undefined,error" else "-Wl,--no-undefined","-o",artifact]
        _ <- command directory clang arguments
        compilerHash <- sha <$> BS.readFile clang
        pure (artifact,format,[object ["provider" .= ("package-declared-native-libraries-v1"::String),
          "symbols" .= externals,"compiler" .= clang,"compilerSha256" .= compilerHash,"arguments" .= arguments]])
    bytes <- BS.readFile artifact
    component <- get record "componentSha256" :: IO String
    providerInputs <- mapM (\value -> get value "inputs") providers
    let inputs = sourceInputs ++ providerInputs
    let proof = object $ ["schema" .= (if null finalizers then 1 else 2::Int),"format" .= (format::String),
          "profile" .= ("thc-package-c-ffi-v1"::String),"unit" .= unit,"target" .= target,
          "componentSha256" .= component,"bitcodeSha256" .= sha bytes,"bitcodeHex" .= hex bytes,"abi" .= abi,
          "buildInputs" .= object ["translationUnits" .= inputs,"providers" .= providers,
            "dependencies" .= map cOnlyProductProof cOnlyProducts,
            "nativeLibraries" .= libraries,"unresolved" .= externals,"argumentBridges" .= bridgeInputs]] ++
          ["finalizers" .= finalizers | not (null finalizers)]
    writeJson (directory </> "native/inputs.json") (object ["sources" .= inputs,"unresolved" .= externals])
    forM modules $ \(name,bytes') -> do
      value <- either fail pure (eitherDecodeStrict' bytes')
      case value of
        Object fields -> do
          let prior = member value "packageNativeArchive"
              unclassified = prior >>= (`member` "unclassifiedReason")
              ownsCalls = any (owned unit) (calls value) || hasFunctionAddress value
          let next = if not ownsCalls || maybe False (/= Null) unclassified then value
                else Object (KM.insert "packageNativeLink" proof fields)
          pure (name, BL.toStrict (encode next))
        _ -> fail "package native Core module is not an object"

compileC :: FilePath -> FilePath -> [String] -> FilePath -> Maybe String -> IO (FilePath,String,Value)
compileC compiler root original directory generated = do
  clang <- tool "THC_CLANG" "clang"
  opt <- tool "THC_LLVM_OPT" "opt"
  let source = directory </> "wrappers.c"
      bitcode = directory </> "original.bc"
      dependency = directory </> "inputs.d"
      assembly = directory </> "original.ll"
  arguments <- case generated of
    Nothing -> pure original
    Just contents -> do
      writeFile source contents
      pure (original ++ ["-c",source])
  -- GHC has separate C and C++ compiler phases and option namespaces. Keep
  -- Cabal's complete successful invocation, including -optcxx configuration;
  -- replace only the selected compiler and output for its LLVM replay.
  let cxx = any (\value -> takeExtension value `elem` [".cc", ".cpp", ".cxx"] &&
                           not ("-" `isPrefixOf` value)) arguments
      compilerFlag = if cxx then "-pgmcxx" else "-pgmc"
      option = if cxx then "-optcxx" else "-optc"
  _ <- command root compiler (arguments ++ [compilerFlag,clang,"-fPIC","-o",bitcode] ++
    map (option ++) ["-emit-llvm","-O1","-MD","-MF",dependency,
                    "-MT","thc_scalar_input","-Werror=date-time"])
  dependencies <- either fail pure . parseDependencies =<< readFile dependency
  observed <- forM (sort (nub dependencies)) $ \path -> do
    absolute <- canonicalizePath (root </> path)
    digest <- sha <$> BS.readFile absolute
    pure (object ["path" .= absolute,"sha256" .= digest])
  _ <- command root opt ["-S","-passes=verify",bitcode,"-o",assembly]
  ir <- readFile assembly
  nativeTarget <- case [target | line <- lines ir, Just target <- [quoted "target triple = " line]] of
    [target] -> pure target
    _ -> fail "package native LLVM target missing"
  let target = sulongScalarTarget nativeTarget
      adjusted = directory </> "target.bc"
  _ <- command root opt ["-passes=verify","--mtriple=" ++ target,bitcode,"-o",adjusted]
  pure (adjusted,target,object ["compiler" .= compiler,"clang" .= clang,"arguments" .= arguments,
    "language" .= (if cxx then "c++" else "c" :: String),
    "nativeTarget" .= nativeTarget,"target" .= target,"files" .= observed])

calls :: Value -> [Value]
calls (Object fields) = maybe [] (:[]) (KM.lookup "foreignCall" fields) ++
  concatMap calls [value | (key,value) <- KM.toList fields,
    key `notElem` ["staticForeignImports","staticForeignImportStubs","packageNativeLink","packageNativeArchive"]]
calls (Array values) = concatMap calls values
calls _ = []
owned :: String -> Value -> Bool
owned unit value = (member value "target" >>= (`member` "unit")) == Just (toJSON unit)
identifier :: String -> Bool
identifier [] = False
identifier (x:xs) = (isAlpha x || x == '_') && all (\c -> isAlphaNum c || c == '_') xs
within :: FilePath -> FilePath -> Bool
within root path = isAbsolute path && not (isAbsolute relative) && ".." `notElem` splitDirectories relative
  where relative = makeRelative root path
nativeObjectOwned :: [FilePath] -> [FilePath] -> FilePath -> Bool
nativeObjectOwned roots allRoots path = any (`within` path) roots &&
  not (any (\other -> other `notElem` roots && within other path &&
    any (\own -> own /= other && within own other) roots) allRoots)
files :: FilePath -> IO [FilePath]
files root = do
  exists <- doesDirectoryExist root
  if not exists then pure [] else do
    entries <- listDirectory root
    concat <$> forM entries (\name -> do
      let path = root </> name
      directory <- doesDirectoryExist path
      if directory then files path else pure [path])
member :: Value -> String -> Maybe Value
member (Object fields) key = KM.lookup (Key.fromString key) fields
member _ _ = Nothing
field :: FromJSON a => Value -> String -> Either String a
field value key = case member value key of
  Just item -> parseValue item
  Nothing -> Left ("package native field missing: " ++ key)
parseValue :: FromJSON a => Value -> Either String a
parseValue value = case fromJSON value of Success result -> Right result; Error message -> Left message
get :: FromJSON a => Value -> String -> IO a
get value key = either fail pure (field value key)
require :: Bool -> String -> Either String ()
require condition message = if condition then Right () else Left message
check :: Bool -> String -> IO ()
check condition message = unless condition (fail message)
readJson :: FilePath -> IO Value
readJson path = either fail pure . eitherDecodeStrict' =<< BS.readFile path
writeJson :: FilePath -> Value -> IO ()
writeJson path = BL.writeFile path . encode
command :: FilePath -> FilePath -> [String] -> IO String
command directory program arguments = do
  inherited <- getEnvironment
  let environment = filter ((/= "GHC_ENVIRONMENT") . fst) inherited
  (status,output,diagnostic) <- readCreateProcessWithExitCode
    (proc program arguments) {cwd=Just directory,env=Just environment} ""
  check (status == ExitSuccess) ("package native command failed: " ++ program ++ " " ++ show arguments ++ "\n" ++ take 8192 (output ++ diagnostic))
  pure output
tool :: String -> String -> IO FilePath
tool variable fallback = do
  selected <- maybe fallback id <$> lookupEnv variable
  found <- if isAbsolute selected then pure selected else findExecutable selected >>= maybe (fail ("missing native tool " ++ selected)) pure
  canonicalizePath found
after :: Eq a => a -> [a] -> Maybe a
after key arguments = case dropWhile (/=key) arguments of _:value:_ -> Just value; _ -> Nothing
quoted :: String -> String -> Maybe String
quoted prefix line | prefix `isPrefixOf` line = case reads (drop (length prefix) line) of [(value,"")] -> Just value; _ -> Nothing
                   | otherwise = Nothing
join :: String -> [String] -> String
join _ [] = ""
join separator (x:xs) = x ++ concatMap (separator ++) xs
sha :: BS.ByteString -> String
sha = hex . SHA.hash
hex :: BS.ByteString -> String
hex = concatMap (\byte -> let value = showHex byte "" in if length value == 1 then '0':value else value) . BS.unpack
