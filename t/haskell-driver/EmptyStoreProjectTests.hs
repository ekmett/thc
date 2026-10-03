-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : EmptyStoreProjectTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Tests for empty store project.
module EmptyStoreProjectTests (tests) where

import System.Directory (createDirectoryIfMissing, removePathForcibly)
import System.FilePath ((</>), takeDirectory)
import Test.HUnit (Test(..), assertBool, assertEqual)
import THC.Driver.Installed (emptyRegistration, modulelessRegistration)
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
  let facade = original <> "depends: provider-1.0\nexposed-modules: Public from provider-1.0:Original\n"
      inspect = modulelessRegistration "empty-compat-0.1.0.0-abc" ["provider-1.0"]
  assertEqual "definite reexport retains exact alias/provider/module"
    (Just [("Public", "provider-1.0", "Original")]) (inspect facade)
  assertBool "facade is not a genuinely empty registration"
    (not (emptyRegistration "empty-compat-0.1.0.0-abc" ["provider-1.0"] facade))
  mapM_ (assertEqual "owned or malformed module inventories rejected" Nothing . inspect)
    [ facade <> "hidden-modules: Private\n"
    , original <> "depends: provider-1.0\nexposed-modules: Public\n"
    , original <> "exposed-modules: Public from provider-1.0:Original\n"
    , original <> "depends: provider-1.0\nexposed-modules: Public from provider-1.0:Original, Public from provider-1.0:Other\n"
    ]

-- Like nats on modern GHC: the package is a real source-built dependency, but
-- its only Haskell module is disabled by the selected compiler condition.
-- A C-only archive models libyaml-clib, and the app imports its actual value
-- through a reexport-only facade like happy-lib, without depending on the
-- provider directly.
emptyProjectTest :: Env -> Test
emptyProjectTest env = TestLabel "empty, C-only and reexport-only Cabal store libraries" $ TestCase $
  withFixtureNamed env "t/fixtures/run-store-project" "empty project" $ \project -> do
    let base = takeDirectory project
        dependency = base </> "dependency-source"
        shim = base </> "empty-source"
        nativeShim = base </> "native-source"
        facade = base </> "facade-source"
        output = base </> "output"
        invoke backend = run env base (Just backend) 240
          ["run", "--verify-artifacts", "--project-dir", project, "completed", "--thc-root", thcRoot env,
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
    createDirectoryIfMissing True facade
    writeText (facade </> "facade.cabal") $ unlines
      ["cabal-version: 3.0", "name: facade", "version: 0.1.0.0",
       "license: BSD-3-Clause", "build-type: Simple", "library",
       "  default-language: Haskell2010", "  build-depends: dep-data ==0.1.0.0",
       "  reexported-modules: Answer as PublicAnswer"]
    sourceDist facade
    writeText (project </> "cabal.project")
      "packages: app/app.cabal dep-data-0.1.0.0.tar.gz empty-compat-0.1.0.0.tar.gz native-only-0.1.0.0.tar.gz facade-0.1.0.0.tar.gz\n"
    let description = project </> "app/app.cabal"
    text <- readText description
    writeText description (replaceText "dep-data ==0.1.0.0"
      "facade ==0.1.0.0, empty-compat ==0.1.0.0, native-only ==0.1.0.0" text)
    let mainSource = project </> "app/app/Main.hs"
    mainText <- readText mainSource
    writeText mainSource (replaceText "import Answer" "import PublicAnswer" mainText)
    prepared <- runPreparation env base
      ["build", "--project-dir", project, "completed", "--thc-root", thcRoot env,
       "--dist-dir", output]
    assertSuccess prepared
    assertNoStdout prepared
    first <- invoke "ast"
    assertSuccess first
    assertNoStdout first
    plan <- readJson (output </> "native/cache/plan.json")
    executable <- case filter ((== "exe:completed") . string . (`field` "component-name"))
      (objects plan "install-plan") of
        [unit] -> pure (string (field unit "bin-file"))
        _ -> fail "expected one completed executable"
    native <- runExe env project Nothing 60 executable []
    assertSuccess native
    assertEqual "native and AST package behavior agree" (out native) (out first)
    audit <- readJson (output </> "audit.json")
    assertBool "unchanged strict package audit" (bool (field audit "accepted"))
    assertEqual "no missing globals" [] (array $ field audit "missingGlobals")
    second <- runExe env base (Just "bytecode") 240 (runtime env)
      ["--verify-artifacts", "--run-executable", '@' : (output </> "packages.json"),
       "main::Main.main", "ghc-internal:GHC.Internal.TopHandler.flushStdHandles", "--", "completed"]
    assertSuccess second
    assertNoStdout second
    assertEqual "native and bytecode package behavior agree" (out native) (out second)
