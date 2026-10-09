-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : ProjectTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; fixture compiler and host process services
--
-- Tests for project.
module ProjectTests (tests, acquisitionTests, buildTests, exceptionBridgeTests, interopTests, cstringTests) where

import Control.Monad (foldM, forM_, when)
import Data.Aeson (Value)
import qualified Data.ByteString as BS
import THC.Compact.Module (readModuleSources, readModuleValue)
import qualified THC.Driver.CoreIndex as CoreIndex
import THC.Driver.Zip (decodeZip)
import Data.List (isInfixOf, nub, sort)
import System.Directory (copyFile, doesFileExist, getModificationTime, listDirectory)
import System.FilePath ((</>), takeDirectory, takeFileName)
import Test.HUnit (Test(..), assertBool, assertEqual)
import TestSupport

tests :: Env -> Test
tests env = TestList [acquisitionTests env, buildTests env, exceptionBridgeTests env, interopTests env, cstringTests env]

interopTests :: Env -> Test
interopTests env = TestLabel "interop acquisition skips only the selected native final link" $ TestCase $
  withFixtureNamed env "t/fixtures/run-interop" "interop café" $ \project -> do
    writeText (project </> "component-options") "-fplugin-opt=THC.Plugin:pretty-diagnostics\n"
    -- A real source-distribution dependency also forces cold store capture.
    -- Its private rebuild must carry the same selected-unit no-link policy.
    assertSuccess =<< runExe env (project </> "support") Nothing 60 "cabal"
      ["sdist", "--output-dir", project]
    writeText (project </> "cabal.project")
      ("packages: run-interop.cabal interop-support-0.1.0.0.tar.gz " ++ show (thcRoot env </> "thc.cabal") ++ "\n")
    let output = takeDirectory project </> "output"
        arguments target = ["acquire", target, "--project-dir", project, "--thc-root", thcRoot env,
                            "--installed-core", "pinned", "--dist-dir", output]
        acquire target = run env project Nothing 300 (arguments target)
        component plan name = one ((== name) . string . (`field` "component-name")) (objects plan "install-plan")
    acquired <- runPreparation env project (arguments "interop-app")
    assertSuccess acquired
    plan <- readJson (output </> "native/cache/plan.json")
    let entry = component plan "exe:interop-app"
        identifier = string (field entry "id")
    assertBool "no fake native executable is created for the guest-only app" . not
      =<< doesFileExist (string $ field entry "bin-file")
    requireFile (string $ field entry "build-info")
    manifest <- readSourceManifest (output </> "packages.json")
    let record = one ((== identifier) . string . (`field` "id")) (objects manifest "units")
    assertBool "normal acquisition publishes the selected application's original Core"
      (any ((== "Main") . string . (`field` "name")) (objects record "modules"))
    mainCore <- readPublishedCore record
      (one ((== "Main") . string . (`field` "name")) (objects record "modules"))
    assertBool "the selected application retains its real JavaScript import"
      ("javascript-v1" `isInfixOf` show mainCore)
    assertBool "mixed JavaScript and C retains the native adapter"
      ("packageNativeLink" `isInfixOf` show mainCore && "abs" `isInfixOf` show mainCore)
    let support = one ((== "interop-support") . string . (`field` "pkg-name")) (objects plan "install-plan")
        supportRecord = one ((== field support "id") . (`field` "id")) (objects manifest "units")
    assertEqual "source distribution exercises cold store capture" "global" (string $ field support "style")
    assertBool "cold capture publishes the genuine support module"
      (any ((== "InteropSupport") . string . (`field` "name")) (objects supportRecord "modules"))
    copyFile (output </> "packages.json") (scratch env </> "interop-packages.json")
    copyFile (output </> "native/cache/plan.json") (scratch env </> "interop-plan.json")
    -- Export the same Safe application against Cabal's installed component via
    -- the normal helper, not local include paths or a hand-written plugin spec.
    let interop = component plan "lib:interop"
        interopId = string (field interop "id")
        packageDb = output </> "native/packagedb/ghc-9.14.1"
    forM_ [("default", "true", []), ("off", "false", []),
           ("explicit", "false", ["-fplugin-opt=THC.Plugin:source-notes", "-g"])] $
      \(name, notes, extra) -> do
        let exported = scratch env </> takeFileName (takeDirectory project) </> ("interop-helper-" ++ name)
            options = ["-fplugin-opt=THC.Plugin:post-tidy", "-fplugin-opt=THC.Plugin:closure=snapshot",
                       "-fplugin-opt=THC.Plugin:foreign-import-provenance",
                       "-fplugin-opt=THC.Plugin:pretty-diagnostics"] ++ extra
            response = project </> "helper-options"
        writeText response (unlines options)
        result <- runExe env project Nothing 120 "env"
          (["THC_CORE_OUT=" ++ exported, "THC_GHC_OUT=" ++ exported </> "objects",
            "THC_SOURCE_NOTES=" ++ notes, thcRoot env </> "bin/export-core.sh",
            "-i", "-package-db", packageDb, "-package-id", interopId, "-fplugin-trustworthy"] ++
            (if name == "explicit" then ["@" ++ response] else options) ++ [project </> "lib/InteropApi.hs"])
        assertSuccess result
        -- Explicit diagnostics are separate from executable CBD. The response
        -- file must reach the plugin just as direct options do.
        requireFile (exported </> "InteropApi.json")
        bytes <- BS.readFile (exported </> "InteropApi.cbd")
        core <- either fail pure (readModuleValue bytes)
        sources <- either fail pure (readModuleSources bytes)
        assertEqual "caller post-tidy option survives direct loading"
          "optimized-Core-after-Tidy-before-CorePrep" (string $ field core "boundary")
        assertEqual "source-note default, opt-out, and explicit caller opt-in survive"
          (name /= "off") (not (null sources))
        requireFile (exported </> "THC.InterfaceClosure.cbd")
    forM_ ["exe:generator", "exe:ordinary"] $ \name -> do
      -- The build-tool executable is built by the interop app; the ordinary
      -- consumer uses the same driver/native directory after that policy ends.
      if name == "exe:ordinary" then assertSuccess =<< acquire "ordinary" else pure ()
      current <- readJson (output </> "native/cache/plan.json")
      let binary = string (field (component current name) "bin-file")
      requireFile binary
      native <- runExe env project Nothing 30 binary []
      assertSuccess native
      assertEqual "non-selected native programs still link and run" "42\n" (out native)
    copyFile (output </> "packages.json") (scratch env </> "interop-ordinary-packages.json")

exceptionBridgeTests :: Env -> Test
exceptionBridgeTests env = TestLabel "automatic exact exception dictionary linking" $ TestCase $
  withFixtureNamed env "t/fixtures/run-pure" "ordinary project" $ \project -> do
    let base = takeDirectory project
        sidecarOutput = base </> "sidecar-output"
        linkedOutput = base </> "linked-output"
        arguments output = ["acquire", "--project-dir", project, "completed", "--thc-root", thcRoot env,
                            "--dist-dir", output]
        acquire output = run env base Nothing 300 (arguments output)
        selected manifest = string (field manifest "foreignExceptionBridgeUnit")
        runtimeRecord manifest = one ((== selected manifest) . string . (`field` "id"))
          (objects manifest "units")
    before <- readText (project </> "run-pure.cabal")
    first <- runPreparation env base (arguments sidecarOutput)
    assertSuccess first
    manifest <- readSourceManifest (sidecarOutput </> "packages.json")
    let bridge = selected manifest
        record = runtimeRecord manifest
        bundle = string (field (field record "bundle") "path")
    assertBool "automatic runtime unit has actual exported modules" (not (null (objects record "modules")))
    plan <- readJson (sidecarOutput </> "native/runtime-sidecar/dist/cache/plan.json")
    let built = one ((== "lib:runtime") . string . (`field` "component-name")) (objects plan "install-plan")
    assertEqual "bridge identity comes from the genuine sidecar Cabal plan" bridge (string (field built "id"))
    internal <- readCore bundle (string $ field
      (one ((== "THC.Internal.Exception") . string . (`field` "name")) (objects record "modules")) "path")
    assertEqual "exported typed proof retains exact unit" bridge
      (string $ field (field internal "foreignExceptionBridge") "unit")
    inputs <- readCore bundle "inplace-manifest.json"
    assertBool "compiled runtime source artifacts participate in the cache key" $
      any (("THC/Internal/Exception." `isInfixOf`) . string . (`field` "path"))
        (objects inputs "nativeArtifacts")
    assertBool "native exception fallback remains a hashed, observed shim input" $
      any ((== "exception.c") . takeFileName . string . (`field` "path"))
        (objects (field inputs "runtimeShimRecipe") "inputs")
    copyFile bundle (scratch env </> "exception-bridge-sidecar.zip")
    copyFile (sidecarOutput </> "packages.json") (scratch env </> "exception-bridge-sidecar-packages.json")
    copyFile (sidecarOutput </> "native/runtime-sidecar/dist/cache/plan.json")
      (scratch env </> "exception-bridge-sidecar-plan.json")
    assertEqual "user Cabal source untouched" before =<< readText (project </> "run-pure.cabal")
    inventedProject <- doesFileExist (project </> "cabal.project")
    assertBool "private sidecar never writes user project configuration" (not inventedProject)
    repeated <- acquire sidecarOutput
    assertSuccess repeated
    warm <- readSourceManifest (sidecarOutput </> "packages.json")
    assertEqual "warm acquisition retains exact bridge unit" bridge (selected warm)
    assertEqual "warm acquisition reuses the content-keyed runtime bundle"
      (field record "bundle") (field (runtimeRecord warm) "bundle")
    forM_ ["ast", "bytecode"] $ \backend -> do
      executed <- run env base (Just backend) 300
        ["run", "--verify-artifacts", "--project-dir", project, "completed", "--thc-root", thcRoot env,
         "--runtime", runtime env, "--dist-dir", sidecarOutput]
      assertSuccess executed
      assertNoStdout executed
      audit <- readJson (sidecarOutput </> "audit.json")
      assertBool "ordinary app remains strictly accepted with the added runtime bundle"
        (bool $ field audit "accepted")
      copyFile (sidecarOutput </> "audit.json")
        (scratch env </> ("exception-bridge-ordinary-" ++ backend ++ "-audit.json"))
    -- Link the real runtime through Cabal as a dependency. The driver must reuse
    -- that unit even though its inplace name differs from a store installation.
    writeText (project </> "run-pure.cabal")
      (replaceText "build-depends: base" "build-depends: thc:runtime, base" before)
    writeText (project </> "cabal.project")
      ("packages: run-pure.cabal " ++ show (thcRoot env </> "thc.cabal") ++ "\n")
    linked <- runPreparation env base (arguments linkedOutput)
    assertSuccess linked
    linkedManifest <- readSourceManifest (linkedOutput </> "packages.json")
    linkedPlan <- readJson (linkedOutput </> "native/cache/plan.json")
    let runtimeUnit = one ((== "lib:runtime") . string . (`field` "component-name"))
          (objects linkedPlan "install-plan")
    assertEqual "already-linked runtime dictionary is never replaced"
      (string $ field runtimeUnit "id") (selected linkedManifest)
    copyFile (string $ field (field (runtimeRecord linkedManifest) "bundle") "path")
      (scratch env </> "exception-bridge-linked.zip")
    copyFile (linkedOutput </> "packages.json") (scratch env </> "exception-bridge-linked-packages.json")
    unusedSidecar <- doesFileExist (linkedOutput </> "native/runtime-sidecar/cabal.project")
    assertBool "already linked bridge does not build another runtime" (not unusedSidecar)

acquisitionTests :: Env -> Test
acquisitionTests env = TestLabel "project acquisition stops before audit and execution" $ TestCase $
  -- Acquire the original MonadFail/exception program with complete pinned Core.
  -- Acquisition must still stop before audit or execution; cstringTests below
  -- separately requires strict acceptance and the genuine executable lifecycle.
  withFixtureNamed env "t/fixtures/run-fail-frontier" "project café" $ \project -> do
    let base = takeDirectory project
        output = base </> "acquired"
        arguments = ["acquire", "--project-dir", project, "fail-frontier", "--thc-root", thcRoot env,
                     "--dist-dir", output]
    forM_ [["--", "guest"], ["--"], ["--runtime", "/missing/thc"], ["--verify-artifacts"]] $ \extra -> do
      rejected <- run env base Nothing 30 (arguments ++ extra)
      assertFailure rejected
      assertNoStdout rejected
      published <- doesFileExist (output </> "packages.json")
      assertBool "CLI rejects runtime options before acquisition" (not published)
    notProject <- runPreparation env base
      ["acquire", "--project-dir", base, "fail-frontier", "--thc-root", thcRoot env]
    assertFailure notProject
    assertContains "Cabal runnable target selection failed" (err notProject)
    acquired <- runPreparation env base arguments
    assertSuccess acquired
    assertNoStdout acquired
    manifest <- readSourceManifest (output </> "packages.json")
    assertEqual "manifest format" "thc-core-packages" (string $ field manifest "format")
    assertEqual "manifest schema" 1 (number $ field manifest "schema")
    let units = objects manifest "units"
        supplied = [unit | unit <- units, not (null $ objects unit "modules")]
    assertBool "genuine executable and boot-library Core acquired" (length supplied >= 2)
    forM_ supplied $ \unit -> do
      bytes <- BS.readFile (string $ field (field unit "bundle") "path")
      members <- either fail pure =<< decodeZip bytes
      forM_ (objects unit "modules") $ \ref -> do
        assertEqual "fresh production modules declare one Core payload" 1
          (maybe 0 length (CoreIndex.modulePaths ref))
        assertBool "Core member matches the final linked module bytes"
          (maybe False ((== 1) . length) (CoreIndex.moduleEntries ref members))
    plan <- readJson (output </> "native/cache/plan.json")
    let entry = one ((== "exe:fail-frontier") . string . (`field` "component-name"))
                    (objects plan "install-plan")
    requireFile (string $ field entry "bin-file")
    audited <- doesFileExist (output </> "audit.json")
    assertBool "acquisition does not start the reachable auditor" (not audited)
    -- Existing reports are historical evidence, never silently replaced by an
    -- acquisition-only rerun or presented as acceptance of the new manifest.
    writeText (output </> "audit.json") "retained older audit evidence\n"
    identities <- mapM (getModificationTime . string . (`field` "path") . (`field` "bundle")) supplied
    repeated <- run env base Nothing 240 arguments
    assertSuccess repeated
    assertNoStdout repeated
    warm <- readSourceManifest (output </> "packages.json")
    assertEqual "warm acquisition preserves module records" (field manifest "units") (field warm "units")
    assertEqual "warm acquisition never rewrites immutable bundles" identities
      =<< mapM (getModificationTime . string . (`field` "path") . (`field` "bundle")) supplied
    assertEqual "prior audit untouched" "retained older audit evidence\n"
      =<< readText (output </> "audit.json")

buildTests :: Env -> Test
buildTests env = TestLabel "Cabal build targets acquire only their selected closures" $ TestCase $
  withFixtureNamed env "t/fixtures/run-project" "build project café" $ \project -> do
    let base = takeDirectory project
        output = base </> "built"
        marker = base </> "program-executed"
        dep = ("dep-data", "lib", ["Answer"])
        helper = ("th-helper", "lib", ["THHelper"])
        bridge = ("app-run", "lib:bridge", ["Bridge", "Paths_app_run"])
        executable = ("app-run", "exe:completed", ["Main"])
        arguments targets =
          ["build", "--project-dir", project, "--project-file", project </> "cabal.project",
           "--thc-root", thcRoot env, "--dist-dir", output] ++ targets
        build previous (working, targets, expected) = do
          result <- run env working Nothing 300 (arguments targets)
          assertSuccess result
          assertNoStdout result
          checkBuild previous targets expected
        checkBuild previous targets expected = do
          assertBool "build never executes the native or guest program" . not =<< doesFileExist marker
          assertBool "build never starts the reachable auditor" . not =<< doesFileExist (output </> "audit.json")
          manifest <- readSourceManifest (output </> "packages.json")
          assertEqual "manifest format" "thc-core-packages" (string $ field manifest "format")
          plan <- readJson (output </> "native/cache/plan.json")
          let local = [(string (field unit "pkg-name"), string (field unit "component-name"), string (field unit "id")) |
                       unit <- objects plan "install-plan",
                       string (field unit "pkg-name") `elem` ["dep-data", "th-helper", "app-run"]]
              known = nub (previous ++ local)
              selected = [(package, component, sort (moduleNames supplied)) |
                          (package, component, identifier) <- known, supplied <- objects manifest "units",
                          identifier == string (field supplied "id")]
          assertEqual ("selected component closures for " ++ show targets) (sort expected) (sort selected)
          pure known
    -- An accidental launch has an observable effect even though the original
    -- fixture exits silently. This source belongs only to the copied project.
    writeText (project </> "app-run/app/Main.hs") $ unlines
      ["module Main where", "import Bridge (expected)", "main :: IO ()",
       "main = writeFile " ++ show marker ++ " (show expected)"]
    -- This first build owns cold compiler/library acquisition. The outer build
    -- job bounds preparation; subsequent CLI checks retain their own deadline.
    let initialTargets = ["dep-data:lib:dep-data"]
        initialArguments = arguments initialTargets
    initial <- runPreparation env project initialArguments
    assertSuccess initial
    assertNoStdout initial
    acquired <- checkBuild [] initialTargets [dep]
    _ <- foldM build acquired
      [ (project </> "dep-data", [], [dep])
      , (project </> "dep-data", ["all"], [dep, helper, bridge, executable])
      -- A module target acquires its whole component. The dependency union must
      -- deduplicate dep-data and exclude the previously built executable.
      , (project, ["dep-data:lib:dep-data", "app-run:bridge:Bridge"], [dep, helper, bridge])
      ]
    pure ()

cstringTests :: Env -> Test
cstringTests env = TestLabel "pinned CString and MonadFail programs match native execution" $ TestCase $
  withFixtureNamed env "t/fixtures/run-cstring" "project café" $ \project -> do
    let output = takeDirectory project </> "output"
        arguments = ["--project-dir", project, "cstring", "--thc-root", thcRoot env,
                     "--dist-dir", output]
    prepared <- runPreparation env (takeDirectory project) (["build"] ++ arguments)
    assertSuccess prepared
    assertNoStdout prepared
    forM_ ["ast", "bytecode"] $ \backend -> do
      result <- if backend == "ast"
        then run env (takeDirectory project) (Just backend) 240
          (["run", "--verify-artifacts"] ++ arguments ++ ["--runtime", runtime env])
        else runExe env (takeDirectory project) (Just backend) 240 (runtime env)
          ["--verify-artifacts", "--run-executable", '@' : (output </> "packages.json"),
           "main::Main.main", "ghc-internal:GHC.Internal.TopHandler.flushStdHandles", "--", "cstring"]
      assertSuccess result
      assertNoStdout result
      diagnostics <- json (last $ lines $ err result)
      assertEqual "backend" backend (string $ field diagnostics "backend")
      assertEqual "unsupported traps" 0 (number $ field diagnostics "unsupportedTraps")
      when (backend == "ast") $ do
        audit <- readJson (output </> "audit.json")
        assertBool "strict audit" (bool $ field audit "accepted")
        assertExecutableLifecycle audit
        assertEqual "no missing globals" [] (array $ field audit "missingGlobals")
        assertBool "original CString binding reached" $ any
          ((== "ghc-internal:GHC.Internal.CString.unpackCString#") . string . (`field` "id"))
          (objects audit "reachableBindings")
    plan <- readJson (output </> "native/cache/plan.json")
    let cstringEntry = one ((== "exe:cstring") . string . (`field` "component-name"))
                           (objects plan "install-plan")
    cstringNative <- runExe env project Nothing 60 (string $ field cstringEntry "bin-file") []
    assertSuccess cstringNative
    assertNoStdout cstringNative
    let base = takeDirectory project
        frontier = base </> "fail-frontier"
        frontierOutput = base </> "fail-output"
    copyTree (root env </> "t/fixtures/run-fail-frontier") frontier
    forM_ ["ast", "bytecode"] $ \backend -> do
      frontierResult <- if backend == "ast"
        then run env base (Just backend) 240
          ["run", "--verify-artifacts", "--project-dir", frontier, "fail-frontier", "--thc-root", thcRoot env,
           "--runtime", runtime env, "--dist-dir", frontierOutput]
        else runExe env base (Just backend) 240 (runtime env)
          ["--verify-artifacts", "--run-executable", '@' : (frontierOutput </> "packages.json"),
           "main::Main.main", "ghc-internal:GHC.Internal.TopHandler.flushStdHandles", "--", "fail-frontier"]
      assertSuccess frontierResult
      assertNoStdout frontierResult
      diagnostics <- json (last $ lines $ err frontierResult)
      assertEqual "fail-frontier backend" backend (string $ field diagnostics "backend")
      assertEqual "fail-frontier unsupported traps" 0 (number $ field diagnostics "unsupportedTraps")
      when (backend == "ast") $ do
        frontierAudit <- readJson (frontierOutput </> "audit.json")
        assertFailFrontierAudit frontierAudit
    frontierPlan <- readJson (frontierOutput </> "native/cache/plan.json")
    let entry = one ((== "exe:fail-frontier") . string . (`field` "component-name"))
                    (objects frontierPlan "install-plan")
    native <- runExe env frontier Nothing 60 (string $ field entry "bin-file") []
    assertSuccess native
    assertNoStdout native
    staging <- listDirectory (frontierOutput </> "native/cache/thc/staging")
    assertEqual "disposable export staging cleaned" [] staging

assertExecutableLifecycle :: Value -> IO ()
assertExecutableLifecycle audit = do
  let roots = ["main::Main.main", "ghc-internal:GHC.Internal.TopHandler.flushStdHandles"]
      reachable = map (string . (`field` "id")) (objects audit "reachableBindings")
  assertEqual "generated main and original shutdown are audited together" roots
    (strings $ field audit "roots")
  forM_ roots $ \entry -> assertBool (entry ++ " is reachable") (entry `elem` reachable)

assertFailFrontierAudit :: Value -> IO ()
assertFailFrontierAudit audit = do
  assertBool "complete pinned Core accepts the original MonadFail program"
    (bool $ field audit "accepted")
  assertEqual "complete pinned Core resolves every reachable global" []
    (array $ field audit "missingGlobals")
  assertExecutableLifecycle audit
  assertEqual "supplied definitions have no unsupported operations" []
    (objects audit "issues")

moduleNames :: Value -> [String]
moduleNames = map (string . (`field` "name")) . (`objects` "modules")

one :: (a -> Bool) -> [a] -> a
one predicate values = case filter predicate values of
  [value] -> value
  _ -> error "expected exactly one plan entry"
