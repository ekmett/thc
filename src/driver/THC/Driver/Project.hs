-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Driver.Project
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Cabal API and host filesystem/process services
--
-- Plan project components and acquire reproducible, dependency-closed Core bundles.
module THC.Driver.Project
  ( runProject, acquireProject, prepareWindowsRuntime, Bundle(..), InstalledBundle(..)
  , prepareInstalledBundle, installedRecords
  , BundleReceipt(..), readGlobalBundle, readBundle, exceptionBridgeModules, projectWindowsWiredBundle
  , publishCapturedStoreUnit
  ) where

import Control.Exception (evaluate, finally, onException)
import Control.Monad (filterM, forM, forM_, unless, when)
import Data.Char (isAlphaNum, isHexDigit)
import GHC.ResponseFile (expandResponse)
import Distribution.Types.Flag (mkFlagName, unFlagName)
import qualified Distribution.InstalledPackageInfo as Package
import Distribution.Pretty (prettyShow)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (FromJSON, ToJSON, Value(..), eitherDecodeStrict', encode, object, (.=))
import qualified Data.Aeson as Aeson
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.List (isSuffixOf, nub, sort, sortOn)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Numeric (showHex)
import System.Directory (canonicalizePath, createDirectory, createDirectoryIfMissing,
                         doesDirectoryExist, doesFileExist, findExecutable, getPermissions,
                         listDirectory, makeAbsolute, removeFile, removePathForcibly,
                         renameFile, setPermissions)
import qualified System.Directory as Directory
import System.Exit (ExitCode(..))
import System.Environment (getEnvironment, getExecutablePath, lookupEnv)
import System.FilePath ((</>), (<.>), pathSeparator, isAbsolute, makeRelative, normalise, splitDirectories,
                        takeDirectory, takeExtension, joinPath, replaceExtension)
import System.IO (IOMode(ReadMode), hClose, hGetContents, hPutStrLn,
                  hSetEncoding, openTempFile, stderr, utf8, withBinaryFile, withFile)
import System.IO.Error (tryIOError)
import qualified System.Info as Host
import THC.Driver.Lock (withLock)
import System.Process (CreateProcess(..), StdStream(..), createProcess, proc, waitForProcess,
                       readCreateProcessWithExitCode)
import THC.Driver.Cabal (PlanOptions(..))
import THC.Driver.Cache (coreCacheDirectory)
import THC.Driver.CoreIndex (packageModules, modulePaths, moduleEntries)
import THC.Driver.CoreSymbols (publishCoreUnit)
import THC.Driver.GhcProxy (ghcProxyCommand, ghcProxyWindowsCommand, directPlugin)
import THC.Driver.NativeRecipe (NativeRecipe(..), componentRoots, componentNativeObjects,
  readNativeRecipe, ensureNativeRecipes, componentRuntimeShim, componentDeclaredModules, componentHomeInterfaces)
import THC.Driver.ScalarBitcode (ScalarBitcode, scalarBuildInputs, linkScalarBitcode)
import THC.Driver.RuntimeShim (RuntimeShim, withRuntimeShim, runtimeShimInputs, validateRuntimeShimModules, foreignExceptionBridgeUnit)
import THC.Driver.PackageNative (captureNativeObject, capturePackageNative, finishPackageNative,
  finishPackageNativeWithDependencies, linkInstalledNative)
import THC.Driver.NativeDependencies (readCOnlyProduct, configuredNativeArchive)
import THC.Driver.NativeCache (nativeToolIdentity, nativePieceIdentity)
import THC.Driver.Installed
import THC.Driver.InstalledForeign
import THC.Driver.Run (RunOptions(..), FfiMode, runtimeLaunchArguments, runResolvedPackage)
import THC.Driver.Zip (decodeZip, encodeZip)
import THC.Driver.Wired (WiredArtifacts(..), moduleSources, sourceHashes, pinnedSourcePath,
                         exportPinnedCore, exportPinnedWindowsCore, probeTargetLayout)

-- Cabal performs the project solve, preprocessing, host-tool/TH execution and
-- native build. Its machine-readable plan and per-component build-info, rather
-- than a reconstruction of GHC flags, drive the separate Core export.
data Unit = Unit
  { unitId :: String
  , unitValue :: Value
  , unitDepends :: [String]
  , unitLocal :: Bool
  }

data Component = Component
  { componentValue :: Value
  , componentCompiler :: FilePath
  , componentArguments :: [String]
  , componentSources :: [(String, FilePath)]
  }

data Bundle = Bundle { bundlePath :: FilePath, bundleHash :: String
                     , bundleModules :: [Value], bundleBuildKey :: String
                     , bundleReexports :: [(String, String, String)] }

data InstalledBundle = InstalledBundle
  { installedOwner :: String, installedBundle :: Bundle }

data BundleReceipt = PlainBundle | TargetLayoutBundle | PinnedSourceBundle
  deriving Show

data ExportContext = ExportContext
  { contextCompiler :: String, contextAbi :: String, contextPlatform :: String
  , contextPluginDb :: FilePath, contextPluginUnit :: String
  , contextPluginLibrary :: FilePath, contextNative :: FilePath
  , contextCache :: FilePath, contextDriverHash :: String
  , contextGhc :: FilePath, contextGhcPkg :: Maybe FilePath
  , contextDriver :: FilePath, contextRoot :: FilePath
  , contextProjectOptions :: [String]
  , contextNativeTools :: Value
  , contextVerifyArtifacts :: Bool
  , contextNoLinkUnit :: Maybe String }

boundary :: String
boundary = "optimized-Core-after-Tidy-before-CorePrep"

-- | Resolve the selected runnable component, acquire its Core, optionally audit, then
-- launch the guest. The path is the working directory for project selection;
-- Windows uses the restricted simple-package backend.
runProject :: RunOptions -> FilePath -> IO ()
runProject
  | Host.os == "mingw32" = runWindowsProject
  | otherwise = buildProject RunGuest

-- Preserve the existing PowerShell-backed simple-package implementation.
-- Both platforms use Cabal's positional target resolver, not a legacy CLI.
runWindowsProject :: RunOptions -> FilePath -> IO ()
runWindowsProject opts working = do
  require (not (null (runThcRoot opts))) "run requires --thc-root DIR"
  let flags = runPlan opts
  compiler <- maybe (findExecutable "ghc" >>= maybe (fail "GHC compiler not found") pure) pure (ghcPath flags) >>= canonicalizePath
  packageTool <- selectedPackageTool compiler (ghcPkgPath flags)
  output <- makeAbsolute (distDirectory flags)
  let native = output </> "selection"
      configuration = cabalProjectOptions opts ++ ["--builddir", native,
        "--with-compiler", compiler, "--with-hc-pkg", packageTool]
  createDirectoryIfMissing True native
  environment <- getEnvironment
  selected <- resolveRunnable working (runTarget opts) configuration environment native
  component <- field (unitValue selected) "component-name" :: IO String
  require (takeWhile (/= ':') component == "exe")
    "the Windows simple-package backend currently supports executable components only"
  source <- field (unitValue selected) "pkg-src" >>= (`field` "path")
  cabalFiles <- filter ((== ".cabal") . takeExtension) <$> listDirectory source
  cabalFile <- case cabalFiles of
    [file] -> pure (source </> file)
    _ -> fail "resolved Windows package must have exactly one Cabal declaration"
  selectedPackageFlags <- field (unitValue selected) "flags" :: IO (Map.Map String Bool)
  driver <- getExecutablePath
  runResolvedPackage opts {runTarget = drop 1 (dropWhile (/= ':') component),
    runPlan = flags {distDirectory = output, ghcPath = Just compiler,
      ghcPkgPath = Just packageTool,
      selectedFlags = [(mkFlagName name, value) | (name, value) <- Map.toList selectedPackageFlags]}}
    working cabalFile (prepareWindowsRuntimeWithVerification (runVerifyArtifacts opts)
      (runThcRoot opts) compiler packageTool driver)

-- Windows retains the simple-package raw-Core route. Acquire its support from
-- the same real Cabal runtime unit and checked native/bundle cache as project
-- exports, using vanilla interfaces and the selected compiler's plugin archive.
-- This does not claim that arbitrary installed dependency Core is available.
prepareWindowsRuntime :: FilePath -> FilePath -> FilePath -> FilePath -> FilePath -> IO ([String], FilePath)
prepareWindowsRuntime = prepareWindowsRuntimeWithVerification True

prepareWindowsRuntimeWithVerification :: Bool -> FilePath -> FilePath -> FilePath -> FilePath -> FilePath -> IO ([String], FilePath)
prepareWindowsRuntimeWithVerification verify repository selectedCompiler selectedPkg selectedDriver output = do
  require (Host.os == "mingw32") "vanilla Windows runtime acquisition requires native Windows"
  root <- canonicalizePath repository
  compiler <- canonicalizePath selectedCompiler
  pkg <- canonicalizePath selectedPkg
  driver <- canonicalizePath selectedDriver
  cache <- coreCacheDirectory
  -- ghc-pkg still has bounded Windows temporary-file paths. Keep the native
  -- build out of arbitrarily deep app output directories; the checked bundle
  -- keys below retain the full source/native/exporter identities.
  let workspaceKey = take 32 (shaHex (BL.toStrict (encode (root,compiler,pkg))))
  native <- makeAbsolute (cache </> "win32" </> workspaceKey)
  createDirectoryIfMissing True native
  withLock (native </> "acquire.lock") $ do
    cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
    let selection = ["--disable-shared", "--with-compiler=" ++ compiler, "--with-hc-pkg=" ++ pkg]
    runCommand True cabal (["build", "lib:thc", "--offline"] ++ selection) root
    plan <- readJson (root </> "dist-newstyle/cache/plan.json")
    compilerId <- field plan "compiler-id"
    abi <- field plan "compiler-abi"
    os <- field plan "os"
    arch <- field plan "arch"
    require (compilerId == "ghc-9.14.1" && os == "windows") "Windows runtime acquisition requires native GHC 9.14.1"
    units <- field plan "install-plan" :: IO [Value]
    let plugins = [value | value <- units, jsonField value "pkg-name" == Just ("thc" :: String),
          jsonField value "component-name" == Just ("lib" :: String), jsonField value "style" == Just ("local" :: String)]
    plugin <- case plugins of [value] -> pure value; _ -> fail "selected Cabal plan has no unique THC plugin"
    pluginUnit <- field plugin "id"
    sourceRoot <- field plugin "pkg-src" >>= (`field` "path") >>= canonicalizePath
    require (sourceRoot == root) "Windows plugin plan belongs to another checkout"
    python <- maybe "python" id <$> lookupEnv "THC_PYTHON"
    (registryStatus, registryOutput, registryDiagnostic) <- readCreateProcessWithExitCode
      (proc python [root </> "bin/plugin.py", "--root", root,
        "--ghc-pkg", pkg, "--registry-only"]) ""
    require (registryStatus == ExitSuccess) ("cannot resolve plugin dependency registry: " ++ registryDiagnostic)
    registry <- either fail pure (eitherDecodeStrict' (Text.encodeUtf8 (Text.pack registryOutput)))
    registryUnit <- field registry "unitId"
    require (registryUnit == pluginUnit) "plugin dependency registry differs from the Cabal plan"
    pluginDb <- field registry "packageDb"
    (status, description, diagnostic) <- readCreateProcessWithExitCode
      (proc pkg ["--package-db",pluginDb,"--ipid","describe",pluginUnit]) ""
    require (status == ExitSuccess) ("cannot read actual vanilla plugin registration: " ++ diagnostic)
    (_, pluginRegistration) <- either (fail . show) pure (Package.parseInstalledPackageInfo (Text.encodeUtf8 (Text.pack description)))
    require (prettyShow (Package.installedUnitId pluginRegistration) == pluginUnit) "plugin registration owner differs"
    archives <- filterM doesFileExist [directory </> ("lib" ++ library ++ ".a") |
      directory <- Package.libraryDirs pluginRegistration, library <- Package.hsLibraries pluginRegistration]
    archive <- case archives of [path] -> canonicalizePath path; _ -> fail "plugin has no unique vanilla archive"
    driverHash <- digestFile driver
    nativeTools <- nativeToolIdentity
    let context = ExportContext compilerId abi (arch ++ "-" ++ os) pluginDb pluginUnit
          archive native cache driverHash compiler (Just pkg) driver root [] nativeTools verify Nothing
        proxy = nativeCompilerProxy context
    createDirectoryIfMissing True (takeDirectory proxy)
    writeFile proxy ghcProxyWindowsCommand
    inherited <- getEnvironment
    let overrides = [("THC_PROXY_DRIVER",driver),("THC_PROXY_GHC",compiler),
          ("THC_PROXY_GLOBAL_UNITS",""),("THC_PROXY_NO_LINK_UNIT",""),
          ("THC_PROXY_NATIVE_RECIPES",native </> "cache/thc/native-recipes-v1")]
        environment = overrides ++ filter ((`notElem` map fst overrides) . fst) inherited
    wired <- wiredGhcInternal context root
    (owner, records) <- linkForeignExceptionRuntime context environment "pinned" Nothing archive [wired]
    let manifest = output </> "runtime-support/packages.json"
    selected <- either fail pure . foreignExceptionBridgeUnit =<< exceptionBridgeModules verify records
    require (selected == Just owner) "Windows runtime dictionary identity differs from its manifest"
    published <- mapM (publishCoreUnit cache verify) records
    atomicJson manifest (object ["format" .= ("thc-core-packages" :: String),
      "schema" .= (1::Int),"ghc" .= ("9.14.1" :: String),"foreignExceptionBridgeUnit" .= owner,"units" .= published])
    -- These exact module owners come from bundles validated above, never from
    -- app-supplied names. Retain the checked manifest through audit and launch;
    -- a long list of cache filenames would exceed cmd.exe's argument limit.
    provided <- fmap concat $ forM records $ \record -> do
      unit <- field record "id"
      references <- field record "modules" :: IO [Value]
      forM references $ \reference -> do
        name <- field reference "name"
        pure ("-fplugin-opt=THC.Plugin:closure-provided-module=" ++ unit ++ ":" ++ name)
    pure (exportWayOptions ++ provided, manifest)

nativeCompilerProxy :: ExportContext -> FilePath
nativeCompilerProxy context = contextNative context </> "cache/thc/native-ghc" ++
  if Host.os == "mingw32" then ".cmd" else ""

exportWayOptions :: [String]
-- Windows consumers must use the same declared module ABI as the private
-- vanilla source build, not unstable workers from the stock thin interfaces.
exportWayOptions = if Host.os == "mingw32" then ["-fignore-interface-pragmas"] else ["-dynamic"]

exportInterfaceWay :: String
exportInterfaceWay = if Host.os == "mingw32" then "vanilla" else "dynamic"

cabalProjectOptions :: RunOptions -> [String]
cabalProjectOptions opts =
  maybe [] (\path -> ["--project-dir", path]) (runProjectDirectory opts) ++
  maybe [] (\path -> ["--project-file", path]) (runProjectFile opts) ++
  ["--enable-tests" | enableTests flags] ++ ["--enable-benchmarks" | enableBenchmarks flags] ++
  ["--flags=" ++ unwords [(if enabled then "" else "-") ++ unFlagName name |
    (name, enabled) <- selectedFlags flags] | not (null (selectedFlags flags))]
  where flags = runPlan opts

-- | Acquisition performs the same native build, source/interface export and
-- atomic manifest publication as run. A published manifest is not an audit or
-- runtime admission result. Keep this boundary explicit for large closures.
acquireProject :: RunOptions -> FilePath -> IO ()
acquireProject = buildProject AcquireOnly

data ProjectAction = AcquireOnly | RunGuest deriving Eq

buildProject :: ProjectAction -> RunOptions -> FilePath -> IO ()
buildProject action opts target = do
  require (Host.os /= "mingw32") "project acquisition is not yet supported on Windows"
  require (action /= AcquireOnly || null (runArguments opts)) "acquire does not accept guest arguments"
  let command = if action == AcquireOnly then "acquire" else "run"
  require (not (null (runThcRoot opts))) (command ++ " requires --thc-root DIR")
  require (runInstalledCore opts `elem` ["required", "pinned"])
    "--installed-core must be required or pinned"
  require (runGhcSource opts == Nothing || runInstalledCore opts == "required")
    "--ghc-source requires --installed-core required"
  let flags = runPlan opts
  working <- canonicalizePath target
  let project = working
      projectOptions = cabalProjectOptions opts
  thcRoot <- canonicalizePath (runThcRoot opts)
  runtime <- maybe (pure (thcRoot </> "build/install/thc/bin/thc")) makeAbsolute (runRuntime opts)
  when (action == RunGuest) $ do
    requireFile runtime
    when (runVerifyArtifacts opts) $ requireFile (thcRoot </> "bin/audit-core.py")
  let buildPlugin = thcRoot </> "bin/build-compiler.sh"
      overrides = maybe [] (\path -> [("GHC", path)]) (ghcPath flags) ++
                  maybe [] (\path -> [("GHC_PKG", path)]) (ghcPkgPath flags)
  requireFile buildPlugin
  inherited <- getEnvironment
  let environment = overrides ++ filter (\(key, _) -> key `notElem` map fst overrides) inherited
  -- Cabal can build thc's executable without building its library. Publish the
  -- actual Cabal plugin registration before consulting the plugin manifest.
  runCommandWithEnv True buildPlugin [] thcRoot (Just environment)
  plugin <- readJson (thcRoot </> "build/compiler/plugin.json")
  schema <- field plugin "schema" :: IO Int
  require (schema == 1) "unsupported THC plugin manifest"
  pluginDb <- field plugin "packageDb"
  pluginUnit <- field plugin "unitId"
  pluginLibrary <- field plugin "sharedLibrary"
  registeredLibrary <- field plugin "cabalSharedLibrary"
  requireFile pluginLibrary
  requireDirectory pluginDb
  let requested = distDirectory flags
      requestedOutput = if isAbsolute requested then requested else project </> requested
  createDirectoryIfMissing True requestedOutput
  output <- canonicalizePath requestedOutput
  let native = output </> "native"
  compiler <- case ghcPath flags of
    Just path -> canonicalizePath path
    Nothing -> findExecutable "ghc" >>= maybe (fail "GHC compiler not found") canonicalizePath
  packageTool <- Just <$> selectedPackageTool compiler (ghcPkgPath flags)
  source <- traverse canonicalizePath (runGhcSource opts)
  withProjectLock output $
    runBuiltProject action project working thcRoot runtime output native (runTarget opts) projectOptions
                    pluginDb pluginUnit pluginLibrary compiler packageTool (runInstalledCore opts)
                    source registeredLibrary (runVerifyArtifacts opts) (runFfiMode opts) (runArguments opts)

resolveRunnable :: FilePath -> String -> [String] -> [(String, String)] -> FilePath -> IO Unit
resolveRunnable working target configuration environment native = do
  -- Cabal list-bin uses run's sole-executable preference and runnable-component
  -- fallback without executing anything. Its exact bin-file selects the unit;
  -- do not duplicate Cabal's package/flag/ambiguity resolution here.
  (resolved, binary, diagnostic) <- readCreateProcessWithExitCode
    (proc (maybe "cabal" id (lookup "CABAL" environment)) (["list-bin", if null target then "." else target] ++ configuration))
      {cwd = Just working, env = Just environment} ""
  require (resolved == ExitSuccess) ("Cabal runnable target selection failed: " ++ diagnostic)
  selectedPath <- case lines binary of
    [path] | not (null path) -> canonicalizePath path
    _ -> fail "cabal list-bin did not return exactly one runnable artifact"
  selectionPlan <- readJson (native </> "cache/plan.json")
  selectionUnits <- mapM readUnit =<< field selectionPlan "install-plan"
  candidates <- filterM (\unit -> case jsonField (unitValue unit) "bin-file" of
    Just path | unitLocal unit -> (== selectedPath) <$> canonicalizePath path
    _ -> pure False) selectionUnits
  case candidates of
    [unit] -> pure unit
    _ -> fail "Cabal runnable artifact does not identify exactly one local component"

-- Resolve the actual selected compiler's companion before Cabal sees the
-- forwarding wrapper. The wrapper directory is not a GHC installation.
selectedPackageTool :: FilePath -> Maybe FilePath -> IO FilePath
selectedPackageTool ghc requested = do
  inherited <- lookupEnv "GHC_PKG"
  path <- case requested `orElse` inherited of
    Just value -> if isAbsolute value then pure value else findExecutable value >>=
      maybe (fail "selected ghc-pkg executable not found") pure
    Nothing -> do
      let suffix = if Host.os == "mingw32" then ".exe" else ""
          candidates = [takeDirectory ghc </> name ++ suffix | name <- ["ghc-pkg-9.14.1", "ghc-pkg"]]
      available <- filterM doesFileExist candidates
      case available of value:_ -> pure value; [] -> fail "no ghc-pkg beside selected GHC"
  packageTool <- canonicalizePath path
  compilerVersion <- output ghc ["--numeric-version"]
  packageVersion <- output packageTool ["--version"]
  require (compilerVersion == "9.14.1" && packageVersion == "GHC package manager version 9.14.1")
    "selected GHC and ghc-pkg must both be 9.14.1"
  compilerDb <- canonicalizePath =<< output ghc ["--print-global-package-db"]
  listing <- output packageTool ["--global", "--no-user-package-db", "list", "ghc-internal"]
  packageDb <- case lines listing of value:_ -> canonicalizePath value; [] -> fail "ghc-pkg did not report its global database"
  require (compilerDb == packageDb) "selected GHC and ghc-pkg global databases differ"
  pure packageTool
  where
    orElse (Just value) _ = Just value
    orElse Nothing other = other
    output program arguments = do
      (status, out, err) <- readCreateProcessWithExitCode (proc program arguments) ""
      require (status == ExitSuccess) ("selected tool failed: " ++ program ++ ": " ++ take 4096 err)
      pure (reverse (dropWhile (`elem` ['\r','\n']) (reverse out)))

runBuiltProject :: ProjectAction -> FilePath -> FilePath -> FilePath -> FilePath -> FilePath -> FilePath ->
                   String -> [String] -> FilePath -> String -> FilePath -> FilePath ->
                   Maybe FilePath -> String -> Maybe FilePath -> FilePath -> Bool -> Maybe FfiMode -> [String] -> IO ()
runBuiltProject action project working thcRoot runtime output native target projectOptions
                pluginDb pluginUnit pluginLibrary ghc ghcPkg installedPolicy ghcSource registeredLibrary verifyArtifacts ffiMode guestArguments = do
  driver <- getExecutablePath
  let proxy = native </> "cache/thc/native-ghc"
      receipts = native </> "cache/thc/native-recipes-v1"
      packageTool = maybe (takeDirectory ghc </> "ghc-pkg") id ghcPkg
      configuration = projectOptions ++ ["--builddir", native,
                       "--with-compiler", proxy, "--with-hc-pkg", packageTool]
  createDirectoryIfMissing True (takeDirectory proxy)
  let wrapper = "#!/bin/sh\n# compiler " ++ shaHex (BL.toStrict (encode (ghc, packageTool))) ++
        "\n" ++ ghcProxyCommand
  exists <- doesFileExist proxy
  unchanged <- if exists then (== wrapper) <$> readFile proxy else pure False
  unless unchanged (writeFile proxy wrapper)
  permissions <- getPermissions proxy
  setPermissions proxy (permissions {Directory.executable = True})
  inherited <- getEnvironment
  let overrides = [("THC_PROXY_DRIVER", driver), ("THC_PROXY_GHC", ghc),
                   ("THC_PROXY_NATIVE_RECIPES", receipts), ("THC_PROXY_GLOBAL_UNITS", ""),
                   ("THC_PROXY_NO_LINK_UNIT", ""),
                   ("THC_PROXY_NATIVE_PIECES", native </> "cache/thc/native-pieces-v1")]
      selectionEnvironment = overrides ++ filter (\(key, _) -> key `notElem` map fst overrides) inherited
      cabal = maybe "cabal" id (lookup "CABAL" selectionEnvironment)
  selectedUnit <- resolveRunnable working target configuration selectionEnvironment native
  selectionPlan <- readJson (native </> "cache/plan.json")
  selectionUnits <- mapM readUnit =<< field selectionPlan "install-plan"
  let selectionById = Map.fromList [(unitId unit, unit) | unit <- selectionUnits]
  require (Map.size selectionById == length selectionUnits) "Cabal plan has duplicate unit IDs"
  selectedClosure <- dependencyClosure selectionById (unitId selectedUnit)
  let guestOnly = any (\unit -> jsonField (unitValue unit) "pkg-name" == Just ("thc" :: String) &&
                              jsonField (unitValue unit) "component-name" == Just ("lib:interop" :: String)) selectedClosure
      noLinkUnit = if guestOnly then Just (unitId selectedUnit) else Nothing
      environment = ("THC_PROXY_NO_LINK_UNIT", maybe "" id noLinkUnit) :
        filter ((/= "THC_PROXY_NO_LINK_UNIT") . fst) selectionEnvironment
      nativeBuild arguments = runCommandWithEnv True cabal arguments project (Just environment)
  selectedPackageName <- field (unitValue selectedUnit) "pkg-name"
  selectedComponentName <- field (unitValue selectedUnit) "component-name"
  require (takeWhile (/= ':') selectedComponentName `elem` ["exe", "bench", "test"])
    "selected Cabal component is not an executable, exitcode test or benchmark"
  let selection = (Just selectedPackageName, selectedComponentName)
      targetComponent = selectedPackageName ++ ":" ++ selectedComponentName
  nativeBuild (["build", targetComponent, "--enable-build-info"] ++ configuration)
  plan <- readJson (native </> "cache/plan.json")
  cabalVersion <- field plan "cabal-version"
  compilerId <- field plan "compiler-id"
  require (take 5 cabalVersion == "3.16." && compilerId == "ghc-9.14.1")
    "THC project run requires cabal-install 3.16 and GHC 9.14.1"
  abi <- field plan "compiler-abi"
  os <- field plan "os"
  arch <- field plan "arch"
  cacheRoot <- coreCacheDirectory
  driverHash <- digestFile driver
  nativeTools <- nativeToolIdentity
  let context = ExportContext compilerId abi (arch ++ "-" ++ os) pluginDb pluginUnit
                              pluginLibrary native cacheRoot driverHash ghc ghcPkg driver thcRoot projectOptions nativeTools verifyArtifacts noLinkUnit
  records <- field plan "install-plan" :: IO [Value]
  units <- mapM readUnit records
  let byId = Map.fromList [(unitId unit, unit) | unit <- units]
  require (Map.size byId == length units) "Cabal plan has duplicate unit IDs"
  selected <- selectRunnable selection units
  require (unitId selected == unitId selectedUnit) "Cabal changed the selected unit identity during its build"
  -- The plan can also list unrelated executables, tests and benchmarks.
  -- Only the requested runnable component and its complete dependency
  -- closure have required build-info; a missing member of that closure fails.
  ordered <- dependencyClosure byId (unitId selected)
  builtLocals <- filterM (\unit -> case jsonField (unitValue unit) "build-info" of
    Nothing -> pure False
    Just path -> doesFileExist path) (filter unitLocal units)
  builtComponents <- forM builtLocals $ \unit -> do
    component <- readComponentMetadata (unitId unit `elem` map unitId ordered) unit context
    pure (unit, component)
  roots <- fmap (sort . nub . concat) $ forM builtComponents $ \(unit, component) -> do
    dist <- field (unitValue unit) "dist-dir"
    componentRoots dist (componentValue component)
  localComponents <- forM (filter (\(unit, _) -> unitId unit `elem` map unitId ordered) builtComponents) $
    \(unit, component) -> do
      completed <- completeHomeModules context roots unit component
      pure (unit, completed)
  -- Repair missing native receipts through Cabal itself; never reconstruct
  -- the missing compiler invocation from a binary setup-config.
  forM_ localComponents $ \(unit, component) -> do
    dist <- field (unitValue unit) "dist-dir"
    ensureNativeRecipes native dist roots ghc (componentValue component) $ do
      packageName <- field (unitValue unit) "pkg-name" :: IO String
      componentName <- field (unitValue unit) "component-name" :: IO String
      sourceRoot <- field (componentValue component) "src-dir"
      let componentTarget = if componentName == "lib" then "lib:" ++ packageName else componentName
      -- v2-build's monitor can say "up to date" after an intermediate .o is
      -- deleted. Ask this same Cabal CLI's Simple Setup to build the component
      -- directly; it owns and reads its own configured build representation.
      runCommandWithEnv True cabal ["act-as-setup", "--build-type=Simple", "--", "build",
        "--builddir=" ++ dist, componentTarget] sourceRoot (Just environment)
  let globals = [unit | unit <- ordered, not (unitLocal unit),
                       jsonField (unitValue unit) "type" == Just ("configured" :: String)]
  installed <- if installedPolicy == "pinned" then pure Map.empty else do
    originalContext <- prepareInterfaceHelper context thcRoot
    let installedUnits = [unit | unit <- ordered,
              jsonField (unitValue unit) "type" == Just ("pre-existing" :: String)]
    originalRegistrations <- mapM (discoverInstalled originalContext . unitId) installedUnits
    helperContext <- case ghcSource of
      Nothing -> pure originalContext
      Just source -> prepareForeignInterfaces
        (ForeignCompiler ghc pluginDb pluginUnit pluginLibrary registeredLibrary driverHash)
        cacheRoot source originalContext originalRegistrations
    registrations <- mapM (discoverInstalled helperContext . unitId) installedUnits
    validateReexports registrations
    bundles <- forM registrations $ \registrationUnit -> do
      planned <- maybe (fail "installed registration not in Cabal plan") pure
        (Map.lookup (registeredId registrationUnit) byId)
      require (sort (unitDepends planned) == sort (installedDepends registrationUnit))
        ("installed dependencies differ from Cabal plan for " ++ registeredId registrationUnit)
      result <- prepareInstalledBundleWithVerification verifyArtifacts cacheRoot (native </> "cache/thc/staging")
        (thcRoot </> "src/driver/cbits/target-layout.c") driverHash helperContext registrationUnit
      bundle <- either (\missing -> fail
        ("complete-interface-core unavailable for " ++ missingUnit missing ++ ":" ++ missingModule missing ++
         " (dynamic interface " ++ missingInterface missing ++ "). Select a full-Core GHC; " ++
         "--installed-core pinned explicitly selects the limited legacy source provider.")) pure result
      pure (registeredId registrationUnit, (registrationUnit, bundle))
    let owners = map (installedOwner . snd . snd) bundles
        registered = map fst bundles
    require (length owners == length (nub owners)) "multiple installed registrations claim one Core owner"
    require (all (\(identifier, (_, item)) -> installedOwner item == identifier ||
                  installedOwner item `notElem` registered && Map.notMember (installedOwner item) byId) bundles)
      "installed Core owner collides with another Cabal unit"
    pure (Map.fromList bundles)
  selectedPackage <- field (unitValue selected) "pkg-name"
  selectedComponent <- field (unitValue selected) "component-name"
  globalBundles <- prepareGlobalBundles context project
    (selectedPackage ++ ":" ++ selectedComponent) byId localComponents globals
  (_, described) <- foldlM (\(keys, acc) unit -> do
    kind <- optionalField (unitValue unit) "type" ("" :: String)
    bundle <- if unitLocal unit
      then Just <$> exportUnit context roots keys unit
      else do
        if kind == "configured" then Just <$> maybe
          (fail ("Core bundle missing for Cabal store component " ++ unitId unit)) pure
          (Map.lookup (unitId unit) globalBundles)
        else pure (installedBundle . snd <$> Map.lookup (unitId unit) installed)
    forM_ (maybe [] bundleReexports bundle) $ \(_, provider, name) -> do
      dependencies <- dependencyClosure byId (unitId unit)
      let owner = maybe provider (installedOwner . snd) (Map.lookup provider installed)
          exported = [moduleName | record <- acc, jsonField record "id" == Just owner,
            moduleRef <- maybe [] id (jsonField record "modules" :: Maybe [Value]),
            Just moduleName <- [jsonField moduleRef "name" :: Maybe String]]
      require (provider `elem` map unitId dependencies && name `elem` exported)
        ("missing concrete store reexport provider " ++ provider ++ ":" ++ name ++ " for " ++ unitId unit)
    let modules = maybe [] bundleModules bundle
        fields = ["id" .= unitId unit, "depends" .= unitDepends unit, "modules" .= modules] ++
                 maybe [] (\item -> ["bundle" .= object ["path" .= bundlePath item,
                                                   "sha256" .= bundleHash item]]) bundle
        keys' = maybe keys (\item -> Map.insert (unitId unit) (bundleBuildKey item) keys) bundle
        recordsForUnit = maybe [object fields] (uncurry installedRecords) (Map.lookup (unitId unit) installed)
    pure (keys', acc ++ recordsForUnit)) (Map.empty, []) ordered
  wired <- if installedPolicy == "required" then pure [] else do
    require (Map.notMember "ghc-internal" byId) "Cabal plan duplicates the wired ghc-internal unit"
    (:[]) <$> wiredGhcInternal context thcRoot
  (bridgeUnit, linked) <- linkForeignExceptionRuntime context environment installedPolicy ghcSource
    registeredLibrary (described ++ wired)
  let manifest = output </> "packages.json"
      -- Complete installed Core provides GHC's generated :Main wrapper and
      -- original Handle shutdown. Pinned source remains the explicit limited
      -- raw-IO provider used by the stock-GHC driver controls.
      lifecycle = installedPolicy == "required"
      entry = if lifecycle then "main::Main.main" else unitId selected ++ ":Main.main"
      shutdown = "ghc-internal:GHC.Internal.TopHandler.flushStdHandles"
      audit = output </> "audit.json"
  published <- mapM (publishCoreUnit cacheRoot verifyArtifacts) linked
  atomicJson manifest (object ["format" .= ("thc-core-packages" :: String),
                               "schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
                               "foreignExceptionBridgeUnit" .= bridgeUnit,
                               "units" .= published])
  when (action == RunGuest) $ do
    when verifyArtifacts $
      runCommand True "python3" ([thcRoot </> "bin/audit-core.py", "--package-manifest", manifest,
                                "--entry", entry] ++
                               (if lifecycle then ["--entry", shutdown] else []) ++
                               ["--io-main", "--output", audit]) thcRoot
    -- Full-Core main and shutdown share one program and its Handle CAFs.
    -- Like cabal run, preserve the caller's cwd even with --project-dir.
    let programName = reverse (takeWhile (/= ':') (reverse (snd selection)))
    runCommand False runtime (runtimeLaunchArguments verifyArtifacts ffiMode (if lifecycle
        then ["--run-executable", '@' : manifest, entry, shutdown]
        else ["--run-io", '@' : manifest, entry]) programName guestArguments) working

-- | Select the two original exception bridge modules. The flag requests full
-- artifact verification rather than replaying an unchanged successful read.
exceptionBridgeModules :: Bool -> [Value] -> IO [Value]
exceptionBridgeModules verify units = fmap concat $ forM units $ \unit -> do
  references <- optionalField unit "modules" ([] :: [Value])
  let selected = [ref | ref <- references, jsonField ref "name" `elem`
        map Just (["THC.Exception", "THC.Internal.Exception"] :: [String])]
  if null selected then pure [] else do
    bundle <- field unit "bundle"
    path <- field bundle "path"
    expected <- field bundle "sha256"
    let request = object ["kind" .= ("exception-bridge" :: String), "bundle" .= bundle,
          "unit" .= (jsonField unit "id" :: Maybe String), "modules" .= selected]
    ready <- rememberSelection verify (path ++ ".bridge.json") [path] request $ do
      bytes <- BS.readFile path
      require (shaHex bytes == expected) "exception runtime bundle changed during linking"
      entries <- either fail pure =<< decodeZip bytes
      Just <$> forM selected (\ref -> do
        member <- field ref "path"
        digest <- field ref "sha256"
        body <- maybe (fail "exception runtime bundle lacks its declared module") pure (lookup member entries)
        require (shaHex body == digest) "exception runtime module hash mismatch"
        value <- either fail pure (eitherDecodeStrict' body)
        require (jsonField value "unit" == (jsonField unit "id" :: Maybe String) &&
          jsonField value "module" == (jsonField ref "name" :: Maybe String))
          "exception runtime module identity mismatch"
        pure value)
    maybe (fail "exception runtime metadata unavailable") pure ready

-- Cabal owns the sidecar's unit, configuration, dependencies and native compiler
-- invocations. The source is the real runtime library; no rewritten package or
-- synthetic foreign import acquires an application unit ID. All generated files
-- live under this run's native output, leaving its project files untouched.
linkForeignExceptionRuntime :: ExportContext -> [(String, String)] -> String -> Maybe FilePath ->
  FilePath -> [Value] -> IO (String, [Value])
linkForeignExceptionRuntime context environment installedPolicy ghcSource registeredLibrary described = do
  existing <- either fail pure . foreignExceptionBridgeUnit =<< exceptionBridgeModules (contextVerifyArtifacts context) described
  case existing of
    Just unit -> pure (unit, described)
    Nothing -> do
      let native = contextNative context
          root = contextRoot context
          directory = native </> "runtime-sidecar"
          project = directory </> "cabal.project"
          dist = directory </> "dist"
          compiler = contextGhc context
          cabal = maybe "cabal" id (lookup "CABAL" environment)
          pkg = maybe (takeDirectory compiler </> "ghc-pkg") id (contextGhcPkg context)
          projectText = "packages: " ++ show (root </> "thc.cabal") ++ "\n"
          configuration = ["--project-file", project, "--builddir", dist,
            "--with-compiler", nativeCompilerProxy context, "--with-hc-pkg", pkg] ++
            ["--disable-shared" | Host.os == "mingw32"]
      createDirectoryIfMissing True directory
      exists <- doesFileExist project
      previous <- if exists then readFile project else pure ""
      unless (previous == projectText) (writeFile project projectText)
      runCommandWithEnv True cabal (["build", "thc:lib:runtime", "--enable-build-info"] ++ configuration)
        directory (Just environment)
      plan <- readJson (dist </> "cache/plan.json")
      require (jsonField plan "compiler-id" == Just (contextCompiler context) &&
        jsonField plan "compiler-abi" == Just (contextAbi context))
        "runtime sidecar compiler differs from the application"
      units <- mapM readUnit =<< field plan "install-plan"
      let byId = Map.fromList [(unitId unit, unit) | unit <- units]
          candidates = [unit | unit <- units, unitLocal unit,
            jsonField (unitValue unit) "pkg-name" == Just ("thc" :: String),
            jsonField (unitValue unit) "component-name" == Just ("lib:runtime" :: String)]
      runtime <- case candidates of
        [unit] -> pure unit
        _ -> fail "runtime sidecar plan must contain exactly one local thc:runtime library"
      ordered <- dependencyClosure byId (unitId runtime)
      require (all (\unit -> unitId unit == unitId runtime ||
        jsonField (unitValue unit) "type" == Just ("pre-existing" :: String)) ordered)
        "runtime sidecar has an unsupported non-installed dependency"
      component <- readComponent runtime context
      shim <- componentRuntimeShim (componentValue component)
      require shim "runtime sidecar lacks the exact native runtime-shim profile"
      componentDist <- field (unitValue runtime) "dist-dir"
      roots <- componentRoots componentDist (componentValue component)
      ensureNativeRecipes native componentDist roots compiler (componentValue component) $
        runCommandWithEnv True cabal ["act-as-setup", "--build-type=Simple", "--", "build",
          "--builddir=" ++ componentDist, "lib:runtime"] root (Just environment)
      let present = [identifier | record <- described, Just identifier <- [jsonField record "id" :: Maybe String]]
          missing = [unit | unit <- ordered, unitId unit /= unitId runtime, unitId unit `notElem` present]
      dependencies <- if installedPolicy == "pinned"
        then pure [object ["id" .= unitId unit, "depends" .= unitDepends unit, "modules" .= ([] :: [Value])] |
                    unit <- missing]
        else do
          original <- prepareInterfaceHelper context root
          registrations <- mapM (discoverInstalled original . unitId) missing
          helper <- case ghcSource of
            Nothing -> pure original
            Just source -> prepareForeignInterfaces
              (ForeignCompiler compiler (contextPluginDb context) (contextPluginUnit context)
                (contextPluginLibrary context) registeredLibrary (contextDriverHash context))
              (contextCache context) source original registrations
          fmap concat $ forM missing $ \unit -> do
            registrationUnit <- discoverInstalled helper (unitId unit)
            require (sort (unitDepends unit) == sort (installedDepends registrationUnit))
              "runtime sidecar installed dependencies differ from its plan"
            result <- prepareInstalledBundleWithVerification (contextVerifyArtifacts context) (contextCache context) (native </> "cache/thc/staging")
              (root </> "src/driver/cbits/target-layout.c") (contextDriverHash context) helper registrationUnit
            bundle <- either (\failure -> fail ("runtime sidecar lacks complete Core: " ++ show failure)) pure result
            pure (installedRecords registrationUnit bundle)
      bundle <- exportUnit context roots Map.empty runtime
      let record = object ["id" .= unitId runtime, "depends" .= unitDepends runtime,
            "modules" .= bundleModules bundle,
            "bundle" .= object ["path" .= bundlePath bundle, "sha256" .= bundleHash bundle]]
          linked = described ++ dependencies ++ [record]
          identities = [identifier | item <- linked, Just identifier <- [jsonField item "id" :: Maybe String]]
      require (length identities == length (nub identities)) "runtime sidecar collides with an existing Core owner"
      selected <- either fail pure . foreignExceptionBridgeUnit =<< exceptionBridgeModules (contextVerifyArtifacts context) linked
      require (selected == Just (unitId runtime)) "runtime sidecar did not export its genuine exception bridge"
      pure (unitId runtime, linked)

prepareInterfaceHelper :: ExportContext -> FilePath -> IO InstalledContext
prepareInterfaceHelper context root = do
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  let ghc = contextGhc context
      pkg = maybe (takeDirectory ghc </> "ghc-pkg") id (contextGhcPkg context)
      selection = ["exe:thc-interface", "--offline", "--with-compiler=" ++ ghc, "--with-hc-pkg=" ++ pkg] ++
        ["--disable-shared" | Host.os == "mingw32"]
  runCommand True cabal ("build" : selection) root
  (status, output, diagnostic) <- readCreateProcessWithExitCode
    (proc cabal ("list-bin" : selection)) {cwd = Just root} ""
  require (status == ExitSuccess) ("cannot locate selected thc-interface: " ++ diagnostic)
  helper <- case lines output of
    [path] -> canonicalizePath path
    _ -> fail "cabal list-bin did not return one thc-interface executable"
  requireFile helper
  -- First slice is deliberately limited to pre-existing global registrations.
  -- A store/source component continues to use its existing Cabal build path.
  installedContext ghc pkg helper [] (object
    ["id" .= contextCompiler context, "abi" .= contextAbi context,
     "platform" .= contextPlatform context, "way" .= (exportInterfaceWay ++ "-nonprofiling")])

-- The probe retains complete installed source/native identities. A successful
-- selection receipt can then avoid reopening an unchanged archive; explicit
-- verification and misses still run the complete original validation.
prepareInstalledBundle :: FilePath -> FilePath -> FilePath -> String -> InstalledContext -> InstalledUnit ->
                          IO (Either MissingCore InstalledBundle)
prepareInstalledBundle = prepareInstalledBundleWithVerification True

prepareInstalledBundleWithVerification :: Bool -> FilePath -> FilePath -> FilePath -> String -> InstalledContext -> InstalledUnit ->
                          IO (Either MissingCore InstalledBundle)
prepareInstalledBundleWithVerification verify cache staging recipe driverHash context registrationUnit = do
  probeCurrent <- prepareInstalledProbe context registrationUnit
  evidence <- optionalIO $ do
    helperHash <- digestFile (installedHelper context)
    recipeHash <- digestFile recipe
    (rtsRegistration, _) <- installedLayoutHeaders context registrationUnit
    probe <- probeCurrent
    let identity = object ["schema" .= (1 :: Int), "helperHash" .= helperHash,
          "driverHash" .= driverHash, "recipeHash" .= recipeHash,
          "rtsRegistration" .= rtsRegistration,
          "registration" .= installedProvenance context registrationUnit]
        index = cache </> "installed-probes/v1" </> shaHex (BL.toStrict (encode identity)) ++ ".json"
    pure (identity, probe, index)
  case evidence of
    Nothing -> acquireInstalledBundle verify cache staging recipe driverHash context registrationUnit (\_ _ _ -> pure ())
    Just (identity, probe, index) -> do
      hit <- optionalIO $ do
        envelope <- readJson index
        record <- field envelope "record"
        require (jsonField envelope "sha256" == Just (shaHex (BL.toStrict (encode record))))
          "corrupt installed probe index"
        require (jsonField record "identity" == Just identity && jsonField record "probe" == Just probe)
          "installed probe cache miss"
        sources <- field record "sources"
        validateSourceObservations sources
        inputs <- field record "inputs"
        nativeArtifacts <- field inputs "nativeArtifacts" :: IO [Value]
        let validateNativeArtifacts = forM_ nativeArtifacts $ \artifact -> do
              path <- field artifact "path"
              expected <- field artifact "sha256"
              actual <- digestFile path
              require (actual == expected) "installed native header changed"
        validateNativeArtifacts
        owner <- field inputs "unit"
        buildKey <- field inputs "buildKey"
        exportKey <- field inputs "exportKey"
        partition <- installedPartition context
        require (length exportKey == 64 && all isHexDigit exportKey) "invalid installed export key"
        let destination = cache </> "core-bundles/v1" </> partition </> exportKey </> registeredId registrationUnit ++ ".zip"
        loaded <- readBundle verify TargetLayoutBundle destination owner buildKey exportKey inputs
          (map fst (installedInterfaces registrationUnit))
        bundle <- maybe (fail "invalid indexed installed bundle") pure loaded
        require (jsonField record "bundleSha256" == Just (bundleHash bundle)) "changed indexed installed bundle"
        after <- probeCurrent
        require (after == probe) "installed payload changed while validating cached bundle"
        validateSourceObservations sources
        validateNativeArtifacts
        pure (InstalledBundle owner bundle)
      case hit of
        Just bundle -> pure (Right bundle)
        Nothing -> acquireInstalledBundle verify cache staging recipe driverHash context registrationUnit $ \bundle inputs modules -> do
          _ <- optionalIO $ do
            sources <- installedSourceObservations modules
            validateSourceObservations sources
            after <- probeCurrent
            require (after == probe) "installed payload changed during acquisition"
            let record = object ["identity" .= identity, "probe" .= probe,
                  "sources" .= sources, "inputs" .= inputs,
                  "bundleSha256" .= bundleHash (installedBundle bundle)]
            atomicJson index (object ["record" .= record, "sha256" .= shaHex (BL.toStrict (encode record))])
          pure ()

-- Cache hints may be absent, stale, corrupt or unsupported by an older helper.
-- Cancellation remains observable; only ordinary IO/protocol failures fall back.
optionalIO :: IO a -> IO (Maybe a)
optionalIO action = either (const Nothing) Just <$> tryIOError action

-- Replay only a successful selection for the exact current inputs and file
-- observations. This is a disposable local cache, not artifact authentication:
-- --verify-artifacts always executes the original validator. In particular a
-- missing/malformed receipt, changed key, or changed file takes the cold path.
rememberSelection :: (FromJSON a, ToJSON a) => Bool -> FilePath -> [FilePath] -> Value ->
                     IO (Maybe a) -> IO (Maybe a)
rememberSelection verify receipt paths request validate = do
  before <- optionalIO observe
  hit <- if verify then pure Nothing else optionalIO $ do
    envelope <- readJson receipt
    record <- field envelope "record"
    require (jsonField envelope "sha256" == Just (shaHex (BL.toStrict (encode record))))
      "invalid selection receipt"
    require (jsonField record "format" == Just ("thc-core-selection-v1" :: String) &&
      jsonField record "request" == Just request && jsonField record "files" == before && before /= Nothing)
      "changed selection inputs"
    field record "result"
  case hit of
    Just ready -> pure (Just ready)
    Nothing -> do
      result <- validate
      case result of
        Nothing -> pure ()
        Just ready -> do
          -- Caching must not make an otherwise valid read fail on a read-only
          -- cache. Atomic replacement also tolerates concurrent equal readers.
          _ <- optionalIO $ do
            after <- observe
            require (maybe True (== after) before) "archive changed while selecting Core"
            let record = object ["format" .= ("thc-core-selection-v1" :: String),
                  "request" .= request, "files" .= after, "result" .= ready]
            atomicJson receipt (object ["record" .= record, "sha256" .= shaHex (BL.toStrict (encode record))])
          pure ()
      pure result
  where
    observe = Aeson.toJSON <$> forM paths (\path -> do
      size <- Directory.getFileSize path
      modified <- Directory.getModificationTime path
      pure (path, size, show modified))

rememberBundle :: Bool -> FilePath -> [FilePath] -> Value -> IO (Maybe Bundle) -> IO (Maybe Bundle)
rememberBundle verify receipt paths request validate = do
  result <- rememberSelection verify receipt paths request (fmap snapshot <$> validate)
  pure (restore <$> result)
  where
    snapshot bundle = (bundlePath bundle, bundleHash bundle, bundleModules bundle,
                       bundleBuildKey bundle, bundleReexports bundle)
    restore (path, digest, modules, key, reexports) = Bundle path digest modules key reexports

installedPartition :: InstalledContext -> IO FilePath
installedPartition context = do
  fields <- mapM (field (installedCompiler context)) ["id", "abi", "platform"] :: IO [String]
  require (all (\value -> not (null value) && all (\c -> isAlphaNum c || c `elem` ("-._" :: String)) value) fields)
    "invalid installed compiler cache partition"
  pure (foldl1 (\left right -> left ++ "-" ++ right) fields)

-- Match THC.Sources' UTF-8 content/absence semantics, including relative paths
-- and unreadable files. These are the exact files the archived Core observed;
-- changed interface bytes invalidate the index before this list can be reused.
sourceObservation :: FilePath -> Maybe String -> IO Value
sourceObservation path contents = do
  absolute <- makeAbsolute path
  pure (object ["path" .= path, "resolved" .= absolute,
    "contentSha256" .= fmap (shaHex . Text.encodeUtf8 . Text.pack) contents])

installedSourceObservations :: [(String, BS.ByteString)] -> IO [Value]
installedSourceObservations modules = do
  observations <- fmap concat $ forM modules $ \(_, bytes) -> do
    core <- either fail pure (eitherDecodeStrict' bytes)
    sources <- field core "sourceFiles" :: IO [Value]
    forM sources $ \source -> do
      path <- field source "path"
      content <- field source "content"
      record <- sourceObservation path content
      pure (path, record)
  let unique = Map.fromList observations
  require (all (\(path, record) -> Map.lookup path unique == Just record) observations)
    "source changed between installed module exports"
  pure (Map.elems unique)

validateSourceObservations :: [Value] -> IO ()
validateSourceObservations sources = forM_ sources $ \expected -> do
  path <- field expected "path"
  contents <- optionalIO $ withFile path ReadMode $ \handle -> do
    hSetEncoding handle utf8
    text <- hGetContents handle
    _ <- evaluate (length text)
    pure text
  actual <- sourceObservation path contents
  require (actual == expected) "installed source observation changed"

-- Ordinary acquisition remains authoritative, including when the optional
-- probe cannot establish complete evidence. Compiler binaries are not hashed.
acquireInstalledBundle :: Bool -> FilePath -> FilePath -> FilePath -> String -> InstalledContext -> InstalledUnit ->
                          (InstalledBundle -> Value -> [(String, BS.ByteString)] -> IO ()) ->
                          IO (Either MissingCore InstalledBundle)
acquireInstalledBundle verify cache staging recipe driverHash context registrationUnit remember = do
  acquired <- acquireInstalled context registrationUnit
  case acquired of
    Left missing -> pure (Left missing)
    Right core -> do
      createDirectoryIfMissing True staging
      (temporary, handle) <- openTempFile staging "installed-native-"
      hClose handle
      removeFile temporary
      createDirectory temporary
      (do
        helperHash <- digestFile (installedHelper context)
        requireFile recipe
        recipeHash <- digestFile recipe
        (rtsRegistration, includes) <- installedLayoutHeaders context registrationUnit
        compilerId <- field (installedCompiler context) "id" :: IO String
        compilerAbi <- field (installedCompiler context) "abi" :: IO String
        compilerPlatform <- field (installedCompiler context) "platform" :: IO String
        let cacheName value = not (null value) &&
              all (\c -> isAlphaNum c || c `elem` ("-._" :: String)) value
            registered = registeredId registrationUnit
            unit = coreOwner core
            modules = sortOn fst (coreModules core)
            nativeDirectory = temporary </> "native-link"
            arguments = ["-no-user-package-db"] ++
              concatMap (\path -> ["-package-db", path]) (installedDatabases context) ++
              ["-package-id", registered] ++ map ("-I" ++) includes
        require (all cacheName [compilerId, compilerAbi, compilerPlatform, registered])
          "installed Core compiler or registered package-cache identity is invalid"
        configured <- case installedSource context of
          Nothing -> pure Nothing
          Just source -> configuredNativeArchive source (nativeDirectory </> "configured")
            (installedCompiler context) (registration registrationUnit)
        let nativeArguments = maybe [] (\(archive,_) -> ["-optl" ++ archive]) configured ++ arguments
            configuredInputs = maybe [] snd configured
        linked <- linkInstalledNative (installedGhc context) (installedLibdir context)
          nativeArguments nativeDirectory unit modules
        forM_ configuredInputs $ \input -> do
          path <- field input "path"
          expected <- field input "sha256"
          actual <- digestFile path
          require (actual == expected) "configured native provider changed during linking"
        linkedArtifacts <- installedNativeArtifacts nativeDirectory
        let nativeArtifacts = nub (configuredInputs ++ linkedArtifacts)
        let inputFields = ["format" .= ("thc-core-build-inputs" :: String), "schema" .= (1 :: Int),
              "unit" .= unit, "compiler" .= installedCompiler context,
              "component" .= object ["kind" .= ("installed-interface" :: String),
                                     "registration" .= installedProvenance context registrationUnit],
              "rtsRegistration" .= rtsRegistration,
              "recipeArtifacts" .= [object ["path" .= ("src/driver/cbits/target-layout.c" :: String),
                                             "sha256" .= recipeHash]],
              "nativeArtifacts" .= nativeArtifacts,
              "generatedCore" .= [object ["module" .= name, "sha256" .= shaHex bytes] | (name, bytes) <- modules],
              "dependencies" .= installedDepends registrationUnit]
            buildKey = shaHex (BL.toStrict (encode (object inputFields)))
            exporter = object ["helperHash" .= helperHash, "driverHash" .= driverHash,
              "options" .= (["post-tidy", "unit-qualified", "source-notes", "dynamic"] :: [String]),
              "foreignLinkRecipe" .= ("installed-native-fcall-v1" :: String)]
            exportKey = shaHex (BL.toStrict (encode ("thc-installed-interface-v2" :: String, buildKey, exporter)))
            inputs = object (inputFields ++ ["buildKey" .= buildKey, "exportKey" .= exportKey, "exporter" .= exporter])
            directory = cache </> "core-bundles/v1" </>
              (compilerId ++ "-" ++ compilerAbi ++ "-" ++ compilerPlatform) </> exportKey
            destination = directory </> (registered ++ ".zip")
        createDirectoryIfMissing True directory
        bundle <- withLock (destination ++ ".lock") $ do
          present <- doesFileExist destination
          cached <- if present then readBundle verify TargetLayoutBundle destination unit buildKey exportKey inputs (map fst modules)
                    else pure Nothing
          case cached of
            Just hit -> pure hit
            Nothing -> do
              layout <- readJson =<< probeTargetLayout includes recipe temporary
              require (validTargetLayout layout &&
                       jsonField layout "targetPlatform" == Just compilerPlatform)
                "installed GHC target layout differs from selected compiler"
              (refs, members) <- packageModules
                [(name, "core/" ++ show index ++ ".json", bytes)
                | (index, (name, bytes)) <- zip [0 :: Int ..] linked]
              let receiptBytes = BL.toStrict (encode (object (inputFields ++
                    ["buildKey" .= buildKey, "exportKey" .= exportKey,
                     "exporter" .= exporter, "targetLayout" .= layout])))
                  inner = object ["format" .= ("thc-core-bundle" :: String), "schema" .= (1 :: Int),
                    "unit" .= unit, "buildKey" .= buildKey, "exportKey" .= exportKey,
                    "targetLayout" .= layout, "modules" .= refs,
                    "buildInputs" .= object ["path" .= ("inplace-manifest.json" :: String),
                                             "sha256" .= shaHex receiptBytes]]
              archive <- either fail pure (encodeZip
                (("manifest.json", BL.toStrict (encode inner)) :
                 ("inplace-manifest.json", receiptBytes) : members))
              -- Keep an existing file intact until the complete replacement is ready.
              atomicBytes destination (BL.toStrict archive)
              rememberFreshBundle TargetLayoutBundle unit exportKey inputs (map fst modules)
                (Bundle destination (shaHex (BL.toStrict archive)) refs buildKey [])
        let result = InstalledBundle unit bundle
        remember result inputs modules
        pure (Right result)) `finally` removePathForcibly temporary

-- Reuse the compiler's actual dependency inventory. Generated translation units
-- are embedded in the component receipt; only external files survive staging
-- and belong in the existing installed-probe invalidation path.
installedNativeArtifacts :: FilePath -> IO [Value]
installedNativeArtifacts directory = do
  let path = directory </> "native/inputs.json"
  exists <- doesFileExist path
  if not exists then pure [] else do
    root <- canonicalizePath directory
    inputs <- readJson path
    sources <- field inputs "sources" :: IO [Value]
    files <- concat <$> mapM (\source -> field source "files") sources :: IO [Value]
    external <- filterM (\file -> not . within root <$> (field file "path" :: IO FilePath)) files
    let unique = nub external
    forM_ unique $ \file -> do
      filePath <- field file "path"
      expected <- field file "sha256"
      actual <- digestFile filePath
      require (actual == expected) "native header changed during installed acquisition"
    pure unique

installedRecords :: InstalledUnit -> InstalledBundle -> [Value]
installedRecords registrationUnit artifact =
  [object ["id" .= registeredId registrationUnit, "depends" .= installedDepends registrationUnit,
           "modules" .= ([] :: [Value])] | installedOwner artifact /= registeredId registrationUnit] ++
  [object ["id" .= installedOwner artifact, "depends" .= installedDepends registrationUnit,
           "modules" .= bundleModules bundle,
           "bundle" .= object ["path" .= bundlePath bundle, "sha256" .= bundleHash bundle]]]
  where bundle = installedBundle artifact

-- Installed ghc-internal interfaces omit executable unfoldings needed by
-- ordinary fail/catch, Typeable, and CString. Re-export original pinned source
-- under its wired unit. This supplies genuine Core; the strict audit still
-- rejects unsupported RTS stack-snapshot operations until they are implemented.
wiredGhcInternal :: ExportContext -> FilePath -> IO Value
wiredGhcInternal context thcRoot = do
  when (Host.os == "mingw32") $ require (contextPlatform context == "x86_64-windows")
    "The pinned native Windows source recipe requires x86_64-windows"
  windowsSpec <- if Host.os == "mingw32" then Just <$> readJson (thcRoot </> "etc/ghc/9.14.1/windows-ghc-internal.json") else pure Nothing
  names <- maybe (pure (map snd moduleSources)) (`field` "modules") windowsSpec
  let packagePath = "nih/pinned/ghc-9.14.1/libraries/ghc-internal"
  sources <- maybe (pure [(name, packagePath </> pinnedSourcePath name, digest) | (name, digest) <- sourceHashes]) (\spec -> do
    files <- field spec "files"
    forM files $ \item -> do
      name <- field item "path"
      path <- optionalField item "source" (packagePath </> name)
      digest <- field item "sha256"
      pure (name, path, digest)) windowsSpec
  let pinned = thcRoot </> packagePath
      layoutRecipe = thcRoot </> "src/driver/cbits/target-layout.c"
      unit = "ghc-internal" :: String
      sourceArtifact (_, path, digest) = object ["path" .= path, "sha256" .= digest]
      generatedPaths = sort [name | (name, _, _) <- sources, takeExtension name == ".hsc"]
      verifySources = forM_ sources $ \(name, path, expected) -> do
        require (not (isAbsolute path) && all (`notElem` [".", ".."]) (splitDirectories path))
          "Unsafe pinned GHC source path"
        requireFile (thcRoot </> path)
        actual <- digestFile (thcRoot </> path)
        require (actual == expected) ("pinned GHC 9.14.1 source changed: " ++ name)
  when (Host.os /= "mingw32") verifySources
  requireFile layoutRecipe
  recipeHash <- digestFile layoutRecipe
  pluginHash <- digestFile (contextPluginLibrary context)
  helper <- if Host.os == "mingw32" then Just <$> prepareInterfaceHelper context thcRoot else pure Nothing
  helperHash <- traverse (digestFile . installedHelper) helper
  let inputFields = ["format" .= ("thc-core-build-inputs" :: String), "schema" .= (1 :: Int),
                     "unit" .= unit,
                     "compiler" .= object ["id" .= contextCompiler context,
                                           "abi" .= contextAbi context,
                                           "platform" .= contextPlatform context,
                                           "way" .= (exportInterfaceWay ++ "-nonprofiling")],
                     "component" .= object (["kind" .= ("pinned-wired-source" :: String),
                                             "modules" .= names, "generatedSources" .= generatedPaths] ++
                       ["sourceGraph" .= spec | Just spec <- [windowsSpec]]),
                     "nativeArtifacts" .= ([] :: [Value]),
                     "sourceArtifacts" .= map sourceArtifact sources,
                     "recipeArtifacts" .= [object
                       ["path" .= ("src/driver/cbits/target-layout.c" :: String),
                        "sha256" .= recipeHash]],
                     "dependencies" .= ([] :: [Value])]
      buildKey = shaHex (BL.toStrict (encode (object inputFields)))
      exporter = object (["pluginUnit" .= contextPluginUnit context,
                         "pluginDb" .= contextPluginDb context,
                         "pluginHash" .= pluginHash,
                         "driverHash" .= contextDriverHash context,
                         "options" .= (["ghc-internal-source-closure-v2", "post-tidy",
                                        "source-notes", "foreign-import-provenance",
                                        "foreign-export-associations", "foreign-export-registration",
                                        "hsc2hs", "-g"] ++
                           (if Host.os == "mingw32" then ["compiler-source-graph", "-O2", "-fwrite-if-simplified-core"]
                            else exportWayOptions) ++ ["-dcore-lint", "-XNoPolyKinds"])] ++
                         ["vanillaInterfaceHelperHash" .= value | Just value <- [helperHash]])
      exportKey = shaHex (BL.toStrict (encode ("thc-wired-ghc-internal-v2" :: String,
                                             buildKey, exporter)))
      buildInputs = object (inputFields ++ ["buildKey" .= buildKey,
                                           "exportKey" .= exportKey, "exporter" .= exporter])
      directory = (if Host.os == "mingw32" then contextNative context </> "bundles"
                   else contextCache context </> "core-bundles/v1" </>
                     (contextCompiler context ++ "-" ++ contextAbi context ++ "-" ++ contextPlatform context)) </> exportKey
      destination = directory </> if Host.os == "mingw32" then "unit.zip"
                    else "ghc-internal-" ++ buildKey ++ ".zip"
  createDirectoryIfMissing True directory
  bundle <- withLock (destination ++ ".lock") $ do
    cached <- doesFileExist destination
    hit <- if cached then readBundle (contextVerifyArtifacts context) PinnedSourceBundle destination unit buildKey exportKey buildInputs (sort names)
           else pure Nothing
    case hit of
      Just value -> pure value
      Nothing -> do
        when cached (removeFile destination)
        let stagingRoot = contextNative context </> "cache/thc/staging"
        createDirectoryIfMissing True stagingRoot
        (staging, handle) <- openTempFile stagingRoot "wired-export-"
        hClose handle
        removeFile staging
        createDirectory staging
        let cleanup = do exists <- doesDirectoryExist staging
                         when exists (removePathForcibly staging)
        (do
          let packageTool = maybe (takeDirectory (contextGhc context) </> "ghc-pkg") id
                              (contextGhcPkg context)
          requireFile packageTool
          artifacts <- case helper of
            Nothing -> exportPinnedCore pinned (contextGhc context) packageTool
              (contextPluginLibrary context) (contextPluginUnit context) layoutRecipe staging
            Just selectedHelper -> do
              verifySources
              exportPinnedWindowsCore pinned [name | (name, _, _) <- sources] (contextGhc context) packageTool
                (installedHelper selectedHelper) names layoutRecipe staging
          layout <- readJson (targetLayout artifacts)
          require (validTargetLayout layout &&
                   jsonField layout "targetPlatform" == Just (contextPlatform context))
                  "GHC target layout receipt differs from compiler target"
          generated <- forM (generatedSources artifacts) $ \(name, path) -> do
            digest <- digestFile path
            pure (object ["path" .= map (\c -> if c == '\\' then '/' else c) name, "sha256" .= digest])
          originalMembers <- forM names $ \name -> do
            let core = staging </> "core" </> (name ++ ".json")
                member = "core/" ++ name ++ ".json"
            artifact <- readJson core
            foundUnit <- field artifact "unit"
            foundName <- field artifact "module"
            foundBoundary <- field artifact "boundary"
            require (foundUnit == unit && foundName == name && foundBoundary == boundary)
              ("pinned wired Core has wrong identity: " ++ name)
            bytes <- BS.readFile core
            pure (member, bytes)
          (refs, members) <- packageModules
            [(name, member, bytes) | (name, (member, bytes)) <- zip names originalMembers]
          let derived = ["targetLayout" .= layout, "generatedSources" .= generated] ++
                ["sourceBuild" .= value | Just value <- [sourceBuildReceipt artifacts]]
              inputsBytes = BL.toStrict (encode (object
                (inputFields ++ ["buildKey" .= buildKey, "exportKey" .= exportKey,
                                 "exporter" .= exporter] ++ derived)))
              inner = object ["format" .= ("thc-core-bundle" :: String),
                              "schema" .= (1 :: Int), "unit" .= unit,
                              "buildKey" .= buildKey, "exportKey" .= exportKey,
                              "targetLayout" .= layout,
                              "generatedSources" .= generated,
                              "modules" .= refs,
                              "buildInputs" .= object
                                ["path" .= ("inplace-manifest.json" :: String),
                                 "sha256" .= shaHex inputsBytes]]
          archive <- either fail pure (encodeZip
            (("manifest.json", BL.toStrict (encode inner)) :
             ("inplace-manifest.json", inputsBytes) : members))
          atomicBytes destination (BL.toStrict archive)
          rememberFreshBundle PinnedSourceBundle unit exportKey buildInputs (sort names)
            (Bundle destination (shaHex (BL.toStrict archive)) refs buildKey []))
          `finally` cleanup
  selected <- maybe (pure bundle) (projectWindowsWiredBundle (contextVerifyArtifacts context) directory bundle) windowsSpec
  pure (object ["id" .= unit, "depends" .= ([] :: [String]),
                "modules" .= bundleModules selected,
                "bundle" .= object ["path" .= bundlePath selected,
                                   "sha256" .= bundleHash selected]])

-- Compilation dependencies do not implicitly load native registration code.
-- Keep the complete genuine archive, and make a separately hashed module-level
-- support projection. No binding, branch, foreign artifact or audit is edited.
-- Loading the excluded complete module still encounters its unsupported
-- registration boundary; it is never advertised as a complete supplied module.
-- | Reuse or construct the explicitly selected Windows module projection.
-- An unchanged successful projection is checked before opening the full ZIP.
projectWindowsWiredBundle :: Bool -> FilePath -> Bundle -> Value -> IO Bundle
projectWindowsWiredBundle verify directory full specification = do
  exclusions <- field specification "archiveOnlyModules" :: IO [Value]
  excluded <- mapM (`field` "module") exclusions :: IO [String]
  let refs = [ref | ref <- bundleModules full, (jsonField ref "name" :: Maybe String) `notElem` map Just excluded]
      names = [name | Just name <- map (`jsonField` "name") refs] :: [String]
      fullNames = [name | Just name <- map (`jsonField` "name") (bundleModules full)] :: [String]
      source = object ["path" .= bundlePath full, "sha256" .= bundleHash full]
      key = shaHex (BL.toStrict (encode ("windows-runtime-module-projection-v1" :: String, source, refs, exclusions)))
      path = directory </> "support.zip"
  require (length excluded == length (nub excluded) && all (`elem` fullNames) excluded)
    "Windows source projection has an invalid excluded-module inventory"
  let request = object ["kind" .= ("windows-source-projection" :: String), "source" .= source,
        "key" .= key, "modules" .= refs, "exclusions" .= exclusions]
  selected <- rememberBundle verify (path ++ ".projection.json") [bundlePath full, path] request $
    Just <$> projectWindowsWiredBundleCold verify path full refs names excluded key source exclusions
  maybe (fail "Windows source projection unavailable") pure selected

projectWindowsWiredBundleCold :: Bool -> FilePath -> Bundle -> [Value] -> [String] -> [String] -> String -> Value -> [Value] -> IO Bundle
projectWindowsWiredBundleCold verify path full refs names excluded key source exclusions = do
  bytes <- BS.readFile (bundlePath full)
  require (shaHex bytes == bundleHash full) "Complete source bundle changed before projection"
  entries <- either fail pure =<< decodeZip bytes
  inner <- maybe (fail "Complete source bundle has no manifest") (either fail pure . eitherDecodeStrict') (lookup "manifest.json" entries)
  layout <- field inner "targetLayout" :: IO Value
  originalInputs <- maybe (fail "Complete source bundle has no build inputs") (either fail pure . eitherDecodeStrict')
    (lookup "inplace-manifest.json" entries)
  compiler <- field originalInputs "compiler" :: IO Value
  component <- field originalInputs "component" :: IO Value
  generated <- field originalInputs "generatedSources" :: IO [Value]
  let inputs = object ["format" .= ("thc-core-build-inputs" :: String), "schema" .= (1 :: Int),
        "unit" .= ("ghc-internal" :: String), "buildKey" .= key, "exportKey" .= key, "compiler" .= compiler,
        "component" .= component,
        "completeSourceBundle" .= source, "archiveOnlyModules" .= exclusions, "modules" .= names]
  -- Exclusions are explicit native lifecycle boundaries, not a convenient way
  -- to hide an ordinary missing dependency or unsupported primop.
  forM_ excluded $ \name -> do
    ref <- case [value | value <- bundleModules full, jsonField value "name" == Just name] of
      [value] -> pure value
      _ -> fail "Ambiguous archived module"
    member <- field ref "path"
    body <- maybe (fail "Missing archived module") pure (lookup member entries)
    value <- either fail pure (eitherDecodeStrict' body)
    foreignCode <- field value "foreign"
    stubs <- field foreignCode "stubs"
    initializers <- field stubs "initializers" :: IO [Value]
    require (not (null initializers)) "Excluded Windows module lacks its recorded native registration obligation"
  withLock (path ++ ".lock") $ do
    cached <- doesFileExist path
    hit <- if cached then readBundle verify PinnedSourceBundle path "ghc-internal" key key inputs (sort names) else pure Nothing
    case hit of
      Just value -> pure value
      Nothing -> do
        members <- fmap concat $ forM refs $ \ref ->
          maybe (fail "Projected Core/index pair differs from genuine source archive") pure
            (moduleEntries ref entries)
        let inputBytes = BL.toStrict (encode (case inputs of
              Object fields -> Object (KeyMap.insert "generatedSources" (Aeson.toJSON generated) (KeyMap.insert "targetLayout" layout fields))
              _ -> inputs))
            manifest = object ["format" .= ("thc-core-bundle" :: String), "schema" .= (1 :: Int),
              "unit" .= ("ghc-internal" :: String), "buildKey" .= key, "exportKey" .= key,
              "targetLayout" .= layout, "generatedSources" .= generated, "modules" .= refs,
              "buildInputs" .= object ["path" .= ("inplace-manifest.json" :: String), "sha256" .= shaHex inputBytes]]
        archive <- either fail pure (encodeZip (("manifest.json", BL.toStrict (encode manifest)) :
          ("inplace-manifest.json", inputBytes) : members))
        atomicBytes path (BL.toStrict archive)
        pure (Bundle path (shaHex (BL.toStrict archive)) refs key [])

-- Cabal locks its own build tree; this also keeps the THC cache and the
-- published package manifest coherent for concurrent runs of one project.
withProjectLock :: FilePath -> IO a -> IO a
withProjectLock output = withLock (output </> ".lock")


readUnit :: Value -> IO Unit
readUnit value = do
  identifier <- field value "id"
  dependencies <- case jsonField value "depends" :: Maybe Value of
    Just _ -> field value "depends"
    Nothing -> case jsonField value "components" :: Maybe Value of
      Nothing -> pure []
      Just components -> do
        -- Cabal's non-per-component library records (including Custom Setup)
        -- group runtime dependencies under lib. Setup runs on the host and its
        -- separate dependency graph must not become part of the guest closure.
        library <- field components "lib"
        field library "depends"
  kind <- optionalField value "type" ("" :: String)
  style <- optionalField value "style" ("" :: String)
  pure (Unit identifier value dependencies (kind == "configured" && style == "local"))

selectRunnable :: (Maybe String, String) -> [Unit] -> IO Unit
selectRunnable target@(wantedPackage, wantedComponent) units = do
  matches <- filterM (\unit -> if not (unitLocal unit) then pure False else do
    component <- optionalField (unitValue unit) "component-name" ("" :: String)
    package <- optionalField (unitValue unit) "pkg-name" ("" :: String)
    pure (component == wantedComponent && maybe True (== package) wantedPackage)) units
  case matches of
    [unit] -> pure unit
    _ -> fail ("selected runnable component " ++ show target ++ " has " ++ show (length matches) ++
               " matching local Cabal components")

dependencyClosure :: Map.Map String Unit -> String -> IO [Unit]
dependencyClosure units target = snd <$> visit Set.empty Set.empty target
  where
    visit active seen identifier = do
      require (identifier `Set.notMember` active) ("Cabal dependency cycle at " ++ identifier)
      case Map.lookup identifier units of
        Nothing -> fail ("Cabal plan lacks dependency unit " ++ identifier)
        Just unit | identifier `Set.member` seen -> pure (seen, [])
                  | otherwise -> do
            (visited, dependencies) <- foldlM (\(found, ordered) dependency -> do
              (more, result) <- visit (Set.insert identifier active) found dependency
              pure (more, ordered ++ result)) (seen, []) (unitDepends unit)
            pure (Set.insert identifier visited, dependencies ++ [unit])

-- Cabal's global store ID includes its source/configuration hash. A repository
-- package built inplace can retain its ID while a project dependency changes.
globalLocation :: ExportContext -> Map.Map String Unit -> Map.Map String Value -> Unit -> IO (String, String, FilePath)
globalLocation context units inputs unit = do
  sourceHash <- field (unitValue unit) "pkg-src-sha256" :: IO String
  require (length sourceHash == 64 && all isHexDigit sourceHash &&
           all (\c -> isAlphaNum c || c `elem` ("-._" :: String)) (unitId unit))
    ("Cabal store source identity is invalid for " ++ unitId unit)
  style <- field (unitValue unit) "style" :: IO String
  require (style `elem` ["global", "inplace"])
    ("unsupported source-built Cabal package style for " ++ unitId unit ++ ": " ++ style)
  dependencies <- dependencyClosure units (unitId unit)
  dependencyInputs <- forM (filter (\dependency -> unitLocal dependency || sourceInplace dependency) dependencies) $ \dependency ->
    maybe (fail ("missing inplace dependency inputs for " ++ unitId dependency)) pure
      (Map.lookup (unitId dependency) inputs)
  require (style == "inplace" || null dependencyInputs)
    ("global Cabal store unit depends on project-local content: " ++ unitId unit)
  let globalKey = shaHex (BL.toStrict (encode
        ("thc-core-store-build-v1" :: String, contextCompiler context,
         contextAbi context, contextPlatform context, unitId unit,
         sourceHash, unitDepends unit, contextNativeTools context)))
      -- Repository packages depending on a project library are Cabal 'inplace'
      -- builds, not immutable store IDs. Their key must follow that library's
      -- actual configured/native inputs and every intervening package identity.
      buildKey = if style == "global" then globalKey else shaHex (BL.toStrict (encode
        ("thc-core-inplace-source-build-v1" :: String, globalKey,
         map sourceIdentity dependencies, dependencyInputs)))
  exporter <- exporterIdentity context
  let exportKey = shaHex (BL.toStrict (encode
        ("thc-core-export-v1" :: String, buildKey, exporter)))
      destination = contextCache context </> "core-bundles/v1" </>
        (contextCompiler context ++ "-" ++ contextAbi context ++ "-" ++ contextPlatform context) </>
        exportKey </> (unitId unit ++ ".zip")
  pure (buildKey, exportKey, destination)

sourceInplace :: Unit -> Bool
sourceInplace unit = jsonField (unitValue unit) "style" == Just ("inplace" :: String)

sourceIdentity :: Unit -> Value
sourceIdentity unit = object ["unit" .= unitId unit, "depends" .= unitDepends unit,
  "configuration" .= object [Key.fromString key .= (jsonField (unitValue unit) key :: Maybe Value) |
    key <- ["type", "style", "pkg-name", "pkg-version", "flags", "component-name",
            "pkg-src-sha256", "pkg-cabal-sha256"]]]

exporterIdentity :: ExportContext -> IO Value
exporterIdentity context = do
  pluginHash <- digestFile (contextPluginLibrary context)
  pure $ object ["pluginUnit" .= contextPluginUnit context,
                 "pluginDb" .= contextPluginDb context,
                 "pluginHash" .= pluginHash,
                 "driverHash" .= contextDriverHash context,
                 "options" .= (["post-tidy", "unit-qualified", "source-notes",
                                "foreign-import-provenance", "foreign-export-associations", "foreign-export-registration",
                                "native-debug-info", "-dynamic", "-dcore-lint",
                                "-fplugin-trustworthy"] :: [String])]

prepareGlobalBundles :: ExportContext -> FilePath -> String -> Map.Map String Unit -> [(Unit, Component)] -> [Unit] -> IO (Map.Map String Bundle)
prepareGlobalBundles _ _ _ _ _ [] = pure Map.empty
prepareGlobalBundles context project target planned locals units = do
  let lockDir = contextCache context </> "core-bundles/v1/export-batches"
  createDirectoryIfMissing True lockDir
  closures <- mapM (dependencyClosure planned . unitId) units
  let localIds = Set.fromList [unitId unit | unit <- concat closures, unitLocal unit]
      inplaceUnits = Map.elems (Map.fromList
        [(unitId unit, unit) | unit <- concat closures, sourceInplace unit])
      observeInputs = do
        localInputs <- forM (filter (\(unit, _) -> unitId unit `Set.member` localIds) locals) $ \(unit, component) -> do
          artifacts <- componentNativeInputs context unit component
          sources <- forM (componentSources component) $ \(name, path) -> do
            digest <- digestFile path
            pure (name, digest)
          pure (unitId unit, object ["unit" .= unitId unit,
            "component" .= normalizePaths (contextNative context) (componentValue component),
            "nativeArtifacts" .= artifacts, "sources" .= sources])
        sourceInputs <- forM inplaceUnits $ \unit -> do
          artifacts <- sourceNativeInputs context unit
          pure (unitId unit, object ["unit" .= unitId unit, "nativeInputs" .= artifacts])
        pure (Map.fromList (localInputs ++ sourceInputs))
  inputs <- observeInputs
  located <- forM units $ \unit -> do
    (buildKey, exportKey, path) <- globalLocation context planned inputs unit
    cached <- doesFileExist path
    hit <- if cached then readGlobalBundle (contextVerifyArtifacts context) path (unitId unit) (unitDepends unit) buildKey exportKey
           else pure Nothing
    pure (unit, buildKey, exportKey, path, hit)
  let missing = [(unit, buildKey, exportKey, path)
                | (unit, buildKey, exportKey, path, Nothing) <- located]
      validateInputs = do
        currentInputs <- observeInputs
        forM_ located $ \(unit, buildKey, _, _, _) -> do
          (currentKey, _, _) <- globalLocation context planned currentInputs unit
          require (currentKey == buildKey)
            ("project dependency inputs changed during capture for " ++ unitId unit)
  when (not (null missing)) $ do
    -- Only identical missing batches serialize the expensive private Cabal
    -- build. Unrelated projects do not wait behind a cache-wide export lock.
    let batch = shaHex (BL.toStrict (encode (sort [key | (_, _, key, _) <- missing])))
    withLock (lockDir </> batch <.> "lock") $ do
      pending <- filterM (\(unit, buildKey, exportKey, path) -> do
        present <- doesFileExist path
        ready <- if present then readGlobalBundle (contextVerifyArtifacts context) path (unitId unit) (unitDepends unit) buildKey exportKey
                 else pure Nothing
        pure (case ready of Nothing -> True; Just _ -> False)) missing
      when (not (null pending)) $
        captureGlobalUnits context project target planned (map first4 pending) pending validateInputs
  -- Check warm hits too, before they enter the combined manifest.
  validateInputs
  pairs <- forM located $ \(unit, buildKey, exportKey, path, hit) -> do
    bundle <- case hit of
      Just value -> pure value
      Nothing -> do
        ready <- readGlobalBundle (contextVerifyArtifacts context) path (unitId unit) (unitDepends unit) buildKey exportKey
        maybe (fail ("Cabal store Core bundle was not published: " ++ unitId unit)) pure ready
    pure (unitId unit, bundle)
  pure (Map.fromList pairs)
  where first4 (unit, _, _, _) = unit

captureGlobalUnits :: ExportContext -> FilePath -> String -> Map.Map String Unit -> [Unit] ->
                      [(Unit, String, String, FilePath)] -> IO () -> IO ()
captureGlobalUnits context project target planned requested missing validateInputs = do
  helper <- prepareInterfaceHelper context (contextRoot context)
  let stagingRoot = contextNative context </> "cache/thc/staging"
  createDirectoryIfMissing True stagingRoot
  (staging, handle) <- openTempFile stagingRoot "store-export-"
  hClose handle
  removeFile staging
  createDirectory staging
  let cleanup = do exists <- doesDirectoryExist staging
                   when exists (removePathForcibly staging)
  (do
    let wrapper = staging </> "ghc-proxy.sh"
        store = staging </> "store"
        dist = staging </> "dist"
        capture = staging </> "capture"
        -- Cabal's offline mode rejects Hackage sources in a fresh store even
        -- when their tarballs are cached; use its normal source cache here.
        -- Rebuild only the selected runnable component's closure. `all` also builds
        -- unrelated tests/apps and their dependencies in this fresh store.
        arguments = ["--store-dir=" ++ store, "build", target,
                     "--enable-build-info", "--builddir", dist, "--with-compiler", wrapper] ++
                    contextProjectOptions context ++
                    maybe [] (\path -> ["--with-hc-pkg", path]) (contextGhcPkg context)
    writeFile wrapper ("#!/bin/sh\n" ++ ghcProxyCommand)
    permissions <- getPermissions wrapper
    setPermissions wrapper (permissions {Directory.executable = True})
    inherited <- getEnvironment
    let overrides = [("THC_PROXY_DRIVER", contextDriver context),
                     ("THC_PROXY_ROOT", contextRoot context),
                     ("THC_PROXY_GHC", contextGhc context),
                     ("THC_PROXY_CAPTURE", capture),
                     ("THC_PROXY_PLUGIN_DB", contextPluginDb context),
                     ("THC_PROXY_PLUGIN_UNIT", contextPluginUnit context),
                     ("THC_PROXY_PLUGIN_LIBRARY", contextPluginLibrary context),
                     ("THC_PROXY_NO_LINK_UNIT", maybe "" id (contextNoLinkUnit context)),
                     ("THC_PROXY_NATIVE_PIECES", staging </> "native-pieces"),
                     ("THC_PROXY_INTERFACE_HELPER", installedHelper helper),
                     ("THC_PROXY_INTERFACE_LIBDIR", installedLibdir helper),
                     ("THC_PROXY_GLOBAL_UNITS", unlines (map unitId requested))]
        environment = overrides ++ filter (\(key, _) -> key `notElem` map fst overrides) inherited
    runCommandWithEnv True "cabal" arguments project (Just environment)
    plan <- readJson (dist </> "cache/plan.json")
    isolatedCompiler <- field plan "compiler-id"
    isolatedAbi <- field plan "compiler-abi"
    isolatedOs <- field plan "os"
    isolatedArch <- field plan "arch"
    require (isolatedCompiler == contextCompiler context && isolatedAbi == contextAbi context &&
             isolatedArch ++ "-" ++ isolatedOs == contextPlatform context)
      "isolated Cabal export build changed compiler or platform"
    isolated <- mapM readUnit =<< field plan "install-plan"
    let byId = Map.fromList [(unitId unit, unit) | unit <- isolated]
    closures <- mapM (dependencyClosure planned . unitId) requested
    let dependencies = Map.elems (Map.fromList [(unitId unit, unit) | unit <- concat closures])
    forM_ dependencies $ \unit -> do
      rebuilt <- maybe (fail ("isolated Cabal plan omitted dependency " ++ unitId unit)) pure
                 (Map.lookup (unitId unit) byId)
      require (sourceIdentity unit == sourceIdentity rebuilt)
        ("isolated Cabal build changed dependency identity for " ++ unitId unit)
    -- An input edit must not leave a captured ZIP under the old cache key,
    -- even when the combined manifest would subsequently be rejected.
    validateInputs
    forM_ missing $ \(unit, buildKey, exportKey, path) -> do
      rebuilt <- maybe (fail ("isolated Cabal plan omitted store unit " ++ unitId unit)) pure
                 (Map.lookup (unitId unit) byId)
      kind <- field (unitValue rebuilt) "type" :: IO String
      style <- field (unitValue rebuilt) "style" :: IO String
      require (kind == "configured" && style `elem` ["global", "inplace"])
        ("isolated Cabal build changed store identity for " ++ unitId unit)
      createDirectoryIfMissing True (takeDirectory path)
      withLock (path ++ ".lock") $ do
        -- Overlapping batches can independently capture the same dependency.
        -- Keep the first valid publication byte-for-byte: a prior manifest may
        -- already refer to its hash, even if another capture is equivalent.
        present <- doesFileExist path
        ready <- if present then readGlobalBundle (contextVerifyArtifacts context) path (unitId unit) (unitDepends unit) buildKey exportKey
                 else pure Nothing
        case ready of
          Just _ -> pure ()
          Nothing -> packGlobalBundle store dist capture byId unit buildKey exportKey path
    ) `onException` do
      _ <- tryIOError (hPutStrLn stderr ("Cabal store capture retained after failure: " ++ staging))
      pure ()
  cleanup

-- | Publish exactly one already captured store unit using its genuine Cabal
-- plan, interfaces/Core and native products. No package solve, compiler replay
-- or acquisition of unrelated units is performed here. The output must be new.
publishCapturedStoreUnit :: FilePath -> FilePath -> FilePath -> FilePath -> String -> FilePath -> IO Bundle
publishCapturedStoreUnit planPath store dist capture identifier destination = do
  selectedStore <- makeAbsolute store
  selectedDist <- makeAbsolute dist
  selectedCapture <- makeAbsolute capture
  selectedDestination <- makeAbsolute destination
  present <- doesFileExist destination
  require (not present) "captured store publication refuses an existing output"
  plan <- readJson planPath
  compiler <- field plan "compiler-id" :: IO String
  abi <- field plan "compiler-abi" :: IO String
  platform <- (,) <$> (field plan "arch" :: IO String) <*> (field plan "os" :: IO String)
  units <- mapM readUnit =<< field plan "install-plan"
  let planned = Map.fromList [(unitId unit,unit) | unit <- units]
  require (Map.size planned == length units) "captured Cabal plan contains duplicate units"
  unit <- maybe (fail "captured Cabal plan lacks requested unit") pure (Map.lookup identifier planned)
  closure <- dependencyClosure planned identifier
  kind <- field (unitValue unit) "type" :: IO String
  require (kind == "configured" && not (unitLocal unit)) "captured publication requires a store unit"
  producer <- digestFile =<< getExecutablePath
  let buildKey = shaHex (BL.toStrict (encode
        ("thc-captured-store-native-build-v1" :: String,compiler,abi,platform,map sourceIdentity closure)))
      exportKey = shaHex (BL.toStrict (encode (buildKey,producer)))
  createDirectoryIfMissing True (takeDirectory destination)
  packGlobalBundle selectedStore selectedDist selectedCapture planned unit buildKey exportKey selectedDestination
  result <- readGlobalBundle True selectedDestination identifier (unitDepends unit) buildKey exportKey
  maybe (fail "new captured unit failed its ordinary bundle validation") pure result

packGlobalBundle :: FilePath -> FilePath -> FilePath -> Map.Map String Unit -> Unit -> String -> String -> FilePath -> IO ()
packGlobalBundle store dist capture planned unit buildKey exportKey destination = do
  let core = capture </> unitId unit </> "core"
  exported <- filter ((== ".json") . takeExtension) <$> recursiveFiles core
  empty <- if not (null exported) then pure [] else do
    -- Inspect only this freshly rebuilt private store, never the original
    -- native store or a guessed empty module. Cabal owns the compiler partition.
    style <- field (unitValue unit) "style" :: IO String
    let databaseRoot = if style == "inplace" then dist </> "packagedb" else store
    partitions <- listDirectory databaseRoot
    registrations <- filterM doesFileExist
      [(if style == "inplace" then databaseRoot </> partition
        else databaseRoot </> partition </> "package.db") </> unitId unit <.> "conf" |
        partition <- partitions]
    bytes <- case registrations of
      [path] -> BS.readFile path
      _ -> fail ("isolated Cabal store lacks a unique registration for " ++ unitId unit)
    reexports <- maybe (fail ("Cabal store build did not export Core for nonempty unit " ++ unitId unit)) pure
      (modulelessRegistration (unitId unit) (unitDepends unit) bytes)
    pure [(if null reexports then "emptyRegistration" else "reexportRegistration") .= Text.decodeUtf8 bytes]
  checked <- forM exported $ \path -> do
    value <- readJson path
    foundUnit <- field value "unit"
    foundBoundary <- field value "boundary" :: IO String
    name <- field value "module" :: IO String
    require (foundUnit == unitId unit && foundBoundary == boundary)
      ("store Core artifact has wrong owner or boundary: " ++ path)
    bytes <- BS.readFile path
    pure (name, bytes)
  let pieces = takeDirectory capture </> "native-pieces"
  dependencies <- fmap concat $ forM (unitDepends unit) $ \identifier -> do
    dependency <- maybe (fail "native dependency is absent from resolved plan") pure (Map.lookup identifier planned)
    -- Only registrations in this acquisition's private store can add products.
    -- Installed boot libraries and Haskell-bearing dependencies remain outside
    -- this C-only path; their runtime/provider contracts are separate.
    partitions <- listDirectory store
    registrations <- filterM doesFileExist
      [store </> partition </> "package.db" </> identifier <.> "conf" | partition <- partitions]
    case registrations of
      [] -> pure []
      [path] -> maybe [] (:[]) <$> readCOnlyProduct (unitValue dependency) path pieces
      _ -> fail "C-only dependency has ambiguous private-store registration"
  -- Cabal has installed these units and may have deleted their temporary
  -- intra-package DBs. Keep the recorded compiler recipe unchanged, but resolve
  -- its native dependency closure against this completed private build.
  let databases directory suffix = do
        exists <- doesDirectoryExist directory
        if not exists then pure [] else do
          partitions <- listDirectory directory
          filterM doesDirectoryExist [directory </> partition </> suffix | partition <- partitions]
  storeDatabases <- databases store "package.db"
  inplaceDatabases <- databases (dist </> "packagedb") ""
  linked <- finishPackageNativeWithDependencies dependencies (storeDatabases ++ inplaceDatabases)
    pieces (capture </> unitId unit) (unitId unit) Nothing checked
  let sorted = sortOn fst linked
      names = map fst sorted
  require (length names == length (nub names))
    ("duplicate exported store modules for " ++ unitId unit)
  (modules, members) <- packageModules
    [(name, "core/" ++ show index ++ ".json", bytes)
    | (index, (name, bytes)) <- zip [0 :: Int ..] sorted]
  let inner = object (["format" .= ("thc-core-bundle" :: String), "schema" .= (1 :: Int),
                      "unit" .= unitId unit, "buildKey" .= buildKey,
                      "exportKey" .= exportKey, "modules" .= modules] ++ empty)
  archive <- either fail pure (encodeZip (("manifest.json", BL.toStrict (encode inner)) : members))
  atomicBytes destination (BL.toStrict archive)

-- | Read a source-store bundle under exact unit/dependency/build/export keys.
-- True always verifies its archive; False can replay a successful selection.
readGlobalBundle :: Bool -> FilePath -> String -> [String] -> String -> String -> IO (Maybe Bundle)
readGlobalBundle verify path unit dependencies buildKey exportKey =
  rememberBundle verify (path ++ ".selection.json") [path]
    (Aeson.toJSON ("global" :: String, unit, dependencies, buildKey, exportKey)) $
      readGlobalBundleCold path unit dependencies buildKey exportKey

readGlobalBundleCold :: FilePath -> String -> [String] -> String -> String -> IO (Maybe Bundle)
readGlobalBundleCold path unit dependencies buildKey exportKey = do
  bytes <- BS.readFile path
  decoded <- decodeZip bytes
  pure $ do
    entries <- either (const Nothing) Just decoded
    raw <- lookup "manifest.json" entries
    inner <- either (const Nothing) Just (eitherDecodeStrict' raw)
    modules <- jsonField inner "modules" :: Maybe [Value]
    payloadPaths <- concat <$> mapM modulePaths modules
    let emptyReceipt = jsonField inner "emptyRegistration" :: Maybe Text.Text
        reexportReceipt = jsonField inner "reexportRegistration" :: Maybe Text.Text
    reexports <- case reexportReceipt of
      Nothing -> Just []
      Just receipt -> modulelessRegistration unit dependencies (Text.encodeUtf8 receipt)
    let validInventory = case (emptyReceipt, reexportReceipt) of
          (Nothing, Nothing) -> not (null modules)
          (Just receipt, Nothing) -> null modules && emptyRegistration unit dependencies (Text.encodeUtf8 receipt)
          (Nothing, Just _) -> null modules && not (null reexports)
          _ -> False
        names = [name | Just name <- map (`jsonField` "name") modules :: [Maybe String]]
        validModule item = do
          name <- jsonField item "name" :: Maybe String
          member <- jsonField item "path" :: Maybe String
          digest <- jsonField item "sha256" :: Maybe String
          foundBoundary <- jsonField item "boundary" :: Maybe String
          checkedPair <- moduleEntries item entries
          body <- lookup member checkedPair
          artifact <- either (const Nothing) Just (eitherDecodeStrict' body)
          owner <- jsonField artifact "unit" :: Maybe String
          actual <- jsonField artifact "module" :: Maybe String
          artifactBoundary <- jsonField artifact "boundary" :: Maybe String
          pure (shaHex body == digest && foundBoundary == boundary &&
                owner == unit && actual == name && artifactBoundary == boundary)
    if (jsonField inner "format" == Just ("thc-core-bundle" :: String) &&
        jsonField inner "schema" == Just (1 :: Int) &&
        jsonField inner "unit" == Just unit &&
        jsonField inner "buildKey" == Just buildKey &&
        jsonField inner "exportKey" == Just exportKey &&
        validInventory && length names == length modules &&
        length names == length (nub names) &&
        length payloadPaths == length (nub payloadPaths) &&
        sort (map fst entries) == sort ("manifest.json" : payloadPaths) &&
        all (== Just True) (map validModule modules))
      then Just (Bundle path (shaHex bytes) modules buildKey reexports)
      else Nothing

exportUnit :: ExportContext -> [FilePath] -> Map.Map String String -> Unit -> IO Bundle
exportUnit context roots keys unit = do
  component <- readComponent unit context >>= completeHomeModules context roots unit
  dist <- field (unitValue unit) "dist-dir"
  runtimeShim <- componentRuntimeShim (componentValue component)
  if runtimeShim
    then withRuntimeShim (contextNative context) dist roots (componentCompiler component)
      (unitId unit) (componentValue component) $ \shim ->
        exportConfiguredUnit context keys unit component Nothing (Just shim) Nothing
    else do
      -- Warm Cabal products may predate LLVM capture. Reuse their actual
      -- compiler receipts while local sources/headers still exist.
      objects <- componentNativeObjects (contextNative context) dist roots (componentValue component)
      forM_ (filter ((== ".o") . takeExtension) objects) $ \path -> do
        recipe <- readNativeRecipe (contextNative context </> "cache/thc/native-recipes-v1")
          (componentCompiler component) path >>= maybe (fail "missing native compiler receipt") pure
        Directory.withCurrentDirectory (recipeDirectory recipe) $
          captureNativeObject (contextNative context </> "cache/thc/native-pieces-v1")
            (componentCompiler component) (recipeArguments recipe)
      exportConfiguredUnit context keys unit component Nothing Nothing (Just objects)

exportConfiguredUnit :: ExportContext -> Map.Map String String -> Unit -> Component -> Maybe ScalarBitcode -> Maybe RuntimeShim -> Maybe [FilePath] -> IO Bundle
exportConfiguredUnit context keys unit component scalar runtimeShim nativeObjects = do
  let retainedInterfaces = case (scalar, runtimeShim) of (Nothing, Nothing) -> Nothing; _ -> Just ()
  helper <- traverse (const (prepareInterfaceHelper context (contextRoot context))) retainedInterfaces
  helperHash <- traverse (digestFile . installedHelper) helper
  nativeInputs <- componentNativeInputs context unit component
  nativePieces <- traverse
    (nativePieceIdentity (contextNative context </> "cache/thc/native-pieces-v1")) nativeObjects
  let dependencies = [(identifier, Map.findWithDefault identifier identifier keys)
                     | identifier <- unitDepends unit]
      normalized = normalizePaths (contextNative context)
      inputFields = ["format" .= ("thc-core-build-inputs" :: String), "schema" .= (1 :: Int),
                     "unit" .= unitId unit,
                     "compiler" .= object ["id" .= contextCompiler context,
                                           "abi" .= contextAbi context,
                                           "platform" .= contextPlatform context],
                     "component" .= normalized (componentValue component),
                     "nativeTools" .= contextNativeTools context,
                     "nativeArtifacts" .= [object ["path" .= path, "sha256" .= digest]
                                           | (path, digest) <- nativeInputs],
                     "dependencies" .= [object ["id" .= identifier, "buildKey" .= identity]
                                        | (identifier, identity) <- dependencies]] ++
                    maybe [] (\recipe -> ["packageNativeRecipe" .= normalized recipe]) nativePieces ++
                    maybe [] (\recipe -> ["packageScalarRecipe" .= scalarBuildInputs recipe]) scalar ++
                    maybe [] (\recipe -> ["runtimeShimRecipe" .= runtimeShimInputs recipe]) runtimeShim
      buildKey = shaHex (BL.toStrict (encode (object inputFields)))
  pluginHash <- digestFile (contextPluginLibrary context)
  let exporter = object $ ["pluginUnit" .= contextPluginUnit context,
                         "pluginDb" .= contextPluginDb context,
                         "pluginHash" .= pluginHash,
                         "driverHash" .= contextDriverHash context,
                         "options" .= (["post-tidy", "unit-qualified", "source-notes",
                                         "foreign-import-provenance", "foreign-export-associations", "foreign-export-registration",
                                         "native-debug-info"] ++ exportWayOptions ++
                                        ["-dcore-lint", "-fplugin-trustworthy"])] ++
                     maybe [] (\digest -> ["scalarInterfaceHelperSha256" .= digest,
                       "scalarInterfaceOptions" .= (["-fwrite-if-simplified-core", "-hisuf", "hi"] :: [String])]) helperHash
      exportKey = shaHex (BL.toStrict (encode ("thc-core-export-v1" :: String, buildKey, exporter)))
      buildInputs = object (inputFields ++ ["buildKey" .= buildKey,
                                           "exportKey" .= exportKey,
                                           "exporter" .= exporter])
      directory = contextNative context </>
        (if Host.os == "mingw32" then "bundles" else "cache/thc/core-bundles/v1") </> exportKey
      destination = directory </> if Host.os == "mingw32" then "unit.zip"
        else unitId unit ++ "-" ++ buildKey ++ ".zip"
  expected <- expectedModuleNames (componentValue component)
  createDirectoryIfMissing True directory
  withLock (destination ++ ".lock") $ do
    cached <- doesFileExist destination
    hit <- if cached then readBundle (contextVerifyArtifacts context) PlainBundle destination (unitId unit) buildKey exportKey buildInputs expected
           else pure Nothing
    case hit of
      Just bundle -> pure bundle
      Nothing -> do
        when cached (removeFile destination)
        freshExport context component unit scalar runtimeShim helper nativeObjects buildKey exportKey buildInputs expected destination

-- An inplace repository package's own project options also escape its unit ID.
-- Hash Cabal's actual configuration as opaque bytes, never reconstruct a
-- compiler invocation from it. Include native products for each such package.
sourceNativeInputs :: ExportContext -> Unit -> IO [(FilePath, String)]
sourceNativeInputs context unit = do
  dist <- field (unitValue unit) "dist-dir"
  require (within (contextNative context) dist)
    ("inplace source dependency has no owned Cabal build directory: " ++ unitId unit)
  products <- sort . filter nativeProduct <$> recursiveFiles (dist </> "build")
  forM ((dist </> "setup-config") : products) $ \path -> do
    digest <- digestFile path
    pure (makeRelative (contextNative context) path, digest)

componentNativeInputs :: ExportContext -> Unit -> Component -> IO [(FilePath, String)]
componentNativeInputs context unit component = do
  dist <- field (unitValue unit) "dist-dir"
  productRoots <- componentRoots dist (componentValue component)
  products <- sort . nub . filter nativeProduct . concat <$> mapM recursiveFiles productRoots
  require (not (null products)) ("native Cabal build has no Haskell artifacts for " ++ unitId unit)
  forM products $ \path -> do
    digest <- digestFile path
    pure (makeRelative (contextNative context) path, digest)

freshExport :: ExportContext -> Component -> Unit -> Maybe ScalarBitcode -> Maybe RuntimeShim -> Maybe InstalledContext -> Maybe [FilePath] -> String -> String -> Value ->
               [String] -> FilePath -> IO Bundle
freshExport context component unit scalar runtimeShim helper nativeObjects buildKey exportKey buildInputs expected destination = do
  let localRoot = contextNative context </> "cache/thc/staging"
  createDirectoryIfMissing True localRoot
  (staging, handle) <- openTempFile localRoot "export-"
  hClose handle
  removeFile staging
  createDirectory staging
  let cleanup = do exists <- doesDirectoryExist staging
                   when exists (removePathForcibly staging)
  (do
    let objects = staging </> "objects"
        core = staging </> "core"
    createDirectoryIfMissing True objects
    sourceDir <- field (componentValue component) "src-dir"
    pluginArguments <- Directory.withCurrentDirectory sourceDir $
      expandResponse (componentArguments component)
    -- The known THC exporter preserves GHC safety inference, as in GhcProxy.
    -- Without plugin trust, an inferred-safe home module becomes unsafe merely
    -- because it is exported, so a later Safe importer fails to compile.
    let pluginOptions = [core, "post-tidy", "unit-qualified", "source-notes", "foreign-import-provenance",
                         "foreign-export-associations", "foreign-export-registration"]
        -- The pinned Windows compiler loads a vanilla archive, not a shared plugin.
        pluginFlags = if Host.os == "mingw32"
          then ["-plugin-package-id", contextPluginUnit context, "-fplugin=THC.Plugin"] ++
               map ("-fplugin-opt=THC.Plugin:" ++) pluginOptions
          else [directPlugin (contextPluginLibrary context) (contextPluginUnit context)
                  pluginOptions pluginArguments]
        arguments = ["--make", "-no-link"] ++ componentArguments component ++
          ["-outputdir", objects, "-odir", objects, "-hidir", objects,
           "-hiedir", objects </> "hie", "-stubdir", objects,
           "-package-db", contextPluginDb context, "-fplugin-trustworthy"] ++ pluginFlags ++ exportWayOptions ++
          ["-fforce-recomp", "-dcore-lint", "-fwrite-if-simplified-core", "-hisuf", "hi"] ++
          map snd (componentSources component)
    runCommand True (componentCompiler component) arguments sourceDir
    exported <- filter ((== ".json") . takeExtension) <$> recursiveFiles core
    checked <- forM exported $ \path -> do
      value <- readJson path
      foundUnit <- field value "unit"
      foundBoundary <- field value "boundary" :: IO String
      name <- field value "module"
      require (foundUnit == unitId unit && foundBoundary == boundary)
        ("Core artifact has wrong unit or boundary: " ++ path)
      bytes <- BS.readFile path
      pure (name, bytes)
    let actual = sort (map fst checked)
    require (length actual == length (nub actual) && actual == expected)
      ("Core module inventory differs from Cabal build-info for " ++ unitId unit ++ ": " ++ show actual)
    sorted <- case (scalar,runtimeShim,helper) of
      (Nothing,Nothing,Nothing) -> do
        selectedHelper <- prepareInterfaceHelper context (contextRoot context)
        Directory.withCurrentDirectory sourceDir $
          capturePackageNative (contextRoot context) (installedHelper selectedHelper) (installedLibdir selectedHelper)
            (componentCompiler component) arguments (unitId unit) staging
        updated <- forM exported $ \path -> do
          value <- readJson path
          name <- field value "module"
          bytes <- BS.readFile path
          pure (name,bytes)
        sortOn fst <$> finishPackageNative (contextNative context </> "cache/thc/native-pieces-v1") staging (unitId unit) nativeObjects updated
      (Just recipe,Nothing,Just selectedHelper) -> do
        retained <- scalarInterfaceModules selectedHelper component unit objects expected
        linkScalarBitcode recipe buildKey retained
      (Nothing,Just shim,Just selectedHelper) -> do
        retained <- scalarInterfaceModules selectedHelper component unit objects expected
        validateRuntimeShimModules shim retained
      _ -> fail "scalar cbits interface helper missing"
    (modules, members) <- packageModules
      [(name, "core/" ++ show index ++ ".json", bytes)
      | (index, (name, bytes)) <- zip [0 :: Int ..] sorted]
    let inputsBytes = BL.toStrict (encode buildInputs)
        inner = object ["format" .= ("thc-core-bundle" :: String), "schema" .= (1 :: Int),
                        "unit" .= unitId unit, "buildKey" .= buildKey,
                        "exportKey" .= exportKey, "modules" .= modules,
                        "buildInputs" .= object ["path" .= ("inplace-manifest.json" :: String),
                                                 "sha256" .= shaHex inputsBytes]]
    archive <- either fail pure (encodeZip
      (("manifest.json", BL.toStrict (encode inner)) : ("inplace-manifest.json", inputsBytes) : members))
    atomicBytes destination (BL.toStrict archive)
    rememberFreshBundle PlainBundle (unitId unit) exportKey buildInputs expected
      (Bundle destination (shaHex (BL.toStrict archive)) modules buildKey [])) `finally` cleanup

-- Source late-plugin JSON has no typed annotations. Recover the exact emitted
-- full-Core interfaces through the selected GHC helper and Cabal's actual
-- library registration; never graft an inferred proof onto that JSON.
scalarInterfaceModules :: InstalledContext -> Component -> Unit -> FilePath -> [String] -> IO [(String,BS.ByteString)]
scalarInterfaceModules helper component unit objects names = do
  sourceDir <- field (componentValue component) "src-dir"
  databases <- mapM (canonicalizePath . (sourceDir </>))
    [path | (flag,path) <- zip (componentArguments component) (drop 1 (componentArguments component)),
      flag == "-package-db"]
  forM names $ \name -> do
    let interface = objects </> map (\c -> if c == '.' then pathSeparator else c) name <.> "hi"
        arguments = ["--libdir",installedLibdir helper,"--unit",unitId unit,"--module",name,
          "--interface",interface,"--way",exportInterfaceWay,"--source-notes"] ++
          concatMap (\database -> ["--package-db",database]) databases
    requireFile interface
    (status,output,diagnostic) <- readCreateProcessWithExitCode
      (proc (installedHelper helper) arguments) {cwd=Just sourceDir} ""
    require (status == ExitSuccess) ("scalar cbits interface acquisition failed: " ++ take 4096 (output ++ diagnostic))
    response <- either fail pure (Aeson.eitherDecodeStrict' (Text.encodeUtf8 (Text.pack output)))
    require (jsonField response "schema" == Just (1::Int) && jsonField response "status" == Just ("loaded"::String))
      "scalar cbits interface helper did not return full Core"
    value <- field response "core"
    require (jsonField value "unit" == Just (unitId unit) && jsonField value "module" == Just name &&
      jsonField value "boundary" == Just boundary && jsonField value "ghc" == Just ("9.14.1"::String))
      "scalar cbits interface identity or boundary mismatch"
    pure (name,BL.toStrict (encode value))

-- | Read a configured bundle under its complete expected inputs and inventory.
-- True retains exhaustive archive, layout, provenance and module validation.
readBundle :: Bool -> BundleReceipt -> FilePath -> String -> String -> String -> Value -> [String] -> IO (Maybe Bundle)
readBundle verify receipt path unit buildKey exportKey buildInputs expected =
  rememberBundle verify (path ++ ".selection.json") [path]
    (Aeson.toJSON (show receipt, unit, buildKey, exportKey, buildInputs, expected)) $
      readBundleCold receipt path unit buildKey exportKey buildInputs expected

-- Source acquisition has just validated/emitted these exact modules and
-- receipts. Seed that successful result without reopening its ZIP while the
-- emission buffers are still live. Existing artifacts never enter this path.
rememberFreshBundle :: BundleReceipt -> String -> String -> Value -> [String] -> Bundle -> IO Bundle
rememberFreshBundle receipt unit exportKey buildInputs expected bundle = do
  let path = bundlePath bundle
      request = Aeson.toJSON (show receipt, unit, bundleBuildKey bundle, exportKey, buildInputs, expected)
  _ <- rememberBundle True (path ++ ".selection.json") [path] request (pure (Just bundle))
  pure bundle

readBundleCold :: BundleReceipt -> FilePath -> String -> String -> String -> Value -> [String] -> IO (Maybe Bundle)
readBundleCold receipt path unit buildKey exportKey buildInputs expected = do
  bytes <- BS.readFile path
  decoded <- decodeZip bytes
  pure $ do
    entries <- either (const Nothing) Just decoded
    raw <- lookup "manifest.json" entries
    inner <- either (const Nothing) Just (eitherDecodeStrict' raw)
    modules <- jsonField inner "modules" :: Maybe [Value]
    payloadPaths <- concat <$> mapM modulePaths modules
    inputRef <- jsonField inner "buildInputs" :: Maybe Value
    inputPath <- jsonField inputRef "path" :: Maybe String
    inputHash <- jsonField inputRef "sha256" :: Maybe String
    inputBytes <- lookup inputPath entries
    storedInputs <- either (const Nothing) Just (eitherDecodeStrict' inputBytes)
    let storedBase = case storedInputs of
          Object fields -> Object (KeyMap.delete "sourceBuild" (KeyMap.delete "generatedSources"
            (KeyMap.delete "targetLayout" fields)))
          value -> value
        layout = jsonField storedInputs "targetLayout" :: Maybe Value
        innerLayout = jsonField inner "targetLayout" :: Maybe Value
        generated = jsonField storedInputs "generatedSources" :: Maybe [Value]
        innerGenerated = jsonField inner "generatedSources" :: Maybe [Value]
        compiler = jsonField buildInputs "compiler" :: Maybe Value
        platform = compiler >>= (`jsonField` "platform") :: Maybe String
        layoutPlatform = layout >>= (`jsonField` "targetPlatform") :: Maybe String
    let names = [name | Just name <- map (`jsonField` "name") modules]
        validModule item = do
          name <- jsonField item "name" :: Maybe String
          member <- jsonField item "path" :: Maybe String
          digest <- jsonField item "sha256" :: Maybe String
          foundBoundary <- jsonField item "boundary" :: Maybe String
          checkedPair <- moduleEntries item entries
          body <- lookup member checkedPair
          artifact <- either (const Nothing) Just (eitherDecodeStrict' body)
          owner <- jsonField artifact "unit" :: Maybe String
          actual <- jsonField artifact "module" :: Maybe String
          artifactBoundary <- jsonField artifact "boundary" :: Maybe String
          pure (shaHex body == digest && foundBoundary == boundary &&
                owner == unit && actual == name && artifactBoundary == boundary)
    if (jsonField inner "format" == Just ("thc-core-bundle" :: String) &&
        jsonField inner "schema" == Just (1 :: Int) &&
        jsonField inner "unit" == Just unit &&
        jsonField inner "buildKey" == Just buildKey &&
        jsonField inner "exportKey" == Just exportKey &&
        inputPath == "inplace-manifest.json" && shaHex inputBytes == inputHash &&
        (case receipt of
          PlainBundle -> storedInputs == buildInputs
          TargetLayoutBundle -> storedBase == buildInputs && layout == innerLayout &&
            maybe False validTargetLayout layout && layoutPlatform == platform &&
            generated == Nothing && innerGenerated == Nothing
          PinnedSourceBundle -> storedBase == buildInputs && layout == innerLayout &&
            generated == innerGenerated && maybe False validTargetLayout layout &&
            layoutPlatform == platform &&
            maybe False (validGeneratedSources buildInputs) generated) &&
        length names == length modules &&
        length names == length (nub names) && sort names == expected &&
        length payloadPaths == length (nub payloadPaths) &&
        sort (map fst entries) == sort ("manifest.json" : inputPath : payloadPaths) &&
        all (== Just True) (map validModule modules))
      then Just (Bundle path (shaHex bytes) modules buildKey [])
      else Nothing

validTargetLayout :: Value -> Bool
validTargetLayout layout =
  jsonField layout "schema" == Just (1 :: Int) &&
  jsonField layout "profiled" == Just False &&
  maybe False (const True) (jsonField layout "tablesNextToCode" :: Maybe Bool) &&
  maybe False (not . null) (jsonField layout "targetPlatform" :: Maybe String) &&
  (jsonField layout "endianness" :: Maybe String) `elem` [Just "little", Just "big"] &&
  maybe False (`elem` [4, 8]) (jsonField layout "wordBytes" :: Maybe Int) &&
  all (maybe False (>= 0) . (jsonField layout :: String -> Maybe Int))
    ["infoTableBytes", "infoTablePtrsOffset", "infoTablePtrsBytes",
     "infoTableNptrsOffset", "infoTableNptrsBytes", "infoTableTypeOffset",
     "infoTableTypeBytes", "infoTableSrtOffset", "infoTableSrtBytes",
     "infoProvEntBytes", "infoProvBytes", "infoProvDescBytes",
     "infoProvEntInfoOffset", "infoProvEntProvOffset", "infoProvNameOffset",
     "infoProvDescOffset", "infoProvTyDescOffset", "infoProvLabelOffset",
     "infoProvUnitOffset", "infoProvModuleOffset", "infoProvFileOffset",
     "infoProvSpanOffset", "stackHeaderBytes", "stackCatchHandlerBytes",
     "stackCatchFrameBytes", "stackUpdateeBytes", "stackUpdateFrameBytes",
     "stackAnnPayloadBytes", "stackAnnFrameBytes", "stackRetFunSizeBytes",
     "stackRetFunFunBytes", "stackRetFunPayloadBytes", "stackRetFunFrameBytes"] &&
  all (\(name, ordinal) -> jsonField layout name == Just (ordinal :: Int))
    [("closureRetBco", 29), ("closureRetSmall", 30), ("closureRetBig", 31),
     ("closureRetFun", 32), ("closureStopFrame", 36), ("closureStack", 53),
     ("closureAnnFrame", 65)]

validGeneratedSources :: Value -> [Value] -> Bool
validGeneratedSources inputs generated =
  let paths = map (`jsonField` "path") generated :: [Maybe FilePath]
      digests = map (`jsonField` "sha256") generated :: [Maybe String]
      expected = (jsonField inputs "component" :: Maybe Value) >>= (`jsonField` "generatedSources") :: Maybe [FilePath]
  in Just (sort [normalise path | Just path <- paths]) == fmap (sort . map normalise) expected &&
     Just (length paths) == fmap length expected &&
     all (maybe False (\digest -> length digest == 64 && all isHexDigit digest)) digests

jsonField :: FromJSON a => Value -> String -> Maybe a
jsonField (Object fields) name = do
  value <- KeyMap.lookup (Key.fromString name) fields
  case Aeson.fromJSON value of
    Aeson.Success result -> Just result
    Aeson.Error _ -> Nothing
jsonField _ _ = Nothing

atomicBytes :: FilePath -> BS.ByteString -> IO ()
atomicBytes path bytes = do
  (temporary, handle) <- openTempFile (takeDirectory path) ".core-"
  let cleanup = do exists <- doesFileExist temporary
                   when exists (removeFile temporary)
  (BS.hPut handle bytes >> hClose handle >> renameFile temporary path) `finally` cleanup

normalizePaths :: FilePath -> Value -> Value
normalizePaths build value = case value of
  String text -> String (Text.replace (Text.pack build) "<native-build>" text)
  Array values -> Array (fmap (normalizePaths build) values)
  Object fields -> Object (fmap (normalizePaths build) fields)
  other -> other

expectedModuleNames :: Value -> IO [String]
expectedModuleNames component = do
  modules <- optionalField component "modules" ([] :: [String])
  files <- optionalField component "src-files" ([] :: [String])
  componentType <- field component "type"
  let runnable = componentType `elem` ["exe", "bench", "test" :: String]
  when runnable $
    require (length files == 1) "project runnable component must have one Haskell main source"
  pure (sort (modules ++ if runnable then ["Main"] else []))

readComponent :: Unit -> ExportContext -> IO Component
readComponent = readComponentMetadata True

-- Cabal's declared modules need not enumerate all home imports accepted by GHC
-- --make (older packages such as HsColour legitimately omit other-modules).
-- Complete that inventory from actual, component-owned binary interfaces, never
-- imports, arbitrary neighboring files, or the later exporter output itself.
completeHomeModules :: ExportContext -> [FilePath] -> Unit -> Component -> IO Component
completeHomeModules context roots unit component = do
  dist <- field (unitValue unit) "dist-dir"
  candidates <- componentHomeInterfaces dist roots (componentValue component)
  if null candidates then pure component else do
    helper <- prepareInterfaceHelper context (contextRoot context)
    forM_ ["vanilla", "dynamic"] $ \way -> do
      let selected = [(name, path) | (actualWay, name, path) <- candidates, actualWay == way]
          names = map fst selected
          rows = [object ["unit" .= unitId unit, "module" .= name, "interface" .= path]
                 | (name, path) <- selected]
      require (length names == length (nub names)) "ambiguous compiler-discovered home module interfaces"
      unless (null selected) $ do
        (status, output, diagnostic) <- boundedInterfaceProcessInput
          (installedHelper helper) ["--home-interface-inventory", installedLibdir helper, unitId unit, way]
          (BL.toStrict (encode rows))
        require (status == ExitSuccess)
          ("home interface identity check failed: " ++ take 4096 (Text.unpack (Text.decodeUtf8 diagnostic)))
        response <- either fail pure (eitherDecodeStrict' output)
        require (jsonField response "schema" == Just (1 :: Int) &&
                 jsonField response "status" == Just ("home-interfaces" :: String) &&
                 jsonField response "unit" == Just (unitId unit) &&
                 jsonField response "way" == Just way &&
                 jsonField response "interfaces" == Just rows)
          "home interface helper returned a different component inventory"
    declared <- field (componentValue component) "modules" :: IO [String]
    let modules = sort (nub (declared ++ [name | (_,name,_) <- candidates]))
    completed <- case componentValue component of
      Object fields -> pure (Object (KeyMap.insert "modules" (Aeson.toJSON modules) fields))
      _ -> fail "Cabal component metadata must be an object"
    -- Keep the original compilation targets; GHC rediscovers their imports.
    -- Adding the discovered modules as source targets could change Main naming
    -- or incorrectly demand a source for compiler-generated modules.
    pure component {componentValue = completed}

readComponentMetadata :: Bool -> Unit -> ExportContext -> IO Component
readComponentMetadata selected unit context = do
  location <- field (unitValue unit) "build-info"
  info <- readJson location
  compiler <- field info "compiler" :: IO Value
  flavour <- field compiler "flavour"
  actualCompilerId <- field compiler "compiler-id"
  require (flavour == ("ghc" :: String) && actualCompilerId == contextCompiler context)
    ("unsupported compiler in build-info for " ++ unitId unit)
  compilerPath <- canonicalizePath =<< field compiler "path"
  when selected $ do
    expectedCompiler <- canonicalizePath (nativeCompilerProxy context)
    require (compilerPath == expectedCompiler)
      "Cabal build-info does not identify the transparent native compiler"
  components <- field info "components" :: IO [Value]
  componentName <- field (unitValue unit) "component-name" :: IO String
  matches <- filterM (\value -> do
    identifier <- optionalField value "unit-id" ("" :: String)
    name <- optionalField value "name" ("" :: String)
    pure (identifier == unitId unit && name == componentName)) components
  value <- case matches of
    [component] -> pure component
    _ -> fail ("build-info disagrees with plan for " ++ unitId unit)
  kind <- field value "type" :: IO String
  files <- optionalField value "src-files" ([] :: [String])
  when (selected && kind == "test") $
    require (length files == 1) "only exitcode-stdio-1.0 test suites are supported"
  when (selected && kind == "bench") $
    require (length files == 1) "only exitcode-stdio-1.0 benchmarks are supported"
  arguments <- field value "compiler-args"
  require (hasPair "-this-unit-id" (unitId unit) arguments)
    ("compiler arguments have wrong unit ID for " ++ unitId unit)
  flags <- field (unitValue unit) "flags" :: IO (Map.Map String Bool)
  configured <- case value of
    Object fields -> pure (Object (KeyMap.insert "thc-cabal-configuration" (object
      ["flags" .= flags, "compiler" .= contextCompiler context,
       "platform" .= contextPlatform context]) fields))
    _ -> fail "Cabal component metadata must be an object"
  modules <- componentDeclaredModules configured
  let completed = case configured of
        Object fields -> Object (KeyMap.insert "modules" (Aeson.toJSON modules) fields)
        _ -> configured
  sources <- if selected then sourcePaths completed arguments else pure []
  pure (Component completed (contextGhc context) arguments sources)

sourcePaths :: Value -> [String] -> IO [(String, FilePath)]
sourcePaths component arguments = do
  root <- field component "src-dir"
  hsDirs <- field component "hs-src-dirs" :: IO [FilePath]
  modules <- optionalField component "modules" ([] :: [String])
  files <- optionalField component "src-files" ([] :: [String])
  let directories = nub [root </> dir | dir <- hsDirs ++
                          [drop 2 flag | flag <- arguments, "-i" `isPrefix` flag, length flag > 2]]
  foundModules <- forM modules $ \name -> do
    let relative = joinPath (split '.' name)
    path <- uniqueSource name [directory </> replaceExtension relative extension |
                               directory <- directories, extension <- ["hs", "lhs"]]
    pure (name, path)
  foundFiles <- forM files $ \name -> do
    path <- uniqueSource name [directory </> name | directory <- directories]
    pure (name, path)
  let found = nub (foundModules ++ foundFiles)
  require (not (null found)) "Cabal component has no Haskell source targets"
  pure found

uniqueSource :: String -> [FilePath] -> IO FilePath
uniqueSource name candidates = do
  matches <- nub <$> filterM doesFileExist candidates
  case matches of
    [path] -> canonicalizePath path
    _ -> fail ("cannot uniquely locate Cabal source " ++ name ++ ": " ++ show matches)

nativeProduct :: FilePath -> Bool
nativeProduct path = any (`isSuffixOf` path) [".o", ".hi", ".hie", ".dyn_o", ".dyn_hi"]

within :: FilePath -> FilePath -> Bool
within root path = isAbsolute path && not (isAbsolute relative) &&
  all (/= "..") (splitDirectories relative)
  where relative = makeRelative (normalise root) (normalise path)

recursiveFiles :: FilePath -> IO [FilePath]
recursiveFiles root = do
  exists <- doesDirectoryExist root
  if not exists then pure [] else do
    names <- listDirectory root
    concat <$> forM names (\name -> do
      let path = root </> name
      directory <- doesDirectoryExist path
      if directory then recursiveFiles path else pure [path])

atomicJson :: FilePath -> Value -> IO ()
atomicJson path value = do
  createDirectoryIfMissing True (takeDirectory path)
  -- A trailing-dot template is normalized on creation by Win32, but not by
  -- GHC's extended-path rename. Share the portable atomic bundle writer.
  atomicBytes path (BL.toStrict (encode value) <> BS.pack [10])

field :: FromJSON a => Value -> String -> IO a
field value name = case value of
  Object fields -> case KeyMap.lookup (Key.fromString name) fields of
    Just item -> case Aeson.fromJSON item of
      Aeson.Success result -> pure result
      Aeson.Error errorMessage -> fail ("invalid JSON field " ++ name ++ ": " ++ errorMessage)
    Nothing -> fail ("missing JSON field " ++ name)
  _ -> fail ("JSON object expected for field " ++ name)

optionalField :: FromJSON a => Value -> String -> a -> IO a
optionalField value name fallback = case value of
  Object fields -> case KeyMap.lookup (Key.fromString name) fields of
    Nothing -> pure fallback
    Just _ -> field value name
  _ -> fail ("JSON object expected for field " ++ name)

readJson :: FilePath -> IO Value
readJson path = do
  contents <- BS.readFile path
  either (fail . (("invalid JSON " ++ path ++ ": ") ++)) pure (eitherDecodeStrict' contents)

digestFile :: FilePath -> IO String
digestFile path = withBinaryFile path ReadMode $ \handle -> do
  bytes <- BL.hGetContents handle
  -- Finish the streamed digest before closing the handle. A lazy hex String
  -- must retain only the 32-byte digest, not a whole driver/helper/artifact
  -- ByteString across later subprocesses or cache validation.
  digest <- evaluate (SHA.hashlazy bytes)
  pure (digestHex digest)

shaHex :: BS.ByteString -> String
shaHex = digestHex . SHA.hash

digestHex :: BS.ByteString -> String
digestHex = concatMap byteHex . BS.unpack
  where byteHex byte = let digits = showHex byte "" in if length digits == 1 then '0':digits else digits

require :: Bool -> String -> IO ()
require yes message = unless yes (fail message)

requireFile :: FilePath -> IO ()
requireFile path = doesFileExist path >>= \exists -> require exists ("required file not found: " ++ path)

requireDirectory :: FilePath -> IO ()
requireDirectory path = doesDirectoryExist path >>= \exists -> require exists ("required directory not found: " ++ path)

runCommand :: Bool -> FilePath -> [String] -> FilePath -> IO ()
runCommand tool command arguments directory =
  runCommandWithEnv tool command arguments directory Nothing

runCommandWithEnv :: Bool -> FilePath -> [String] -> FilePath -> Maybe [(String, String)] -> IO ()
runCommandWithEnv tool command arguments directory environment = do
  (_, _, _, process) <- createProcess (proc command arguments)
    { cwd = Just directory, env = environment,
      std_out = if tool then UseHandle stderr else Inherit }
  result <- waitForProcess process
  when (result /= ExitSuccess) (fail ("command failed: " ++ command ++ " (" ++ show result ++ ")"))

hasPair :: Eq a => a -> a -> [a] -> Bool
hasPair first second values = any (== [first, second]) (zipWith (\x y -> [x, y]) values (drop 1 values))

isPrefix :: String -> String -> Bool
isPrefix prefix value = take (length prefix) value == prefix

split :: Char -> String -> [String]
split _ [] = [""]
split separator text = case break (== separator) text of
  (part, []) -> [part]
  (part, _:rest) -> part : split separator rest

foldlM :: Monad m => (b -> a -> m b) -> b -> [a] -> m b
foldlM _ value [] = pure value
foldlM step value (item:rest) = step value item >>= \next -> foldlM step next rest
