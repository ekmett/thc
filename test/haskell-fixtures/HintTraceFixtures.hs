-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module HintTraceFixtures (prepareHintTrace) where
import Control.Monad (forM_, unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString as BS
import Data.List (isPrefixOf, sort)
import FixtureSupport (hashes, run, runWithTimeout, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

prepareHintTrace :: FilePath -> IO ()
prepareHintTrace root = do
  let directory = "build/hint-trace"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "compiler/test-fixtures/HintTraceAudit.hs"
      driver = "compiler/test-fixtures/HintTraceNative.hs"
      entries = ["hints", "traces", "event", "marker", "binary", "addressHints"] :: [String]
      stages = ["pre", "post"] :: [String]
  createDirectoryIfMissing True output
  old <- doesFileExist manifest
  when old (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "Hint/trace fixtures require GHC 9.14.1")
  forM_ stages $ \stage -> do
    let core = directory </> stage </> "core"
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source]) ""
    forM_ entries $ \entry -> do
      _ <- run root [] "python3" ["scripts/audit-core.py", "--entry", entry,
        "--output", directory </> stage </> entry ++ ".audit.json", core </> "HintTraceAudit.json"] ""
      pure ()
  let native = output </> "native"
      eventlog = native </> "oracle.eventlog"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make", "-O2", "-eventlog", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ (root </> "compiler/test-fixtures"), "-odir", native, "-hidir", native,
    root </> driver, "-o", native </> "oracle"] ""
  observations <- runWithTimeout (Just 30000000) root [] (native </> "oracle")
    ["+RTS", "-l", "-ol" ++ eventlog, "-RTS"] ""
  let expected = unlines [name ++ "\t" ++ show x ++ "\t" ++ show (x + delta)
        | x <- [-3,0,1,37,999 :: Int], (name,delta) <- [("hints",1),("traces",19)]]
  unless (observations == expected) (die "Native hint/trace results disagree")
  emitted <- BS.readFile eventlog
  unless (all (`BS.isInfixOf` emitted) ["hint-trace-event", "hint-trace-marker", BS.pack [65,0,66,0]]) $
    die "Native GHC did not emit the expected user-event payloads"
  writeFile (output </> "oracle.tsv") observations
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  inputHashes <- hashes root (sort $ [source, driver, "thc.cabal", "test/haskell-fixtures/Main.hs",
    "test/haskell-fixtures/HintTraceFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
    "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py",
    "scripts/audit-core.py", "scripts/core-capabilities.json"] ++
    ["compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
    ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"])
  artifactHashes <- hashes root ([directory </> "oracle.tsv", directory </> "native/oracle",
    directory </> "native/oracle.eventlog"] ++
    [directory </> stage </> suffix | stage <- stages,
      suffix <- "core/HintTraceAudit.json" : [entry ++ ".audit.json" | entry <- entries]])
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "stages" .= stages, "nativeRows" .= (10 :: Int),
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "hint-trace: 19 primops, strict pre/post Core, 10 native observations and native event payloads"
