-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module TestSupportTests (tests) where

import Control.Exception (SomeException, bracket, displayException, try)
import Control.Monad (forM_)
import Data.Aeson (Value, encode, object, (.=))
import qualified Data.ByteString.Lazy as BL
import Data.IORef (newIORef, readIORef, writeIORef)
import Data.List (isInfixOf)
import System.Directory (createDirectory, createDirectoryIfMissing, createDirectoryLink,
  doesDirectoryExist, doesFileExist, getTemporaryDirectory, removeFile, removePathForcibly)
import System.FilePath ((</>), takeDirectory)
import System.IO (hClose, openTempFile)
import Test.HUnit (Test(..), assertBool, assertFailure)
import TestSupport (Env(..), assertContains, withFixtureNamed)

tests :: Test
tests = TestLabel "temporary fixture audit diagnostics" $ TestList
  [ TestCase $ withHarness $ \environment -> do
      projectPath <- newIORef ""
      let outside = root environment </> "outside"
      createDirectory outside
      writeFile (outside </> "audit.json") "outside sentinel"
      failure <- try $ withFixtureNamed environment "fixture" "project" $ \project -> do
        writeIORef projectPath project
        let output = takeDirectory project </> "output"
        createDirectory output
        BL.writeFile (output </> "audit.json") (encode audit)
        createDirectoryLink outside (project </> "linked-store")
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
