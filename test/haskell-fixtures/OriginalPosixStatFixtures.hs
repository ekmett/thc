-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module OriginalPosixStatFixtures (prepareOriginalPosixStat) where

import Control.Monad (forM, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BSC
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import System.Info (os)
import Text.Read (readMaybe)

prepareOriginalPosixStat :: FilePath -> IO ()
prepareOriginalPosixStat root
  | os /= "linux" = do
      let directory = "build/original-posix-stat"
      createDirectoryIfMissing True (root </> directory)
      inputHashes <- hashes root fixtureSources
      writeJson (root </> directory </> "manifest.json") $ object
        ["schema" .= (1 :: Int), "platform" .= os, "supported" .= False,
         "reason" .= ("Only original Linux stat scalar declarations have native/Core proof" :: String),
         "inputHashes" .= inputHashes, "artifactHashes" .= object []]
      putStrLn "original-posix-stat: explicitly excluded on this platform (Linux-only proof)"
  | otherwise = prepareLinux root

fixtureSources :: [FilePath]
fixtureSources = ["compiler/test-fixtures/OriginalPosixStatAudit.hs", "compiler/test-fixtures/OriginalPosixStatNative.hs",
  "thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
  "test/haskell-fixtures/OriginalPosixStatFixtures.hs", "scripts/audit-core.py", "scripts/core_original_foreign.py",
  "scripts/core-capabilities.json", "compiler/export.sh", "compiler/build.sh", "compiler/THC/Plugin.hs"]

prepareLinux :: FilePath -> IO ()
prepareLinux root = do
  let directory = "build/original-posix-stat"
      source = "compiler/test-fixtures/OriginalPosixStatAudit.hs"
      driver = "compiler/test-fixtures/OriginalPosixStatNative.hs"
      entries = ["originalStatSize", "originalStatDev", "originalStatIno", "originalStatMode", "originalStatLength", "originalStatTypes"]
      execute = runLogged 180 root (directory </> "logs")
  createDirectoryIfMissing True (root </> directory </> "native")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original stat requires GHC 9.14.1")
  info <- execute "ghc-info" [] ghc ["--info"]
  case readMaybe (BSC.unpack (commandStdout info)) :: Maybe [(String,String)] of
    Just target | Just host <- lookup "Host platform" target,
                  not (null host), lookup "Target platform" target == Just host,
                  lookup "target word size" target == Just "8" -> pure ()
    _ -> die "Original stat requires a native 64-bit GHC"
  let binary = directory </> "native/oracle"
  compiled <- execute "native-build" [] ghc ["--make", "-j2", "-O2", "-fforce-recomp", "-dcore-lint",
    "-package", "ghc-internal", "-icompiler/test-fixtures", "-odir", root </> directory </> "native",
    "-hidir", root </> directory </> "native", driver, "-o", root </> binary]
  observed <- execute "native-run" [] (root </> binary) [root </> directory </> "native"]
  (size,images,modes) <- maybe (die "Malformed original stat observations") pure
    (readMaybe (BSC.unpack (commandStdout observed)) :: Maybe (Integer, [([Int],[Integer])], [(Integer,Integer)]))
  let oracle = directory </> "oracle.json"
  writeJson (root </> oracle) $ object ["size" .= size, "images" .= images, "modes" .= modes]
  exports <- forM ["pre","post"] $ \stage -> do
    let core = directory </> stage </> "core"
        modules = [core </> "OriginalPosixStatAudit.json",core </> "THC.InterfaceClosure.json"]
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> directory </> stage </> "ghc")]
      "compiler/export.sh" (["-package", "ghc-internal"] ++ options ++ [source])
    mapM_ (\path -> do
      present <- doesFileExist (root </> path)
      unless present (die ("Missing original Core export: " ++ path))) modules
    audits <- forM entries $ \entry -> do
      let path = directory </> stage </> entry ++ ".audit.json"
      command <- execute (stage ++ "-audit-" ++ entry) [] "python3"
        (["scripts/audit-core.py", "--entry", entry, "--output", path] ++ modules)
      pure (path,command)
    pure (modules,exported,audits)
  let commands = [version,info,compiled,observed] ++ concat [exported : map snd audits | (_,exported,audits) <- exports]
      artifacts = [binary,oracle,directory </> "native/sample.bin"] ++ concat [modules ++ map fst audits | (modules,_,audits) <- exports] ++ concatMap commandArtifacts commands
  inputHashes <- hashes root fixtureSources
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "platform" .= os, "supported" .= True,
     "oracle" .= oracle, "stages" .= (["pre","post"] :: [String]),
     "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "original-posix-stat: native stat images, predicates and strict pre/post originals prepared"
