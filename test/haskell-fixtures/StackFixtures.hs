-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

module StackFixtures (prepareOriginalStack, prepareOriginalStackFormatter, exportOriginalStackSource) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import qualified Data.Map.Strict as Map
import Data.List (sort, isPrefixOf, isSuffixOf)
import FixtureSupport (CommandResult (..), hashFile, hashes, runLogged, writeJson)
import qualified FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, renameFile)
import System.Environment (lookupEnv, getExecutablePath)
import System.Exit (die)
import System.FilePath ((</>), makeRelative, replaceExtension, takeExtension)
import qualified THC.Driver.Wired as Wired

entries :: [String]
entries = ["captureOriginal", "decodeOriginal", "renderOriginal", "renderOriginalNames", "peekOriginalInfoTable",
           "lookupOriginalIPE", "peekOriginalInfoProv"]

proofResource :: FilePath
proofResource = "compiler/test-fixtures/OriginalStackProof.json"

prepareOriginalStack :: FilePath -> IO ()
prepareOriginalStack root = do
  let base = "build/original-stack"
      manifest = root </> base </> "manifest.json"
  createDirectoryIfMissing True (root </> base)
  previous <- listDirectory (root </> base)
  let available n = if "run-" ++ show n `elem` previous then available (n + 1) else "run-" ++ show n
      attempt = available (1 :: Int)
      directory = base </> attempt
      logs = directory </> "logs"
      run label overrides program arguments = runLogged 180 root logs label overrides program arguments
  createDirectoryIfMissing True (root </> directory)
  present <- doesFileExist manifest
  when present (renameFile manifest (root </> directory </> "previous-manifest.json"))
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run "ghc-version" [] ghc ["--numeric-version"]
  unless (BS.words (commandStdout version) == ["9.14.1"]) (die "Original stack fixture requires GHC 9.14.1")
  revision <- run "thc-revision" [] "git" ["rev-parse", "HEAD"]

  let source = "compiler/test-fixtures/OriginalStackAudit.hs"
      driver = "compiler/test-fixtures/OriginalStackAuditNative.hs"
  stages <- forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage ++ "-core"
        options = ["-package", "ghc-internal"] ++
          ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          map ("-fplugin-opt=THC.Plugin:closure=" ++) entries ++ [source]
    result <- run (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> directory </> stage ++ "-ghc")]
      "compiler/export.sh" options
    paths <- map (core </>) . sort <$> listDirectory (root </> core)
    let json = filter (\p -> reverse (take 5 (reverse p)) == ".json") paths
    unless (core </> "OriginalStackAudit.json" `elem` json) (die "Missing original stack consumer export")
    pure (stage, json, result)

  let native = directory </> "native"
      executable = native </> "original-stack-native"
  createDirectoryIfMissing True (root </> native)
  compile <- run "native-compile" [] ghc
    ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-g", "-finfo-table-map",
     "-package", "ghc-internal", "-i" ++ root </> "compiler/test-fixtures",
     "-odir", root </> native, "-hidir", root </> native, root </> driver, "-o", root </> executable]
  nativeRun <- run "native-invariants" [] (root </> executable) []

  let commands = [version, revision] ++ [r | (_, _, r) <- stages] ++ [compile, nativeRun]
      artifacts = concat [paths | (_, paths, _) <- stages] ++ [executable] ++
        concatMap commandArtifacts commands
      sources = [source, driver, "test/haskell-fixtures/StackFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "test/haskell-fixtures/Main.hs", "thc.cabal", proofResource,
        "compiler/export.sh", "compiler/build.sh", "compiler/toolchain.sh", "compiler/THC/Plugin.hs",
        "compiler/THC/CBV.hs", "compiler/THC/Demands.hs", "compiler/THC/Sources.hs", "compiler/THC/Wired.hs", "compiler/plugin.py",
        "compiler/pinned-ghc-internal/LICENSE"] ++ map ("compiler/pinned-ghc-internal/" ++)
        ["GHC/Internal/Stack/CloneStack.hs", "GHC/Internal/Stack/Decode.hs",
         "GHC/Internal/InfoProv/Types.hsc", "GHC/Internal/Heap/InfoTable.hsc"]
  proofHash <- hashFile (root </> proofResource)
  unless (proofHash == "db63661c12a6ecb757697e759fcb95e4d51f3689619bdb7682a041788eb41d4f")
    (die "Original stack proof resource changed; review its original-source provenance")
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object
    ["format" .= ("thc-original-stack-fixture" :: String), "schema" .= (1 :: Int),
     "entries" .= entries, "thcRevision" .= BS.unpack (BS.takeWhile (/= '\n') (commandStdout revision)),
     "ghc" .= object ["path" .= ghc, "version" .= ("9.14.1" :: String), "installedArtifactsHashed" .= False],
     "stages" .= Map.fromList [(stage, paths) | (stage, paths, _) <- stages],
     "proofResource" .= proofResource, "nativeOutput" .= (logs </> "native-invariants.stdout"),
     "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes,
     "commands" .= map commandRecord commands,
     "limit" .= ("Native invariants only; retained source contracts include unsupported cold getters. No guest Decode success or native/JVM frame equivalence." :: String)]
  putStrLn ("Original source consumer and native shape evidence: " ++ manifest)

-- Reuse the production unmodified-source exporter. Installed interfaces are read,
-- never hashed or changed; its private overlay and generated HSC stay in this attempt.
exportOriginalStackSource :: FilePath -> FilePath -> IO ()
exportOriginalStackSource root directory = do
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  let sourceRoot = root </> "compiler/pinned-ghc-internal"
  mapM_ (\(path, expected) -> do
    observed <- hashFile (sourceRoot </> path)
    unless (observed == expected) (die ("Changed pinned original source: " ++ path))) Wired.sourceHashes
  let field name = do
        output <- FixtureSupport.run root [] "python3" ["compiler/plugin.py", "--field", name] ""
        case lines output of
          [value] | not (null value) -> pure value
          _ -> die ("Invalid plugin field: " ++ name)
  library <- field "sharedLibrary"
  unit <- field "unitId"
  generated <- Wired.exportPinnedCore sourceRoot ghc ghcPkg library unit
    (root </> "compiler/target-layout.c") (root </> directory)
  writeJson (root </> directory </> "generated.json") $ object
    ["sources" .= map (\(original, path) -> (original, makeRelative root path)) (Wired.generatedSources generated),
     "targetLayout" .= makeRelative root (Wired.targetLayout generated)]

-- A formatter proof, intentionally separate from original-stack's full decoder frontier.
prepareOriginalStackFormatter :: FilePath -> IO ()
prepareOriginalStackFormatter root = do
  let base = "build/original-stack-formatter"
      manifest = root </> base </> "manifest.json"
  createDirectoryIfMissing True (root </> base)
  previous <- listDirectory (root </> base)
  let available n = if "run-" ++ show n `elem` previous then available (n + 1) else "run-" ++ show n
      directory = base </> available (1 :: Int)
      logs = directory </> "logs"
      command label overrides program args = runLogged 300 root logs label overrides program args
  createDirectoryIfMissing True (root </> directory)
  present <- doesFileExist manifest
  when present (renameFile manifest (root </> directory </> "previous-manifest.json"))
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- command "ghc-version" [] ghc ["--numeric-version"]
  unless (BS.words (commandStdout version) == ["9.14.1"]) (die "Original formatter requires GHC 9.14.1")
  plugin <- command "plugin-build" [] "compiler/build.sh" []
  executable <- getExecutablePath
  -- The cold, serial export of all 54 pinned modules can exceed five minutes
  -- on CI; keep the other formatter commands on their shorter limit.
  sourceExport <- runLogged 600 root logs "original-source-export" [] executable
    ["original-stack-source-export", directory </> "originals"]
  let coreRoot = directory </> "originals/core"
  originals <- map (coreRoot </>) . sort <$> listDirectory (root </> coreRoot)
  let source = "compiler/test-fixtures/OriginalStackFormatter.hs"
      native = "compiler/test-fixtures/OriginalStackFormatterNative.hs"
  stages <- forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage ++ "-core"
    exported <- command (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> directory </> stage ++ "-ghc")]
      "compiler/export.sh" (["-package", "ghc-internal", "-fignore-interface-pragmas"] ++
        ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source])
    pure (stage, core </> "OriginalStackFormatter.json", exported)
  let nativeDirectory = directory </> "native"
      binary = nativeDirectory </> "formatter"
  createDirectoryIfMissing True (root </> nativeDirectory)
  compiled <- command "native-compile" [] ghc
    ["--make", "-O2", "-fignore-interface-pragmas", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
     "-package", "ghc-internal", "-i" ++ root </> "compiler/test-fixtures",
     "-odir", root </> nativeDirectory, "-hidir", root </> nativeDirectory,
     root </> native, "-o", root </> binary]
  observed <- command "native-observations" [] (root </> binary) []
  audits <- forM stages $ \(stage, consumer, _) -> do
    let output = directory </> stage ++ "-audit.json"
    audited <- command (stage ++ "-audit") [] "python3"
      (["scripts/audit-core.py", "--entry", "formatOriginal", "--output", output, consumer] ++ originals)
    pure (output, audited)
  scriptNames <- listDirectory (root </> "scripts")
  let generatedSources = [directory </> "originals/generated" </> replaceExtension path "hs" |
        (path,_) <- Wired.moduleSources, takeExtension path == ".hsc"]
      layout = directory </> "originals/target-layout.json"
      commands = [version, plugin, sourceExport] ++ [result | (_,_,result) <- stages] ++
        [compiled, observed] ++ map snd audits
      inputs = [source,native,"test/haskell-fixtures/StackFixtures.hs","test/haskell-fixtures/FixtureSupport.hs",
        "test/haskell-fixtures/Main.hs","thc.cabal","src/THC/Driver/Wired.hs","compiler/target-layout.c",
        "compiler/export.sh","compiler/build.sh","compiler/toolchain.sh","compiler/plugin.py",
        "compiler/THC/Plugin.hs","compiler/THC/CBV.hs","compiler/THC/Demands.hs","compiler/THC/Sources.hs","compiler/THC/Wired.hs",
        "scripts/audit-core.py","scripts/core-capabilities.json","src/main/resources/thc/scalar-primop-signatures.json"] ++
        map (("compiler/pinned-ghc-internal/" ++) . fst) Wired.sourceHashes ++
        ["scripts" </> name | name <- sort scriptNames, "core_" `isPrefixOf` name, ".py" `isSuffixOf` name]
      artifacts = originals ++ [path | (_,path,_) <- stages] ++ [binary] ++ map fst audits ++
        concatMap commandArtifacts commands ++ generatedSources ++ [layout, directory </> "originals/generated.json"]
  sourceHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object
    ["format" .= ("thc-original-stack-formatter-fixture" :: String), "schema" .= (1 :: Int),
     "ghc" .= ("9.14.1" :: String), "installedArtifactsHashed" .= False,
     "originals" .= originals, "stages" .= Map.fromList [(stage,path) | (stage,path,_) <- stages],
     "nativeOutput" .= (logs </> "native-observations.stdout"), "audits" .= map fst audits,
     "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands,
     "limit" .= ("Original prettyStackEntry only; not full original stack decoding or native-frame equivalence." :: String)]
  putStrLn ("Original formatter source and native evidence: " ++ manifest)
