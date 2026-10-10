-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : TestSupportTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Tests for test support.
module TestSupportTests (tests, helperMode) where

import Control.Concurrent (forkFinally, killThread, newEmptyMVar, putMVar, takeMVar, threadDelay, throwTo)
import Control.Exception (AsyncException(ThreadKilled, UserInterrupt), SomeException, bracket, displayException, finally, fromException, try)
import Control.Monad (forM_, void, when)
import Data.Aeson (Value, encode, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.IORef (newIORef, readIORef, writeIORef)
import Data.List (isInfixOf)
import System.Directory (createDirectory, createDirectoryIfMissing, createDirectoryLink,
  doesDirectoryExist, doesFileExist, getTemporaryDirectory, pathIsSymbolicLink, removeFile, removePathForcibly)
import System.Environment (getEnvironment, getExecutablePath)
import System.Exit (ExitCode(..), exitWith)
import System.FilePath ((</>), takeDirectory)
import System.IO (IOMode(WriteMode), hClose, hPutStrLn, openTempFile, stderr, withFile)
import System.Info (os)
import qualified System.Process as Process
import System.Timeout (timeout)
import Test.HUnit (Test(..), assertBool, assertEqual, assertFailure)
import THC.Driver.Process (runProducer)
import TestSupport (Env(..), Result(..), assertContains, runExe, withFixtureNamed)

tests :: Test
tests = TestLabel "driver test support" $ TestList
  [ TestCase $ withHarness $ \environment -> do
      projectPath <- newIORef ""
      let outside = root environment </> "outside"
      createDirectory outside
      BL.writeFile (outside </> "audit.json") (encode (object
        ["missingGlobals" .= [object ["id" .= ("outside sentinel" :: String)]]]))
      failure <- try $ withFixtureNamed environment "fixture" "project" $ \project -> do
        writeIORef projectPath project
        let output = takeDirectory project </> "output"
        createDirectory output
        BL.writeFile (output </> "audit.json") (encode audit)
        if os == "mingw32" then do
          -- Junctions exercise the same no-traversal guard without requiring
          -- Windows symlink privilege. Pass the path as data, not shell syntax.
          variables <- getEnvironment
          let process = (Process.proc "cmd.exe" ["/d", "/q", "/v:off"])
                { Process.cwd = Just project
                , Process.env = Just (("THC_TEST_AUDIT_STORE", outside) :
                    filter ((/= "THC_TEST_AUDIT_STORE") . fst) variables) }
          (status, _, diagnostic) <- Process.readCreateProcessWithExitCode process
            "mklink /J linked-store \"%THC_TEST_AUDIT_STORE%\"\nexit\n"
          assertEqual ("create external-store junction: " ++ diagnostic) ExitSuccess status
        else createDirectoryLink outside (project </> "linked-store")
        assertBool "external store is linked" =<< pathIsSymbolicLink (project </> "linked-store")
        assertFailure "original test failure"
      originalFailure failure
      project <- readIORef projectPath
      assertBool "bracket still removes temporary project and outputs" . not =<<
        doesDirectoryExist (takeDirectory project)
      assertBool "external linked store remains untouched" =<< doesFileExist (outside </> "audit.json")
      report <- readFile (scratch environment </> "commands.log")
      forM_ ["missingGlobals=105", "issues=1", "GHC.Internal.Target.missing1",
             "unsupported-foreign-call", "GHC.Internal.Owner.worker", "/expr/2",
             "5 further missing entries omitted"] (`assertContains` report)
      forM_ ["large payload sentinel", "outside sentinel", "missing105"] $ \unwanted ->
        assertBool ("summary excludes " ++ unwanted) (not (unwanted `isInfixOf` report))
      assertBool "diagnostic output stays bounded" (length report < 16000)
  , TestCase $ withHarness $ \environment -> do
      failure <- try $ withFixtureNamed environment "fixture" "project" $ \_ ->
        assertFailure "original test failure"
      originalFailure failure
  , TestCase $ withHarness $ \environment -> do
      -- A broken persistent log and malformed audit must both leave the original
      -- assertion intact; the diagnostic still has its independent stderr sink.
      createDirectory (scratch environment </> "commands.log")
      failure <- try $ withFixtureNamed environment "fixture" "project" $ \project -> do
        writeFile (project </> "audit.json") "{ malformed"
        assertFailure "original test failure"
      originalFailure failure
  , TestCase $ withHarness $ \environment -> do
      self <- getExecutablePath
      let directory = root environment
          writes = directory </> "descendant-writes"
          stop = directory </> "stop-descendant"
      -- The same process tree first checks normal exit and both captured streams.
      completed <- runExe environment directory Nothing 5 self
        ["--test-support-tree", "once", directory]
      assertEqual "descendant stdout" "descendant stdout\n" (out completed)
      assertEqual "descendant stderr" "descendant stderr\n" (err completed)
      assertEqual "parent preserves descendant status" (ExitFailure 17) (code completed)
      (do
        failure <- try (runExe environment directory Nothing 2 self
          ["--test-support-tree", "hold", directory])
        case failure :: Either SomeException Result of
          Left problem -> assertContains "timed out:" (displayException problem)
          Right result -> assertFailure ("expected subprocess timeout: " ++ show result)
        before <- BS.readFile writes
        assertBool "descendant wrote before the timeout" (not (BS.null before))
        threadDelay 250000
        after <- BS.readFile writes
        assertEqual "timeout cleanup stops descendant file writes before returning" before after)
        `finally` writeFile stop ""
  , TestLabel "production process ownership" $ TestCase $ withHarness $ \environment -> do
      self <- getExecutablePath
      let directory = root environment
          command mode = (Process.proc self ["--test-support-producer", mode, directory])
            { Process.cwd = Just directory }
      forM_ [("success", ExitSuccess), ("once", ExitFailure 17)] $ \(mode, expected) -> do
        completed <- timeout 3000000 $ withFile (directory </> "stdout") WriteMode $ \output ->
          withFile (directory </> "stderr") WriteMode $ \diagnostic ->
            runProducer (command mode) { Process.std_out = Process.UseHandle output,
                                        Process.std_err = Process.UseHandle diagnostic }
        assertEqual "production command preserves exit status" (Just expected) completed
        assertEqual "production stdout" "descendant stdout\n" =<< readFile (directory </> "stdout")
        assertEqual "production stderr" "descendant stderr\n" =<< readFile (directory </> "stderr")
      forM_ [("cancel", ThreadKilled), ("interrupt", UserInterrupt)] $ \(label, exception) -> do
        let scope = directory </> label
            writes = scope </> "descendant-writes"
            stop = scope </> "stop-descendant"
        createDirectory scope
        (do
          finished <- newEmptyMVar
          worker <- forkFinally (runProducer (Process.proc self
            ["--test-support-producer", "hold", scope])) (putMVar finished)
          let await = do
                started <- doesFileExist writes
                if started then pure () else threadDelay 10000 >> await
          ready <- timeout 3000000 await
          case ready of
            Nothing -> killThread worker >> assertFailure "production descendant did not become ready"
            Just () -> pure ()
          throwTo worker exception
          outcome <- timeout 3000000 (takeMVar finished)
          case outcome of
            Just (Left problem) -> assertEqual "cancellation preserves the asynchronous exception"
              (Just exception) (fromException problem)
            other -> assertFailure ("production cancellation did not finish: " ++ show other)
          before <- BS.readFile writes
          assertBool "production descendant wrote before cancellation" (not (BS.null before))
          threadDelay 250000
          after <- BS.readFile writes
          assertEqual "production cancellation stops descendant writes before returning" before after)
          `finally` writeFile stop ""
  ]
  where
    audit :: Value
    audit = object ["accepted" .= False,
      "missingGlobals" .= [object ["id" .= ("GHC.Internal.Target.missing" ++ show index),
        "references" .= ("large payload sentinel" :: String)] | index <- [1..105 :: Int]],
      "issues" .= [object ["code" .= ("unsupported-foreign-call" :: String),
        "owner" .= ("GHC.Internal.Owner.worker" :: String), "path" .= ("/expr/2" :: String),
        "detail" .= replicate 10000 'x']],
      "reachableBindings" .= replicate 10000 ("large payload sentinel" :: String)]
    originalFailure :: Either SomeException () -> IO ()
    originalFailure result = case result of
      Left problem -> assertContains "original test failure" (displayException problem)
      Right () -> assertFailure "expected the original assertion failure"

-- Reuse this test executable so the lifetime control needs no shell or toolchain.
helperMode :: [String] -> Maybe (IO ())
helperMode arguments = case arguments of
  ["--test-support-tree", mode, directory] -> Just $ do
    self <- getExecutablePath
    (_, _, _, child) <- Process.createProcess
      (Process.proc self ["--test-support-writer", mode, directory])
    -- The timeout case leaves an exited leader whose descendant keeps the
    -- inherited output pipes open. Capture must not reap away group ownership.
    if mode == "hold" then pure () else Process.waitForProcess child >>= exitWith
  ["--test-support-producer", mode, directory] -> Just $ do
    self <- getExecutablePath
    (_, _, _, child) <- Process.createProcess
      (Process.proc self ["--test-support-writer", if mode == "success" then "once" else mode, directory])
    status <- Process.waitForProcess child
    exitWith (if mode == "success" then ExitSuccess else status)
  ["--test-support-writer", "once", _] -> Just $ do
    putStrLn "descendant stdout"
    hPutStrLn stderr "descendant stderr"
    exitWith (ExitFailure 17)
  ["--test-support-writer", "hold", directory] -> Just $ do
    let loop = do
          exists <- doesDirectoryExist directory
          stopped <- doesFileExist (directory </> "stop-descendant")
          when (exists && not stopped) $ do
            appendFile (directory </> "descendant-writes") "x"
            threadDelay 10000
            loop
    -- A broken cleanup must fail the assertion, not leave an unbounded orphan.
    void (timeout 10000000 loop)
  _ -> Nothing

withHarness :: (Env -> IO a) -> IO a
withHarness action = bracket temporary removePathForcibly $ \base -> do
  createDirectory (base </> "fixture")
  createDirectoryIfMissing True (base </> "evidence")
  action (Env base base "unused" "unused" (base </> "evidence"))
  where
    temporary = do
      directory <- getTemporaryDirectory
      (path, handle) <- openTempFile directory "thc-diagnostic-test-"
      hClose handle
      removeFile path
      createDirectory path
      pure path
