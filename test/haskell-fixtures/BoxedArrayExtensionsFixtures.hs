-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module BoxedArrayExtensionsFixtures (prepareBoxedArrayExtensions) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import qualified Data.Map.Strict as Map
import Data.List (sort)
import FixtureSupport (CommandResult(..), hashes, runLogged, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, renameFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

prepareBoxedArrayExtensions :: FilePath -> IO ()
prepareBoxedArrayExtensions root = do
  let directory = "build/boxed-array-extensions"
      manifest = root </> directory </> "manifest.json"
      source = "compiler/test-fixtures/BoxedArrayExtensionsAudit.hs"
      driver = "compiler/test-fixtures/BoxedArrayExtensionsNative.hs"
      entries = ["boxedExtSizes","boxedExtClone","boxedExtCopy","boxedExtMove","boxedExtThaw","boxedExtLazy"] :: [String]
  createDirectoryIfMissing True (root </> directory)
  previous <- listDirectory (root </> directory)
  let available n = if "run-" ++ show n `elem` previous then available (n + 1) else "run-" ++ show n
      attempt = directory </> available (1 :: Int)
      run label env program args = runLogged 180 root (attempt </> "logs") label env program args
  createDirectoryIfMissing True (root </> attempt)
  exists <- doesFileExist manifest
  when exists (renameFile manifest (root </> attempt </> "previous-manifest.json"))
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run "ghc-version" [] ghc ["--numeric-version"]
  unless (BS.words (commandStdout version) == ["9.14.1"]) (die "Boxed array fixtures require GHC 9.14.1")
  info <- run "ghc-info" [] ghc ["--info"]
  settings <- maybe (die "Malformed GHC platform information") pure
    (readMaybe (BS.unpack (commandStdout info)) :: Maybe [(String,String)])
  unless (lookup "target word size" settings == Just "8" &&
    lookup "Host platform" settings /= Nothing && lookup "Host platform" settings == lookup "Target platform" settings)
    (die "Boxed array fixtures require a native 64-bit GHC target")
  stages <- forM ["pre","post"] $ \stage -> do
    let core = attempt </> stage ++ "-core"
    exported <- run (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> attempt </> stage ++ "-ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        map ("-fplugin-opt=THC.Plugin:closure=" ++) entries ++ [source])
    modules <- map (core </>) . filter ((== ".json") . takeExtension) . sort <$> listDirectory (root </> core)
    unless (core </> "BoxedArrayExtensionsAudit.json" `elem` modules) (die "Missing boxed-array Core export")
    audits <- forM entries $ \entry -> do
      let output = attempt </> stage ++ "-" ++ entry ++ ".audit.json"
      audited <- run (stage ++ "-audit-" ++ entry) [] "python3"
        (["scripts/audit-core.py"] ++ modules ++ ["--entry",entry,"--output",output])
      pure (output,audited)
    pure (stage,modules,exported,audits)
  let native = attempt </> "native"
      executable = native </> "boxed-array-extensions-oracle"
  createDirectoryIfMissing True (root </> native)
  compiled <- run "native-compile" [] ghc
    ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint",
     "-i" ++ root </> "compiler/test-fixtures","-odir",root </> native,"-hidir",root </> native,
     root </> driver,"-o",root </> executable]
  observed <- run "native-oracle" [] (root </> executable) []
  compilerSources <- map ("compiler/THC" </>) . filter ((== ".hs") . takeExtension) <$> listDirectory (root </> "compiler/THC")
  auditSources <- map ("scripts" </>) . filter (\name -> take 5 name == "core_" && takeExtension name == ".py") <$> listDirectory (root </> "scripts")
  let commands = [version,info] ++ concat [[exported] ++ map snd audits | (_,_,exported,audits) <- stages] ++ [compiled,observed]
      artifacts = [executable] ++ concatMap commandArtifacts commands ++
        concat [modules ++ map fst audits | (_,modules,_,audits) <- stages]
      sources = [source,driver,"test/haskell-fixtures/BoxedArrayExtensionsFixtures.hs","test/haskell-fixtures/FixtureSupport.hs",
        "test/haskell-fixtures/Main.hs","thc.cabal","compiler/export.sh","compiler/build.sh","compiler/toolchain.sh",
        "compiler/plugin.py","scripts/audit-core.py","scripts/core-capabilities.json","src/main/resources/thc/scalar-primop-signatures.json"]
        ++ compilerSources ++ auditSources
  inputs <- hashes root sources
  outputs <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),"wordBits" .= (64 :: Int),
    "entries" .= entries,"stages" .= Map.fromList [(stage,modules) | (stage,modules,_,_) <- stages],
    "audits" .= Map.fromList [(stage,map fst audits) | (stage,_,_,audits) <- stages],
    "oracle" .= (attempt </> "logs/native-oracle.stdout"),"nativeRows" .= length (BS.lines (commandStdout observed)),
    "inputHashes" .= inputs,"artifactHashes" .= outputs,"commands" .= map commandRecord commands,
    "installedArtifactsHashed" .= False,"runtimeVerified" .= False,
    "limit" .= ("Native valid ranges only. Managed invalid ranges and immutable-source alias rejection are JVM-only checks." :: String)]
  putStrLn ("Prepared boxed-array extensions: " ++ manifest)
