-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- An acquisition-only view of genuine recompiled interfaces. Native compilation
-- always retains the caller's compiler, package database and installed libraries.
module THC.Driver.InstalledForeign
  ( ForeignCompiler(..), prepareForeignInterfaces, missingForeignProof, createView, viewContext ) where

import Control.Exception (bracket, bracketOnError)
import Control.Monad (filterM, forM, forM_, unless)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (Value(..), FromJSON, Result(..), fromJSON, eitherDecodeStrict', encode, object, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.List (isPrefixOf, nub, sort)
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Distribution.Compiler (CompilerFlavor(GHC))
import Distribution.InstalledPackageInfo (parseInstalledPackageInfo, showInstalledPackageInfo)
import qualified Distribution.Types.InstalledPackageInfo as Package
import Distribution.PackageDescription (library, libBuildInfo, cppOptions, hcOptions,
  defaultLanguage, defaultExtensions, includeDirs, hsSourceDirs)
import Distribution.Pretty (prettyShow)
import Distribution.Simple.Configure (getPersistBuildConfig)
import qualified Distribution.Simple.Compiler as Compiler
import Distribution.Simple.LocalBuildInfo (localPkgDescr, compiler, hostPlatform, buildDir,
  allComponentsInBuildOrder, componentPackageDeps, componentUnitId)
import Distribution.Utils.Path (getSymbolicPath, makeSymbolicPath)
import GHC.Fingerprint (getFileHash)
import Numeric (showHex)
import System.Directory
import System.Environment (getEnvironment)
import System.Exit (ExitCode(..))
import System.FilePath
import System.IO (hClose, openTempFile, SeekMode(AbsoluteSeek))
import System.IO.Error (tryIOError)
import qualified System.Info as Host
import qualified System.Posix.IO as Posix
import System.Process (proc, CreateProcess(..), readCreateProcessWithExitCode)
import THC.Driver.Installed

-- The published plugin library and the actual Cabal registration are both
-- checked: -plugin-package-id loads the latter, not an arbitrary copied .so.
data ForeignCompiler = ForeignCompiler
  { foreignGhc :: FilePath, foreignPluginDb :: FilePath, foreignPluginUnit :: String
  , foreignPluginLibrary :: FilePath, foreignRegisteredLibrary :: FilePath
  , foreignDriverHash :: String }

boundModule, posixModule :: String
boundModule = "GHC.Internal.Conc.Bound"
posixModule = "GHC.Internal.System.Posix.Internals"

-- Presence is not permission to replace bad evidence. The helper has already
-- checked actual annotations against the retained Core/foreign products.
missingForeignProof :: String -> Value -> Either String Bool
missingForeignProof name core
  | name == boundModule = case (member "staticForeignExports" core, member "staticForeignExportRegistration" core) of
      (Nothing, Nothing) -> Right True
      (Just _, Just proof) -> classified proof
      _ -> Left "incomplete static foreign-export evidence"
  | name == posixModule = maybe (Right True) classified (member "staticForeignImportStubs" core)
  | otherwise = Left "module is outside the installed foreign producer profile"
  where
    classified proof
      | member "status" proof == Just (String "verified") = Right False
      | otherwise = Left "present foreign provenance is not verified; refusing regeneration"

prepareForeignInterfaces :: ForeignCompiler -> FilePath -> FilePath -> InstalledContext ->
                            [InstalledUnit] -> IO InstalledContext
prepareForeignInterfaces producer cache source context registrations = do
  let candidates = [u | u <- registrations, all (`elem` map fst (installedInterfaces u)) [boundModule, posixModule]]
  case candidates of
    [] -> pure context
    [unit] -> do
      original <- forM [boundModule, posixModule] $ \name -> do
        path <- maybe (fail "missing original foreign interface") pure (lookup name (installedInterfaces unit))
        before <- hashFile path
        core <- readCore context unit name path
        after <- hashFile path
        check (before == after) "installed interface changed while examining foreign provenance"
        missing <- either fail pure (missingForeignProof name core)
        pure (name, core, missing, (path, before))
      let needed = [(name, core) | (name, core, True, _) <- original]
          observed = [observation | (_, _, _, observation) <- original]
      if null needed then pure context else prepare unit needed observed
    _ -> fail "multiple installed units contain the original foreign modules"
  where
    prepare unit needed originalFiles = do
      root <- canonicalizePath source
      config <- configuredRecipe producer context unit root
      let requestedCache = cache </> "installed-foreign/v1"
      createDirectoryIfMissing True requestedCache
      cacheRoot <- canonicalizePath requestedCache
      withLock (cacheRoot </> ".lock") $ do
        before <- inputs config unit
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
          let selected = viewContext context view
          validateView selected unit needed
          pure selected
        selected <- case hit of
          Right value -> pure value
          Left _ -> freshDirectory cacheRoot $ \destination -> do
            forM_ needed $ \(name, _) -> compileOriginal producer config destination name
            view <- createView context unit destination (map fst needed)
            let selected = viewContext context view
            validateView selected unit needed
            after <- inputs config unit
            check (after == before) "GHC source/configuration/interfaces changed during annotation compilation"
            (files, links) <- outputInventory destination
            let receipt = object ["inputs" .= before, "view" .= view, "files" .= files, "links" .= links]
            atomicJson index receipt
            pure selected
        after <- inputs config unit
        check (after == before) "GHC source/configuration/interfaces changed during annotation acquisition"
        pure selected
    inputs config unit = do
      -- Re-enumerate, not only re-hash the old list: an added include or boot
      -- interface can alter compiler resolution during a running compilation.
      files <- recipeFiles config
      observed <- fileInventory files
      forM_ (installedInterfaces unit) $ \(name, installed) -> do
        forM_ ["hi", "dyn_hi"] $ \suffix -> do
          let original = recipeRoot config </> "_build/stage1/libraries/ghc-internal/build" </> modulePath name <.> suffix
          check (lookup (replaceExtension installed suffix) observed == lookup original observed)
            ("selected GHC and source interfaces changed: " ++ name)
      -- Includes each complete retained payload in the selected registered
      -- dependency closure, not ABI/mtime summaries or a GHC binary hash.
      probe <- probeInstalled context unit
      global <- command (installedPackageTool context)
        ["--global", "--no-user-package-db", "--expand-pkgroot", "dump"] Nothing
      pure $ object ["schema" .= (1 :: Int), "recipe" .= recipeIdentity config,
        "files" .= observed, "installed" .= probe, "globalRegistrations" .= global,
        "driver" .= foreignDriverHash producer]

-- This first recipe deliberately supports a configured native Linux stage1
-- tree, not an arbitrary source tarball or a guessed installed-GHC source path.
data Recipe = Recipe { recipeRoot :: FilePath, recipeArguments :: [String]
                     , recipeFiles :: IO [FilePath], recipeIdentity :: Value }

configuredRecipe :: ForeignCompiler -> InstalledContext -> InstalledUnit -> FilePath -> IO Recipe
configuredRecipe producer context unit root = do
  check (null (installedDatabases context)) "foreign regeneration requires the selected global package database"
  version <- command (foreignGhc producer) ["--numeric-version"] Nothing
  check (words version == ["9.14.1"]) "--ghc-source requires selected GHC 9.14.1"
  let stage = root </> "_build/stage1"
      packageRoot = root </> "libraries/ghc-internal"
      configured = stage </> "libraries/ghc-internal"
      built = configured </> "build"
      autogen = built </> "autogen"
      src = packageRoot </> "src"
  lbi <- getPersistBuildConfig Nothing (makeSymbolicPath configured)
  check (prettyShow (Compiler.compilerId (compiler lbi)) == "ghc-9.14.1") "GHC source configuration compiler differs"
  let platform = prettyShow (hostPlatform lbi)
  check (platform `elem` ["x86_64-linux", "aarch64-linux"] &&
         platform == Host.arch ++ "-" ++ Host.os &&
         member "platform" (installedCompiler context) == Just (String (Text.pack platform)))
    "--ghc-source supports matching native x86_64/aarch64 Linux stage1 builds only"
  actualBuild <- canonicalizePath (getSymbolicPath (buildDir lbi))
  expectedBuild <- canonicalizePath built
  check (actualBuild == expectedBuild) "GHC setup-config belongs to a different build tree"
  info <- maybe (fail "GHC source configuration has no ghc-internal library") (pure . libBuildInfo)
    (library (localPkgDescr lbi))
  component <- case allComponentsInBuildOrder lbi of
    [value] -> pure value
    _ -> fail "unexpected GHC source component configuration"
  check (prettyShow (componentUnitId component) == registeredId unit &&
         sort (map (prettyShow . fst) (componentPackageDeps component)) == sort (installedDepends unit))
    "GHC source configuration unit/dependencies differ from selected installation"
  check (map getSymbolicPath (hsSourceDirs info) == ["src"] &&
         map getSymbolicPath (includeDirs info) == ["include"])
    "unsupported GHC source/include directory configuration"
  -- Do not execute hidden arbitrary hooks/plugins from a setup-config. This is
  -- the known library option profile; conditional CPP comes from Cabal itself.
  check (hcOptions GHC info == ["-this-unit-id", "ghc-internal", "-Wcompat", "-Wnoncanonical-monad-instances"] &&
         all ("-D" `isPrefixOf`) (cppOptions info)) "unsupported ghc-internal compiler option profile"
  forM_ (installedInterfaces unit) $ \(name, installed) -> do
    let original = built </> modulePath name <.> "dyn_hi"
    left <- hashFile installed
    right <- hashFile original
    check (left == right) ("--ghc-source interfaces do not match selected GHC: " ++ name)
  let includeRoots = [root </> "rts/include", stage </> "rts/build/include", built,
                      built </> "include", packageRoot </> "include"]
      roots = [built, autogen, src]
      macros = autogen </> "cabal_macros.h"
  forM_ [boundModule, posixModule] $ \name -> do
    let original = built </> modulePath name <.> "dyn_hi"
        sourceFile = src </> modulePath name <.> "hs"
    description <- command (foreignGhc producer) ["--show-iface", original] Nothing
    digest <- show <$> getFileHash sourceFile
    let fingerprints = [value | line <- lines description, ["src", "hash:", value] <- [words line]]
    check (fingerprints == [digest])
      ("--ghc-source original source does not match retained self-recomp metadata: " ++ name)
  let baseFiles = [configured </> "setup-config", packageRoot </> "ghc-internal.cabal", macros,
                   root </> "mk/config.mk", stage </> "lib/settings", installedLibdir context </> "settings",
                   src </> modulePath boundModule <.> "hs", src </> modulePath posixModule <.> "hs",
                   foreignPluginLibrary producer, foreignRegisteredLibrary producer, installedHelper context] ++
                  [replaceExtension path suffix | (_, path) <- installedInterfaces unit, suffix <- ["hi", "dyn_hi"]]
      args = ["-c", "-fforce-recomp", "-O2", "-static", "-dynamic-too", "-fsplit-sections",
              "-hide-all-packages", "-no-user-package-db", "-package-env", "-",
              "-this-package-name", "ghc-internal", "-i"] ++
        concatMap (\dep -> ["-package-id", dep]) (installedDepends unit) ++
        hcOptions GHC info ++ maybe [] (\lang -> ["-X" ++ prettyShow lang]) (defaultLanguage info) ++
        map (("-X" ++) . prettyShow) (defaultExtensions info) ++
        map ("-i" ++) roots ++ map ("-I" ++) includeRoots ++
        map ("-optP" ++) (cppOptions info) ++ ["-optP-include", "-optP" ++ macros,
        "-fwrite-if-simplified-core", "-dcore-lint", "-package-db", foreignPluginDb producer,
        "-plugin-package-id", foreignPluginUnit producer, "-fplugin=THC.Plugin",
        "-fplugin-opt=THC.Plugin:post-tidy", "-fplugin-opt=THC.Plugin:unit-qualified"]
  published <- hashFile (foreignPluginLibrary producer)
  registered <- hashFile (foreignRegisteredLibrary producer)
  check (published == registered) "published and registered THC plugin libraries differ"
  pluginDescription <- command (installedPackageTool context)
    ["--global", "--no-user-package-db", "--expand-pkgroot", "--package-db", foreignPluginDb producer,
     "--ipid", "describe", foreignPluginUnit producer] Nothing
  plugin <- parseRegistration pluginDescription
  libraryMatches <- filterM doesFileExist
    [directory </> "lib" ++ name ++ "-ghc9.14.1.so"
    | directory <- Package.libraryDynDirs plugin, name <- Package.hsLibraries plugin]
  check (foreignRegisteredLibrary producer `elem` libraryMatches && length libraryMatches == 1)
    "THC plugin registration does not resolve to the recorded shared library"
  infoOutput <- command (foreignGhc producer) ["--info"] Nothing
  let allFiles = do
        includes <- concat <$> mapM (treeFiles (\p -> takeExtension p `elem` [".h", ".hpp"])) includeRoots
        interfaces <- treeFiles (\p -> takeExtension p `elem` [".hi", ".dyn_hi", ".hi-boot", ".dyn_hi-boot"]) built
        pluginFiles <- treeFiles (\p -> takeExtension p == ".conf" || takeFileName p == "package.cache")
          (foreignPluginDb producer)
        pure (sort (nub (baseFiles ++ includes ++ interfaces ++ pluginFiles)))
  pure (Recipe root args allFiles $ object
    ["root" .= root, "ghc" .= foreignGhc producer, "ghcInfo" .= infoOutput,
     "arguments" .= args, "pluginUnit" .= foreignPluginUnit producer, "pluginRegistration" .= pluginDescription])

compileOriginal :: ForeignCompiler -> Recipe -> FilePath -> String -> IO ()
compileOriginal producer recipe destination name = do
  let stem = destination </> "interfaces" </> modulePath name
      scratch = destination </> "compile" </> name
      options = if name == boundModule then ["foreign-export-associations", "foreign-export-registration"]
                else ["foreign-import-provenance"]
  createDirectoryIfMissing True (takeDirectory stem)
  createDirectoryIfMissing True scratch
  -- The output directory must be the plugin's first option.
  let arguments = ["-fplugin-opt=THC.Plugin:" ++ scratch] ++ recipeArguments recipe ++
        map ("-fplugin-opt=THC.Plugin:" ++) options ++
        ["-odir", scratch, "-stubdir", scratch, "-tmpdir", scratch, "-dumpdir", scratch,
         "-ohi", stem <.> "hi", "-dynohi", stem <.> "dyn_hi",
         "-o", stem <.> "o", "-dyno", stem <.> "dyn_o",
         recipeRoot recipe </> "libraries/ghc-internal/src" </> modulePath name <.> "hs"]
  _ <- command (foreignGhc producer) arguments (Just (recipeRoot recipe))
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
    unless (name == "package.conf.d") $ do
      let original = installedLibdir context </> name
      directory <- doesDirectoryExist original
      (if directory then createDirectoryLink else createFileLink) original (libdir </> name)
  forM_ (installedInterfaces unit) $ \(name, original) -> do
    let target = interfaces </> modulePath name <.> "dyn_hi"
        actual = if name `elem` names then destination </> "interfaces" </> modulePath name <.> "dyn_hi" else original
    createDirectoryIfMissing True (takeDirectory target)
    createFileLink actual target
  dumped <- command (installedPackageTool context) ["--global", "--no-user-package-db", "--expand-pkgroot", "dump"] Nothing
  records <- mapM parseRegistration (splitRegistrations (lines dumped))
  check (length [() | record <- records, prettyShow (Package.installedUnitId record) == registeredId unit] == 1)
    "selected package database lost ghc-internal"
  forM_ (zip [(0 :: Int)..] records) $ \(index, original) -> do
    let record = if prettyShow (Package.installedUnitId original) == registeredId unit
                 then original { Package.importDirs = [interfaces] } else original
    writeFile (db </> show index <.> "conf") (showInstalledPackageInfo record)
  _ <- command (installedPackageTool context) ["--global-package-db", db, "--global", "recache"] Nothing
  let wrapper = view </> "ghc-pkg"
  writeFile wrapper ("#!/bin/sh\nexec " ++ shellQuote (installedPackageTool context) ++
    " --global-package-db " ++ shellQuote db ++ " \"$@\"\n")
  permissions <- getPermissions wrapper
  setPermissions wrapper permissions { executable = True }
  pure view

viewContext :: InstalledContext -> FilePath -> InstalledContext
viewContext context view = context { installedLibdir = view </> "lib",
  installedGlobalDb = view </> "lib/package.conf.d", installedPackageTool = view </> "ghc-pkg" }

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
  bytes <- command (installedHelper context) (helperCommand context unit (name, path)) Nothing
  response <- either fail pure (eitherDecodeStrict' (Text.encodeUtf8 (Text.pack bytes)))
  check (member "status" response == Just (String "loaded"))
    "--ghc-source requires complete installed interfaces before regeneration"
  field response "core"

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
  check (status == ExitSuccess) ("installed foreign command failed: " ++ program ++ "\n" ++ diagnostic)
  pure output

freshDirectory :: FilePath -> (FilePath -> IO a) -> IO a
freshDirectory parent action = bracketOnError create removePathForcibly action
  where create = do
          (path, handle) <- openTempFile parent "producer-"
          hClose handle
          removeFile path
          createDirectory path
          pure path

atomicJson :: FilePath -> Value -> IO ()
atomicJson path value = do
  (temporary, handle) <- openTempFile (takeDirectory path) "receipt-"
  hClose handle
  BL.writeFile temporary (encode value)
  renameFile temporary path

withLock :: FilePath -> IO a -> IO a
withLock path action = bracket (Posix.openFd path Posix.ReadWrite
  (Posix.defaultFileFlags {Posix.creat = Just 0o600})) Posix.closeFd $ \descriptor -> do
    Posix.waitToSetLock descriptor (Posix.WriteLock, AbsoluteSeek, 0, 0)
    action
