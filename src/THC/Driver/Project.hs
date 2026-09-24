-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

module THC.Driver.Project (runProject) where

import Control.Exception (bracket, finally)
import Control.Monad (filterM, forM, forM_, unless, when)
import Data.Char (isAlphaNum, isHexDigit)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (FromJSON, Value(..), eitherDecodeStrict', encode, object, (.=))
import qualified Data.Aeson as Aeson
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.List (isSuffixOf, nub, nubBy, sort, sortOn)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import qualified Data.Text as Text
import Numeric (showHex)
import System.Directory (canonicalizePath, createDirectory, createDirectoryIfMissing,
                         doesDirectoryExist, doesFileExist, findExecutable, getPermissions,
                         listDirectory, makeAbsolute, removeFile, removePathForcibly,
                         renameFile, setPermissions)
import qualified System.Directory as Directory
import System.Exit (ExitCode(..))
import System.Environment (getEnvironment, getExecutablePath)
import System.FilePath ((</>), isAbsolute, makeRelative, normalise, splitDirectories,
                        takeDirectory, takeExtension, takeFileName, joinPath, replaceExtension)
import System.IO (SeekMode(AbsoluteSeek), hClose, openTempFile, stderr)
import qualified System.Posix.IO as Posix
import System.Process (CreateProcess(..), StdStream(..), createProcess, proc, waitForProcess)
import THC.Driver.Cabal (PlanOptions(..))
import THC.Driver.Cache (coreCacheDirectory)
import THC.Driver.Run (RunOptions(..))
import THC.Driver.Zip (decodeZip, encodeZip)

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

data ExportContext = ExportContext
  { contextCompiler :: String, contextAbi :: String, contextPlatform :: String
  , contextPluginDb :: FilePath, contextPluginUnit :: String
  , contextPluginLibrary :: FilePath, contextNative :: FilePath
  , contextCache :: FilePath, contextDriverHash :: String
  , contextGhc :: FilePath, contextDriver :: FilePath }

boundary :: String
boundary = "optimized-Core-after-Tidy-before-CorePrep"

runProject :: RunOptions -> FilePath -> IO ()
runProject opts target = do
  require (not (null (runExecutable opts))) "run requires --exe NAME"
  require (not (null (runThcRoot opts))) "run requires --thc-root DIR"
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
  requireFile pluginLibrary
  requireDirectory pluginDb
  let requested = distDirectory flags
      output = if isAbsolute requested then requested else project </> requested
      native = output </> "native"
      executable = runExecutable opts
      cabalArgs = ["build", "all", "--enable-build-info", "--project-file", "cabal.project",
                   "--builddir", native] ++
                  maybe [] (\path -> ["--with-compiler", path]) (ghcPath flags) ++
                  maybe [] (\path -> ["--with-hc-pkg", path]) (ghcPkgPath flags)
  createDirectoryIfMissing True output
  compiler <- case ghcPath flags of
    Just path -> canonicalizePath path
    Nothing -> findExecutable "ghc" >>= maybe (fail "GHC compiler not found") canonicalizePath
  withProjectLock output $
    runBuiltProject project thcRoot runtime output native executable cabalArgs
                    pluginDb pluginUnit pluginLibrary compiler

runBuiltProject :: FilePath -> FilePath -> FilePath -> FilePath -> FilePath ->
                   String -> [String] -> FilePath -> String -> FilePath -> FilePath -> IO ()
runBuiltProject project thcRoot runtime output native executable cabalArgs
                pluginDb pluginUnit pluginLibrary ghc = do
  runCommand True "cabal" cabalArgs project
  plan <- readJson (native </> "cache/plan.json")
  cabalVersion <- field plan "cabal-version"
  compilerId <- field plan "compiler-id"
  require (take 5 cabalVersion == "3.16." && compilerId == "ghc-9.14.1")
    "THC project run requires cabal-install 3.16 and GHC 9.14.1"
  abi <- field plan "compiler-abi"
  os <- field plan "os"
  arch <- field plan "arch"
  cacheRoot <- coreCacheDirectory
  driver <- getExecutablePath
  driverHash <- digestFile driver
  let context = ExportContext compilerId abi (arch ++ "-" ++ os) pluginDb pluginUnit
                              pluginLibrary native cacheRoot driverHash ghc driver
  records <- field plan "install-plan" :: IO [Value]
  units <- mapM readUnit records
  let byId = Map.fromList [(unitId unit, unit) | unit <- units]
  require (Map.size byId == length units) "Cabal plan has duplicate unit IDs"
  selected <- selectExecutable executable units
  closures <- mapM (dependencyClosure byId . unitId) (filter unitLocal units)
  let ordered = nubBy (\a b -> unitId a == unitId b) (concat closures)
      globals = [unit | unit <- ordered, not (unitLocal unit),
                       jsonField (unitValue unit) "type" == Just ("configured" :: String)]
  globalBundles <- prepareGlobalBundles context project globals
  (_, described) <- foldlM (\(keys, acc) unit -> do
    kind <- optionalField (unitValue unit) "type" ("" :: String)
    bundle <- if unitLocal unit
      then Just <$> exportUnit context keys unit
      else do
        if kind == "configured" then Just <$> maybe
          (fail ("Core bundle missing for Cabal store component " ++ unitId unit)) pure
          (Map.lookup (unitId unit) globalBundles)
        else pure Nothing
    let modules = maybe [] bundleModules bundle
        fields = ["id" .= unitId unit, "depends" .= unitDepends unit, "modules" .= modules] ++
                 maybe [] (\item -> ["bundle" .= object ["path" .= bundlePath item,
                                                   "sha256" .= bundleHash item]]) bundle
        keys' = maybe keys (\item -> Map.insert (unitId unit) (bundleBuildKey item) keys) bundle
    pure (keys', acc ++ [object fields])) (Map.empty, []) ordered
  let manifest = output </> "packages.json"
      entry = unitId selected ++ ":Main.main"
      audit = output </> "audit.json"
  atomicJson manifest (object ["format" .= ("thc-core-packages" :: String),
                               "schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
                               "units" .= described])
  runCommand True "python3" [thcRoot </> "scripts/audit-core.py", "--package-manifest", manifest,
                             "--entry", entry, "--io-main", "--output", audit] thcRoot
  runCommand False runtime ["--run-io", '@' : manifest, entry] thcRoot

-- Cabal locks its own build tree; this also keeps the THC cache and the
-- published package manifest coherent for concurrent runs of one project.
withProjectLock :: FilePath -> IO a -> IO a
withProjectLock output = withLock (output </> ".lock")

withLock :: FilePath -> IO a -> IO a
withLock path action =
  bracket (Posix.openFd path Posix.ReadWrite
             (Posix.defaultFileFlags {Posix.creat = Just 0o600})) Posix.closeFd $ \descriptor -> do
    Posix.waitToSetLock descriptor (Posix.WriteLock, AbsoluteSeek, 0, 0)
    action

readUnit :: Value -> IO Unit
readUnit value = do
  identifier <- field value "id"
  dependencies <- optionalField value "depends" []
  kind <- optionalField value "type" ("" :: String)
  style <- optionalField value "style" ("" :: String)
  pure (Unit identifier value dependencies (kind == "configured" && style == "local"))

selectExecutable :: String -> [Unit] -> IO Unit
selectExecutable target units = do
  let pieces = split ':' target
  (wantedPackage, wantedComponent) <- case pieces of
    [name] | not (null name) -> pure (Nothing, "exe:" ++ name)
    [package, "exe", name] | not (null package) && not (null name) ->
      pure (Just package, "exe:" ++ name)
    _ -> fail "--exe must be NAME or PACKAGE:exe:NAME"
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
                                "-g", "-dynamic", "-dcore-lint"] :: [String])]

prepareGlobalBundles :: ExportContext -> FilePath -> [Unit] -> IO (Map.Map String Bundle)
prepareGlobalBundles _ _ [] = pure Map.empty
prepareGlobalBundles context project units = do
  let lockDir = contextCache context </> "core-bundles/v1"
  createDirectoryIfMissing True lockDir
  withLock (lockDir </> "global-export.lock") $ do
    located <- forM units $ \unit -> do
      (buildKey, exportKey, path) <- globalLocation context unit
      cached <- doesFileExist path
      hit <- if cached then readGlobalBundle path (unitId unit) buildKey exportKey
             else pure Nothing
      pure (unit, buildKey, exportKey, path, hit)
    let missing = [(unit, buildKey, exportKey, path)
                  | (unit, buildKey, exportKey, path, Nothing) <- located]
    when (not (null missing)) $ captureGlobalUnits context project (map first4 missing) missing
    pairs <- forM located $ \(unit, buildKey, exportKey, path, hit) -> do
      bundle <- case hit of
        Just value -> pure value
        Nothing -> do
          ready <- readGlobalBundle path (unitId unit) buildKey exportKey
          maybe (fail ("Cabal store Core bundle was not published: " ++ unitId unit)) pure ready
      pure (unitId unit, bundle)
    pure (Map.fromList pairs)
  where first4 (unit, _, _, _) = unit

captureGlobalUnits :: ExportContext -> FilePath -> [Unit] ->
                      [(Unit, String, String, FilePath)] -> IO ()
captureGlobalUnits context project requested missing = do
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
        arguments = ["--store-dir=" ++ store, "build", "all", "--offline",
                     "--enable-build-info", "--project-file", "cabal.project",
                     "--builddir", dist, "--with-compiler", wrapper]
    writeFile wrapper "#!/bin/sh\nexec \"$THC_PROXY_DRIVER\" ghc-proxy \"$@\"\n"
    permissions <- getPermissions wrapper
    setPermissions wrapper (permissions {Directory.executable = True})
    inherited <- getEnvironment
    let overrides = [("THC_PROXY_DRIVER", contextDriver context),
                     ("THC_PROXY_GHC", contextGhc context),
                     ("THC_PROXY_CAPTURE", capture),
                     ("THC_PROXY_PLUGIN_DB", contextPluginDb context),
                     ("THC_PROXY_PLUGIN_UNIT", contextPluginUnit context),
                     ("THC_PROXY_GLOBAL_UNITS", unlines (map unitId requested))]
        environment = overrides ++ filter (\(key, _) -> key `notElem` map fst overrides) inherited
    runCommandWithEnv True "cabal" arguments project (Just environment)
    plan <- readJson (dist </> "cache/plan.json")
    isolated <- mapM readUnit =<< field plan "install-plan"
    let byId = Map.fromList [(unitId unit, unit) | unit <- isolated]
    forM_ missing $ \(unit, buildKey, exportKey, path) -> do
      rebuilt <- maybe (fail ("isolated Cabal plan omitted store unit " ++ unitId unit)) pure
                 (Map.lookup (unitId unit) byId)
      originalHash <- field (unitValue unit) "pkg-src-sha256" :: IO String
      rebuiltHash <- field (unitValue rebuilt) "pkg-src-sha256" :: IO String
      require (originalHash == rebuiltHash && unitDepends unit == unitDepends rebuilt)
        ("isolated Cabal build changed store identity for " ++ unitId unit)
      createDirectoryIfMissing True (takeDirectory path)
      withLock (path ++ ".lock") $
        packGlobalBundle capture unit buildKey exportKey path
    ) `finally` cleanup

packGlobalBundle :: FilePath -> Unit -> String -> String -> FilePath -> IO ()
packGlobalBundle capture unit buildKey exportKey destination = do
  let core = capture </> unitId unit </> "core"
  exported <- filter ((== ".json") . takeExtension) <$> recursiveFiles core
  require (not (null exported))
    ("Cabal store build did not export Core for " ++ unitId unit)
  checked <- forM exported $ \path -> do
    value <- readJson path
    foundUnit <- field value "unit"
    foundBoundary <- field value "boundary" :: IO String
    name <- field value "module" :: IO String
    require (foundUnit == unitId unit && foundBoundary == boundary)
      ("store Core artifact has wrong owner or boundary: " ++ path)
    bytes <- BS.readFile path
    pure (name, bytes)
  let sorted = sortOn fst checked
      names = map fst sorted
  require (length names == length (nub names))
    ("duplicate exported store modules for " ++ unitId unit)
  let members = [("core/" ++ show index ++ ".json", bytes)
                | (index, (_, bytes)) <- zip [0 :: Int ..] sorted]
      modules = [object ["name" .= name, "boundary" .= boundary,
                         "path" .= member, "sha256" .= shaHex bytes]
                | ((name, bytes), (member, _)) <- zip sorted members]
      inner = object ["format" .= ("thc-core-bundle" :: String), "schema" .= (1 :: Int),
                      "unit" .= unitId unit, "buildKey" .= buildKey,
                      "exportKey" .= exportKey, "modules" .= modules]
  archive <- either fail pure (encodeZip (("manifest.json", BL.toStrict (encode inner)) : members))
  atomicBytes destination (BL.toStrict archive)

readGlobalBundle :: FilePath -> String -> String -> String -> IO (Maybe Bundle)
readGlobalBundle path unit buildKey exportKey = do
  bytes <- BS.readFile path
  decoded <- decodeZip bytes
  pure $ do
    entries <- either (const Nothing) Just decoded
    raw <- lookup "manifest.json" entries
    inner <- either (const Nothing) Just (eitherDecodeStrict' raw)
    modules <- jsonField inner "modules" :: Maybe [Value]
    let names = [name | Just name <- map (`jsonField` "name") modules :: [Maybe String]]
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
        not (null modules) && length names == length modules &&
        length paths == length modules && length names == length (nub names) &&
        sort (map fst entries) == sort ("manifest.json" : paths) &&
        all (== Just True) (map validModule modules))
      then Just (Bundle path (shaHex bytes) modules buildKey)
      else Nothing

exportUnit :: ExportContext -> Map.Map String String -> Unit -> IO Bundle
exportUnit context keys unit = do
  component <- readComponent unit (contextCompiler context)
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
                                        | (identifier, identity) <- dependencies]]
      buildKey = shaHex (BL.toStrict (encode (object inputFields)))
  pluginHash <- digestFile (contextPluginLibrary context)
  let exporter = object ["pluginUnit" .= contextPluginUnit context,
                         "pluginDb" .= contextPluginDb context,
                         "pluginHash" .= pluginHash,
                         "driverHash" .= contextDriverHash context,
                         "options" .= (["post-tidy", "unit-qualified", "source-notes",
                                         "-g", "-dynamic", "-dcore-lint"] :: [String])]
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
    hit <- if cached then readBundle destination (unitId unit) buildKey exportKey buildInputs expected
           else pure Nothing
    case hit of
      Just bundle -> pure bundle
      Nothing -> do
        when cached (removeFile destination)
        freshExport context component unit buildKey exportKey buildInputs expected destination

freshExport :: ExportContext -> Component -> Unit -> String -> String -> Value ->
               [String] -> FilePath -> IO Bundle
freshExport context component unit buildKey exportKey buildInputs expected destination = do
  let localRoot = contextNative context </> "cache/thc/staging"
  createDirectoryIfMissing True localRoot
  (staging, handle) <- openTempFile localRoot "export-"
  hClose handle
  removeFile staging
  createDirectory staging
  let cleanup = do exists <- doesDirectoryExist staging
                   when exists (removePathForcibly staging)
  (do
    let objects = staging </> "ghc"
        core = staging </> "core"
    createDirectoryIfMissing True objects
    let arguments = ["--make", "-no-link"] ++ componentArguments component ++
          ["-outputdir", objects, "-odir", objects, "-hidir", objects,
           "-hiedir", objects </> "hie", "-stubdir", objects,
           "-package-db", contextPluginDb context, "-plugin-package-id", contextPluginUnit context,
           "-fplugin=THC.Plugin", "-fplugin-opt=THC.Plugin:" ++ core,
           "-fplugin-opt=THC.Plugin:post-tidy", "-fplugin-opt=THC.Plugin:unit-qualified",
           "-fplugin-opt=THC.Plugin:source-notes", "-g", "-dynamic", "-fforce-recomp", "-dcore-lint"] ++
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
    let sorted = sortOn fst checked
        actual = map fst sorted
    require (length actual == length (nub actual) && actual == expected)
      ("Core module inventory differs from Cabal build-info for " ++ unitId unit ++ ": " ++ show actual)
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

readBundle :: FilePath -> String -> String -> String -> Value -> [String] -> IO (Maybe Bundle)
readBundle path unit buildKey exportKey buildInputs expected = do
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
        storedInputs == buildInputs &&
        length names == length modules && length paths == length modules &&
        length names == length (nub names) && sort names == expected &&
        sort (map fst entries) == sort ("manifest.json" : inputPath : paths) &&
        all (== Just True) (map validModule modules))
      then Just (Bundle path (shaHex bytes) modules buildKey)
      else Nothing

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

readComponent :: Unit -> String -> IO Component
readComponent unit compilerId = do
  location <- field (unitValue unit) "build-info"
  info <- readJson location
  compiler <- field info "compiler" :: IO Value
  flavour <- field compiler "flavour"
  actualCompilerId <- field compiler "compiler-id"
  require (flavour == ("ghc" :: String) && actualCompilerId == compilerId)
    ("unsupported compiler in build-info for " ++ unitId unit)
  compilerPath <- field compiler "path"
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
  sources <- sourcePaths value arguments
  pure (Component value compilerPath arguments sources)

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
