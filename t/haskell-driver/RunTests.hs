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
import System.Directory (canonicalizePath, doesFileExist, getModificationTime)
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
        -- Prepare this fresh project explicitly before bounded execution,
        -- then edit the same project to exercise invalidation.
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
    prepared <- runPreparation env base
      ["build", "--project-dir", package, "completed", "--dist-dir", output,
       "--thc-root", thcRoot env]
    assertSuccess prepared
    assertNoStdout prepared
    ordinary <- invokeWith Nothing []
    assertSuccess ordinary
    assertNoStdout ordinary
    audited <- doesFileExist (exported </> "audit.json")
    assertBool "default run does not invoke the auditor" (not audited)
    coldNative <- executable
    requireFile coldNative
    coldNativeResult <- runExe env package Nothing 60 coldNative []
    assertSuccess coldNativeResult
    assertEqual "native stdout" (out coldNativeResult) (out ordinary)
    result <- invoke (Just "bytecode")
    assertSuccess result
    assertNoStdout result
    diagnostics <- json (last $ lines $ err result)
    assertEqual "backend" "bytecode" (string $ field diagnostics "backend")
    assertEqual "unsupported traps" 0 (number $ field diagnostics "unsupportedTraps")
    audit <- readJson (exported </> "audit.json")
    assertBool "strict Core accepted" (bool $ field audit "accepted")
    manifest <- readJson (output </> "packages.json")
    let entry = one (any ((== "Main") . string . (`field` "name")) . (`objects` "modules"))
                    (objects manifest "units")
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
        pure (path == sourcePath && case content of Known contents -> not (BS.null contents); _ -> False))
        sources
      assertBool "source content" hasSource
      native <- executable
      requireFile (takeDirectory native </> "completed-tmp" </> moduleName ++ ".hi")
    -- Temporary export objects never become another native component build.
    forM_ [".o", ".dyn_o"] $ \suffix -> do
      objectsFound <- findFiles (output </> "native/cache/thc/staging") suffix
      assertBool "temporary export objects cleaned" (null objectsFound)

    original <- readText source
    assertContains "answer ==# 42#" original
    writeText source (replaceText "answer ==# 42#" "answer ==# 43#" original)
    failed <- invoke (Just "ast")
    assertFailure failed
    failedAudit <- readJson (exported </> "audit.json")
    assertBool "valid Core still accepted" (bool $ field failedAudit "accepted")
    failedNative <- executable
    requireFile failedNative
    failedNativeResult <- runExe env package Nothing 60 failedNative []
    assertFailure failedNativeResult

    previousAudit <- readText (exported </> "audit.json")
    previousAuditTime <- getModificationTime (exported </> "audit.json")
    writeText source "module Main where\nmain :: IO ()\nmain = putStrLn \"hello\"\n"
    console <- invokeWith Nothing []
    assertSuccess console
    assertEqual "ordinary console run leaves previous audit bytes untouched" previousAudit
      =<< readText (exported </> "audit.json")
    assertEqual "ordinary console run does not refresh an old audit" previousAuditTime
      =<< getModificationTime (exported </> "audit.json")
    native <- executable
    requireFile native
    nativeResult <- runExe env package Nothing 60 native []
    assertSuccess nativeResult
    assertEqual "native console output" "hello\n" (out nativeResult)
    assertEqual "THC console output" (out nativeResult) (out console)

    cabalText <- readText (package </> "run-pure.cabal")
    writeText (package </> "run-pure.cabal") (cabalText ++ "  ghc-options: -main-is Main.start\n")
    writeText source "module Main where\nmain :: IO ()\nmain = error \"wrong entry\"\nstart :: IO Int\nstart = pure (error \"unused\")\n"
    selected <- invoke Nothing
    assertSuccess selected
    assertNoStdout selected
    selectedAudit <- readJson (exported </> "audit.json")
    assertBool "selected IO Int answer accepted" (bool $ field selectedAudit "accepted")
    selectedNative <- executable
    assertSuccess =<< runExe env package Nothing 60 selectedNative []

anyM :: Monad m => (a -> m Bool) -> [a] -> m Bool
anyM predicate values = or <$> mapM predicate values

one :: (a -> Bool) -> [a] -> a
one predicate values = case filter predicate values of
  [value] -> value
  _ -> error "expected exactly one selected component or module"
