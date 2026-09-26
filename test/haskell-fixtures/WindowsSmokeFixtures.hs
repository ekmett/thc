-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module WindowsSmokeFixtures (prepareWindowsSmoke, prepareWindowsDriver) where

import Control.Monad (forM, unless)
import Data.Aeson (Value(..), eitherDecodeStrict, object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import qualified Data.Set as Set
import qualified Data.Text as Text
import Data.Time (defaultTimeLocale, formatTime, getCurrentTime)
import FixtureSupport
import System.Directory (copyFile, createDirectoryIfMissing, doesDirectoryExist, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeDirectory, takeExtension)
import qualified System.Info as Host

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
      modules = ["build/core/THC.Prim.json", "build/core/THC.Fixtures.json"]
  createDirectoryIfMissing True (root </> native)
  exported <- runLogged 300 root logs "export" [] "powershell.exe"
    ["-NoProfile", "-File", root </> "compiler/export.ps1", "examples/THC/Fixtures.hs"]
  compiled <- runLogged 180 root logs "native-build" [] ghc
    ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-iexamples",
     "-odir", root </> native, "-hidir", root </> native, "examples/NativeOracle.hs",
     "-o", root </> oracle]
  observed <- runLogged 60 root logs "native-oracle" [] (root </> oracle) []
  let rows = map (splitTab . BSC.unpack) (BSC.lines (commandStdout observed))
  unless (length rows == 133 && all ((== 3) . length) rows) (die "unexpected native smoke inventory")
  BS.writeFile (root </> native </> "oracle.tsv") (commandStdout observed)
  let entries = Set.toAscList (Set.fromList [entry | entry:_ <- rows])
  audits <- forM entries $ \entry ->
    runLogged 60 root logs ("audit-" ++ entry) [] python
      (["scripts/audit-core.py", "--entry", entry, "--output", root </> logs </> entry ++ ".audit.json"] ++ modules)
  rejected <- runLoggedExpect 1 60 root logs "audit-missing-entry" [] python
    (["scripts/audit-core.py", "--entry", "missingWindowsSmokeEntry", "--output",
      root </> logs </> "missing.audit.json"] ++ modules)
  (cstringCommands, cstringArtifacts) <- prepareCString root logs ghc
  compiler <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let sources = ["examples/NativeOracle.hs", "examples/THC/Fixtures.hs",
        "examples/THC/Prim.hs", "examples/THC/MapWorkload.hs",
        "test/haskell-fixtures/WindowsSmokeFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "test/haskell-fixtures/Main.hs",
        "compiler/pinned-ghc-internal/GHC/Internal/CString.hs", "compiler/interface/Main.hs", "compiler/export.ps1",
        "scripts/windows-common.ps1", "scripts/audit-core.py", "scripts/core-capabilities.json",
        "thc.cabal", "cabal.project"] ++
        ["compiler/THC" </> path | path <- compiler, takeExtension path == ".hs"] ++
        ["scripts" </> path | path <- scripts, take 5 path == "core_", takeExtension path == ".py"]
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
  let source = "compiler/pinned-ghc-internal/GHC/Internal/CString.hs"
      overlay = logs </> "cstring-interfaces"
      interface = overlay </> "GHC/Internal/CString.hi"
      output = "build/map/boot-core/GHC.Internal.CString.json"
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
  case eitherDecodeStrict (commandStdout exported) of
    Right (Object envelope) | KeyMap.lookup "status" envelope == Just (String "loaded"),
      Just core@(Object _) <- KeyMap.lookup "core" envelope -> do
        createDirectoryIfMissing True (takeDirectory (root </> output))
        writeJson (root </> output) core
    _ -> die "CString helper did not return genuine loaded Core"
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
    copyFile (root </> "test/fixtures/run-pure" </> relative) (root </> destination)
    pure destination
  compiled <- runLogged 120 root logs "native-build" [] ghc
    ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-i" ++ (root </> package </> "app"),
     "-odir", root </> native, "-hidir", root </> native, root </> package </> "app/Main.hs",
     "-o", root </> native </> "completed.exe"]
  observed <- runLogged 60 root logs "native-run" [] (root </> native </> "completed.exe") []
  unless (BS.null (commandStdout observed)) (die "unexpected run-pure native output")
  driver <- oneLine <$> run root [] cabal ["list-bin", "exe:thc", "--disable-shared", "--with-compiler=" ++ ghc] ""
  runs <- forM [(backend,dense) | backend <- ["ast","bytecode"], dense <- ["false","true"]] $ \(backend,dense) -> do
    let label = backend ++ "-" ++ dense
        output = root </> logs </> ("dist with spaces " ++ label)
    result <- runLogged 180 root logs label
      [("THC_BACKEND", backend), ("JAVA_OPTS", "-Dthc.diagnostics=true -Dthc.handoffSlabs=" ++ dense)]
      driver ["run", root </> package </> "run-pure.cabal", "--exe", "completed",
              "--thc-root", root, "--dist-dir", output]
    unless (commandStdout result == commandStdout observed) (die "driver output differs from native GHC")
    let diagnostics = [fields | line <- BSC.lines (commandStderr result),
          Right (Object fields) <- [eitherDecodeStrict line]]
    unless (any ((== Just (String (Text.pack backend))) . KeyMap.lookup "backend") diagnostics)
      (die ("driver did not report selected backend: " ++ label))
    pure result
  drivers <- listDirectory (root </> "src/THC/Driver")
  let commands = [compiled, observed] ++ runs
      sources = ["test/fixtures/run-pure" </> path | path <- originals] ++
        ["src/THC/Driver" </> path | path <- drivers, takeExtension path == ".hs"] ++
        ["test/haskell-fixtures/WindowsSmokeFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "test/haskell-fixtures/Main.hs",
         "compiler/export.ps1", "scripts/windows-common.ps1", "scripts/windows.ps1", "thc.cabal"]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root (copied ++ [native </> "completed.exe"] ++ concatMap commandArtifacts commands)
  writeJson (root </> "build/windows-driver/provenance.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "system" .= Host.os,
     "runs" .= (4 :: Int), "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes,
     "commands" .= map commandRecord commands]
  putStrLn "windows-driver: native GHC completion matches AST/bytecode, default/dense, paths with spaces"