-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module RtsShutdownFixtures (prepareRtsShutdown) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as C8
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import Text.Read (readMaybe)

prepareRtsShutdown :: FilePath -> IO ()
prepareRtsShutdown root = do
  let directory = "build/rts-shutdown"
      source = "compiler/test-fixtures/RtsShutdownNative.hs"
      native = directory </> "native"
      binary = root </> native </> "oracle"
      manifest = directory </> "manifest.json"
      observe = runLogged 120 root (directory </> "logs")
  createDirectoryIfMissing True (root </> native)
  present <- doesFileExist (root </> manifest)
  when present (removeFile (root </> manifest))
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- observe "version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Shutdown oracle requires GHC 9.14.1")
  compiled <- observe "native-build" [] ghc ["--make", "-O2", "-dynamic", "-fforce-recomp", "-Wall", "-Werror",
    "-dcore-lint", "-dstg-lint", "-package", "unix", "-odir", root </> native,
    "-hidir", root </> native, source, "-o", binary]
  signals <- observe "signals" [] binary ["--signals"]
  (term,stop) <- maybe (die "Invalid native signal constants") pure
    (readMaybe (C8.unpack (commandStdout signals)) :: Maybe (Int,Int))
  let cases = [("exit-" ++ show index, "exit", code, expected) |
               (index,(code,expected)) <- zip [0 :: Int ..] [(0,0),(37,37),(255,255),(256,0),(-1,255)]] ++
              [("term","signal",term,negate term),("stop","signal",stop,255)]
  rows <- forM [(label,kind,code,expected,fast) | (label,kind,code,expected) <- cases, fast <- [0 :: Int,1]] $
    \(label,kind,code,expected,fast) -> do
      command <- runLoggedExpect expected 120 root (directory </> "logs") (label ++ "-" ++ show fast)
        [] binary [kind,show code,show fast]
      unless (BS.null (commandStdout command) && BS.null (commandStderr command))
        (die "Original shutdown unexpectedly produced output")
      pure (object ["kind" .= kind,"code" .= code,"fast" .= fast,"exit" .= expected], command)
  let oracle = directory </> "oracle.json"
      commands = [version,compiled,signals] ++ map snd rows
  writeJson (root </> oracle) $ object ["schema" .= (1 :: Int),"cases" .= map fst rows]
  inputs <- hashes root [source,"test/haskell-fixtures/RtsShutdownFixtures.hs",
    "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/Main.hs","thc.cabal"]
  artifacts <- hashes root (oracle : concatMap commandArtifacts commands)
  writeJson (root </> manifest) $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "inputHashes" .= inputs,"artifactHashes" .= artifacts,"commands" .= map commandRecord commands]
  putStrLn "rts-shutdown: 14 original RTS subprocess exit/signal observations"
