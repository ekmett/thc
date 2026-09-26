-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module SumJoinFixtures (prepareSumJoins) where

import Control.Monad (forM, unless)
import Data.Aeson (Value(..), decodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.Foldable (toList)
import FixtureSupport (CommandResult(..), hashes, runLogged, writeJson)
import System.Directory (createDirectoryIfMissing)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))

walk :: Value -> [Value]
walk value = value : case value of
  Object fields -> concatMap walk (KeyMap.elems fields)
  Array fields -> concatMap walk (toList fields)
  _ -> []

prepareSumJoins :: FilePath -> IO ()
prepareSumJoins root = do
  let directory = "build/sum-join"
      output = root </> directory
      source = "compiler/test-fixtures/SumJoinAudit.hs"
      driver = "compiler/test-fixtures/SumJoinAuditNative.hs"
      entries = ["forwardCase", "recursiveCase", "nestedCase"]
      logs = directory </> "commands"
      native = directory </> "native"
  createDirectoryIfMissing True (root </> native)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- runLogged 30 root logs "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Sum joins require GHC 9.14.1")
  compiled <- runLogged 120 root logs "native-build" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-icompiler/test-fixtures",
     "-odir", native, "-hidir", native, driver, "-o", native </> "oracle"]
  observations <- runLogged 30 root logs "native-run" [] (output </> "native/oracle") []
  unless (length (BSC.lines (commandStdout observations)) == 18) (die "Unexpected sum-join row count")
  BS.writeFile (output </> "oracle.tsv") (commandStdout observations)
  artifacts <- fmap concat $ forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        modulePath = core </> "SumJoinAudit.json"
    exported <- runLogged 180 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source])
    bytes <- BS.readFile (root </> modulePath)
    let joins = [fields | Just value <- [decodeStrict' bytes], Object fields <- walk value,
                          KeyMap.member "joinValueArity" fields]
    unless (length joins >= 4 && all (\fields -> case KeyMap.lookup "joinResultRep" fields of
      Just (Object proof) -> KeyMap.lookup "aggregate" proof == Just (String "unboxed-sum")
      _ -> False) joins) (die "Sum-join fixture lost genuine optimized sum joins")
    let report = directory </> stage </> "audit.json"
    audited <- runLogged 30 root logs (stage ++ "-audit") [] "python3"
      (["scripts/audit-core.py", "--output", report] ++ concatMap (\entry -> ["--entry", entry]) entries ++ [modulePath])
    auditBytes <- BS.readFile (root </> report)
    case decodeStrict' auditBytes of
      Just (Object value) | KeyMap.lookup "accepted" value == Just (Bool True) -> pure ()
      _ -> die ("Strict sum-join audit rejected " ++ stage)
    pure (modulePath : report : commandArtifacts exported ++ commandArtifacts audited)
  sourceHashes <- hashes root [source, driver, "test/haskell-fixtures/SumJoinFixtures.hs",
    "scripts/audit-core.py", "scripts/core-capabilities.json", "compiler/THC/Plugin.hs"]
  artifactHashes <- hashes root ((directory </> "oracle.tsv") : artifacts ++ concatMap commandArtifacts [version, compiled, observations])
  writeJson (output </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes]
  putStrLn "sum-join: 18 native observations and exact pre/post-Tidy sum-join audits"
