-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE CPP #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TemplateHaskell #-}

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
  ( runProject, acquireProject, buildTargetsProject, prepareWindowsRuntime, Bundle(..), InstalledBundle(..)
  , exportInstalledUnit, prepareInstalledBundle, prepareInstalledBundleWithVerification, installedRecords
  , BundleReceipt(..), readGlobalBundle, readBundle, exceptionBridgeModules, projectWindowsWiredBundle
  , publishCapturedStoreUnit, readCapturedStoreBundles, readCapturedInstalledBundles
  , selectedPackageTool, componentCoreRecords
  ) where

import Control.Exception (bracket, evaluate, finally, onException)
import Control.Monad (filterM, forM, forM_, unless, when)
import Data.Char (isAlphaNum, isHexDigit, isSpace)
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
import Data.List (isPrefixOf, isSuffixOf, nub, nubBy, sort, sortOn, stripPrefix)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.Text.Encoding.Error (lenientDecode)
import Numeric (showHex)
import Language.Haskell.TH.Syntax (addDependentFile, lift, loc_filename, runIO)
import qualified Language.Haskell.TH.Syntax as TH
import System.Directory (canonicalizePath, createDirectory, createDirectoryIfMissing,
                         doesDirectoryExist, doesFileExist, findExecutable, getPermissions,
                         listDirectory, makeAbsolute, removeFile, removePathForcibly,
                         renameFile, setPermissions)
import qualified System.Directory as Directory
import System.Exit (ExitCode(..))
import System.Environment (getEnvironment, getExecutablePath, lookupEnv)
import System.FilePath ((</>), (<.>), pathSeparator, isAbsolute, makeRelative, normalise, splitDirectories, equalFilePath,
                        takeDirectory, takeFileName, takeExtension, joinPath, replaceExtension)
import System.IO (IOMode(ReadMode), hClose, hGetContents, hPutStrLn,
                  hSetEncoding, openTempFile, stderr, utf8, withBinaryFile, withFile)
import System.IO.Error (tryIOError)
import qualified System.Info as Host
import THC.Driver.Lock (withLock)
import System.Process (CreateProcess(..), StdStream(..), createProcess, proc, waitForProcess,
                       readCreateProcessWithExitCode)
import THC.Driver.Process (runProducer)
import THC.Driver.Admission (Admission, localAdmission, waitForNative, withBuildAdmission, traceAdmission)
import THC.Driver.Cabal (PlanOptions(..))
import THC.Driver.Cache (coreCacheDirectory)
import THC.Driver.CoreIndex (packageModules, modulePaths, moduleEntries)
import THC.Driver.CoreSymbols (publishCoreUnit)
import THC.Driver.GhcProxy (ghcProxyCommand, ghcProxyWindowsCommand, directPlugin, coreReplayArguments)
import THC.Driver.NativeRecipe (NativeRecipe(..), componentRoots, componentNativeObjects,
  readNativeRecipe, ensureNativeRecipes, componentRuntimeShim, componentDeclaredModules, componentHomeInterfaces,
  componentMainModule)
import THC.Driver.ScalarBitcode (ScalarBitcode, scalarBuildInputs, linkScalarBitcode)
import THC.Driver.RuntimeShim (RuntimeShim, withRuntimeShim, runtimeShimInputs, validateRuntimeShimModules, foreignExceptionBridgeUnit)
import THC.Driver.PackageNative (captureNativeObject, captureConfiguredNativeObject, capturePackageNative, finishPackageNative,
  finishPackageNativeWithDependencies, linkInstalledNativeWithProduct)
import THC.Driver.NativeDependencies (readNativeProduct, readNativeProductAvailable, configuredNativeArchive)
import THC.Driver.NativeCache (nativeToolIdentity, nativePieceIdentity)
import THC.Driver.NativeImage (validateNativeImage, buildNativeImage)
import THC.Driver.Installed
import THC.Driver.InstalledForeign
import THC.Driver.Run (RunOptions(..), runtimeLaunchArguments, runtimeDebugEnvironment, runResolvedPackage)
import THC.Driver.Zip (decodeZip, encodeZip)
import THC.Compact.Core (Presence(..))
import THC.Compact.Debug (SourceFile(..))
import THC.Compact.Module (readModuleMetadata, readModuleMetadataFile, readModuleSources)
import THC.Driver.Wired (probeTargetLayout, preparePinnedInterfaces, pinnedRecipeIdentity)

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

-- Installed acquisition keeps its ordinary CBD return type. Only the selected
-- project source can instead be a checked, not-yet-converted interface unit.
data InstalledSource = InstalledCBD InstalledBundle | InstalledInterfaces String Value | InstalledSupport String Value

installedSourceOwner :: InstalledSource -> String
installedSourceOwner (InstalledCBD artifact) = installedOwner artifact
installedSourceOwner (InstalledInterfaces owner _) = owner
installedSourceOwner (InstalledSupport owner _) = owner

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
  , contextNoLinkUnits :: [String]
  , contextCoreView :: Maybe InstalledContext
  , contextCoreInterfaces :: Map.Map String FilePath }

-- Invocation-private executable capture and its immutable selection identity.
-- Final publication still uses the actual concrete plan and ordinary keys.
data OriginalStoreCapture = OriginalStoreCapture
  { originalCaptureDirectory :: FilePath
  , originalCapturePlan :: Map.Map String Unit
  , originalCaptureKeys :: Map.Map String (String, String)
  , originalCaptureStore :: FilePath }

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
  requestedCompiler <- maybe (lookupEnv "GHC") (pure . Just) (ghcPath flags)
  locatedCompiler <- case requestedCompiler of
    Just path -> do
      requestedPath <- if takeFileName path == path then pure path else makeAbsolute path
      findExecutable requestedPath >>= maybe (fail "selected GHC compiler not found") makeAbsolute
    Nothing -> findExecutable "ghc" >>= maybe (fail "GHC compiler not found") makeAbsolute
  compiler <- actualTool "ghc" locatedCompiler
  requestedPkg <- maybe (lookupEnv "GHC_PKG") (pure . Just) (ghcPkgPath flags)
  packageTool <- actualTool "ghc-pkg" =<< selectedPackageTool compiler requestedPkg
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
      (runInstalledCore opts) (runGhcSource opts) (runThcRoot opts) compiler packageTool driver)
  where
    -- Match the PowerShell exporter: bindist launchers narrow wide argv.
    -- Keep one actual tool identity through Cabal acquisition and export.
    actualTool name path = do
      let versioned = takeDirectory path </> (name ++ "-9.14.1.exe")
      available <- doesFileExist versioned
      canonicalizePath (if equalFilePath (takeFileName path) (name ++ ".exe") && available
        then versioned else path)

-- Windows retains the simple-package raw-Core route. Acquire its support from
-- the same real Cabal runtime unit and checked native/bundle cache as project
-- exports, using vanilla interfaces and the selected compiler's plugin archive.
-- This does not claim that arbitrary installed dependency Core is available.
prepareWindowsRuntime :: FilePath -> FilePath -> FilePath -> FilePath -> FilePath -> IO ([String], FilePath)
prepareWindowsRuntime = prepareWindowsRuntimeWithVerification True "pinned" Nothing

prepareWindowsRuntimeWithVerification :: Bool -> String -> Maybe FilePath -> FilePath -> FilePath -> FilePath -> FilePath -> FilePath -> IO ([String], FilePath)
prepareWindowsRuntimeWithVerification verify policy source repository selectedCompiler selectedPkg selectedDriver output = do
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
    runCommand True cabal (["build", "lib:thc"] ++ selection) root
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
          archive native cache driverHash compiler (Just pkg) driver root [] nativeTools verify [] Nothing Map.empty
        proxy = nativeCompilerProxy context
    createDirectoryIfMissing True (takeDirectory proxy)
    writeFile proxy ghcProxyWindowsCommand
    inherited <- getEnvironment
    let overrides = [("THC_PROXY_DRIVER",driver),("THC_PROXY_GHC",compiler),
          ("THC_PROXY_GLOBAL_UNITS",""),("THC_PROXY_NO_LINK_UNIT",""),
          ("THC_PROXY_NATIVE_RECIPES",native </> "cache/thc/native-recipes-v1")]
        environment = overrides ++ filter ((`notElem` map fst overrides) . fst) inherited
    internal <- case [value | value <- units,
        jsonField value "pkg-name" == Just ("ghc-internal" :: String),
        jsonField value "type" == Just ("pre-existing" :: String)] of
      [value] -> readUnit value
      _ -> fail "Windows plugin plan has no unique installed ghc-internal unit"
    (prepared, wired) <- wiredGhcInternal context root policy source (unitId internal)
    let selectedContext = context { contextCoreView =
          if policy == "required" || source /= Nothing then Just prepared else contextCoreView context }
    -- The genuine source bundle owns wired Core, while Cabal dependencies name
    -- its selected installed registration. Preserve that empty registration
    -- record, as installedRecords does; otherwise the sidecar reacquires the
    -- already supplied Core owner and correctly rejects the collision.
    let supplied = wired : [object ["id" .= unitId internal, "depends" .= unitDepends internal,
          "modules" .= ([] :: [Value])] | jsonField wired "id" /= Just (unitId internal)]
    (owner, records) <- linkForeignExceptionRuntime selectedContext environment policy source archive supplied
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

-- | Build arbitrary Cabal components using the same acquisition as run.
buildTargetsProject :: RunOptions -> [String] -> FilePath -> IO ()
buildTargetsProject opts targets = buildProject (BuildTargets (runNativeImage opts) targets) opts

data ProjectAction = AcquireOnly | BuildTargets Bool [String] | RunGuest deriving Eq

buildsNativeImages :: ProjectAction -> Bool
buildsNativeImages (BuildTargets enabled _) = enabled
buildsNativeImages _ = False

buildProject :: ProjectAction -> RunOptions -> FilePath -> IO ()
buildProject action opts target = do
  require (Host.os /= "mingw32") "project acquisition is not yet supported on Windows"
  let command = case action of AcquireOnly -> "acquire"; BuildTargets _ _ -> "build"; RunGuest -> "run"
  require (action == RunGuest || null (runArguments opts)) (command ++ " does not accept guest arguments")
  require (not (null (runThcRoot opts))) (command ++ " requires --thc-root DIR")
  require (runInstalledCore opts `elem` ["required", "pinned", "demand"])
    "--installed-core must be required, pinned or demand"
  require (not (buildsNativeImages action) || runInstalledCore opts /= "demand")
    "Native Image requires acquired CBD inputs; select --installed-core pinned or required. Demand conversion requires a running THC context."
  require (runInstalledCore opts /= "demand" || not (runVerifyArtifacts opts))
    "--installed-core demand does not yet support the offline prelaunch audit (--verify-artifacts). Select required or pinned for that audit; interface demand always verifies its content snapshot."
  require (runGhcSource opts == Nothing || runInstalledCore opts `elem` ["required", "demand"])
    "--ghc-source requires --installed-core required or demand"
  let flags = runPlan opts
  working <- canonicalizePath target
  let project = working
      projectOptions = cabalProjectOptions opts
  thcRoot <- canonicalizePath (runThcRoot opts)
  when (buildsNativeImages action) $ validateNativeImage thcRoot
  producer <- installedProducer thcRoot
  defaultRuntime <- maybe (pure (thcRoot </> "build/install/thc/bin/thc")) (`field` "runtime") producer
  runtime <- maybe (pure defaultRuntime) makeAbsolute (runRuntime opts)
  when (action == RunGuest) $ do
    requireFile runtime
    when (runVerifyArtifacts opts) $ requireFile (thcRoot </> "bin/audit-core.py")
  compiler <- case ghcPath flags of
    Just path -> do
      requestedCompiler <- if takeFileName path == path then pure path else makeAbsolute path
      findExecutable requestedCompiler >>= maybe (fail "selected GHC compiler not found") makeAbsolute
    Nothing -> findExecutable "ghc" >>= maybe (fail "GHC compiler not found") makeAbsolute
  packageTool <- selectedPackageTool compiler (ghcPkgPath flags)
  let buildPlugin = thcRoot </> "bin/build-compiler.sh"
      -- Plugin publication and interface preparation share Cabal's build tree.
      -- Pass the same selected invocation paths, including wrappers, to both.
      overrides = [("GHC", compiler), ("GHC_PKG", packageTool)]
  inherited <- getEnvironment
  launchEnvironment <- runtimeDebugEnvironment Host.os opts inherited
  let environment = overrides ++ filter (\(key, _) -> key `notElem` map fst overrides) inherited
  -- Cabal can build thc's executable without building its library. Publish the
  -- actual Cabal plugin registration before consulting the plugin manifest.
  plugin <- case producer of
    Just installed -> do
      runCommandWithEnv True "python3" ["-B", thcRoot </> "bin/plugin.py", "--root", thcRoot,
        "--check-installed", "--ghc", compiler, "--ghc-pkg", packageTool] thcRoot (Just environment)
      pure installed
    Nothing -> do
      requireFile buildPlugin
      let tools = thcRoot </> "build/compiler"
      createDirectoryIfMissing True tools
      withLock (tools </> "cabal-tools.lock") $ do
        runCommandWithEnv True buildPlugin [] thcRoot (Just environment)
        readJson (tools </> "plugin.json")
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
  source <- traverse canonicalizePath (runGhcSource opts)
  withProjectLock output $
    runBuiltProject action project working thcRoot runtime output native (runTarget opts) projectOptions
                    pluginDb pluginUnit pluginLibrary compiler (Just packageTool) (runInstalledCore opts)
                    source registeredLibrary (runVerifyArtifacts opts) launchEnvironment (runArguments opts)

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

-- Cabal 3.16's target command uses build's own selector functions. Its report
-- normalizes module/file targets to complete components. Resolve only its
-- fully-qualified forms against the plan, never stale build-info.
resolveBuildTargets :: FilePath -> [String] -> [String] -> [(String, String)] -> FilePath -> IO [Unit]
resolveBuildTargets working targets configuration environment native = do
  let cabal = maybe "cabal" id (lookup "CABAL" environment)
      requested = if null targets then ["."] else targets
  (status, output, diagnostic) <- readCreateProcessWithExitCode
    (proc cabal (["target"] ++ requested ++ configuration)) {cwd = Just working, env = Just environment} ""
  require (status == ExitSuccess) ("Cabal build target selection failed: " ++ diagnostic)
  let report = drop 1 (dropWhile (/= "Fully qualified target forms:") (lines output))
      forms = [target | line <- takeWhile (isPrefixOf " - ") report, Just target <- [stripPrefix " - " line]]
  require (not (null forms)) "Cabal target did not return supported local component targets"
  plan <- readJson (native </> "cache/plan.json")
  units <- filter unitLocal <$> (mapM readUnit =<< field plan "install-plan")
  named <- forM units $ \unit -> do name <- componentTarget unit; pure (name, unit)
  selected <- fmap concat $ forM forms $ \form -> do
    let matches = [unit | (name,unit) <- named, form == name]
    require (not (null matches)) ("Cabal target is not a supported local component: " ++ form)
    pure matches
  let unique = nubBy (\left right -> unitId left == unitId right) selected
      complete = case dropWhile (isPrefixOf " - ") report of
        summary:_ -> case words summary of
          "Found":count:noun:"matching":_ -> count == show (length unique) && noun `elem` ["target", "targets"]
          _ -> False
        _ -> False
  require complete "Cabal target selection includes unsupported non-local or ambiguous components"
  pure unique

componentTarget :: Unit -> IO String
componentTarget unit = do
  package <- field (unitValue unit) "pkg-name"
  component <- field (unitValue unit) "component-name"
  pure (package ++ ":" ++ if component == "lib" then "lib:" ++ package else component)

-- Resolve the actual selected compiler's companion before Cabal sees the
-- forwarding wrapper. The wrapper directory is not a GHC installation.
selectedPackageTool :: FilePath -> Maybe FilePath -> IO FilePath
selectedPackageTool ghc requested = do
  inherited <- lookupEnv "GHC_PKG"
  path <- case requested `orElse` inherited of
    Just value -> do
      requestedPkg <- if takeFileName value == value then pure value else makeAbsolute value
      findExecutable requestedPkg >>= maybe (fail "selected ghc-pkg executable not found") pure
    Nothing -> do
      let suffix = if Host.os == "mingw32" then ".exe" else ""
          names = [name ++ suffix | name <- ["ghc-pkg-9.14.1", "ghc-pkg"]]
          candidates = map (takeDirectory ghc </>) names
      available <- filterM doesFileExist candidates
      case available of
        value:_ -> pure value
        [] -> do
          onPath <- mapM findExecutable names
          case [value | Just value <- onPath] of
            value:_ -> pure value
            [] -> fail "selected ghc-pkg executable not found beside GHC or on PATH"
  packageTool <- makeAbsolute path
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

-- Prepare only the genuine helper and selected interface view. Installed Core
-- acquisition may overlap native work only for this already-installed closure.
prepareProjectInterfaceView :: ExportContext -> Maybe Value -> String -> Maybe FilePath -> String ->
                               Map.Map String Unit -> [Unit] -> IO (InstalledContext, InstalledContext, [Value])
prepareProjectInterfaceView context producer installedPolicy ghcSource registeredLibrary byId installedUnits = do
  originalContext <- prepareInterfaceHelper context (contextRoot context)
  support <- case producer of
    Nothing -> pure []
    Just value -> do
      manifest <- readJson =<< field value "runtimeSupport"
      field manifest "units"
  let supportFor identifier = case [record | record <- support, jsonField record "id" == Just identifier] of
        [record] -> Just record
        _ -> Nothing
  originalRegistrations <- case installedUnits of
    [] -> pure []
    _ -> do
      discover <- registrationSnapshot originalContext
      mapM (discover . unitId) installedUnits
  validateReexports originalRegistrations
  forM_ originalRegistrations $ \registrationUnit -> do
    planned <- maybe (fail "installed registration not in Cabal plan") pure (Map.lookup (registeredId registrationUnit) byId)
    require (sort (unitDepends planned) == sort (installedDepends registrationUnit))
      ("installed dependencies differ from Cabal plan for " ++ registeredId registrationUnit)
  let acquisitionRegistrations = [registrationUnit | registrationUnit <- originalRegistrations,
        supportFor (registeredId registrationUnit) == Nothing]
  helperContext <- if installedPolicy == "pinned"
    then preparePinnedInterfaces (contextCache context) (contextPluginDb context) (contextPluginUnit context) (contextPluginLibrary context) originalContext acquisitionRegistrations
    else case ghcSource of
      Nothing -> pure originalContext
      Just source -> prepareForeignInterfaces
        (ForeignCompiler (contextGhc context) (contextPluginDb context) (contextPluginUnit context) (contextPluginLibrary context) registeredLibrary (contextDriverHash context))
        (contextCache context) source originalContext originalRegistrations
  pure (originalContext, helperContext, support)

-- The selected already-installed closure has no dependency on native-created
-- products. Discover/read/lock outside admission; only ready helper processes
-- consume the actual Cabal budget. Final plan reconciliation happens at caller.
acquireProjectInstalled :: Admission -> ExportContext -> FilePath -> String -> Map.Map String Unit -> [Unit] ->
                           (InstalledContext, InstalledContext, [Value]) ->
                           IO (Map.Map String (InstalledUnit, InstalledSource), InstalledContext, [Value], [InstalledUnit], [(FilePath, BS.ByteString)])
acquireProjectInstalled admission context layout installedPolicy byId installedUnits
                        (originalContext, helperContext, support) = do
  capturedPath <- lookupEnv "THC_CAPTURED_STORE_BUNDLES"
  let supportFor identifier = case [record | record <- support, jsonField record "id" == Just identifier] of
        [record] -> Just record
        _ -> Nothing
  traceAdmission "installed-original-registration-start" Nothing
  originalRegistrations <- case installedUnits of
    [] -> pure []
    _ -> do
      discover <- registrationSnapshot originalContext
      mapM (discover . unitId) installedUnits
  validateReexports originalRegistrations
  forM_ originalRegistrations $ \registrationUnit -> do
    planned <- maybe (fail "installed registration not in Cabal plan") pure (Map.lookup (registeredId registrationUnit) byId)
    require (sort (unitDepends planned) == sort (installedDepends registrationUnit))
      ("installed dependencies differ from Cabal plan for " ++ registeredId registrationUnit)
  traceAdmission "installed-original-registration-end" Nothing
  traceAdmission "installed-selected-registration-start" Nothing
  let selectedContext unit = if supportFor (unitId unit) == Nothing then helperContext else originalContext
  snapshots <- forM (nub (map selectedContext installedUnits)) $ \snapshotContext ->
    (,) snapshotContext <$> registrationSnapshot snapshotContext
  registrations <- forM installedUnits $ \unit ->
    maybe (fail "missing selected installed registration snapshot") ($ unitId unit)
      (lookup (selectedContext unit) snapshots)
  validateReexports registrations
  traceAdmission "installed-selected-registration-end" Nothing
  traceAdmission "installed-input-snapshot-start" Nothing
  inputSnapshot <- installedInputSnapshot
    [(originalContext, originalRegistrations), (helperContext, registrations)]
  traceAdmission "installed-input-snapshot-end" Nothing
  retained <- case capturedPath of
    Nothing -> pure Nothing
    Just path -> do
      supplied <- readJson path
      case jsonField supplied "installed" :: Maybe Value of
        Nothing -> pure Nothing
        Just _ -> do
          when (installedPolicy == "pinned") $ do
            request <- field supplied "request"
            require (jsonField request "coreInterfaceView" == Just (installedViewIdentity helperContext))
              "captured installed Core uses a different pinned interface view"
          Just <$> readCapturedInstalledBundles (contextVerifyArtifacts context) (installedCompiler originalContext) originalRegistrations path
  (bundles, demandInputs) <- case retained of
    Just selectedInstalled -> pure ([(identifier, (unit, InstalledCBD artifact)) |
      (identifier, (unit, artifact)) <- Map.toList selectedInstalled], [])
    Nothing -> do
      (demandInputs, demandUnits) <- if installedPolicy == "demand"
        then prepareInstalledDemandWithAdmission admission helperContext [r | r <- registrations, supportFor (registeredId r) == Nothing] else pure ([], Map.empty)
      let acquireSource registrationUnit = case Map.lookup (registeredId registrationUnit) demandUnits of
              Just record -> do
                owner <- field record "id"
                pure (InstalledInterfaces owner record)
              Nothing -> do
                result <- prepareInstalledBundleWithAdmission admission (contextVerifyArtifacts context) (contextNativeTools context)
                  (contextCache context) (contextNative context </> "cache/thc/staging") layout helperContext registrationUnit
                either (\missing -> fail
                  ("complete-interface-core unavailable for " ++ missingUnit missing ++ ":" ++ missingModule missing ++
                   " (" ++ missingInterface missing ++ "). Build the libraries with -fwrite-if-simplified-core or select --installed-core pinned.")) (pure . InstalledCBD) result
      bundles <- forM registrations $ \registrationUnit -> do
        planned <- maybe (fail "installed registration not in Cabal plan") pure
          (Map.lookup (registeredId registrationUnit) byId)
        require (sort (unitDepends planned) == sort (installedDepends registrationUnit))
          ("installed dependencies differ from Cabal plan for " ++ registeredId registrationUnit)
        source <- case supportFor (registeredId registrationUnit) of
          Just record -> pure (InstalledSupport (registeredId registrationUnit) record)
          Nothing -> acquireSource registrationUnit
        pure (registeredId registrationUnit, (registrationUnit, source))
      pure (bundles, demandInputs)
  let owners = map (installedSourceOwner . snd . snd) bundles
      registered = map fst bundles
  require (length owners == length (nub owners)) "multiple installed registrations claim one Core owner"
  require (all (\(identifier, (_, item)) -> installedSourceOwner item == identifier ||
                installedSourceOwner item `notElem` registered && Map.notMember (installedSourceOwner item) byId) bundles)
    "installed Core owner collides with another Cabal unit"
  pure (Map.fromList bundles, helperContext, demandInputs, originalRegistrations, inputSnapshot)

withOriginalStoreCapture :: ExportContext -> InstalledContext -> Map.Map String Unit -> [Unit] ->
                            FilePath -> [(String, String)] ->
                            (Maybe OriginalStoreCapture -> [(String, String)] -> IO a) -> IO a
withOriginalStoreCapture context helper planned selected project environment body = do
  supplied <- lookupEnv "THC_CAPTURED_STORE_BUNDLES"
  candidates <- if supplied /= Nothing then pure [] else filterM immutable selected
  located <- forM candidates $ \unit -> do
    (buildKey, exportKey, path) <- globalLocation context planned Map.empty unit
    present <- doesFileExist path
    ready <- if present then readGlobalForReplay context path (unitId unit) (unitDepends unit) buildKey exportKey
      else pure Nothing
    pure (unit, buildKey, exportKey, path, ready)
  let missing = [(unit, buildKey, exportKey) | (unit, buildKey, exportKey, _, Nothing) <- located]
      warm = Map.fromList [(unitId unit, replayInterfacePath path) |
        (unit, _, _, path, Just bundle) <- located, not (null (bundleModules bundle))]
      scrub = filter ((`notElem` ["THC_PROXY_CORE_LIBDIR", "THC_PROXY_CORE_DATABASES", "THC_PROXY_ORIGINAL_BUILD", "THC_PROXY_BUILD_ADMISSION"]) . fst)
  if null missing then body Nothing (scrub environment) else do
    let root = contextNative context </> "cache/thc/staging"
    createDirectoryIfMissing True root
    bracket (temporary root) (const (pure ())) $ \capture -> do
      let overrides = [("THC_PROXY_ROOT", contextRoot context),
            ("THC_PROXY_GHC_PKG", maybe (takeDirectory (contextGhc context) </> "ghc-pkg") id (contextGhcPkg context)),
            ("THC_PROXY_CORE_INTERFACES", Text.unpack (Text.decodeUtf8 (BL.toStrict (encode warm)))),
            ("THC_PROXY_CAPTURE", capture), ("THC_PROXY_ORIGINAL_BUILD", "1"),
            ("THC_PROXY_GLOBAL_UNITS", unlines [unitId unit | (unit, _, _) <- missing]),
            ("THC_PROXY_INTERFACE_HELPER", installedHelper helper),
            ("THC_PROXY_INTERFACE_LIBDIR", installedLibdir helper)] ++
            concat [[("THC_PROXY_CORE_LIBDIR", installedLibdir view),
              ("THC_PROXY_CORE_DATABASES", Text.unpack (Text.decodeUtf8 (BL.toStrict (encode (helperDatabases view)))))] |
              Just view <- [contextCoreView context]]
          captureEnvironment = overrides ++ filter ((`notElem` map fst overrides) . fst) (scrub environment)
          cabal = maybe "cabal" id (lookup "CABAL" environment)
      result <- (do
        (status, output, diagnostic) <- readCreateProcessWithExitCode
          (proc cabal (["path", "--output-format=json", "--store-dir"] ++ contextProjectOptions context ++
            ["--builddir", contextNative context])) {cwd = Just project, env = Just captureEnvironment} ""
        require (status == ExitSuccess) ("cannot query selected Cabal store: " ++ diagnostic)
        paths <- either fail pure (eitherDecodeStrict' (Text.encodeUtf8 (Text.pack output)) :: Either String Value)
        store <- field paths "store-dir"
        require (isAbsolute store) "Cabal selected a relative native store"
        let original = OriginalStoreCapture capture planned
              (Map.fromList [(unitId unit, (buildKey, exportKey)) | (unit, buildKey, exportKey) <- missing]) store
        body (Just original) captureEnvironment
        ) `onException` do
          exists <- doesDirectoryExist capture
          when exists $ do
            _ <- tryIOError (hPutStrLn stderr ("Original Cabal capture retained after failure: " ++ capture))
            pure ()
      exists <- doesDirectoryExist capture
      when exists (removePathForcibly capture)
      pure result
  where
    immutable unit
      | jsonField (unitValue unit) "type" /= Just ("configured" :: String) ||
        jsonField (unitValue unit) "style" /= Just ("global" :: String) = pure False
      | otherwise = all (\dependency -> not (unitLocal dependency || sourceInplace dependency)) <$>
          dependencyClosure planned (unitId unit)
    temporary root = do
      (path, handle) <- openTempFile root "original-store-capture-"
      hClose handle
      removeFile path
      createDirectory path
      pure path

runBuiltProject :: ProjectAction -> FilePath -> FilePath -> FilePath -> FilePath -> FilePath -> FilePath ->
                   String -> [String] -> FilePath -> String -> FilePath -> FilePath ->
                   Maybe FilePath -> String -> Maybe FilePath -> FilePath -> Bool -> [(String, String)] -> [String] -> IO ()
runBuiltProject action project working thcRoot runtime output native target projectOptions
                pluginDb pluginUnit pluginLibrary ghc ghcPkg installedPolicy ghcSource registeredLibrary verifyArtifacts launchEnvironment guestArguments = do
  driver <- getExecutablePath
  producer <- installedProducer thcRoot
  layout <- maybe (pure (thcRoot </> "src/driver/cbits/target-layout.c")) (`field` "targetLayout") producer
  let proxy = native </> "cache/thc/native-ghc"
      receipts = native </> "cache/thc/native-recipes-v1"
      packageTool = maybe (takeDirectory ghc </> "ghc-pkg") id ghcPkg
      configuration = projectOptions ++ ["--package-db=" ++ pluginDb | producer /= Nothing] ++ ["--builddir", native,
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
                   ("THC_PROXY_CAPTURE", native </> "cache/thc/capture"),
                   ("THC_PROXY_PLUGIN_DB", pluginDb), ("THC_PROXY_PLUGIN_UNIT", pluginUnit),
                   ("THC_PROXY_PLUGIN_LIBRARY", pluginLibrary),
                   ("THC_PROXY_NO_LINK_UNIT", ""),
                   ("THC_PROXY_NATIVE_PIECES", native </> "cache/thc/native-pieces-v1")]
      selectionEnvironment = overrides ++ filter (\(key, _) -> key `notElem` ("THC_PROXY_BUILD_ADMISSION" : map fst overrides)) inherited
      cabal = maybe "cabal" id (lookup "CABAL" selectionEnvironment)
  selectedUnits <- case action of
    BuildTargets _ targets -> resolveBuildTargets working targets configuration selectionEnvironment native
    _ -> pure <$> resolveRunnable working target configuration selectionEnvironment native
  selectionPlan <- readJson (native </> "cache/plan.json")
  forM_ producer $ \installed -> do
    identity <- field installed "compiler"
    require (jsonField identity "id" == (jsonField selectionPlan "compiler-id" :: Maybe String) &&
      jsonField identity "abi" == (jsonField selectionPlan "compiler-abi" :: Maybe String) &&
      jsonField identity "arch" == (jsonField selectionPlan "arch" :: Maybe String) &&
      jsonField identity "os" == (jsonField selectionPlan "os" :: Maybe String))
      "installed THC producer differs from Cabal's selected compiler ABI/platform"
  selectionUnits <- mapM readUnit =<< field selectionPlan "install-plan"
  let selectionById = Map.fromList [(unitId unit, unit) | unit <- selectionUnits]
  require (Map.size selectionById == length selectionUnits) "Cabal plan has duplicate unit IDs"
  noLinkUnits <- fmap concat $ forM selectedUnits $ \unit -> do
    closure <- dependencyClosure selectionById (unitId unit)
    let guestOnly = any (\dependency -> jsonField (unitValue dependency) "pkg-name" == Just ("thc" :: String) &&
          jsonField (unitValue dependency) "component-name" == Just ("lib:interop" :: String)) closure
        runnable = maybe False ((`elem` ["exe", "test", "bench"]) . takeWhile (/= ':'))
          (jsonField (unitValue unit) "component-name" :: Maybe String)
    pure [unitId unit | guestOnly && runnable]
  let initialEnvironment = ("THC_PROXY_NO_LINK_UNIT", unlines noLinkUnits) :
        filter ((/= "THC_PROXY_NO_LINK_UNIT") . fst) selectionEnvironment
  targetComponents <- forM selectedUnits $ \unit -> do
    name <- componentTarget unit
    component <- field (unitValue unit) "component-name"
    require (takeWhile (/= ':') component `elem` ["lib", "exe", "test", "bench"])
      ("Core acquisition does not support Cabal component " ++ name)
    pure name
  selectionCompiler <- field selectionPlan "compiler-id"
  selectionAbi <- field selectionPlan "compiler-abi"
  selectionOs <- field selectionPlan "os"
  selectionArch <- field selectionPlan "arch"
  selectionCache <- coreCacheDirectory
  selectionDriverHash <- digestFile driver
  selectionNativeTools <- nativeToolIdentity
  let selectionContext = ExportContext selectionCompiler selectionAbi (selectionArch ++ "-" ++ selectionOs)
        pluginDb pluginUnit pluginLibrary native selectionCache selectionDriverHash ghc ghcPkg driver thcRoot
        projectOptions selectionNativeTools verifyArtifacts noLinkUnits Nothing Map.empty
  selectionOrdered <- nubBy (\left right -> unitId left == unitId right) . concat <$>
    mapM (dependencyClosure selectionById . unitId) selectedUnits
  let selectionInstalled = [unit | unit <- selectionOrdered,
        jsonField (unitValue unit) "type" == Just ("pre-existing" :: String)]
  preparedInterfaces <- prepareProjectInterfaceView selectionContext producer installedPolicy ghcSource
    registeredLibrary selectionById selectionInstalled
  let (_, selectedView, _) = preparedInterfaces
      captureContext = selectionContext {contextCoreView = if installedPolicy == "pinned" then Just selectedView else Nothing}
      selectedHelper = if installedPolicy == "pinned" then selectedView else let (helper, _, _) = preparedInterfaces in helper
  withOriginalStoreCapture captureContext selectedHelper selectionById selectionOrdered project initialEnvironment $ \originalCapture environment -> do
    (initialInstalled, initialHelper, initialInputs, originalRegistrations, inputSnapshot) <-
      withBuildAdmission (native </> "cache/thc/staging")
        (\handoff -> runCommandWithEnv True cabal
          (["build"] ++ targetComponents ++ ["--enable-build-info"] ++ configuration) project
          (Just (("THC_PROXY_BUILD_ADMISSION", handoff) :
            filter ((/= "THC_PROXY_BUILD_ADMISSION") . fst) environment)))
        (\admission -> acquireProjectInstalled admission selectionContext layout installedPolicy
          selectionById selectionInstalled preparedInterfaces)
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
                                pluginLibrary native cacheRoot driverHash ghc ghcPkg driver thcRoot projectOptions nativeTools verifyArtifacts noLinkUnits Nothing Map.empty
    records <- field plan "install-plan" :: IO [Value]
    units <- mapM readUnit records >>= concreteProjectUnits context
    let byId = Map.fromList [(unitId unit, unit) | unit <- units]
    require (Map.size byId == length units) "Cabal plan has duplicate unit IDs"
    selected <- forM selectedUnits $ \before -> do
      after <- maybe (fail "Cabal changed the selected unit identity during its build") pure
        (Map.lookup (unitId before) byId)
      oldTarget <- componentTarget before
      newTarget <- componentTarget after
      require (oldTarget == newTarget) "Cabal changed the selected component during its build"
      pure after
    -- Stale build-info for previously built targets must not broaden acquisition.
    ordered <- nubBy (\left right -> unitId left == unitId right) . concat <$>
      mapM (dependencyClosure byId . unitId) selected
    builtLocals <- filterM (\unit -> case jsonField (unitValue unit) "build-info" of
      Nothing -> pure False
      Just path -> doesFileExist path) (filter unitLocal units)
    builtComponents <- forM builtLocals $ \unit -> do
      component <- readComponentMetadata (unitId unit `elem` map unitId ordered) unit context
      pure (unit, component)
    prepareBackpackSignatures context byId ordered builtComponents
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
        let setupTarget = if componentName == "lib" then "lib:" ++ packageName else componentName
        -- v2-build's monitor can say "up to date" after an intermediate .o is
        -- deleted. Ask this same Cabal CLI's Simple Setup to build the component
        -- directly; it owns and reads its own configured build representation.
        runCommandWithEnv True cabal ["act-as-setup", "--build-type=Simple", "--", "build",
          "--builddir=" ++ dist, setupTarget] sourceRoot (Just environment)
    let globals = [unit | unit <- ordered, not (unitLocal unit),
                         jsonField (unitValue unit) "type" == Just ("configured" :: String)]
    capturedPath <- lookupEnv "THC_CAPTURED_STORE_BUNDLES"
    let installedUnits = [unit | unit <- ordered,
          jsonField (unitValue unit) "type" == Just ("pre-existing" :: String)]
        installedScope = sort [(unitId unit, sort (unitDepends unit)) | unit <- installedUnits]
        initialScope = sort [(unitId unit, sort (unitDepends unit)) | unit <- selectionInstalled]
    let samePreparation = installedScope == initialScope && driverHash == selectionDriverHash &&
          nativeTools == selectionNativeTools && contextCompiler context == contextCompiler selectionContext &&
          contextAbi context == contextAbi selectionContext && contextPlatform context == contextPlatform selectionContext
    finalPrepared@(originalContext, finalHelper, _) <- prepareProjectInterfaceView context producer
      installedPolicy ghcSource registeredLibrary byId installedUnits
    currentRegistrations <- case installedUnits of
      [] -> pure []
      _ -> do
        discover <- registrationSnapshot originalContext
        mapM (discover . unitId) installedUnits
    currentInputSnapshot <- forM inputSnapshot $ \(path, _) -> (,) path <$> digestFile path
    (installed, acquiredContext, interfaceInputs) <-
      if samePreparation && currentInputSnapshot == [(path, digestHex digest) | (path, digest) <- inputSnapshot] && preparedInterfaces == finalPrepared && currentRegistrations == originalRegistrations then do
        let registrations = map fst (Map.elems initialInstalled)
            observedContext registrationUnit = if registrationUnit `elem` originalRegistrations
              then originalContext else finalHelper
        snapshots <- forM (nub (map observedContext registrations)) $ \snapshotContext ->
          (,) snapshotContext <$> registrationSnapshot snapshotContext
        forM_ registrations $ \registrationUnit -> do
          current <- maybe (fail "missing final installed registration snapshot") ($ registeredId registrationUnit)
            (lookup (observedContext registrationUnit) snapshots)
          require (current == registrationUnit) "selected installed interface view changed during native build"
        pure (initialInstalled, initialHelper, initialInputs)
      else do
        -- A concrete post-build plan may legitimately differ (for example
        -- Backpack instantiation). Do not use speculative results in it.
        (completed, helper, inputs, _, _) <- acquireProjectInstalled localAdmission context layout
          installedPolicy byId installedUnits finalPrepared
        pure (completed, helper, inputs)
    let coreContext = context { contextCoreView = if installedPolicy == "pinned"
          then Just acquiredContext else Nothing }
    captured <- traverse (\_ -> prepareGlobalBundles coreContext originalCapture project targetComponents byId localComponents globals) capturedPath
    globalBundles <- maybe (prepareGlobalBundles coreContext originalCapture project
      targetComponents byId localComponents globals) pure captured
    -- All original products have now been published or replaced by the
    -- isolated fallback; guest execution no longer owns this private stage.
    forM_ originalCapture $ \capture -> removePathForcibly (originalCaptureDirectory capture)
    let sourceInterfaces = Map.map (replayInterfacePath . bundlePath)
          (Map.filter (not . null . bundleModules) globalBundles)
    (_, _, described) <- foldlM (\(keys, interfaces, acc) unit -> do
      kind <- optionalField (unitValue unit) "type" ("" :: String)
      bundle <- if unitLocal unit
        then Just <$> exportUnit coreContext {contextCoreInterfaces = interfaces} roots keys unit
        else do
          if kind == "configured" then Just <$> maybe
            (fail ("Core bundle missing for Cabal store component " ++ unitId unit)) pure
            (Map.lookup (unitId unit) globalBundles)
          else pure $ case snd <$> Map.lookup (unitId unit) installed of
            Just (InstalledCBD artifact) -> Just (installedBundle artifact)
            _ -> Nothing
      forM_ (maybe [] bundleReexports bundle) $ \(_, provider, name) -> do
        dependencies <- dependencyClosure byId (unitId unit)
        let owner = maybe provider (installedSourceOwner . snd) (Map.lookup provider installed)
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
          interfaces' = if unitLocal unit then maybe interfaces
            (\item -> if null (bundleModules item) then interfaces else
              Map.insert (unitId unit) (replayInterfacePath (bundlePath item)) interfaces) bundle else interfaces
          recordsForUnit = maybe [object fields] (uncurry installedSourceRecords) (Map.lookup (unitId unit) installed)
      pure (keys', interfaces', acc ++ recordsForUnit)) (Map.empty, sourceInterfaces, []) ordered
    let manifest = output </> "packages.json"
        -- Both providers retain GHC's generated main wrapper and Handle shutdown.
        entry = "main::Main.main"
        shutdown = "ghc-internal:GHC.Internal.TopHandler.flushStdHandles"
        audit = output </> "audit.json"
        publish path bridge suppliedUnits = do
          published <- mapM (publishCoreUnit cacheRoot verifyArtifacts) suppliedUnits
          atomicJson path (object ["format" .= ("thc-core-packages" :: String),
            "schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
            "foreignExceptionBridgeUnit" .= (bridge :: Maybe String),
            "interfaceInputs" .= interfaceInputs, "units" .= published])
        link = linkForeignExceptionRuntime coreContext environment installedPolicy ghcSource registeredLibrary
    if buildsNativeImages action then do
      -- This union is acquisition inventory. Only each runnable's own closure
      -- can select its Main wrapper and exact exception bridge without ambiguity.
      publish manifest Nothing described
      let owners = Map.map (installedSourceOwner . snd) installed
          runnable unit = maybe False ((`elem` ["exe", "test", "bench"]) . takeWhile (/= ':'))
            (jsonField (unitValue unit) "component-name" :: Maybe String)
      forM_ (filter runnable selected) $ \unit -> do
        component <- field (unitValue unit) "component-name"
        let directory = output </> "native-images" </> shaHex (Text.encodeUtf8 (Text.pack (unitId unit)))
            packages = directory </> "packages.json"
            program = reverse (takeWhile (/= ':') (reverse component))
        componentRecords <- componentCoreRecords owners (unitId unit) described
        (bridge, linked) <- link componentRecords
        createDirectoryIfMissing True directory
        publish packages (Just bridge) linked
        buildNativeImage thcRoot directory program packages
    else do
      (bridge, linked) <- link described
      publish manifest (Just bridge) linked
    when (action == RunGuest) $ do
      when verifyArtifacts $
        runCommand True "python3" (["-B", thcRoot </> "bin/audit-core.py", "--runtime", runtime, "--package-manifest", manifest,
                                  "--entry", entry, "--entry", shutdown] ++
                                 ["--io-main", "--output", audit]) thcRoot
      -- Full-Core main and shutdown share one program and its Handle CAFs.
      -- Like cabal run, preserve the caller's cwd even with --project-dir.
      selectedComponent <- case selected of
        [unit] -> field (unitValue unit) "component-name"
        _ -> fail "run requires exactly one runnable component"
      let programName = reverse (takeWhile (/= ':') (reverse selectedComponent))
      runCommandWithEnv False runtime (["--allow-interface-helper" | installedPolicy == "demand"] ++ runtimeLaunchArguments verifyArtifacts
        ["--run-executable", '@' : manifest, entry, shutdown] programName guestArguments) working (Just launchEnvironment)

-- | Select the original exception bridge and optional weak finalizer ABI modules.
-- The flag requests full artifact verification rather than replaying an
-- unchanged successful read.
exceptionBridgeModules :: Bool -> [Value] -> IO [Value]
exceptionBridgeModules verify units = fmap concat $ forM units $ \unit -> do
  references <- optionalField unit "modules" ([] :: [Value])
  let selected = [ref | ref <- references, jsonField ref "name" `elem`
        map Just (["THC.Exception", "THC.Internal.Exception", "THC.Internal.Weak"] :: [String])]
  if null selected then pure [] else if all (\ref -> jsonField ref "compact" /= (Nothing :: Maybe Value)) selected
    then forM selected $ \ref -> do
      compact <- field ref "compact"
      path <- field compact "path"
      expected <- field compact "sha256"
      bytes <- BS.readFile path
      require (shaHex bytes == expected) "installed exception runtime module hash mismatch"
      value <- either fail pure (snd <$> readModuleMetadata bytes)
      require (jsonField value "unit" == (jsonField unit "id" :: Maybe String) &&
        jsonField value "module" == (jsonField ref "name" :: Maybe String))
        "installed exception runtime module identity mismatch"
      pure value
    else do
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
          value <- either fail pure (snd <$> readModuleMetadata body)
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
      producer <- installedProducer (contextRoot context)
      case producer of
        Just installed -> linkInstalledRuntime context installedPolicy ghcSource registeredLibrary described installed
        Nothing -> buildSidecar
  where
    buildSidecar = do
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
      dependencies <- do
          original <- prepareInterfaceHelper context root
          registrations <- mapM (discoverInstalled original . unitId) missing
          helper <- if installedPolicy == "pinned" then
            preparePinnedInterfaces (contextCache context)
              (contextPluginDb context) (contextPluginUnit context) (contextPluginLibrary context) original registrations
            else case ghcSource of
              Nothing -> pure original
              Just source -> prepareForeignInterfaces
                (ForeignCompiler compiler (contextPluginDb context) (contextPluginUnit context)
                  (contextPluginLibrary context) registeredLibrary (contextDriverHash context))
                (contextCache context) source original registrations
          fmap concat $ forM missing $ \unit -> do
            registrationUnit <- discoverInstalled helper (unitId unit)
            require (sort (unitDepends unit) == sort (installedDepends registrationUnit))
              "runtime sidecar installed dependencies differ from its plan"
            result <- prepareInstalledBundleWithVerification (contextVerifyArtifacts context) (contextNativeTools context)
              (contextCache context) (native </> "cache/thc/staging")
              (root </> "src/driver/cbits/target-layout.c") helper registrationUnit
            bundle <- either (\failure -> fail ("runtime sidecar lacks complete Core: " ++ show failure)) pure result
            pure (installedRecords registrationUnit bundle)
      bundle <- exportUnit context roots Map.empty runtime
      let record = object ["id" .= unitId runtime, "depends" .= unitDepends runtime,
            "modules" .= bundleModules bundle,
            "bundle" .= object ["path" .= bundlePath bundle, "sha256" .= bundleHash bundle]]
          linked = described ++ dependencies ++ [record]
          identities = [identifier | item <- linked, Just identifier <- [jsonField item "id" :: Maybe String]]
      require (length identities == length (nub identities)) "runtime sidecar collides with an existing Core owner"
      services <- exceptionBridgeModules (contextVerifyArtifacts context) linked
      require (any (\value -> jsonField value "unit" == Just (unitId runtime) &&
        jsonField value "module" == Just ("THC.Internal.Weak" :: String)) services)
        "runtime sidecar did not export its weak finalizer ABI module"
      selected <- either fail pure (foreignExceptionBridgeUnit services)
      require (selected == Just (unitId runtime)) "runtime sidecar did not export its genuine exception bridge"
      pure (unitId runtime, linked)

prepareInterfaceHelper :: ExportContext -> FilePath -> IO InstalledContext
prepareInterfaceHelper context root = do
  producer <- installedProducer root
  helper <- case producer of
    Just installed -> field installed "interfaceHelper"
    Nothing -> buildHelper
  requireFile helper
  let ghc = contextGhc context
      pkg = maybe (takeDirectory ghc </> "ghc-pkg") id (contextGhcPkg context)
  databases <- maybe (pure []) (\value -> (:[]) <$> field value "packageDb") producer
  original <- installedContext ghc pkg helper databases (object
    ["id" .= contextCompiler context, "abi" .= contextAbi context,
     "platform" .= contextPlatform context, "way" .= (exportInterfaceWay ++ "-nonprofiling")])
  pure (maybe original id (contextCoreView context))
  where
  buildHelper = do
    cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
    buildDirectory <- lookupEnv "THC_CABAL_BUILD_DIR"
    let ghc = contextGhc context
        pkg = maybe (takeDirectory ghc </> "ghc-pkg") id (contextGhcPkg context)
        selection = ["exe:thc-interface", "--with-compiler=" ++ ghc, "--with-hc-pkg=" ++ pkg] ++
          ["--disable-shared" | Host.os == "mingw32"] ++
          ["--builddir=" ++ directory | Just directory <- [buildDirectory]]
        tools = root </> "build/compiler"
    createDirectoryIfMissing True tools
    -- These tools share Cabal's root build tree with plugin publication. Release
    -- its lock before installed-Core probing and provider work.
    withLock (tools </> "cabal-tools.lock") $ do
      runCommand True cabal ("build" : selection) root
      (status, output, diagnostic) <- readCreateProcessWithExitCode
        (proc cabal ("list-bin" : selection)) {cwd = Just root} ""
      require (status == ExitSuccess) ("cannot locate selected thc-interface: " ++ diagnostic)
      helper <- case lines output of
        [path] -> canonicalizePath path
        _ -> fail "cabal list-bin did not return one thc-interface executable"
      requireFile helper
      pure helper

-- The descriptor is selected by root discovery. Its products are immutable;
-- validation never publishes a registry or falls back to a checkout build.
installedProducer :: FilePath -> IO (Maybe Value)
installedProducer root = do
  let path = root </> "installed-producer.json"
  present <- doesFileExist path
  if not present then pure Nothing else do
    value <- readJson path
    require (jsonField value "format" == Just ("thc-installed-producer" :: String) &&
      jsonField value "schema" == Just (1 :: Int)) "unsupported installed THC producer descriptor"
    pure (Just value)

linkInstalledRuntime :: ExportContext -> String -> Maybe FilePath -> FilePath -> [Value] -> Value -> IO (String, [Value])
linkInstalledRuntime context policy source library described producer = do
  manifest <- readJson =<< field producer "runtimeSupport"
  layout <- field producer "targetLayout"
  records <- field manifest "units" :: IO [Value]
  bridge <- field manifest "foreignExceptionBridgeUnit" :: IO String
  runtime <- case [record | record <- records, jsonField record "id" == Just bridge] of
    [record] -> pure record
    _ -> fail "installed runtime support must contain its genuine runtime unit"
  dependencies <- field runtime "depends" :: IO [String]
  original <- prepareInterfaceHelper context (contextRoot context)
  let present = [identifier | record <- described, Just identifier <- [jsonField record "id" :: Maybe String]]
      discover seen [] = pure seen
      discover seen (identifier:pending)
        | identifier `elem` present || any ((== identifier) . registeredId) seen = discover seen pending
        | otherwise = do
            registrationUnit <- discoverInstalled original identifier
            discover (seen ++ [registrationUnit]) (pending ++ installedDepends registrationUnit)
  registrations <- discover [] dependencies
  helper <- if policy == "pinned" then
    preparePinnedInterfaces (contextCache context) (contextPluginDb context) (contextPluginUnit context)
      (contextPluginLibrary context) original registrations
    else case source of
      Nothing -> pure original
      Just ghcSource -> prepareForeignInterfaces
        (ForeignCompiler (contextGhc context) (contextPluginDb context) (contextPluginUnit context)
          (contextPluginLibrary context) library (contextDriverHash context))
        (contextCache context) ghcSource original registrations
  acquired <- fmap concat $ forM registrations $ \originalRegistration -> do
    registrationUnit <- discoverInstalled helper (registeredId originalRegistration)
    require (sort (installedDepends registrationUnit) == sort (installedDepends originalRegistration))
      "installed runtime dependency changed while preparing its Core interfaces"
    result <- prepareInstalledBundleWithVerification (contextVerifyArtifacts context) (contextNativeTools context)
      (contextCache context) (contextNative context </> "cache/thc/staging")
      layout helper registrationUnit
    bundle <- either (\failure -> fail ("installed runtime dependency lacks complete Core: " ++ show failure)) pure result
    pure (installedRecords registrationUnit bundle)
  let linked = described ++ acquired ++ [runtime]
      identities = [identifier | record <- linked, Just identifier <- [jsonField record "id" :: Maybe String]]
  require (length identities == length (nub identities)) "installed runtime collides with an existing Core owner"
  services <- exceptionBridgeModules (contextVerifyArtifacts context) linked
  selected <- either fail pure (foreignExceptionBridgeUnit services)
  require (selected == Just bridge && any (\value -> jsonField value "unit" == Just bridge &&
    jsonField value "module" == Just ("THC.Internal.Weak" :: String)) services)
    "installed runtime lacks its genuine exception bridge or weak finalizer ABI"
  pure (bridge, linked)

-- This path adds installed bundle assembly to the pinned recipe's native/Core
-- dependencies. Main and Run do not construct these artifacts. Keep native
-- tools, interfaces, source observations and target-layout inputs separate.
installedBundleRecipeIdentity :: Value
installedBundleRecipeIdentity = object
  ["pinnedRecipe" .= pinnedRecipeIdentity, "sourceHash" .= recipeHash,
   "zip-archive" .= (VERSION_zip_archive :: String)]
  where
    recipeHash :: String
    recipeHash = $(do
      source <- loc_filename <$> TH.location
      let root = iterate takeDirectory source !! 5
          files = ["src/driver/THC/Driver/" ++ name | name <- ["Project.hs", "CoreIndex.hs", "CoreSymbols.hs", "Zip.hs"]]
      records <- forM files $ \name -> do
        let path = root </> name
        addDependentFile path
        bytes <- runIO (BS.readFile path)
        pure (name, BS.unpack (SHA.hash bytes))
      let bytes = SHA.hash (BL.toStrict (encode records))
      lift (concatMap (\byte -> let value = showHex byte "" in replicate (2 - length value) '0' ++ value)
            (BS.unpack bytes)))

-- | Publish one exact registered unit from complete retained Core. Declared
-- dependencies remain references; this does not acquire a runnable closure.
-- Input interfaces/databases are read-only. Only output/cache are writable.
exportInstalledUnit :: InstalledContext -> String -> FilePath -> FilePath -> FilePath -> IO ()
exportInstalledUnit context identifier layout output cache = do
  requireFile (installedHelper context)
  requireFile layout
  registrationUnit <- discoverInstalled context identifier
  -- Discovery fails before output creation for a missing requested unit.
  createDirectoryIfMissing True output
  withLock (output </> "export.lock") $ do
    acquired <- prepareInstalledBundle cache (output </> "staging") layout context registrationUnit
    artifact <- either (\missing -> fail
      ("complete-interface-core unavailable for " ++ missingUnit missing ++ ":" ++ missingModule missing ++
       " (" ++ missingInterface missing ++ "). Build the registered unit with -fwrite-if-simplified-core.")) pure acquired
    let records = installedRecords registrationUnit artifact
    bridge <- either fail pure . foreignExceptionBridgeUnit =<< exceptionBridgeModules True records
    published <- mapM (publishCoreUnit output True) records
    atomicJson (output </> "packages.json") (object ["format" .= ("thc-core-packages" :: String),
      "schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
      "foreignExceptionBridgeUnit" .= bridge, "units" .= published])

-- The probe retains complete installed source/native identities. A successful
-- selection receipt can then avoid reopening an unchanged archive; explicit
-- verification and misses still run the complete original validation.
prepareInstalledBundle :: FilePath -> FilePath -> FilePath -> InstalledContext -> InstalledUnit ->
                          IO (Either MissingCore InstalledBundle)
prepareInstalledBundle cache staging recipe context registrationUnit = do
  nativeTools <- nativeToolIdentity
  prepareInstalledBundleWithVerification True nativeTools cache staging recipe context registrationUnit

prepareInstalledBundleWithVerification :: Bool -> Value -> FilePath -> FilePath -> FilePath -> InstalledContext -> InstalledUnit ->
                          IO (Either MissingCore InstalledBundle)
prepareInstalledBundleWithVerification = prepareInstalledBundleWithAdmission localAdmission

prepareInstalledBundleWithAdmission :: Admission -> Bool -> Value -> FilePath -> FilePath -> FilePath -> InstalledContext -> InstalledUnit ->
                                      IO (Either MissingCore InstalledBundle)
prepareInstalledBundleWithAdmission admission verify nativeTools cache staging recipe context registrationUnit = do
  let producer = object ["recipe" .= installedBundleRecipeIdentity, "nativeTools" .= nativeTools]
  fullProbe <- prepareInstalledProbeWithAdmission admission context registrationUnit
  -- Ordinary builds reuse the exact validated inventory while its helper and
  -- raw interfaces retain their file observations. Verification always probes
  -- the contents; source text and native artifacts are validated below either way.
  let probeCurrent
        | verify = fullProbe
        | otherwise = do
            units <- probeClosure context registrationUnit
            let registrations = map (installedProvenance context) units
                paths = installedHelper context : concatMap (map snd . installedInterfaces) units
                request = object ["producer" .= producer, "helper" .= installedHelper context,
                  "requested" .= registeredId registrationUnit, "registrations" .= registrations]
                receipt = cache </> "installed-probes/v1/selections" </>
                  shaHex (BL.toStrict (encode request)) ++ ".json"
                checkInventory probe = require (jsonField probe "registrations" == Just registrations)
                  "installed probe registration inventory changed"
                validate = do
                  (helperHash, probe) <- fullProbe
                  -- The inner probe rediscovers registrations. Never record a
                  -- newer closure under the outer request's earlier identity.
                  checkInventory probe
                  pure (Just (BS.unpack helperHash, probe))
            -- File metadata does not reflect access changes. Keep the full
            -- probe's read-access requirement without rereading its contents.
            forM_ paths $ \path -> withBinaryFile path ReadMode (const (pure ()))
            selected <- rememberSelection False receipt paths request validate
            (helperHash, probe) <- maybe (fail "installed probe selection unavailable") pure selected
            require (length helperHash == 32) "invalid installed probe helper digest"
            checkInventory probe
            after <- probeClosure context registrationUnit
            require (after == units) "installed registrations changed while selecting probe"
            pure (BS.pack helperHash, probe)
  evidence <- optionalIO $ do
    recipeHash <- digestFile recipe
    (rtsRegistration, _) <- installedLayoutHeaders context registrationUnit
    before@(helperHash, _) <- probeCurrent
    let identity = object ["schema" .= (1 :: Int), "helperHash" .= digestHex helperHash,
          "producer" .= producer, "recipeHash" .= recipeHash,
          "rtsRegistration" .= rtsRegistration,
          "registration" .= installedProvenance context registrationUnit]
        index = cache </> "installed-probes/v1" </> shaHex (BL.toStrict (encode identity)) ++ ".json"
    pure (identity, before, index)
  case evidence of
    Nothing -> acquireInstalledBundle admission verify cache staging recipe producer context registrationUnit (\_ _ _ -> pure ())
    Just (identity, before@(_, probe), index) -> do
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
        require (after == before) "installed payload changed while validating cached bundle"
        validateSourceObservations sources
        validateNativeArtifacts
        pure (InstalledBundle owner bundle)
      case hit of
        Just bundle -> pure (Right bundle)
        Nothing -> acquireInstalledBundle admission verify cache staging recipe producer context registrationUnit $ \bundle inputs modules -> do
          _ <- optionalIO $ do
            sources <- installedSourceObservations modules
            validateSourceObservations sources
            after <- probeCurrent
            require (after == before) "installed payload changed during acquisition"
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
    sources <- either fail pure (readModuleSources bytes)
    forM sources $ \(SourceFile _ pathBytes contents) -> do
      path <- text pathBytes
      content <- case contents of
        Known value -> Just <$> text value
        _ -> pure Nothing
      record <- sourceObservation path content
      pure (path, record)
  let unique = Map.fromList observations
  require (all (\(path, record) -> Map.lookup path unique == Just record) observations)
    "source changed between installed module exports"
  pure (Map.elems unique)
  where text = either (fail . show) (pure . Text.unpack) . Text.decodeUtf8'

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
-- probe cannot establish complete evidence. GHC compiler binaries are not hashed.
acquireInstalledBundle :: Admission -> Bool -> FilePath -> FilePath -> FilePath -> Value -> InstalledContext -> InstalledUnit ->
                          (InstalledBundle -> Value -> [(String, BS.ByteString)] -> IO ()) ->
                          IO (Either MissingCore InstalledBundle)
acquireInstalledBundle admission verify cache staging recipe producer context registrationUnit remember = do
  acquired <- acquireInstalledWithAdmission admission context registrationUnit
  case acquired of
    Left missing -> pure (Left missing)
    Right core -> do
      -- No token is held while waiting. Native packaging has its existing
      -- serial process owners; only interface helpers overlap this build.
      waitForNative admission
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
              concatMap (\path -> ["-package-db", path]) (helperDatabases context) ++
              ["-package-id", registered] ++ map ("-I" ++) includes
        require (all cacheName [compilerId, compilerAbi, compilerPlatform, registered])
          "installed Core compiler or registered package-cache identity is invalid"
        configured <- case installedSource context of
          Nothing -> pure Nothing
          Just source -> configuredNativeArchive (captureConfiguredNativeObject (nativeDirectory </> "pieces"))
            source (nativeDirectory </> "configured") (installedCompiler context) unit (registration registrationUnit)
        let nativeArguments = maybe [] (\(archive,_,_) -> ["-optl" ++ archive]) configured ++ arguments
            configuredInputs = maybe [] (\(_,inputs,_) -> inputs) configured
            configuredProduct = configured >>= (\(_,_,ownedProduct) -> ownedProduct)
            -- Windows installed adapters execute MSVC LLVM and call the actual
            -- MinGW archive through the existing private PE companion. Keep C
            -- globals/lifecycle there once; MinGW LLVM is not a Sulong input.
            -- Its complete configured inventory remains build provenance below.
            linkedProduct = if compilerPlatform == "x86_64-windows" then Nothing else configuredProduct
        createDirectory (temporary </> "core")
        staged <- forM (zip [0 :: Int ..] modules) $ \(index, (name, bytes)) -> do
          let path = temporary </> "core" </> show index <.> "cbd"
          BS.writeFile path bytes
          pure (name, path)
        linked <- linkInstalledNativeWithProduct (installedGhc context) (installedPackageTool context) (installedLibdir context)
          nativeArguments nativeDirectory unit linkedProduct staged
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
            exporter = object ["helperHash" .= helperHash, "producer" .= producer,
              "options" .= (["post-tidy", "unit-qualified", "source-notes", interfaceWayName (installedInterfaceWay context)] :: [String]),
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
              (refs, members) <- packageCoreFiles linked
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

installedSourceRecords :: InstalledUnit -> InstalledSource -> [Value]
installedSourceRecords registrationUnit (InstalledCBD artifact) = installedRecords registrationUnit artifact
installedSourceRecords _ (InstalledSupport _ record) = [record]
installedSourceRecords registrationUnit (InstalledInterfaces owner record) =
  [object ["id" .= registeredId registrationUnit, "depends" .= installedDepends registrationUnit,
           "modules" .= ([] :: [Value])] | owner /= registeredId registrationUnit] ++ [record]

installedRecords :: InstalledUnit -> InstalledBundle -> [Value]
installedRecords registrationUnit artifact =
  [object ["id" .= registeredId registrationUnit, "depends" .= installedDepends registrationUnit,
           "modules" .= ([] :: [Value])] | installedOwner artifact /= registeredId registrationUnit] ++
  [object ["id" .= installedOwner artifact, "depends" .= installedDepends registrationUnit,
      "modules" .= bundleModules (installedBundle artifact),
      "bundle" .= object ["path" .= bundlePath (installedBundle artifact), "sha256" .= bundleHash (installedBundle artifact)]]]

-- The ordinary pinned-source recipe already owns the complete Cabal C/C++
-- inventory and its configured native archive. Acquire through that recipe
-- before projecting Windows runtime modules; do not relink a private subset
-- against the whole wired Core owner.
wiredGhcInternal :: ExportContext -> FilePath -> String -> Maybe FilePath -> String -> IO (InstalledContext, Value)
wiredGhcInternal context root policy source registeredUnit = do
  require (Host.os == "mingw32" && contextPlatform context == "x86_64-windows")
    "Windows wired native acquisition requires the selected native compiler"
  specification <- readJson (root </> "etc/ghc/9.14.1/windows-ghc-internal.json")
  native <- prepareInterfaceHelper context root
  original <- case source of
    Nothing -> pure native
    Just path -> do
      (selected, proof) <- configuredSourceView native path
      atomicJson (contextNative context </> "configured-source-proof.json") proof
      pure selected
  registered <- discoverInstalled original registeredUnit
  prepared <- if policy == "pinned" && source == Nothing then preparePinnedInterfaces (contextCache context)
      (contextPluginDb context) (contextPluginUnit context) (contextPluginLibrary context)
      original [registered]
    else case source of
      Nothing -> pure original
      Just path -> prepareForeignInterfaces
        (ForeignCompiler (contextGhc context) (contextPluginDb context) (contextPluginUnit context)
          (contextPluginLibrary context) (contextPluginLibrary context) (contextDriverHash context))
        (contextCache context) path original [registered]
  selected <- discoverInstalled prepared (registeredId registered)
  result <- prepareInstalledBundleWithVerification (contextVerifyArtifacts context) (contextNativeTools context)
    (contextCache context) (contextNative context </> "cache/thc/staging")
    (root </> "src/driver/cbits/target-layout.c") prepared selected
  artifact <- either (fail . show) pure result
  require (installedOwner artifact == "ghc-internal")
    "Pinned native source acquisition changed the genuine wired Core owner"
  let full = installedBundle artifact
  projection <- projectWindowsWiredBundle (contextVerifyArtifacts context)
    (takeDirectory (bundlePath full)) full specification
  pure (prepared, object ["id" .= installedOwner artifact, "depends" .= installedDepends selected,
    "modules" .= bundleModules projection,
    "bundle" .= object ["path" .= bundlePath projection, "sha256" .= bundleHash projection]])


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
  -- Ordinary installed acquisition records its source/native inputs in the
  -- complete bundle; it does not add the old wired recipe's generated list.
  generated <- optionalField originalInputs "generatedSources" [] :: IO [Value]
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
    value <- either fail pure (snd <$> readModuleMetadata body)
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

-- Cabal's plan records component dependencies before Backpack instantiation.
-- Built compiler inputs name the concrete units; an instantiated library's
-- registration also carries providers used only to fill its signatures.
concreteProjectUnits :: ExportContext -> [Unit] -> IO [Unit]
concreteProjectUnits context units = do
  let knownUnits = Set.fromList (map unitId units)
  built <- filterM (\unit -> case jsonField (unitValue unit) "build-info" of
    Just path | unitLocal unit -> doesFileExist path
    _ -> pure False) units
  components <- forM built $ \unit -> do
    component <- readComponentMetadata False unit context
    pure (unit, component)
  if not (any (elem "-instantiated-with" . componentArguments . snd) components)
    then pure units
    else do
      dependencies <- forM components $ \(unit, component) -> do
        let arguments = componentArguments component
            values flag = [value | (key,value) <- zip arguments (drop 1 arguments), key == flag]
        actual <- if "-fno-code" `elem` arguments then pure (unitDepends unit)
          else if "-instantiated-with" `elem` arguments then do
            registered <- backpackRegistration (unitId unit) component
            require (not (Package.indefinite registered))
              ("Backpack registration is not concrete: " ++ unitId unit)
            pure (map prettyShow (Package.depends registered))
          else pure (map (takeWhile (not . isSpace)) (values "-package-id"))
        require (all (`Set.member` knownUnits) actual)
          ("compiled Backpack dependency absent from Cabal plan for " ++ unitId unit)
        pure (unitId unit, nub actual)
      let resolved = Map.fromList dependencies
      pure [unit {unitDepends = Map.findWithDefault (unitDepends unit) (unitId unit) resolved} | unit <- units]

-- Cabal typechecks indefinite libraries only in the vanilla way. The dynamic
-- exporter needs their signature interfaces, but only for concrete instances
-- in the selected runnable closure, never unrelated previously built targets.
prepareBackpackSignatures :: ExportContext -> Map.Map String Unit -> [Unit] -> [(Unit, Component)] -> IO ()
prepareBackpackSignatures context units selected components = when (Host.os /= "mingw32") $ do
  let selectedIds = Set.fromList (map unitId selected)
      indefinite = Map.fromList [(unitId unit, component) | (unit, component) <- components,
                    "-fno-code" `elem` componentArguments component]
      requiredSignatures = nub [identifier | (unit, component) <- components,
        unitId unit `Set.member` selectedIds,
        let arguments = componentArguments component, "-fno-code" `notElem` arguments,
        (flag, identifier) <- zip arguments (drop 1 arguments), flag == "-this-component-id",
        Map.member identifier indefinite]
  ordered <- concat <$> mapM (dependencyClosure units) requiredSignatures
  forM_ (nub (map unitId ordered)) $ \identifier ->
    forM_ (Map.lookup identifier indefinite) $ \component -> do
      registered <- backpackRegistration identifier component
      require (Package.indefinite registered) ("Backpack signature unit is not indefinite: " ++ identifier)
      directory <- case Package.importDirs registered of
        [path] | within (contextNative context) path -> pure path
        _ -> fail ("Backpack signature interfaces are not in the owned build: " ++ identifier)
      source <- field (componentValue component) "src-dir"
      targets <- sourcePaths (componentValue component) (componentArguments component)
      runCommand True (contextGhc context)
        (["--make", "-no-link"] ++ componentArguments component ++
         ["-dynamic", "-hisuf", "dyn_hi", "-hidir", directory] ++ map snd targets) source

backpackRegistration :: String -> Component -> IO Package.InstalledPackageInfo
backpackRegistration identifier component = do
  source <- field (componentValue component) "src-dir"
  let arguments = componentArguments component
  paths <- filterM doesFileExist [source </> database </> identifier <.> "conf" |
    (flag,database) <- zip arguments (drop 1 arguments), flag == "-package-db"]
  path <- case nub paths of
    [value] -> pure value
    _ -> fail ("no unique Backpack registration for " ++ identifier)
  (_, registered) <- either (fail . show) pure . Package.parseInstalledPackageInfo =<< BS.readFile path
  require (prettyShow (Package.installedUnitId registered) == identifier)
    ("Backpack registration has a different unit ID: " ++ identifier)
  pure registered

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

-- | Select one component's original acquisition records and their dependency
-- closure. Installed registration aliases retain their actual Core owners even
-- when GHC gives the exported unit a different identity. Records are returned
-- unchanged; owner edges are used only for this selection.
componentCoreRecords :: Map.Map String String -> String -> [Value] -> IO [Value]
componentCoreRecords owners target records = do
  units <- mapM readUnit records
  let withOwner unit = unit {unitDepends = unitDepends unit ++
        [owner | Just owner <- [Map.lookup (unitId unit) owners], owner /= unitId unit]}
      byId = Map.fromList [(unitId unit, withOwner unit) | unit <- units]
  require (Map.size byId == length units) "Acquisition records have duplicate unit IDs"
  map unitValue <$> dependencyClosure byId target

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
      providerKey = maybe globalKey (\path -> shaHex (BL.toStrict (encode
        ("core-interface-view-v2" :: String, globalKey, installedViewIdentity path)))) (contextCoreView context)
      -- Repository packages depending on a project library are Cabal 'inplace'
      -- builds, not immutable store IDs. Their key must follow that library's
      -- actual configured/native inputs and every intervening package identity.
      buildKey = if style == "global" then providerKey else shaHex (BL.toStrict (encode
        ("thc-core-inplace-source-build-v1" :: String, providerKey,
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
  pure $ object $ ["pluginUnit" .= contextPluginUnit context,
                 "pluginDb" .= contextPluginDb context,
                 "pluginHash" .= pluginHash,
                 "driverHash" .= contextDriverHash context,
                 "options" .= (["post-tidy", "unit-qualified", "source-notes",
                                "foreign-import-provenance", "foreign-export-associations", "foreign-export-registration",
                                "native-debug-info", "-dynamic", "-dcore-lint",
                                "-fplugin-trustworthy"] :: [String])] ++
                 ["coreInterfaceView" .= installedViewIdentity path | Just path <- [contextCoreView context]]

prepareGlobalBundles :: ExportContext -> Maybe OriginalStoreCapture -> FilePath -> [String] -> Map.Map String Unit -> [(Unit, Component)] -> [Unit] -> IO (Map.Map String Bundle)
prepareGlobalBundles _ _ _ _ _ _ [] = pure Map.empty
prepareGlobalBundles context originalCapture project target planned locals units = do
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
  suppliedPath <- lookupEnv "THC_CAPTURED_STORE_BUNDLES"
  case suppliedPath of
    Just path -> do
      require (isAbsolute path) "THC_CAPTURED_STORE_BUNDLES must be an absolute path"
      let closure = Map.elems (Map.fromList [(unitId unit, unit) | unit <- concat closures])
          request = object $ ["compiler" .= contextCompiler context, "abi" .= contextAbi context,
            "platform" .= contextPlatform context, "units" .= map sourceIdentity closure, "inputs" .= inputs] ++
            ["coreInterfaceView" .= installedViewIdentity view | Just view <- [contextCoreView context]]
      -- This is the current consumer snapshot, not invented historical producer
      -- evidence. An explicit retained-stage handoff must bind its bundles to it.
      atomicJson (path ++ ".request.json") request
      selected <- readCapturedStoreBundles (contextVerifyArtifacts context) request
        [(unitId unit, unitDepends unit) | unit <- units] path
      forM_ (Map.elems selected) $ \bundle -> do
        ready <- replayInterfacesReady context bundle
        require ready "retained pinned Core bundle lacks its matching replay interfaces"
      pure selected
    Nothing -> do
      located <- forM units $ \unit -> do
        (buildKey, exportKey, path) <- globalLocation context planned inputs unit
        cached <- doesFileExist path
        hit <- if cached then readGlobalForReplay context path (unitId unit) (unitDepends unit) buildKey exportKey
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
          checked <- forM located $ \(unit, buildKey, exportKey, path, _) -> do
            present <- doesFileExist path
            ready <- if present then readGlobalForReplay context path (unitId unit) (unitDepends unit) buildKey exportKey
                     else pure Nothing
            pure (unit, buildKey, exportKey, path, ready)
          let pending = [(unit, buildKey, exportKey, path) | (unit, buildKey, exportKey, path, Nothing) <- checked]
          forM_ originalCapture $ \capture ->
            publishOriginalStoreCapture context capture planned pending validateInputs
          -- A concurrent publication or original capture can complete either
          -- payload or interfaces. Recheck before choosing the isolated fallback.
          remaining <- forM checked $ \(unit, buildKey, exportKey, path, _) -> do
            present <- doesFileExist path
            ready <- if present then readGlobalForReplay context path (unitId unit) (unitDepends unit) buildKey exportKey
              else pure Nothing
            pure (unit, buildKey, exportKey, path, ready)
          let uncaptured = [(unit, buildKey, exportKey, path) | (unit, buildKey, exportKey, path, Nothing) <- remaining]
              warm = Map.fromList [(unitId unit, replayInterfacePath path)
                | (unit, _, _, path, Just bundle) <- remaining, not (null (bundleModules bundle))]
          when (not (null uncaptured)) $
            captureGlobalUnits context { contextCoreInterfaces = Map.union warm (contextCoreInterfaces context) }
              project target planned (map first4 uncaptured) uncaptured validateInputs
      -- Check warm hits too, before they enter the combined manifest.
      validateInputs
      pairs <- forM located $ \(unit, buildKey, exportKey, path, hit) -> do
        bundle <- case hit of
          Just value -> pure value
          Nothing -> do
            ready <- readGlobalForReplay context path (unitId unit) (unitDepends unit) buildKey exportKey
            maybe (fail ("Cabal store Core bundle was not published: " ++ unitId unit)) pure ready
        pure (unitId unit, bundle)
      pure (Map.fromList pairs)
  where first4 (unit, _, _, _) = unit

-- Optional original-build reuse is admitted only under the ordinary immutable
-- keys and final dependency identities. Availability is checked for the whole
-- configured native closure before any ZIP or replay interfaces are published.
-- Missing warm-native compiler evidence falls back; invalid declared evidence
-- retains the native reader's ordinary hard failure.
publishOriginalStoreCapture :: ExportContext -> OriginalStoreCapture -> Map.Map String Unit ->
                               [(Unit, String, String, FilePath)] -> IO () -> IO ()
publishOriginalStoreCapture context capture planned pending validateInputs = do
  databases <- nativeCaptureDatabases (originalCaptureStore capture) (contextNative context)
  let directory = originalCaptureDirectory capture
      pieces = contextNative context </> "cache/thc/native-pieces-v1"
      completedRegistration unit = do
        paths <- filterM doesFileExist [database </> unitId unit <.> "conf" | database <- databases]
        case paths of
          [] -> pure Nothing
          [path] -> pure (Just path)
          _ -> fail ("native dependency has ambiguous completed registration: " ++ unitId unit)
      moduleless unit path = do
        bytes <- BS.readFile path
        pure (modulelessRegistration (unitId unit) (unitDepends unit) bytes /= Nothing)
      completed unit = doesFileExist (directory </> unitId unit </> "capture-complete")
  eligible <- filterM (\(unit, buildKey, exportKey, _) ->
    if Map.lookup (unitId unit) (originalCaptureKeys capture) /= Just (buildKey, exportKey)
      then pure False else do
        before <- dependencyClosure (originalCapturePlan capture) (unitId unit)
        after <- dependencyClosure planned (unitId unit)
        if map sourceIdentity (sortOn unitId before) /= map sourceIdentity (sortOn unitId after)
          then pure False else do
            fresh <- completed unit
            if fresh then pure True else do
              selected <- completedRegistration unit
              maybe (pure False) (moduleless unit) selected) pending
  closures <- mapM (dependencyClosure planned . first4) eligible
  let dependencies = Map.elems (Map.fromList [(unitId unit, unit) | unit <- concat closures,
        jsonField (unitValue unit) "type" == Just ("configured" :: String)])
  available <- forM dependencies $ \unit -> do
    selected <- completedRegistration unit
    case selected of
      Nothing -> pure False
      Just path -> do
        native <- readNativeProductAvailable (unitValue unit) (unitDepends unit) path pieces
        case native of
          Left _ -> pure False
          Right (Just _) -> pure True
          Right Nothing -> do
            fresh <- completed unit
            if fresh then pure True else moduleless unit path
  when (not (null eligible) && and available) $ do
    validateInputs
    packageTool <- maybe (fail "selected native ghc-pkg is missing") pure (contextGhcPkg context)
    forM_ eligible $ \(unit, buildKey, exportKey, path) -> do
      createDirectoryIfMissing True (takeDirectory path)
      withLock (path ++ ".lock") $ do
        present <- doesFileExist path
        loaded <- if present then readGlobalBundle (contextVerifyArtifacts context) path (unitId unit)
          (unitDepends unit) buildKey exportKey else pure Nothing
        bundle <- case loaded of
          Just value -> pure value
          Nothing -> do
            packGlobalBundle packageTool pieces databases directory planned unit buildKey exportKey path
            readGlobalBundle (contextVerifyArtifacts context) path (unitId unit) (unitDepends unit) buildKey exportKey
              >>= maybe (fail "original Core bundle publication was not readable") pure
        ready <- replayInterfacesReady context bundle
        unless ready $ do
          requireFile (directory </> unitId unit </> "capture-complete")
          requireFile (directory </> unitId unit </> "interfaces-ready")
          names <- mapM (`field` "name") (bundleModules bundle)
          publishReplayInterfaces context (directory </> unitId unit </> "objects") names False path
  where first4 (unit, _, _, _) = unitId unit

-- Core replay interfaces belong to the same immutable exporter key as their
-- executable payload. Native package registrations and libraries stay intact.
replayInterfacePath :: FilePath -> FilePath
replayInterfacePath path = path ++ ".interfaces"

replayInterfacesReady :: ExportContext -> Bundle -> IO Bool
replayInterfacesReady context bundle = case contextCoreView context of
  Nothing -> pure True
  Just _ | null (bundleModules bundle) -> pure True
         | otherwise -> do
             let directory = replayInterfacePath (bundlePath bundle)
                 receipt = directory </> "complete"
             ready <- doesFileExist receipt
             when (ready && contextVerifyArtifacts context) $ do
               records <- either fail pure . eitherDecodeStrict' =<< BS.readFile receipt :: IO [(FilePath, String)]
               require (not (null records)) "empty Core replay interface receipt"
               forM_ records $ \(relative, expected) -> do
                 let path = directory </> relative
                 require (within directory path) "Core replay interface path leaves its bundle"
                 requireFile path
                 actual <- digestFile path
                 require (actual == expected) ("Core replay interface digest differs: " ++ relative)
             pure ready

readGlobalForReplay :: ExportContext -> FilePath -> String -> [String] -> String -> String -> IO (Maybe Bundle)
readGlobalForReplay context path unit dependencies buildKey exportKey = do
  loaded <- readGlobalBundle (contextVerifyArtifacts context) path unit dependencies buildKey exportKey
  case loaded of
    Nothing -> pure Nothing
    Just bundle -> do
      ready <- replayInterfacesReady context bundle
      pure (if ready then Just bundle else Nothing)

publishReplayInterfaces :: ExportContext -> FilePath -> [String] -> Bool -> FilePath -> IO ()
publishReplayInterfaces context objects modules dynamicHi bundle = forM_ (contextCoreView context) $ \_ -> unless (null modules) $ do
  let destination = replayInterfacePath bundle
  ready <- doesFileExist (destination </> "complete")
  unless ready $ do
    exists <- doesDirectoryExist objects
    require exists ("completed Core replay has no interface directory: " ++ objects)
    forM_ modules $ \name -> requireFile
      (objects </> map (\c -> if c == '.' then pathSeparator else c) name <.> "hi")
    (staging, handle) <- openTempFile (takeDirectory destination) "replay-interfaces-"
    hClose handle
    removeFile staging
    createDirectory staging
    files <- filter (\path -> takeExtension path `elem` [".hi", ".dyn_hi", ".hi-boot", ".dyn_hi-boot"])
      <$> recursiveFiles objects
    records <- fmap concat $ forM files $ \path -> do
      let relative = makeRelative objects path
          target = staging </> relative
      createDirectoryIfMissing True (takeDirectory target)
      bytes <- BS.readFile path
      BS.writeFile target bytes
      let entry = (relative, shaHex bytes)
      -- Local replay explicitly uses -dynamic -hisuf hi; package lookup uses
      -- dyn_hi for that same genuine dynamic interface. Cabal store replay
      -- keeps its actual ways and must never relabel a vanilla interface.
      if dynamicHi && takeExtension path `elem` [".hi", ".hi-boot"] then do
        let alias = replaceExtension relative
              (if takeExtension path == ".hi" then "dyn_hi" else "dyn_hi-boot")
        BS.writeFile (staging </> alias) bytes
        pure [entry, (alias, shaHex bytes)]
      else pure [entry]
    BL.writeFile (staging </> "complete") (encode records)
    Directory.renameDirectory staging destination

-- | Explicit reuse of a preserved capture, separate from current-producer cache
-- lookup. The caller supplies the exact compiler/plan/local-native snapshot;
-- original archive paths, hashes and producer keys are never rewritten.
readCapturedStoreBundles :: Bool -> Value -> [(String, [String])] -> FilePath -> IO (Map.Map String Bundle)
readCapturedStoreBundles verify request requested path = do
  manifest <- readJson path
  require (jsonField manifest "format" == Just ("thc-captured-store-bundles" :: String) &&
           jsonField manifest "schema" == Just (1 :: Int) && jsonField manifest "request" == Just request)
    "captured store compiler, plan or local/native input snapshot differs"
  rows <- field manifest "bundles" :: IO [Value]
  owners <- mapM (`field` "unit") rows
  require (length owners == length (nub owners) && sort owners == sort (map fst requested))
    "captured store bundle inventory differs from requested closure"
  let dependencies = Map.fromList requested
  pairs <- forM (zip owners rows) $ \(owner, row) -> do
    archive <- field row "path"
    expectedHash <- field row "sha256"
    buildKey <- field row "buildKey"
    exportKey <- field row "exportKey"
    require (isAbsolute archive) "captured store bundle path must be absolute"
    selected <- readGlobalBundle verify archive owner (dependencies Map.! owner) buildKey exportKey
    bundle <- maybe (fail ("invalid captured store bundle: " ++ owner)) pure selected
    require (bundleHash bundle == expectedHash) ("captured store bundle digest differs: " ++ owner)
    pure (owner, bundle)
  pure (Map.fromList pairs)

-- | Explicit installed-artifact selection, not a current-producer cache hit.
-- Keep the original successful probe and build inputs; validate its archive
-- through the same reader and receipt path as ordinary installed acquisition.
readCapturedInstalledBundles :: Bool -> Value -> [InstalledUnit] -> FilePath -> IO (Map.Map String (InstalledUnit, InstalledBundle))
readCapturedInstalledBundles verify compiler requested path = do
  manifest <- readJson path
  require (jsonField manifest "format" == Just ("thc-captured-store-bundles" :: String) &&
           jsonField manifest "schema" == Just (1 :: Int)) "invalid captured installed manifest"
  rows <- field manifest "installed" :: IO [Value]
  owners <- mapM (`field` "unit") rows
  require (length owners == length (nub owners) && sort owners == sort (map registeredId requested))
    "captured installed inventory differs from requested closure"
  let registrations = Map.fromList [(registeredId unit, unit) | unit <- requested]
  pairs <- forM (zip owners rows) $ \(identifier, row) -> do
    archive <- field row "path"
    probePath <- field row "probe"
    require (isAbsolute archive && isAbsolute probePath) "captured installed paths must be absolute"
    envelope <- readJson probePath
    record <- field envelope "record"
    require (jsonField envelope "sha256" == Just (shaHex (BL.toStrict (encode record))))
      "corrupt captured installed probe"
    identity <- field record "identity"
    provenance <- field identity "registration"
    inputs <- field record "inputs"
    component <- field inputs "component"
    interfaces <- field provenance "interfaces" :: IO [Value]
    names <- mapM (`field` "module") interfaces
    let unit = registrations Map.! identifier
    require (jsonField provenance "registeredUnit" == Just identifier &&
      jsonField provenance "compiler" == Just compiler && jsonField inputs "compiler" == Just compiler &&
      jsonField inputs "format" == Just ("thc-core-build-inputs" :: String) &&
      jsonField inputs "schema" == Just (1 :: Int) &&
      jsonField component "kind" == Just ("installed-interface" :: String) &&
      jsonField component "registration" == Just provenance &&
      length names == length (nub names) && sort names == sort (map fst (installedInterfaces unit)) &&
      fmap sort (jsonField provenance "depends") == Just (sort (installedDepends unit)) &&
      fmap sort (jsonField inputs "dependencies") == Just (sort (installedDepends unit)) &&
      fmap sort (jsonField provenance "reexports") == Just (sort (installedReexports unit)))
      ("captured installed compiler, registration or inventory differs: " ++ identifier)
    owner <- field inputs "unit"
    buildKey <- field inputs "buildKey"
    exportKey <- field inputs "exportKey"
    selected <- readBundle verify TargetLayoutBundle archive owner buildKey exportKey inputs (sort names)
    bundle <- maybe (fail ("invalid captured installed bundle: " ++ identifier)) pure selected
    require (jsonField record "bundleSha256" == Just (bundleHash bundle))
      ("captured installed bundle digest differs: " ++ identifier)
    pure (identifier, (unit, InstalledBundle owner bundle))
  pure (Map.fromList pairs)

captureGlobalUnits :: ExportContext -> FilePath -> [String] -> Map.Map String Unit -> [Unit] ->
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
        -- Rebuild only the selected components' closure. `all` also builds
        -- unrelated tests/apps and their dependencies in this fresh store.
        arguments = ["--store-dir=" ++ store, "build"] ++ target ++
                    ["--enable-build-info", "--builddir", dist, "--with-compiler", wrapper] ++
                    contextProjectOptions context ++
                    maybe [] (\path -> ["--with-hc-pkg", path]) (contextGhcPkg context)
    writeFile wrapper ("#!/bin/sh\n" ++ ghcProxyCommand)
    permissions <- getPermissions wrapper
    setPermissions wrapper (permissions {Directory.executable = True})
    inherited <- getEnvironment
    let overrides = [("THC_PROXY_DRIVER", contextDriver context),
                     ("THC_PROXY_ROOT", contextRoot context),
                     ("THC_PROXY_GHC", contextGhc context),
                     ("THC_PROXY_GHC_PKG", maybe (takeDirectory (contextGhc context) </> "ghc-pkg") id (contextGhcPkg context)),
                     ("THC_PROXY_CORE_INTERFACES", Text.unpack (Text.decodeUtf8 (BL.toStrict (encode (contextCoreInterfaces context))))),
                     ("THC_PROXY_CAPTURE", capture),
                     ("THC_PROXY_PLUGIN_DB", contextPluginDb context),
                     ("THC_PROXY_PLUGIN_UNIT", contextPluginUnit context),
                     ("THC_PROXY_PLUGIN_LIBRARY", contextPluginLibrary context),
                     ("THC_PROXY_NO_LINK_UNIT", unlines (contextNoLinkUnits context)),
                     ("THC_PROXY_NATIVE_PIECES", staging </> "native-pieces"),
                     ("THC_PROXY_INTERFACE_HELPER", installedHelper helper),
                     ("THC_PROXY_INTERFACE_LIBDIR", installedLibdir helper),
                     ("THC_PROXY_GLOBAL_UNITS", unlines (map unitId requested))] ++
                     concat [[("THC_PROXY_CORE_LIBDIR", installedLibdir view),
                       ("THC_PROXY_CORE_DATABASES", Text.unpack (Text.decodeUtf8 (BL.toStrict (encode (helperDatabases view)))))]
                       | Just view <- [contextCoreView context]]
        environment = overrides ++ filter (\(key, _) -> key `notElem` ("THC_PROXY_BUILD_ADMISSION" : "THC_PROXY_ORIGINAL_BUILD" : "THC_PROXY_CORE_DATABASES" : "THC_PROXY_CORE_LIBDIR" : map fst overrides)) inherited
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
        bundle <- case ready of
          Just value -> pure value
          Nothing -> do
            selectedPkg <- maybe (fail "selected native ghc-pkg is missing") pure (contextGhcPkg context)
            databases <- nativeCaptureDatabases store dist
            packGlobalBundle selectedPkg (staging </> "native-pieces") databases capture byId unit buildKey exportKey path
            readGlobalBundle (contextVerifyArtifacts context) path (unitId unit) (unitDepends unit) buildKey exportKey
              >>= maybe (fail "fresh Core bundle publication was not readable") pure
        names <- mapM (`field` "name") (bundleModules bundle)
        completed <- replayInterfacesReady context bundle
        unless completed $ do
          requireFile (capture </> unitId unit </> "interfaces-ready")
          publishReplayInterfaces context (capture </> unitId unit </> "objects") names False path
    ) `onException` do
      _ <- tryIOError (hPutStrLn stderr ("Cabal store capture retained after failure: " ++ staging))
      pure ()
  cleanup

-- | Publish exactly one already captured store unit using its genuine Cabal
-- plan, interfaces/Core and native products. No package solve, compiler replay
-- or acquisition of unrelated units is performed here. The output must be new.
publishCapturedStoreUnit :: FilePath -> FilePath -> FilePath -> FilePath -> FilePath -> String -> FilePath -> IO Bundle
publishCapturedStoreUnit packageTool planPath store dist capture identifier destination = do
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
  databases <- nativeCaptureDatabases selectedStore selectedDist
  packGlobalBundle packageTool (takeDirectory selectedCapture </> "native-pieces") databases selectedCapture planned unit buildKey exportKey selectedDestination
  result <- readGlobalBundle True selectedDestination identifier (unitDepends unit) buildKey exportKey
  maybe (fail "new captured unit failed its ordinary bundle validation") pure result

-- Actual completed Cabal registration sources; callers retain their selected
-- store configuration rather than deriving it from a Core staging directory.
nativeCaptureDatabases :: FilePath -> FilePath -> IO [FilePath]
nativeCaptureDatabases store dist = do
  let databases directory suffix = do
        exists <- doesDirectoryExist directory
        if not exists then pure [] else do
          partitions <- listDirectory directory
          filterM doesDirectoryExist [directory </> partition </> suffix | partition <- partitions]
  storeDatabases <- databases store "package.db"
  inplaceDatabases <- databases (dist </> "packagedb") ""
  pure (storeDatabases ++ inplaceDatabases)

packGlobalBundle :: FilePath -> FilePath -> [FilePath] -> FilePath -> Map.Map String Unit -> Unit -> String -> String -> FilePath -> IO ()
packGlobalBundle packageTool pieces databases capture planned unit buildKey exportKey destination =
  bracket temporary removePathForcibly $ \staging ->
    packGlobalBundleStaged packageTool staging pieces databases capture planned unit buildKey exportKey destination
  where
    temporary = do
      (path, handle) <- openTempFile (takeDirectory destination) "core-link-"
      hClose handle
      removeFile path
      createDirectory path
      pure path

-- Retained captures are immutable inputs. Native linkage may amend only the
-- private copies made here, including copied dependency modules.
packGlobalBundleStaged :: FilePath -> FilePath -> FilePath -> [FilePath] -> FilePath -> Map.Map String Unit -> Unit -> String -> String -> FilePath -> IO ()
packGlobalBundleStaged packageTool staging pieces databases capture planned unit buildKey exportKey destination = do
  let core = capture </> unitId unit </> "core"
  exported <- filter ((== ".cbd") . takeExtension) <$> recursiveFiles core
  empty <- if not (null exported) then pure [] else do
    -- The actual completed registration proves moduleless/reexport shape;
    -- absence of a captured CBD alone never creates an empty module.
    registrations <- filterM doesFileExist [database </> unitId unit <.> "conf" | database <- databases]
    bytes <- case registrations of
      [path] -> BS.readFile path
      _ -> fail ("completed Cabal build lacks a unique registration for " ++ unitId unit)
    reexports <- maybe (fail ("Cabal store build did not export Core for nonempty unit " ++ unitId unit)) pure
      (modulelessRegistration (unitId unit) (unitDepends unit) bytes)
    pure [(if null reexports then "emptyRegistration" else "reexportRegistration") .= Text.decodeUtf8 bytes]
  let stageModules owner paths = do
        createDirectoryIfMissing True (staging </> owner)
        forM (zip [0 :: Int ..] paths) $ \(index, path) -> do
          value <- readCoreMetadata path
          require (jsonField value "unit" == Just owner && jsonField value "boundary" == Just boundary)
            ("store Core artifact has wrong owner or boundary: " ++ path)
          name <- field value "module"
          let staged = staging </> owner </> show index <.> "cbd"
          Directory.copyFile path staged
          pure (name, staged)
  checked <- stageModules (unitId unit) exported
  -- Temporary intra-package DBs may already have been removed. Resolve the
  -- unchanged native compiler receipt against the caller's completed build.
  closure <- dependencyClosure planned (unitId unit)
  (_,linked) <- foldlM (\(providers,selected) dependency -> do
    let owner = unitId dependency
        sourceDirectory = capture </> owner
        direct = nub [(identifier:path,component) | identifier <- unitDepends dependency,
          (path,component) <- Map.findWithDefault [] identifier providers]
        -- Providers without C/FFI of their own forward only graph edges. The
        -- resolved Cabal path remains in the consuming component's receipt.
        forwarded = direct
    kind <- optionalField (unitValue dependency) "type" ("" :: String)
    if kind /= "configured" then pure (Map.insert owner forwarded providers,selected) else do
      registrations <- filterM doesFileExist
        [database </> owner <.> "conf" | database <- databases]
      capturedNative <- case registrations of
        [] -> pure Nothing
        [path] -> readNativeProduct (unitValue dependency) (unitDepends dependency) path pieces
        _ -> fail "native dependency has ambiguous completed registration"
      nativeReceipt <- doesFileExist (sourceDirectory </> "native.json")
      bodies <- if owner == unitId unit then pure checked else if not nativeReceipt then pure [] else do
        paths <- filter ((== ".cbd") . takeExtension) <$> recursiveFiles (sourceDirectory </> "core")
        stageModules owner paths
      (prepared,component) <- finishPackageNativeWithDependencies packageTool capturedNative direct (databases)
        pieces sourceDirectory (takeDirectory destination </> "native-components" </> owner) owner Nothing bodies
      let available = maybe forwarded (\value -> [([],value)]) component
      pure (Map.insert owner available providers,if owner == unitId unit then prepared else selected)
    ) (Map.empty,checked) closure
  let sorted = sortOn fst linked
      names = map fst sorted
  require (length names == length (nub names))
    ("duplicate exported store modules for " ++ unitId unit)
  (modules, members) <- packageCoreFiles sorted
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
          artifact <- either (const Nothing) Just (snd <$> readModuleMetadata body)
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
                    ["coreInterfaceView" .= installedViewIdentity path | Just path <- [contextCoreView context]] ++
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
    usable <- case hit of
      Nothing -> pure Nothing
      Just bundle -> do
        ready <- replayInterfacesReady context bundle
        pure (if ready then Just bundle else Nothing)
    case usable of
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
    replay <- case contextCoreView context of
      Nothing -> pure []
      Just view -> Directory.withCurrentDirectory sourceDir $ coreReplayArguments
        (maybe (takeDirectory (contextGhc context) </> "ghc-pkg") id (contextGhcPkg context))
        (installedLibdir view) (helperDatabases view) staging (Map.toList (contextCoreInterfaces context)) (componentArguments component)
    runCommand True (componentCompiler component) (arguments ++ replay) sourceDir
    let replayed = component { componentArguments = componentArguments component ++ replay }
    exported <- filter ((== ".cbd") . takeExtension) <$> recursiveFiles core
    checked <- forM exported $ \path -> do
      value <- readCoreMetadata path
      foundUnit <- field value "unit"
      foundBoundary <- field value "boundary" :: IO String
      name <- field value "module"
      require (foundUnit == unitId unit && foundBoundary == boundary)
        ("Core artifact has wrong unit or boundary: " ++ path)
      pure (name, path)
    let actual = sort (map fst checked)
    require (length actual == length (nub actual) && actual == expected)
      ("Core module inventory differs from Cabal build-info for " ++ unitId unit ++ ": " ++ show actual)
    sorted <- case (scalar,runtimeShim,helper) of
      (Nothing,Nothing,Nothing) -> do
        selectedHelper <- prepareInterfaceHelper context (contextRoot context)
        Directory.withCurrentDirectory sourceDir $
          capturePackageNative (contextRoot context) (installedHelper selectedHelper) (installedLibdir selectedHelper)
            (componentCompiler component) (arguments ++ replay) (unitId unit) staging
        updated <- forM exported $ \path -> do
          value <- readCoreMetadata path
          name <- field value "module"
          pure (name,path)
        packageTool <- maybe (fail "selected native ghc-pkg is missing") pure (contextGhcPkg context)
        sortOn fst <$> finishPackageNative packageTool (contextNative context </> "cache/thc/native-pieces-v1") staging (unitId unit) nativeObjects updated
      (Just recipe,Nothing,Just selectedHelper) -> do
        retained <- scalarInterfaceModules selectedHelper replayed unit objects expected
        linkScalarBitcode recipe buildKey retained
      (Nothing,Just shim,Just selectedHelper) -> do
        retained <- scalarInterfaceModules selectedHelper replayed unit objects expected
        validateRuntimeShimModules shim retained
      _ -> fail "scalar cbits interface helper missing"
    (modules, members) <- packageCoreFiles sorted
    let inputsBytes = BL.toStrict (encode buildInputs)
        inner = object ["format" .= ("thc-core-bundle" :: String), "schema" .= (1 :: Int),
                        "unit" .= unitId unit, "buildKey" .= buildKey,
                        "exportKey" .= exportKey, "modules" .= modules,
                        "buildInputs" .= object ["path" .= ("inplace-manifest.json" :: String),
                                                 "sha256" .= shaHex inputsBytes]]
    archive <- either fail pure (encodeZip
      (("manifest.json", BL.toStrict (encode inner)) : ("inplace-manifest.json", inputsBytes) : members))
    publishReplayInterfaces context objects expected (exportInterfaceWay == "dynamic") destination
    atomicBytes destination (BL.toStrict archive)
    rememberFreshBundle PlainBundle (unitId unit) exportKey buildInputs expected
      (Bundle destination (shaHex (BL.toStrict archive)) modules buildKey [])) `finally` cleanup

-- Recover exact typed annotations from the emitted full-Core interfaces through
-- the selected GHC helper and Cabal's actual library registration.
scalarInterfaceModules :: InstalledContext -> Component -> Unit -> FilePath -> [String] -> IO [(String,FilePath)]
scalarInterfaceModules helper component unit objects names = do
  sourceDir <- field (componentValue component) "src-dir"
  let core = objects </> "interface-core"
  createDirectoryIfMissing True core
  databases <- mapM (canonicalizePath . (sourceDir </>))
    [path | (flag,path) <- zip (componentArguments component) (drop 1 (componentArguments component)),
      flag == "-package-db"]
  forM names $ \name -> do
    let interface = objects </> map (\c -> if c == '.' then pathSeparator else c) name <.> "hi"
        arguments = ["--libdir",installedLibdir helper,"--unit",unitId unit,"--module",name,
          "--interface",interface,"--way",exportInterfaceWay,"--source-notes"] ++
          concatMap (\database -> ["--package-db",database]) databases
    requireFile interface
    (status,output,diagnostic) <- boundedInterfaceProcessIn sourceDir (installedHelper helper) arguments
    require (status == ExitSuccess) ("scalar cbits interface acquisition failed: " ++
      take 4096 (Text.unpack (Text.decodeUtf8With lenientDecode (output <> diagnostic))))
    value <- either fail pure (snd <$> readModuleMetadata output)
    require (jsonField value "unit" == Just (unitId unit) && jsonField value "module" == Just name &&
      jsonField value "boundary" == Just boundary && jsonField value "ghc" == Just ("9.14.1"::String))
      "scalar cbits interface identity or boundary mismatch"
    let path = core </> name <.> "cbd"
    BS.writeFile path output
    pure (name,path)

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
          artifact <- either (const Nothing) Just (snd <$> readModuleMetadata body)
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
  (jsonField layout "schema" :: Maybe Int) `elem` [Just 1, Just 2] &&
  (jsonField layout "schema" /= Just (2 :: Int) ||
    case mapM (jsonField layout :: String -> Maybe Int)
      ["rtsFlagsBytes", "traceFlagsBytes", "rtsTraceFlagsOffset",
       "rtsTraceFlagsBytes", "traceUserOffset", "traceUserBytes"] of
      Just [rtsBytes, traceBytes, traceOffset, traceMemberBytes, userOffset, userBytes] ->
        rtsBytes > 0 && traceBytes > 0 && traceMemberBytes == traceBytes && userBytes == 1 &&
        traceOffset >= 0 && traceOffset <= rtsBytes - traceMemberBytes &&
        userOffset >= 0 && userOffset <= traceBytes - userBytes
      _ -> False) &&
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
  signatures <- optionalField component "thc-signatures" ([] :: [String])
  files <- optionalField component "src-files" ([] :: [String])
  mainModule <- componentMainModule component
  when (mainModule /= Nothing) $
    require (length files == 1) "project runnable component must have one Haskell main source"
  pure (sort (filter (`notElem` signatures) modules ++ maybe [] pure mainModule))

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
  let signatures = [name | (name,path) <- sources, takeExtension path `elem` [".hsig", ".lhsig"]]
      described = case completed of
        Object fields -> Object (KeyMap.insert "thc-signatures" (Aeson.toJSON signatures) fields)
        _ -> completed
  pure (Component described (contextGhc context) arguments sources)

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
        candidates extensions = [directory </> replaceExtension relative extension |
                                  directory <- directories, extension <- extensions]
    implementations <- filterM doesFileExist (candidates ["hs", "lhs"])
    path <- if not (null implementations) then uniqueSource name implementations else do
      -- Preserve implementation-source ambiguity checks. Cabal also emits
      -- signature stubs in autogen; for signatures, use directory search order.
      signatures <- filterM doesFileExist (candidates ["hsig", "lhsig"])
      case signatures of
        signature:_ -> canonicalizePath signature
        [] -> uniqueSource name []
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

readCoreMetadata :: FilePath -> IO Value
readCoreMetadata path = snd <$> readModuleMetadataFile path

-- Linking owns staged files. Read their final bytes only when publishing the
-- bundle that records their hashes and stores those exact bytes.
packageCoreFiles :: [(String, FilePath)] -> IO ([Value], [(FilePath, BS.ByteString)])
packageCoreFiles files = do
  modules <- forM (zip [0 :: Int ..] files) $ \(index, (name, path)) -> do
    bytes <- BS.readFile path
    pure (name, "core/" ++ show index ++ ".cbd", bytes)
  packageModules modules

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
  let commandLine = (proc command arguments)
        { cwd = Just directory, env = environment,
          std_out = if tool then UseHandle stderr else Inherit }
  result <- if tool then runProducer commandLine else do
    -- Preserve foreground terminal input and interrupt behavior for the guest.
    (_, _, _, process) <- createProcess commandLine
    waitForProcess process
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
