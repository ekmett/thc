-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- | Package-owned C/C++/CAPI acquisition. Compile the real configured source and
-- retained GHC wrappers while Cabal's headers exist; carry LLVM, not guesses
-- about native object layouts, into the immutable Core bundle.
module THC.Driver.PackageNative
  ( captureNativeObject, captureNativeComponent, capturePackageNative, finishPackageNative
  , nativeSignatures, nativeWrapperSource, nativeCompilerArguments, validateNativeIR, nativeObjectOwned
  ) where

import Control.Monad (filterM, forM, forM_, unless, when)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (FromJSON, Value(..), eitherDecodeStrict', encode, object, toJSON, (.=), fromJSON, Result(..))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Char (isAlpha, isAlphaNum)
import Data.List (groupBy, isPrefixOf, nub, sort)
import qualified Data.Text as T
import qualified Data.Text.Encoding as T
import Numeric (showHex)
import System.Directory
import System.Environment (getEnvironment, lookupEnv)
import System.Exit (ExitCode(..))
import System.FilePath
import System.Process (CreateProcess(..), proc, readCreateProcessWithExitCode)
import THC.Driver.ScalarBitcode (parseDependencies, sulongScalarTarget)

-- (original emitted symbol, convention, safety, semantic carriers, result)
type Signature = (String, String, String, [String], String)

nativeSignatures :: String -> [Value] -> Either String [Signature]
nativeSignatures unit modules = do
  imports <- concat <$> mapM moduleImports modules
  signatures <- mapM classify imports
  let ordered = sort (nub signatures)
  forM_ (groupBy (\a b -> first a == first b) ordered) $ \variants -> do
    require (length (nub (map cAbi variants)) == 1) "conflicting package native signatures"
    -- Addr# and ByteArray# have the same C pointer ABI but distinct Core
    -- carriers. Do not collapse their typed adapters or pointer handling.
    -- ByteArray# and MutableByteArray# erase to the same Core shape; without
    -- further call-site proof, choosing a read/write policy would be ambiguous.
    require (length variants == length (nub (map coreAbi variants)))
      "ambiguous package native byte-array mutability variants"
  pure ordered
  where
    first (symbol,_,_,_,_) = symbol
    cAbi (_,convention,safety,arguments,result) =
      (convention,safety,map pointerAbi arguments,result)
    pointerAbi value | value `elem` ["ByteArray#","MutableByteArray#"] = "AddrRep"
                     | otherwise = value
    coreAbi (_,convention,safety,arguments,result) =
      (convention,safety,map (\value -> if value == "MutableByteArray#" then "ByteArray#" else value) arguments,result)
    moduleImports value = do
      require (member value "unit" == Just (toJSON unit)) "package native module owner differs"
      case member value "staticForeignImports" of
        Nothing -> Right []
        Just proof -> do
          require (member proof "schema" == Just (toJSON (1::Int)) &&
            member proof "status" == Just "verified" && member proof "unit" == Just (toJSON unit))
            "package native imports lack verified typed provenance"
          expected <- field proof "expectedForeign"
          require (maybe (emptyForeign expected) (== expected) (member value "foreign"))
            "package native retained foreign product differs from typed import provenance"
          require (member proof "expectedCalls" == Just (toJSON (calls value)))
            "package native Core calls differ from typed import provenance"
          require (maybe True (== proof) (member value "staticForeignImportStubs"))
            "package native retained stub provenance differs"
          field proof "imports"
    classify entry = do
      emitted <- field entry "emitted"
      symbol <- field emitted "symbol"
      convention <- field emitted "convention"
      safety <- field emitted "safety"
      arguments <- field emitted "arguments"
      results <- field emitted "result"
      require (identifier symbol && member emitted "unit" == Just (toJSON unit) &&
        convention `elem` ["ccall", "capi"] && safety == "unsafe" &&
        not (null arguments) && last arguments == "void" && all inputCarrier (init arguments))
        "package native call requires static unsafe C/CAPI and supported scalar/byte-array inputs"
      result <- case results of
        ["void"] -> Right "void"
        ["void", value] | scalarCarrier value && value /= "AddrRep" -> Right value
        _ -> Left "package native call requires State with zero or one scalar result"
      pure (symbol,convention,safety,init arguments,result)

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

validateNativeIR :: String -> Either String ()
validateNativeIR source = require (not (any forbidden (lines source)))
  "package native C constructors/destructors are unsupported"
  where forbidden line = any (`isPrefixOf` line) ["@llvm.global_ctors =", "@llvm.global_dtors ="]

nativeWrapperSource :: [(Signature, String)] -> Either String String
nativeWrapperSource entries = fmap concat $ forM entries $ \((symbol,convention,_,arguments,result), entry) -> do
  require (identifier symbol && identifier entry && all inputCarrier arguments &&
    (result == "void" || scalarCarrier result && result /= "AddrRep"))
    "invalid native wrapper ABI"
  let types = map cType arguments
      parameters = if null types then "void" else join ", " [ty ++ " a" ++ show index | (index,ty) <- zip [0::Int ..] types]
      prototype = if null types then "void" else join ", " types
      args = join ", " ["a" ++ show index | index <- [0 .. length arguments - 1]]
  -- Retained CAPI definitions already provide their exact C prototypes (for
  -- example HsWord8*, while Core quite correctly lowers this to AddrRep).
  pure ((if convention == "capi" then "" else
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
capturePackageNative :: FilePath -> FilePath -> FilePath -> [String] -> String -> FilePath -> IO ()
capturePackageNative helper libdir compiler arguments unit directory = do
  let core = directory </> "core"
      objects = directory </> "objects"
  paths <- sort . filter ((== ".json") . takeExtension) <$> files core
  sourceValues <- mapM readJson paths
  let needed = [(path,value) | (path,value) <- zip paths sourceValues, any (owned unit) (calls value)]
  unless (null needed) $ do
    root <- getCurrentDirectory >>= canonicalizePath
    configured <- either fail pure (nativeCompilerArguments arguments)
    retained <- forM needed $ \(path,source) -> do
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
      writeJson path value
      pure value
    signatures <- either fail pure (nativeSignatures unit retained)
    unless (null signatures) $ do
      sources <- mapM stubSource retained
      perModule <- mapM (either fail pure . nativeSignatures unit . (:[])) retained
      let inputIdentity = object ["unit" .= unit,"compiler" .= compiler,"arguments" .= arguments,
            "sources" .= sources,"imports" .= map (\(a,b,c,d,e) -> toJSON (a,b,c,d,e)) signatures]
          provisional = sha (BL.toStrict (encode inputIdentity))
          makeEntries component = [(signature,"thc_native_" ++ component ++ "_" ++ show index) | (index,signature) <- zip [0::Int ..] signatures]
          nativeDirectory = directory </> "native"
          -- GHC compiles each module's CAPI stubs as its own translation unit.
          -- Preserve private helpers, macros and header include boundaries.
          -- Repeated direct ccall imports need just one component adapter.
          compileUnits component = forM
            [(index,source,entries) | (index,(source,ownedSignatures)) <- zip [0::Int ..] (zip sources perModule),
              let earlier = concat (take index perModule),
              let entries = [entry | entry@(signature,_) <- makeEntries component,
                    signature `elem` ownedSignatures && signature `notElem` earlier],
              not (null entries)] $ \(index,source,entries) -> do
                let output = nativeDirectory </> show index
                createDirectoryIfMissing True output
                wrappers <- either fail pure (nativeWrapperSource entries)
                (bitcode,target,inputs) <- compileC compiler root configured output
                  (Just ("#include <Rts.h>\n#include <HsFFI.h>\n" ++ source ++ wrappers))
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
         "componentSha256" .= component,"inputs" .= inputs,"sourceIdentity" .= inputIdentity,
         "abi" .= [object ["symbol" .= symbol,"entry" .= entry,"convention" .= convention,"safety" .= safety,
           "arguments" .= arguments',"result" .= result] |
           ((symbol,convention,safety,arguments',result),entry) <- entries]])

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
finishPackageNative pieces directory unit currentObjects modules = do
  let receipt = directory </> "native.json"
  exists <- doesFileExist receipt
  if not exists then pure modules else do
    record <- readJson receipt
    check (member record "unit" == Just (toJSON unit)) "package native receipt owner differs"
    roots <- get record "objectRoots" :: IO [FilePath]
    root <- get record "root"
    target <- get record "target"
    wrapper <- get record "bitcode"
    components <- mapM readJson =<< files (pieces </> "components")
    allRoots <- concat <$> mapM (\value -> get value "roots")
      [value | value <- components, member value "root" == Just (toJSON (root::String))]
    candidates <- filter ((== "piece.json") . takeFileName) <$> files pieces
    native <- filterM (\value -> do
      objectPath <- get value "object"
      pure (member value "root" == Just (toJSON (root::String)) &&
        maybe (nativeObjectOwned roots allRoots objectPath) (objectPath `elem`) currentObjects)) =<< mapM readJson candidates
    -- Local receipts survive runs; exact current Cabal membership excludes
    -- deleted/renamed C sources and sibling components. Private store receipts
    -- instead belong to this fresh acquisition, after unpacked objects vanish.
    case currentObjects of
      Nothing -> pure ()
      Just _ -> forM_ native $ \value -> do
        path <- get value "object"
        digest <- sha <$> BS.readFile path
        check (member value "objectSha256" == Just (toJSON digest)) "package native object receipt is stale"
    forM_ native $ \value -> check (member value "target" == Just (toJSON (target::String))) "package C object target differs"
    inputs <- mapM (\value -> get value "inputs" :: IO Value) (record:native)
    bitcodes <- mapM (\value -> get value "bitcode") native
    abi <- get record "abi" :: IO [Value]
    entries <- mapM (\value -> get value "entry") abi
    link <- tool "THC_LLVM_LINK" "llvm-link"
    opt <- tool "THC_LLVM_OPT" "opt"
    nm <- tool "THC_LLVM_NM" "llvm-nm"
    let linked = directory </> "native/linked.bc"
        linkedIR = directory </> "native/linked.ll"
        final = directory </> "native/package.bc"
    _ <- command directory link (wrapper : bitcodes ++ ["-o",linked])
    _ <- command directory opt ["-S","-passes=verify",linked,"-o",linkedIR]
    either fail pure . validateNativeIR =<< readFile linkedIR
    _ <- command directory opt ["-passes=internalize,globaldce","-internalize-public-api-list=" ++ join "," entries,linked,"-o",final]
    undefinedSymbols <- command directory nm ["--undefined-only","--format=posix",final]
    -- These are normal C memory operations supplied by Sulong/libc. This first
    -- profile does not silently acquire arbitrary extra native libraries.
    let externals = [name | line <- lines undefinedSymbols, name:_ <- [words line]]
    check (all (\name -> name `elem` ["memcpy","memmove","memset","memcmp","bcmp"] || "llvm." `isPrefixOf` name) externals)
      ("package native unresolved dependencies: " ++ show externals)
    bytes <- BS.readFile final
    component <- get record "componentSha256" :: IO String
    let proof = object ["schema" .= (1::Int),"format" .= ("llvm-bitcode"::String),
          "profile" .= ("thc-package-c-ffi-v1"::String),"unit" .= unit,"target" .= target,
          "componentSha256" .= component,"bitcodeSha256" .= sha bytes,"bitcodeHex" .= hex bytes,"abi" .= abi,
          "buildInputs" .= object ["translationUnits" .= inputs,"unresolved" .= externals]]
    writeJson (directory </> "native/inputs.json") (object ["sources" .= inputs,"unresolved" .= externals])
    forM modules $ \(name,bytes') -> do
      value <- either fail pure (eitherDecodeStrict' bytes')
      case value of
        Object fields -> pure (name, BL.toStrict (encode (Object
          (if any (owned unit) (calls value) then KM.insert "packageNativeLink" proof fields else fields))))
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
    key `notElem` ["staticForeignImports","staticForeignImportStubs","packageNativeLink"]]
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
  Just item -> case fromJSON item of Success result -> Right result; Error message -> Left message
  Nothing -> Left ("package native field missing: " ++ key)
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
