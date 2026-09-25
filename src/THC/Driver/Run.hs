-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module THC.Driver.Run (RunOptions(..), runPackage) where

import Control.Monad (unless, when)
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
import System.Directory (canonicalizePath, doesFileExist, listDirectory, makeAbsolute,
                         createDirectoryIfMissing, removeFile)
import System.Environment (getEnvironment)
import System.Exit (ExitCode(ExitSuccess))
import System.FilePath
import System.IO (stderr)
import System.Process (createProcess, proc, waitForProcess, CreateProcess(..), StdStream(..))
import THC.Driver.Cabal (PlanOptions(..), configurePackage)

data RunOptions = RunOptions
  { runPlan :: PlanOptions
  , runExecutable :: String
  , runThcRoot :: FilePath
  , runRuntime :: Maybe FilePath
  , runInstalledCore :: String
  }

-- This first run slice uses the package configuration that Cabal itself
-- elaborated. Native build output is never executed; the exported GHC Core is.
runPackage :: RunOptions -> FilePath -> IO ()
runPackage opts target = do
  unless (not (null (runExecutable opts))) $ fail "run requires --exe NAME"
  unless (not (null (runThcRoot opts))) $ fail "run requires --thc-root DIR"
  unless (runInstalledCore opts == "pinned") $
    fail "--installed-core required currently requires a cabal.project directory"
  (cabalFile, lbi) <- configurePackage (runPlan opts) target
  let packageRoot = takeDirectory cabalFile
      selectedName = runExecutable opts
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
  runtime <- maybe (pure (thcRoot </> "build/install/thc/bin/thc")) makeAbsolute (runRuntime opts)
  let output = packageRoot </> dist </> "thc-run" </> selectedName
      core = output </> "core"
      objects = output </> "ghc"
  ensureFile (thcRoot </> "compiler/export.sh")
  ensureFile (thcRoot </> "scripts/audit-core.py")
  ensureFile runtime
  createDirectoryIfMissing True core
  createDirectoryIfMissing True objects
  oldCore <- filter ((== ".json") . takeExtension) <$> listDirectory core
  mapM_ (removeFile . (core </>)) oldCore
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
                    "-fplugin-opt=THC.Plugin:closure=main"] ++
                   packages ++ concatMap (\directory -> ["-i" ++ directory]) dirs ++
                   extensions ++ cpp ++ hcOptions GHC info ++
                   ["-fno-code", "-fwrite-interface", "-fwrite-if-simplified-core", source]
  checked True (thcRoot </> "compiler/export.sh") exportArgs thcRoot environment
  files <- sort . filter ((== ".json") . takeExtension) <$> listDirectory core
  let modules = [core </> file | file <- files, file /= "audit.json"]
  unless (not (null modules)) $ fail "GHC plugin exported no Core modules"
  checked True "python3" ([thcRoot </> "scripts/audit-core.py", "--entry", "main:Main.main", "--io-main",
                      "--output", output </> "audit.json"] ++ modules) thcRoot inherited
  checked False runtime ["--run-io", intercalate "," modules, "main:Main.main"] thcRoot inherited

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
