-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- Production of native observations only. Independent semantics, host ABI and
-- exact original FCall proofs are checked by the Kotlin fixture consumers.
module OriginalStdioFixtures (prepareOriginalStdio, prepareOriginalStdioRead, prepareOriginalFcntl) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=), toJSON)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import FixtureSupport
import Foreign.C.Types (CInt, CLong)
import Foreign.Ptr (Ptr, nullPtr)
import Foreign.Storable (sizeOf)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile, renameFile)
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
source = "compiler/test-fixtures/OriginalStdioAudit.hs"
driver = "compiler/test-fixtures/OriginalStdioAuditNative.hs"

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
  when present $ do
    digest <- hashFile manifest
    let previous = output </> "previous-manifests"
    createDirectoryIfMissing True previous
    renameFile manifest (previous </> digest ++ ".json")
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
      "-package","ghc-internal","-i" ++ (root </> "compiler/test-fixtures"),
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
        modules = [core </> "OriginalStdioAudit.json",core </> "THC.InterfaceClosure.json"]
        postTidy = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
        roots = ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",root </> stageDir </> "ghc"),("THC_SOURCE_NOTES","true")]
      "compiler/export.sh" (["-package","ghc-internal"] ++ postTidy ++ roots ++ [source])
    mapM_ (\path -> do
      exists <- doesFileExist (root </> path)
      unless exists (die ("Missing genuine GHC export: " ++ path))) modules
    audits <- if not requireSupported then pure [] else forM entries $ \entry -> do
      let path = stageDir </> entry ++ ".audit.json"
      command <- execute (stage ++ "-audit-" ++ entry) [] "python3"
        (["scripts/audit-core.py","--entry",entry,"--output",path] ++ modules)
      pure (entry,path,command)
    pure (stage,object ["modules" .= modules],Map.fromList [(entry,path) | (entry,path,_) <- audits],
          exported : [command | (_,_,command) <- audits],modules ++ [path | (_,path,_) <- audits])
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let commands = [version,info] ++ nativeCommands ++ concat [cs | (_,_,_,cs,_) <- exports]
      sources = sort $ [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/OriginalStdioFixtures.hs",
        "scripts/prepare-original-stdio.sh","scripts/audit-core.py","scripts/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json",
        "compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py"] ++
        ["compiler/THC" </> path | path <- plugin, takeExtension path == ".hs"] ++
        ["scripts" </> path | path <- scripts, "core_" `isPrefixOf` path, takeExtension path == ".py"]
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
     "claim" .= ("Native observations and genuine pre/post Core; Kotlin owns semantic, ABI and raw-proof validation" :: String)]
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
-- each child receives the seekable input file as fd0 before RTS initialization,
-- then the native harness opens and seeks its own fresh copy. Never start this
-- oracle with fd0 closed: an RTS descriptor could occupy it before Haskell main.
prepareOriginalStdioRead :: FilePath -> IO ()
prepareOriginalStdioRead root = do
  let dir = "build/original-stdio-read"
      native = dir </> "native"
      coreSource = "compiler/test-fixtures/OriginalStdioReadAudit.hs"
      nativeSource = "compiler/test-fixtures/OriginalStdioReadNative.hs"
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
    "-package","ghc-internal","-package","unix","-i" ++ (root </> "compiler/test-fixtures"),
    "-odir",root </> native,"-hidir",root </> native,nativeSource,"-o",root </> binary]
  rows <- forM (zip [0 :: Int ..] readRequests) $ \(index,(entry,arguments)) -> do
    let resultPath = dir </> "results" </> show index ++ ".txt"
        label = "native-" ++ replicate (3 - length (show index)) '0' ++ show index
    old <- doesFileExist (root </> resultPath)
    when old (removeFile (root </> resultPath))
    command <- runLoggedWithInput input 120 root (dir </> "logs") label [] (root </> binary)
      (entry : map show arguments ++ [root </> input,root </> resultPath])
    text <- BSC.unpack <$> BS.readFile (root </> resultPath)
    case lines text of
      [result,buffer] | Just number <- readInteger result,
                        length buffer == 32,
                        all (`elem` ("0123456789abcdef" :: String)) buffer ->
        pure (object ["entry" .= entry,"arguments" .= arguments,"result" .= number,
                      "bufferHex" .= buffer,"stdoutHex" .= hexBytes (commandStdout command),
                      "stderrHex" .= hexBytes (commandStderr command)],command,resultPath)
      _ -> die ("Malformed native original read result: " ++ resultPath)
  writeJson (root </> oracle) (toJSON [row | (row,_,_) <- rows])
  exports <- forM ["pre","post"] $ \stage -> do
    let stageDir = dir </> stage
        core = stageDir </> "core"
        modules = [core </> "OriginalStdioReadAudit.json",core </> "THC.InterfaceClosure.json"]
        postTidy = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
        roots = ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- readEntries]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",root </> stageDir </> "ghc"),
       ("THC_SOURCE_NOTES","true")]
      "compiler/export.sh" (["-package","ghc-internal"] ++ postTidy ++ roots ++ [coreSource])
    mapM_ (\path -> do
      exists <- doesFileExist (root </> path)
      unless exists (die ("Missing genuine original read Core: " ++ path))) modules
    audits <- forM readEntries $ \entry -> do
      let path = stageDir </> entry ++ ".audit.json"
      command <- execute (stage ++ "-audit-" ++ entry) [] "python3"
        (["scripts/audit-core.py","--entry",entry,"--output",path] ++ modules)
      pure (entry,path,command)
    pure (stage,modules,Map.fromList [(entry,path) | (entry,path,_) <- audits],
          exported : [command | (_,_,command) <- audits],
          modules ++ [path | (_,path,_) <- audits])
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let commands = [version,compiled] ++ [command | (_,command,_) <- rows] ++
        concat [items | (_,_,_,items,_) <- exports]
      sources = sort $ [coreSource,nativeSource,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/OriginalStdioFixtures.hs",
        "scripts/audit-core.py","scripts/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json",
        "compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py"] ++
        ["compiler/THC" </> path | path <- plugin, takeExtension path == ".hs"] ++
        ["scripts" </> path | path <- scripts, "core_" `isPrefixOf` path, takeExtension path == ".py"]
      artifacts = [input,oracle,binary] ++ [path | (_,_,path) <- rows] ++
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

-- One composite original-declaration export/oracle; the JVM consumer checks
-- exact flag values, shared descriptor state and each compiled operation.
prepareOriginalFcntl :: FilePath -> IO ()
prepareOriginalFcntl root
  | Host.os /= "linux" || Host.arch /= "x86_64" || sizeOf (0 :: CLong) /= 8 = do
      createDirectoryIfMissing True (root </> "build/original-fcntl")
      inputHashes <- fcntlSources root >>= hashes root
      writeJson (root </> "build/original-fcntl/manifest.json") $ object
        ["schema" .= (1 :: Int), "platform" .= Host.os, "supported" .= False,
         "reason" .= ("Original Linux x86_64 LP64 fcntl declarations only" :: String),
         "inputHashes" .= inputHashes, "artifactHashes" .= object []]
      putStrLn "original-fcntl: explicitly excluded on this platform"
  | otherwise = prepareNativeFcntl root

fcntlSources :: FilePath -> IO [FilePath]
fcntlSources root = do
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  pure $ sort $ ["compiler/test-fixtures/OriginalFcntlAudit.hs", "compiler/test-fixtures/OriginalFcntlNative.hs",
    "thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
    "test/haskell-fixtures/OriginalStdioFixtures.hs", "scripts/audit-core.py", "scripts/core-capabilities.json",
    "src/main/resources/thc/scalar-primop-signatures.json", "compiler/build.sh", "compiler/export.sh",
    "compiler/toolchain.sh", "compiler/plugin.py"] ++
    ["compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
    ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"]

prepareNativeFcntl :: FilePath -> IO ()
prepareNativeFcntl root = do
  let output = "build/original-fcntl"
      coreSource = "compiler/test-fixtures/OriginalFcntlAudit.hs"
      nativeSource = "compiler/test-fixtures/OriginalFcntlNative.hs"
      names = ["originalAppend", "originalCreat", "originalNoctty", "originalNonblock", "originalRdonly",
               "originalRdwr", "originalWronly", "originalGetfl", "originalSetfl", "originalGetFlags", "originalSetFlags"]
      execute = runLogged 180 root (output </> "logs")
      binary = output </> "native/oracle"
      oracle = output </> "oracle.json"
      manifest = root </> output </> "manifest.json"
  createDirectoryIfMissing True (root </> output </> "native")
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original fcntl requires GHC 9.14.1")
  info <- execute "ghc-info" [] ghc ["--info"]
  case readMaybe (BSC.unpack (commandStdout info)) :: Maybe [(String, String)] of
    Just target | lookup "Host platform" target == Just "x86_64-unknown-linux",
                  lookup "Target platform" target == lookup "Host platform" target,
                  lookup "target word size" target == Just "8" -> pure ()
    _ -> die "Original fcntl requires native Linux x86_64 GHC"
  compiled <- execute "native-build" [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint",
    "-package", "ghc-internal", "-package", "unix", "-icompiler/test-fixtures",
    "-odir", root </> output </> "native", "-hidir", root </> output </> "native", nativeSource, "-o", root </> binary]
  observed <- execute "native-run" [] (root </> binary) [root </> output </> "native/private-file"]
  (constants, rows, invalid) <- maybe (die "Malformed original fcntl observations") pure
    (readMaybe (BSC.unpack (commandStdout observed)) :: Maybe ([Integer], [[Integer]], [Integer]))
  unless (length constants == 9 && length rows == 4 && all ((== 3) . length) rows && length invalid == 2)
    (die "Incomplete original fcntl observations")
  writeJson (root </> oracle) $ object ["constants" .= constants, "rows" .= rows, "invalid" .= invalid]
  exports <- forM ["pre", "post"] $ \stage -> do
    let core = output </> stage </> "core"
        modules = [core </> "OriginalFcntlAudit.json", core </> "THC.InterfaceClosure.json"]
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ name | name <- names]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> output </> stage </> "ghc")]
      "compiler/export.sh" (["-package", "ghc-internal"] ++ options ++ [coreSource])
    audits <- forM names $ \name -> do
      let path = output </> stage </> name ++ ".audit.json"
      audited <- execute (stage ++ "-audit-" ++ name) [] "python3"
        (["scripts/audit-core.py", "--entry", name, "--output", path] ++ modules)
      pure (path, audited)
    pure (exported : map snd audits, modules ++ map fst audits)
  let commands = [version, info, compiled, observed] ++ concatMap fst exports
      artifacts = [binary, oracle] ++ concatMap snd exports ++ concatMap commandArtifacts commands
  inputHashes <- fcntlSources root >>= hashes root
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= names,
    "platform" .= Host.os, "supported" .= True, "installedArtifactsHashed" .= False,
    "strictAccepted" .= True, "runtimeVerified" .= False, "nativeRows" .= length rows,
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "original-fcntl: nine genuine constants, four native shared-status rows and eleven pre/post Core roots"
