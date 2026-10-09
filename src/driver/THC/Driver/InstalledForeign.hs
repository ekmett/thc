-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Driver.InstalledForeign
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Cabal API and host filesystem/process services
--
-- An acquisition-only view of genuine recompiled interfaces. Native compilation
-- always retains the caller's compiler, package database and installed libraries.
module THC.Driver.InstalledForeign
  ( ForeignCompiler(..), prepareForeignInterfaces, missingForeignProof, createView, viewContext, configuredView, configuredSourceView
  , observeProbeInterfaces, retainedUsageFiles, verifyUsageFiles, matchUsageFiles, validateRegisteredLibrary ) where

import Control.Monad (filterM, forM, forM_, unless)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (Value(..), FromJSON, Result(..), fromJSON, eitherDecodeStrict', encode, object, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Char (isAlpha, isHexDigit, isSpace)
import Data.List (isPrefixOf, nub, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.Text.Encoding.Error (lenientDecode)
import Distribution.Compiler (CompilerFlavor(GHC))
import Distribution.InstalledPackageInfo (parseInstalledPackageInfo, showInstalledPackageInfo)
import qualified Distribution.Types.InstalledPackageInfo as Package
import Distribution.PackageDescription (library, libBuildInfo, cppOptions, hcOptions,
  defaultLanguage, defaultExtensions, includeDirs, hsSourceDirs)
import Distribution.Pretty (prettyShow)
import Distribution.Simple.Configure (getPersistBuildConfig)
import Distribution.Simple.GHC (componentGhcOptions)
import Distribution.Simple.Program.GHC (renderGhcOptions)
import qualified Distribution.Simple.Compiler as Compiler
import Distribution.Simple.LocalBuildInfo (localPkgDescr, compiler, hostPlatform, buildDir,
  allComponentsInBuildOrder, componentPackageDeps, componentUnitId)
import Distribution.Utils.Path (getSymbolicPath, makeSymbolicPath)
import Distribution.Verbosity (normal)
import GHC.Fingerprint (getFileHash)
import GHC.Clock (getMonotonicTimeNSec)
import Numeric (showHex)
import System.Directory
import System.Environment (getEnvironment)
import System.Exit (ExitCode(..))
import System.FilePath
import System.IO (hClose, openTempFile)
import System.IO.Error (tryIOError)
import qualified System.Info as Host
import THC.Driver.Lock (withLock)
import System.Process (proc, CreateProcess(..), readCreateProcessWithExitCode)
import Text.Read (readEither)
import THC.Driver.Installed
import THC.Driver.NativeDependencies (configuredSourceBuild)
import THC.Compact.Module (readModuleMetadata)

-- | The published plugin library and the actual Cabal registration are both
-- checked: -plugin-package-id loads the latter, not an arbitrary copied library.
data ForeignCompiler = ForeignCompiler
  { foreignGhc :: FilePath, foreignPluginDb :: FilePath, foreignPluginUnit :: String
  , foreignPluginLibrary :: FilePath, foreignRegisteredLibrary :: FilePath
  , foreignDriverHash :: String }

boundModule, posixModule, directoryModule :: String
boundModule = "GHC.Internal.Conc.Bound"
posixModule = "GHC.Internal.System.Posix.Internals"
directoryModule = "System.Directory.Internal.Posix"

unixModules :: [String]
unixModules = ["System.Posix.Files.PosixString", "System.Posix.Process.Internals", "System.Posix.Signals",
  "System.Posix.Directory.PosixPath", "System.Posix.Env.PosixString", "System.Posix.IO.Common"]

nominalModules :: [String]
nominalModules = ["GHC.Internal.TopHandler", "GHC.Internal.Conc.Sync"]

-- | Determine whether a configured producer module lacks retained provenance.
-- Presence is not permission to replace bad evidence. The helper has already
-- checked actual annotations against the retained Core/foreign products.
missingForeignProof :: String -> Value -> Either String Bool
missingForeignProof name core
  | name == boundModule = case (member "staticForeignExports" core, member "staticForeignExportRegistration" core) of
      (Nothing, Nothing) -> Right True
      (Just _, Just proof) -> classified proof
      _ -> Left "incomplete static foreign-export evidence"
  | name `elem` (posixModule : directoryModule : unixModules) =
      maybe (Right True) classified (member "staticForeignImportStubs" core)
  | name `elem` nominalModules = case (member "staticForeignImports" core, member "staticForeignImportStubs" core) of
      (Nothing, Nothing) -> Right True
      (Just proof, _) -> classified proof
      _ -> Left "incomplete static foreign-import evidence"
  | otherwise = Left "module is outside the installed foreign producer profile"
  where
    classified proof
      | member "status" proof == Just (String "verified") = Right False
      | otherwise = Left (name ++ ": present foreign provenance is not verified; refusing regeneration" ++
          case member "reason" proof of Just (String reason) -> ": " ++ Text.unpack reason; _ -> "")

-- | Acquire configured-source interfaces for the supported foreign profiles,
-- preserving original unit identities and validating cached source/tool inputs.
-- The returned view is for Core acquisition, not a replacement native compiler.
prepareForeignInterfaces :: ForeignCompiler -> FilePath -> FilePath -> InstalledContext ->
                            [InstalledUnit] -> IO InstalledContext
prepareForeignInterfaces producer cache source context registrations = do
  root <- canonicalizePath source
  let selected = context { installedSource = Just root }
  if Host.os == "mingw32" then prepareProfile nominalModules selected else do
    base <- prepareProfile [boundModule, posixModule] selected
    unix <- prepareProfile unixModules base
    prepareProfile [directoryModule] unix
  where
    prepareProfile names selected = do
      let candidates = [u | u <- registrations, all (`elem` map fst (installedInterfaces u)) names]
      case candidates of
        [] -> pure selected
        [unit] -> do
          owned <- if Host.os == "mingw32" then do
            registered <- parseRegistration (registration unit)
            configuration <- configuredSourceBuild source registered
            owner <- case configuration of
              Just (path, False) -> canonicalizePath (takeDirectory path)
              _ -> fail "Windows nominal view lost its retained Cabal native owner"
            pure selected { installedSource = Just owner }
            else pure selected
          -- Regenerate the same unit in one view: a second overlay would
          -- otherwise lose earlier Bound/Posix provenance from this unit.
          let selectedNames = names ++ [name | names == [boundModule, posixModule],
                name <- nominalModules, name `elem` map fst (installedInterfaces unit)]
          original <- forM selectedNames $ \name -> do
            path <- maybe (fail "missing original foreign interface") pure (lookup name (installedInterfaces unit))
            before <- hashFile path
            core <- readCore selected unit name path
            after <- hashFile path
            check (before == after) "installed interface changed while examining foreign provenance"
            missing <- either fail pure (missingForeignProof name core)
            pure (name, core, missing, (path, before))
          let needed = [(name, core) | (name, core, True, _) <- original]
              observed = [observation | (_, _, _, observation) <- original]
          if null needed then pure owned else prepare owned unit selectedNames needed observed
        _ -> fail "multiple installed units contain the original foreign modules"
    prepare selected unit names needed originalFiles = do
      root <- canonicalizePath source
      config <- configuredRecipe producer selected unit root names
      let requestedCache = cache </> "installed-foreign/v1"
      createDirectoryIfMissing True requestedCache
      cacheRoot <- canonicalizePath requestedCache
      withLock (cacheRoot </> ".lock") $ do
        before <- inputs selected config unit
        currentOriginals <- fileInventory (map fst originalFiles)
        check (currentOriginals == originalFiles) "installed foreign proof changed before regeneration"
        let key = sha (BL.toStrict (encode before))
            index = cacheRoot </> key <.> "json"
        hit <- tryIOError $ do
          receipt <- readJson index
          check (member "inputs" receipt == Just before) "foreign interface cache inputs differ"
          view <- field receipt "view"
          canonical <- canonicalizePath view
          check (canonical == view && takeFileName view == "view" &&
                 takeDirectory (takeDirectory view) == cacheRoot) "foreign cache view is outside its generation"
          outputFiles <- field receipt "files"
          outputLinks <- field receipt "links"
          actual <- outputInventory (takeDirectory view)
          check (actual == (outputFiles, outputLinks)) "foreign cache files or links changed"
          let viewCompiler = viewContext selected view
          validateView viewCompiler unit needed
          pure viewCompiler
        acquired <- case hit of
          Right value -> pure value
          Left _ -> freshDirectory cacheRoot $ \destination -> do
            forM_ needed $ \(name, _) -> compileOriginal producer config destination name
            view <- createView selected unit destination (map fst needed)
            let viewCompiler = viewContext selected view
            validateView viewCompiler unit needed
            after <- inputs selected config unit
            check (after == before) "GHC source/configuration/interfaces changed during annotation compilation"
            (files, links) <- outputInventory destination
            let receipt = object ["inputs" .= before, "view" .= view, "files" .= files, "links" .= links]
            atomicJson index receipt
            pure viewCompiler
        after <- inputs selected config unit
        check (after == before) "GHC source/configuration/interfaces changed during annotation acquisition"
        pure acquired
    inputs selected config unit = do
      _ <- verifyUsageFiles (recipeRoot config) (recipeSourceFiles config)
      forM_ (recipeUsageFiles config) $ \(_, original) -> do
        _ <- verifyUsageFiles (recipeRoot config) original
        pure ()
      -- Re-enumerate, not only re-hash the old list: an added include or boot
      -- interface can alter compiler resolution during a running compilation.
      files <- recipeFiles config
      observed <- fileInventory files
      forM_ (installedInterfaces unit) $ \(name, installed) -> do
        forM_ (interfaceSuffixes (recipeWay config)) $ \suffix -> do
          let original = recipeBuilt config </> modulePath name <.> suffix
          check (lookup (replaceExtension installed suffix) observed == lookup original observed)
            ("selected GHC and source interfaces changed: " ++ name)
      -- Includes each complete retained payload in the selected registered
      -- dependency closure, not ABI/mtime summaries or a GHC binary hash.
      probe <- probeInstalled selected unit
      dependencyInterfaces <- observeProbeInterfaces probe
      global <- command (installedPackageTool selected)
        (packageGlobalArguments selected ++ ["dump"]) Nothing
      pure $ object ["schema" .= (1 :: Int), "recipe" .= recipeIdentity config,
        "files" .= observed, "installed" .= probe, "dependencyInterfaces" .= dependencyInterfaces,
        "globalRegistrations" .= global,
        "driver" .= foreignDriverHash producer]

-- Original configured builds only: Linux Hadrian stage1, or the retained
-- Windows Cabal producer's source/dist pair. A source tarball is insufficient.
data Recipe = Recipe { recipeRoot :: FilePath, recipeArguments :: [String]
                     , recipeFiles :: IO [FilePath], recipeIdentity :: Value
                     , recipeUsageFiles :: [(String, [(FilePath, String)])]
                     , recipeSourceFiles :: [(FilePath, String)]
                     , recipeVersionHeaders :: (FilePath, FilePath)
                     , recipeProducerLibraries :: [FilePath]
                     , recipeBuilt :: FilePath, recipeSources :: [(String, FilePath)]
                     , recipeWay :: InterfaceWay }

configuredRecipe :: ForeignCompiler -> InstalledContext -> InstalledUnit -> FilePath -> [String] -> IO Recipe
configuredRecipe producer context unit root names = do
  check (null (installedDatabases context)) "foreign regeneration requires the selected global package database"
  version <- command (foreignGhc producer) ["--numeric-version"] Nothing
  check (words version == ["9.14.1"]) "--ghc-source requires selected GHC 9.14.1"
  packageName <- case names of
    bound : posix : nominal | bound == boundModule && posix == posixModule &&
      all (`elem` nominalModules) nominal && length (nub nominal) == length nominal -> pure "ghc-internal"
    _ | names == nominalModules -> pure "ghc-internal"
    _ | names == unixModules -> pure "unix"
    [directory] | directory == directoryModule -> pure "directory"
    _ -> fail "unsupported installed foreign source profile"
  ownerRoot <- if Host.os == "mingw32" then do
    registered <- parseRegistration (registration unit)
    configuration <- configuredSourceBuild root registered
    case configuration of
      Just (path, False) -> canonicalizePath (takeDirectory path)
      _ -> fail "Windows nominal source requires its actual retained Cabal owner"
    else pure root
  let windows = Host.os == "mingw32"
      way = installedInterfaceWay context
      suffix = if way == VanillaInterfaces then "hi" else "dyn_hi"
      hscProfile = packageName /= "ghc-internal"
      includeDirectory = if packageName == "directory" then "." else "include"
      stage = root </> "_build/stage1"
      packageRoot = if windows then ownerRoot </> "source" else root </> "libraries" </> packageName
      configured = if windows then ownerRoot </> "dist" else stage </> "libraries" </> packageName
      built = configured </> "build"
      autogen = built </> "autogen"
      src = if hscProfile then packageRoot else packageRoot </> "src"
      invocation = if windows then packageRoot else root
  check (not windows || (names == nominalModules && way == VanillaInterfaces))
    "Windows foreign regeneration requires the nominal profile and vanilla interfaces"
  lbi <- getPersistBuildConfig Nothing (makeSymbolicPath configured)
  check (prettyShow (Compiler.compilerId (compiler lbi)) == "ghc-9.14.1") "GHC source configuration compiler differs"
  let platform = prettyShow (hostPlatform lbi)
  check (platform `elem` ["x86_64-linux", "aarch64-linux", "x86_64-windows"] &&
         platform == Host.arch ++ "-" ++ (if windows then "windows" else Host.os) &&
         member "platform" (installedCompiler context) == Just (String (Text.pack platform)))
    "foreign source configuration must match the native selected compiler platform"
  actualBuild <- canonicalizePath (getSymbolicPath (buildDir lbi))
  expectedBuild <- canonicalizePath built
  check (actualBuild == expectedBuild) "GHC setup-config belongs to a different build tree"
  info <- maybe (fail "GHC source configuration has no selected library") (pure . libBuildInfo)
    (library (localPkgDescr lbi))
  component <- case allComponentsInBuildOrder lbi of
    [value] -> pure value
    _ -> fail "unexpected GHC source component configuration"
  check (prettyShow (componentUnitId component) == registeredId unit &&
         sort (map (prettyShow . fst) (componentPackageDeps component)) == sort (installedDepends unit))
    "GHC source configuration unit/dependencies differ from selected installation"
  registeredUnit <- parseRegistration (registration unit)
  let expectedIncludes = [includeDirectory] ++
        (if windows then packageRoot : Package.includeDirs registeredUnit else [])
  check (map getSymbolicPath (hsSourceDirs info) == [if hscProfile then "." else "src"] &&
         map (normalise . getSymbolicPath) (includeDirs info) == map normalise expectedIncludes)
    ("unsupported GHC source/include directory configuration: " ++
      show (map getSymbolicPath (hsSourceDirs info), map getSymbolicPath (includeDirs info)))
  -- Do not execute hidden arbitrary hooks/plugins from a setup-config. This is
  -- the known library option profile; conditional CPP comes from Cabal itself.
  check (hcOptions GHC info == (if hscProfile then ["-Wall"] else
           ["-this-unit-id", "ghc-internal", "-Wcompat", "-Wnoncanonical-monad-instances"]) &&
         cppOptions info == (if hscProfile then [] else
           ["-DBIGNUM_GMP"] ++ ["-D_WIN32_WINNT=0x06010000" | windows]) &&
         fmap prettyShow (defaultLanguage info) == Just "Haskell2010" &&
         map prettyShow (defaultExtensions info) == (if hscProfile then [] else ["NoImplicitPrelude"]))
    "unsupported original library compiler option profile"
  forM_ (installedInterfaces unit) $ \(name, installed) -> do
    let original = built </> modulePath name <.> suffix
    left <- hashFile installed
    right <- hashFile original
    check (left == right) ("--ghc-source interfaces do not match selected GHC: " ++ name)
  descriptions <- command (installedPackageTool context)
    (packageGlobalArguments context ++ ["--package-db", foreignPluginDb producer, "dump"]) Nothing
  records <- mapM parseRegistration (splitRegistrations (lines descriptions))
  rts <- case [record | record <- records, prettyShow (Package.sourcePackageId record) == "rts-1.0.3"] of
    [record] -> pure record
    _ -> fail "expected exactly one selected RTS registration"
  versionCandidates <- filterM doesFileExist [directory </> "ghcversion.h" | directory <- Package.includeDirs rts]
  selectedVersion <- case versionCandidates of
    [path] -> canonicalizePath path
    _ -> fail "expected exactly one selected RTS ghcversion.h"
  originalVersion <- canonicalizePath (if windows then selectedVersion else root </> "rts/include/ghcversion.h")
  originalVersionHash <- show <$> getFileHash originalVersion
  _ <- verifyUsageFiles invocation [(selectedVersion, originalVersionHash)]
  let includeRoots = if windows then nub
        ([built, autogen, packageRoot, packageRoot </> "include", built </> "include"] ++
         Package.includeDirs registeredUnit ++ Package.includeDirs rts) else
        [root </> "rts/include", stage </> "rts/build/include", built] ++
        if includeDirectory == "." then [packageRoot] else [built </> "include", packageRoot </> "include"]
      roots = [built, autogen, src]
      macros = autogen </> "cabal_macros.h"
  originalInputs <- forM names $ \name -> do
    let original = built </> modulePath name <.> suffix
        -- Unix/directory retain the configured hsc2hs output, whose own
        -- UsageFile points back to the original .hsc. Never rerun hsc2hs with
        -- newly guessed headers and call that the installed source.
        sourceFile = (if hscProfile then built else src) </> modulePath name <.> "hs"
    description <- command (foreignGhc producer) ["--show-iface", original] Nothing
    digest <- show <$> getFileHash sourceFile
    let fingerprints = [value | line <- lines description, ["src", "hash:", value] <- [words line]]
    check (fingerprints == [digest])
      ("--ghc-source original source does not match retained self-recomp metadata: " ++ name)
    retained <- either fail pure (retainedUsageFiles description)
    verified <- verifyUsageFiles invocation retained
    required <- mapM canonicalizePath $ (if hscProfile
      then [src </> modulePath name <.> "hsc"] else
        [path | not (null retained), path <- [originalVersion, macros]]) ++
      (if name == posixModule then [built </> "include/HsBaseConfig.h", stage </> "rts/build/include/ghcplatform.h"] else [])
    check (all (`elem` map fst verified) required)
      ("original interface lacks required CPP dependency evidence: " ++ name)
    pure (name, (sourceFile, digest), verified)
  let usageFiles = [(name, files) | (name, _, files) <- originalInputs]
      sourceFiles = [file | (_, file, _) <- originalInputs]
      baseFiles = [configured </> "setup-config", packageRoot </> packageName <.> "cabal", macros,
                   installedLibdir context </> "settings",
                   foreignPluginLibrary producer, foreignRegisteredLibrary producer, installedHelper context] ++ map fst sourceFiles ++
                  (if windows then [] else [root </> "hadrian/cfg/system.config", stage </> "lib/settings"]) ++
                  [replaceExtension path extension | (_, path) <- installedInterfaces unit, extension <- interfaceSuffixes way]
      configuredArgs = if windows then
        renderGhcOptions (compiler lbi) (hostPlatform lbi)
          (componentGhcOptions normal lbi info component (makeSymbolicPath built)) ++
        ["-clear-package-db", "-package-db", installedGlobalDb context, "-package-id", registeredId unit]
        else ["-static", "-fsplit-sections",
              "-hide-all-packages", "-no-user-package-db", "-package-env", "-",
              "-this-package-name", packageName, "-i"] ++
        (if hscProfile then ["-this-unit-id", registeredId unit] else []) ++
        concatMap (\dep -> ["-package-id", dep]) (installedDepends unit) ++
        hcOptions GHC info ++ maybe [] (\lang -> ["-X" ++ prettyShow lang]) (defaultLanguage info) ++
        map (("-X" ++) . prettyShow) (defaultExtensions info) ++
        map ("-i" ++) roots ++ map ("-I" ++) includeRoots ++
        map ("-optP" ++) (cppOptions info) ++ ["-optP-include", "-optP" ++ macros]
      args = configuredArgs ++ ["-c", "-fforce-recomp", "-O2"] ++
        ["-dynamic-too" | way == DynamicInterfaces] ++ ["-no-user-package-db", "-package-env", "-",
        "-fwrite-if-simplified-core", "-dcore-lint", "-package-db", foreignPluginDb producer,
        "-plugin-package-id", foreignPluginUnit producer, "-fplugin=THC.Plugin",
        "-fplugin-opt=THC.Plugin:post-tidy", "-fplugin-opt=THC.Plugin:unit-qualified"]
  published <- hashFile (foreignPluginLibrary producer)
  registered <- hashFile (foreignRegisteredLibrary producer)
  check (published == registered) "published and registered THC plugin libraries differ"
  pluginDescription <- command (installedPackageTool context)
    (packageGlobalArguments context ++ ["--package-db", foreignPluginDb producer,
     "--ipid", "describe", foreignPluginUnit producer]) Nothing
  plugin <- parseRegistration pluginDescription
  _ <- validateRegisteredLibrary way plugin (foreignRegisteredLibrary producer)
  -- GHC records dynamically loaded plugin dependencies as UsageFiles too. Only
  -- those exact registered library paths may be additional producer inputs;
  -- accepting every .so (or ignoring every non-.h) would hide a new CPP include.
  dependencies <- registrationClosure records (foreignPluginUnit producer)
  producerLibraries <- sort . nub <$> (mapM canonicalizePath =<< filterM doesFileExist
    (concatMap (registeredLibraries way) dependencies))
  infoOutput <- command (foreignGhc producer) ["--info"] Nothing
  let allFiles = do
        includes <- concat <$> mapM (treeFiles (\p -> takeExtension p `elem` [".h", ".hpp"])) includeRoots
        interfaces <- treeFiles (\p -> takeExtension p `elem` [".hi", ".dyn_hi", ".hi-boot", ".dyn_hi-boot"]) built
        pluginFiles <- treeFiles (\p -> takeExtension p == ".conf" || takeFileName p == "package.cache")
          (foreignPluginDb producer)
        pure (sort (nub (selectedVersion : baseFiles ++ includes ++ interfaces ++ pluginFiles ++
          producerLibraries ++ concatMap (map fst . snd) usageFiles)))
  let identity = object
        ["root" .= root, "ghc" .= foreignGhc producer, "ghcInfo" .= infoOutput,
         "arguments" .= args, "pluginUnit" .= foreignPluginUnit producer, "pluginRegistration" .= pluginDescription,
         "originalUsageFiles" .= usageFiles, "originalSourceFiles" .= sourceFiles,
         "versionHeaders" .= (originalVersion, selectedVersion),
         "producerLibraries" .= producerLibraries]
  pure (Recipe invocation args allFiles identity usageFiles sourceFiles (originalVersion, selectedVersion) producerLibraries
    built [(name, path) | (name, (path, _), _) <- originalInputs] way)

interfaceSuffixes :: InterfaceWay -> [String]
interfaceSuffixes VanillaInterfaces = ["hi"]
interfaceSuffixes DynamicInterfaces = ["hi", "dyn_hi"]

registeredLibraries :: InterfaceWay -> Package.InstalledPackageInfo -> [FilePath]
registeredLibraries way info = nub
  [directory </> "lib" ++ name ++ suffix | directory <- directories, name <- Package.hsLibraries info]
  where
    directories = if way == VanillaInterfaces then Package.libraryDirsStatic info ++ Package.libraryDirs info
      else Package.libraryDynDirs info
    suffix = if way == VanillaInterfaces then ".a" else "-ghc9.14.1.so"

-- | Require the recorded library to be the unique actual registered product.
-- Canonicalize both sides: Cabal may register a checkout junction while the
-- selected archive is recorded through that junction's resolved directory.
validateRegisteredLibrary :: InterfaceWay -> Package.InstalledPackageInfo -> FilePath -> IO FilePath
validateRegisteredLibrary way registered recorded = do
  paths <- nub <$> (mapM canonicalizePath =<< filterM doesFileExist (registeredLibraries way registered))
  actual <- canonicalizePath recorded
  check (paths == [actual]) "THC plugin registration does not resolve to the recorded library for its selected way"
  pure actual

compileOriginal :: ForeignCompiler -> Recipe -> FilePath -> String -> IO ()
compileOriginal producer recipe destination name = do
  let stem = destination </> "interfaces" </> modulePath name
      scratch = destination </> "compile" </> name
      options = if name == boundModule then ["foreign-export-associations", "foreign-export-registration"]
                else ["foreign-import-provenance"]
  createDirectoryIfMissing True (takeDirectory stem)
  createDirectoryIfMissing True scratch
  -- The output directory must be the plugin's first option.
  source <- maybe (fail "missing original configured source") pure (lookup name (recipeSources recipe))
  let arguments = ["-fplugin-opt=THC.Plugin:" ++ scratch] ++ recipeArguments recipe ++
        map ("-fplugin-opt=THC.Plugin:" ++) options ++
        ["-odir", scratch, "-stubdir", scratch, "-tmpdir", scratch, "-dumpdir", scratch,
         "-hiedir", scratch, "-ohi", stem <.> "hi", "-o", stem <.> "o"] ++
        (if recipeWay recipe == DynamicInterfaces then
          ["-dynohi", stem <.> "dyn_hi", "-dyno", stem <.> "dyn_o"] else []) ++
        [source]
  let receipt = scratch </> "compilation.json"
      record exit seconds = object ["command" .= (foreignGhc producer : arguments),
        "cwd" .= recipeRoot recipe, "nativeExit" .= (exit :: Maybe Int), "seconds" .= (seconds :: Maybe Double)]
  atomicJson receipt (record Nothing Nothing)
  started <- getMonotonicTimeNSec
  _ <- command (foreignGhc producer) arguments (Just (recipeRoot recipe))
  finished <- getMonotonicTimeNSec
  -- A failed command leaves its exact arguments and an unknown exit here;
  -- the thrown diagnostic retains the actual native status. Never record 0
  -- until the owning subprocess check has succeeded.
  removeFile receipt
  atomicJson receipt (record (Just 0) (Just (fromIntegral (finished - started) / 1e9)))
  description <- command (foreignGhc producer)
    ["--show-iface", stem <.> if recipeWay recipe == VanillaInterfaces then "hi" else "dyn_hi"] Nothing
  retained <- either fail pure (retainedUsageFiles description)
  verified <- verifyUsageFiles (recipeRoot recipe) retained
  expected <- maybe (fail "missing original CPP input inventory") pure (lookup name (recipeUsageFiles recipe))
  check (matchUsageFiles (recipeVersionHeaders recipe) (recipeProducerLibraries recipe) expected verified)
    ("regenerated interface changed the original CPP dependency inventory: " ++ name)
  pure ()

createView :: InstalledContext -> InstalledUnit -> FilePath -> [String] -> IO FilePath
createView context unit destination names = do
  let view = destination </> "view"
      libdir = view </> "lib"
      db = libdir </> "package.conf.d"
      interfaces = view </> "interfaces"
  createDirectoryIfMissing True db
  entries <- listDirectory (installedLibdir context)
  forM_ entries $ \name ->
    unless (name == "package.conf.d" || Host.os == "mingw32") $ do
      let original = installedLibdir context </> name
      directory <- doesDirectoryExist original
      (if directory then createDirectoryLink else createFileLink) original (libdir </> name)
  forM_ (installedInterfaces unit) $ \(name, original) ->
    forM_ (interfaceSuffixes (installedInterfaceWay context)) $ \suffix -> do
      -- Dynamic compilation also reads vanilla dependencies; a vanilla-only
      -- view must not manufacture a dynamic interface from vanilla bytes.
      let target = interfaces </> modulePath name <.> suffix
          actual = if name `elem` names then destination </> "interfaces" </> modulePath name <.> suffix
                   else replaceExtension original suffix
      createDirectoryIfMissing True (takeDirectory target)
      (if Host.os == "mingw32" then copyFile else createFileLink) actual target
  dumped <- command (installedPackageTool context) (packageGlobalArguments context ++ ["dump"]) Nothing
  canonicalInterfaces <- canonicalizePath interfaces
  records <- mapM parseRegistration (splitRegistrations (lines dumped))
  check (length [() | record <- records, prettyShow (Package.installedUnitId record) == registeredId unit] == 1)
    "selected package database lost the original foreign unit"
  forM_ (zip [(0 :: Int)..] records) $ \(index, original) -> do
    let record = if prettyShow (Package.installedUnitId original) == registeredId unit
                 then original { Package.importDirs = [canonicalInterfaces] } else original
    writeFile (db </> show index <.> "conf") (showInstalledPackageInfo record)
  _ <- command (installedPackageTool context) ["--global-package-db", db, "--global", "recache"] Nothing
  unless (Host.os == "mingw32") $ do
    let wrapper = view </> "ghc-pkg"
    writeFile wrapper ("#!/bin/sh\nexec " ++ shellQuote (installedPackageTool context) ++
      " --global-package-db " ++ shellQuote db ++ " \"$@\"\n")
    permissions <- getPermissions wrapper
    setPermissions wrapper permissions { executable = True }
  pure view

viewContext :: InstalledContext -> FilePath -> InstalledContext
viewContext context view
  | Host.os == "mingw32" = context { installedGlobalDb = view </> "lib/package.conf.d" }
  | otherwise = context { installedLibdir = view </> "lib",
      installedGlobalDb = view </> "lib/package.conf.d",
      installedLibdirGlobalDb = view </> "lib/package.conf.d", installedPackageTool = view </> "ghc-pkg" }

-- | Select a retained complete Windows source view while preserving every
-- native registration field. Only interface locations may differ.
configuredView :: InstalledContext -> FilePath -> IO InstalledContext
configuredView context source = do
  check (Host.os == "mingw32") "configured source view selection requires native Windows"
  view <- canonicalizePath (source </> "view")
  let selected = viewContext context view
      records current = do
        dumped <- command (installedPackageTool current) (packageGlobalArguments current ++ ["dump"]) Nothing
        mapM parseRegistration (splitRegistrations (lines dumped))
      identifier = prettyShow . Package.installedUnitId
  original <- records context
  changed <- records selected
  let originals = Map.fromList [(identifier record, record) | record <- original]
  check (sort (map identifier original) == sort (map identifier changed))
    "configured source view changed the installed unit inventory"
  forM_ changed $ \record -> do
    before <- maybe (fail "configured source view introduced an installed unit") pure
      (Map.lookup (identifier record) originals)
    check (record { Package.importDirs = Package.importDirs before } == before)
      "configured source view changed native registration or ABI fields"
  pure selected

-- | Validate a retained Windows source provider against its real configured
-- unit, current compiler/settings/graph, and original source/CPP fingerprints.
-- Return the consumed observations for ordinary acquisition provenance.
configuredSourceView :: InstalledContext -> FilePath -> IO (InstalledContext, Value)
configuredSourceView context source = do
  root <- canonicalizePath source
  actualSettings <- either fail pure . readEither =<< command (installedGhc context) ["--info"] Nothing
  selected <- configuredView context root
  inputs <- readJson (root </> "inputs.json")
  check (member "compiler" inputs == Just (installedCompiler context))
    "configured source provider differs from selected compiler/ABI"
  retainedSettings <- field inputs "settings" :: IO [(String, String)]
  check (retainedSettings == actualSettings) "configured source provider changed selected GHC settings"
  registered <- field inputs "registration" >>= parseRegistration
  let identifier = prettyShow (Package.installedUnitId registered)
  unit <- discoverInstalled selected identifier
  configuration <- configuredSourceBuild root registered
  expected <- canonicalizePath (root </> "dist")
  check (configuration == Just (expected, False)) "configured source provider lost its native owner"
  lbi <- getPersistBuildConfig Nothing (makeSymbolicPath expected)
  component <- case allComponentsInBuildOrder lbi of
    [value] -> pure value
    _ -> fail "configured source provider has multiple components"
  compilerId <- field (installedCompiler context) "id" :: IO String
  platform <- field (installedCompiler context) "platform" :: IO String
  check (prettyShow (Compiler.compilerId (compiler lbi)) == compilerId &&
    prettyShow (hostPlatform lbi) == platform &&
    componentUnitId component == Package.installedUnitId registered &&
    sort (map fst (componentPackageDeps component)) == sort (Package.depends registered))
    "configured source provider changed compiler/unit/dependency identities"
  (owner, modules) <- either fail pure . readEither =<< readFile (root </> "complete")
  let inventory = sort (filter (/= "GHC.Internal.Prim") (map fst (installedInterfaces unit)))
  check (owner == identifier && sort modules == inventory) "configured source provider has an incomplete inventory"
  graphValue <- readJson (root </> "source-graph.json")
  graph <- case fromJSON graphValue of
    Success nodes -> pure (nodes :: [Value])
    Error problem -> fail problem
  nodes <- forM graph $ \node -> (,,) <$> field node "module" <*> field node "source" <*> field node "boot"
  check (sort [name | (name, _, False) <- nodes] == inventory)
    "configured source graph differs from installed module inventory"
  observedGraph <- command (installedHelper context)
    ["--source-graph", installedLibdir context, root </> "source-graph-request.json"] (Just (root </> "source"))
  actualGraph <- either fail pure (eitherDecodeStrict' (Text.encodeUtf8 (Text.pack observedGraph)))
  check (actualGraph == graphValue) "current helper changed the retained compilation source graph"
  observations <- forM [(name, path) | (name, path, False) <- nodes] $ \(name, path) -> do
    interface <- maybe (fail "configured source graph lacks its interface") pure (lookup name (installedInterfaces unit))
    let sourceFile = if isAbsolute path then path else root </> "source" </> path
    description <- command (installedGhc context) ["--show-iface", interface] Nothing
    digest <- show <$> getFileHash sourceFile
    check ([fingerprint | line <- lines description, ["src", "hash:", fingerprint] <- [words line]] == [digest])
      ("configured source differs from retained interface: " ++ name)
    retained <- either (fail . (("configured source " ++ name ++ ": ") ++)) pure (retainedUsageFiles description)
    cpp <- verifyUsageFiles (root </> "source") retained
    pure (sourceFile, digest, cpp)
  pure (selected { installedSource = Just root },
    object ["root" .= root, "inputs" .= inputs, "sourceObservations" .= observations])

validateView :: InstalledContext -> InstalledUnit -> [(String, Value)] -> IO ()
validateView context original needed = do
  selected <- discoverInstalled context (registeredId original)
  before <- parseRegistration (registration original)
  after <- parseRegistration (registration selected)
  check (after { Package.importDirs = Package.importDirs before } == before)
    "acquisition view changed native registration or ABI fields"
  check (map fst (installedInterfaces selected) == map fst (installedInterfaces original))
    "acquisition view changed the registered module inventory"
  forM_ needed $ \(name, previous) -> do
    path <- maybe (fail "acquisition view lost interface") pure (lookup name (installedInterfaces selected))
    core <- readCore context selected name path
    missing <- either fail pure (missingForeignProof name core)
    check (not missing && member "foreign" core == member "foreign" previous &&
           member "unit" core == member "unit" previous && member "module" core == member "module" previous)
      ("rebuilt original interface lacks matching genuine provenance: " ++ name)

readCore :: InstalledContext -> InstalledUnit -> String -> FilePath -> IO Value
readCore context unit name path = do
  (status, bytes, diagnostic) <- boundedInterfaceProcess (installedHelper context) (helperCommand context unit (name, path))
  check (status == ExitSuccess)
    ("--ghc-source requires complete installed interfaces before regeneration: " ++
      take 4096 (Text.unpack (Text.decodeUtf8With lenientDecode (bytes <> diagnostic))))
  either fail (pure . snd) (readModuleMetadata bytes)

modulePath :: String -> FilePath
modulePath = map (\c -> if c == '.' then pathSeparator else c)

member :: String -> Value -> Maybe Value
member name (Object fields) = KeyMap.lookup (Key.fromString name) fields
member _ _ = Nothing

field :: FromJSON a => Value -> String -> IO a
field value name = case member name value of
  Just item -> case fromJSON item of Success result -> pure result; Error _ -> bad
  Nothing -> bad
  where bad = fail ("invalid installed foreign cache field: " ++ name)

readJson :: FilePath -> IO Value
readJson path = either fail pure . eitherDecodeStrict' =<< BS.readFile path

check :: Bool -> String -> IO ()
check yes message = unless yes (fail message)

hashFile :: FilePath -> IO String
hashFile path = sha <$> BS.readFile path

sha :: BS.ByteString -> String
sha = concatMap (\byte -> let s = showHex byte "" in replicate (2 - length s) '0' ++ s) . BS.unpack . SHA.hash

fileInventory :: [FilePath] -> IO [(FilePath, String)]
fileInventory = mapM (\path -> (,) path <$> hashFile path)

-- GHC 9.14.1's own pretty-printer emits UsageFile as addDependentFile followed
-- by a quoted Haskell FilePath and its Fingerprint. Restrict parsing to the
-- original Self-Recomp section, never source strings in the retained Core/C.
-- An absent/stripped or differently formatted record is not sufficient evidence.
-- A non-CPP module still has a complete usages record, but no header UsageFiles.
retainedUsageFiles :: String -> Either String [(FilePath, String)]
retainedUsageFiles description = do
  section <- case dropWhile (/= "Self-Recomp") (lines description) of
    [] -> Left "original interface lacks self-recompilation input evidence"
    _:rest -> case break ("  orphan hash:" `isPrefixOf`) rest of
      (_, []) -> Left "unsupported GHC self-recompilation record format"
      (body, _) -> Right body
  let rows = [dropWhile isSpace line | line <- section,
              "addDependentFile" `isPrefixOf` dropWhile isSpace line]
  unless (any ("  usages: [" `isPrefixOf`) section)
    (Left "original interface lacks retained usage evidence")
  traverse parse rows
  where
    parse row = case quotedPath (dropWhile isSpace (drop (length ("addDependentFile" :: String)) row)) of
      [(path, rest)] ->
        let (digest, ending) = span isHexDigit (dropWhile isSpace rest)
        in if not (null path) && not (null digest) && length digest <= 32 &&
              filter (not . isSpace) ending `elem` [",", "]"]
           then Right (path, digest)
           else Left "unsupported GHC UsageFile fingerprint record"
      _ -> Left "unsupported GHC UsageFile path record"
    -- GHC pretty-prints Windows FilePaths verbatim, not as Haskell string
    -- literals: reads would interpret \t/\r or reject other backslashes.
    quotedPath value@('"':body) = case break (== '"') body of
      (path, '"':rest) | Host.os == "mingw32" -> [(path, rest)]
      (path@(drive:':':'\\':_), '"':rest) | isAlpha drive -> [(path, rest)]
      (path@('\\':'\\':_), '"':rest) -> [(path, rest)]
      _ -> reads value
    quotedPath value = reads value

-- Relative UsageFile paths are relative to the original Hadrian invocation
-- directory, which is the explicitly supplied tree root. Absolute system
-- headers remain absolute; they too must match the original GHC fingerprint.
-- The same observer rechecks the retained target-source fingerprint pairs,
-- including after the initial recipe validation and before every publication.
verifyUsageFiles :: FilePath -> [(FilePath, String)] -> IO [(FilePath, String)]
verifyUsageFiles root records = do
  verified <- forM records $ \(path, expected) -> do
    actualPath <- canonicalizePath (if isAbsolute path then path else root </> path)
    actual <- show <$> getFileHash actualPath
    check (actual == expected) ("original source/CPP input differs from retained fingerprint: " ++ actualPath)
    pure (actualPath, expected)
  pure (sort (nub verified))

-- All paths have already been resolved and fingerprints checked. GHC injects
-- its installed RTS version header rather than Hadrian's source-tree copy;
-- permit this single exact substitution only with the original fingerprint.
-- Everything else must be an original input or an actual registered plugin
-- dependency library. Missing original inputs and newly shadowing includes fail.
matchUsageFiles :: (FilePath, FilePath) -> [FilePath] -> [(FilePath, String)] -> [(FilePath, String)] -> Bool
matchUsageFiles (originalVersion, selectedVersion) libraries original generated =
  sort (nub expected) == sort (nub actual)
  where
    expected = [(if path == originalVersion then selectedVersion else path, digest) | (path, digest) <- original]
    actual = [(path, digest) | (path, digest) <- generated,
      path `notElem` libraries || path `elem` map fst expected]

registrationClosure :: [Package.InstalledPackageInfo] -> String -> IO [Package.InstalledPackageInfo]
registrationClosure records root = visit Map.empty [root]
  where
    visit found [] = pure (Map.elems found)
    visit found (identifier:rest)
      | Map.member identifier found = visit found rest
      | otherwise = case [record | record <- records, prettyShow (Package.installedUnitId record) == identifier] of
          [record] -> visit (Map.insert identifier record found) (map prettyShow (Package.depends record) ++ rest)
          _ -> fail ("missing or ambiguous producer dependency registration: " ++ identifier)

-- probeInstalled discovers the complete registered dependency closure. Its
-- native helper probes dynamic payloads, while -dynamic-too also consumes
-- vanilla dependencies. Resolve each way through the original registration:
-- a canonical .dyn_hi symlink target need not have its .hi sibling beside it.
observeProbeInterfaces :: Value -> IO [(FilePath, String)]
observeProbeInterfaces probe = do
  registrations <- field probe "registrations" :: IO [Value]
  paths <- fmap concat $ forM registrations $ \record -> do
    info <- parseRegistration =<< field record "registration"
    way <- field record "way" :: IO String
    suffixes <- case way of
      "dynamic" -> pure ["hi", "dyn_hi"]
      "vanilla" -> pure ["hi"]
      _ -> fail "invalid selected dependency interface way"
    modules <- field record "interfaces" :: IO [Value]
    fmap concat $ forM modules $ \entry -> do
      name <- field entry "module"
      expected <- field entry "path"
      forM suffixes $ \suffix -> do
        matches <- filterM doesFileExist
          [directory </> modulePath name <.> suffix | directory <- Package.importDirs info]
        path <- case matches of
          [found] -> canonicalizePath found
          _ -> fail ("expected exactly one dependency " ++ suffix ++ " interface for " ++ name)
        check (suffix /= (if way == "dynamic" then "dyn_hi" else "hi") || path == expected)
          "dependency selected interface changed after inventory probe"
        pure path
  fileInventory (sort (nub paths))

-- Source trees are supplied explicitly; enumerate the complete relevant header
-- and interface inventories, so additions/removals invalidate the recipe too.
treeFiles :: (FilePath -> Bool) -> FilePath -> IO [FilePath]
treeFiles wanted directory = do
  names <- sort <$> listDirectory directory
  concat <$> forM names (\name -> do
    let path = directory </> name
    dir <- doesDirectoryExist path
    if dir then treeFiles wanted path else pure [path | wanted path])

outputInventory :: FilePath -> IO ([(FilePath, String)], [(FilePath, FilePath)])
outputInventory directory = do
  names <- sort <$> listDirectory directory
  pairs <- forM names $ \name -> do
    let path = directory </> name
    linked <- pathIsSymbolicLink path
    if linked then do target <- getSymbolicLinkTarget path; pure ([], [(path, target)]) else do
      dir <- doesDirectoryExist path
      if dir then outputInventory path else do digest <- hashFile path; pure ([(path, digest)], [])
  pure (concatMap fst pairs, concatMap snd pairs)

parseRegistration :: String -> IO Package.InstalledPackageInfo
parseRegistration text = case parseInstalledPackageInfo (Text.encodeUtf8 (Text.pack text)) of
  Left errors -> fail ("invalid installed registration: " ++ show errors)
  Right (_, info) -> pure info

splitRegistrations :: [String] -> [String]
splitRegistrations [] = []
splitRegistrations lines' = let (record, rest) = break (== "---") lines'
  in [unlines record | any (not . null) record] ++ case rest of [] -> []; _:remaining -> splitRegistrations remaining

shellQuote :: String -> String
shellQuote value = "'" ++ concatMap (\c -> if c == '\'' then "'\\''" else [c]) value ++ "'"

command :: FilePath -> [String] -> Maybe FilePath -> IO String
command program arguments directory = do
  inherited <- getEnvironment
  let clean = filter (\(name, _) -> name `notElem`
        ["GHC_PACKAGE_PATH", "GHC_ENVIRONMENT", "CPATH", "C_INCLUDE_PATH", "CPLUS_INCLUDE_PATH", "OBJC_INCLUDE_PATH"]) inherited
  (status, output, diagnostic) <- readCreateProcessWithExitCode
    (proc program arguments) { cwd = directory, env = Just clean } ""
  check (status == ExitSuccess) ("installed foreign command failed: " ++ program ++ " (" ++ show status ++ ")\n" ++ diagnostic)
  pure output

freshDirectory :: FilePath -> (FilePath -> IO a) -> IO a
freshDirectory parent action = do
  (path, handle) <- openTempFile parent "producer-"
  hClose handle
  removeFile path
  createDirectory path
  -- A failed compiler/validation attempt must retain its actual outputs.
  -- Only a fully verified generation gets an index receipt.
  action path

atomicJson :: FilePath -> Value -> IO ()
atomicJson path value = do
  (temporary, handle) <- openTempFile (takeDirectory path) "receipt-"
  hClose handle
  BL.writeFile temporary (encode value)
  renameFile temporary path
