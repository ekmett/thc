-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP #-}
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : NativeImageTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : OverloadedStrings; Linux ELF fixtures and host process services
--
-- Producer-boundary checks use a stub recipe and tiny C ELF fixtures. They
-- establish invocation/publication ownership, never Native Image execution.
module NativeImageTests (tests) where

import Control.Exception (bracket)
#ifndef mingw32_HOST_OS
import Control.Concurrent (forkFinally, killThread, newEmptyMVar, putMVar, takeMVar, threadDelay)
import Control.Exception (finally)
import System.Posix.Files (createNamedPipe)
import System.Timeout (timeout)
#endif
import Control.Monad (forM_)
import Data.Aeson (toJSON)
import Data.List (isPrefixOf)
import System.Directory
  ( doesDirectoryExist, doesFileExist, getPermissions, listDirectory
  , removeFile, setPermissions, executable )
import System.FilePath ((</>))
import System.Environment (lookupEnv, setEnv, unsetEnv)
import System.Exit (ExitCode(..))
import System.Info (arch, os)
import System.IO.Error (tryIOError)
import System.Process (readProcessWithExitCode)
import Test.HUnit (Test(..), assertBool, assertEqual, assertFailure)
import NativeCacheTests (withEnvironment, withScratch, writeExecutable)
import TestSupport (array, assertContains, field, readJson, readText, writeText)
import THC.Driver.NativeImage (buildNativeImage, validateNativeImage)
import THC.Driver.Run (runtimeLaunchArguments)

tests :: Test
tests = TestLabel "native image producer boundary" $ if os /= "linux" || arch /= "x86_64"
  then TestLabel "unsupported platform rejects before acquisition" $ TestCase $ expectFailure "requires Linux x86-64" (validateNativeImage ".")
  else TestList
    [ TestLabel "prerequisites fail before acquisition" $ TestCase $ withProducer $ \root output manifest -> do
        validateNativeImage root
        removeFile (root </> "build/install/thc/lib/thc-0.1-experiment.jar")
        expectFailure "installed THC runtime" (validateNativeImage root)
        writeText (root </> "build/install/thc/lib/thc-0.1-experiment.jar") "stub distribution"
        writeExecutable (root </> "jdk/bin/native-image") "#!/bin/sh\necho incompatible\n"
        expectFailure "25.3.4.1" (validateNativeImage root)
        assertBool "validation creates no output" . not =<< doesDirectoryExist output
        assertBool "validation preserves acquisition input" =<< doesFileExist manifest
    , TestLabel "binding, fresh repeated builds and failed replacement" $ TestCase $ withProducer $ \root output manifest -> do
        let program = "logical +name \"café\""
            completion = output </> "completion.json"
            image = output </> "artifacts/program"
        writeText (output </> "unrelated.txt") "keep"
        buildNativeImage root output program manifest
        first <- readJson completion
        assertEqual "qualified default profile" (toJSON ("resource-copy" :: String)) (field first "profile")
        let artifactPaths = [field artifact "path" | artifact <- array (field first "artifacts")]
        forM_ ["artifacts/program", "artifacts/libproducer-witness.bin", "artifacts/diagnostics/nested/resource.txt"] $ \path ->
          assertBool "producer report owns output membership" (elem (toJSON (path :: String)) artifactPaths)
        assertEqual "runtime diagnostics name is not reserved by producer logs" "payload" =<<
          readText (output </> "artifacts/diagnostics/nested/resource.txt")
        let binding = field first "binding"
        assertEqual "logical program name is opaque; ELF basename is fixed"
          (toJSON (runtimeLaunchArguments True
            ["--run-executable", '@' : manifest, "main::Main.main",
             "ghc-internal:GHC.Internal.TopHandler.flushStdHandles"] program [])) (field binding "arguments")
        assertEqual "native properties" (toJSON (["ast", "false", "native"] :: [String])) $
          toJSON [field (field binding "properties") key | key <- ["thc.backend", "thc.asyncExceptions", "thc.byteArrayStorage"]]
        buildNativeImage root output program manifest
        second <- readJson completion
        assertEqual "repeated production retains the same deployable inventory" (field first "artifacts") (field second "artifacts")
        assertEqual "producer invoked afresh, no image reuse" "build\nbuild\n" =<< readText (root </> "invocations.txt")
        assertEqual "unrelated caller content survives" "keep" =<< readText (output </> "unrelated.txt")
        assertBool "successful staging is removed" . null . filter (isPrefixOf ".native-image-build-") =<< listDirectory output
        withEnvironment [("THC_NATIVE_IMAGE_VECTOR_PROFILE", "intrinsics")] $ buildNativeImage root output program manifest
        overridden <- readJson completion
        assertEqual "explicit valid profile survives" (toJSON ("intrinsics" :: String)) (field overridden "profile")
        withEnvironment [("THC_TEST_NATIVE_MODE", "fail")] $
          expectFailure "native image producer failed" (buildNativeImage root output program manifest)
        assertBool "failure removes prior success marker" . not =<< doesFileExist completion
        assertBool "failure preserves prior artifact" =<< doesFileExist image
        let failed = output </> ".native-image-failed"
        assertContains "intentional producer failure" =<< readText (failed </> "build.stderr")
        writeText (failed </> "previous-attempt.txt") "old failure"
        withEnvironment [("THC_TEST_NATIVE_MODE", "fail")] $
          expectFailure "native image producer failed" (buildNativeImage root output program manifest)
        assertContains "intentional producer failure" =<< readText (failed </> "build.stderr")
        assertBool "next attempt removes the previous failed stage" . not =<< doesFileExist (failed </> "previous-attempt.txt")
        assertBool "failed staging does not accumulate" . null . filter (isPrefixOf ".native-image-build-") =<< listDirectory output
        assertEqual "repeated failure preserves unrelated caller content" "keep" =<< readText (output </> "unrelated.txt")
    , TestLabel "zero exit cannot bless missing or invalid artifacts" $ TestCase $ withProducer $ \root output manifest ->
        forM_ ["missing", "invalid", "not-executable", "bad-sidecar", "report-missing", "escape", "symlink-escape", "bad-report"] $ \mode -> do
          writeText (output </> "completion.json") "stale success"
          withEnvironment [("THC_TEST_NATIVE_MODE", mode)] $
            expectFailure "native image" (buildNativeImage root output "ordinary" manifest)
          assertBool "invalid output has no completion" . not =<< doesFileExist (output </> "completion.json")
#ifndef mingw32_HOST_OS
    , TestLabel "cancellation owns preparation descendants" $ TestCase $ withProducer $ \root output manifest -> do
        let ready = root </> "child-ready"
            release = root </> "release-child"
            marker = root </> "descendant-marker"
        createNamedPipe ready 0o600
        withEnvironment [("THC_TEST_NATIVE_MODE", "cancel"), ("THC_TEST_NATIVE_READY", ready),
          ("THC_TEST_NATIVE_RELEASE", release), ("THC_TEST_NATIVE_MARKER", marker)] $ do
          finished <- newEmptyMVar
          worker <- forkFinally (buildNativeImage root output "cancelled" manifest) (putMVar finished)
          finally (do
            handshake <- timeout 5000000 (readText ready)
            assertEqual "actual child is blocked before cancellation" (Just "ready") handshake
            killThread worker
            result <- takeMVar finished
            case result of
              Left _ -> pure ()
              Right () -> assertFailure "cancelled producer returned success"
            writeText release ""
            -- Match the existing subprocess-lifetime check's bounded observation
            -- after the readiness handshake; this is not retry or warmup.
            threadDelay 250000
            assertBool "cancelled descendant cannot write after release" . not =<< doesFileExist marker
            assertBool "cancellation cannot publish completion" . not =<< doesFileExist (output </> "completion.json"))
            (killThread worker >> writeText release "")
#endif
    ]

expectFailure :: String -> IO () -> IO ()
expectFailure fragment action = do
  result <- tryIOError action
  case result of
    Left problem -> assertContains fragment (show problem)
    Right () -> assertFailure ("accepted " ++ fragment)

withProducer :: (FilePath -> FilePath -> FilePath -> IO ()) -> IO ()
withProducer action = withScratch $ \scratch -> do
  let root = scratch </> "THC root with spaces"
      output = scratch </> "component output"
      manifest = scratch </> "component packages.json"
      jdk = root </> "jdk"
      elf = root </> "witness"
      library = root </> "witness.so"
  writeText (root </> "witness.c") "int main(void) { return 0; }\n"
  writeText (root </> "library.c") "int producer_witness(void) { return 42; }\n"
  forM_ [[root </> "witness.c", "-o", elf], ["-shared", "-fPIC", root </> "library.c", "-o", library]] $ \arguments -> do
    (status, _, diagnostic) <- readProcessWithExitCode "cc" arguments ""
    assertEqual ("actual C ELF fixture: " ++ diagnostic) ExitSuccess status
  writeText manifest "{}"
  writeText (root </> "build/install/thc/lib/thc-0.1-experiment.jar") "stub distribution"
  forM_ ["java", "javac", "jar", "native-image"] $ \tool ->
    writeExecutable (jdk </> "bin" </> tool) "#!/bin/sh\necho GraalVM 25.3.4.1\n"
  let recipe = root </> "research/native-image-preparation/prepared-image.sh"
  writeText recipe $ unlines
    [ "#!/bin/bash", "set -eu"
    , "test \"$1\" = \"$PWD\" && test \"$2\" = executable"
    , "test \"$THC_NATIVE_IMAGE_EXECUTABLE_NAME\" = program"
    , "test -f \"$THC_NATIVE_IMAGE_EXECUTABLE_CONFIG\""
    , "printf 'build\\n' >> \"$1/invocations.txt\""
    , "mkdir -p \"$3/reproduction-inventory\""
    , "printf '{}' > \"$3/reproduction-inventory/native-libraries.json\""
    , "case \"$THC_TEST_NATIVE_MODE\" in"
    , " fail) echo 'intentional producer failure' >&2; exit 7 ;;"
    , " cancel) (printf ready > \"$THC_TEST_NATIVE_READY\"; while ! test -f \"$THC_TEST_NATIVE_RELEASE\"; do sleep 0.01; done; printf leaked > \"$THC_TEST_NATIVE_MARKER\") & wait; exit 0 ;;"
    , " missing) exit 0 ;;"
    , "esac"
    , "cp \"$THC_TEST_NATIVE_ELF\" \"$3/program\""
    , "cp \"$THC_TEST_NATIVE_LIBRARY\" \"$3/libproducer-witness.bin\""
    , "mkdir -p \"$3/diagnostics/nested\""
    , "printf payload > \"$3/diagnostics/nested/resource.txt\""
    , "printf '{\"executables\":[\"program\"],\"shared_libraries\":[\"libproducer-witness.bin\"],\"language_resources\":[\"diagnostics\"]}' > \"$3/build-artifacts.json\""
    , "case \"$THC_TEST_NATIVE_MODE\" in"
    , " invalid) printf 'not ELF' > \"$3/program\" ;;"
    , " not-executable) chmod -x \"$3/program\" ;;"
    , " bad-sidecar) printf 'not ELF' > \"$3/libproducer-witness.bin\" ;;"
    , " report-missing) rm \"$3/build-artifacts.json\" ;;"
    , " escape) printf outside > \"$3/../escape-data\"; printf '{\"executables\":[\"program\"],\"language_resources\":[\"../escape-data\"]}' > \"$3/build-artifacts.json\" ;;"
    , " symlink-escape) printf outside > \"$3/../escape-data\"; ln -s ../escape-data \"$3/escape-link\"; printf '{\"executables\":[\"program\"],\"language_resources\":[\"escape-link\"]}' > \"$3/build-artifacts.json\" ;;"
    , " bad-report) printf '{\"executables\":null}' > \"$3/build-artifacts.json\" ;;"
    , "esac"
    ]
  permissions <- getPermissions recipe
  setPermissions recipe permissions {executable = True}
  bracket (lookupEnv "THC_NATIVE_IMAGE_VECTOR_PROFILE")
    (maybe (unsetEnv "THC_NATIVE_IMAGE_VECTOR_PROFILE") (setEnv "THC_NATIVE_IMAGE_VECTOR_PROFILE")) $ \_ -> do
      unsetEnv "THC_NATIVE_IMAGE_VECTOR_PROFILE"
      withEnvironment [("JAVA_HOME", jdk), ("THC_LLVM_READOBJ", "/bin/true"),
        ("THC_TEST_NATIVE_MODE", "success"), ("THC_TEST_NATIVE_ELF", elf),
        ("THC_TEST_NATIVE_LIBRARY", library)] $ action root output manifest
