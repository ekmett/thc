-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module OriginalTermiosFixtures (prepareOriginalTermios) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, sort)
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import System.Info (os, arch)
import Text.Read (readMaybe)

prepareOriginalTermios :: FilePath -> IO ()
prepareOriginalTermios root
  | os /= "linux" || arch /= "x86_64" = do
      createDirectoryIfMissing True (root </> directory)
      inputHashes <- fixtureSources root >>= hashes root
      writeJson (root </> directory </> "manifest.json") $ object
        ["schema" .= (1 :: Int), "platform" .= os, "supported" .= False,
         "reason" .= ("Original Linux x86_64 termios declarations only" :: String),
         "inputHashes" .= inputHashes, "artifactHashes" .= object []]
      putStrLn "original-termios: explicitly excluded on this platform"
  | otherwise = prepareLinux root

directory :: FilePath
directory = "build/original-termios"

fixtureSources :: FilePath -> IO [FilePath]
fixtureSources root = do
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  pure $ sort $ ["compiler/test-fixtures/OriginalTermiosAudit.hs", "compiler/test-fixtures/OriginalTermiosNative.hs",
    "thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
    "test/haskell-fixtures/OriginalTermiosFixtures.hs", "scripts/audit-core.py", "scripts/core-capabilities.json",
    "src/main/resources/thc/scalar-primop-signatures.json", "compiler/export.sh", "compiler/build.sh",
    "compiler/toolchain.sh", "compiler/plugin.py"] ++
    ["compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
    ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"]

prepareLinux :: FilePath -> IO ()
prepareLinux root = do
  let entries = ["originalTermiosSize", "originalEcho", "originalIcanon", "originalVmin", "originalVtime",
        "originalTcsanow", "originalLflag", "originalPokeLflag", "originalCC"]
      execute = runLogged 180 root (directory </> "logs")
  createDirectoryIfMissing True (root </> directory </> "native")
  let manifest = root </> directory </> "manifest.json"
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original termios requires GHC 9.14.1")
  info <- execute "ghc-info" [] ghc ["--info"]
  case readMaybe (BSC.unpack (commandStdout info)) :: Maybe [(String,String)] of
    Just target | lookup "Host platform" target == Just "x86_64-unknown-linux",
                  lookup "Target platform" target == lookup "Host platform" target,
                  lookup "target word size" target == Just "8" -> pure ()
    _ -> die "Original termios requires native Linux x86_64 GHC"
  let binary = directory </> "native/oracle"
  compiled <- execute "native-build" [] ghc ["--make", "-j2", "-O2", "-fforce-recomp", "-dcore-lint",
    "-package", "ghc-internal", "-icompiler/test-fixtures", "-odir", root </> directory </> "native",
    "-hidir", root </> directory </> "native", "compiler/test-fixtures/OriginalTermiosNative.hs", "-o", root </> binary]
  observed <- execute "native-run" [] (root </> binary) []
  (constants, rows) <- maybe (die "Malformed original termios observations") pure
    (readMaybe (BSC.unpack (commandStdout observed)) :: Maybe ([Integer], [(Integer,Integer,Integer,Integer,[Int])]))
  let oracle = directory </> "oracle.json"
  writeJson (root </> oracle) $ object ["constants" .= constants, "rows" .= rows]
  exports <- forM ["pre","post"] $ \stage -> do
    let core = directory </> stage </> "core"
        modules = [core </> "OriginalTermiosAudit.json", core </> "THC.InterfaceClosure.json"]
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> directory </> stage </> "ghc")]
      "compiler/export.sh" (["-package", "ghc-internal"] ++ options ++ ["compiler/test-fixtures/OriginalTermiosAudit.hs"])
    mapM_ (\path -> doesFileExist (root </> path) >>= \present -> unless present (die ("Missing original Core: " ++ path))) modules
    audits <- forM entries $ \entry -> do
      let path = directory </> stage </> entry ++ ".audit.json"
      command <- execute (stage ++ "-audit-" ++ entry) [] "python3"
        (["scripts/audit-core.py", "--entry", entry, "--output", path] ++ modules)
      pure (path,command)
    pure (modules,exported,audits)
  let commands = [version,info,compiled,observed] ++ concat [exported : map snd audits | (_,exported,audits) <- exports]
      artifacts = [binary,oracle] ++ concat [modules ++ map fst audits | (modules,_,audits) <- exports] ++ concatMap commandArtifacts commands
  inputHashes <- fixtureSources root >>= hashes root
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "platform" .= os, "supported" .= True,
     "oracle" .= oracle, "entries" .= entries, "strictAccepted" .= True,
     "runtimeVerified" .= False, "installedArtifactsHashed" .= False, "nativeRows" .= length rows, "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "original-termios: six native images and nine strict pre/post original helpers prepared"
