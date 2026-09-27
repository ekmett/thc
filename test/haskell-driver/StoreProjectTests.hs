-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : StoreProjectTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Tests for store project.
module StoreProjectTests (tests, inplaceTests, concurrentTests, exportSafetyTests) where

import Control.Concurrent (forkFinally, killThread, newEmptyMVar, putMVar, readMVar)
import Control.Exception (bracket, throwIO)
import Control.Monad (forM_, unless)
import Data.Char (isHexDigit)
import qualified Data.ByteString as BS
import Data.List (isPrefixOf, sort)
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
import THC.Driver.Lock (withLock)

tests :: Env -> Test
tests env = TestList [proxyOptionsTest env, storeProjectTest env, customStoreProjectTest env,
  inplaceTests env, concurrentTests env, exportSafetyTests env, nativeVariantsTest env False, nativeVariantsTest env True]

exportSafetyTests :: Env -> Test
exportSafetyTests env = TestLabel "local export preserves inferred safety and rejects Unsafe imports" $ TestCase $
  withFixtureNamed env "test/fixtures/run-store-project" "local safe library" $ \project ->
  withCache (takeDirectory project </> "cache") $ do
    let base = takeDirectory project
        output = base </> "output"
        source = project </> "dep-data/src/SafeDependency.hs"
        acquire = run env base Nothing 240
          ["acquire", "completed", "--project-dir", project, "--thc-root", thcRoot env,
           "--dist-dir", output]
    -- The existing store fixture has an inferred-safe helper and a Safe caller.
    -- Keeping the dependency local exercises Project.freshExport, not GhcProxy.
    writeText (project </> "cabal.project") "packages: app/app.cabal dep-data/dep-data.cabal\n"
    acquired <- acquire
    assertSuccess acquired
    assertNoStdout acquired
    plan <- readJson (output </> "native/cache/plan.json")
    let dependency = one ((== "dep-data") . string . (`field` "pkg-name")) (objects plan "install-plan")
        identifier = string (field dependency "id")
        entry = one ((== "exe:completed") . string . (`field` "component-name")) (objects plan "install-plan")
        bundle manifest = string $ field (field (one ((== identifier) . string . (`field` "id"))
          (objects manifest "units")) "bundle") "path"
    assertEqual "local dependency uses freshExport" "local" (string $ field dependency "style")
    native <- runExe env project Nothing 60 (string $ field entry "bin-file") []
    assertSuccess native
    assertNoStdout native
    manifest <- readJson (output </> "packages.json")
    let path = bundle manifest
    receipt <- readCore path "inplace-manifest.json"
    assertEqual "known plugin trust participates in exporter identity" 1
      (length $ filter (== "-fplugin-trustworthy")
        (strings $ field (field receipt "exporter") "options"))
    contents <- readCore path "manifest.json"
    assertEqual "both original modules exported" ["Answer", "SafeDependency"]
      (sort $ map (string . (`field` "name")) (objects contents "modules"))
    stamp <- getModificationTime path
    warm <- acquire
    assertSuccess warm
    assertNoStdout warm
    repeated <- readJson (output </> "packages.json")
    assertEqual "warm export preserves immutable bundle identity" path (bundle repeated)
    assertEqual "warm export does not rewrite bundle" stamp =<< getModificationTime path
    -- Trusting the exporter must not make an explicitly Unsafe source module
    -- safe. Check real GHC both natively and with the same known THC plugin.
    original <- readText source
    writeText source ("{-# LANGUAGE Unsafe #-}\n" ++ original)
    compiler <- maybe "ghc" id <$> lookupEnv "GHC"
    plugin <- readJson (thcRoot env </> "build/compiler/plugin.json")
    let pluginFlags = ["-package-db", string $ field plugin "packageDb",
          "-plugin-package-id", string $ field plugin "unitId",
          "-fplugin=THC.Plugin", "-fplugin-trustworthy"]
    forM_ [("native", []), ("export", pluginFlags)] $ \(name, extra) -> do
      rejected <- runExe env (project </> "dep-data") Nothing 60 compiler
        (["--make", "-fno-code", "-fforce-recomp", "-isrc", "src/Answer.hs",
          "-outputdir", base </> ("unsafe-" ++ name)] ++ extra)
      assertFailure rejected
      assertContains "SafeDependency: Can't be safely imported!" (err rejected)
      assertContains "The module itself isn't safe." (err rejected)

concurrentTests :: Env -> Test
concurrentTests env = TestLabel "overlapping project captures share immutable cache publications" $ TestCase $
  withFixtureNamed env "test/fixtures/run-store-project" "first" $ \first ->
  withFixtureNamed env "test/fixtures/run-store-project" "second" $ \second ->
  withCache (takeDirectory first </> "cache") $ do
    let base = takeDirectory first
        source = base </> "dependency-source"
        facade = base </> "facade-source"
        cache = base </> "cache/core-bundles/v1"
        output project = takeDirectory project </> "output"
        invoke project backend = run env (takeDirectory project) (Just backend) 240
          ["run", "completed", "--project-dir", project, "--thc-root", thcRoot env,
           "--runtime", runtime env, "--dist-dir", output project]
    copyTree (first </> "dep-data") source
    sourceDist env source first
    Directory.copyFile (first </> "dep-data-0.1.0.0.tar.gz") (second </> "dep-data-0.1.0.0.tar.gz")
    forM_ [first, second] $ \project -> removePathForcibly (project </> "dep-data")
    -- The second missing batch overlaps the first but is not identical, so the
    -- two private exports may race to publish the same dep-data cache entry.
    Directory.createDirectoryIfMissing True facade
    writeText (facade </> "dep-extra.cabal") $ unlines
      ["cabal-version: 3.0", "name: dep-extra", "version: 0.1.0.0",
       "license: BSD-3-Clause", "build-type: Simple", "library",
       "  reexported-modules: dep-data:Answer",
       "  build-depends: dep-data ==0.1.0.0", "  default-language: Haskell2010"]
    sourceDist env facade second
    writeText (second </> "cabal.project")
      "packages: app/app.cabal dep-data-0.1.0.0.tar.gz dep-extra-0.1.0.0.tar.gz\n"
    let description = second </> "app/app.cabal"
    original <- readText description
    writeText description (replaceText "dep-data ==0.1.0.0" "dep-data ==0.1.0.0, dep-extra ==0.1.0.0" original)
    Directory.createDirectoryIfMissing True cache
    -- A separate producer retaining the old global lock must not block either
    -- project. This is a synchronization control, not a timing benchmark.
    withLock (cache </> "global-export.lock") $ do
      completed <- newEmptyMVar
      bracket (forkFinally (invoke second "bytecode") (putMVar completed))
        (\thread -> killThread thread >> readMVar completed >> pure ()) $ \_ -> do
          left <- invoke first "ast"
          right <- readMVar completed >>= either throwIO pure
          forM_ [("ast", left), ("bytecode", right)] $ \(backend, result) -> do
            assertSuccess result
            assertNoStdout result
            assertBackend backend result
    leftManifest <- readJson (output first </> "packages.json")
    rightManifest <- readJson (output second </> "packages.json")
    let dependency manifest = one (isPrefixOf "dep-data-" . string . (`field` "id")) (objects manifest "units")
        leftUnit = dependency leftManifest
        rightUnit = dependency rightManifest
        identifier = string (field leftUnit "id")
        bundle = field leftUnit "bundle"
        path = string (field bundle "path")
    assertEqual "shared store identity" identifier (string $ field rightUnit "id")
    assertEqual "both manifests retain the same immutable publication" bundle (field rightUnit "bundle")
    bytes <- BS.readFile path
    stamp <- getModificationTime path
    forM_ [first, second] $ \project -> do
      assertReachable (output project) identifier
      plan <- readJson (output project </> "native/cache/plan.json")
      let executable = string (field (one ((== "exe:completed") . string . (`field` "component-name"))
            (objects plan "install-plan")) "bin-file")
      native <- runExe env project Nothing 60 executable []
      assertSuccess native
      assertNoStdout native
      warm <- invoke project "bytecode"
      assertSuccess warm
      assertEqual "warm publication bytes unchanged" bytes =<< BS.readFile path
      assertEqual "warm publication not replaced" stamp =<< getModificationTime path

inplaceTests :: Env -> Test
inplaceTests env = TestLabel "archive dependency retains its project-local dependency contents" $ TestCase $
  withFixtureNamed env "test/fixtures/run-store-project" "inplace dependencies" $ \project ->
  withCache (takeDirectory project </> "cache") $ do
    let base = takeDirectory project
        dependency = base </> "dependency-source"
        facade = base </> "facade-source"
        leaf = project </> "local-leaf"
        leafSource = leaf </> "LocalLeaf.hs"
        output = base </> "output"
        invoke backend = run env base (Just backend) 300
          ["run", "completed", "--project-dir", project, "--thc-root", thcRoot env,
           "--runtime", runtime env, "--dist-dir", output]
        planned plan name = one ((== name) . string . (`field` "pkg-name")) (objects plan "install-plan")
        described manifest identifier = one ((== identifier) . string . (`field` "id")) (objects manifest "units")
        bundlePath manifest identifier = string (field (field (described manifest identifier) "bundle") "path")
    copyTree (project </> "dep-data") dependency
    removePathForcibly (project </> "dep-data")
    Directory.createDirectoryIfMissing True leaf
    writeText (leaf </> "local-leaf.cabal") $ unlines
      ["cabal-version: 3.0", "name: local-leaf", "version: 0.1.0.0",
       "license: BSD-3-Clause", "build-type: Simple", "library",
       "  exposed-modules: LocalLeaf", "  build-depends: base >=4.22 && <4.23",
       "  default-language: Haskell2010", "  ghc-options: -O2"]
    writeText leafSource "module LocalLeaf (leafValue) where\nleafValue :: Int\nleafValue = 42\n"
    Directory.createDirectoryIfMissing True facade
    writeText (facade </> "leaf-facade.cabal") $ unlines
      ["cabal-version: 3.0", "name: leaf-facade", "version: 0.1.0.0",
       "license: BSD-3-Clause", "build-type: Simple", "library",
       "  reexported-modules: local-leaf:LocalLeaf",
       "  build-depends: local-leaf ==0.1.0.0", "  default-language: Haskell2010"]
    let description = dependency </> "dep-data.cabal"
    original <- readText description
    writeText description (replaceText "build-depends: base >=4.22 && <4.23"
      "build-depends: base >=4.22 && <4.23, leaf-facade ==0.1.0.0" original)
    writeText (dependency </> "src/SafeDependency.hs") $ unlines
      ["{-# LANGUAGE CPP #-}", "module SafeDependency (stableValue) where",
       "import LocalLeaf (leafValue)", "#ifndef THC_INPLACE_DELTA",
       "#define THC_INPLACE_DELTA 0", "#endif",
       "stableValue :: Int", "stableValue = leafValue + (THC_INPLACE_DELTA)"]
    mapM_ (\source -> sourceDist env source project) [facade, dependency]
    writeText (project </> "cabal.project") $ unlines
      ["packages: app/app.cabal local-leaf/local-leaf.cabal",
       "          dep-data-0.1.0.0.tar.gz leaf-facade-0.1.0.0.tar.gz"]
    first <- invoke "ast"
    assertSuccess first
    assertNoStdout first
    assertBackend "ast" first
    plan <- readJson (output </> "native/cache/plan.json")
    let dependencyUnit = planned plan "dep-data"
        identifier = string (field dependencyUnit "id")
        facadeId = string (field (planned plan "leaf-facade") "id")
        executable = string (field (planned plan "app-store") "bin-file")
        leafDist = string (field (planned plan "local-leaf") "dist-dir")
        leafArtifacts = map ((leafDist </> "build") </>) ["LocalLeaf.o", "LocalLeaf.hi"]
    forM_ ["dep-data", "leaf-facade"] $ \name ->
      assertEqual "Cabal itself chooses inplace archive style" "inplace"
        (string $ field (planned plan name) "style")
    native <- runExe env project Nothing 60 executable []
    assertSuccess native
    assertEqual "unchanged native oracle" (out native) (out first)
    manifest <- readJson (output </> "packages.json")
    let path = bundlePath manifest identifier
    facadeInner <- readCore (bundlePath manifest facadeId) "manifest.json"
    assertEqual "reexport-only inplace archive owns no synthetic Core" []
      (objects facadeInner "modules")
    assertBool "exact isolated registration retained"
      (not (null (string (field facadeInner "reexportRegistration"))))
    assertReachable output identifier
    stamp <- getModificationTime path
    second <- invoke "bytecode"
    assertSuccess second
    assertNoStdout second
    assertBackend "bytecode" second
    secondManifest <- readJson (output </> "packages.json")
    assertEqual "warm inplace Core bundle reused" path (bundlePath secondManifest identifier)
    assertEqual "warm bundle not rewritten" stamp =<< getModificationTime path
    originalLeafArtifacts <- mapM BS.readFile leafArtifacts
    writeText (project </> "cabal.project.local")
      "package dep-data\n  ghc-options: -DTHC_INPLACE_DELTA=-1\n"
    configured <- invoke "bytecode"
    assertFailure configured
    configuredPlan <- readJson (output </> "native/cache/plan.json")
    assertEqual "package-specific project options retain the inplace ID" identifier
      (string $ field (planned configuredPlan "dep-data") "id")
    assertEqual "package-specific options do not modify the source archive"
      (field dependencyUnit "pkg-src-sha256") (field (planned configuredPlan "dep-data") "pkg-src-sha256")
    assertEqual "package-specific options leave local dependency artifacts unchanged"
      originalLeafArtifacts =<< mapM BS.readFile leafArtifacts
    configuredManifest <- readJson (output </> "packages.json")
    assertBool "inplace package configuration invalidates Core independently of local contents"
      (bundlePath configuredManifest identifier /= path)
    assertReachable output identifier
    configuredNative <- runExe env project Nothing 60 executable []
    assertFailure configuredNative
    removeFile (project </> "cabal.project.local")
    originalLeaf <- readText leafSource
    writeText leafSource (replaceText "leafValue = 42" "leafValue = 41" originalLeaf)
    changed <- invoke "ast"
    assertFailure changed
    changedPlan <- readJson (output </> "native/cache/plan.json")
    assertEqual "inplace ID deliberately stays unchanged" identifier
      (string $ field (planned changedPlan "dep-data") "id")
    assertEqual "archive contents deliberately stay unchanged"
      (field dependencyUnit "pkg-src-sha256") (field (planned changedPlan "dep-data") "pkg-src-sha256")
    changedManifest <- readJson (output </> "packages.json")
    assertBool "local dependency content invalidates inplace Core"
      (bundlePath changedManifest identifier /= path)
    assertReachable output identifier
    changedNative <- runExe env project Nothing 60 executable []
    assertFailure changedNative

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
                , (True, ["--make", "-this-unit-id", "sample", "-g0"])
                , (True, ["--make", "-this-unit-id", "sample", "-g2"])
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
        assertBool "replay does not change native debug settings or hidden binder identities"
          (all (`notElem` ["-g", "-g0", "-g1", "-g2", "-g3"])
            (drop (length supplied) (drop 1 replay)))
        assertEqual "replayed compiler receives exactly one provenance opt-in" 1
          (length $ filter (== flag) replay)
      else pure ()
  where
    splitArguments "" = []
    splitArguments input = let (value, rest) = break (== '\0') input
                           in value : case rest of [] -> []; _:more -> splitArguments more

storeProjectTest :: Env -> Test
storeProjectTest env = TestLabel "source-built Cabal store Core" $ TestCase $
  -- Keep assembler output paths portable; proxy-only cases above cover quotes.
  withFixtureNamed env "test/fixtures/run-store-project" "project café" $ \project ->
  withCache (takeDirectory project </> "cache") $ do
    let base = takeDirectory project
        source = base </> "dependency-source"
        answer = source </> "src/SafeDependency.hs"
        output = base </> "output"
        archive = project </> "dep-data-0.1.0.0.tar.gz"
        invoke backend = run env base (Just backend) 240
          ["run", "--project-dir", project, "completed", "--thc-root", thcRoot env,
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
          ["run", "--project-dir", project, "completed", "--thc-root", thcRoot env,
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

nativeVariantsTest :: Env -> Bool -> Test
nativeVariantsTest env cxx = TestLabel
  ("same native " ++ (if cxx then "C++/ccall-header" else "C") ++ " symbol retains pointer and byte-array variants") $ TestCase $
  withFixtureNamed env "test/fixtures/run-native-variants" "native variants" $ \project ->
  withCache (takeDirectory project </> "cache") $ do
    if cxx then do
      original <- readText (project </> "variants.c")
      writeText (project </> "variants.cc") $ unlines
        ["static_assert(THC_CXX_CONFIGURATION == 7, \"Cabal C++ options must survive replay\");",
         "template<class T> static T read_cpp(T const *p) { return *p; }",
         "extern \"C\" {", replaceText "bytes[i]" "read_cpp(bytes + i)" original, "}"]
      removeFile (project </> "variants.c")
      description <- readText (project </> "native-variants.cabal")
      writeText (project </> "native-variants.cabal")
        (replaceText "c-sources: variants.c" "cxx-sources: variants.cc\n  cxx-options: -std=c++11 -DTHC_CXX_CONFIGURATION=7" description)
      haskell <- readText (project </> "Main.hs")
      writeText (project </> "Main.hs") (replaceText "\"variant_sum\"" "\"variants.h variant_sum\"" haskell)
    else pure ()
    let base = takeDirectory project
        output = base </> "output"
        invoke backend = run env base (Just backend) 240
          ["run", "--project-dir", project, "variants", "--thc-root", thcRoot env,
           "--runtime", runtime env, "--dist-dir", output]
    forM_ ["ast", "bytecode"] $ \backend -> do
      actual <- invoke backend
      assertSuccess actual
      assertNoStdout actual
      diagnostics <- json (last $ lines $ err actual)
      assertEqual "selected backend" backend (string $ field diagnostics "backend")
      assertEqual "no runtime traps" 0 (number $ field diagnostics "unsupportedTraps")
      audit <- readJson (output </> "audit.json")
      assertBool "unchanged strict audit accepted both call shapes" (bool $ field audit "accepted")
      assertEqual "no missing foreign or Haskell globals" [] (array $ field audit "missingGlobals")
    plan <- readJson (output </> "native/cache/plan.json")
    let component = one ((== "exe:variants") . string . (`field` "component-name"))
          [unit | unit <- objects plan "install-plan", string (field unit "type") == "configured"]
        identifier = string (field component "id")
    native <- runExe env project Nothing 60 (string $ field component "bin-file") []
    assertSuccess native
    assertNoStdout native
    manifest <- readJson (output </> "packages.json")
    let unit = one ((== identifier) . string . (`field` "id")) (objects manifest "units")
        bundle = string (field (field unit "bundle") "path")
        modulePath = string (field (one ((== "Main") . string . (`field` "name")) (objects unit "modules")) "path")
    core <- readCore bundle modulePath
    if cxx then do
      let declarations = objects (field core "staticForeignImports") "imports"
      assertBool "ordinary ccall retains its original header name"
        (all ((== "variants.h") . string . (`field` "header")) declarations)
      let inputs = field (field core "packageNativeLink") "buildInputs"
          units = array (field inputs "translationUnits")
          cppUnits = [value | value <- drop 1 units, string (field value "language") == "c++"]
      assertEqual "exactly one compiled C++ source receipt" 1 (length cppUnits)
    else pure ()
    let abi = objects (field core "packageNativeLink") "abi"
    assertEqual "one real C symbol" ["variant_sum", "variant_sum"] (map (string . (`field` "symbol")) abi)
    assertEqual "two exact semantic carrier adapters" [["AddrRep", "WordRep"], ["ByteArray#", "WordRep"]]
      (map (map string . array . (`field` "arguments")) abi)
    case abi of
      [address, bytes] -> assertBool "the variants have distinct component entrypoints"
        (field address "entry" /= field bytes "entry")
      _ -> fail "expected exactly two native ABI variants"

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
