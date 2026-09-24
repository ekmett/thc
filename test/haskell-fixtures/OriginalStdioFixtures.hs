-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- Production of native observations only. Independent semantics, host ABI and
-- exact original FCall proofs are checked by the Kotlin fixture consumers.
module OriginalStdioFixtures (prepareOriginalStdio) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=), toJSON)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile, renameFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

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
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original stdio requires pinned GHC 9.14.1")
  info <- execute "ghc-info" [] ghc ["--info"]
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
