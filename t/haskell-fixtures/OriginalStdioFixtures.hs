-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (065 original-errno)
-- Purpose: Check errno is read/written in the correct native context across foreign calls.
-- Produces/consumed result: CBDs and oracle.json derived from native observations.
-- Cost and overlap: Keep the FFI errno boundary, especially thread/context isolation.
--   Consolidate into package FFI integration; do not test the system errno constants
--   themselves.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 065.
--
-- Fixture rationale (066 original-process-identity)
-- Purpose: Check process identity calls execute through ordinary package linkage.
-- Produces/consumed result: CBDs, oracle.json and installed package/runtime modules reused
--   by other groups.
-- Cost and overlap: No standalone libc identity harness is justified. Keep package-call
--   smoke only; move shared package acquisition to its own owner and never cache live
--   PID/UID observations as stable results.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 066.
--
-- Fixture rationale (080 original-stdio-read)
-- Purpose: Check reads preserve buffer contents, lengths, EOF and file-position behavior.
-- Inputs: OriginalStdioReadAudit.hs/Native.hs, embedded requests/payload, selected
--   GHC/native libraries, exporter and auditor. No installed-Core acquisition.
-- Produces: Native executable, input.bin, forty result files, oracle.json, pre/post
--   CBD pairs, two audits, logs and manifest; CMake owns all persistent products.
-- Cost and overlap: One native process, two audits. This crosses genuine safe/unsafe
--   read declarations and C buffer offsets; provider tests do not exercise that ABI.
--   The child starts with explicit seekable input.bin on fd0 and resets it per case.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 080.
--
-- Fixture rationale (099 original-stdio)
-- Purpose: Check public writes preserve bytes/counts and descriptor behavior.
-- Produces/consumed result: Installed/Core fixtures and oracle.json for write operations.
-- Cost and overlap: One package I/O integration test can cover this. 144 native process
--   starts for four write operations are unjustified; batch cases and retire the separate
--   conformance harness.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 099.

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : OriginalStdioFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Production of native observations only. Independent semantics, host ABI and
-- exact original FCall proofs are checked by the Java fixture consumers.
module OriginalStdioFixtures (prepareOriginalStdio, prepareOriginalStdioRead, prepareOriginalErrno, prepareOriginalProcessIdentity) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=), toJSON)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import FixtureSupport
import InstalledCoreFixtures (InstalledFixture(..), prepareInstalledCoreUnits)
import Foreign.C.Types (CInt, CLong)
import Foreign.Ptr (Ptr, nullPtr)
import Foreign.Storable (sizeOf)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import qualified System.Info as Host
import Text.Read (readMaybe)

entries :: [String]
entries = ["originalWrite", "originalSafeWrite", "originalWriteErrno", "originalSafeWriteErrno"]

requests :: [(String,[Integer])]
requests = [(entry,[fd,offset,count]) | entry <- entries, fd <- [-2147483648,-1,1,2],
  (offset,count) <- [(0,0),(256,0),(0,1),(1,8),(10,1),(127,3),(128,128),(255,1),(0,256)]]

directory, source, driver :: FilePath
directory = "build/original-stdio"
source = "t/fixtures/compiler/OriginalStdioAudit.hs"
driver = "t/fixtures/compiler/OriginalStdioAuditNative.hs"

prepareOriginalStdio :: FilePath -> [String] -> IO ()
prepareOriginalStdio root options = do
  let nativeOnly = "--native-only" `elem` options
      exportOnly = "--export-only" `elem` options
      requireSupported = "--require-supported" `elem` options
      mode | nativeOnly = "native-only" :: String
           | exportOnly = "export-only"
           | otherwise = "full"
      output = root </> directory
      manifest = output </> "manifest.json"
      execute = runLogged 120 root (directory </> "logs")
  unless (all (`elem` ["--native-only","--export-only","--require-supported"]) options &&
          Set.size (Set.fromList options) == length options &&
          not (nativeOnly && (exportOnly || requireSupported))) $
    die "Usage: thc-fixtures original-stdio [--native-only|--export-only] [--require-supported]"
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  -- Native execution domain, not a second semantic/foreign-proof checker.
  unless (Host.os `elem` ["linux","darwin"] && sizeOf (0 :: CInt) == 4 &&
          sizeOf (0 :: CLong) == 8 && sizeOf (nullPtr :: Ptr ()) == 8) $
    die "Original stdio native preparation requires a Linux/macOS LP64 host"
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original stdio requires pinned GHC 9.14.1")
  info <- execute "ghc-info" [] ghc ["--info"]
  case readMaybe (BSC.unpack (commandStdout info)) :: Maybe [(String,String)] of
    Just target | Just host <- lookup "Host platform" target,
                  not (null host), lookup "Target platform" target == Just host,
                  lookup "target word size" target == Just "8" -> pure ()
    _ -> die "Original stdio native preparation rejects cross-compiling or non-64-bit GHC"
  (observations,nativeCommands,nativeArtifacts) <- if exportOnly then pure ([],[],[]) else do
    let native = directory </> "native"
        binary = native </> "original-stdio-oracle"
        results = directory </> "results"
    createDirectoryIfMissing True (root </> native)
    createDirectoryIfMissing True (root </> results)
    compiled <- execute "native-build" [] ghc ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint",
      "-package","ghc-internal","-i" ++ (root </> "t/fixtures/compiler"),
      "-odir",root </> native,"-hidir",root </> native,driver,"-o",root </> binary]
    rows <- forM (zip [0 :: Int ..] requests) $ \(index,(entry,arguments)) -> do
      let resultPath = results </> show index ++ ".txt"
          number = show index
          label = "native-" ++ replicate (3 - length number) '0' ++ number
      stale <- doesFileExist (root </> resultPath)
      when stale (removeFile (root </> resultPath))
      actual <- runLogged 10 root (directory </> "logs") label [] (root </> binary)
        (entry : map show arguments ++ [root </> resultPath])
      text <- BSC.unpack <$> BS.readFile (root </> resultPath)
      result <- maybe (die ("Malformed native integer result: " ++ resultPath)) pure
        (readInteger (takeWhile (/= '\n') text))
      unless (text == show result ++ "\n") (die ("Malformed native result framing: " ++ resultPath))
      let observation = object ["entry" .= entry,"arguments" .= arguments,"result" .= result,
            "stdoutHex" .= hexBytes (commandStdout actual),"stderrHex" .= hexBytes (commandStderr actual)]
      pure (observation,actual,resultPath)
    let observed = [row | (row,_,_) <- rows]
        oracle = directory </> "oracle.json"
    writeJson (root </> oracle) (toJSON observed)
    pure (observed,compiled : [command | (_,command,_) <- rows],binary : oracle : [path | (_,_,path) <- rows])
  exports <- if nativeOnly then pure [] else forM ["pre","post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        modules = [core </> "OriginalStdioAudit.cbd",core </> "THC.InterfaceClosure.cbd"]
        postTidy = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
        roots = ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",root </> stageDir </> "ghc"),("THC_SOURCE_NOTES","true")]
      "bin/export-core.sh" (["-package","ghc-internal"] ++ postTidy ++ roots ++ [source])
    mapM_ (\path -> do
      exists <- doesFileExist (root </> path)
      unless exists (die ("Missing genuine GHC export: " ++ path))) modules
    audits <- if not requireSupported then pure [] else forM entries $ \entry -> do
      let path = stageDir </> entry ++ ".audit.json"
      command <- execute (stage ++ "-audit-" ++ entry) [] "python3"
        (["bin/audit-core.py","--entry", "main:OriginalStdioAudit." ++ entry,"--output",path] ++ modules)
      pure (entry,path,command)
    pure (stage,object ["modules" .= modules],Map.fromList [(entry,path) | (entry,path,_) <- audits],
          exported : [command | (_,_,command) <- audits],modules ++ [path | (_,path,_) <- audits])
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  let commands = [version,info] ++ nativeCommands ++ concat [cs | (_,_,_,cs,_) <- exports]
      sources = sort $ [source,driver,"thc.cabal","t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/FixtureSupport.hs","t/haskell-fixtures/OriginalStdioFixtures.hs",
        "bin/prepare-original-stdio.sh","bin/audit-core.py","bin/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json",
        "bin/build-compiler.sh","bin/export-core.sh","bin/toolchain.sh","bin/plugin.py"] ++
        ["src/compiler/THC" </> path | path <- plugin, takeExtension path == ".hs"] ++
        ["bin" </> path | path <- scripts, "core_" `isPrefixOf` path, takeExtension path == ".py"]
      artifacts = nativeArtifacts ++ concat [paths | (_,_,_,_,paths) <- exports] ++ concatMap commandArtifacts commands
  inputHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object
    ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),"ghcInfo" .= BSC.unpack (commandStdout info),
     "mode" .= mode,"entries" .= entries,"payloadHex" .= hexBytes (BS.pack [0..255]),
     "nativeRows" .= length observations,
     "stages" .= Map.fromList [(stage,settings) | (stage,settings,_,_,_) <- exports],
     "audits" .= Map.fromList [(stage,audits) | (stage,_,audits,_,_) <- exports],
     "strictAccepted" .= requireSupported,"runtimeVerified" .= False,"installedArtifactsHashed" .= False,
     "commands" .= map commandRecord commands,"inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,
     "claim" .= ("Native observations and genuine pre/post Core; Java owns semantic, ABI and raw-proof validation" :: String)]
  putStrLn ("original-stdio: " ++ show (length observations) ++ " native observations, " ++
            show (length exports) ++ " Core stages; no runtime claim")

readEntries :: [String]
readEntries = ["originalRead","originalSafeRead","originalReadErrno","originalSafeReadErrno"]

readRequests :: [(String,[Integer])]
readRequests = [(entry,[fd,offset,count,start]) | entry <- readEntries,
  (fd,offset,count,start) <- [(-1,0,0,0),(-1,3,4,0),(1,3,4,0),(2,3,0,0),
                             (0,16,0,0),(0,2,4,0),(0,1,12,0),(0,0,5,4),
                             (0,5,4,8),(0,3,6,7)]]

-- The original GHC c_read/c_safe_read wrappers read from a real native fd0;
-- the child receives the seekable input file as fd0 before RTS initialization,
-- then the native harness opens and seeks its own fresh copy. Never start this
-- oracle with fd0 closed: an RTS descriptor could occupy it before Haskell main.
prepareOriginalStdioRead :: FilePath -> IO ()
prepareOriginalStdioRead root = do
  let dir = "build/original-stdio-read"
      native = dir </> "native"
      coreSource = "t/fixtures/compiler/OriginalStdioReadAudit.hs"
      nativeSource = "t/fixtures/compiler/OriginalStdioReadNative.hs"
      input = dir </> "input.bin"
      oracle = dir </> "oracle.json"
      manifest = dir </> "manifest.json"
      binary = native </> "original-stdio-read-oracle"
      execute = runLogged 120 root (dir </> "logs")
  createDirectoryIfMissing True (root </> native)
  createDirectoryIfMissing True (root </> dir </> "results")
  stale <- doesFileExist (root </> manifest)
  when stale (removeFile (root </> manifest))
  unless (Host.os `elem` ["linux","darwin"] && sizeOf (0 :: CInt) == 4 &&
          sizeOf (0 :: CLong) == 8 && sizeOf (nullPtr :: Ptr ()) == 8) $
    die "Original stdio read requires a Linux/macOS LP64 host"
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original stdio read requires GHC 9.14.1")
  let payload = BS.pack [0,1,127,128,255,65,195,169]
  BS.writeFile (root </> input) payload
  compiled <- execute "native-build" [] ghc ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint",
    "-package","ghc-internal","-package","unix","-i" ++ (root </> "t/fixtures/compiler"),
    "-odir",root </> native,"-hidir",root </> native,nativeSource,"-o",root </> binary]
  let cases = [(entry, arguments, dir </> "results" </> show index ++ ".txt")
        | (index, (entry, arguments)) <- zip [0 :: Int ..] readRequests]
  observed <- runLoggedWithInput input 120 root (dir </> "logs") "native-observations" [] (root </> binary)
    (concat [entry : map show arguments ++ [root </> input, root </> resultPath]
      | (entry, arguments, resultPath) <- cases])
  rows <- forM cases $ \(entry, arguments, resultPath) -> do
    text <- BSC.unpack <$> BS.readFile (root </> resultPath)
    case lines text of
      [result,buffer] | Just number <- readInteger result,
                        length buffer == 32,
                        all (`elem` ("0123456789abcdef" :: String)) buffer ->
        pure (object ["entry" .= entry,"arguments" .= arguments,"result" .= number,
                      "bufferHex" .= buffer],resultPath)
      _ -> die ("Malformed native original read result: " ++ resultPath)
  writeJson (root </> oracle) (toJSON [row | (row,_) <- rows])
  exports <- forM ["pre","post"] $ \stage -> do
    let stageDir = dir </> stage
        core = stageDir </> "core"
        modules = [core </> "OriginalStdioReadAudit.cbd",core </> "THC.InterfaceClosure.cbd"]
        postTidy = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
        roots = ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- readEntries]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",root </> stageDir </> "ghc"),
       ("THC_SOURCE_NOTES","true")]
      "bin/export-core.sh" (["-package","ghc-internal"] ++ postTidy ++ roots ++ [coreSource])
    mapM_ (\path -> do
      exists <- doesFileExist (root </> path)
      unless exists (die ("Missing genuine original read Core: " ++ path))) modules
    let path = stageDir </> "audit.json"
    command <- execute (stage ++ "-audit") [] "python3"
      (["bin/audit-core.py", "--output", path] ++ modules ++
       concat [["--entry", "main:OriginalStdioReadAudit." ++ entry] | entry <- readEntries])
    pure (stage,modules,path,[exported,command],modules ++ [path])
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  let commands = [version,compiled,observed] ++
        concat [items | (_,_,_,items,_) <- exports]
      sources = sort $ [coreSource,nativeSource,"thc.cabal","t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/FixtureSupport.hs","t/haskell-fixtures/OriginalStdioFixtures.hs",
        "bin/audit-core.py","bin/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json",
        "bin/build-compiler.sh","bin/export-core.sh","bin/toolchain.sh","bin/plugin.py"] ++
        ["src/compiler/THC" </> path | path <- plugin, takeExtension path == ".hs"] ++
        ["bin" </> path | path <- scripts, "core_" `isPrefixOf` path, takeExtension path == ".py"]
      artifacts = [input,oracle,binary] ++ [path | (_,path) <- rows] ++
        concat [paths | (_,_,_,_,paths) <- exports] ++ concatMap commandArtifacts commands
  inputHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson (root </> manifest) $ object
    ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
     "entries" .= readEntries,"payloadHex" .= hexBytes payload,"nativeRows" .= length rows,
     "stages" .= Map.fromList [(stage,modules) | (stage,modules,_,_,_) <- exports],
     "audits" .= Map.fromList [(stage,reports) | (stage,_,reports,_,_) <- exports],
     "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,
     "commands" .= map commandRecord commands]
  putStrLn ("original-stdio-read: " ++ show (length rows) ++ " native observations, pre/post strict Core audits")

-- Genuine resetErrno/getErrno Core plus an independent native header oracle
-- for all signed CInt boundaries. The Java consumer checks the observations.
prepareOriginalErrno :: FilePath -> IO ()
prepareOriginalErrno root = do
  let output = "build/original-errno"
      coreSource = "t/fixtures/compiler/OriginalErrnoAudit.hs"
      nativeSource = "t/fixtures/compiler/OriginalErrnoNative.hs"
      names = ["originalResetErrno" :: String]
      execute = runLogged 180 root (output </> "logs")
      binary = output </> "native/oracle"
      observedPath = output </> "native/observations.txt"
      oracle = output </> "oracle.json"
      manifest = root </> output </> "manifest.json"
  createDirectoryIfMissing True (root </> output </> "native")
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  let sources = sort $ [coreSource, nativeSource, "thc.cabal", "t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/OriginalStdioFixtures.hs",
        "bin/audit-core.py", "bin/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json",
        "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
        ["bin" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"]
  inputHashes <- hashes root sources
  if not (Host.os `elem` ["linux", "darwin"] && sizeOf (0 :: CInt) == 4 &&
          sizeOf (0 :: CLong) == 8 && sizeOf (nullPtr :: Ptr ()) == 8)
    then do
      writeJson manifest $ object ["schema" .= (1 :: Int), "platform" .= Host.os,
        "supported" .= False, "reason" .= ("Original Linux/macOS LP64 errno declarations only" :: String),
        "inputHashes" .= inputHashes, "artifactHashes" .= object []]
      putStrLn "original-errno: explicitly excluded on this platform"
    else do
      ghc <- maybe "ghc" id <$> lookupEnv "GHC"
      version <- execute "ghc-version" [] ghc ["--numeric-version"]
      unless (commandStdout version == "9.14.1\n") (die "Original errno requires GHC 9.14.1")
      info <- execute "ghc-info" [] ghc ["--info"]
      case readMaybe (BSC.unpack (commandStdout info)) :: Maybe [(String, String)] of
        Just target | Just host <- lookup "Host platform" target,
                      not (null host), lookup "Target platform" target == Just host,
                      lookup "target word size" target == Just "8" -> pure ()
        _ -> die "Original errno requires a native 64-bit GHC"
      compiled <- execute "native-build" [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
        "-package", "ghc-internal", "-it/fixtures/compiler", "-odir", root </> output </> "native",
        "-hidir", root </> output </> "native", nativeSource, "-o", root </> binary]
      old <- doesFileExist (root </> observedPath)
      when old (removeFile (root </> observedPath))
      observed <- execute "native-run" [] (root </> binary) [root </> observedPath]
      unless (BS.null (commandStdout observed) && BS.null (commandStderr observed))
        (die "Original errno native oracle unexpectedly wrote stdout/stderr")
      text <- BSC.unpack <$> BS.readFile (root </> observedPath)
      rows <- maybe (die "Malformed original errno observations") pure (readMaybe text :: Maybe [[Integer]])
      unless (length rows == 5 && all ((== 7) . length) rows)
        (die "Incomplete original errno observations")
      let fields = ["value", "roundTrip", "successResult", "successErrno", "failureResult", "failureErrno", "resetErrno"]
      writeJson (root </> oracle) (toJSON [object (zipWith (.=) fields row) | row <- rows])
      exports <- forM ["pre", "post"] $ \stage -> do
        let core = output </> stage </> "core"
            modules = [core </> "OriginalErrnoAudit.cbd", core </> "THC.InterfaceClosure.cbd"]
            options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
              ["-fplugin-opt=THC.Plugin:closure=" ++ name | name <- names]
        exported <- execute (stage ++ "-export")
          [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> output </> stage </> "ghc"),
           ("THC_SOURCE_NOTES", "true")]
          "bin/export-core.sh" (["-package", "ghc-internal"] ++ options ++ [coreSource])
        audits <- forM names $ \name -> do
          let path = output </> stage </> name ++ ".audit.json"
          audited <- execute (stage ++ "-audit-" ++ name) [] "python3"
            (["bin/audit-core.py", "--entry", "main:OriginalErrnoAudit." ++ name, "--output", path] ++ modules)
          pure (name, path, audited)
        pure (stage, modules, Map.fromList [(name, path) | (name, path, _) <- audits],
              exported : [command | (_, _, command) <- audits], modules ++ [path | (_, path, _) <- audits])
      let commands = [version, info, compiled, observed] ++ concat [cs | (_, _, _, cs, _) <- exports]
          artifacts = [binary, observedPath, oracle] ++ concat [paths | (_, _, _, _, paths) <- exports] ++
            concatMap commandArtifacts commands
      artifactHashes <- hashes root artifacts
      writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= names,
        "platform" .= Host.os, "supported" .= True, "installedArtifactsHashed" .= False,
        "strictAccepted" .= True, "runtimeVerified" .= False, "nativeRows" .= length rows,
        "stages" .= Map.fromList [(stage, modules) | (stage, modules, _, _, _) <- exports],
        "audits" .= Map.fromList [(stage, reports) | (stage, _, reports, _, _) <- exports],
        "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
      putStrLn "original-errno: five native signed CInt observations, genuine pre/post resetErrno Core"

-- Original installed process-identity declarations and independent native
-- observations. PID values are local to the process that observed them.
prepareOriginalProcessIdentity :: FilePath -> IO ()
prepareOriginalProcessIdentity root = do
  let output = "build/original-process-identity"
      coreSource = "t/fixtures/compiler/OriginalProcessIdentityAudit.hs"
      nativeSource = "t/fixtures/compiler/OriginalProcessIdentityNative.hs"
      names = ["originalGetPid", "originalGetEuid" :: String]
      execute = runLogged 180 root (output </> "logs")
      binary = output </> "native/oracle"
      observedPath = output </> "native/observations.txt"
      oracle = output </> "oracle.json"
      manifest = root </> output </> "manifest.json"
  createDirectoryIfMissing True (root </> output </> "native")
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  let sources = sort $ [coreSource, nativeSource, "thc.cabal", "t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/OriginalStdioFixtures.hs",
        "t/haskell-fixtures/InstalledCoreFixtures.hs", "src/driver/THC/Driver/Installed.hs",
        "src/driver/THC/Driver/Project.hs", "src/driver/THC/Driver/Wired.hs",
        "src/driver/THC/Driver/PackageNative.hs", "src/driver/cbits/target-layout.c",
        "src/runtime/THC/Exception.hs", "src/runtime/THC/Internal/Exception.hs",
        "bin/audit-core.py", "bin/core-capabilities.json", "src/main/java/thc/runtime/ProcessIdentity.java",
        "src/main/resources/thc/scalar-primop-signatures.json",
        "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
        ["bin" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"]
  inputHashes <- hashes root sources
  if not (Host.os `elem` ["linux", "darwin"] && sizeOf (0 :: CInt) == 4 &&
          sizeOf (0 :: CLong) == 8 && sizeOf (nullPtr :: Ptr ()) == 8)
    then do
      writeJson manifest $ object ["schema" .= (1 :: Int), "platform" .= Host.os,
        "supported" .= False, "reason" .= ("Original Linux/macOS LP64 process identity declarations only" :: String),
        "inputHashes" .= inputHashes, "artifactHashes" .= object []]
      putStrLn "original-process-identity: explicitly excluded on this platform"
    else do
      ghc <- maybe "ghc" id <$> lookupEnv "GHC"
      version <- execute "ghc-version" [] ghc ["--numeric-version"]
      unless (commandStdout version == "9.14.1\n") (die "Original process identity requires GHC 9.14.1")
      pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
      unit <- execute "unix-unit" [] pkg ["field", "unix", "id", "--simple-output"]
      owner <- case BSC.lines (commandStdout unit) of
        [value] -> pure (BSC.unpack value)
        _ -> die "Expected one installed Unix unit"
      unless (isOriginalUnixUnit owner)
        (die "Original process identity requires the pinned Unix unit")
      info <- execute "ghc-info" [] ghc ["--info"]
      case readMaybe (BSC.unpack (commandStdout info)) :: Maybe [(String, String)] of
        Just target | Just host <- lookup "Host platform" target,
                      not (null host), lookup "Target platform" target == Just host,
                      lookup "target word size" target == Just "8" -> pure ()
        _ -> die "Original process identity requires a native 64-bit GHC"
      compiled <- execute "native-build" [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
        "-package", "ghc-internal", "-package", "unix", "-it/fixtures/compiler", "-odir", root </> output </> "native",
        "-hidir", root </> output </> "native", nativeSource, "-o", root </> binary]
      old <- doesFileExist (root </> observedPath)
      when old (removeFile (root </> observedPath))
      observed <- execute "native-run" [] (root </> binary) [root </> observedPath]
      unless (BS.null (commandStdout observed) && BS.null (commandStderr observed))
        (die "Original process identity native oracle unexpectedly wrote stdout/stderr")
      text <- BSC.unpack <$> BS.readFile (root </> observedPath)
      values <- maybe (die "Malformed original process identity observations") pure (readMaybe text :: Maybe [Integer])
      unless (length values == 3)
        (die "Incomplete original process identity observations")
      let fields = ["pid", "parentPid", "euid"]
      writeJson (root </> oracle) (object (zipWith (.=) fields values))
      exports <- forM ["pre", "post"] $ \stage -> do
        let core = output </> stage </> "core"
            modules = [core </> "OriginalProcessIdentityAudit.cbd", core </> "THC.InterfaceClosure.cbd"]
            options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
              ["-fplugin-opt=THC.Plugin:closure=" ++ name | name <- names]
        exported <- execute (stage ++ "-export")
          [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> output </> stage </> "ghc"),
           ("THC_SOURCE_NOTES", "true")]
          "bin/export-core.sh" (["-package", "ghc-internal", "-package", "unix"] ++ options ++ [coreSource])
        audits <- forM names $ \name -> do
          let path = output </> stage </> name ++ ".audit.json"
          audited <- execute (stage ++ "-audit-" ++ name) [] "python3"
            (["bin/audit-core.py", "--entry", "main:OriginalProcessIdentityAudit." ++ name, "--output", path] ++ modules)
          pure (name, path, audited)
        pure (stage, modules, Map.fromList [(name, path) | (name, path, _) <- audits],
              exported : [command | (_, _, command) <- audits], modules ++ [path | (_, path, _) <- audits])
      runtimeExport <- execute "runtime-export"
        [("THC_CORE_OUT", root </> output </> "runtime-core"), ("THC_GHC_OUT", root </> output </> "runtime-ghc")]
        "bin/export-core.sh" ["-isrc/runtime", "-fplugin-opt=THC.Plugin:post-tidy", "src/runtime/THC/Exception.hs"]
      let runtimeModules = [output </> "runtime-core" </> name | name <- ["THC.Exception.cbd", "THC.Internal.Exception.cbd"]]
      installed <- prepareInstalledCoreUnits root output ["unix"]
      let commands = fixtureCommands installed ++ [version, info, unit, compiled, observed, runtimeExport] ++ concat [cs | (_, _, _, cs, _) <- exports]
          artifacts = fixtureArtifacts installed ++ runtimeModules ++ [binary, observedPath, oracle] ++ concat [paths | (_, _, _, _, paths) <- exports] ++
            concatMap commandArtifacts commands
      artifactHashes <- hashes root artifacts
      writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= names,
        "platform" .= Host.os, "supported" .= True, "installedArtifactsHashed" .= True,
        "packageManifest" .= fixturePackages installed, "runtimeModules" .= runtimeModules,
        "installedPublications" .= filter (\path -> takeExtension path /= ".zip" && path /= fixturePackages installed) (fixtureArtifacts installed),
        "installedBundles" .= filter ((== ".zip") . takeExtension) (fixtureArtifacts installed),
        "strictAccepted" .= True, "runtimeVerified" .= False, "nativeRows" .= (1 :: Int), "unixUnit" .= owner,
        "stages" .= Map.fromList [(stage, modules) | (stage, modules, _, _, _) <- exports],
        "audits" .= Map.fromList [(stage, reports) | (stage, _, reports, _, _) <- exports],
        "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
      putStrLn "original-process-identity: live native PID/parent PID/effective UID, two genuine pre/post Core roots"
