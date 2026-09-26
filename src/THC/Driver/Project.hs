-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

module THC.Driver.Project
  (runProject, Bundle(..), InstalledBundle(..), prepareInstalledBundle, installedRecords) where

import Control.Exception (evaluate, finally)
import Control.Monad (filterM, forM, forM_, unless, when)
import Data.Char (isAlphaNum, isHexDigit)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (FromJSON, Value(..), eitherDecodeStrict', encode, object, (.=))
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
                        takeDirectory, takeExtension, takeFileName, joinPath, replaceExtension)
import System.IO (IOMode(ReadMode), hClose, hGetContents,
                  hSetEncoding, openTempFile, stderr, utf8, withFile)
import System.IO.Error (tryIOError)
import THC.Driver.Lock (withLock)
import System.Process (CreateProcess(..), StdStream(..), createProcess, proc, waitForProcess,
                       readCreateProcessWithExitCode)
import THC.Driver.Cabal (PlanOptions(..))
import THC.Driver.Cache (coreCacheDirectory)
import THC.Driver.ForeignBitcode (linkClockGetTime)
import THC.Driver.GhcProxy (ghcProxyCommand)
import THC.Driver.NativeRecipe (NativeRecipe(..), componentRoots, componentNativeObjects,
  readNativeRecipe, ensureNativeRecipes, componentRuntimeShim)
import THC.Driver.ScalarBitcode (ScalarBitcode, scalarBuildInputs, linkScalarBitcode)
import THC.Driver.RuntimeShim (RuntimeShim, withRuntimeShim, runtimeShimInputs, validateRuntimeShimModules)
import THC.Driver.PackageNative (captureNativeObject, capturePackageNative, finishPackageNative)
import THC.Driver.Installed
import THC.Driver.InstalledForeign
import THC.Driver.Run (RunOptions(..))
import THC.Driver.Zip (decodeZip, encodeZip)
import THC.Driver.Wired (WiredArtifacts(..), moduleSources, sourceHashes,
                         exportPinnedCore, probeTargetLayout)

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
                     , bundleModules :: [Value], bundleBuildKey :: String }

data InstalledBundle = InstalledBundle
  { installedOwner :: String, installedBundle :: Bundle }

data BundleReceipt = PlainBundle | TargetLayoutBundle | PinnedSourceBundle

data ExportContext = ExportContext
  { contextCompiler :: String, contextAbi :: String, contextPlatform :: String
  , contextPluginDb :: FilePath, contextPluginUnit :: String
  , contextPluginLibrary :: FilePath, contextNative :: FilePath
  , contextCache :: FilePath, contextDriverHash :: String
  , contextGhc :: FilePath, contextGhcPkg :: Maybe FilePath
  , contextDriver :: FilePath, contextRoot :: FilePath }

boundary :: String
boundary = "optimized-Core-after-Tidy-before-CorePrep"

runProject :: RunOptions -> FilePath -> IO ()
runProject opts target = do
  require (not (null (runExecutable opts))) "run requires --exe NAME"
  require (not (null (runThcRoot opts))) "run requires --thc-root DIR"
  require (runInstalledCore opts `elem` ["required", "pinned"])
    "--installed-core must be required or pinned"
  require (runGhcSource opts == Nothing || runInstalledCore opts == "required")
    "--ghc-source requires --installed-core required"
  let flags = runPlan opts
  require (null (selectedFlags flags) && not (enableTests flags) && not (enableBenchmarks flags))
    "project flags, tests and benchmarks belong in cabal.project"
  project <- canonicalizePath target
  requireFile (project </> "cabal.project")
  thcRoot <- canonicalizePath (runThcRoot opts)
  runtime <- maybe (pure (thcRoot </> "build/install/thc/bin/thc")) makeAbsolute (runRuntime opts)
  requireFile runtime
  requireFile (thcRoot </> "scripts/audit-core.py")
  let buildPlugin = thcRoot </> "compiler/build.sh"
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
      executable = runExecutable opts
  createDirectoryIfMissing True requestedOutput
  output <- canonicalizePath requestedOutput
  (wantedPackage, wantedComponent) <- executableSelection executable
  let targetComponent = maybe "" (++ ":") wantedPackage ++ wantedComponent
  let native = output </> "native"
      cabalArgs = ["build", targetComponent, "--enable-build-info", "--project-file", "cabal.project",
                   "--builddir", native]
  compiler <- case ghcPath flags of
    Just path -> canonicalizePath path
    Nothing -> findExecutable "ghc" >>= maybe (fail "GHC compiler not found") canonicalizePath
  packageTool <- Just <$> selectedPackageTool compiler (ghcPkgPath flags)
  source <- traverse canonicalizePath (runGhcSource opts)
  withProjectLock output $
    runBuiltProject project thcRoot runtime output native executable cabalArgs
                    pluginDb pluginUnit pluginLibrary compiler packageTool (runInstalledCore opts)
                    source registeredLibrary (runArguments opts)

-- Resolve the actual selected compiler's companion before Cabal sees the
-- forwarding wrapper. The wrapper directory is not a GHC installation.
selectedPackageTool :: FilePath -> Maybe FilePath -> IO FilePath
selectedPackageTool ghc requested = do
  inherited <- lookupEnv "GHC_PKG"
  path <- case requested `orElse` inherited of
    Just value -> if isAbsolute value then pure value else findExecutable value >>=
      maybe (fail "selected ghc-pkg executable not found") pure
    Nothing -> do
      let candidates = [takeDirectory ghc </> "ghc-pkg-9.14.1", takeDirectory ghc </> "ghc-pkg"]
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

runBuiltProject :: FilePath -> FilePath -> FilePath -> FilePath -> FilePath ->
                   String -> [String] -> FilePath -> String -> FilePath -> FilePath ->
                   Maybe FilePath -> String -> Maybe FilePath -> FilePath -> [String] -> IO ()
runBuiltProject project thcRoot runtime output native executable cabalArgs
                pluginDb pluginUnit pluginLibrary ghc ghcPkg installedPolicy ghcSource registeredLibrary guestArguments = do
  driver <- getExecutablePath
  let proxy = native </> "cache/thc/native-ghc"
      receipts = native </> "cache/thc/native-recipes-v1"
      packageTool = maybe (takeDirectory ghc </> "ghc-pkg") id ghcPkg
      nativeArguments = cabalArgs ++ ["--with-compiler", proxy, "--with-hc-pkg", packageTool]
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
                   ("THC_PROXY_NATIVE_PIECES", native </> "cache/thc/native-pieces-v1")]
      environment = overrides ++ filter (\(key, _) -> key `notElem` map fst overrides) inherited
      nativeBuild arguments = runCommandWithEnv True "cabal" arguments project (Just environment)
  nativeBuild nativeArguments
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
  let context = ExportContext compilerId abi (arch ++ "-" ++ os) pluginDb pluginUnit
                              pluginLibrary native cacheRoot driverHash ghc ghcPkg driver thcRoot
  records <- field plan "install-plan" :: IO [Value]
  units <- mapM readUnit records
  let byId = Map.fromList [(unitId unit, unit) | unit <- units]
  require (Map.size byId == length units) "Cabal plan has duplicate unit IDs"
  selected <- selectExecutable executable units
  -- The plan can also list unrelated executables, tests and benchmarks.
  -- Only the requested executable and its complete dependency
  -- closure have required build-info; a missing member of that closure fails.
  ordered <- dependencyClosure byId (unitId selected)
  builtLocals <- filterM (\unit -> case jsonField (unitValue unit) "build-info" of
    Nothing -> pure False
    Just path -> doesFileExist path) (filter unitLocal units)
  builtComponents <- forM builtLocals $ \unit -> do
    component <- readComponentMetadata (unitId unit `elem` map unitId ordered) unit context
    pure (unit, component)
  let localComponents = filter (\(unit, _) -> unitId unit `elem` map unitId ordered) builtComponents
  roots <- fmap (sort . nub . concat) $ forM builtComponents $ \(unit, component) -> do
    dist <- field (unitValue unit) "dist-dir"
    componentRoots dist (componentValue component)
  -- Repair missing native receipts through Cabal itself; never reconstruct
  -- the missing compiler invocation from a binary setup-config.
  forM_ localComponents $ \(unit, component) -> do
    dist <- field (unitValue unit) "dist-dir"
    ensureNativeRecipes native dist roots ghc (componentValue component) $ do
      packageName <- field (unitValue unit) "pkg-name" :: IO String
      componentName <- field (unitValue unit) "component-name" :: IO String
      sourceRoot <- field (componentValue component) "src-dir"
      let target = if componentName == "lib" then "lib:" ++ packageName else componentName
      -- v2-build's monitor can say "up to date" after an intermediate .o is
      -- deleted. Ask this same Cabal CLI's Simple Setup to build the component
      -- directly; it owns and reads its own configured build representation.
      runCommandWithEnv True "cabal" ["act-as-setup", "--build-type=Simple", "--", "build",
        "--builddir=" ++ dist, target] sourceRoot (Just environment)
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
      result <- prepareInstalledBundle cacheRoot (native </> "cache/thc/staging")
        (thcRoot </> "compiler/target-layout.c") driverHash helperContext registrationUnit
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
    (selectedPackage ++ ":" ++ selectedComponent) globals
  (_, described) <- foldlM (\(keys, acc) unit -> do
    kind <- optionalField (unitValue unit) "type" ("" :: String)
    bundle <- if unitLocal unit
      then Just <$> exportUnit context roots keys unit
      else do
        if kind == "configured" then Just <$> maybe
          (fail ("Core bundle missing for Cabal store component " ++ unitId unit)) pure
          (Map.lookup (unitId unit) globalBundles)
        else pure (installedBundle . snd <$> Map.lookup (unitId unit) installed)
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
  let manifest = output </> "packages.json"
      -- Complete installed Core provides GHC's generated :Main wrapper and
      -- original Handle shutdown. Pinned source remains the explicit limited
      -- raw-IO provider used by the stock-GHC driver controls.
      lifecycle = installedPolicy == "required"
      entry = if lifecycle then "main::Main.main" else unitId selected ++ ":Main.main"
      shutdown = "ghc-internal:GHC.Internal.TopHandler.flushStdHandles"
      audit = output </> "audit.json"
  atomicJson manifest (object ["format" .= ("thc-core-packages" :: String),
                               "schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
                               "units" .= (described ++ wired)])
  runCommand True "python3" ([thcRoot </> "scripts/audit-core.py", "--package-manifest", manifest,
                              "--entry", entry] ++
                             (if lifecycle then ["--entry", shutdown] else []) ++
                             ["--io-main", "--output", audit]) thcRoot
  -- Full-Core main and shutdown share one program and its Handle CAFs.
  -- Execute relative paths from the Cabal project just as the native binary does.
  let programName = reverse (takeWhile (/= ':') (reverse executable))
  runCommand False runtime ((if lifecycle
      then ["--run-executable", '@' : manifest, entry, shutdown]
      else ["--run-io", '@' : manifest, entry]) ++ ["--", programName] ++ guestArguments) project

prepareInterfaceHelper :: ExportContext -> FilePath -> IO InstalledContext
prepareInterfaceHelper context root = do
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  let ghc = contextGhc context
      pkg = maybe (takeDirectory ghc </> "ghc-pkg") id (contextGhcPkg context)
      selection = ["exe:thc-interface", "--offline", "--with-compiler=" ++ ghc, "--with-hc-pkg=" ++ pkg]
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
     "platform" .= contextPlatform context, "way" .= ("dynamic-nonprofiling" :: String)])

-- The optional index avoids hydration on proven hits, but never replaces the
-- existing JSON-derived bundle identity or its complete archive validation.
-- Registered IDs/ABI/mtime alone cannot identify mutable installed payloads.
prepareInstalledBundle :: FilePath -> FilePath -> FilePath -> String -> InstalledContext -> InstalledUnit ->
                          IO (Either MissingCore InstalledBundle)
prepareInstalledBundle cache staging recipe driverHash context registrationUnit = do
  evidence <- optionalIO $ do
    helperHash <- digestFile (installedHelper context)
    recipeHash <- digestFile recipe
    (rtsRegistration, _) <- installedLayoutHeaders context registrationUnit
    probe <- probeInstalled context registrationUnit
    let identity = object ["schema" .= (1 :: Int), "helperHash" .= helperHash,
          "driverHash" .= driverHash, "recipeHash" .= recipeHash,
          "rtsRegistration" .= rtsRegistration,
          "registration" .= installedProvenance context registrationUnit]
        index = cache </> "installed-probes/v1" </> shaHex (BL.toStrict (encode identity)) ++ ".json"
    pure (identity, probe, index)
  case evidence of
    Nothing -> acquireInstalledBundle cache staging recipe driverHash context registrationUnit (\_ _ _ -> pure ())
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
        owner <- field inputs "unit"
        buildKey <- field inputs "buildKey"
        exportKey <- field inputs "exportKey"
        partition <- installedPartition context
        require (length exportKey == 64 && all isHexDigit exportKey) "invalid installed export key"
        let destination = cache </> "core-bundles/v1" </> partition </> exportKey </> registeredId registrationUnit ++ ".zip"
        loaded <- readBundle TargetLayoutBundle destination owner buildKey exportKey inputs
          (map fst (installedInterfaces registrationUnit))
        bundle <- maybe (fail "invalid indexed installed bundle") pure loaded
        require (jsonField record "bundleSha256" == Just (bundleHash bundle)) "changed indexed installed bundle"
        after <- probeInstalled context registrationUnit
        require (after == probe) "installed payload changed while validating cached bundle"
        validateSourceObservations sources
        pure (InstalledBundle owner bundle)
      case hit of
        Just bundle -> pure (Right bundle)
        Nothing -> acquireInstalledBundle cache staging recipe driverHash context registrationUnit $ \bundle inputs modules -> do
          _ <- optionalIO $ do
            sources <- installedSourceObservations modules
            validateSourceObservations sources
            after <- probeInstalled context registrationUnit
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
acquireInstalledBundle :: FilePath -> FilePath -> FilePath -> String -> InstalledContext -> InstalledUnit ->
                          (InstalledBundle -> Value -> [(String, BS.ByteString)] -> IO ()) ->
                          IO (Either MissingCore InstalledBundle)
acquireInstalledBundle cache staging recipe driverHash context registrationUnit remember = do
  acquired <- acquireInstalled context registrationUnit
  case acquired of
    Left missing -> pure (Left missing)
    Right core -> do
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
      require (all cacheName [compilerId, compilerAbi, compilerPlatform, registered])
        "installed Core compiler or registered package-cache identity is invalid"
      let unit = coreOwner core
          modules = sortOn fst (coreModules core)
          inputFields = ["format" .= ("thc-core-build-inputs" :: String), "schema" .= (1 :: Int),
            "unit" .= unit, "compiler" .= installedCompiler context,
            "component" .= object ["kind" .= ("installed-interface" :: String),
                                   "registration" .= installedProvenance context registrationUnit],
            "rtsRegistration" .= rtsRegistration,
            "recipeArtifacts" .= [object ["path" .= ("compiler/target-layout.c" :: String),
                                           "sha256" .= recipeHash]],
            "nativeArtifacts" .= ([] :: [Value]),
            "generatedCore" .= [object ["module" .= name, "sha256" .= shaHex bytes] | (name, bytes) <- modules],
            "dependencies" .= installedDepends registrationUnit]
          buildKey = shaHex (BL.toStrict (encode (object inputFields)))
          exporter = object (["helperHash" .= helperHash, "driverHash" .= driverHash,
                             "options" .= (["post-tidy", "unit-qualified", "source-notes", "dynamic"] :: [String])] ++
                             ["foreignLinkRecipe" .= ("original-capi-llvm-v4" :: String)
                             | any ((== "System.CPUTime.Posix.ClockGetTime") . fst) modules])
          exportKey = shaHex (BL.toStrict (encode ("thc-installed-interface-v2" :: String, buildKey, exporter)))
          inputs = object (inputFields ++ ["buildKey" .= buildKey, "exportKey" .= exportKey, "exporter" .= exporter])
          directory = cache </> "core-bundles/v1" </>
            (compilerId ++ "-" ++ compilerAbi ++ "-" ++ compilerPlatform) </> exportKey
          destination = directory </> (registered ++ ".zip")
      createDirectoryIfMissing True directory
      bundle <- withLock (destination ++ ".lock") $ do
        present <- doesFileExist destination
        cached <- if present then readBundle TargetLayoutBundle destination unit buildKey exportKey inputs (map fst modules)
                  else pure Nothing
        case cached of
          Just hit -> pure hit
          Nothing -> do
            createDirectoryIfMissing True staging
            (temporary, handle) <- openTempFile staging "installed-layout-"
            hClose handle
            removeFile temporary
            createDirectory temporary
            (do
              layout <- readJson =<< probeTargetLayout includes recipe temporary
              require (validTargetLayout layout &&
                       jsonField layout "targetPlatform" == Just compilerPlatform)
                "installed GHC target layout differs from selected compiler"
              linked <- forM modules $ \(name, bytes) -> do
                result <- linkClockGetTime (installedLibdir context) staging
                  compilerPlatform unit name bytes
                pure (name, result)
              let members = [("core/" ++ show index ++ ".json", bytes)
                            | (index, (_, bytes)) <- zip [0 :: Int ..] linked]
                  refs = [object ["name" .= name, "boundary" .= boundary,
                                  "path" .= member, "sha256" .= shaHex bytes]
                         | ((name, bytes), (member, _)) <- zip linked members]
                  receiptBytes = BL.toStrict (encode (object (inputFields ++
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
              pure (Bundle destination (shaHex (BL.toStrict archive)) refs buildKey))
              `finally` removePathForcibly temporary
      let result = InstalledBundle unit bundle
      remember result inputs modules
      pure (Right result)

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
  let pinned = thcRoot </> "compiler/pinned-ghc-internal"
      layoutRecipe = thcRoot </> "compiler/target-layout.c"
      unit = "ghc-internal" :: String
      names = map snd moduleSources
      sourceArtifact (name, digest) = object
        ["path" .= ("compiler/pinned-ghc-internal/" ++ name), "sha256" .= digest]
  forM_ sourceHashes $ \(name, expected) -> do
    let path = pinned </> name
    requireFile path
    actual <- digestFile path
    require (actual == expected) ("pinned GHC 9.14.1 source changed: " ++ name)
  requireFile layoutRecipe
  recipeHash <- digestFile layoutRecipe
  pluginHash <- digestFile (contextPluginLibrary context)
  let inputFields = ["format" .= ("thc-core-build-inputs" :: String), "schema" .= (1 :: Int),
                     "unit" .= unit,
                     "compiler" .= object ["id" .= contextCompiler context,
                                           "abi" .= contextAbi context,
                                           "platform" .= contextPlatform context,
                                           "way" .= ("dynamic-nonprofiling" :: String)],
                     "component" .= object ["kind" .= ("pinned-wired-source" :: String),
                                             "modules" .= names],
                     "nativeArtifacts" .= ([] :: [Value]),
                     "sourceArtifacts" .= map sourceArtifact sourceHashes,
                     "recipeArtifacts" .= [object
                       ["path" .= ("compiler/target-layout.c" :: String),
                        "sha256" .= recipeHash]],
                     "dependencies" .= ([] :: [Value])]
      buildKey = shaHex (BL.toStrict (encode (object inputFields)))
      exporter = object ["pluginUnit" .= contextPluginUnit context,
                         "pluginDb" .= contextPluginDb context,
                         "pluginHash" .= pluginHash,
                         "driverHash" .= contextDriverHash context,
                         "options" .= (["ghc-internal-source-closure-v2", "post-tidy",
                                        "source-notes", "foreign-import-provenance",
                                        "hsc2hs", "-g", "-dynamic", "-dcore-lint",
                                        "-XNoPolyKinds"] :: [String])]
      exportKey = shaHex (BL.toStrict (encode ("thc-wired-ghc-internal-v2" :: String,
                                             buildKey, exporter)))
      buildInputs = object (inputFields ++ ["buildKey" .= buildKey,
                                           "exportKey" .= exportKey, "exporter" .= exporter])
      directory = contextCache context </> "core-bundles/v1" </>
                  (contextCompiler context ++ "-" ++ contextAbi context ++ "-" ++
                   contextPlatform context) </> exportKey
      destination = directory </> ("ghc-internal-" ++ buildKey ++ ".zip")
  createDirectoryIfMissing True directory
  bundle <- withLock (destination ++ ".lock") $ do
    cached <- doesFileExist destination
    hit <- if cached then readBundle PinnedSourceBundle destination unit buildKey exportKey buildInputs (sort names)
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
          artifacts <- exportPinnedCore pinned (contextGhc context) packageTool
                           (contextPluginLibrary context) (contextPluginUnit context)
                           layoutRecipe staging
          layout <- readJson (targetLayout artifacts)
          require (validTargetLayout layout &&
                   jsonField layout "targetPlatform" == Just (contextPlatform context))
                  "GHC target layout receipt differs from compiler target"
          generated <- forM (generatedSources artifacts) $ \(name, path) -> do
            digest <- digestFile path
            pure (object ["path" .= name, "sha256" .= digest])
          members <- forM names $ \name -> do
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
          let refs = [object ["name" .= name, "boundary" .= boundary,
                              "path" .= ("core/" ++ name ++ ".json"),
                              "sha256" .= shaHex bytes]
                     | (name, (_, bytes)) <- zip names members]
              derived = ["targetLayout" .= layout, "generatedSources" .= generated]
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
          pure (Bundle destination (shaHex (BL.toStrict archive)) refs buildKey))
          `finally` cleanup
  pure (object ["id" .= unit, "depends" .= ([] :: [String]),
                "modules" .= bundleModules bundle,
                "bundle" .= object ["path" .= bundlePath bundle,
                                   "sha256" .= bundleHash bundle]])

-- Cabal locks its own build tree; this also keeps the THC cache and the
-- published package manifest coherent for concurrent runs of one project.
withProjectLock :: FilePath -> IO a -> IO a
withProjectLock output = withLock (output </> ".lock")


readUnit :: Value -> IO Unit
readUnit value = do
  identifier <- field value "id"
  dependencies <- optionalField value "depends" []
  kind <- optionalField value "type" ("" :: String)
  style <- optionalField value "style" ("" :: String)
  pure (Unit identifier value dependencies (kind == "configured" && style == "local"))

executableSelection :: String -> IO (Maybe String, String)
executableSelection target = case split ':' target of
  [name] | not (null name) -> pure (Nothing, "exe:" ++ name)
  [package, "exe", name] | not (null package) && not (null name) ->
    pure (Just package, "exe:" ++ name)
  _ -> fail "--exe must be NAME or PACKAGE:exe:NAME"

selectExecutable :: String -> [Unit] -> IO Unit
selectExecutable target units = do
  (wantedPackage, wantedComponent) <- executableSelection target
  matches <- filterM (\unit -> if not (unitLocal unit) then pure False else do
    component <- optionalField (unitValue unit) "component-name" ("" :: String)
    package <- optionalField (unitValue unit) "pkg-name" ("" :: String)
    pure (component == wantedComponent && maybe True (== package) wantedPackage)) units
  case matches of
    [unit] -> pure unit
    _ -> fail ("selected executable " ++ show target ++ " has " ++ show (length matches) ++
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

-- Cabal's source-built store ID is the immutable package identity. Unlike
-- local -inplace IDs, it already includes Cabal's source/configuration hash.
globalLocation :: ExportContext -> Unit -> IO (String, String, FilePath)
globalLocation context unit = do
  sourceHash <- field (unitValue unit) "pkg-src-sha256" :: IO String
  require (length sourceHash == 64 && all isHexDigit sourceHash &&
           all (\c -> isAlphaNum c || c `elem` ("-._" :: String)) (unitId unit))
    ("Cabal store source identity is invalid for " ++ unitId unit)
  let buildKey = shaHex (BL.toStrict (encode
        ("thc-core-store-build-v1" :: String, contextCompiler context,
         contextAbi context, contextPlatform context, unitId unit,
         sourceHash, unitDepends unit)))
  exporter <- exporterIdentity context
  let exportKey = shaHex (BL.toStrict (encode
        ("thc-core-export-v1" :: String, buildKey, exporter)))
      destination = contextCache context </> "core-bundles/v1" </>
        (contextCompiler context ++ "-" ++ contextAbi context ++ "-" ++ contextPlatform context) </>
        exportKey </> (unitId unit ++ ".zip")
  pure (buildKey, exportKey, destination)

exporterIdentity :: ExportContext -> IO Value
exporterIdentity context = do
  pluginHash <- digestFile (contextPluginLibrary context)
  pure $ object ["pluginUnit" .= contextPluginUnit context,
                 "pluginDb" .= contextPluginDb context,
                 "pluginHash" .= pluginHash,
                 "driverHash" .= contextDriverHash context,
                 "options" .= (["post-tidy", "unit-qualified", "source-notes",
                                "foreign-import-provenance",
                                "-g", "-dynamic", "-dcore-lint"] :: [String])]

prepareGlobalBundles :: ExportContext -> FilePath -> String -> [Unit] -> IO (Map.Map String Bundle)
prepareGlobalBundles _ _ _ [] = pure Map.empty
prepareGlobalBundles context project target units = do
  let lockDir = contextCache context </> "core-bundles/v1"
  createDirectoryIfMissing True lockDir
  withLock (lockDir </> "global-export.lock") $ do
    located <- forM units $ \unit -> do
      (buildKey, exportKey, path) <- globalLocation context unit
      cached <- doesFileExist path
      hit <- if cached then readGlobalBundle path (unitId unit) (unitDepends unit) buildKey exportKey
             else pure Nothing
      pure (unit, buildKey, exportKey, path, hit)
    let missing = [(unit, buildKey, exportKey, path)
                  | (unit, buildKey, exportKey, path, Nothing) <- located]
    when (not (null missing)) $ captureGlobalUnits context project target (map first4 missing) missing
    pairs <- forM located $ \(unit, buildKey, exportKey, path, hit) -> do
      bundle <- case hit of
        Just value -> pure value
        Nothing -> do
          ready <- readGlobalBundle path (unitId unit) (unitDepends unit) buildKey exportKey
          maybe (fail ("Cabal store Core bundle was not published: " ++ unitId unit)) pure ready
      pure (unitId unit, bundle)
    pure (Map.fromList pairs)
  where first4 (unit, _, _, _) = unit

captureGlobalUnits :: ExportContext -> FilePath -> String -> [Unit] ->
                      [(Unit, String, String, FilePath)] -> IO ()
captureGlobalUnits context project target requested missing = do
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
        -- Rebuild only the selected executable's closure. `all` also builds
        -- unrelated tests/apps and their dependencies in this fresh store.
        arguments = ["--store-dir=" ++ store, "build", target,
                     "--enable-build-info", "--project-file", "cabal.project",
                     "--builddir", dist, "--with-compiler", wrapper] ++
                    maybe [] (\path -> ["--with-hc-pkg", path]) (contextGhcPkg context)
    writeFile wrapper ("#!/bin/sh\n" ++ ghcProxyCommand)
    permissions <- getPermissions wrapper
    setPermissions wrapper (permissions {Directory.executable = True})
    inherited <- getEnvironment
    let overrides = [("THC_PROXY_DRIVER", contextDriver context),
                     ("THC_PROXY_GHC", contextGhc context),
                     ("THC_PROXY_CAPTURE", capture),
                     ("THC_PROXY_PLUGIN_DB", contextPluginDb context),
                     ("THC_PROXY_PLUGIN_UNIT", contextPluginUnit context),
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
    forM_ missing $ \(unit, buildKey, exportKey, path) -> do
      rebuilt <- maybe (fail ("isolated Cabal plan omitted store unit " ++ unitId unit)) pure
                 (Map.lookup (unitId unit) byId)
      originalHash <- field (unitValue unit) "pkg-src-sha256" :: IO String
      rebuiltHash <- field (unitValue rebuilt) "pkg-src-sha256" :: IO String
      kind <- field (unitValue rebuilt) "type" :: IO String
      style <- field (unitValue rebuilt) "style" :: IO String
      require (kind == "configured" && style == "global" &&
               originalHash == rebuiltHash && unitDepends unit == unitDepends rebuilt)
        ("isolated Cabal build changed store identity for " ++ unitId unit)
      createDirectoryIfMissing True (takeDirectory path)
      withLock (path ++ ".lock") $
        packGlobalBundle store capture unit buildKey exportKey path
    ) `finally` cleanup

packGlobalBundle :: FilePath -> FilePath -> Unit -> String -> String -> FilePath -> IO ()
packGlobalBundle store capture unit buildKey exportKey destination = do
  let core = capture </> unitId unit </> "core"
  exported <- filter ((== ".json") . takeExtension) <$> recursiveFiles core
  empty <- if not (null exported) then pure [] else do
    -- Inspect only this freshly rebuilt private store, never the original
    -- native store or a guessed empty module. Cabal owns the compiler partition.
    partitions <- listDirectory store
    registrations <- filterM doesFileExist
      [store </> partition </> "package.db" </> unitId unit <.> "conf" | partition <- partitions]
    bytes <- case registrations of
      [path] -> BS.readFile path
      _ -> fail ("isolated Cabal store lacks a unique registration for " ++ unitId unit)
    require (emptyRegistration (unitId unit) (unitDepends unit) bytes)
      ("Cabal store build did not export Core for nonempty unit " ++ unitId unit)
    pure ["emptyRegistration" .= Text.decodeUtf8 bytes]
  checked <- forM exported $ \path -> do
    value <- readJson path
    foundUnit <- field value "unit"
    foundBoundary <- field value "boundary" :: IO String
    name <- field value "module" :: IO String
    require (foundUnit == unitId unit && foundBoundary == boundary)
      ("store Core artifact has wrong owner or boundary: " ++ path)
    bytes <- BS.readFile path
    pure (name, bytes)
  linked <- finishPackageNative (takeDirectory capture </> "native-pieces") (capture </> unitId unit) (unitId unit) Nothing checked
  let sorted = sortOn fst linked
      names = map fst sorted
  require (length names == length (nub names))
    ("duplicate exported store modules for " ++ unitId unit)
  let members = [("core/" ++ show index ++ ".json", bytes)
                | (index, (_, bytes)) <- zip [0 :: Int ..] sorted]
      modules = [object ["name" .= name, "boundary" .= boundary,
                         "path" .= member, "sha256" .= shaHex bytes]
                | ((name, bytes), (member, _)) <- zip sorted members]
      inner = object (["format" .= ("thc-core-bundle" :: String), "schema" .= (1 :: Int),
                      "unit" .= unitId unit, "buildKey" .= buildKey,
                      "exportKey" .= exportKey, "modules" .= modules] ++ empty)
  archive <- either fail pure (encodeZip (("manifest.json", BL.toStrict (encode inner)) : members))
  atomicBytes destination (BL.toStrict archive)

readGlobalBundle :: FilePath -> String -> [String] -> String -> String -> IO (Maybe Bundle)
readGlobalBundle path unit dependencies buildKey exportKey = do
  bytes <- BS.readFile path
  decoded <- decodeZip bytes
  pure $ do
    entries <- either (const Nothing) Just decoded
    raw <- lookup "manifest.json" entries
    inner <- either (const Nothing) Just (eitherDecodeStrict' raw)
    modules <- jsonField inner "modules" :: Maybe [Value]
    let validInventory = case jsonField inner "emptyRegistration" :: Maybe Text.Text of
          Nothing -> not (null modules)
          Just receipt -> null modules && emptyRegistration unit dependencies (Text.encodeUtf8 receipt)
        names = [name | Just name <- map (`jsonField` "name") modules :: [Maybe String]]
        paths = [member | Just member <- map (`jsonField` "path") modules :: [Maybe String]]
        validModule item = do
          name <- jsonField item "name" :: Maybe String
          member <- jsonField item "path" :: Maybe String
          digest <- jsonField item "sha256" :: Maybe String
          foundBoundary <- jsonField item "boundary" :: Maybe String
          body <- lookup member entries
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
        length paths == length modules && length names == length (nub names) &&
        sort (map fst entries) == sort ("manifest.json" : paths) &&
        all (== Just True) (map validModule modules))
      then Just (Bundle path (shaHex bytes) modules buildKey)
      else Nothing

exportUnit :: ExportContext -> [FilePath] -> Map.Map String String -> Unit -> IO Bundle
exportUnit context roots keys unit = do
  component <- readComponent unit context
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
  dist <- field (unitValue unit) "dist-dir"
  let productFlags = ["-odir", "-hidir", "-hiedir", "-stubdir", "-outputdir"]
      productRoots = nub [path | (flag, path) <- zip (componentArguments component)
                                                   (drop 1 (componentArguments component)),
                                 flag `elem` productFlags]
  require (not (null productRoots) && all (within dist) productRoots)
    ("Cabal build-info lacks component-scoped native output roots for " ++ unitId unit)
  products <- sort . nub . filter nativeProduct . concat <$> mapM recursiveFiles productRoots
  require (not (null products)) ("native Cabal build has no Haskell artifacts for " ++ unitId unit)
  nativeInputs <- forM products $ \path -> do
    digest <- digestFile path
    pure (makeRelative (contextNative context) path, digest)
  let dependencies = [(identifier, Map.findWithDefault identifier identifier keys)
                     | identifier <- unitDepends unit]
      normalized = normalizePaths (contextNative context)
      inputFields = ["format" .= ("thc-core-build-inputs" :: String), "schema" .= (1 :: Int),
                     "unit" .= unitId unit,
                     "compiler" .= object ["id" .= contextCompiler context,
                                           "abi" .= contextAbi context,
                                           "platform" .= contextPlatform context],
                     "component" .= normalized (componentValue component),
                     "nativeArtifacts" .= [object ["path" .= path, "sha256" .= digest]
                                           | (path, digest) <- nativeInputs],
                     "dependencies" .= [object ["id" .= identifier, "buildKey" .= identity]
                                        | (identifier, identity) <- dependencies]] ++
                    maybe [] (\recipe -> ["packageScalarRecipe" .= scalarBuildInputs recipe]) scalar ++
                    maybe [] (\recipe -> ["runtimeShimRecipe" .= runtimeShimInputs recipe]) runtimeShim
      buildKey = shaHex (BL.toStrict (encode (object inputFields)))
  pluginHash <- digestFile (contextPluginLibrary context)
  let exporter = object $ ["pluginUnit" .= contextPluginUnit context,
                         "pluginDb" .= contextPluginDb context,
                         "pluginHash" .= pluginHash,
                         "driverHash" .= contextDriverHash context,
                         "options" .= (["post-tidy", "unit-qualified", "source-notes",
                                         "foreign-import-provenance",
                                         "-g", "-dynamic", "-dcore-lint"] :: [String])] ++
                     maybe [] (\digest -> ["scalarInterfaceHelperSha256" .= digest,
                       "scalarInterfaceOptions" .= (["-fwrite-if-simplified-core", "-hisuf", "hi"] :: [String])]) helperHash
      exportKey = shaHex (BL.toStrict (encode ("thc-core-export-v1" :: String, buildKey, exporter)))
      buildInputs = object (inputFields ++ ["buildKey" .= buildKey,
                                           "exportKey" .= exportKey,
                                           "exporter" .= exporter])
      directory = contextNative context </> "cache/thc/core-bundles/v1" </> exportKey
      destination = directory </> (unitId unit ++ "-" ++ buildKey ++ ".zip")
  expected <- expectedModuleNames (componentValue component)
  createDirectoryIfMissing True directory
  withLock (destination ++ ".lock") $ do
    cached <- doesFileExist destination
    hit <- if cached then readBundle PlainBundle destination (unitId unit) buildKey exportKey buildInputs expected
           else pure Nothing
    case hit of
      Just bundle -> pure bundle
      Nothing -> do
        when cached (removeFile destination)
        freshExport context component unit scalar runtimeShim helper nativeObjects buildKey exportKey buildInputs expected destination

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
    let arguments = ["--make", "-no-link"] ++ componentArguments component ++
          ["-outputdir", objects, "-odir", objects, "-hidir", objects,
           "-hiedir", objects </> "hie", "-stubdir", objects,
           "-package-db", contextPluginDb context, "-plugin-package-id", contextPluginUnit context,
           "-fplugin=THC.Plugin", "-fplugin-opt=THC.Plugin:" ++ core,
           "-fplugin-opt=THC.Plugin:post-tidy", "-fplugin-opt=THC.Plugin:unit-qualified",
           "-fplugin-opt=THC.Plugin:source-notes",
           "-fplugin-opt=THC.Plugin:foreign-import-provenance",
           "-g", "-dynamic", "-fforce-recomp", "-dcore-lint", "-fwrite-if-simplified-core", "-hisuf", "hi"] ++
          map snd (componentSources component)
    sourceDir <- field (componentValue component) "src-dir"
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
          capturePackageNative (installedHelper selectedHelper) (installedLibdir selectedHelper)
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
    let members = [("core/" ++ show index ++ ".json", bytes)
                  | (index, (_, bytes)) <- zip [0 :: Int ..] sorted]
        modules = [object ["name" .= name, "boundary" .= boundary,
                           "path" .= member, "sha256" .= shaHex bytes]
                  | ((name, bytes), (member, _)) <- zip sorted members]
        inputsBytes = BL.toStrict (encode buildInputs)
        inner = object ["format" .= ("thc-core-bundle" :: String), "schema" .= (1 :: Int),
                        "unit" .= unitId unit, "buildKey" .= buildKey,
                        "exportKey" .= exportKey, "modules" .= modules,
                        "buildInputs" .= object ["path" .= ("inplace-manifest.json" :: String),
                                                 "sha256" .= shaHex inputsBytes]]
    archive <- either fail pure (encodeZip
      (("manifest.json", BL.toStrict (encode inner)) : ("inplace-manifest.json", inputsBytes) : members))
    atomicBytes destination (BL.toStrict archive)
    pure (Bundle destination (shaHex (BL.toStrict archive)) modules buildKey)) `finally` cleanup

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
          "--interface",interface,"--way","dynamic","--source-notes"] ++
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

readBundle :: BundleReceipt -> FilePath -> String -> String -> String -> Value -> [String] -> IO (Maybe Bundle)
readBundle receipt path unit buildKey exportKey buildInputs expected = do
  bytes <- BS.readFile path
  decoded <- decodeZip bytes
  pure $ do
    entries <- either (const Nothing) Just decoded
    raw <- lookup "manifest.json" entries
    inner <- either (const Nothing) Just (eitherDecodeStrict' raw)
    modules <- jsonField inner "modules" :: Maybe [Value]
    inputRef <- jsonField inner "buildInputs" :: Maybe Value
    inputPath <- jsonField inputRef "path" :: Maybe String
    inputHash <- jsonField inputRef "sha256" :: Maybe String
    inputBytes <- lookup inputPath entries
    storedInputs <- either (const Nothing) Just (eitherDecodeStrict' inputBytes)
    let storedBase = case storedInputs of
          Object fields -> Object (KeyMap.delete "generatedSources"
            (KeyMap.delete "targetLayout" fields))
          value -> value
        layout = jsonField storedInputs "targetLayout" :: Maybe Value
        innerLayout = jsonField inner "targetLayout" :: Maybe Value
        generated = jsonField storedInputs "generatedSources" :: Maybe [Value]
        innerGenerated = jsonField inner "generatedSources" :: Maybe [Value]
        compiler = jsonField buildInputs "compiler" :: Maybe Value
        platform = compiler >>= (`jsonField` "platform") :: Maybe String
        layoutPlatform = layout >>= (`jsonField` "targetPlatform") :: Maybe String
    let names = [name | Just name <- map (`jsonField` "name") modules]
        paths = [member | Just member <- map (`jsonField` "path") modules]
        validModule item = do
          name <- jsonField item "name" :: Maybe String
          member <- jsonField item "path" :: Maybe String
          digest <- jsonField item "sha256" :: Maybe String
          foundBoundary <- jsonField item "boundary" :: Maybe String
          body <- lookup member entries
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
            maybe False validGeneratedSources generated) &&
        length names == length modules && length paths == length modules &&
        length names == length (nub names) && sort names == expected &&
        sort (map fst entries) == sort ("manifest.json" : inputPath : paths) &&
        all (== Just True) (map validModule modules))
      then Just (Bundle path (shaHex bytes) modules buildKey)
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

validGeneratedSources :: [Value] -> Bool
validGeneratedSources generated =
  let paths = map (`jsonField` "path") generated :: [Maybe FilePath]
      digests = map (`jsonField` "sha256") generated :: [Maybe String]
      expected = sort [path | (path, _) <- moduleSources, takeExtension path == ".hsc"]
  in sort [path | Just path <- paths] == expected &&
     length paths == length expected &&
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
  (temporary, handle) <- openTempFile (takeDirectory path) ".core-zip-"
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
  when (componentType == ("exe" :: String)) $
    require (length files == 1) "project executable must have one Haskell main source"
  pure (sort (modules ++ if componentType == "exe" then ["Main"] else []))

readComponent :: Unit -> ExportContext -> IO Component
readComponent = readComponentMetadata True

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
  require (not selected || compilerPath == contextNative context </> "cache/thc/native-ghc")
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
  arguments <- field value "compiler-args"
  require (hasPair "-this-unit-id" (unitId unit) arguments)
    ("compiler arguments have wrong unit ID for " ++ unitId unit)
  sources <- if selected then sourcePaths value arguments else pure []
  flags <- field (unitValue unit) "flags" :: IO (Map.Map String Bool)
  configured <- case value of
    Object fields -> pure (Object (KeyMap.insert "thc-cabal-configuration" (object
      ["flags" .= flags, "compiler" .= contextCompiler context,
       "platform" .= contextPlatform context]) fields))
    _ -> fail "Cabal component metadata must be an object"
  pure (Component configured (contextGhc context) arguments sources)

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
  (temporary, handle) <- openTempFile (takeDirectory path) (takeFileName path ++ ".")
  BS.hPut handle (BL.toStrict (encode value) <> BS.pack [10])
  hClose handle
  renameFile temporary path

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
digestFile path = shaHex <$> BS.readFile path

shaHex :: BS.ByteString -> String
shaHex = concatMap byteHex . BS.unpack . SHA.hash
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
