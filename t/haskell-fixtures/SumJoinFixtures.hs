-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : SumJoinFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for sum join.
module SumJoinFixtures (prepareSumJoins) where

import Control.Monad (forM, forM_, unless)
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
import THC.Compact.Module (readModuleValue)

walk :: Value -> [Value]
walk value = value : case value of
  Object fields -> concatMap walk (KeyMap.elems fields)
  Array fields -> concatMap walk (toList fields)
  _ -> []

prepareSumJoins :: FilePath -> IO ()
prepareSumJoins root = do
  let directory = "build/sum-join"
      output = root </> directory
      source = "t/fixtures/compiler/SumJoinAudit.hs"
      driver = "t/fixtures/compiler/SumJoinAuditNative.hs"
      entries = ["forwardCase", "recursiveCase", "nestedCase", "stateForwardCase", "stateRecursiveCase",
                 "tupleForwardCase", "sumForwardCase"]
      logs = directory </> "commands"
      native = directory </> "native"
  createDirectoryIfMissing True (root </> native)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- runLogged 30 root logs "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Sum joins require GHC 9.14.1")
  compiled <- runLogged 120 root logs "native-build" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-it/fixtures/compiler",
     "-odir", native, "-hidir", native, driver, "-o", native </> "oracle"]
  observations <- runLogged 30 root logs "native-run" [] (output </> "native/oracle") []
  unless (length (BSC.lines (commandStdout observations)) == 42) (die "Unexpected sum-join row count")
  BS.writeFile (output </> "oracle.tsv") (commandStdout observations)
  artifacts <- fmap concat $ forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        modulePath = core </> "SumJoinAudit.cbd"
    exported <- runLogged 180 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "bin/export-core.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source])
    bytes <- BS.readFile (root </> modulePath)
    value <- either die pure (readModuleValue bytes)
    let nodes = walk value
        joins = [fields | Object fields <- nodes,
                          KeyMap.member "joinValueArity" fields]
    unless (length joins >= 6 && all (\fields -> case KeyMap.lookup "joinResultRep" fields of
      Just (Object proof) -> KeyMap.lookup "aggregate" proof == Just (String "unboxed-sum")
      _ -> False) joins) (die "Sum-join fixture lost genuine optimized sum joins")
    let states = [() | Array node <- nodes,
                      String "app" : Array function : Array arguments : _ <- [toList node],
                      String "lam" : Array parameters : _ <- [toList function],
                      [Object parameter] <- [toList parameters],
                      Just (Object proof) <- [KeyMap.lookup "rep" parameter],
                      KeyMap.lookup "kind" proof == Just (String "void"),
                      [Array argument] <- [toList arguments],
                      String "void" : _ <- [toList argument]]
    unless (length states >= 2) (die "Sum-join fixture lost its genuine runRW# state lambdas")
    forM_ [("tupleForward", "unboxed-tuple"), ("sumForward", "unboxed-sum")] $ \(name, aggregate) -> do
      let preserved = [() | Object binding <- nodes,
                            KeyMap.lookup "id" binding == Just (String ("main:SumJoinAudit." <> name)),
                            Just (Array lambda) <- [KeyMap.lookup "expr" binding],
                            String "lam" : _ : Array body : Object metadata : _ <- [toList lambda],
                            Just (Object result) <- [KeyMap.lookup "resultRep" metadata],
                            KeyMap.lookup "aggregate" result == Just (String aggregate),
                            Just (Array reps) <- [KeyMap.lookup "primReps" result],
                            length reps == 3,
                            String "case" : Array scrutinee : _ : Array arms : _ <- [toList body],
                            length arms == 2,
                            Object scrutineeMetadata : _ <- [reverse (toList scrutinee)],
                            Just (Object proof) <- [KeyMap.lookup "rep" scrutineeMetadata],
                            KeyMap.lookup "aggregate" proof == Just (String "unboxed-sum")]
      unless (length preserved == 1) (die "Sum-case fixture lost its genuine three-slot result boundary")
    let report = directory </> stage </> "audit.json"
    audited <- runLogged 30 root logs (stage ++ "-audit") [] "python3"
      (["bin/audit-core.py", "--output", report] ++ concatMap (\entry -> ["--entry", "main:SumJoinAudit." ++ entry]) entries ++ [modulePath])
    auditBytes <- BS.readFile (root </> report)
    case decodeStrict' auditBytes of
      Just (Object value) | KeyMap.lookup "accepted" value == Just (Bool True) -> pure ()
      _ -> die ("Strict sum-join audit rejected " ++ stage)
    pure (modulePath : report : commandArtifacts exported ++ commandArtifacts audited)
  sourceHashes <- hashes root [source, driver, "t/haskell-fixtures/SumJoinFixtures.hs",
    "bin/audit-core.py", "bin/core-capabilities.json", "src/compiler/THC/Plugin.hs", "src/compiler/THC/Wired.hs"]
  artifactHashes <- hashes root ((directory </> "oracle.tsv") : artifacts ++ concatMap commandArtifacts [version, compiled, observations])
  writeJson (output </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes]
  putStrLn "sum-join: 42 native observations and exact pre/post-Tidy sum-join audits"
