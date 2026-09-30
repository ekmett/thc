-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : WindowsSmokeFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for windows smoke.
module WindowsSmokeFixtures (prepareWindowsSmoke, prepareWindowsDriver, prepareWindowsBridge) where

import Control.Exception (bracket)
import Control.Monad (forM, unless)
import Data.Aeson (Value(..), eitherDecodeStrict, object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.Aeson as Aeson
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import qualified Data.Set as Set
import qualified Data.Text as Text
import Data.Time (defaultTimeLocale, formatTime, getCurrentTime)
import FixtureSupport
import System.Directory (canonicalizePath, copyFile, createDirectoryIfMissing, doesDirectoryExist, doesFileExist, findExecutable, listDirectory)
import System.Environment (lookupEnv, setEnv, unsetEnv)
import System.Exit (die)
import System.FilePath ((</>), takeDirectory, takeExtension)
import qualified System.Info as Host
import GHC.ResponseFile (escapeArgs)
import THC.Compact.Module (readModuleValue)
import THC.Driver.Project (prepareWindowsRuntime)

-- Execute the real opaque boxer/projector and its Exception dictionary. Native
-- expected values and exported Core use the same actual Cabal runtime unit.
prepareWindowsBridge :: FilePath -> IO ()
prepareWindowsBridge root = do
  unless (Host.os == "mingw32") (die "windows-bridge requires native Windows GHC")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC" >>= canonicalizePath
  let pkg = takeDirectory ghc </> "ghc-pkg.exe"
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  python <- maybe "python" id <$> lookupEnv "THC_PYTHON"
  stamp <- formatTime defaultTimeLocale "%Y%m%dT%H%M%S%q" <$> getCurrentTime
  let directory = "build/windows-bridge"
      logs = directory </> stamp
      native = logs </> "native"
      core = logs </> "core"
      source = "t/fixtures/compiler/WindowsBridgeAudit.hs"
      db = root </> "dist-newstyle/packagedb/ghc-9.14.1"
      entries = ["roundTripScalar","dictionaryRoundTripScalar","inertDisplayScalar","caughtRoundTripScalar"]
      readValue path = either die pure . eitherDecodeStrict =<< BS.readFile path
      field name (Object fields) = case KeyMap.lookup name fields of
        Just value -> case Aeson.fromJSON value of
          Aeson.Success result -> pure result
          Aeson.Error message -> die message
        Nothing -> die ("missing fixture field " ++ show name)
      field _ _ = die "expected fixture object"
  createDirectoryIfMissing True (root </> native)
  built <- runLogged 180 root logs "runtime-native" [] cabal
    ["build","lib:runtime","--offline","--disable-shared","--with-compiler=" ++ ghc,"--with-hc-pkg=" ++ pkg]
  plan <- readValue (root </> "dist-newstyle/cache/plan.json")
  units <- field "install-plan" plan :: IO [Value]
  let runtime = [value | value@(Object fields) <- units,
        KeyMap.lookup "pkg-name" fields == Just (String "thc"),
        KeyMap.lookup "component-name" fields == Just (String "lib:runtime")]
  owner <- case runtime of [value] -> field "id" value; _ -> die "no unique actual Cabal runtime unit"
  let package = ["-package-db",db,"-package-id",owner]
  compiled <- runLogged 180 root logs "native-build" [] ghc
    (["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint","-main-is","WindowsBridgeAudit",
      "-odir",root </> native,"-hidir",root </> native,source,"-o",root </> native </> "oracle.exe"] ++ package)
  observed <- runLogged 60 root logs "native-oracle" [] (root </> native </> "oracle.exe") []
  let rows = map (splitTab . BSC.unpack . BSC.dropWhileEnd (== '\r')) (BSC.lines (commandStdout observed))
  unless (length rows == 3 && all ((==5) . length) rows) (die "unexpected native bridge inventory")
  driver <- reverse . dropWhile (`elem` ("\r\n" :: String)) . reverse <$> run root [] cabal
    ["list-bin","exe:thc","--disable-shared","--with-compiler=" ++ ghc] ""
  (supportOptions, supportManifest) <- prepareWindowsRuntime root ghc pkg driver (root </> logs)
  powershell <- maybe "powershell.exe" id <$> findExecutable "pwsh"
  let response = root </> logs </> "export.args"
  writeFile response (escapeArgs (supportOptions ++ package ++ ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries] ++
    ["-fplugin-opt=THC.Plugin:foreign-import-provenance",source]))
  exported <- runLogged 180 root logs "export"
    [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",root </> logs </> "objects")]
    powershell ["-NoProfile","-File",root </> "bin/export-core.ps1","@" ++ response]
  modules <- map ((root </> core) </>) . filter ((==".cbd") . takeExtension) <$> listDirectory (root </> core)
  let linked = modules ++ ["@" ++ supportManifest]
  support <- readValue supportManifest
  selected <- field "foreignExceptionBridgeUnit" support
  unless (selected == owner) (die "native oracle and acquired runtime dictionary have different owners")
  -- Retain even a failed audit's exact acquisition and native-oracle inputs.
  writeJson (root </> directory </> "linked.json") (object ["modules" .= linked,"owner" .= owner,"logs" .= logs])
  -- The checked Windows package graph contains about 3 GiB of full Core. Allow
  -- its indexed catalogue to finish and close; scalar fixtures remain bounded
  -- separately. This is acquisition/audit time, not a compiled guest-call retry.
  audits <- forM entries $ \entry -> runLogged 300 root logs ("audit-" ++ entry) [] python
    (["bin/audit-core.py","--entry","main:WindowsBridgeAudit." ++ entry,"--package-manifest",supportManifest,
      "--output",root </> logs </> entry ++ ".audit.json"] ++ modules)
  rejected <- runLoggedExpect 1 60 root logs "audit-missing-support" [] python
    (["bin/audit-core.py","--entry","main:WindowsBridgeAudit.roundTripScalar","--output",root </> logs </> "missing-support.audit.json"] ++ modules)
  negative <- readValue (root </> logs </> "missing-support.audit.json")
  issues <- field "issues" negative :: IO [Value]
  unless (any (\value -> case value of
    Object fields -> KeyMap.lookup "detail" fields == Just (String "Interface closure lacks its exact complete provided module")
    _ -> False) issues) (die "missing-support control did not fail the provided-module contract")
  drivers <- listDirectory (root </> "src/driver/THC/Driver")
  let commands = [built,compiled,observed,exported] ++ audits ++ [rejected]
      sources = [source,"t/haskell-fixtures/WindowsSmokeFixtures.hs","t/haskell-fixtures/FixtureSupport.hs",
        "t/haskell-fixtures/Main.hs","thc.cabal","bin/export-core.ps1","src/compiler/THC/Plugin.hs",
        "src/compiler/THC/Interface.hs","src/compiler/interface/Main.hs","etc/ghc/9.14.1/windows-ghc-internal.json",
        "src/driver/cbits/target-layout.c","bin/windows-common.ps1",
        "bin/audit-core.py","bin/core-capabilities.json","bin/core_original_foreign.py",
        "bin/core_package_manifest.py","bin/core_md5_foreign.py",
        "src/runtime/THC/Internal/Exception.hs","src/runtime/THC/Exception.hs"] ++
        ["src/driver/THC/Driver" </> path | path <- drivers,takeExtension path == ".hs"]
  inputHashes <- hashes root sources
  supportUnits <- field "units" support :: IO [Value]
  supportArtifacts <- mapM (field "path") (concatMap unitArtifactReferences supportUnits)
  artifactHashes <- hashes root (modules ++ supportArtifacts ++ [native </> "oracle.exe"] ++ concatMap commandArtifacts commands ++
    [logs </> entry ++ ".audit.json" | entry <- entries] ++
    [logs </> "missing-support.audit.json",logs </> "runtime-support/packages.json"])
  writeJson (root </> directory </> "provenance.json") (object
    ["schema" .= (1::Int),"ghc" .= ("9.14.1"::String),"system" .= Host.os,"owner" .= owner,
     "modules" .= linked,"logs" .= logs,"entries" .= entries,"nativeRows" .= rows,
     "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,"commands" .= map commandRecord commands])

-- Extend the existing native fixture producer. Expected values come only from
-- GHC's executable, while the late plugin supplies actual Core for JVM tests.
prepareWindowsSmoke :: FilePath -> IO ()
prepareWindowsSmoke root = do
  unless (Host.os == "mingw32") (die "windows-smoke requires native Windows GHC")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  python <- maybe "python" id <$> lookupEnv "THC_PYTHON"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (words version == ["9.14.1"]) (die "windows-smoke requires GHC 9.14.1")
  stamp <- formatTime defaultTimeLocale "%Y%m%dT%H%M%S%q" <$> getCurrentTime
  let directory = "build/windows-smoke"
      logs = directory </> stamp
      native = "build/native"
      oracle = native </> "native-oracle.exe"
      modules = ["build/core/THC.Prim.Test.cbd", "build/core/Fixtures.cbd"]
  createDirectoryIfMissing True (root </> native)
  powershell <- maybe "powershell.exe" id <$> findExecutable "pwsh"
  exported <- runLogged 300 root logs "export" [] powershell
    ["-NoProfile", "-File", root </> "bin/export-core.ps1", "t/fixtures/core/Fixtures.hs"]
  compiled <- runLogged 180 root logs "native-build" [] ghc
    ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-it/fixtures/core", "-it/fixtures/compiler",
     "-odir", root </> native, "-hidir", root </> native, "t/fixtures/core/NativeOracle.hs",
     "-o", root </> oracle]
  observed <- runLogged 60 root logs "native-oracle" [] (root </> oracle) []
  let rows = map (splitTab . BSC.unpack) (BSC.lines (commandStdout observed))
  unless (length rows == 133 && all ((== 3) . length) rows) (die "unexpected native smoke inventory")
  BS.writeFile (root </> native </> "oracle.tsv") (commandStdout observed)
  let entries = Set.toAscList (Set.fromList [entry | entry:_ <- rows])
  audits <- forM entries $ \entry ->
    runLogged 60 root logs ("audit-" ++ entry) [] python
      (["bin/audit-core.py", "--entry", "main:Fixtures." ++ entry, "--output", root </> logs </> entry ++ ".audit.json"] ++ modules)
  rejected <- runLoggedExpect 1 60 root logs "audit-missing-entry" [] python
    (["bin/audit-core.py", "--entry", "missingWindowsSmokeEntry", "--output",
      root </> logs </> "missing.audit.json"] ++ modules)
  (cstringCommands, cstringArtifacts) <- prepareCString root logs ghc
  compiler <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  let sources = ["t/fixtures/core/NativeOracle.hs", "t/fixtures/core/NativeTiming.hs", "t/fixtures/core/Fixtures.hs",
        "t/fixtures/compiler/THC/Prim/Test.hs", "t/fixtures/core/MapWorkload.hs",
        "t/haskell-fixtures/WindowsSmokeFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
        "t/haskell-fixtures/Main.hs",
        "nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/CString.hs", "src/compiler/interface/Main.hs", "bin/export-core.ps1",
        "bin/windows-common.ps1", "bin/audit-core.py", "bin/core-capabilities.json",
        "thc.cabal", "cabal.project"] ++
        ["src/compiler/THC" </> path | path <- compiler, takeExtension path == ".hs"] ++
        ["bin" </> path | path <- scripts, take 5 path == "core_", takeExtension path == ".py"]
      commands = [exported, compiled, observed] ++ audits ++ [rejected] ++ cstringCommands
      artifacts = cstringArtifacts ++ modules ++ [native </> "oracle.tsv", oracle] ++
        concatMap commandArtifacts commands ++
        [logs </> entry ++ ".audit.json" | entry <- entries] ++ [logs </> "missing.audit.json"]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "provenance.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "system" .= Host.os,
     "nativeRows" .= length rows, "entries" .= entries, "logs" .= logs,
     "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes,
     "commands" .= map commandRecord commands]
  putStrLn "windows-smoke: 133 native GHC rows, 19 strict entry audits, missing-entry negative control"

-- The Windows bindist has no installed simplified Core. Compile this one
-- unchanged, hash-pinned module with real -fwrite-if-simplified-core, then read
-- it through the same strict interface helper used for installed libraries.
-- No plugin interface is loaded into the ghc-internal unit being rebuilt.
prepareCString :: FilePath -> FilePath -> FilePath -> IO ([CommandResult], [FilePath])
prepareCString root logs ghc = do
  let source = "nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/CString.hs"
      overlay = logs </> "cstring-interfaces"
      interface = overlay </> "GHC/Internal/CString.hi"
      output = "build/map/boot-core/GHC.Internal.CString.cbd"
      pkg = takeDirectory ghc </> "ghc-pkg.exe"
      oneLine = reverse . dropWhile (\c -> c == '\r' || c == '\n') . reverse
  digest <- hashFile (root </> source)
  unless (digest == "3b2e7a0fb2880d8f98cb002adfbaa36a8469667b7494f8f695fe1a6f181de573")
    (die "pinned CString source changed")
  imports <- oneLine <$> run root [] pkg ["field", "ghc-internal", "import-dirs", "--simple-output"] ""
  unit <- oneLine <$> run root [] pkg ["field", "ghc-internal", "id", "--simple-output"] ""
  libdir <- oneLine <$> run root [] ghc ["--print-libdir"] ""
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  helper <- oneLine <$> run root [] cabal ["list-bin", "exe:thc-interface", "--disable-shared",
    "--with-compiler=" ++ ghc, "--with-hc-pkg=" ++ pkg] ""
  installed <- interfaceFiles imports ""
  let dependencies = filter (/= "GHC" </> "Internal" </> "CString.hi") installed
  copied <- forM dependencies $ \relative -> do
    let destination = overlay </> relative
    createDirectoryIfMissing True (takeDirectory (root </> destination))
    copyFile (imports </> relative) (root </> destination)
    pure destination
  compiled <- runLogged 120 root logs "cstring-build" [] ghc
    ["-c", "-O2", "-g", "-fforce-recomp", "-fwrite-if-simplified-core", "-dcore-lint",
     "-this-unit-id", "ghc-internal", "-package", "ghc-internal",
     "-odir", root </> overlay, "-hidir", root </> overlay, source]
  exported <- runLogged 60 root logs "cstring-interface" [] helper
    ["--libdir", libdir, "--unit", unit, "--module", "GHC.Internal.CString",
     "--interface", root </> interface, "--way", "vanilla", "--source-notes"]
  case readModuleValue (commandStdout exported) of
    Right (Object core) | KeyMap.lookup "unit" core == Just (String "ghc-internal"),
      KeyMap.lookup "module" core == Just (String "GHC.Internal.CString") -> do
        createDirectoryIfMissing True (takeDirectory (root </> output))
        BS.writeFile (root </> output) (commandStdout exported)
    _ -> die "CString helper did not return genuine loaded CBD Core"
  pure ([compiled, exported], output : interface : copied)

interfaceFiles :: FilePath -> FilePath -> IO [FilePath]
interfaceFiles root relative = do
  names <- listDirectory (root </> relative)
  concat <$> forM names (\name -> do
    let path = relative </> name
    directory <- doesDirectoryExist (root </> path)
    if directory then interfaceFiles root path
    else pure [path | takeExtension path == ".hi"])

-- Actual public CLI regression: native expected completion, package and dist
-- paths containing spaces, and the response-file boundary through PowerShell.
prepareWindowsDriver :: FilePath -> IO ()
prepareWindowsDriver root = do
  unless (Host.os == "mingw32") (die "windows-driver requires native Windows")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  buildDirectory <- lookupEnv "THC_CABAL_BUILD_DIR"
  stamp <- formatTime defaultTimeLocale "%Y%m%dT%H%M%S%q" <$> getCurrentTime
  let logs = "build/windows-driver" </> stamp
      package = logs </> "package with spaces"
      native = logs </> "native"
      oneLine = reverse . dropWhile (\c -> c == '\r' || c == '\n') . reverse
      originals = ["run-pure.cabal", "app/Main.hs", "app/Answer.hs"]
  createDirectoryIfMissing True (root </> native)
  copied <- forM originals $ \relative -> do
    let destination = package </> relative
    createDirectoryIfMissing True (takeDirectory (root </> destination))
    copyFile (root </> "t/fixtures/run-pure" </> relative) (root </> destination)
    pure destination
  compiled <- runLogged 120 root logs "native-build" [] ghc
    ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-i" ++ (root </> package </> "app"),
     "-odir", root </> native, "-hidir", root </> native, root </> package </> "app/Main.hs",
     "-o", root </> native </> "completed.exe"]
  observed <- runLogged 60 root logs "native-run" [] (root </> native </> "completed.exe") []
  unless (BS.null (commandStdout observed)) (die "unexpected run-pure native output")
  driver <- oneLine <$> run root [] cabal (["list-bin", "exe:thc", "--disable-shared", "--with-compiler=" ++ ghc] ++
    ["--builddir=" ++ directory | Just directory <- [buildDirectory]]) ""
  -- The build script sets GHC_PKG, which would hide a broken .exe companion
  -- lookup. Exercise ordinary compiler-relative discovery, restoring our
  -- process environment even if a CLI regression throws.
  runs <- bracket (lookupEnv "GHC_PKG" <* unsetEnv "GHC_PKG")
    (maybe (unsetEnv "GHC_PKG") (setEnv "GHC_PKG")) $ \_ ->
    forM [(backend,dense) | backend <- ["ast","bytecode"], dense <- ["false","true"]] $ \(backend,dense) -> do
      let label = backend ++ "-" ++ dense
          output = root </> logs </> ("dist with spaces " ++ label)
          verify = backend == "ast" && dense == "false"
      -- The first run may build the pinned vanilla support graph from source;
      -- explicitly audit it, then exercise the default no-audit policy in the
      -- later modes while reusing that exact support cache.
      result <- runLogged (if verify then 1800 else 180) root logs label
        [("THC_BACKEND", backend), ("JAVA_OPTS", "-Dthc.diagnostics=true -Dthc.handoffSlabs=" ++ dense)]
        driver (["run", "--project-dir", root </> package, "completed",
                 "--thc-root", root, "--dist-dir", output] ++ ["--verify-artifacts" | verify])
      unless (commandStdout result == commandStdout observed) (die "driver output differs from native GHC")
      let diagnostics = [fields | line <- BSC.lines (commandStderr result),
            Right (Object fields) <- [eitherDecodeStrict line]]
      unless (any ((== Just (String (Text.pack backend))) . KeyMap.lookup "backend") diagnostics)
        (die ("driver did not report selected backend: " ++ label))
      pure result
  drivers <- listDirectory (root </> "src/driver/THC/Driver")
  let commands = [compiled, observed] ++ runs
      supportManifests = [logs </> ("dist with spaces " ++ backend ++ "-" ++ dense) </>
        "thc-run/completed/runtime-support/packages.json" | backend <- ["ast","bytecode"], dense <- ["false","true"]]
      auditedManifests = take 1 supportManifests
      sources = ["t/fixtures/run-pure" </> path | path <- originals] ++
        ["src/driver/THC/Driver" </> path | path <- drivers, takeExtension path == ".hs"] ++
        ["t/haskell-fixtures/WindowsSmokeFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
        "t/haskell-fixtures/Main.hs",
         "bin/export-core.ps1", "src/compiler/interface/Main.hs", "etc/ghc/9.14.1/windows-ghc-internal.json", "src/driver/WindowsRunMain.hs",
         "bin/windows-common.ps1", "bin/windows.ps1", "thc.cabal"]
  sourceHashes <- hashes root sources
  exports <- fmap concat $ forM supportManifests $ \manifest -> do
    let output = takeDirectory (takeDirectory manifest)
        core = output </> "core"
        audit = output </> "audit.json"
        verified = manifest `elem` auditedManifests
    audited <- doesFileExist (root </> audit)
    unless (audited == verified) (die ("driver audit presence differs from requested policy: " ++ manifest))
    files <- filter ((== ".json") . takeExtension) <$> listDirectory (root </> core)
    pure ([core </> file | file <- files] ++ [audit | verified] ++ [output </> "export.args"])
  artifactHashes <- hashes root (copied ++ supportManifests ++ exports ++ [native </> "completed.exe"] ++ concatMap commandArtifacts commands)
  writeJson (root </> "build/windows-driver/provenance.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "system" .= Host.os,
     "runs" .= (4 :: Int), "packageToolSelection" .= ("compiler-companion" :: String),
     "supportManifests" .= supportManifests, "auditedManifests" .= auditedManifests,
     "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes,
     "commands" .= map commandRecord commands]
  putStrLn "windows-driver: native GHC completion matches AST/bytecode, default/dense, paths with spaces"
