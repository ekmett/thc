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
  ( captureNativeObject, captureConfiguredNativeObject, captureNativeComponent, capturePackageNative, finishPackageNative
  , finishPackageNativeWithDependencies
  , linkInstalledNative, linkInstalledNativeWithProduct, installedNativeSignatures, nativeCapiSource
  , nativeIrSymbol, nativeSignatures, nativeFinalizers, archiveNativeModule, archiveNativeModules, nativeWrapperSource, nativeAddressSource, nativeCompilerArguments, nativeObjectOwned, nativeDeferredLinkArguments, nativeRootArguments, tool
  , nativeCallSeedWitness, nativeFunctionExternals, nativeFinalizerDefinition
  ) where

import Control.Monad (filterM, forM, forM_, unless, when)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (FromJSON, Value(..), eitherDecodeStrict', encode, object, toJSON, (.=), fromJSON, Result(..))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Char (digitToInt, isAlpha, isAlphaNum, isHexDigit, isSpace)
import Data.List (groupBy, isInfixOf, isPrefixOf, nub, sort)
import qualified Data.Text as T
import qualified Data.Text.Encoding as T
import Numeric (showHex)
import System.Directory
import System.Environment (getExecutablePath, lookupEnv)
import System.Exit (ExitCode(..))
import System.FilePath
import System.Process (CreateProcess(..), proc, readCreateProcessWithExitCode)
import THC.Driver.ScalarBitcode (readDependencies, sulongScalarTarget)
import THC.Driver.NativeLibrarySources (zlibChecksumSources)
import THC.Driver.NativeArgumentBridge (nativeArgumentBridge, nativeCallWitness, nativeProviderForwarding, nativeModuleLayout)
import THC.Driver.NativeDependencies (NativeProduct, nativeProductProof, nativeProductPieces, nativeLinkInputs, nativeSymbolArchives, nativeSymbolArchivesWithProduct, nativeWindowsRtsInputs)
import THC.Driver.Installed (boundedInterfaceProcess)
import THC.Driver.NativeCache (nativeObjcopySelection, nativeCompilerEnvironment, nativeCompilerFlags)
import THC.Driver.RuntimeShim (coreNativeOverride, coreNativeImport)
import THC.Compact.Module (readModuleValue, finalizeModuleMetadata)

-- (original emitted symbol, convention, safety, semantic carriers, result)
type Signature = (String, String, String, [String], String)

-- | Validate an actual captured leaf adapter against its final canonical
-- provider. Other roots, native obligations or ABI shapes retain strict linkage.
nativeCallSeedWitness :: String -> Signature -> String -> String -> String -> String -> [String] -> [String] -> Bool
nativeCallSeedWitness target (symbol,convention,safety,arguments,returned) entry providerSymbol providerSource seedSource definitions externals =
  convention == "ccall" && safety `elem` ["safe","unsafe"] &&
  all inputCarrier arguments && (returned == "void" || scalarCarrier returned) &&
  definitions == [entry] && externals == [providerSymbol] &&
  nativeCallWitness target symbol providerSymbol providerSource providerSource /= Nothing &&
  case nativeCallWitness target providerSymbol entry providerSource seedSource of
    Just (actualArguments,actualResult,_) ->
      map llvmCarrier arguments == actualArguments && llvmCarrier returned == actualResult
    Nothing -> False
  where
    llvmCarrier "void" = "void"
    llvmCarrier value | value `elem` ["AddrRep","ByteArray#","MutableByteArray#"] = "ptr"
    llvmCarrier "FloatRep" = "float"
    llvmCarrier "DoubleRep" = "double"
    llvmCarrier value | value `elem` ["Int8Rep","Word8Rep"] = "i8"
    llvmCarrier value | value `elem` ["Int16Rep","Word16Rep"] = "i16"
    llvmCarrier value | value `elem` ["Int32Rep","Word32Rep"] = "i32"
    llvmCarrier _ = "i64"

-- Installed interfaces often predate retained source-import annotations. Their
-- FCallIds still carry the exact callable ABI. Unlifted heap pointers do not,
-- by themselves, distinguish ByteArray# from MutableByteArray#.
installedNativeSignatures :: String -> Value -> Either String [Signature]
installedNativeSignatures unit value
  | member value "staticForeignImports" /= Nothing = do
      imports <- nativeImports unit value
      signatures <- mapM (nativeSignature unit) imports
      _ <- mapM coreNativeOverride (filter (owned unit) (calls value))
      core <- mapM coreNativeImport imports
      pure (sort (nub [signature | (signature,False) <- zip signatures core, supportedSignature signature]))
  | otherwise = do
      let descriptors = filter (owned unit) (calls value)
      core <- mapM coreNativeOverride descriptors
      pure . sort . nub $
        [signature | (call,False) <- zip descriptors core, javascriptSymbol call == Nothing,
          Just signature <- [direct call], supportedSignature signature]
  where
    direct call = do
      target <- member call "target"
      symbol <- either (const Nothing) Just (field target "symbol")
      convention <- either (const Nothing) Just (field call "convention")
      safety <- either (const Nothing) Just (field call "safety")
      arguments <- either (const Nothing) Just (field call "argumentReps")
      result <- member call "resultRep"
      components <- either (const Nothing) Just (field result "components")
      carriers <- case member call "argumentTypes" of
        Nothing -> mapM scalar arguments
        Just raw -> do
          types <- either (const Nothing) Just (parseValue raw)
          if length types == length arguments then mapM typed (zip types arguments) else Nothing
      returns <- mapM scalar components
      returned <- case returns of
        ["void"] -> Just "void"
        ["void",r] | scalarCarrier r -> Just r
        _ -> Nothing
      if member call "schema" == Just (toJSON (if member call "argumentTypes" == Nothing then 1 else 2::Int)) &&
          member target "kind" == Just "static" && member target "isFunction" == Just (Bool True) &&
          identifier symbol && convention `elem` ["ccall","capi"] &&
          member call "arity" == Just (toJSON (length arguments)) &&
          member call "suppliedArity" == Just (toJSON (length arguments)) &&
          not (null carriers) && last carriers == "void" && all inputCarrier (init carriers) &&
          member result "aggregate" == Just "unboxed-tuple"
        then Just (symbol,convention,safety,init carriers,returned) else Nothing
    scalar rep = case member rep "primReps" >>= either (const Nothing) Just . parseValue of
      Just [] | member rep "kind" == Just "void" -> Just "void"
      Just [r] | scalarCarrier r -> Just r
      _ -> Nothing
    typed (Null,rep) = scalar rep
    typed (String kind,rep) | kind `elem` ["ByteArray#","MutableByteArray#"],
      member rep "kind" == Just "object",
      member rep "primReps" == Just (toJSON (["BoxedRep (Just Unlifted)"]::[String])) = Just (T.unpack kind)
    typed _ = Nothing

-- One component per installed unit, never one differently linked component per
-- module. Source products are reused only when their ordinary C obligations can
-- be linked; foreign exports/registration remain owned by the managed runtime.
linkInstalledNative :: FilePath -> FilePath -> FilePath -> [String] -> FilePath -> String ->
  [(String,FilePath)] -> IO [(String,FilePath)]
linkInstalledNative compiler packageTool libdir arguments directory unit modules =
  linkInstalledNativeWithProduct compiler packageTool libdir arguments directory unit Nothing modules

linkInstalledNativeWithProduct :: FilePath -> FilePath -> FilePath -> [String] -> FilePath -> String ->
  Maybe NativeProduct -> [(String,FilePath)] -> IO [(String,FilePath)]
linkInstalledNativeWithProduct compiler packageTool libdir arguments directory unit ownedProduct modules = do
  decoded <- mapM (\(_,path) -> BS.readFile path >>= either fail pure . readModuleValue) modules
  -- Do not splice a second component into an already acquired unit.
  let acquired = any (\value -> member value "packageNativeLink" /= Nothing) decoded
      eligible value = not acquired && member value "unit" == Just (toJSON unit) &&
        all (\key -> member value key == Nothing) ["packageNativeLink","staticForeignExports","staticForeignExportRegistration"] &&
        case member value "foreign" >>= (`member` "stubs") of
          Nothing -> True
          Just Null -> True
          Just stubs -> member stubs "header" == Just "" &&
            all (\key -> member stubs key == Just (toJSON ([]::[Value]))) ["initializers","finalizers"]
      selected = [(name,value) | ((name,_),value) <- zip modules decoded, eligible value]
  perModule <- mapM (either fail pure . installedNativeSignatures unit . snd) selected
  let signatures = sort (nub (concat perModule))
      requestedAddresses = nub (concatMap (addressLabels . snd) selected)
      requestedSymbols = nub (requestedAddresses ++ [(symbol,True) | (symbol,_,_,_,_) <- signatures])
  archives <- nativeSymbolArchivesWithProduct packageTool libdir directory unit arguments ownedProduct requestedSymbols
  declaredAddresses <- mapM (either fail pure . nativeAddressDeclarations unit . snd) selected
  -- An ordinary callable root selects its native provider, not an address
  -- getter. The final companion extracts only actually unresolved members.
  let addresses = sort . nub $ concat declaredAddresses ++
        [address | address <- concatMap snd archives, address `elem` requestedAddresses]
  if null signatures && null addresses then pure modules else do
    createDirectoryIfMissing True directory
    root <- canonicalizePath directory
    configured <- either fail pure (nativeCompilerArguments arguments)
    sources <- mapM (stubSource . snd) selected
    writeNativeWrappers compiler root (('-':'B':libdir):arguments) (('-':'B':libdir):configured)
      unit root signatures [] sources perModule [] (const Nothing) addresses (map fst archives) True
    let original = [(name,bytes) | (name,bytes) <- modules, name `elem` map fst selected]
    linked <- fst <$> finishPackageNativeWithDependencies packageTool ownedProduct [] []
      (root </> "pieces") root root unit (Just []) original
    pure [(name,maybe bytes id (lookup name linked)) | (name,bytes) <- modules]

nativeSignatures :: String -> [Value] -> Either String [Signature]
nativeSignatures unit modules = do
  imports <- concat <$> mapM moduleImports modules
  called <- mapM (nativeSignature unit) imports
  _ <- mapM coreNativeOverride (filter (owned unit) (concatMap calls modules))
  core <- mapM coreNativeImport imports
  finalizers <- nativeFinalizerSignatures unit modules
  let signatures = [signature | (signature,False) <- zip called core] ++ finalizers
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
      require (member proof "schema" `elem` map (Just . toJSON) ([1,2,3,4]::[Int]) && member proof "unit" == Just (toJSON unit) &&
        member proof "module" == member value "module" && member proof "scope" == Just "retained-static-import-products" &&
        member proof "execution" == Just "not-linked" && member proof "profile" `elem`
          [Just "ghc-9.14.1-thc-only-static-c-imports-v1",Just "ghc-9.14.1-thc-stock-static-foreign-imports-v2"])
        "package native imports lack typed provenance identity"
      require (maybe True (== proof) (member value "staticForeignImportStubs"))
        "package native retained stub provenance differs"
      if member proof "status" == Just "unclassified" then do
        requireKeys proof ["schema","scope","execution","profile","unit","module","status","reason"]
        require (member proof "reason" == Just "non-static-c-import-declaration")
          ("package native imports have an unrecognized unclassified producer: " ++ unit ++ "/" ++ show (member value "module") ++ " " ++ show (member proof "reason"))
        pure []
      else do
        requireKeys proof (["schema","scope","execution","profile","unit","module","status","wordBits","expectedForeign","expectedCalls","imports"] ++
          ["addresses" | member proof "schema" `elem` map (Just . toJSON) ([2,3,4]::[Int])] ++
          ["wrappers" | member proof "schema" `elem` map (Just . toJSON) ([3,4]::[Int])] ++
          ["importForeign" | member proof "schema" == Just (toJSON (4::Int))])
        require (member proof "status" == Just "verified" && member proof "wordBits" == Just (toJSON (64::Int)))
          "package native imports lack verified typed provenance"
        expected <- field proof "expectedForeign"
        require (maybe (emptyForeign expected) (== expected) (member value "foreign"))
          "package native retained foreign product differs from typed import provenance"
        require (member proof "expectedCalls" == Just (toJSON (calls value)))
          "package native Core calls differ from typed import provenance"
        _ <- nativeImportForeign value
        imports <- field proof "imports"
        require ((member proof "profile" == Just "ghc-9.14.1-thc-stock-static-foreign-imports-v2") ==
          any (\entry -> member entry "convention" == Just "prim") imports)
          "package primitive producer profile inventory differs"
        forM_ imports $ \entry -> do
          binder <- field entry "binder"
          require (member binder "unit" == Just (toJSON unit) && member binder "module" == member value "module")
            "package native import binder owner differs"
          _ <- nativeSignature unit entry
          require (member entry "convention" /= Just "prim" ||
            member proof "profile" == Just "ghc-9.14.1-thc-stock-static-foreign-imports-v2")
            "primitive import requires the stock foreign v2 profile"
          pure ()
        _ <- nativeAddresses unit value
        let javascript = [symbol | call <- calls value, owned unit call,
              Just symbol <- [javascriptSymbol call]]
        pure [entry | entry <- imports,
          (member entry "emitted" >>= (`member` "symbol")) `notElem` map Just javascript]

-- JavaScript declarations lower to reserved ccall markers, but their explicit
-- Core descriptor selects Truffle, not a native adapter. A symbol prefix alone
-- never changes linkage; keep ordinary C imports in the same module.
javascriptSymbol :: Value -> Maybe Value
javascriptSymbol call
  | member call "intrinsic" == Just "javascript-v1"
  , member call "schema" == Just (toJSON (1::Int))
  , member call "convention" == Just "ccall"
  , member call "safety" `elem` [Just "safe", Just "unsafe"]
  , Just (String source) <- member call "javascriptSource"
  , not (T.null source)
  , Just target <- member call "target"
  , member target "kind" == Just "static", member target "isFunction" == Just (Bool True)
  , let symbol = toJSON ("thc_javascript_v1_" ++ hex (T.encodeUtf8 source))
  , member target "symbol" == Just symbol = Just symbol
  | otherwise = Nothing

-- Address declarations carry their own nominal type and stock-emitter evidence.
-- They never become synthetic expectedCalls. The C-finalizer ABI has one
-- object pointer or an environment pointer followed by the object pointer.
nativeAddresses :: String -> Value -> Either String [Value]
nativeAddresses unit value = case member value "staticForeignImports" of
  Just proof | member proof "schema" `elem` map (Just . toJSON) ([2,3,4]::[Int]), member proof "status" == Just "verified" -> do
    addresses <- field proof "addresses"
    require ((not (null addresses) || member proof "schema" `elem` map (Just . toJSON) ([3,4]::[Int])) &&
      length addresses == length (nub addresses)) "empty or duplicate native address inventory"
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
        (case member entry "normalizedType" >>= finalizerType of
          Just arguments' -> member entry "callback" ==
            Just (object ["arguments" .= arguments',"result" .= ("void"::String)])
          Nothing -> False))
        "unsupported native callback proof"
    pure addresses
  _ -> Right []

-- The normalized nominal type is checked independently of the callback tag.
-- These are the two RTS C-finalizer ABIs, not arbitrary indirect FFI.
finalizerType :: Value -> Maybe [String]
finalizerType value
  | member value "kind" == Just "forall" = member value "body" >>= finalizerType
  | Just [function] <- named value "GHC.Internal.Ptr" "FunPtr",
    Just arguments' <- pointers function, length arguments' `elem` [1,2] = Just arguments'
  | otherwise = Nothing
  where
    pointers function
      | member function "kind" == Just "function",
        Just argument <- member function "argument", Just [_] <- named argument "GHC.Internal.Ptr" "Ptr",
        Just result <- member function "result", Just rest <- pointers result = Just ("AddrRep":rest)
      | Just [unit] <- named function "GHC.Internal.Types" "IO",
        Just [] <- named unit "GHC.Internal.Tuple" "Unit" = Just []
      | otherwise = Nothing
    named item modName occurrence
      | member item "kind" == Just "tycon", member item "name" == Just (object
          ["unit" .= ("ghc-internal"::String),"module" .= (modName::String),
           "occurrence" .= (occurrence::String),"namespace" .= ("type"::String)]),
        Just (Array args) <- member item "arguments" = Just (foldr (:) [] args)
      | otherwise = Nothing

nativeFinalizerSignatures :: String -> [Value] -> Either String [Signature]
nativeFinalizerSignatures unit modules = do
  mapM_ (nativeImports unit) modules
  addresses <- concat <$> mapM (nativeAddresses unit) modules
  sort . nub <$> mapM signature [entry | entry <- addresses, member entry "callback" /= Just Null,
    member entry "symbol" /= Just "free"]
  where
    signature entry = do
      symbol <- field entry "symbol"
      callback <- field entry "callback"
      arguments' <- field callback "arguments"
      pure (symbol,"ccall","unsafe",arguments',"void")

nativeFinalizers :: String -> [Value] -> Either String [String]
nativeFinalizers unit modules = sort . nub . map (\(symbol,_,_,_,_) -> symbol) <$> nativeFinalizerSignatures unit modules

-- | Check the original linked definition against the retained RTS callback ABI.
nativeFinalizerDefinition :: String -> [String] -> String -> Bool
nativeFinalizerDefinition symbol arguments' source =
  length definitions == 1 && all valid definitions
  where
    definitions = [line | line <- lines source, "define " `isPrefixOf` line,
      ("@" ++ symbol ++ "(") `isInfixOf` line]
    valid line = let (before,rest) = break (== '@') line
                     parameters = takeWhile (/= ')') (drop (length symbol + 2) rest)
                     ordinary = ["dso_local","noundef"]
                     fields = map T.unpack (T.splitOn "," (T.pack parameters))
                 in filter (`notElem` ordinary) (words before) == ["define","void"] &&
                   arguments' `elem` [["AddrRep"],["AddrRep","AddrRep"]] &&
                   length fields == length arguments' && all pointer fields
    pointer parameter = case words parameter of
      "ptr":attributes -> not (null attributes) && "%" `isPrefixOf` last attributes &&
        all (`elem` ["noundef","nocapture","readonly","writeonly"]) (init attributes)
      _ -> False

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
    (convention `notElem` ["ccall","prim"] || declaredSymbol == symbol) &&
    (case member entry "header" of Just Null -> True; Just (String header) -> validHeader (T.unpack header); _ -> False))
    "package native declaration differs from emitted ABI"
  if convention == "prim" then do
    require (identifier symbol && member emitted "unit" == Just (toJSON unit) && safety == "safe" &&
      member entry "header" == Just Null && member entry "isFunction" == Just (Bool True) &&
      all (\rep -> inputCarrier rep || gcCarrier rep || rep == "void") arguments &&
      all (\rep -> scalarCarrier rep || gcCarrier rep || rep == "void") results)
      "package primitive import has non-concrete stock carriers"
    -- This exact list is a retained exclusion signature, never a native ABI.
    -- The normalized nominal tree and recursive expectedCalls retain tuple shape.
    pure (symbol,convention,safety,arguments,show results)
  else do
    require (identifier symbol && member emitted "unit" == Just (toJSON unit) &&
      convention `elem` ["ccall", "capi"] && safety `elem` ["unsafe","safe","interruptible"] &&
      not (null arguments) && last arguments == "void" && all (\rep -> inputCarrier rep || gcCarrier rep) (init arguments))
      "package native call has malformed static C/CAPI metadata"
    result <- case results of
      ["void"] -> Right "void"
      ["void", result] | scalarCarrier result || gcCarrier result -> Right result
      _ -> Left "package native call requires State with zero or one concrete result"
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
-- Preserve declared safety for runtime foreign-call entry and readmission.
-- Interruptible native transport requires separate support.
-- Exact GC-carrier provenance is archival, not permission to pass a managed
-- object to C. Keep native ABI eligibility separate from proof validation.
supportedSignature (_,convention,safety,arguments,result) = convention `elem` ["ccall","capi"] && safety `elem` ["unsafe","safe"] &&
  all inputCarrier arguments && (result == "void" || scalarCarrier result)

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

gcCarrier :: String -> Bool
gcCarrier value = value `elem` ["BoxedRep (Just Lifted)","BoxedRep (Just Unlifted)"]

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
    cType "IntRep" = "intptr_t"
    cType "WordRep" = "uintptr_t"
    cType "FloatRep" = "float"
    cType "DoubleRep" = "double"
    cType value = (if "Word" `isPrefixOf` value then "uint" ++ drop 4 width else "int" ++ drop 3 width) ++ "_t"
      where width = take (length value - 3) value

-- Taking a symbol address needs no invocation ABI. The function declaration has
-- no parameter prototype and is never called here; the reached dynamic call
-- supplies its genuine GHC ABI separately. Opaque LLVM pointers retain the
-- actual linked definition's type, extent and storage.
nativeAddressSource :: [(String,Bool,String)] -> Either String String
nativeAddressSource entries = fmap concat $ forM entries $ \(symbol,function,entry) -> do
  require (identifier symbol && identifier entry) "invalid native address symbol"
  pure ((if function then "extern void " ++ symbol ++ "();\n" else "extern unsigned char " ++ symbol ++ "[];\n") ++
    "void * " ++ entry ++ "(void) { return (void *) &" ++ symbol ++ "; }\n")

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
      let output = maybe (maybe "" id (after "-odir" arguments) </> replaceExtension source "o") id (after "-o" arguments)
      exists <- doesFileExist output
      when (within root sourcePath && exists) $ do
        _ <- captureConfiguredNativeObject pieces root compiler arguments
        pure ()
    _ -> pure ()

-- The installed provider has already compiled this exact configured PIC argv.
-- Keep its declared object identity; replay only its C/C++ phase into LLVM.
captureConfiguredNativeObject :: FilePath -> FilePath -> FilePath -> [String] -> IO Value
captureConfiguredNativeObject pieces sourceRoot compiler arguments = do
  root <- canonicalizePath sourceRoot
  case [value | value <- arguments, takeExtension value `elem` [".c", ".cc", ".cpp", ".cxx"],
                not ("-" `isPrefixOf` value)] of
    [source] -> do
      sourcePath <- canonicalizePath (root </> source)
      check (within root sourcePath) "configured native source is outside its package"
      let output = maybe (maybe "" id (after "-odir" arguments) </> replaceExtension source "o") id (after "-o" arguments)
      native <- canonicalizePath (root </> output)
      nativeHash <- sha <$> BS.readFile native
      let directory = pieces </> sha (T.encodeUtf8 (T.pack native))
      createDirectoryIfMissing True directory
      (bitcode,target,inputs) <- compileC compiler root arguments directory Nothing
      let piece = object ["root" .= root,"object" .= native,"objectSha256" .= nativeHash,
            "bitcode" .= bitcode,"target" .= target,"inputs" .= inputs]
      writeJson (directory </> "piece.json") piece
      pure piece
    _ -> fail "configured native capture requires one C/C++ source"

-- Called while the package source and generated headers are still alive.
-- The CBD written by the late Core pass does not contain retained annotations;
-- hydrate exactly those interfaces which contain this unit's foreign calls.
capturePackageNative :: FilePath -> FilePath -> FilePath -> FilePath -> [String] -> String -> FilePath -> IO ()
capturePackageNative repository helper libdir compiler arguments unit directory = do
  let core = directory </> "core"
      objects = directory </> "objects"
  paths <- sort . filter ((== ".cbd") . takeExtension) <$> files core
  sourceValues <- mapM (\path -> BS.readFile path >>= either fail pure . readModuleValue) paths
  let needed = [(path,value) | (path,value) <- zip paths sourceValues,
        any (owned unit) (calls value) || hasFunctionAddress value || not (null (addressLabels value)) ||
        maybe False (\stubs -> stubs /= Null && member stubs "initializers" /= Just (toJSON ([]::[Value])))
          (member value "foreign" >>= (`member` "stubs"))]
  unless (null needed) $ do
    root <- getCurrentDirectory >>= canonicalizePath
    configured <- either fail pure (nativeCompilerArguments arguments)
    hydrated <- forM needed $ \(_,source) -> do
      name <- get source "module"
      let interface = objects </> map (\c -> if c == '.' then pathSeparator else c) name <.> "hi"
          databases = [database | (flag,database) <- zip arguments (drop 1 arguments), flag == "-package-db"]
          way = if "-dynamic" `elem` arguments then "dynamic" else "vanilla"
      (status,output,diagnostic) <- boundedInterfaceProcess helper (["--libdir",libdir,"--unit",unit,"--module",name,
        "--interface",interface,"--way",way,"--source-notes","--home-interfaces",objects] ++
        concatMap (\database -> ["--package-db",database]) databases)
      check (status == ExitSuccess) ("package native interface has no retained full Core: " ++ show diagnostic)
      value <- either fail pure (readModuleValue output)
      check (member value "unit" == Just (toJSON unit) && member value "module" == Just (toJSON (name::String)))
        "package native retained interface identity differs"
      pure (output,value)
    retained <- either fail pure (archiveNativeModules unit (map snd hydrated))
    -- Archive classification is an in-memory input to wrapper selection. Keep
    -- the original payload untouched until final native linkage is known.
    forM_ (zip needed hydrated) $ \((path,_),(bytes,_)) -> BS.writeFile path bytes
    signatures <- either fail pure (nativeSignatures unit retained)
    addresses <- sort . nub . concat <$> mapM (either fail pure . nativeAddressDeclarations unit) retained
    finalizers <- either fail pure (nativeFinalizers unit retained)
    when (null signatures && null addresses) $ writeJson (directory </> "native.json")
      (object ["unit" .= unit,"archiveOnly" .= True])
    unless (null signatures && null addresses) $ do
      perModule <- mapM (either fail pure . nativeSignatures unit . (:[])) retained
      -- Ordinary ccall adapters never consume GHC's C stubs. In particular a
      -- wrapper's RTS callback helper belongs to the managed NFI bridge, not LLVM.
      sources <- mapM (\(value,signatures') -> if any (\(_,convention,_,_,_) -> convention == "capi") signatures'
          then stubSource value else pure "")
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
      writeNativeWrappers compiler root arguments configured unit directory signatures finalizers
        sources perModule providers wrapperHeader addresses [] False

-- The adapter compiler is shared by source acquisition and installed FCallIds.
-- Both paths carry the original declared ABI, actual CAPI source and headers.
writeNativeWrappers :: FilePath -> FilePath -> [String] -> [String] -> String -> FilePath ->
  [Signature] -> [String] -> [String] -> [[Signature]] -> [Value] -> (String -> Maybe String) -> [(String,Bool)] -> [FilePath] -> Bool -> IO ()
writeNativeWrappers compiler root arguments configured unit directory signatures finalizers sources perModule providers wrapperHeader addresses dataLibraries installed = do
  let nativeDirectory = directory </> "native"
  nativeTarget <- if installed then do
    clang <- tool "THC_CLANG" "clang"
    command root clang ["-dumpmachine"]
    else pure ""
  -- Installed adapters call the original native package bodies through the
  -- Win64 scalar ABI. Compile those adapters for Sulong's real MSVC target;
  -- captured MinGW source LLVM keeps its original target and admission rules.
  -- MinGW's GHC headers otherwise erase __attribute__ when __GNUC__ is absent,
  -- breaking Clang's own vector/intrinsic headers. Its GNU compatibility mode
  -- retains those attributes without changing the actual MSVC target or ABI.
  let adapterArguments = ["-optc" ++ option |
        "x86_64-" `isPrefixOf` nativeTarget &&
        ("-windows" `isInfixOf` nativeTarget || "-mingw" `isInfixOf` nativeTarget),
        option <- ["--target=x86_64-pc-windows-msvc","-fgnuc-version=4.2.1"]]
  capiOwners <- if installed then forM (nub [symbol | (symbol,"capi",_,_,_) <- signatures]) $ \symbol -> do
      owner <- either fail pure (nativeCapiSource symbol sources)
      pure (symbol,owner)
    else pure []
  dataInputs <- forM dataLibraries $ \path -> do
    digest <- sha <$> BS.readFile path
    pure (object ["path" .= path,"sha256" .= digest])
  let inputIdentity = object $ ["unit" .= unit,"compiler" .= compiler,"arguments" .= arguments,"installed" .= installed,
        "sources" .= sources,"providers" .= providers,"addresses" .= addresses,"dataLibraries" .= dataInputs,
        "imports" .= map (\(a,b,c,d,e) -> toJSON (a,b,c,d,e)) signatures,"finalizers" .= finalizers] ++
        ["adapterArguments" .= adapterArguments | not (null adapterArguments)]
      provisional = sha (BL.toStrict (encode inputIdentity))
      inventory = sort ([(signature,False) | signature <- signatures] ++
        [((symbol,"ccall","unsafe",[],"AddrRep"),True) | (symbol,_) <- addresses])
      makeEntries component = [(signature,address,"thc_native_" ++ component ++ "_" ++ show index) |
        (index,(signature,address)) <- zip [0::Int ..] inventory]
      callEntries component = [(signature,entry) | (signature,False,entry) <- makeEntries component]
      addressEntries component = [(symbol,function,entry) | ((symbol,_,_,_,_),True,entry) <- makeEntries component,
        Just function <- [lookup symbol addresses]]
      -- GHC emits separate ccall prototypes. Keep different declared types of
      -- one symbol in separate C translation units too; signedness variants
      -- with the same native ABI do not need a retained source header.
      insertEntry entry [] = [[entry]]
      insertEntry entry@(signature@(symbol,_,_,_,_),_) (group:groups)
        | any (\(other@(name,_,_,_,_),_) -> symbol == name && nativeCAbi id signature /= nativeCAbi id other) group =
            group : insertEntry entry groups
        | otherwise = (entry:group):groups
      -- GHC compiles each module's CAPI stubs as its own translation unit.
      -- Direct ccall adapters must not enter that CAPI header namespace:
      -- GHC emits those calls independently, and a header may name struct
      -- pointers or narrower C parameters than the emitted caller ABI.
      -- Preserve private helpers, macros and header include boundaries.
      -- Repeated direct ccall imports need just one component adapter.
      compileCalls component = forM
        [(index,variant,convention,if convention == "capi" then source else "",entries) |
          (index,(source,ownedSignatures)) <- zip [0::Int ..] (zip sources perModule),
          convention <- ["ccall","capi"],
          let earlier = concat (take index perModule),
          let selectedEntries = [entry | entry@(signature@(symbol,callConvention,_,_,_),_) <- callEntries component,
                callConvention == convention,
                if installed && convention == "capi" then lookup symbol capiOwners == Just index
                else signature `elem` ownedSignatures && signature `notElem` earlier],
          (variant,entries) <- zip [0::Int ..] (if installed then foldr insertEntry [] selectedEntries else [selectedEntries]),
          not (null entries)] $ \(index,variant,convention,source,entries) -> do
            let output = nativeDirectory </> show index </> convention </> if installed then show variant else ""
            createDirectoryIfMissing True output
            wrappers <- either fail pure (nativeWrapperSource
              [(signature,entry,wrapperHeader symbol) | (signature@(symbol,_,_,_,_),entry) <- entries])
            -- Direct ccall declares the emitted ABI itself. Even HsFFI.h
            -- declares RTS functions using nominal pointer types that can
            -- conflict with valid opaque Addr# callers. CAPI keeps its actual
            -- GHC stub headers; adapters need only standard scalar types.
            let preamble = ["#include <Rts.h>\n" | convention == "capi"] ++ ["#include <stdint.h>\n"]
            (bitcode,target,inputs) <- compileC compiler root (configured ++ adapterArguments) output
              (Just (concat preamble ++ source ++ wrappers))
            headers <- headerInputs (output </> "wrappers.c") inputs
            pure (bitcode,target,inputs,headers)
      compileUnits component = do
        called <- compileCalls component
        addressUnits <- if null addresses then pure [] else do
          let output = nativeDirectory </> "addresses"
          createDirectoryIfMissing True output
          source <- either fail pure (nativeAddressSource (addressEntries component))
          (bitcode,target,inputs) <- compileC compiler root (configured ++ adapterArguments) output (Just source)
          headers <- headerInputs (output </> "wrappers.c") inputs
          pure [(bitcode,target,inputs,headers)]
        pure (called ++ addressUnits)
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
    ["unit" .= unit,"installed" .= installed,"root" .= root,"objectRoots" .= roots,"bitcode" .= bitcode,"target" .= target,
     "componentSha256" .= component,"inputs" .= inputs,"sourceIdentity" .= inputIdentity,"providers" .= providers,
     "dataLibraries" .= dataLibraries,"dataSymbols" .= [entry | (_,True,entry) <- entries],
     "finalizers" .= [entry | ((symbol,"ccall","unsafe",arguments',"void"),False,entry) <- entries,
       arguments' `elem` [["AddrRep"],["AddrRep","AddrRep"]], symbol `elem` finalizers],
     "abi" .= [object ["symbol" .= symbol,"entry" .= entry,"convention" .= convention,"safety" .= safety,
       "arguments" .= arguments',"result" .= result] |
       ((symbol,convention,safety,arguments',result),_,entry) <- entries]])

-- An inlined FCallId still names the original generated CAPI wrapper. Place
-- its adapter with that retained source, not with whichever caller was seen
-- first. The C compiler checks the actual declaration and native ABI.
nativeCapiSource :: String -> [String] -> Either String Int
nativeCapiSource symbol sources = case [index | (index,source) <- zip [0..] sources,
  symbol `elem` words (map (\c -> if isAlphaNum c || c == '_' then c else ' ') source)] of
  [index] -> Right index
  [] -> Left ("No retained CAPI source for emitted wrapper: " ++ symbol)
  _ -> Left ("Ambiguous retained CAPI source for emitted wrapper: " ++ symbol)

hasFunctionAddress :: Value -> Bool
hasFunctionAddress (Array values) = case foldr (:) [] values of
  String "lit" : String "function-addr" : _ -> True
  items -> any hasFunctionAddress items
hasFunctionAddress (Object fields) = any hasFunctionAddress (KM.elems fields)
hasFunctionAddress _ = False

addressLabels :: Value -> [(String,Bool)]
addressLabels (Array values) = case foldr (:) [] values of
  String "lit" : String kind : String symbol : _ | kind `elem` ["data-addr","function-addr"] -> [(T.unpack symbol,kind == "function-addr")]
  items -> concatMap addressLabels items
addressLabels (Object fields) = concatMap addressLabels (KM.elems fields)
addressLabels _ = []

nativeAddressDeclarations :: String -> Value -> Either String [(String,Bool)]
nativeAddressDeclarations unit value = do
  declarations <- nativeAddresses unit value
  symbols <- forM [entry | entry <- declarations, member entry "callback" == Just Null] $ \entry ->
    (,) <$> field entry "symbol" <*> field entry "isFunction"
  -- Native GHC process-global state cannot represent a THC context's scheduler
  -- or compiler state. These exact RTS objects remain context-owned overrides.
  pure [(symbol,function) | (symbol,function) <- symbols, function || symbol `notElem`
    ["enabled_capabilities","ghc_unique_counter64","ghc_unique_inc","RtsFlags","rts_IOManagerIsWin32Native"]]

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
stubSource value = either fail pure (nativeImportForeign value) >>= \product' -> case product' of
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

-- Only a stock producer partition can remove export/RTS products from the C
-- adapter translation unit. The complete original archive remains unchanged.
nativeImportForeign :: Value -> Either String (Maybe Value)
nativeImportForeign value = case member value "staticForeignImports" of
  Just proof | member proof "schema" == Just (toJSON (4::Int)) -> do
    exports <- nativeStaticExports value
    require (not (null exports)) "mixed import partition lacks static export declarations"
    require (member proof "status" == Just "verified" && member proof "expectedForeign" == member value "foreign")
      "mixed native import product differs from its original archive"
    product' <- field proof "importForeign"
    require (member product' "schema" == Just (toJSON (1::Int)) &&
      member product' "execution" == Just "not-linked" && member product' "files" == Just (toJSON ([]::[Value])))
      "mixed import partition has additional foreign obligations"
    case member product' "stubs" of
      Just stubs | stubs /= Null -> require
        (member stubs "initializers" == Just (toJSON ([]::[Value])) && member stubs "finalizers" == Just (toJSON ([]::[Value])))
        "mixed import partition has lifecycle obligations"
      _ -> pure ()
    pure (Just product')
  _ -> pure (member value "foreign")

nativeStaticExports :: Value -> Either String [Value]
nativeStaticExports value = case member value "staticForeignExports" of
  Nothing -> pure []
  Just inventory -> do
    registration <- field value "staticForeignExportRegistration"
    require (member inventory "schema" == Just (toJSON (1::Int)) &&
      member inventory "producer" == Just "THC.Plugin/typeCheckResultAction" &&
      member inventory "scope" == Just "static-export-associations" && member inventory "execution" == Just "not-linked" &&
      member inventory "unit" == member value "unit" && member inventory "module" == member value "module")
      "static export inventory owner/profile differs"
    require (member registration "schema" == Just (toJSON (2::Int)) && member registration "status" == Just "verified" &&
      member registration "wordBits" == Just (toJSON (64::Int)) &&
      member registration "scope" == Just "retained-foreign-products" && member registration "execution" == Just "not-linked" &&
      member registration "profile" `elem` map Just ["ghc-9.14.1-thc-only-native-static-ccall-v1",
        "ghc-9.14.1-thc-only-native-static-ccall-imports-v2","ghc-9.14.1-thc-only-native-static-c-products-v3"] &&
      member registration "expectedForeign" == member value "foreign" && member registration "expectedExports" == Just inventory)
      "static exports lack exact stock registration provenance"
    exports <- field inventory "exports"
    require (not (null exports) && member registration "roots" == Just (toJSON [binder | entry <- exports, Just binder <- [member entry "binder"]]))
      "static export registration roots differ"
    forM_ exports $ \entry -> do
      binder <- field entry "binder"
      symbol <- field entry "symbol"
      require (identifier symbol && member binder "unit" == member value "unit" && member binder "module" == member value "module" &&
        member binder "namespace" == Just "value" && member entry "convention" == Just "ccall" &&
        member entry "normalizationRole" == Just "representational") "static export declaration identity differs"
      owner <- field binder "unit"
      name <- field binder "module"
      occurrence <- field binder "occurrence"
      bindings <- field value "bindings"
      require (length [binding | binding <- bindings,
        member binding "id" == Just (toJSON (owner ++ ":" ++ name ++ "." ++ occurrence :: String))] == 1)
        "static export declaration has no exact retained Core binder"
    pure exports

-- PE is not a Sulong LLVM container. Keep Windows components as verified raw
-- LLVM with a separately rooted native companion, including ordinary imports.
nativeDeferredLinkArguments :: String -> [String] -> Maybe [String]
nativeDeferredLinkArguments target symbols
  | "-windows" `isInfixOf` target || "-mingw" `isInfixOf` target = Nothing
  | otherwise = Just $ concatMap (\symbol ->
      if "-darwin" `isInfixOf` target || "-apple-macosx" `isInfixOf` target
        then ["-Xlinker","-U","-Xlinker",'_' : symbol]
        else ["-Xlinker","--ignore-unresolved-symbol=" ++ symbol]) symbols

nativeRootArguments :: String -> [String] -> [String]
nativeRootArguments target
  -- Without the component's machine-code references, -u can extract an archive
  -- but still succeed when a root has no definition. PE must prove each one.
  | "-windows" `isInfixOf` target || "-mingw" `isInfixOf` target =
      concatMap (\symbol -> ["-Xlinker","--require-defined=" ++ symbol])
  | otherwise = concatMap (\symbol -> ["-Xlinker","-u","-Xlinker",
      if "-darwin" `isInfixOf` target || "-apple-macosx" `isInfixOf` target then '_' : symbol else symbol])

finishPackageNative :: FilePath -> FilePath -> FilePath -> String -> Maybe [FilePath] -> [(String,FilePath)] -> IO [(String,FilePath)]
finishPackageNative packageTool pieces directory unit objects modules =
  fst <$> finishPackageNativeWithDependencies packageTool Nothing [] [] pieces directory directory unit objects modules

-- | Declared dependency paths refer to separately linked components. Never
-- embed another unit's C globals/constructors in this component's LLVM.
finishPackageNativeWithDependencies :: FilePath -> Maybe NativeProduct -> [([String],Value)] -> [FilePath] -> FilePath -> FilePath -> FilePath ->
  String -> Maybe [FilePath] -> [(String,FilePath)] -> IO ([(String,FilePath)],Maybe Value)
finishPackageNativeWithDependencies packageTool ownedProduct dependencyPaths publishedDatabases pieces sourceDirectory directory unit currentObjects modules = do
  -- Validate retained call products against actual Core once, before linking.
  -- Header-only values intentionally have no bindings and are not call proofs.
  decoded <- forM modules $ \(_,path) -> do
    bytes <- BS.readFile path
    value <- either fail pure (readModuleValue bytes)
    pure (sha bytes,value)
  classified <- either fail pure (archiveNativeModules unit (map snd decoded))
  let archived = zip3 modules (map fst decoded) classified
  let receipt = sourceDirectory </> "native.json"
  exists <- doesFileExist receipt
  if not exists then bare archived else do
    record <- readJson receipt
    check (member record "unit" == Just (toJSON unit)) "package native receipt owner differs"
    if member record "archiveOnly" == Just (Bool True) then do
      check (case record of Object fields -> KM.size fields == 2; _ -> False) "invalid archive-only native receipt"
      bare archived
    else finish archived record
  where
   dependencies = nub (map snd dependencyPaths)
   bare archived = case ownedProduct of
    Nothing -> unlinked archived
    Just captured -> case nativeProductPieces captured of
      [] -> unlinked archived
      first:_ -> do
        root <- get first "root" :: IO String
        target <- get first "target" :: IO String
        inputs <- get first "inputs"
        compiler <- get inputs "compiler" :: IO String
        arguments <- get inputs "arguments" :: IO [String]
        let proof = nativeProductProof captured
        finish archived (object ["unit" .= unit,"root" .= root,"target" .= target,
          "objectRoots" .= ([]::[String]),"inputs" .= ([]::[Value]),"abi" .= ([]::[Value]),
          "componentSha256" .= sha (BL.toStrict (encode proof)),
          "sourceIdentity" .= object ["compiler" .= compiler,"arguments" .= arguments,"nativeProduct" .= proof]])
   finish archived record = do
    roots <- get record "objectRoots" :: IO [FilePath]
    root <- get record "root"
    target <- get record "target"
    components <- mapM readJson =<< files (pieces </> "components")
    allRoots <- concat <$> mapM (\value -> get value "roots")
      [value | value <- components, member value "root" == Just (toJSON (root::String))]
    candidates <- filter ((== "piece.json") . takeFileName) <$> files pieces
    discovered <- filterM (\value -> do
      objectPath <- get value "object"
      pure (member value "root" == Just (toJSON (root::String)) &&
        maybe (nativeObjectOwned roots allRoots objectPath) (objectPath `elem`) currentObjects)) =<< mapM readJson candidates
    -- Local receipts survive runs; exact current Cabal membership excludes
    -- deleted/renamed C sources and sibling components. Private store receipts
    -- instead belong to this fresh acquisition, after unpacked objects vanish.
    let native = maybe discovered nativeProductPieces ownedProduct
    case currentObjects of
      Nothing -> pure ()
      Just _ -> forM_ native $ \value -> do
        path <- get value "object"
        digest <- sha <$> BS.readFile path
        check (member value "objectSha256" == Just (toJSON digest)) "package native object receipt is stale"
    forM_ ownedProduct $ \capturedProduct -> do
      let proof = nativeProductProof capturedProduct
      owner <- get proof "unit"
      check (owner == unit) "native product is not owned by this component"
      products <- get proof "translationUnits" :: IO [Value]
      forM_ products $ \captured -> do
        piece <- get captured "receipt"
        path <- get piece "bitcode"
        observed <- sha <$> BS.readFile path
        check (member captured "bitcodeSha256" == Just (toJSON observed))
          "native component bitcode changed after selection"
    forM_ native $ \value -> check (member value "target" == Just (toJSON (target::String))) "package C object target differs"
    forM_ dependencyPaths $ \(path,dependency) -> do
      owner <- get dependency "unit"
      check (not (null path) && last path == owner && owner /= unit && unit `notElem` path &&
        member dependency "target" == Just (toJSON (target::String))) "native dependency path/target differs"
    bitcodePaths <- mapM (`get` "bitcode") native
    wrapper <- maybe (pure []) (fmap (:[]) . either fail pure . parseValue) (member record "bitcode")
    inputHashes <- mapM (fmap sha . BS.readFile) (wrapper ++ bitcodePaths)
    producer <- sha <$> (BS.readFile =<< getExecutablePath)
    let inputHash = sha (BL.toStrict (encode (producer,record,inputHashes,dependencyPaths,
          nativeProductProof <$> ownedProduct,[digest | (_,digest,_) <- archived])))
        cached = directory </> "native/component-link.json"
    present <- doesFileExist cached
    previous <- if present then Just <$> readJson cached else pure Nothing
    proof <- case previous of
      Just value | member value "inputSha256" == Just (toJSON inputHash) -> get value "link"
      _ -> do
        value <- materialize archived record native
        writeJson cached (object ["inputSha256" .= inputHash,"link" .= value])
        pure value
    linkedModules <- attach archived record proof
    let descriptor = object $ ["schema" .= (1::Int),"profile" .= ("thc-package-native-component-v1"::String)] ++
          [Key.fromString key .= value | key <- ["unit","target","componentSha256","bitcodeSha256","bitcodeHex",
            "format","exports","dependencies","nativeLibrary"], Just value <- [member proof key]]
    pure (linkedModules,Just descriptor)
   materialize archived record native = do
    target <- get record "target" :: IO String
    abi <- get record "abi" :: IO [Value]
    let empty key = maybe True (== toJSON ([]::[Value])) (member record key)
        eligible = not (null native) && not (null abi) &&
          all ((== Just "ccall") . (`member` "convention")) abi &&
          all empty ["dataSymbols","finalizers","providers"]
    if not eligible then materializeStrict archived record native else do
      root <- get record "root"
      identity <- get record "sourceIdentity"
      compiler <- get identity "compiler"
      originalArguments <- get identity "arguments"
      configured <- either fail pure (nativeCompilerArguments originalArguments)
      bitcodes <- mapM (`get` "bitcode") native
      inputs <- mapM (fmap sha . BS.readFile) bitcodes
      let component = sha (BL.toStrict (encode (unit,target,identity,inputs)))
          demand = directory </> "native/demand"
          original = demand </> "provider.bc"
          originalIR = demand </> "provider.ll"
          provider symbol = "thc_provider_" ++ component ++ "_" ++ symbol
          entry index = "thc_native_" ++ component ++ "_" ++ show index
      createDirectoryIfMissing True demand
      link <- tool "THC_LLVM_LINK" "llvm-link"
      opt <- tool "THC_LLVM_OPT" "opt"
      _ <- command directory link (bitcodes ++ ["-o",original])
      _ <- command directory opt ["-S","-passes=verify",original,"-o",originalIR]
      source <- readFile originalIR
      symbols <- mapM (`get` "symbol") abi :: IO [String]
      -- Unknown providers, unsupported native shapes and ABI conversions stay
      -- on the existing strict path. A catalogue cannot invent their provider.
      case (,) <$> nativeModuleLayout target source <*>
        mapM (\symbol -> nativeProviderForwarding target symbol (provider symbol) source) (nub symbols) of
        Nothing -> materializeStrict archived record native
        Just (layout,forwards) -> do
          let forwarding = demand </> "forwarding.ll"
              forwardBitcode = demand </> "forwarding.bc"
              forwardingSource = unlines ["target triple = " ++ show target,
                "target datalayout = " ++ show layout] ++ concat forwards
          writeFile forwarding forwardingSource
          _ <- command directory opt ["-passes=verify",forwarding,"-o",forwardBitcode]
          compiled <- forM (zip [0::Int ..] abi) $ \(index,signature) -> do
            symbol <- get signature "symbol"
            safety <- get signature "safety"
            arguments <- get signature "arguments"
            returned <- get signature "result"
            wrappers <- either fail pure (nativeWrapperSource
              [((provider symbol,"ccall",safety,arguments,returned),entry index,Nothing)])
            let seedDirectory = demand </> "seeds" </> show index
            createDirectoryIfMissing True seedDirectory
            (path,observed,metadata) <- compileC compiler root configured seedDirectory
              (Just ("#include <stdint.h>\n" ++ wrappers))
            check (observed == target) "package native call seed target differs"
            let seedIR = seedDirectory </> "verified.ll"
            _ <- command directory opt ["-S","-passes=verify",path,"-o",seedIR]
            seedSource <- readFile seedIR
            let witness = nativeCallWitness target (provider symbol) (entry index) forwardingSource seedSource
            bytes <- BS.readFile path
            pure (setMember "entry" (toJSON (entry index)) signature,
              object ["entry" .= entry index,"bitcodeSha256" .= sha bytes,"bitcodeHex" .= hex bytes,
                "providerUnit" .= unit,"providerComponentSha256" .= component,"providerSymbol" .= provider symbol],
              metadata,witness)
          if any (\(_,_,_,witness) -> witness == Nothing) compiled
            then materializeStrict archived record native
            else do
              let providerAbi = [setMember "entry" (toJSON (provider symbol)) signature |
                    (symbol,signature) <- nubBySymbol (zip symbols abi)]
                  canonical = setMember "abi" (toJSON providerAbi) . setMember "bitcode" (toJSON forwardBitcode) .
                    setMember "componentSha256" (toJSON component) $ record
              -- Only this component contains its real C globals and lifecycle.
              -- The strict materializer retains public C/constructor/native
              -- obligations; first-use modules contain only their call seed.
              proof <- materializeStrict archived canonical native
              bytes <- BS.readFile (directory </> "native/package.bc")
              let canonicalIR = demand </> "canonical.ll"
              _ <- command directory opt ["-S","-passes=verify",directory </> "native/package.bc","-o",canonicalIR]
              canonicalSource <- readFile canonicalIR
              nm <- tool "THC_LLVM_NM" "llvm-nm"
              let names flags path = do
                    output <- command directory nm (flags ++ ["--format=posix",path])
                    pure (sort [nativeIrSymbol target name | line <- lines output, name:_ <- [words line]])
              verified <- forM (zip [0::Int ..] compiled) $ \(index,(signature,_,_,_)) -> do
                symbol <- get signature "symbol"; safety <- get signature "safety"
                arguments <- get signature "arguments"; returned <- get signature "result"
                let seedDirectory = demand </> "seeds" </> show index
                    path = seedDirectory </> "target.bc"
                    seedIR = seedDirectory </> "verified.ll"
                seedSource <- readFile seedIR
                definitions <- names ["--defined-only"] path
                externals <- names ["--undefined-only"] path
                pure (nativeModuleLayout target canonicalSource == Just layout &&
                  nativeCallSeedWitness target (symbol,"ccall",safety,arguments,returned)
                    (entry index) (provider symbol) canonicalSource seedSource definitions externals)
              if not (and verified) then materializeStrict archived record native else do
                exports <- get proof "exports" :: IO [String]
                let buildInputs = maybe (object []) id (member proof "buildInputs")
                    seedInputs = [metadata | (_,_,metadata,_) <- compiled]
                translationUnits <- get buildInputs "translationUnits" :: IO [Value]
                let
                    result = setMember "schema" (toJSON (3::Int)) .
                      setMember "profile" "thc-package-c-ffi-demand-v1" .
                      setMember "format" "llvm-bitcode" . setMember "bitcodeSha256" (toJSON (sha bytes)) .
                      setMember "bitcodeHex" (toJSON (hex bytes)) .
                      setMember "abi" (toJSON [signature | (signature,_,_,_) <- compiled]) .
                      setMember "callSeeds" (toJSON [seed | (_,seed,_,_) <- compiled]) .
                      setMember "exports" (toJSON (sort (nub (map provider symbols ++ exports)))) .
                      setMember "buildInputs" (setMember "translationUnits" (toJSON (translationUnits ++ seedInputs)) buildInputs) $ proof
                pure result
   nubBySymbol [] = []
   nubBySymbol (value@(symbol,_):rest) = value : nubBySymbol (filter ((/= symbol) . fst) rest)
   materializeStrict archived record native = do
    root <- get record "root"
    target <- get record "target"
    wrapper <- maybe (pure []) (fmap (:[]) . either fail pure . parseValue) (member record "bitcode")
    createDirectoryIfMissing True (directory </> "native")
    wrapperInputs <- get record "inputs" :: IO [Value]
    nativeInputs <- mapM (\value -> get value "inputs" :: IO Value) native
    let sourceInputs = wrapperInputs ++ nativeInputs
    bitcodes <- mapM (\value -> get value "bitcode") native
    abi <- get record "abi" :: IO [Value]
    finalizers <- maybe (pure []) (either fail pure . parseValue) (member record "finalizers") :: IO [String]
    dataSymbols <- maybe (pure []) (either fail pure . parseValue) (member record "dataSymbols") :: IO [String]
    entries <- mapM (\value -> get value "entry") abi
    link <- tool "THC_LLVM_LINK" "llvm-link"
    opt <- tool "THC_LLVM_OPT" "opt"
    nm <- tool "THC_LLVM_NM" "llvm-nm"
    let linked = directory </> "native/linked.bc"
        linkedIR = directory </> "native/linked.ll"
        final = directory </> "native/package.bc"
    _ <- command directory link (wrapper ++ bitcodes ++ ["-o",linked])
    defined <- sort . nub . concat <$> forM bitcodes (\path -> do
      output <- command directory nm ["--defined-only","--extern-only","--format=posix",path]
      pure [symbol | line <- lines output, name:_ <- [words line],
        let symbol = nativeIrSymbol target name, not ("llvm." `isPrefixOf` symbol)])
    -- External linkage alone includes hidden helpers. LLVM's Darwin-style
    -- output exposes bitcode visibility on every target: hidden definitions
    -- are "private external", unlike default/protected exports. Inspect the
    -- linked component, since other translation units can narrow visibility.
    symbols <- command directory nm ["--defined-only","--extern-only","--format=darwin",linked]
    let visiblePublic = sort . nub $ [symbol | line <- lines symbols, name:attributes <- [reverse (words line)],
          let symbol = nativeIrSymbol target name, symbol `elem` defined, "external" `elem` attributes, "private" `notElem` attributes]
        -- Installed units are closed executables rooted by their whole-unit
        -- FFI/address entries. Source-store components remain reusable C
        -- providers and retain every public definition for declared consumers.
        public = if member record "installed" == Just (Bool True)
          then filter (`elem` entries) visiblePublic else visiblePublic
    _ <- command directory opt ["-S","-passes=verify",linked,"-o",linkedIR]
    linkedSource <- readFile linkedIR
    -- A typed Haskell address is not a C definition proof. Require the actual
    -- linked definition before allowing its namespaced pointer adapter.
    forM_ [value | value <- abi, member value "entry" `elem` map (Just . toJSON) finalizers] $ \value -> do
      symbol <- get value "symbol"
      arguments' <- get value "arguments" :: IO [String]
      check (nativeFinalizerDefinition symbol arguments' linkedSource)
        ("package finalizer definition differs from its pointer ABI: " ++ symbol)
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
    -- Publish ordinary LLVM operations rather than intrinsics normally lowered
    -- by a machine backend (for example relative string-table loads).
    passes <- words <$> command directory opt ["--print-passes"]
    let modern = "pre-isel-intrinsic-lowering" `elem` passes
        trim input = do
          lowered <- if modern then pure input else do
            -- LLVM 18 exposes this lowering only through the legacy pass manager.
            -- Its target pass configuration requires the captured target triple.
            let output = directory </> "native/pre-isel.bc"
            _ <- command directory opt ["--mtriple=" ++ target,
              "-pre-isel-intrinsic-lowering",input,"-o",output]
            pure output
          command directory opt ["-passes=" ++
            (if modern then "pre-isel-intrinsic-lowering," else "") ++ "internalize,globaldce",
            "-internalize-public-api-list=" ++ join "," (entries ++ public),lowered,"-o",final]
        unresolved = do
          output <- command directory nm ["--undefined-only","--format=posix",final]
          pure [nativeIrSymbol target name | line <- lines output, name:_ <- [words line]]
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
    declarations <- fmap concat $ forM archived $ \(_,_,value) -> do
      either fail pure (nativeStaticExports value)
    exportNames <- mapM (`get` "symbol") declarations :: IO [String]
    peers <- nub . concat <$> mapM dependencyClosure dependencies
    peerExternals <- filterM (\symbol -> do
      owners <- fmap nub . fmap concat $ forM peers $ \peer -> do
        names <- get peer "exports" :: IO [String]
        pure [(member peer "unit",member peer "componentSha256",member peer "bitcodeSha256") | symbol `elem` names]
      check (length owners <= 1) ("ambiguous declared native providers for " ++ symbol)
      pure (not (null owners))) externals
    -- Stable pointers, exported callbacks and bound-thread support belong to
    -- this context. A native RTS would describe its scheduler, not THC's.
    -- Keep their calls and declared ABI in verified LLVM unchanged.
    let managedExternals = filter (`elem` (["hs_free_stable_ptr","rtsSupportsBoundThreads"] ++ exportNames)) externals
        deferredExternals = nub (managedExternals ++ peerExternals)
        nativeExternals = filter (\name -> name `notElem` deferredExternals && not ("llvm." `isPrefixOf` name)) externals
    -- The configured C compiler and linker own native symbol resolution.
    -- Sulong consumes the embedded LLVM and native dependency list; there is
    -- no tested-symbol inventory or inferred library/ABI here.
    identity <- get record "sourceIdentity"
    compiler <- get identity "compiler"
    originalArguments <- get identity "arguments"
    libdir <- command directory compiler ["--print-libdir"] >>= \output -> case lines output of
      [path] -> pure path
      _ -> fail "native compiler did not report one library directory"
    externalArguments <- nativeLinkInputs packageTool libdir root publishedDatabases (Just unit) originalArguments
    dataLibraries <- maybe (pure []) (either fail pure . parseValue) (member record "dataLibraries") :: IO [FilePath]
    -- Installed packages may bundle native dependencies in their registered
    -- archive rather than extra-libraries. Resolve only calls still external
    -- after captured LLVM and managed/peer callbacks have claimed their bodies;
    -- raw Core address labels must not select native Haskell closures here.
    nativeArchives <- if member record "installed" == Just (Bool True) && not (null nativeExternals)
      then do
        -- Inspect the final module: source providers can introduce declarations
        -- after linkedSource was read. nm alone does not distinguish undefined
        -- functions from data references to native text, such as info tables.
        let finalIR = directory </> "native/package.ll"
        _ <- command directory opt ["-S","-passes=verify",final,"-o",finalIR]
        finalSource <- readFile finalIR
        nativeSymbolArchives packageTool libdir root unit originalArguments
          [(symbol,True) | symbol <- nativeFunctionExternals target nativeExternals finalSource]
      else pure []
    nativeArchiveInputs <- forM (map fst nativeArchives) $ \path -> do
      hash <- sha <$> BS.readFile path
      pure (object ["path" .= path,"sha256" .= hash])
    let linkArguments = dataLibraries ++ map fst nativeArchives ++ externalArguments
    clang <- tool "THC_CLANG" "clang"
    sdkFlags <- nativeCompilerFlags
    (artifact,format,libraries,nativeLibrary,runtimeInputs) <- if null nativeExternals
      then pure (final,"llvm-bitcode",[],Nothing,[]) else do
        let darwin = "-darwin" `isInfixOf` target || "-apple-macosx" `isInfixOf` target
            windows = "-windows" `isInfixOf` target || "-mingw" `isInfixOf` target
            -- Installed Core can retain unused RTS calls. Do not pull native
            -- RTS archives into the process to satisfy them: ordinary shared
            -- library lazy resolution reports a missing target if reached.
            resolution = if member record "installed" == Just (Bool True)
              then ["-Wl,-undefined,dynamic_lookup" | darwin]
              else [if darwin then "-Wl,-undefined,error" else "-Wl,--no-undefined"]
            container = directory </> if darwin then "native/final.dylib" else "native/final.so"
            artifact = if windows || not (null deferredExternals) then final else container
            format = if windows || not (null deferredExternals) then "llvm-bitcode"
              else if darwin then "llvm-embedded-mach-o" else "llvm-embedded-elf"
            -- Current Apple ld ignores -fembed-bitcode's legacy bundle flag.
            -- Sulong accepts raw bitcode in the Mach-O __LLVM,__bundle section.
            embedding = if darwin
              then concatMap (\argument -> ["-Xlinker",argument]) ["-sectcreate","__LLVM","__bundle",final]
              else ["-fembed-bitcode"]
            deferredArguments = nativeDeferredLinkArguments target deferredExternals
            exclusions = maybe [] id deferredArguments
            -- PE components stay raw LLVM; record no container argv for the
            -- omitted machine-code recipe, without an undefined allowance.
            arguments = case deferredArguments of
              Nothing -> []
              Just _ -> ["--target=" ++ target,"-shared","-fPIC",final] ++ embedding ++
                linkArguments ++ resolution ++ exclusions ++ ["-o",container]
        unless (null arguments) $ do
          _ <- command directory clang (sdkFlags ++ arguments)
          pure ()
        -- A container's machine code is not executed by Sulong. Materialize
        -- native dependencies separately, rooting archive extraction with the
        -- actual unresolved symbols. Never include the component here: its
        -- globals and constructors must exist only in the LLVM instance.
        -- Installed components may retain unused calls without native providers.
        -- Root actual archive definitions on every target; missing installed
        -- calls retain the component's lazy policy without loading a native RTS.
        rooted <- if member record "installed" == Just (Bool True)
          then do
            definitions <- concat <$> forM (nub (filter ((== ".a") . takeExtension) linkArguments)) (\archive ->
              case lookup archive nativeArchives of
                -- A fallback mixed archive contributes only its selected C
                -- function roots, never additional native Haskell data labels.
                Just roots | archive `notElem` dataLibraries -> pure (map fst roots)
                _ -> do
                  output <- command directory nm ["--defined-only","--extern-only","--format=posix",archive]
                  pure [nativeIrSymbol target name | line <- lines output, name:_ <- [words line]])
            pure (filter (`elem` definitions) nativeExternals)
          else pure nativeExternals
        let dependency = directory </> if windows then "native/dependencies.dll"
              else if darwin then "native/dependencies.dylib" else "native/dependencies.so"
            nativeRoots = nativeRootArguments target rooted
        (dependencyCompiler,dependencyArguments,runtimeInputs) <- if windows then do
          -- PE cannot defer the real C objects' RTS references. Let the producing
          -- GHC resolve its registered native closure, before the MinGW CRT;
          -- appending a static RTS after CRT extraction duplicates _fpreset.
          -- The DLL namespace contains only the existing package roots. All
          -- supporting native RTS/Haskell state remains private to this DLL;
          -- Core-owned operations and deferred callbacks retain their managed
          -- dispatch, rather than acquiring a second guest runtime.
          (runtimeUnit,inputs) <- nativeWindowsRtsInputs packageTool libdir
          let exports = directory </> "native/dependency-exports.def"
              initializer = directory </> "native/clock.c"
              clockSource = unlines
                ["/* Initialize only the original native C clock, never a guest RTS. */",
                 "extern void initializeTimer(void);",
                 "static void __attribute__((constructor)) thc_package_native_clock(void) { initializeTimer(); }"]
              privateArguments = ["-shared","-no-hs-main","-hide-all-packages","-no-user-package-db",
                "-package-id",runtimeUnit,initializer,"-o",dependency] ++
                ["-optl" ++ option | option <- nativeRoots ++ linkArguments ++
                  ["-Wl,--exclude-all-symbols",exports]]
          check (all identifier rooted) "Windows native dependency root is not a C symbol"
          writeFile exports (unlines ("EXPORTS":rooted))
          -- GetTime's QPC frequency is private static storage. Its original
          -- initializer allocates nothing and needs no finalizer. hs_init would
          -- instead create a second scheduler/heap and must never run here.
          writeFile initializer clockSource
          pure (compiler,privateArguments,inputs ++ [object ["path" .= initializer,
            "sha256" .= sha (T.encodeUtf8 (T.pack clockSource)),"source" .= clockSource]])
        else pure (clang,sdkFlags ++ ["--target=" ++ target,"-shared","-fPIC"] ++ nativeRoots ++ linkArguments ++
          resolution ++ exclusions ++ ["-o",dependency],[])
        _ <- command directory dependencyCompiler dependencyArguments
        -- Native archives can themselves carry compiler-embedded LLVM. Keep
        -- their companion native-only, and the ELF component's LLVM section
        -- exactly final.bc rather than concatenated archive-member payloads.
        objcopy <- tool "THC_LLVM_OBJCOPY" (takeDirectory clang </> "llvm-objcopy")
        let stripArguments = map ("--remove-section=" ++)
              (if darwin then ["__LLVM,__bundle","__LLVM,__bitcode","__LLVM,__cmdline"] else [".llvmbc",".llvmcmd"]) ++ [dependency]
            componentArguments = ["--update-section=.llvmbc=" ++ final,artifact]
        _ <- command directory objcopy stripArguments
        unless (windows || darwin || not (null deferredExternals)) $ do
          _ <- command directory objcopy componentArguments
          pure ()
        dependencyBytes <- BS.readFile dependency
        objcopyHash <- sha <$> BS.readFile objcopy
        compilerHash <- sha <$> BS.readFile dependencyCompiler
        pure (artifact,format,[object ["provider" .= ("package-declared-native-libraries-v1"::String),
          "symbols" .= nativeExternals,"compiler" .= dependencyCompiler,"compilerSha256" .= compilerHash,
          "arguments" .= arguments,
          "dependencyArguments" .= dependencyArguments,
          "objcopy" .= objcopy,"objcopySha256" .= objcopyHash,
          "objcopyArguments" .= (stripArguments : [componentArguments | not windows && not darwin && null deferredExternals])]],
          Just (object ["sha256" .= sha dependencyBytes,"hex" .= hex dependencyBytes]),runtimeInputs)
    bytes <- BS.readFile artifact
    component <- get record "componentSha256" :: IO String
    providerInputs <- mapM (\value -> get value "inputs") providers
    let inputs = sourceInputs ++ providerInputs
    let proof = object $ ["schema" .= (if null finalizers then 1 else 2::Int),"format" .= (format::String),
          "profile" .= ("thc-package-c-ffi-v1"::String),"unit" .= unit,"target" .= target,
          "componentSha256" .= component,"bitcodeSha256" .= sha bytes,"bitcodeHex" .= hex bytes,"abi" .= abi,
          "exports" .= public,"dependencies" .= dependencies,
          "buildInputs" .= object ["translationUnits" .= inputs,"providers" .= providers,
            "nativeProduct" .= (nativeProductProof <$> ownedProduct),
            "dependencies" .= [object ["declaredPath" .= path,"unit" .= member peer "unit",
              "componentSha256" .= member peer "componentSha256","bitcodeSha256" .= member peer "bitcodeSha256"] |
              (path,peer) <- dependencyPaths],
            "nativeLibraries" .= libraries,"unresolved" .= externals,"argumentBridges" .= bridgeInputs]] ++
          ["finalizers" .= finalizers | not (null finalizers)] ++
          ["dataSymbols" .= dataSymbols | not (null dataSymbols)] ++
          maybe [] (\library -> ["nativeLibrary" .= library]) nativeLibrary
    -- Native archives are linker inputs, not C translation units. Retain their
    -- hashes in the installed bundle's build identity and cache observations.
    archiveInputs <- maybe (pure []) (either fail pure . parseValue) (member identity "dataLibraries") :: IO [Value]
    -- Durable registered archives survive temporary acquisition cleanup; their
    -- observations invalidate cached Core if the private support bytes change.
    let archiveFiles = archiveInputs ++ nativeArchiveInputs ++ runtimeInputs
    writeJson (directory </> "native/inputs.json") (object
      ["sources" .= (inputs ++ [object ["files" .= archiveFiles] |
        not (null archiveFiles)]),"unresolved" .= externals])
    pure proof
   dependencyClosure :: Value -> IO [Value]
   dependencyClosure value = do
    nested <- get value "dependencies" :: IO [Value]
    rest <- concat <$> mapM dependencyClosure nested
    pure (value:rest)
   unlinked archived = do
    forM_ archived $ \((_,path),_,value) -> finalizeModuleMetadata path value
    pure (modules,Nothing)
   attach archived record proof = do
    abi <- get proof "abi" :: IO [Value]
    forM archived $ \((name,bytes'),_,value) -> do
      case value of
        Object fields -> do
          let prior = member value "packageNativeArchive"
              unclassified = prior >>= (`member` "unclassifiedReason")
              ownsCalls = any (owned unit) (calls value) || hasFunctionAddress value || not (null (addressLabels value))
          let next = if null abi || not ownsCalls || maybe False (/= Null) unclassified then value
                else Object (KM.insert "packageNativeLink" proof
                  (if member record "installed" == Just (Bool True) then KM.delete "foreignLink" fields else fields))
          finalizeModuleMetadata bytes' next
          pure (name, bytes')
        _ -> fail "package native Core module is not an object"

compileC :: FilePath -> FilePath -> [String] -> FilePath -> Maybe String -> IO (FilePath,String,Value)
compileC compiler root original directory generated = do
  clang <- tool "THC_CLANG" "clang"
  sdkFlags <- nativeCompilerFlags
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
  replayed <- either fail pure (replayDependencyArguments option arguments)
  _ <- command root compiler (replayed ++ map (option ++) sdkFlags ++ [compilerFlag,clang,"-fPIC","-o",bitcode] ++
    map (option ++) ["-emit-llvm","-O1","-MD","-MF",dependency,
                    "-MT","thc_scalar_input","-Werror=date-time"])
  dependencies <- readDependencies dependency
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

-- Dependency filenames/targets belong to the replay output, like -o above.
-- Retain the successful original argv in the receipt, including its spelling.
replayDependencyArguments :: String -> [String] -> Either String [String]
replayDependencyArguments phase = go
  where
    go [] = Right []
    go (flag:value:rest) | flag == phase = option [flag,value] value rest
    go (flag:rest) | phase `isPrefixOf` flag && length flag > length phase =
      option [flag] (drop (length phase) flag) rest
    go (flag:rest) = (flag:) <$> go rest
    option original value rest
      | value `elem` ["-MD","-MMD"] = go rest
      | value `elem` ["-MF","-MT","-MQ"] = operand rest >>= go
      | any (\prefix -> prefix `isPrefixOf` value && length value > length prefix) ["-MF","-MT","-MQ"] = go rest
      | otherwise = (original ++) <$> go rest
    operand (flag:_:rest) | flag == phase = Right rest
    operand (flag:rest) | phase `isPrefixOf` flag && length flag > length phase = Right rest
    operand _ = Left "package native dependency option lacks its compiler operand"

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
  inherited <- nativeCompilerEnvironment
  let environment = filter ((/= "GHC_ENVIRONMENT") . fst) inherited
  (status,output,diagnostic) <- readCreateProcessWithExitCode
    (proc program arguments) {cwd=Just directory,env=Just environment} ""
  check (status == ExitSuccess) ("package native command failed: " ++ program ++ " " ++ show arguments ++ "\n" ++ take 8192 (output ++ diagnostic))
  pure output
-- | Resolve an acquisition tool. Objcopy shares its adjacent-clang/PATH
-- selection with cache identity; explicit overrides never silently fall back.
tool :: String -> String -> IO FilePath
tool "THC_LLVM_OBJCOPY" fallback = do
  (selected, found) <- nativeObjcopySelection fallback
  maybe (fail ("missing native tool " ++ selected)) pure found
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

-- | Callable unresolved symbols in verified, disassembled LLVM. Native symbol
-- tables alone cannot distinguish function declarations from data references
-- whose providers happen to live in an executable section. Only decode the
-- declaration's global name; the LLVM verifier owns its signature grammar.
nativeFunctionExternals :: String -> [String] -> String -> [String]
nativeFunctionExternals target externals source = filter (`elem` declarations) externals
  where
    declarations = [normalize name | line <- lines source, "declare " `isPrefixOf` line,
      Just (name,suffix) <- [functionName line],
      "(" `isPrefixOf` dropWhile isSpace suffix]
    normalize ('\SOH':name) = nativeIrSymbol target name
    normalize name = name
    functionName ('@':rest) = globalName rest
    functionName ('"':rest) = quotedName [] rest >>= functionName . snd
    functionName (_:rest) = functionName rest
    functionName [] = Nothing
    globalName ('"':rest) = quotedName [] rest
    globalName rest = case span (\c -> isAlphaNum c || c `elem` ("$._-" :: String)) rest of
      ([],_) -> Nothing
      value -> Just value
    quotedName chunks ('"':rest) = case T.decodeUtf8' (BS.concat (reverse chunks)) of
      Right name -> Just (T.unpack name,rest)
      Left _ -> Nothing
    quotedName chunks ('\\':a:b:rest) | isHexDigit a && isHexDigit b =
      quotedName (BS.singleton (fromIntegral (16 * digitToInt a + digitToInt b)) : chunks) rest
    quotedName _ ('\\':_) = Nothing
    quotedName chunks (c:rest) = quotedName (T.encodeUtf8 (T.singleton c) : chunks) rest
    quotedName _ [] = Nothing

-- | LLVM nm reports target linker spellings even for bitcode. Keep IR names
-- internally; Darwin adds exactly one underscore, including to C names that
-- already start with underscores. Linker arguments add that prefix back.
nativeIrSymbol :: String -> String -> String
nativeIrSymbol target ('_':name)
  | "-darwin" `isInfixOf` target || "-apple-macosx" `isInfixOf` target = name
nativeIrSymbol _ name = name
