-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

module THC.Driver.Project (runProject) where

import Control.Exception (bracket, finally)
import Control.Monad (filterM, forM, unless, when)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (FromJSON, Value(..), eitherDecodeStrict', encode, object, (.=))
import qualified Data.Aeson as Aeson
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.List (isSuffixOf, nub, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import Numeric (showHex)
import System.Directory (canonicalizePath, createDirectory, createDirectoryIfMissing,
                         doesDirectoryExist, doesFileExist, listDirectory, makeAbsolute,
                         removeFile, removePathForcibly, renameDirectory, renameFile)
import System.Exit (ExitCode(..))
import System.FilePath ((</>), isAbsolute, makeRelative, takeDirectory, takeExtension,
                        takeFileName, joinPath, replaceExtension)
import System.IO (SeekMode(AbsoluteSeek), hClose, openTempFile, stderr)
import qualified System.Posix.IO as Posix
import System.Process (CreateProcess(..), StdStream(..), createProcess, proc, waitForProcess)
import THC.Driver.Cabal (PlanOptions(..))
import THC.Driver.Run (RunOptions(..))

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
  , componentBuildInfo :: FilePath
  }

data Export = Export { exportName :: String, exportPath :: FilePath }

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
      selector = if ':' `elem` executable then executable else "exe:" ++ executable
      cabalArgs = ["build", selector, "--enable-build-info", "--project-file", "cabal.project",
                   "--builddir", native] ++
                  maybe [] (\path -> ["--with-compiler", path]) (ghcPath flags) ++
                  maybe [] (\path -> ["--with-hc-pkg", path]) (ghcPkgPath flags)
  createDirectoryIfMissing True output
  withProjectLock output $
    runBuiltProject project thcRoot runtime output native executable cabalArgs
                    pluginDb pluginUnit pluginLibrary

runBuiltProject :: FilePath -> FilePath -> FilePath -> FilePath -> FilePath ->
                   String -> [String] -> FilePath -> String -> FilePath -> IO ()
runBuiltProject project thcRoot runtime output native executable cabalArgs
                pluginDb pluginUnit pluginLibrary = do
  runCommand True "cabal" cabalArgs project
  plan <- readJson (native </> "cache/plan.json")
  cabalVersion <- field plan "cabal-version"
  compilerId <- field plan "compiler-id"
  require (take 5 cabalVersion == "3.16." && compilerId == "ghc-9.14.1")
    "THC project run requires cabal-install 3.16 and GHC 9.14.1"
  records <- field plan "install-plan" :: IO [Value]
  units <- mapM readUnit records
  let byId = Map.fromList [(unitId unit, unit) | unit <- units]
  require (Map.size byId == length units) "Cabal plan has duplicate unit IDs"
  selected <- selectExecutable executable units
  closure <- dependencyClosure byId (unitId selected)
  described <- forM closure $ \unit -> do
    exports <- if unitLocal unit
      then exportUnit unit compilerId pluginDb pluginUnit pluginLibrary output
      else pure []
    modules <- forM exports $ \export -> do
      digest <- digestFile (exportPath export)
      pure (object ["name" .= exportName export,
                    "boundary" .= ("optimized-Core-after-Tidy-before-CorePrep" :: String),
                    "path" .= makeRelative output (exportPath export), "sha256" .= digest])
    pure (object ["id" .= unitId unit, "depends" .= unitDepends unit,
                  "modules" .= modules], exports)
  let manifest = output </> "packages.json"
      entry = unitId selected ++ ":Main.main"
      audit = output </> "audit.json"
      files = [exportPath export | (_, exports) <- described, export <- exports]
  require (not (null files)) "selected Cabal executable exported no Core"
  atomicJson manifest (object ["format" .= ("thc-core-packages" :: String),
                               "schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
                               "units" .= map fst described])
  -- The package-aware audit/runtime entrypoints consume this manifest once the
  -- corresponding cross-unit loader lands. The legacy file-list path remains
  -- strict for this independently testable driver slice.
  runCommand True "python3" ([thcRoot </> "scripts/audit-core.py", "--entry", entry,
                              "--io-main", "--output", audit] ++ files) thcRoot
  runCommand False runtime ["--run-io", comma files, entry] thcRoot

-- Cabal locks its own build tree; this also keeps the THC cache and the
-- published package manifest coherent for concurrent runs of one project.
withProjectLock :: FilePath -> IO a -> IO a
withProjectLock output action =
  bracket (Posix.openFd (output </> ".lock") Posix.ReadWrite
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

exportUnit :: Unit -> String -> FilePath -> String -> FilePath -> FilePath -> IO [Export]
exportUnit unit compilerId pluginDb pluginUnit pluginLibrary output = do
  component <- readComponent unit compilerId
  dist <- field (unitValue unit) "dist-dir"
  products <- filter nativeProduct <$> recursiveFiles dist
  require (not (null products)) ("native Cabal build has no Haskell artifacts for " ++ unitId unit)
  inputs <- mapM (\(name, path) -> do hash <- digestFile path; pure (name, hash))
    ([ ("source:" ++ path, path) | (_, path) <- componentSources component ] ++
     [ ("native:" ++ path, path) | path <- sort products ])
  buildInfoHash <- digestFile (componentBuildInfo component)
  pluginHash <- digestFile pluginLibrary
  let key = shaHex (BL.toStrict (encode (unitValue unit, buildInfoHash, pluginHash, inputs)))
      unitRoot = output </> "core/units" </> shaHex (BL.toStrict (encode (unitId unit)))
      destination = unitRoot </> key
      cache = destination </> "cache.json"
  expected <- expectedModuleNames (componentValue component)
  cached <- doesFileExist cache
  if cached then do
    previous <- readJson cache
    storedUnit <- optionalField previous "unit" ("" :: String)
    storedKey <- optionalField previous "key" ("" :: String)
    modules <- optionalField previous "modules" ([] :: [Value])
    valid <- and <$> mapM (validCacheEntry destination) modules
    names <- mapM (\value -> field value "name") modules
    if storedUnit == unitId unit && storedKey == key && valid && sort names == expected
      then mapM (cacheExport destination) modules
      else do removePathForcibly destination; freshExport component unit pluginDb pluginUnit unitRoot destination
  else do
    incomplete <- doesDirectoryExist destination
    when incomplete (removePathForcibly destination)
    freshExport component unit pluginDb pluginUnit unitRoot destination

freshExport :: Component -> Unit -> FilePath -> String -> FilePath -> FilePath -> IO [Export]
freshExport component unit pluginDb pluginUnit unitRoot destination = do
  createDirectoryIfMissing True unitRoot
  (staging, handle) <- openTempFile unitRoot "export-"
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
           "-package-db", pluginDb, "-plugin-package-id", pluginUnit,
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
      boundary <- field value "boundary" :: IO String
      name <- field value "module"
      require (foundUnit == unitId unit &&
               boundary == "optimized-Core-after-Tidy-before-CorePrep")
        ("Core artifact has wrong unit or boundary: " ++ path)
      pure (name, path)
    expected <- expectedModuleNames (componentValue component)
    let actual = sort (map fst checked)
    require (length actual == length (nub actual) && actual == expected)
      ("Core module inventory differs from Cabal build-info for " ++ unitId unit ++ ": " ++ show actual)
    -- These are export-only GHC objects; Cabal's native products above are the
    -- durable incremental inputs. Keep just the content-addressed Core.
    removePathForcibly objects
    metadata <- forM checked $ \(name, path) -> do
      hash <- digestFile path
      pure (object ["name" .= name, "path" .= makeRelative staging path, "sha256" .= hash])
    atomicJson (staging </> "cache.json")
      (object ["unit" .= unitId unit, "key" .= takeFileName destination, "modules" .= metadata])
    renameDirectory staging destination
    mapM (cacheExport destination) metadata) `finally` cleanup

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
  pure (Component value compilerPath arguments sources location)

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

validCacheEntry :: FilePath -> Value -> IO Bool
validCacheEntry root value = do
  relative <- optionalField value "path" ("" :: String)
  expected <- optionalField value "sha256" ("" :: String)
  let path = root </> relative
  exists <- doesFileExist path
  if exists && not (null relative) then (== expected) <$> digestFile path else pure False

cacheExport :: FilePath -> Value -> IO Export
cacheExport root value = Export <$> field value "name" <*> ((root </>) <$> field value "path")

nativeProduct :: FilePath -> Bool
nativeProduct path = any (`isSuffixOf` path) [".o", ".hi", ".hie", ".dyn_o", ".dyn_hi"]

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
runCommand tool command arguments directory = do
  (_, _, _, process) <- createProcess (proc command arguments)
    { cwd = Just directory, std_out = if tool then UseHandle stderr else Inherit }
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

comma :: [String] -> String
comma = foldr (\item rest -> item ++ if null rest then "" else ',' : rest) ""

foldlM :: Monad m => (b -> a -> m b) -> b -> [a] -> m b
foldlM _ value [] = pure value
foldlM step value (item:rest) = step value item >>= \next -> foldlM step next rest
