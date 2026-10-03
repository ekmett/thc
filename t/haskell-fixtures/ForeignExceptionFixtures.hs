-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (163 foreign-exceptions)
-- Purpose: Check foreign exception boxing/rethrow/cleanup and Safe Haskell API
--   boundaries, plus host metadata and polyglot storage/interop behavior.
-- Consumes: ForeignExceptionAudit/Native/Safe/UnsafeImport sources, THC runtime API
--   modules/exception.c, storage/interop sources, installed Core/native support.
-- Produces/consumed result: Runtime/pre/post CBDs, audits, native/API controls, manifest.
-- Cost and overlap: THC-owned exception and lifetime contracts are valuable. Separate
--   compile-only Safe controls and interop/storage tests; share acquired dependencies.
-- Build status: QUARANTINED. runtime-core/*.cbd is enumerated and consumed, so leftovers
--   change inputs. The ordered Make lane and dependent full-Core tag are excluded.
-- Detailed inputs/outputs: docs/fixture-inputs.log, entry 163.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : ForeignExceptionFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for foreign exception.
module ForeignExceptionFixtures (prepareForeignExceptions) where
import Control.Monad (forM, unless)
import Data.Aeson (Value(..), object, toJSON, (.=))
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString.Char8 as BS
import Data.List (sort)
import qualified Data.Map.Strict as Map
import FixtureSupport hiding (run)
import InstalledCoreFixtures
import System.Directory (createDirectoryIfMissing, listDirectory, doesFileExist, removeFile, canonicalizePath, copyFile)
import System.Exit (die)
import System.Environment (lookupEnv)
import System.FilePath ((</>), takeExtension, takeDirectory, isAbsolute)

prepareForeignExceptions :: FilePath -> IO ()
prepareForeignExceptions root = do
  let directory = "build/foreign-exceptions"
      source = "t/fixtures/compiler/ForeignExceptionAudit.hs"
      entries = ["caught", "rethrowNow", "rethrowLater", "cleanup", "metadata", "lazyOrdinary", "ordinary", "displayIsInert", "parseCleanup", "hostMetadata", "hostMetadataCleanup", "hostCatch"]
      run label env program args = runLogged 600 root (directory </> "logs") label env program args
  createDirectoryIfMissing True (root </> directory)
  let manifestPath = root </> directory </> "manifest.json"
  present <- doesFileExist manifestPath
  if present then removeFile manifestPath else pure ()
  plugin <- run "plugin" [] "bin/build-compiler.sh" []
  supplied <- lookupEnv "THC_FOREIGN_EXCEPTION_INSTALLED"
  (ghc, packages, installedArtifacts, installedCommands) <- case supplied of
    Nothing -> do
      configuredSource <- lookupEnv "THC_FOREIGN_EXCEPTION_GHC_SOURCE" >>= maybe
        (die "Set THC_FOREIGN_EXCEPTION_GHC_SOURCE to the configured original GHC source tree") pure
      installed <- prepareInstalledCoreWithForeignUnits root directory configuredSource ["bytestring", "text"]
      pure (fixtureGhc installed, fixturePackages installed, fixtureArtifacts installed, fixtureCommands installed)
    Just sourceManifest -> do
      -- Reuse only immutable installed support. Local runtime exports, strict
      -- package/Core audits and native/Safe controls below always run afresh.
      ghc <- maybe "ghc" id <$> lookupEnv "GHC"
      path <- canonicalizePath sourceManifest
      manifest <- readJson path
      units <- field manifest "units" :: IO [Value]
      let local = directory </> "installed"
          packagePath = local </> "packages.json"
          original = local </> "source-packages.json"
      createDirectoryIfMissing True (root </> local </> "bundles")
      copyFile path (root </> original)
      copied <- forM (zip [0 :: Int ..] units) $ \(index, unit) -> case unit of
        Object fields | Just (Object bundle) <- KM.lookup "bundle" fields -> do
          bundlePath <- field (Object bundle) "path"
          expected <- field (Object bundle) "sha256"
          let input = if isAbsolute bundlePath then bundlePath else takeDirectory path </> bundlePath
              output = local </> "bundles" </> show index ++ ".zip"
          digest <- hashFile input
          unless (digest == expected) (die "Supplied installed bundle hash mismatch")
          copyFile input (root </> output)
          copiedHash <- hashFile (root </> output)
          unless (copiedHash == expected) (die "Supplied installed bundle changed while copying")
          pure (Object (KM.insert "bundle" (Object (KM.insert "path" (toJSON (root </> output)) bundle)) fields), [output])
        _ -> pure (unit, [])
      case manifest of
        Object fields -> writeJson (root </> packagePath) (Object (KM.insert "units" (toJSON (map fst copied)) fields))
        _ -> die "Invalid supplied installed package manifest"
      pure (ghc, packagePath, original : packagePath : concatMap snd copied, [])
  support <- run "runtime-export"
    [("THC_CORE_OUT", root </> directory </> "runtime-core"), ("THC_GHC_OUT", root </> directory </> "runtime-ghc")]
    "bin/export-core.sh" ["-isrc/runtime", "-fplugin-opt=THC.Plugin:post-tidy", "src/runtime/THC/Exception.hs", "src/runtime/THC/Polyglot.hs",
      "t/fixtures/compiler/PolyglotStorage.hs", "t/fixtures/compiler/InteropPrimitives.hs", "src/runtime/THC/Interop.hs"]
  supportNames <- sort . filter (\name -> takeExtension name == ".cbd" && name /= "THC.InterfaceClosure.cbd") <$>
    listDirectory (root </> directory </> "runtime-core")
  let supportModules = map ((directory </> "runtime-core") </>) supportNames
      interopEntries = ["getLibrary", "readOne", "writeOne", "sumBytes", "arrayLong", "caughtRead"]
      storageEntries = ["copySlice", "view", "mutableView", "copyInto", "readByte"]
  storageAudit <- run "storage-audit" [] "python3"
    (["bin/audit-core.py", "--package-manifest", packages] ++
      concatMap (\entry -> ["--entry", "main:PolyglotStorage." ++ entry ++ "#"]) storageEntries ++
      ["--output", directory </> "storage-audit.json"] ++ supportModules)
  interopAudit <- run "interop-audit" [] "python3"
    (["bin/audit-core.py", "--package-manifest", packages] ++
      concatMap (\entry -> ["--entry", "main:InteropPrimitives." ++ entry]) interopEntries ++
      ["--output", directory </> "interop-audit.json"] ++ supportModules)
  stages <- forM ["pre", "post"] $ \stage -> do
    let output = directory </> stage </> "core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
    exported <- run (stage ++ "-export")
      [("THC_CORE_OUT", root </> output), ("THC_GHC_OUT", root </> directory </> stage </> "ghc")]
      "bin/export-core.sh" (["-isrc/runtime"] ++ options ++ [source])
    names <- sort . filter (\name -> takeExtension name == ".cbd" && name /= "THC.InterfaceClosure.cbd") <$>
      listDirectory (root </> output)
    let modules = map (output </>) (filter (== "ForeignExceptionAudit.cbd") names) ++ supportModules
    audit <- run (stage ++ "-audit") [] "python3"
      (["bin/audit-core.py", "--package-manifest", packages] ++
        concatMap (\entry -> ["--entry", "main:ForeignExceptionAudit." ++ entry]) entries ++
        ["--output", directory </> stage </> "audit.json"] ++ modules)
    pure (stage, modules, [exported, audit])
  let native = directory </> "native"
      common = ["--make", "-O2", "-dynamic", "-fforce-recomp", "-isrc/runtime", "-odir", native, "-hidir", native]
  createDirectoryIfMissing True (root </> native)
  built <- run "native-build" [] ghc
    (common ++ ["t/fixtures/compiler/ForeignExceptionNative.hs", "src/runtime/exception.c", "-o", native </> "oracle"])
  oracle <- run "native-oracle" [] (root </> native </> "oracle") []
  unless (BS.unpack (commandStdout oracle) == "(42,77,99,1)\n") (die "Native exception compatibility control changed")
  safe <- run "safe-client" [] ghc
    (common ++ ["-fno-code", "t/fixtures/compiler/ForeignExceptionSafe.hs"])
  rejected <- runLoggedExpect 1 600 root (directory </> "logs") "unsafe-import-rejected" []
    ghc (common ++ ["-fno-code", "t/fixtures/compiler/ForeignExceptionUnsafeImport.hs"])
  unless ("Can't be safely imported" `BS.isInfixOf` commandStderr rejected)
    (die "Unsafe internal import failed for an unrelated reason")
  scripts <- listDirectory (root </> "bin")
  compiler <- listDirectory (root </> "src/compiler/THC")
  codec <- listDirectory (root </> "src/cbd/THC/Compact")
  let sources = ["src/runtime/THC/Polyglot.hs", "src/runtime/THC/Internal/Polyglot.hs", "src/runtime/THC/Interop/Buffer.hs",
        "src/runtime/THC/Interop/Array.hs", "src/runtime/THC/Interop/Array/Unsafe.hs", "src/runtime/THC/Prim.hs", "src/runtime/THC/Interop.hs",
        "t/fixtures/compiler/InteropPrimitives.hs", "src/test/resources/thc/polyglot-abi.json",
        "t/fixtures/compiler/PolyglotStorage.hs", "src/runtime/THC/Exception.hs", "src/runtime/THC/Internal/Exception.hs", "src/runtime/exception.c", source,
        "t/fixtures/compiler/ForeignExceptionNative.hs", "t/fixtures/compiler/ForeignExceptionSafe.hs",
        "t/fixtures/compiler/ForeignExceptionUnsafeImport.hs", "t/haskell-fixtures/ForeignExceptionFixtures.hs",
        "t/haskell-fixtures/InstalledCoreFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
        "t/haskell-fixtures/Main.hs", "src/driver/THC/Driver/RuntimeShim.hs", "thc.cabal",
        "bin/audit-core.py", "bin/core-capabilities.json", "src/driver/cbits/target-layout.c",
        "bin/export-core.sh", "bin/build-compiler.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- compiler, takeExtension file == ".hs"] ++
        ["src/cbd/THC/Compact" </> file | file <- codec, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- scripts, take 5 file == "core_", takeExtension file == ".py"]
      commands = installedCommands ++ [plugin, support, storageAudit, interopAudit] ++ concat [runs | (_, _, runs) <- stages] ++ [built, oracle, safe, rejected]
      outputs = installedArtifacts ++ concatMap commandArtifacts commands ++
        concat [modules ++ [directory </> stage </> "core/THC.InterfaceClosure.cbd"] ++
          [directory </> stage </> "audit.json"] | (stage, modules, _) <- stages] ++
        [native </> "oracle", directory </> "storage-audit.json", directory </> "interop-audit.json"]
  inputs <- hashes root (sort sources)
  artifacts <- hashes root (sort outputs)
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "packageManifest" .= packages, "stages" .= Map.fromList [(stage, modules) | (stage, modules, _) <- stages],
     "inputHashes" .= inputs, "artifactHashes" .= artifacts, "commands" .= map commandRecord commands,
     "nativeControl" .= ("public API compatibility; foreign application exceptions require a polyglot host" :: String)]
  putStrLn "Prepared genuine foreign-exception Core, exact ABI audits, native API controls, and Safe-Haskell boundaries"
