-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE DeriveGeneric, OverloadedStrings #-}
-- Receipts of actual successful Cabal compiler calls, never setup-config data.
module THC.Driver.NativeRecipe
  ( NativeRecipe(..), captureNativeRecipe, readNativeRecipe, recipePath
  , componentRoots, componentNativeObjects, componentNativeDeclarations, componentRuntimeShim
  , ensureNativeRecipes, cRecipeOptions ) where

import Control.Monad (filterM, forM, unless, when)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (FromJSON, ToJSON, Value(..), eitherDecodeStrict', encode, fromJSON, Result(..))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.List (isPrefixOf, nub, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Text.Encoding as Text
import qualified Data.Text as Text
import Distribution.Compiler (unknownCompilerInfo, AbiTag(NoAbiTag))
import Distribution.PackageDescription (PackageDescription, BuildInfo, customFieldsBI,
  buildType, BuildType(Simple), cSources, cxxSources, asmSources, cmmSources, jsSources,
  genPackageFlags, flagName, mkFlagAssignment, mkFlagName, unFlagName)
import Distribution.PackageDescription.Parsec (parseGenericPackageDescriptionMaybe)
import Distribution.PackageDescription.Configuration (finalizePD, flattenPackageDescription)
import Distribution.Parsec (eitherParsec)
import Distribution.Simple.LocalBuildInfo (lookupComponent, componentBuildInfo)
import Distribution.Types.ComponentName (ComponentName(CExeName, CLibName))
import Distribution.Types.ComponentRequestedSpec (ComponentRequestedSpec(OneComponentRequestedSpec))
import Distribution.Types.DependencySatisfaction (DependencySatisfaction(Satisfied))
import Distribution.Types.LibraryName (LibraryName(LMainLibName, LSubLibName))
import Distribution.Types.UnqualComponentName (unUnqualComponentName)
import Distribution.Utils.Path (getSymbolicPath)
import GHC.Generics (Generic)
import Numeric (showHex)
import System.Directory
import System.FilePath
import System.IO (hClose, openTempFile)
import System.IO.Error (tryIOError)

data NativeRecipe = NativeRecipe
  { recipeSchema :: Int, recipeCompiler :: FilePath, recipeDirectory :: FilePath
  , recipeArguments :: [String], recipeSource :: FilePath
  , recipeObject :: FilePath, recipeObjectHash :: String
  } deriving (Eq, Show, Generic)
instance FromJSON NativeRecipe
instance ToJSON NativeRecipe

recipePath :: FilePath -> FilePath -> FilePath
recipePath directory path = directory </> digest (Text.encodeUtf8 (Text.pack path)) <.> "json"

digest :: BS.ByteString -> String
digest = concatMap (\x -> let s = showHex x "" in if length s == 1 then '0':s else s) . BS.unpack . SHA.hash

-- Expand response files before the native call; the caller supplies GHC's own
-- response parser. Output filenames here follow GHC's -odir/-osuf convention.
captureNativeRecipe :: FilePath -> FilePath -> [String] -> IO ()
captureNativeRecipe directory compiler arguments = when ("-c" `elem` arguments) $
  case [x | x <- arguments, takeExtension x `elem` [".c", ".cc", ".cpp", ".cxx", ".S", ".s", ".cmm", ".js"]] of
    [source] -> do
      root <- getCurrentDirectory >>= canonicalizePath
      sourcePath <- canonicalizePath source
      compilerPath <- canonicalizePath compiler
      let output = case after "-o" arguments of
            Just path -> path
            Nothing -> maybe "" id (after "-odir" arguments) </>
              replaceExtension source (maybe "o" id (after "-osuf" arguments))
      outputPath <- canonicalizePath output
      exists <- doesFileExist outputPath
      when exists $ do
        hash <- digest <$> BS.readFile outputPath
        createDirectoryIfMissing True directory
        let destination = recipePath directory outputPath
        (temporary, handle) <- openTempFile directory "receipt-"
        BL.hPut handle (encode (NativeRecipe 1 compilerPath root arguments sourcePath outputPath hash))
        hClose handle
        renameFile temporary destination
    _ -> pure ()

readNativeRecipe :: FilePath -> FilePath -> FilePath -> IO (Maybe NativeRecipe)
readNativeRecipe directory compiler output = do
  result <- tryIOError $ do
    bytes <- BS.readFile (recipePath directory output)
    recipe <- either fail pure (eitherDecodeStrict' bytes)
    hash <- digest <$> BS.readFile output
    pure $ if recipeSchema recipe == 1 && recipeCompiler recipe == compiler &&
      recipeObject recipe == output && recipeObjectHash recipe == hash then Just recipe else Nothing
  pure (either (const Nothing) id result)

componentRoots :: FilePath -> Value -> IO [FilePath]
componentRoots dist component = do
  arguments <- field component "compiler-args"
  roots <- mapM canonicalizePath (nub [path | (flag,path) <- zip arguments (drop 1 arguments),
    flag `elem` ["-odir", "-hidir", "-hiedir", "-stubdir", "-outputdir"]])
  unless (not (null roots) && all (within dist) roots) (fail "Cabal build-info has no owned component output roots")
  pure roots

-- A successful Cabal command is insufficient: it may have reused an archive
-- after an intermediate object or receipt disappeared. Force the caller's
-- targeted ordinary rebuild and require the observed recipe afterwards.
ensureNativeRecipes :: FilePath -> FilePath -> [FilePath] -> FilePath -> Value -> IO () -> IO ()
ensureNativeRecipes native dist roots compiler component rebuild = do
  objects <- componentNativeObjects native dist roots component
  declarations <- componentNativeDeclarations component
  runtimeShim <- componentRuntimeShim component
  -- The active declarations must satisfy the bounded profile before consulting
  -- warm objects. One surviving receipt cannot conceal a second missing TU.
  let relativeC source = takeExtension source == ".c" && not (isAbsolute source) &&
        ".." `notElem` splitDirectories source
  unless (if runtimeShim then not (null declarations) && all relativeC declarations else case declarations of
    [] -> True
    [source] -> relativeC source
    _ -> False)
    (fail "native scalar profile requires exactly one active relative C source; runtime shims require only relative C sources")
  let receipts = native </> "cache/thc/native-recipes-v1"
  missing <- filterM (\path -> maybe True (const False) <$> readNativeRecipe receipts compiler path) objects
  unless (null missing && (null declarations || any ((== ".o") . takeExtension) objects)) $ do
    mapM_ removeFile objects
    rebuild
    rebuilt <- componentNativeObjects native dist roots component
    unless (null declarations || not (null rebuilt))
      (fail "active native declarations remain unobserved after Cabal rebuild")
    unless (all (`elem` rebuilt) missing) (fail "Cabal did not rebuild missing native recipe outputs")
    mapM_ (\path -> do
      receipt <- readNativeRecipe receipts compiler path
      unless (maybe False (const True) receipt) (fail ("Cabal native output has no compiler receipt: " ++ path))) rebuilt

-- Resolve conditions using Cabal's actual plan, not the union of inactive
-- branches (e.g. ad's optional -ffi backend). This is only the declaration
-- guard: native membership and compiler arguments still require receipts.
-- Callers without a resolved plan retain the conservative union behavior.
componentNativeDeclarations :: Value -> IO [FilePath]
componentNativeDeclarations component = do
  (package, info) <- configuredBuildInfo component
  let sources = map getSymbolicPath (cSources info) ++ map getSymbolicPath (cxxSources info) ++
        map getSymbolicPath (asmSources info) ++ map getSymbolicPath (cmmSources info) ++ map getSymbolicPath (jsSources info)
  unless (null sources || buildType package == Simple) (fail "native receipts require a Simple package")
  pure (sort (nub sources))

-- Explicit producer opt-in, not a package-name or module-name whitelist. The
-- later retained-interface check still rejects every non-runtime foreign call.
componentRuntimeShim :: Value -> IO Bool
componentRuntimeShim component = do
  (package, info) <- configuredBuildInfo component
  case [value | (name, value) <- customFieldsBI info, name == "x-thc-runtime-shim"] of
    [] -> pure False
    [value] | words value == ["v1"] -> do
      kind <- field component "type" :: IO String
      unless (kind == "lib" && buildType package == Simple)
        (fail "runtime shim profile requires a Simple library component")
      pure True
    _ -> fail "unsupported or ambiguous x-thc-runtime-shim profile"

configuredBuildInfo :: Value -> IO (PackageDescription, BuildInfo)
configuredBuildInfo component = do
  root <- field component "src-dir"
  cabalFile <- field component "cabal-file"
  name <- field component "name"
  bytes <- BS.readFile (root </> cabalFile)
  generic <- maybe (fail "cannot parse public Cabal native declaration inventory") pure
    (parseGenericPackageDescriptionMaybe bytes)
  selected <- either fail pure (eitherParsec name)
  package <- case component of
    Object fields | Just configuration <- KM.lookup "thc-cabal-configuration" fields -> do
      flags <- field configuration "flags" :: IO (Map.Map String Bool)
      compiler <- either fail pure . eitherParsec =<< field configuration "compiler"
      platform <- either fail pure . eitherParsec =<< field configuration "platform"
      unless (Map.keys flags == sort (map (unFlagName . flagName) (genPackageFlags generic)))
        (fail "Cabal plan must provide every declared package flag exactly once")
      let assignment = mkFlagAssignment [(mkFlagName flagText, enabled) | (flagText, enabled) <- Map.toList flags]
      -- Dependencies have already been solved by Cabal. Fixed flags prevent
      -- finalizePD from exploring a different configuration here.
      case finalizePD assignment (OneComponentRequestedSpec selected) (const Satisfied)
             platform (unknownCompilerInfo compiler NoAbiTag) [] generic of
        Left missing -> fail ("cannot resolve configured Cabal component: " ++ show missing)
        Right (resolved, _) -> pure resolved
    _ -> pure (flattenPackageDescription generic)
  configured <- maybe (fail "Cabal component absent from public package declaration") pure (lookupComponent package selected)
  pure (package, componentBuildInfo configured)

-- Assign nested build roots to their most-specific component. A library's
-- output directory can otherwise accidentally include a sibling executable.
componentNativeObjects :: FilePath -> FilePath -> [FilePath] -> Value -> IO [FilePath]
componentNativeObjects native dist allRoots component = do
  roots <- componentRoots dist component
  modules <- field component "modules"
  kind <- field component "type" :: IO String
  -- Cabal 3.16 build-info reports the base build directory, but its GHC
  -- builder nests executable artifacts in <name>/<name>-tmp and named-library
  -- artifacts in <name>. Derive only those exact parsed component layouts;
  -- the paired-interface, native-receipt and component-ownership guards below
  -- still apply to every object.
  suffixes <- case kind of
    "exe" -> do
      name <- field component "name"
      case eitherParsec name of
        Right (CExeName value) -> let executableName = unUnqualComponentName value
          in pure [executableName </> (executableName ++ "-tmp")]
        _ -> fail "Cabal executable build-info has an invalid component name"
    "lib" -> do
      name <- field component "name"
      case eitherParsec name of
        Right (CLibName (LSubLibName value)) -> pure [unUnqualComponentName value]
        Right (CLibName LMainLibName) -> pure []
        _ -> fail "Cabal library build-info has an invalid component name"
    _ -> pure []
  arguments <- field component "compiler-args"
  artifacts <- mapM canonicalizePath
    [directory </> suffix | suffix <- suffixes,
      (flag,directory) <- zip arguments (drop 1 arguments), flag `elem` ["-odir", "-outputdir"]]
  let haskellRoots = nub (roots ++ artifacts)
  let names = nub (modules ++ ["Main" | kind == "exe"])
      expected = [joinPath (splitModule name) | name <- names]
      owns path = any (`within` path) roots &&
        not (any (\other -> other `notElem` roots && within other path &&
          any (\own -> own /= other && within own other) roots) allRoots)
  paths <- sort . nub . filter owns . concat <$> mapM files roots
  filterM (\path -> do
    let extension = takeExtension path
        object = extension `elem` [".o", ".dyn_o", ".p_o", ".p_dyn_o"]
        iface = replaceExtension path (drop 1 extension ++ "-unused")
        hi = case extension of ".o" -> replaceExtension path "hi"; ".dyn_o" -> replaceExtension path "dyn_hi"; _ -> iface
        moduleObject = any (\root -> dropExtension (makeRelative root path) `elem` expected) haskellRoots
    receiptExists <- doesFileExist (recipePath (native </> "cache/thc/native-recipes-v1") path)
    unless (not (object && moduleObject && receiptExists))
      (fail ("Cabal Haskell/native object collision: " ++ path))
    hasInterface <- doesFileExist hi
    pure (object && not (moduleObject && hasInterface))) paths
  where
    splitModule text = case break (== '.') text of (name,[]) -> [name]; (name,_:rest) -> name:splitModule rest
    files root = do
      exists <- doesDirectoryExist root
      entries <- if exists then listDirectory root else pure []
      concat <$> forM entries (\name -> do
        let path = root </> name
        isDir <- doesDirectoryExist path
        if isDir then files path else (:[]) <$> canonicalizePath path)

within :: FilePath -> FilePath -> Bool
within root path = isAbsolute path && not (isAbsolute relative) && ".." `notElem` splitDirectories relative
  where relative = makeRelative root path

field :: FromJSON a => Value -> Text.Text -> IO a
field value key = case fromJSON value :: Result (KM.KeyMap Value) of
  Success fields -> case KM.lookup (fromString key) fields of
    Just item -> case fromJSON item of Success result -> pure result; Error err -> fail err
    Nothing -> fail ("missing build-info field " ++ Text.unpack key)
  Error err -> fail err
  where fromString = Key.fromText

-- Deliberately closed GHC C recipe grammar. Return actual forwarded -optc
-- options for the native roundtrip, not reconstructed Cabal defaults.
cRecipeOptions :: [String] -> Either String (FilePath, FilePath, [String])
cRecipeOptions arguments = do
  (sources,compilers,options) <- walk arguments ([],[],[])
  case (sources,compilers) of
    ([source],compiler:_) | "-c" `elem` arguments && "-fPIC" `elem` arguments &&
        takeExtension source == ".c" && not (isAbsolute source) && ".." `notElem` splitDirectories source &&
        isAbsolute compiler -> Right (source,compiler,reverse options)
    _ -> Left "scalar cbits requires one relative C source and explicit absolute Clang in a vanilla PIC recipe"
  where
    walk [] state = Right state
    walk (flag:value:rest) (sources,compilers,options)
      | flag == "-pgmc" = walk rest (sources,value:compilers,options)
      | flag == "-optc" && allowedCc value = walk rest (sources,compilers,value:options)
      | flag `elem` ["-odir","-hidir","-stubdir","-package-db","-package-id"] = walk rest (sources,compilers,options)
    walk (flag:rest) (sources,compilers,options)
      | flag `elem` ["-c","-fPIC","-package-env=-","-fforce-recomp","-O","-O0","-O1","-O2","-hide-all-packages","-no-user-package-db","-clear-package-db","-global-package-db"] = walk rest (sources,compilers,options)
      | "-optc" `isPrefixOf` flag && allowedCc (drop 5 flag) = walk rest (sources,compilers,drop 5 flag:options)
      | "-I" `isPrefixOf` flag && length flag > 2 = walk rest (sources,compilers,options)
      | takeExtension flag == ".c" && not ("-" `isPrefixOf` flag) = walk rest (flag:sources,compilers,options)
      | otherwise = Left ("scalar cbits: unsupported native argument " ++ flag)
    allowedCc flag = flag `elem` ["-O0","-O1","-O2","-O3","-std=c11","-std=c17","-std=gnu11","-std=gnu17","-fno-strict-aliasing"] ||
      any (\prefix -> prefix `isPrefixOf` flag && length flag > length prefix) ["-D","-U","-W"]

after :: Eq a => a -> [a] -> Maybe a
after wanted values = case dropWhile (/= wanted) values of _:value:_ -> Just value; _ -> Nothing
