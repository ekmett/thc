-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : RunTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Tests for run.
module RunTests (tests) where

import Control.Monad (forM_)
import qualified Data.ByteString as BS
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import System.Directory (canonicalizePath, doesDirectoryExist, doesFileExist, getModificationTime)
import System.FilePath ((</>), takeDirectory)
import Test.HUnit (Test(..), assertBool, assertEqual)
import THC.Compact.Core (Presence(..))
import THC.Compact.Debug (SourceFile(..))
import THC.Compact.Inspect (unpackContainer)
import THC.Compact.Module (readModuleSources)
import TestSupport

tests :: Env -> Test
tests env = TestLabel "implicit Cabal project native versus THC run" $ TestCase $
  -- GHC's Linux -g assembler cannot quote a double quote in .file paths.
  -- Plan tests retain quoted paths; this project capture retains spaces/Unicode.
  withFixtureNamed env "t/fixtures/run-pure" "project café" $ \package -> do
    let base = takeDirectory package
        source = package </> "app/Main.hs"
        -- Native compilation is independent of the THC backend. Keep the first
        -- build cold, then let Cabal reuse unchanged objects while every run
        -- still exports Core and audited controls explicitly request verification.
        -- Later edits use this same directory, exercising actual invalidation.
        output = base </> "output"
        invokeWith backend options = run env base backend 180
          (["run", "--project-dir", package, "completed", "--dist-dir", output,
            "--thc-root", thcRoot env, "--runtime", runtime env] ++ options)
        invoke backend = invokeWith backend ["--verify-artifacts"]
        exported = output
        executable = do
          plan <- readJson (output </> "native/cache/plan.json")
          pure $ string $ field (one ((== "exe:completed") . string . (`field` "component-name"))
            (objects plan "install-plan")) "bin-file"
    cold <- doesDirectoryExist output
    assertBool "fixture starts with a cold native dist directory" (not cold)
    ordinary <- invokeWith Nothing []
    assertSuccess ordinary
    assertNoStdout ordinary
    audited <- doesFileExist (exported </> "audit.json")
    assertBool "default run does not invoke the auditor" (not audited)
    forM_ ["bytecode", "ast"] $ \backend -> do
      result <- invoke (Just backend)
      assertSuccess result
      assertNoStdout result
      diagnostics <- json (last $ lines $ err result)
      assertEqual "backend" backend (string $ field diagnostics "backend")
      assertEqual "unsupported traps" 0 (number $ field diagnostics "unsupportedTraps")
      audit <- readJson (exported </> "audit.json")
      assertBool "strict Core accepted" (bool $ field audit "accepted")
      manifest <- readJson (output </> "packages.json")
      let entry = one (any ((== "Main") . string . (`field` "name")) . (`objects` "modules"))
                      (objects manifest "units")
      assertEqual "IO root" [string (field entry "id") ++ ":Main.main"] (strings $ field audit "roots")
      let primitives = map (string . (`field` "name")) (objects audit "primitives")
      forM_ ["newMutVar#", "writeMutVar#", "readMutVar#", "raise#"] $ \primitive ->
        assertBool ("missing " ++ primitive) (primitive `elem` primitives)
      forM_ ["Main", "Answer"] $ \moduleName -> do
        let moduleEntry = one ((== moduleName) . string . (`field` "name")) (objects entry "modules")
        core <- readPublishedCore entry moduleEntry
        bytes <- readPublishedCoreBytes entry moduleEntry
        sources <- either fail pure (readModuleSources bytes)
        (_,_,segments) <- either fail pure (unpackContainer bytes)
        assertEqual "post-Tidy Core" "optimized-Core-after-Tidy-before-CorePrep"
          (string $ field core "boundary")
        assertBool "source spans" (not (BS.null (segments !! 4)))
        sourcePath <- canonicalizePath (package </> "app" </> moduleName ++ ".hs")
        hasSource <- anyM (\(SourceFile _ file content) -> do
          path <- canonicalizePath (Text.unpack (Text.decodeUtf8 file))
          pure (path == sourcePath && case content of Known source -> not (BS.null source); _ -> False))
          sources
        assertBool "source content" hasSource
        native <- executable
        requireFile (takeDirectory native </> "completed-tmp" </> moduleName ++ ".hi")
      -- Temporary export objects never become another native component build.
      forM_ [".o", ".dyn_o"] $ \suffix -> do
        objectsFound <- findFiles (output </> "native/cache/thc/staging") suffix
        assertBool "temporary export objects cleaned" (null objectsFound)
      native <- executable
      requireFile native
      nativeResult <- runExe env package Nothing 60 native []
      assertSuccess nativeResult
      assertEqual "native stdout" (out nativeResult) (out result)

    previousAudit <- readText (exported </> "audit.json")
    previousAuditTime <- getModificationTime (exported </> "audit.json")
    unverified <- invokeWith Nothing []
    assertSuccess unverified
    assertEqual "unverified run preserves the guest result" (out ordinary) (out unverified)
    assertEqual "unverified run leaves previous audit bytes untouched" previousAudit
      =<< readText (exported </> "audit.json")
    assertEqual "unverified run does not refresh an old audit" previousAuditTime
      =<< getModificationTime (exported </> "audit.json")

    original <- readText source
    assertContains "answer ==# 42#" original
    writeText source (replaceText "answer ==# 42#" "answer ==# 43#" original)
    forM_ ["bytecode", "ast"] $ \backend -> do
      result <- invoke (Just backend)
      assertFailure result
      audit <- readJson (exported </> "audit.json")
      assertBool "valid Core still accepted" (bool $ field audit "accepted")
      native <- executable
      requireFile native
      nativeResult <- runExe env package Nothing 60 native []
      assertFailure nativeResult

    writeText source "module Main where\nmain :: IO ()\nmain = putStrLn \"native only\"\n"
    unsupported <- invoke Nothing
    assertFailure unsupported
    assertNoStdout unsupported
    audit <- readJson (exported </> "audit.json")
    assertBool "console IO rejected" (not $ bool $ field audit "accepted")
    native <- executable
    requireFile native
    nativeResult <- runExe env package Nothing 60 native []
    assertSuccess nativeResult
    assertEqual "native console output" "native only\n" (out nativeResult)

    writeText source "module Main where\nmain :: IO Int\nmain = pure 42\n"
    wrongResult <- invoke Nothing
    assertFailure wrongResult
    assertNoStdout wrongResult
    wrongAudit <- readJson (exported </> "audit.json")
    assertBool "IO Int rejected" (not $ bool $ field wrongAudit "accepted")
    assertBool "correct boundary code" $ any
      ((== "io-main-boundary") . string . (`field` "code")) (objects wrongAudit "issues")

anyM :: Monad m => (a -> m Bool) -> [a] -> m Bool
anyM predicate values = or <$> mapM predicate values

one :: (a -> Bool) -> [a] -> a
one predicate values = case filter predicate values of
  [value] -> value
  _ -> error "expected exactly one selected component or module"
