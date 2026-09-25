-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module RtsDiagnosticFixtures (prepareRtsDiagnostics) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as C8
import Data.Word (Word8)
import FixtureSupport hiding (run)
import System.Directory (createDirectoryIfMissing, doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))

prepareRtsDiagnostics :: FilePath -> IO ()
prepareRtsDiagnostics root = do
  let directory = "build/rts-diagnostics"
      source = "compiler/test-fixtures/RtsDiagnosticsNative.hs"
      native = directory </> "native"
      binary = native </> "oracle"
      manifest = directory </> "manifest.json"
      oracle = directory </> "oracle.json"
      run = runLogged 120 root (directory </> "logs")
      cases :: [(String, [Word8], Int)]
      cases = [("ascii",[97,108,112,104,97],0), ("empty",[],0),
        ("bytes",[88,89,255,128,37,10,0,90],2), ("nul",[97,0,98],0), ("newline",[97,10],0)]
  createDirectoryIfMissing True (root </> native)
  present <- doesFileExist (root </> manifest)
  when present (removeFile (root </> manifest))
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run "version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Diagnostic oracle requires GHC 9.14.1")
  compiled <- run "native-build" [] ghc ["--make", "-O2", "-dynamic", "-fforce-recomp", "-Wall", "-Werror",
    "-dcore-lint", "-dstg-lint", "-package", "ghc-internal", "-odir", root </> native,
    "-hidir", root </> native, source, "-o", root </> binary]
  rows <- forM (map (\(label,_,_) -> label) cases ++ ["stack","heap"]) $ \label -> do
    command <- run label [] (root </> binary) [label]
    prefix <- case C8.lines (commandStdout command) of
      [name,"returned"] -> pure (name <> ": ")
      _ -> die "Diagnostic native call failed to return"
    let output = commandStderr command
    case [(bytes,offset) | (name,bytes,offset) <- cases, name == label] of
      [(bytes,offset)] -> unless (output == prefix <> BS.pack (takeWhile (/= 0) (drop offset bytes)) <> "\n")
        (die "Original errorBelch2 changed its %s byte/newline contract")
      _ -> unless (if label == "stack" then (prefix <> "Stack space overflow: current size ") `BS.isPrefixOf` output
                   else "Out of memory" `BS.isInfixOf` output || "Heap exhausted;" `BS.isInfixOf` output)
        (die "Original overflow report lost its failure kind")
    pure (label, BS.unpack output, command)
  writeJson (root </> oracle) $ object ["schema" .= (1 :: Int),
    "cases" .= [object ["name" .= name, "bytes" .= bytes, "offset" .= offset,
       "message" .= takeWhile (/= 0) (drop offset bytes)] | (name,bytes,offset) <- cases],
    "nativeOutput" .= [object ["name" .= name, "stderr" .= bytes] | (name,bytes,_) <- rows],
    "overflowSizesAreBackendSpecific" .= True, "nativeCallsReturned" .= True]
  let commands = [version,compiled] ++ [command | (_,_,command) <- rows]
  inputs <- hashes root [source,"test/haskell-fixtures/RtsDiagnosticFixtures.hs",
    "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/Main.hs","thc.cabal"]
  artifacts <- hashes root (oracle : concatMap commandArtifacts commands)
  writeJson (root </> manifest) $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "inputHashes" .= inputs, "artifactHashes" .= artifacts, "commands" .= map commandRecord commands]
  putStrLn "rts-diagnostics: five original CString observations and two returning overflow reports"
