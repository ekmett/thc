-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module StoreProjectTests (tests) where

import Control.Exception (bracket)
import Control.Monad (forM_, unless)
import Data.Char (isHexDigit)
import Data.List (isPrefixOf)
import System.Directory (getModificationTime, getPermissions, removeFile,
                         removePathForcibly, setPermissions)
import qualified System.Directory as Directory
import System.Environment (getEnvironment, lookupEnv, setEnv, unsetEnv)
import System.Exit (ExitCode(..))
import System.FilePath ((</>), takeDirectory, takeFileName)
import qualified System.Process as Process
import Test.HUnit (Test(..), assertBool, assertEqual)
import TestSupport
import THC.Driver.GhcProxy (ghcProxyCommand)

tests :: Env -> Test
tests env = TestList [proxyOptionsTest env, storeProjectTest env, customStoreProjectTest env]

proxyOptionsTest :: Env -> Test
proxyOptionsTest env = TestLabel "compiler proxy preserves arguments and replay provenance" $ TestCase $
  withFixtureNamed env "test/fixtures/run-store-project" "proxy" $ \project -> do
    let compiler = project </> "compiler.sh"
        wrapper = project </> "ghc-proxy.sh"
        arguments = project </> "compiler-arguments.txt"
        response = project </> "compiler response.txt"
        settings = [("THC_PROXY_DRIVER", driver env),
                    ("THC_PROXY_GHC", compiler), ("THC_PROXY_GLOBAL_UNITS", "sample\n"),
                    ("THC_PROXY_CAPTURE", project </> "capture"),
                    ("THC_PROXY_PLUGIN_DB", project </> "plugin-db"),
                    ("THC_PROXY_PLUGIN_UNIT", "thc-plugin"),
                    ("THC_PROXY_ARGUMENTS", arguments)]
    writeText compiler "#!/bin/sh\nprintf 'BEGIN\\0' >> \"$THC_PROXY_ARGUMENTS\"\nprintf '%s\\0' \"$@\" >> \"$THC_PROXY_ARGUMENTS\"\n"
    writeText wrapper ("#!/bin/sh\n" ++ ghcProxyCommand)
    writeText response "--make\n-this-unit-id\nsample\n+RTS\n-A8m\n-RTS\n"
    forM_ [compiler, wrapper] $ \path -> do
      permissions <- getPermissions path
      setPermissions path permissions { Directory.executable = True }
    original <- getEnvironment
    let environment = settings ++ filter ((`notElem` map fst settings) . fst) original
        cases = [ (True, ["--make", "-this-unit-id", "sample", "+RTS", "-A8m", "-RTS"])
                , (False, ["--numeric-version", "+RTS", "-A8m", "-RTS", "--",
                           "space and café", "", "line\nbreak", "\"quoted\"", "$literal"])
                , (True, ["@" ++ response])
                , (False, ["--numeric-version", "--RTS", "+RTS", "-A8m", "-RTS"])
                ]
    forM_ cases $ \(replays, supplied) -> do
      writeText arguments ""
      let command = (Process.proc wrapper supplied)
            { Process.cwd = Just project, Process.env = Just environment }
      (status, _, stderr) <- Process.readCreateProcessWithExitCode command ""
      assertEqual stderr ExitSuccess status
      calls <- splitArguments <$> readText arguments
      let (native, replay) = break (== "BEGIN") (drop 1 calls)
          flag = "-fplugin-opt=THC.Plugin:foreign-import-provenance"
      assertEqual "native compiler receives every original argument exactly" supplied native
      assertEqual "only selected Core compilations replay" (if replays then 2 else 1)
        (length $ filter (== "BEGIN") calls)
      if replays then do
        assertEqual "Core replay retains original arguments exactly" supplied
          (take (length supplied) (drop 1 replay))
        assertEqual "replayed compiler receives exactly one provenance opt-in" 1
          (length $ filter (== flag) replay)
      else pure ()
  where
    splitArguments "" = []
    splitArguments input = let (value, rest) = break (== '\0') input
                           in value : case rest of [] -> []; _:more -> splitArguments more

storeProjectTest :: Env -> Test
storeProjectTest env = TestLabel "source-built Cabal store Core" $ TestCase $
  -- Post-Tidy export uses -g; its Linux assembler cannot quote double quotes in paths.
  withFixtureNamed env "test/fixtures/run-store-project" "project café" $ \project ->
  withCache (takeDirectory project </> "cache") $ do
    let base = takeDirectory project
        source = base </> "dependency-source"
        answer = source </> "src/SafeDependency.hs"
        output = base </> "output"
        archive = project </> "dep-data-0.1.0.0.tar.gz"
        invoke backend = run env base (Just backend) 240
          ["run", project, "--exe", "completed", "--thc-root", thcRoot env,
           "--runtime", runtime env, "--dist-dir", output]
        global plan = one (\unit -> string (field unit "style") == "global" &&
                            string (field unit "pkg-name") == "dep-data")
                       (objects plan "install-plan")
        bundle manifest identifier = field (one
          ((== identifier) . string . (`field` "id")) (objects manifest "units")) "bundle"
    copyTree (project </> "dep-data") source
    removePathForcibly (project </> "dep-data")
    -- Exercise both generated wrappers with a real GHC RTS option: local
    -- compilation uses native-ghc, store capture uses its temporary wrapper.
    let dependencyDescription = source </> "dep-data.cabal"
    dependency <- readText dependencyDescription
    writeText dependencyDescription (dependency ++ "\n  ghc-options: +RTS -A8m -RTS\n")
    sourceDist env source project
    -- Both the initial native build and fresh-store Core capture must select
    -- the requested executable, not build every sibling component. This valid
    -- Cabal component deliberately fails if either path still uses `all`.
    let appDescription = project </> "app/app.cabal"
    description <- readText appDescription
    writeText appDescription (description ++ "\n  ghc-options: +RTS -A8m -RTS\n" ++ unlines
      ["", "executable unrelated", "  main-is: Unrelated.hs", "  hs-source-dirs: app",
       "  build-depends: base >=4.22 && <4.23", "  default-language: Haskell2010"])
    writeText (project </> "app/app/Unrelated.hs")
      "module Main where\nmain :: IO ()\nmain = intentionallyUnbuildableSibling\n"
    first <- invoke "ast"
    assertSuccess first
    assertNoStdout first
    assertBackend "ast" first
    firstPlan <- readJson (output </> "native/cache/plan.json")
    let firstId = string (field (global firstPlan) "id")
        executable = string (field (one
          (\unit -> string (field unit "component-name") == "exe:completed")
          (objects firstPlan "install-plan")) "bin-file")
    native <- runExe env project Nothing 60 executable []
    assertSuccess native
    assertEqual "native and THC output" (out native) (out first)
    firstManifest <- readJson (output </> "packages.json")
    let firstPath = string (field (bundle firstManifest firstId) "path")
    firstInner <- readCore firstPath "manifest.json"
    let exportKey = string (field firstInner "exportKey")
        buildKey = string (field firstInner "buildKey")
        digest key = length key == 64 && all isHexDigit key
    assertEqual "store manifest identifies its Cabal unit" firstId
      (string $ field firstInner "unit")
    assertBool "store build key is a SHA-256 digest" (digest buildKey)
    assertBool "store export key is a SHA-256 digest" (digest exportKey)
    assertEqual "store export key selects its cache directory" exportKey
      (takeFileName $ takeDirectory firstPath)
    assertBool "store ZIP uses shared application cache"
      ((base </> "cache/core-bundles/v1") `isPrefixOf` firstPath)
    assertEqual "Cabal store ID is ZIP basename" (firstId ++ ".zip") (takeFileName firstPath)
    assertReachable output firstId
    firstTime <- getModificationTime firstPath
    second <- invoke "bytecode"
    assertSuccess second
    assertNoStdout second
    assertBackend "bytecode" second
    secondManifest <- readJson (output </> "packages.json")
    assertEqual "store ZIP reused" firstPath (string $ field (bundle secondManifest firstId) "path")
    secondTime <- getModificationTime firstPath
    assertEqual "store ZIP not rewritten" firstTime secondTime

    original <- readText answer
    writeText answer (replaceText "stableValue = 42" "stableValue = 41" original)
    removeFile archive
    sourceDist env source project
    changed <- invoke "ast"
    assertFailure changed
    changedPlan <- readJson (output </> "native/cache/plan.json")
    let changedId = string (field (global changedPlan) "id")
    assertBool "changed source has a new Cabal store ID" (changedId /= firstId)
    changedManifest <- readJson (output </> "packages.json")
    assertBool "changed source has a new ZIP"
      (string (field (bundle changedManifest changedId) "path") /= firstPath)
    assertReachable output changedId

customStoreProjectTest :: Env -> Test
customStoreProjectTest env = TestLabel "Custom Setup library retains runtime-only transitive closure" $ TestCase $
  withFixtureNamed env "test/fixtures/run-store-project" "custom project" $ \project ->
  withCache (takeDirectory project </> "cache") $ do
    let base = takeDirectory project
        dependency = base </> "dependency-source"
        leaf = base </> "leaf-source"
        setupOnly = base </> "setup-source"
        output = base </> "output"
        invoke backend = run env base (Just backend) 240
          ["run", project, "--exe", "completed", "--thc-root", thcRoot env,
           "--runtime", runtime env, "--dist-dir", output]
        planned plan name = one ((== name) . string . (`field` "pkg-name")) (objects plan "install-plan")
        described manifest identifier = one ((== identifier) . string . (`field` "id")) (objects manifest "units")
    copyTree (project </> "dep-data") dependency
    removePathForcibly (project </> "dep-data")
    Directory.createDirectoryIfMissing True leaf
    writeText (leaf </> "runtime-leaf.cabal") $ unlines
      ["cabal-version: 3.0", "name: runtime-leaf", "version: 0.1.0.0",
       "license: BSD-3-Clause", "build-type: Simple", "library",
       "  exposed-modules: RuntimeLeaf", "  build-depends: base >=4.22 && <4.23",
       "  default-language: Haskell2010"]
    writeText (leaf </> "RuntimeLeaf.hs") $ unlines
      ["module RuntimeLeaf (leafValue) where", "leafValue :: Int", "leafValue = 42",
       "{-# OPAQUE leafValue #-}"]
    Directory.createDirectoryIfMissing True setupOnly
    writeText (setupOnly </> "setup-only.cabal") $ unlines
      ["cabal-version: 3.0", "name: setup-only", "version: 0.1.0.0",
       "license: BSD-3-Clause", "build-type: Simple", "library",
       "  exposed-modules: SetupOnly", "  build-depends: base >=4.22 && <4.23",
       "  default-language: Haskell2010"]
    writeText (setupOnly </> "SetupOnly.hs")
      "module SetupOnly (prepare) where\nprepare :: IO ()\nprepare = pure ()\n"
    let descriptionPath = dependency </> "dep-data.cabal"
    description <- readText descriptionPath
    writeText descriptionPath (replaceText "build-type: Simple" "build-type: Custom"
      (replaceText "build-depends: base >=4.22 && <4.23"
        "build-depends: base >=4.22 && <4.23, runtime-leaf ==0.1.0.0" description) ++ unlines
      ["", "custom-setup", "  setup-depends: base, Cabal, setup-only ==0.1.0.0"])
    writeText (dependency </> "Setup.hs") $ unlines
      ["import Distribution.Simple (defaultMain)", "import SetupOnly (prepare)",
       "main :: IO ()", "main = prepare >> defaultMain"]
    writeText (dependency </> "src/SafeDependency.hs") $ unlines
      ["module SafeDependency (stableValue) where", "import RuntimeLeaf (leafValue)",
       "stableValue :: Int", "stableValue = leafValue"]
    mapM_ (\source -> sourceDist env source project) [leaf, setupOnly, dependency]
    writeText (project </> "cabal.project") $ unlines
      ["packages: app/app.cabal dep-data-0.1.0.0.tar.gz runtime-leaf-0.1.0.0.tar.gz setup-only-0.1.0.0.tar.gz"]
    first <- invoke "ast"
    assertSuccess first
    assertNoStdout first
    assertBackend "ast" first
    plan <- readJson (output </> "native/cache/plan.json")
    let custom = planned plan "dep-data"
        identifier = string (field custom "id")
        leafId = string (field (planned plan "runtime-leaf") "id")
        setupId = string (field (planned plan "setup-only") "id")
        components = field custom "components"
        runtimeDeps = array (field (field components "lib") "depends")
        setupDeps = array (field (field components "setup") "depends")
        executable = string (field (planned plan "app-store") "bin-file")
    native <- runExe env project Nothing 60 executable []
    assertSuccess native
    assertEqual "Custom Setup native and THC output" (out native) (out first)
    assertBool "real Cabal grouped library dependencies include transitive leaf"
      (leafId `elem` map string runtimeDeps)
    assertBool "real Custom Setup uses its host-only package" (setupId `elem` map string setupDeps)
    manifest <- readJson (output </> "packages.json")
    assertEqual "guest dependencies are exactly the library component's"
      runtimeDeps (array (field (described manifest identifier) "depends"))
    assertBool "Setup-only library is absent from guest closure"
      (all ((/= setupId) . string . (`field` "id")) (objects manifest "units"))
    let leafBundle = string (field (field (described manifest leafId) "bundle") "path")
    stamp <- getModificationTime leafBundle
    assertReachable output identifier
    audit <- readJson (output </> "audit.json")
    assertBool "transitive OPAQUE value is genuinely reached" $ any
      ((== leafId ++ ":RuntimeLeaf.leafValue") . string . (`field` "id"))
      (objects audit "reachableBindings")
    second <- invoke "bytecode"
    assertSuccess second
    assertNoStdout second
    assertBackend "bytecode" second
    assertEqual "transitive captured bundle is reused" stamp =<< getModificationTime leafBundle

sourceDist :: Env -> FilePath -> FilePath -> IO ()
sourceDist env source project = do
  result <- runExe env source Nothing 60 "cabal" ["sdist", "--output-dir", project]
  assertSuccess result

assertReachable :: FilePath -> String -> IO ()
assertReachable output identifier = do
  audit <- readJson (output </> "audit.json")
  assertBool "strict package audit accepted" (bool $ field audit "accepted")
  assertEqual "no missing globals" [] (array $ field audit "missingGlobals")
  assertBool "OPAQUE external binding reached" $ any
    ((== identifier ++ ":Answer.answerValue") . string . (`field` "id"))
    (objects audit "reachableBindings")

assertBackend :: String -> Result -> IO ()
assertBackend backend result = do
  unless (not (null (lines $ err result))) (fail "missing THC diagnostics")
  diagnostics <- json (last $ lines $ err result)
  assertEqual "backend" backend (string $ field diagnostics "backend")
  assertEqual "external value forced" 1
    (number $ field (field diagnostics "thunkEvaluationsByLabel") "answerValue")
  assertEqual "no traps" 0 (number $ field diagnostics "unsupportedTraps")

withCache :: FilePath -> IO a -> IO a
withCache path action = bracket acquire restore (const action)
  where
    acquire = do
      prior <- lookupEnv "THC_CACHE_HOME"
      setEnv "THC_CACHE_HOME" path
      pure prior
    restore = maybe (unsetEnv "THC_CACHE_HOME") (setEnv "THC_CACHE_HOME")

one :: (a -> Bool) -> [a] -> a
one predicate values = case filter predicate values of
  [value] -> value
  _ -> error "expected one Cabal unit"
