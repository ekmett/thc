-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.Driver.Run
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Cabal API and host filesystem/process services
--
-- Build a selected Cabal component and launch it with explicit guest runtime arguments.
module THC.Driver.Run
  ( RunOptions(..), FfiMode(..), parseFfiMode, runtimeLaunchArguments, runtimeIndexedEntryArguments, runResolvedPackage
  ) where

import Control.Monad (unless, when)
import Data.List (isSuffixOf, intercalate, sort)
import Distribution.Compiler (CompilerFlavor(GHC))
import Distribution.PackageDescription
import Distribution.Pretty (prettyShow)
import Distribution.Simple.Build (build)
import Distribution.Simple.BuildPaths (autogenPackageModulesDir, autogenComponentModulesDir)
import Distribution.Simple.LocalBuildInfo hiding (packageRoot)
import Distribution.Simple.PreProcess (knownSuffixHandlers)
import Distribution.Simple.Setup
import Distribution.Utils.Path (getSymbolicPath, makeSymbolicPath)
import Distribution.Verbosity (silent)
import GHC.ResponseFile (escapeArgs)
import System.Directory (canonicalizePath, doesFileExist, findExecutable, listDirectory, makeAbsolute,
                         createDirectoryIfMissing, removeFile)
import System.Environment (getEnvironment, lookupEnv)
import System.Info (os)
import System.Exit (ExitCode(ExitSuccess))
import System.FilePath
import System.IO (stderr)
import System.Process (createProcess, proc, waitForProcess, CreateProcess(..), StdStream(..))
import THC.Driver.Cabal (PlanOptions(..), configurePackage)

-- | Resolved driver inputs, runtime selection and arguments passed to the
-- guest program. Compiler/package selection lives in 'runPlan'.
data RunOptions = RunOptions
  { runPlan :: PlanOptions
  , runTarget :: String
  , runProjectDirectory :: Maybe FilePath
  , runProjectFile :: Maybe FilePath
  , runThcRoot :: FilePath
  , runRuntime :: Maybe FilePath
  , runInstalledCore :: String
  , runGhcSource :: Maybe FilePath
  , runFfiMode :: Maybe FfiMode
  , runVerifyArtifacts :: Bool
  , runArguments :: [String]
  }

-- | Explicit runtime FFI selection. Omitting it preserves launcher defaults.
data FfiMode = NativeFfi | ManagedFfi deriving (Eq, Show)

-- | Parse the two accepted, case-sensitive @--ffi@ values.
--
-- >>> parseFfiMode "native"
-- Right NativeFfi
-- >>> parseFfiMode "managed"
-- Right ManagedFfi
-- >>> parseFfiMode "auto"
-- Left "--ffi must be native or managed; got \"auto\""
parseFfiMode :: String -> Either String FfiMode
parseFfiMode "native" = Right NativeFfi
parseFfiMode "managed" = Right ManagedFfi
parseFfiMode value = Left ("--ffi must be native or managed; got " ++ show value)

-- | Runtime selection belongs before the entry command, never in GHC flags or
-- after the guest delimiter. No explicit choice leaves launcher defaults and
-- its environment/property configuration intact. Artifact verification is
-- independent of FFI selection and disabled unless explicitly requested.
--
-- >>> runtimeLaunchArguments True (Just ManagedFfi) ["--run-io", "bundle.json", "main:Main.main"] "demo" ["hello"]
-- ["--verify-artifacts","--ffi","managed","--run-io","bundle.json","main:Main.main","--","demo","hello"]
-- >>> runtimeLaunchArguments False Nothing ["--run-io", "bundle.json", "main:Main.main"] "demo" []
-- ["--run-io","bundle.json","main:Main.main","--","demo"]
runtimeLaunchArguments :: Bool -> Maybe FfiMode -> [String] -> String -> [String] -> [String]
runtimeLaunchArguments verify mode entry program arguments =
  ["--verify-artifacts" | verify] ++
  maybe [] (\selected -> ["--ffi", case selected of
    NativeFfi -> "native"
    ManagedFfi -> "managed"]) mode ++ entry ++ ["--", program] ++ arguments

-- | Explicit loose pairs retain their own ownership beside the authenticated
-- package manifest. Keep paths as separate CLI operands, before guest arguments.
runtimeIndexedEntryArguments :: [FilePath] -> FilePath -> String -> [String]
runtimeIndexedEntryArguments modules manifest entry =
  concatMap (\path -> ["--json-sidecar", path, path ++ ".idx"]) modules ++
  ["--run-io", intercalate "," (modules ++ ["@" ++ manifest]), entry]

-- | Internal simple-package backend retained for Windows after Cabal resolves
-- the public positional target. This is not a second command-line selector.
-- Native build output is never executed; the exported GHC Core is.
runResolvedPackage :: RunOptions -> FilePath -> FilePath -> (FilePath -> IO ([String], FilePath)) -> IO ()
runResolvedPackage opts working target prepareRuntime = do
  unless (runGhcSource opts == Nothing) $
    fail "the Windows simple-package backend does not support --ghc-source"
  unless (not (null (runTarget opts))) $ fail "resolved runnable component has no name"
  unless (not (null (runThcRoot opts))) $ fail "run requires --thc-root DIR"
  unless (runInstalledCore opts == "pinned") $
    fail "the Windows simple-package backend does not support --installed-core required"
  (cabalFile, lbi) <- configurePackage (runPlan opts) target
  let packageRoot = takeDirectory cabalFile
      selectedName = runTarget opts
      configured = localPkgDescr lbi
      candidates = [ (clbi, exe) | clbi <- allComponentsInBuildOrder lbi
                 , CExe exe <- [getComponent configured (componentLocalName clbi)]
                 , prettyShow (Distribution.PackageDescription.exeName exe) == selectedName ]
  (clbi, exe) <- case candidates of
    [found] -> pure found
    [] -> fail ("no enabled Cabal executable named " ++ selectedName)
    _ -> fail ("ambiguous Cabal executable named " ++ selectedName)
  unless (null (componentInternalDeps clbi) && null (componentExeDeps clbi)) $
    fail "THC run currently requires an executable without internal library or build-tool dependencies"
  let info = buildInfo exe
      dist = distDirectory (runPlan opts)
      buildCommon = (buildCommonFlags defaultBuildFlags)
        { setupVerbosity = Flag silent
        , setupWorkingDir = Flag (makeSymbolicPath packageRoot)
        , setupDistPref = Flag (makeSymbolicPath dist)
        , setupTargets = ["exe:" ++ selectedName]
        }
      buildFlags = defaultBuildFlags {buildCommonFlags = buildCommon}
  build configured lbi buildFlags knownSuffixHandlers

  roots <- mapM (canonicalizePath . (packageRoot </>) . getSymbolicPath) (hsSourceDirs info)
  let mainName = getSymbolicPath (modulePath exe)
  sources <- filterMFile doesFileExist [directory </> mainName | directory <- roots]
  source <- case sources of
    [file] -> pure file
    [] -> fail ("Cabal executable main source not found: " ++ mainName)
    _ -> fail ("Cabal executable main source is ambiguous: " ++ mainName)

  thcRoot <- canonicalizePath (runThcRoot opts)
  let windows = os == "mingw32"
      launcher = if windows then "thc.bat" else "thc"
      exporter = thcRoot </> "bin" </> if windows then "export-core.ps1" else "export-core.sh"
      hostEntrySource = thcRoot </> "src/driver/WindowsRunMain.hs"
      entry = if windows then "main:THC.WindowsRunMain.thcRunMain" else "main:Main.main"
  runtime <- maybe (pure (thcRoot </> "build/install/thc/bin" </> launcher)) makeAbsolute (runRuntime opts)
  python <- maybe (if windows then "python" else "python3") id <$> lookupEnv "THC_PYTHON"
  let output = packageRoot </> dist </> "thc-run" </> selectedName
      core = output </> "core"
      objects = output </> "ghc"
  ensureFile exporter
  when windows (ensureFile hostEntrySource)
  when (runVerifyArtifacts opts) $ ensureFile (thcRoot </> "bin/audit-core.py")
  ensureFile runtime
  createDirectoryIfMissing True core
  createDirectoryIfMissing True objects
  oldCore <- filter (\path -> takeExtension path == ".json" || ".json.idx" `isSuffixOf` path) <$> listDirectory core
  mapM_ (removeFile . (core </>)) oldCore
  (supportOptions, supportManifest) <- prepareRuntime output
  inherited <- getEnvironment
  let overrides = [("THC_CORE_OUT", core), ("THC_GHC_OUT", objects)] ++
        maybe [] (\path -> [("GHC", path)]) (ghcPath (runPlan opts)) ++
        maybe [] (\path -> [("GHC_PKG", path)]) (ghcPkgPath (runPlan opts))
      environment = overrides ++ filter (\(key, _) -> key `notElem` map fst overrides) inherited
      dirs = roots ++ [packageRoot </> getSymbolicPath (autogenPackageModulesDir lbi),
                       packageRoot </> getSymbolicPath (autogenComponentModulesDir lbi clbi)]
      extensions = maybe [] (\language -> ["-X" ++ prettyShow language]) (defaultLanguage info) ++
                   ["-X" ++ prettyShow extension | extension <- defaultExtensions info]
      cpp = if null (cppOptions info) then [] else "-cpp" : map ("-optP" ++) (cppOptions info)
      packages = concat [["-package-id", prettyShow unit] | (unit, _) <- componentPackageDeps clbi]
      -- Cabal already built the native program. Writing simplified Core keeps
      -- GHC's optimizer and plugin active under -fno-code, including source
      -- notes, without sending source filenames through its assembler.
      exportArgs = ["-hide-all-packages", "-no-user-package-db", "-package-env", "-",
                    "-fplugin-opt=THC.Plugin:closure=" ++ (if windows then "thcRunMain" else "main"),
                    "-fplugin-opt=THC.Plugin:foreign-import-provenance",
                    "-fplugin-opt=THC.Plugin:json-index"] ++
                   packages ++ concatMap (\directory -> ["-i" ++ directory]) dirs ++
                   extensions ++ cpp ++ hcOptions GHC info ++ supportOptions ++
                   ["-fno-code", "-fwrite-interface", "-fwrite-if-simplified-core", source] ++
                   [hostEntrySource | windows]
  if windows
    then do
      -- Windows PowerShell -File consumes a lone "-" and splits colon-bearing
      -- named arguments. GHC's own response-file format preserves exact tokens.
      let response = output </> "export.args"
      writeFile response (escapeArgs exportArgs)
      -- Prefer the current PowerShell host when installed. Each host retains
      -- its configured execution policy; never add a policy override.
      powershell <- maybe "powershell.exe" id <$> findExecutable "pwsh"
      checked True powershell ["-NoProfile", "-File", exporter, "@" ++ response] thcRoot environment
    else checked True exporter exportArgs thcRoot environment
  files <- sort . filter ((== ".json") . takeExtension) <$> listDirectory core
  let modules = [core </> file | file <- files, file /= "audit.json"]
  unless (not (null modules)) $ fail "GHC plugin exported no Core modules"
  mapM_ (ensureFile . (++ ".idx")) modules
  when (runVerifyArtifacts opts) $
    checked True python ([thcRoot </> "bin/audit-core.py", "--entry", entry, "--io-main",
                      "--package-manifest", supportManifest,
                      "--output", output </> "audit.json"] ++ modules) thcRoot inherited
  checked False runtime (runtimeLaunchArguments (runVerifyArtifacts opts) (runFfiMode opts)
    (runtimeIndexedEntryArguments modules supportManifest entry) selectedName (runArguments opts)) working inherited

filterMFile :: (a -> IO Bool) -> [a] -> IO [a]
filterMFile predicate items = do
  flags <- mapM predicate items
  pure [item | (item, True) <- zip items flags]

ensureFile :: FilePath -> IO ()
ensureFile path = do
  exists <- doesFileExist path
  unless exists (fail ("required THC runtime/tool not found: " ++ path))

checked :: Bool -> FilePath -> [String] -> FilePath -> [(String, String)] -> IO ()
checked tool command arguments directory environment = do
  (_, _, _, process) <- createProcess (proc command arguments)
    {cwd = Just directory, env = Just environment, std_out = if tool then UseHandle stderr else Inherit}
  result <- waitForProcess process
  when (result /= ExitSuccess) $ fail ("command failed: " ++ command ++ " (" ++ show result ++ ")")
