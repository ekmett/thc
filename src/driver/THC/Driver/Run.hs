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
  ( RunOptions(..), resolveThcRoot, runtimeLaunchArguments, runtimeEntryArguments, runtimeDebugEnvironment, runResolvedPackage
  ) where

import Control.Monad (unless, when)
import Data.Char (toUpper)
import Data.List (intercalate, sort)
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
import System.Directory (canonicalizePath, doesDirectoryExist, doesFileExist, findExecutable, listDirectory, makeAbsolute,
                         createDirectoryIfMissing, removeFile)
import System.Environment (getEnvironment, getExecutablePath, lookupEnv)
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
  , runVerifyArtifacts :: Bool
  , runDapPort :: Maybe Int
  , runDapSuspend :: Bool
  , runDapWaitAttached :: Bool
  , runNativeImage :: Bool
  , runArguments :: [String]
  }

-- | Locate the source/build tree containing this executable, including when
-- launched through a symlink or from another Cabal project. An explicit root
-- supports drivers copied outside their build tree.
resolveThcRoot :: FilePath -> IO FilePath
resolveThcRoot supplied
  | not (null supplied) = do
      exists <- doesDirectoryExist supplied
      unless exists $ fail ("THC root directory does not exist: " ++ supplied)
      canonicalizePath supplied
  | otherwise = do
      executable <- canonicalizePath =<< getExecutablePath
      search executable (takeDirectory executable)
  where
    search executable directory = do
      hasPackage <- doesFileExist (directory </> "thc.cabal")
      hasCompiler <- doesFileExist (directory </> "bin/build-compiler.sh")
      if hasPackage && hasCompiler then pure directory
        else if takeDirectory directory == directory
          then fail ("Cannot locate THC source/build root from " ++ executable ++
                     "; use --thc-root DIR when the driver is installed separately")
          else search executable (takeDirectory directory)

-- | Artifact verification belongs before the entry command, never in GHC flags
-- or after the guest delimiter, and is disabled unless explicitly requested.
--
-- >>> runtimeLaunchArguments True ["--run-io", "bundle.json", "main:Main.main"] "demo" ["hello"]
-- ["--verify-artifacts","--run-io","bundle.json","main:Main.main","--","demo","hello"]
-- >>> runtimeLaunchArguments False ["--run-io", "bundle.json", "main:Main.main"] "demo" []
-- ["--run-io","bundle.json","main:Main.main","--","demo"]
runtimeLaunchArguments :: Bool -> [String] -> String -> [String] -> [String]
runtimeLaunchArguments verify entry program arguments =
  ["--verify-artifacts" | verify] ++ entry ++ ["--", program] ++ arguments

-- | Enable Graal's DAP instrument on an explicitly selected loopback port.
-- The installed Gradle launcher reads JAVA_OPTS on Unix and Windows. Only
-- validated numbers and booleans are appended; existing JVM options survive.
runtimeDebugEnvironment :: String -> RunOptions -> [(String, String)] -> IO [(String, String)]
runtimeDebugEnvironment hostOS opts inherited = case runDapPort opts of
  Nothing -> do
    unless (runDapSuspend opts && runDapWaitAttached opts) $
      fail "--dap-no-suspend and --dap-no-wait-attached require --dap-port"
    pure inherited
  Just port -> do
    unless (port >= 1 && port <= 65535) $ fail "--dap-port must be an integer from 1 to 65535"
    let boolean True = "true"
        boolean False = "false"
        settings = unwords
          [ "-Dpolyglot.dap=127.0.0.1:" ++ show port
          , "-Dpolyglot.dap.Suspend=" ++ boolean (runDapSuspend opts)
          , "-Dpolyglot.dap.WaitAttached=" ++ boolean (runDapWaitAttached opts)
          ]
        sameKey key = (if hostOS == "mingw32" then map toUpper key else key) == "JAVA_OPTS"
        existing = unwords [value | (key, value) <- inherited, sameKey key]
    pure (("JAVA_OPTS", existing ++ " " ++ settings) :
          filter (not . sameKey . fst) inherited)

-- | Loose Core modules accompany the authenticated package manifest.
runtimeEntryArguments :: [FilePath] -> FilePath -> String -> [String]
runtimeEntryArguments modules manifest entry =
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
  launchEnvironment <- runtimeDebugEnvironment os opts =<< getEnvironment
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
  oldCore <- filter ((== ".cbd") . takeExtension) <$> listDirectory core
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
                    "-fplugin-opt=THC.Plugin:foreign-export-associations",
                    "-fplugin-opt=THC.Plugin:foreign-export-registration"] ++
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
  files <- sort . filter ((== ".cbd") . takeExtension) <$> listDirectory core
  let modules = [core </> file | file <- files]
  unless (not (null modules)) $ fail "GHC plugin exported no Core modules"
  when (runVerifyArtifacts opts) $
    checked True python ([thcRoot </> "bin/audit-core.py", "--runtime", runtime, "--entry", entry, "--io-main",
                      "--package-manifest", supportManifest,
                      "--output", output </> "audit.json"] ++ modules) thcRoot inherited
  checked False runtime (runtimeLaunchArguments (runVerifyArtifacts opts)
    (runtimeEntryArguments modules supportManifest entry) selectedName (runArguments opts)) working launchEnvironment

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
