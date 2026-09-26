-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module EmptyStoreProjectTests (tests) where

import Control.Exception (bracket)
import qualified Data.ByteString.Char8 as BS
import System.Directory (createDirectoryIfMissing, getModificationTime, removePathForcibly)
import System.Environment (lookupEnv, setEnv, unsetEnv)
import System.FilePath ((</>), takeDirectory)
import Test.HUnit (Test(..), assertBool, assertEqual)
import THC.Driver.Installed (emptyRegistration)
import TestSupport

tests :: Env -> Test
tests env = TestList [registrationTest, emptyProjectTest env]

registrationTest :: Test
registrationTest = TestLabel "empty store registrations" $ TestCase $ do
  let original = "name: empty-compat\nversion: 0.1.0.0\nid: empty-compat-0.1.0.0-abc\n"
      accepted = emptyRegistration "empty-compat-0.1.0.0-abc" []
  assertBool "genuinely empty library" (accepted original)
  assertBool "C-only archive has no Haskell modules despite Cabal's hs-libraries field"
    (accepted (original <> "hs-libraries: HSempty-compat\n"))
  mapM_ (assertBool "nonempty, unrelated or malformed registration rejected" . not . accepted)
    [ original <> "exposed-modules: Compat\n"
    , original <> "hidden-modules: Compat.Internal\n"
    , original <> "hs-libraries: HSempty-compat\nexposed-modules: Compat\n"
    , original <> "hs-libraries: HSempty-compat\nhidden-modules: Compat.Internal\n"
    , original <> "depends: provider-1.0\n"
    , original <> "exposed-modules: Compat from provider-1.0:Original\n"
    , "name: unrelated\nversion: 0.1.0.0\nid: unrelated-0.1.0.0\n"
    , "not a package registration"
    ]
  assertBool "dependency inventory can be nonempty but must match exactly"
    (emptyRegistration "empty-compat-0.1.0.0-abc" ["provider-1.0"]
      (original <> "depends: provider-1.0\n"))

-- Like nats on modern GHC: the package is a real source-built dependency, but
-- its only Haskell module is disabled by the selected compiler condition.
-- The second dependency models libyaml-clib: a real C archive, no Haskell modules.
emptyProjectTest :: Env -> Test
emptyProjectTest env = TestLabel "conditional empty and C-only Cabal store libraries" $ TestCase $
  withFixtureNamed env "test/fixtures/run-store-project" "empty project" $ \project -> do
    let base = takeDirectory project
        dependency = base </> "dependency-source"
        shim = base </> "empty-source"
        nativeShim = base </> "native-source"
        output = base </> "output"
        invoke backend = run env base (Just backend) 240
          ["run", project, "--exe", "completed", "--thc-root", thcRoot env,
           "--runtime", runtime env, "--dist-dir", output]
        sourceDist path = runExe env path Nothing 60 "cabal" ["sdist", "--output-dir", project] >>= assertSuccess
    copyTree (project </> "dep-data") dependency
    removePathForcibly (project </> "dep-data")
    sourceDist dependency
    createDirectoryIfMissing True shim
    writeText (shim </> "empty-compat.cabal") $ unlines
      ["cabal-version: 2.4", "name: empty-compat", "version: 0.1.0.0",
       "license: BSD-3-Clause", "build-type: Simple", "library",
       "  default-language: Haskell2010", "  if impl(ghc <7.9)",
       "    exposed-modules: Compat", "    build-depends: base"]
    writeText (shim </> "Compat.hs") "module Compat where\nvalue :: Int\nvalue = 1\n"
    sourceDist shim
    createDirectoryIfMissing True nativeShim
    writeText (nativeShim </> "native-only.cabal") $ unlines
      ["cabal-version: 2.4", "name: native-only", "version: 0.1.0.0",
       "license: BSD-3-Clause", "build-type: Simple", "library",
       "  default-language: Haskell2010", "  c-sources: native.c"]
    writeText (nativeShim </> "native.c") "int native_marker(void) { return 42; }\n"
    sourceDist nativeShim
    writeText (project </> "cabal.project")
      "packages: app/app.cabal dep-data-0.1.0.0.tar.gz empty-compat-0.1.0.0.tar.gz native-only-0.1.0.0.tar.gz\n"
    let description = project </> "app/app.cabal"
    text <- readText description
    writeText description (replaceText "dep-data ==0.1.0.0"
      "dep-data ==0.1.0.0, empty-compat ==0.1.0.0, native-only ==0.1.0.0" text)
    priorCache <- lookupEnv "THC_CACHE_HOME"
    let restore = maybe (unsetEnv "THC_CACHE_HOME") (setEnv "THC_CACHE_HOME") priorCache
    bracket (setEnv "THC_CACHE_HOME" (base </> "cache")) (const restore) $ \_ -> do
      first <- invoke "ast"
      assertSuccess first
      assertNoStdout first
      plan <- readJson (output </> "native/cache/plan.json")
      identifier <- case filter ((== "empty-compat") . string . (`field` "pkg-name"))
        (objects plan "install-plan") of
          [unit] -> pure (string (field unit "id"))
          _ -> fail "expected exactly one real empty store unit"
      manifest <- readJson (output </> "packages.json")
      unit <- case filter ((== identifier) . string . (`field` "id")) (objects manifest "units") of
        [value] -> pure value
        _ -> fail "empty dependency was discarded from package manifest"
      assertEqual "honest empty module inventory" [] (array $ field unit "modules")
      let path = string (field (field unit "bundle") "path")
      receipt <- readCore path "manifest.json"
      assertBool "actual isolated registration retained and validated"
        (emptyRegistration identifier [] (BS.pack (string (field receipt "emptyRegistration"))))
      nativeId <- case filter ((== "native-only") . string . (`field` "pkg-name"))
        (objects plan "install-plan") of
          [nativeUnit] -> pure (string (field nativeUnit "id"))
          _ -> fail "expected exactly one real C-only store unit"
      nativeUnit <- case filter ((== nativeId) . string . (`field` "id")) (objects manifest "units") of
        [value] -> pure value
        _ -> fail "C-only dependency was discarded from package manifest"
      assertEqual "C archive does not acquire invented Core modules" [] (array $ field nativeUnit "modules")
      let nativePath = string (field (field nativeUnit "bundle") "path")
      nativeReceipt <- readCore nativePath "manifest.json"
      let nativeRegistration = string (field nativeReceipt "emptyRegistration")
      assertContains ("HS" ++ nativeId) nativeRegistration
      assertBool "C-only registration validates its actual empty module inventory"
        (emptyRegistration nativeId [] (BS.pack nativeRegistration))
      nativeStamp <- getModificationTime nativePath
      stamp <- getModificationTime path
      audit <- readJson (output </> "audit.json")
      assertBool "unchanged strict package audit" (bool (field audit "accepted"))
      assertEqual "no missing globals" [] (array $ field audit "missingGlobals")
      second <- invoke "bytecode"
      assertSuccess second
      assertNoStdout second
      assertEqual "validated empty bundle reused without rewriting" stamp =<< getModificationTime path
      assertEqual "validated C-only bundle reused without rewriting" nativeStamp =<< getModificationTime nativePath
