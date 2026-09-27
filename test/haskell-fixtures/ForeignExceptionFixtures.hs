-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module ForeignExceptionFixtures (prepareForeignExceptions) where
import Control.Monad (forM, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.List (sort)
import qualified Data.Map.Strict as Map
import FixtureSupport hiding (run)
import InstalledCoreFixtures
import System.Directory (createDirectoryIfMissing, listDirectory, doesFileExist, removeFile)
import System.Exit (die)
import System.Environment (lookupEnv)
import System.FilePath ((</>), takeExtension)

prepareForeignExceptions :: FilePath -> IO ()
prepareForeignExceptions root = do
  let directory = "build/foreign-exceptions"
      source = "compiler/test-fixtures/ForeignExceptionAudit.hs"
      entries = ["caught", "rethrowNow", "rethrowLater", "cleanup", "metadata", "lazyOrdinary", "ordinary", "displayIsInert", "parseCleanup", "hostMetadata", "hostMetadataCleanup", "hostCatch"]
      run label env program args = runLogged 600 root (directory </> "logs") label env program args
  createDirectoryIfMissing True (root </> directory)
  let manifestPath = root </> directory </> "manifest.json"
  present <- doesFileExist manifestPath
  if present then removeFile manifestPath else pure ()
  plugin <- run "plugin" [] "compiler/build.sh" []
  configuredSource <- lookupEnv "THC_FOREIGN_EXCEPTION_GHC_SOURCE" >>= maybe
    (die "Set THC_FOREIGN_EXCEPTION_GHC_SOURCE to the configured original GHC source tree") pure
  installed <- prepareInstalledCoreWithForeign root directory configuredSource
  support <- run "runtime-export"
    [("THC_CORE_OUT", root </> directory </> "runtime-core"), ("THC_GHC_OUT", root </> directory </> "runtime-ghc")]
    "compiler/export.sh" ["-iruntime", "-fplugin-opt=THC.Plugin:post-tidy", "runtime/THC/Exception.hs", "examples/THC/Polyglot.hs"]
  supportNames <- sort . filter (\name -> takeExtension name == ".json" && name /= "THC.InterfaceClosure.json") <$>
    listDirectory (root </> directory </> "runtime-core")
  let supportModules = map ((directory </> "runtime-core") </>) supportNames
  stages <- forM ["pre", "post"] $ \stage -> do
    let output = directory </> stage </> "core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
    exported <- run (stage ++ "-export")
      [("THC_CORE_OUT", root </> output), ("THC_GHC_OUT", root </> directory </> stage </> "ghc")]
      "compiler/export.sh" (["-iruntime"] ++ options ++ [source])
    names <- sort . filter (\name -> takeExtension name == ".json" && name /= "THC.InterfaceClosure.json") <$>
      listDirectory (root </> output)
    let modules = map (output </>) (filter (== "ForeignExceptionAudit.json") names) ++ supportModules
    audits <- forM entries $ \entry ->
      run (stage ++ "-audit-" ++ entry) [] "python3"
        (["scripts/audit-core.py", "--package-manifest", fixturePackages installed, "--entry", "main:ForeignExceptionAudit." ++ entry,
          "--output", directory </> stage </> entry ++ "-audit.json"] ++ modules)
    pure (stage, modules, exported : audits)
  let native = directory </> "native"
      common = ["--make", "-O2", "-dynamic", "-fforce-recomp", "-iruntime", "-odir", native, "-hidir", native]
  createDirectoryIfMissing True (root </> native)
  built <- run "native-build" [] (fixtureGhc installed)
    (common ++ ["compiler/test-fixtures/ForeignExceptionNative.hs", "runtime/exception.c", "-o", native </> "oracle"])
  oracle <- run "native-oracle" [] (root </> native </> "oracle") []
  unless (BS.unpack (commandStdout oracle) == "(42,77,99,1)\n") (die "Native exception compatibility control changed")
  safe <- run "safe-client" [] (fixtureGhc installed)
    (common ++ ["-fno-code", "compiler/test-fixtures/ForeignExceptionSafe.hs"])
  rejected <- runLoggedExpect 1 600 root (directory </> "logs") "unsafe-import-rejected" []
    (fixtureGhc installed) (common ++ ["-fno-code", "compiler/test-fixtures/ForeignExceptionUnsafeImport.hs"])
  unless ("Can't be safely imported" `BS.isInfixOf` commandStderr rejected)
    (die "Unsafe internal import failed for an unrelated reason")
  scripts <- listDirectory (root </> "scripts")
  compiler <- listDirectory (root </> "compiler/THC")
  let sources = ["examples/THC/Polyglot.hs", "runtime/THC/Exception.hs", "runtime/THC/Internal/Exception.hs", "runtime/exception.c", source,
        "compiler/test-fixtures/ForeignExceptionNative.hs", "compiler/test-fixtures/ForeignExceptionSafe.hs",
        "compiler/test-fixtures/ForeignExceptionUnsafeImport.hs", "test/haskell-fixtures/ForeignExceptionFixtures.hs",
        "test/haskell-fixtures/InstalledCoreFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "test/haskell-fixtures/Main.hs", "src/THC/Driver/RuntimeShim.hs", "thc.cabal",
        "scripts/audit-core.py", "scripts/core-capabilities.json", "compiler/target-layout.c",
        "compiler/export.sh", "compiler/build.sh", "compiler/toolchain.sh", "compiler/plugin.py"] ++
        ["compiler/THC" </> file | file <- compiler, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, take 5 file == "core_", takeExtension file == ".py"]
      commands = fixtureCommands installed ++ [plugin, support] ++ concat [runs | (_, _, runs) <- stages] ++ [built, oracle, safe, rejected]
      outputs = fixtureArtifacts installed ++ concatMap commandArtifacts commands ++
        concat [modules ++ [directory </> stage </> "core/THC.InterfaceClosure.json"] ++
          [directory </> stage </> entry ++ "-audit.json" | entry <- entries] | (stage, modules, _) <- stages] ++
        [native </> "oracle"]
  inputs <- hashes root (sort sources)
  artifacts <- hashes root (sort outputs)
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "packageManifest" .= fixturePackages installed, "stages" .= Map.fromList [(stage, modules) | (stage, modules, _) <- stages],
     "inputHashes" .= inputs, "artifactHashes" .= artifacts, "commands" .= map commandRecord commands,
     "nativeControl" .= ("public API compatibility; foreign application exceptions require a polyglot host" :: String)]
  putStrLn "Prepared genuine foreign-exception Core, exact ABI audits, native API controls, and Safe-Haskell boundaries"
