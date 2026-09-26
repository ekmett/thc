-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module DelimitedContinuationsFixtures (prepareDelimitedContinuations) where

import Control.Monad (forM, unless)
import Data.Aeson (Value(..), decodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import FixtureSupport (CommandResult(..), hashes, runLogged, writeJson)
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

prepareDelimitedContinuations :: FilePath -> IO ()
prepareDelimitedContinuations root = do
  let directory = "build/delimited-continuations"
      output = root </> directory
      source = "examples/DelimitedContinuations.hs"
      driver = "compiler/test-fixtures/DelimitedContinuationsNative.hs"
      entries = ["promptPure", "abortSuffix", "resumeTwice", "nestedPrompts", "sameTagNearest", "capturedCatch", "capturedMask", "escapedResume", "ambientMask", "resumedTail", "resumedJoin", "resumedScalar", "recapturedMask"]
      stages = ["pre", "post"]
      logs = directory </> "commands"
      native = directory </> "native"
  createDirectoryIfMissing True (root </> native)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- runLogged 30 root logs "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Delimited continuations require GHC 9.14.1")
  compiled <- runLogged 120 root logs "native-build" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-iexamples",
     "-odir", native, "-hidir", native, driver, "-o", native </> "oracle"]
  observations <- runLogged 30 root logs "native-run" [] (output </> "native/oracle") []
  let values = map (read . BSC.unpack) (BSC.lines (commandStdout observations)) :: [Integer]
  unless (length values == 39) (die "Unexpected delimited-continuation native row count")
  artifacts <- fmap concat $ forM stages $ \stage -> do
    let core = directory </> stage </> "core"
    exported <- runLogged 180 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source])
    audits <- fmap concat $ forM entries $ \entry -> do
      let report = directory </> stage </> (entry ++ "-audit.json")
      audited <- runLogged 30 root logs (stage ++ "-audit-" ++ entry) [] "python3"
        ["scripts/audit-core.py", "--entry", entry, "--output", report, core </> "DelimitedContinuations.json"]
      bytes <- BS.readFile (root </> report)
      case decodeStrict' bytes of
        Just (Object value) | KeyMap.lookup "accepted" value == Just (Bool True),
          KeyMap.lookup "issues" value == Just (Array mempty),
          KeyMap.lookup "missingGlobals" value == Just (Array mempty) -> pure ()
        _ -> die ("Strict continuation audit rejected " ++ entry)
      pure (report : commandArtifacts audited)
    pure ((core </> "DelimitedContinuations.json") : commandArtifacts exported ++ audits)
  plugins <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let sources = [source, driver, "thc.cabal", "test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/DelimitedContinuationsFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json",
        "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py"] ++
        ["compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root (artifacts ++ concatMap commandArtifacts [version, compiled, observations])
  writeJson (output </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "stages" .= stages, "arguments" .= ([-2,0,7] :: [Int]), "native" .= values,
     "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes]
  putStrLn "delimited-continuations: 39 native observations and original pre/post Core audits"
