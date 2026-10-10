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
module StoreProjectTests (tests, storeProjectTest, nativeVariantsTest, inplaceTests, concurrentTests, exportSafetyTests, proxyOptionsTest, staticExportsTest, customStoreProjectTest) where

import Control.Concurrent (forkFinally, killThread, newEmptyMVar, putMVar, readMVar)
import Control.Exception (bracket, finally, throwIO)
import Control.Monad (forM_, unless)
import qualified Data.Aeson as Aeson
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.List (sort, stripPrefix)
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import qualified Distribution.InstalledPackageInfo as Package
import System.Directory (getModificationTime, getPermissions, removeFile,
                         removePathForcibly, setPermissions)
import qualified System.Directory as Directory
import System.Environment (getEnvironment, lookupEnv)
import System.Exit (ExitCode(..))
import System.FilePath ((</>), takeDirectory)
import qualified System.Process as Process
import System.Timeout (timeout)
import Test.HUnit (Test(..), assertBool, assertEqual)
import TestSupport
import THC.Driver.GhcProxy (ghcProxyCommand, directPlugin)
import THC.Driver.PackageNative (nativeSignatures, finishPackageNative)
import THC.Driver.Installed (boundedInterfaceProcessIn)
import THC.Compact.Module (readModuleValue)

tests :: Env -> Test
tests env = TestList [proxyOptionsTest env, staticExportsTest env, storeProjectTest env, customStoreProjectTest env,
  inplaceTests env, concurrentTests env, exportSafetyTests env, nativeVariantsTest env False, nativeVariantsTest env True]

exportSafetyTests :: Env -> Test
exportSafetyTests env = TestLabel "local export preserves inferred safety and rejects Unsafe imports" $ TestCase $
  withFixtureNamed env "t/fixtures/run-store-project" "local safe library" $ \project -> do
    let base = takeDirectory project
        output = base </> "output"
        source = project </> "dep-data/src/SafeDependency.hs"
        arguments = ["acquire", "completed", "--project-dir", project, "--thc-root", thcRoot env,
                     "--dist-dir", output]
        acquire = run env base Nothing 240 arguments
    -- The existing store fixture has an inferred-safe helper and a Safe caller.
    -- Keeping the dependency local exercises Project.freshExport, not GhcProxy.
    writeText (project </> "cabal.project") "packages: app/app.cabal dep-data/dep-data.cabal\n"
    acquired <- runPreparation env base arguments
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
    manifest <- readSourceManifest (output </> "packages.json")
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
    repeated <- readSourceManifest (output </> "packages.json")
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
        directFlags = ["-package-db", string $ field plugin "packageDb", "-fplugin-trustworthy",
          directPlugin (string $ field plugin "sharedLibrary") (string $ field plugin "unitId")
            [base </> "direct-core"] []]
    forM_ [("native", []), ("export", pluginFlags), ("direct", directFlags)] $ \(name, extra) -> do
      rejected <- runExe env (project </> "dep-data") Nothing 60 compiler
        (["--make", "-fno-code", "-fforce-recomp", "-isrc", "src/Answer.hs",
          "-outputdir", base </> ("unsafe-" ++ name)] ++ extra)
      assertFailure rejected
      assertContains "SafeDependency: Can't be safely imported!" (err rejected)
      assertContains "The module itself isn't safe." (err rejected)

concurrentTests :: Env -> Test
concurrentTests env = TestLabel "overlapping project captures share immutable cache publications" $ TestCase $
  withFixtureNamed env "t/fixtures/run-store-project" "first" $ \first ->
  withFixtureNamed env "t/fixtures/run-store-project" "second" $ \second -> do
    let base = takeDirectory first
        source = base </> "dependency-source"
        facade = base </> "facade-source"
        output project = takeDirectory project </> "output"
        invoke project backend = run env (takeDirectory project) (Just backend) 240
          ["run", "--verify-artifacts", "completed", "--project-dir", project, "--thc-root", thcRoot env,
           "--runtime", runtime env, "--dist-dir", output project]
    copyTree (first </> "dep-data") source
    -- Fresh source keeps the raced archive absent from the shared cache while
    -- compiler-library preparation can reuse its unchanged inputs.
    appendFile (source </> "src/Answer.hs") ("\n-- Concurrent capture source: " ++ show source ++ "\n")
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
    -- Acquire the compiler libraries through the existing local dependency.
    -- Its inplace export cannot publish the archived store unit raced below.
    let preparationProject = base </> "preparation.project"
    writeText preparationProject ("packages: " ++ show (source </> "dep-data.cabal") ++ "\n")
    prepared <- runPreparation env base
      ["build", "dep-data:lib:dep-data", "--project-dir", base,
       "--project-file", preparationProject, "--thc-root", thcRoot env,
       "--dist-dir", base </> "prepared"]
    assertSuccess prepared
    assertNoStdout prepared
    completed <- newEmptyMVar
    bracket (forkFinally (invoke second "bytecode") (putMVar completed))
      (\thread -> killThread thread >> readMVar completed >> pure ()) $ \_ -> do
        left <- invoke first "ast"
        right <- readMVar completed >>= either throwIO pure
        forM_ [("ast", left), ("bytecode", right)] $ \(backend, result) -> do
          assertSuccess result
          assertNoStdout result
          assertBackend backend result
    leftPlan <- readJson (output first </> "native/cache/plan.json")
    rightPlan <- readJson (output second </> "native/cache/plan.json")
    leftManifest <- readSourceManifest (output first </> "packages.json")
    rightManifest <- readSourceManifest (output second </> "packages.json")
    let dependency plan manifest =
          let planned = one ((== "dep-data") . string . (`field` "pkg-name")) (objects plan "install-plan")
          in one ((== string (field planned "id")) . string . (`field` "id")) (objects manifest "units")
        leftUnit = dependency leftPlan leftManifest
        rightUnit = dependency rightPlan rightManifest
        identifier = string (field leftUnit "id")
        bundle = field leftUnit "bundle"
        path = string (field bundle "path")
    assertEqual "shared store identity" identifier (string $ field rightUnit "id")
    assertEqual "both manifests retain the same immutable publication" bundle (field rightUnit "bundle")
    bytes <- BS.readFile path
    stamp <- getModificationTime path
    forM_ [(first, leftPlan), (second, rightPlan)] $ \(project, plan) -> do
      assertReachable (output project) identifier
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
  withFixtureNamed env "t/fixtures/run-store-project" "inplace dependencies" $ \project -> do
    let base = takeDirectory project
        dependency = base </> "dependency-source"
        facade = base </> "facade-source"
        leaf = project </> "local-leaf"
        leafSource = leaf </> "LocalLeaf.hs"
        output = base </> "output"
        invoke backend = run env base (Just backend) 300
          ["run", "--verify-artifacts", "completed", "--project-dir", project, "--thc-root", thcRoot env,
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
    prepared <- runPreparation env base
      ["build", "--project-dir", project, "completed", "--thc-root", thcRoot env,
       "--dist-dir", output]
    assertSuccess prepared
    assertNoStdout prepared
    first <- invoke "ast"
    assertSuccess first
    assertNoStdout first
    assertBackend "ast" first
    plan <- readJson (output </> "native/cache/plan.json")
    let dependencyUnit = planned plan "dep-data"
        identifier = string (field dependencyUnit "id")
        executable = string (field (planned plan "app-store") "bin-file")
        leafDist = string (field (planned plan "local-leaf") "dist-dir")
        leafArtifacts = map ((leafDist </> "build") </>) ["LocalLeaf.o", "LocalLeaf.hi"]
    forM_ ["dep-data", "leaf-facade"] $ \name ->
      assertEqual "Cabal itself chooses inplace archive style" "inplace"
        (string $ field (planned plan name) "style")
    native <- runExe env project Nothing 60 executable []
    assertSuccess native
    assertEqual "unchanged native oracle" (out native) (out first)
    manifest <- readSourceManifest (output </> "packages.json")
    let path = bundlePath manifest identifier
    assertReachable output identifier
    stamp <- getModificationTime path
    second <- invoke "bytecode"
    assertSuccess second
    assertNoStdout second
    assertBackend "bytecode" second
    secondManifest <- readSourceManifest (output </> "packages.json")
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
    configuredManifest <- readSourceManifest (output </> "packages.json")
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
    changedManifest <- readSourceManifest (output </> "packages.json")
    assertBool "local dependency content invalidates inplace Core"
      (bundlePath changedManifest identifier /= path)
    assertReachable output identifier
    changedNative <- runExe env project Nothing 60 executable []
    assertFailure changedNative

staticExportsTest :: Env -> Test
staticExportsTest env = TestLabel "ordinary replay retains mixed static callback provenance" $ TestCase $
  withFixtureNamed env "t/fixtures/run-static-exports" "static callbacks" $ \project -> do
    assertSuccess =<< runExe env (thcRoot env) Nothing 180 "bin/build-compiler.sh" []
    plugin <- readJson (thcRoot env </> "build/compiler/plugin.json")
    compiler <- maybe "ghc" id <$> lookupEnv "GHC"
    libdirResult <- runExe env project Nothing 30 compiler ["--print-libdir"]
    assertSuccess libdirResult
    helper <- Directory.findExecutable "thc-interface" >>= maybe (fail "Missing genuine interface helper") pure
    let unit = "static-export-fixture"
        capture = project </> "capture"
        pieces = project </> "pieces"
        objectDirectory = capture </> unit </> "objects"
        libdir = case lines (out libdirResult) of [value] -> value; _ -> error "Expected one GHC library directory"
        settings = [("THC_PROXY_GHC", compiler), ("THC_PROXY_GLOBAL_UNITS", unit),
          ("THC_PROXY_CAPTURE", capture), ("THC_PROXY_PLUGIN_DB", string $ field plugin "packageDb"),
          ("THC_PROXY_PLUGIN_UNIT", string $ field plugin "unitId"),
          ("THC_PROXY_PLUGIN_LIBRARY", string $ field plugin "sharedLibrary"),
          ("THC_PROXY_NATIVE_PIECES", pieces), ("THC_PROXY_INTERFACE_HELPER", helper),
          ("THC_PROXY_INTERFACE_LIBDIR", libdir), ("THC_PROXY_ROOT", thcRoot env)]
    original <- getEnvironment
    let runProxy arguments = do
          let command = (Process.proc (driver env) ("ghc-proxy" : arguments))
                { Process.cwd = Just project, Process.env = Just (settings ++ filter
                    (\(key,_) -> key `notElem` ("THC_PROXY_NATIVE_RECIPES" : "THC_PROXY_NO_LINK_UNIT" : map fst settings)) original) }
          completed <- timeout (120 * 1000000) (Process.readCreateProcessWithExitCode command "")
          (status, stdout, stderr) <- maybe (fail "Static-export proxy timed out") pure completed
          writeText (scratch env </> "static-exports-replay.log") (stdout ++ stderr)
          assertEqual stderr ExitSuccess status
    -- Cabal compiles C translation units separately. Passing C source through
    -- --make's replay would let GHC's -no-link -o overwrite the native oracle.
    runProxy
      ["-c", "-dynamic", "-Icbits", "cbits/callbacks.c", "-o", "callbacks.o"]
    let native = project </> "oracle"
    runProxy ["--make", "-O2", "-dynamic", "-fforce-recomp", "-odir", "objects",
      "-this-unit-id", unit, "-Icbits", "Main.hs", "callbacks.o", "-o", native]
    oracle <- runExe env project Nothing 30 native []
    assertSuccess oracle
    assertEqual "native address-taken callback and StablePtr release" "43\n" (out oracle)
    (status, hydrated, diagnostic) <- boundedInterfaceProcessIn project helper
      ["--libdir", libdir, "--unit", unit, "--module", "NativeExport", "--interface", objectDirectory </> "NativeExport.hi",
       "--home-interfaces", objectDirectory, "--way", "dynamic"]
    assertEqual (show diagnostic) ExitSuccess status
    let staged = capture </> unit </> "NativeExport.cbd"
    BS.writeFile staged hydrated
    Directory.copyFile staged (scratch env </> "static-exports-hydrated.cbd")
    core <- either fail pure (readModuleValue hydrated)
    let imports = field core "staticForeignImports"
        exports = field core "staticForeignExports"
        registration = field core "staticForeignExportRegistration"
        callbacks = objects exports "exports"
    assertEqual "mixed declarations retain typed import proof" "verified" (string $ field imports "status")
    assertEqual "mixed declarations retain explicit product partition" 4 (number $ field imports "schema")
    assertEqual "registration proves the complete original product" (field core "foreign") (field registration "expectedForeign")
    assertEqual "import proof proves the same complete original product" (field core "foreign") (field imports "expectedForeign")
    assertEqual "typed registration is verified" "verified" (string $ field registration "status")
    assertEqual "exactly the source-declared C symbol" ["thc_pkg_callback"] (map (string . (`field` "symbol")) callbacks)
    assertEqual "typed callback roots agree with registration" (map (`field` "binder") callbacks) (array $ field registration "roots")
    assertEqual "native adapter partition has no RTS registration initializer" []
      (array $ field (field (field imports "importForeign") "stubs") "initializers")
    assertBool "real CAPI adapter remains in the import partition"
      (not (null (string $ field (field (field imports "importForeign") "stubs") "source")))
    case nativeSignatures unit [core] of
      Left failure -> assertBool failure False
      Right signatures -> assertEqual "both actual C import declarations remain callable" 2 (length signatures)
    selectedPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
    packageTool <- Directory.findExecutable selectedPkg >>= maybe (fail "ghc-pkg missing") Directory.makeAbsolute
    callbackObject <- Directory.canonicalizePath (project </> "callbacks.o")
    published <- finishPackageNative packageTool pieces (capture </> unit) unit (Just [callbackObject])
      [("NativeExport", staged)]
      `finally` copyTree capture (scratch env </> "static-exports-capture")
    case published of
      [("NativeExport", path)] -> do
        Directory.copyFile path (scratch env </> "static-exports-linked.cbd")
        linked <- BS.readFile path >>= either fail pure . readModuleValue
        let nativeLink = field linked "packageNativeLink"
        assertEqual "managed externals remain in verified LLVM" "llvm-bitcode" (string $ field nativeLink "format")
        let inputs = field nativeLink "buildInputs"
            managed = ["hs_free_stable_ptr", "thc_pkg_callback"]
            nativeSymbols = concatMap (strings . (`field` "symbols")) (objects inputs "nativeLibraries")
        assertBool "ordinary C dependency has a native companion" (not $ null nativeSymbols)
        assertBool "native companion does not own the managed callback or StablePtr release"
          (all (`notElem` nativeSymbols) managed)
        assertEqual "unresolved symbols retain the managed and native partitions"
          (sort $ managed ++ nativeSymbols) (sort $ strings $ field inputs "unresolved")
        assertEqual "native publication preserves authentic callback registration"
          registration (field linked "staticForeignExportRegistration")
      _ -> fail "Static-export publication changed module identity"

proxyOptionsTest :: Env -> Test
proxyOptionsTest env = TestLabel "compiler proxy preserves arguments and replay provenance" $ TestCase $
  withFixtureNamed env "t/fixtures/run-store-project" "proxy" $ \project -> do
    let compiler = project </> "compiler.sh"
        wrapper = project </> "ghc-proxy.sh"
        arguments = project </> "compiler-arguments.txt"
        response = project </> "compiler response.txt"
        settings = [("THC_PROXY_DRIVER", driver env),
                    ("THC_PROXY_GHC", compiler), ("THC_PROXY_GLOBAL_UNITS", "sample\n"),
                    ("THC_PROXY_CAPTURE", project </> "capture"),
                    ("THC_PROXY_PLUGIN_DB", project </> "plugin-db"),
                    ("THC_PROXY_PLUGIN_UNIT", "thc-plugin"),
                    ("THC_PROXY_PLUGIN_LIBRARY", project </> "plugin café.so"),
                    ("THC_PROXY_NO_LINK_UNIT", ""),
                    ("THC_PROXY_ARGUMENTS", arguments)]
    writeText compiler "#!/bin/sh\nprintf 'BEGIN\\0' >> \"$THC_PROXY_ARGUMENTS\"\nprintf '%s\\0' \"$@\" >> \"$THC_PROXY_ARGUMENTS\"\n"
    writeText wrapper ("#!/bin/sh\n" ++ ghcProxyCommand)
    writeText response "--make\n-this-unit-id\nsample\n-fplugin-opt=THC.Plugin:closure=response\n+RTS\n-A8m\n-RTS\n"
    forM_ [compiler, wrapper] $ \path -> do
      permissions <- getPermissions path
      setPermissions path permissions { Directory.executable = True }
    original <- getEnvironment
    let coreVariables = ["THC_PROXY_CORE_LIBDIR", "THC_PROXY_CORE_DATABASES", "THC_PROXY_CORE_INTERFACES", "THC_PROXY_GHC_PKG"]
        environment = settings ++ filter ((`notElem` (coreVariables ++ map fst settings)) . fst) original
        ordinary = [ (True, ["--make", "-this-unit-id", "sample", "+RTS", "-A8m", "-RTS"])
                , (False, ["--numeric-version", "+RTS", "-A8m", "-RTS", "--",
                           "space and café", "", "line\nbreak", "\"quoted\"", "$literal"])
                , (True, ["@" ++ response])
                , (True, ["--make", "-this-unit-id", "sample", "-g0"])
                , (True, ["--make", "-this-unit-id", "sample", "-g2"])
                , (False, ["--numeric-version", "--RTS", "+RTS", "-A8m", "-RTS"])
                ]
        ordinaryCases = [(replays, False, "", supplied) | (replays, supplied) <- ordinary] ++
          [(True, True, "sample", ["--make", "-this-unit-id", "sample",
             "-fplugin-opt=THC.Plugin:closure=first", "-fplugin-opt", "THC.Plugin:closure=second"]),
           (True, True, "sample", ["--make", "-this-unit-id=sample"]),
           (True, True, "sample", ["@" ++ response]),
           (False, False, "sample", ["--make", "-this-unit-id", "sample-tool"]),
           (False, False, "sample", ["--numeric-version"]),
           (True, True, "sample\nguest-two\n", ["--make", "-this-unit-id", "sample"]),
           (False, True, "sample\nguest-two\n", ["--make", "-this-unit-id", "guest-two"]),
           (False, False, "sample\nguest-two\n", ["--make", "-this-unit-id", "sample-tool"])]
        cases = [(False, value) | value <- ordinaryCases] ++
          [(True, (False, False, "", ["--make", "-this-unit-id", "sample", "-fno-code"])),
           (True, (True, False, "", ["--make", "-this-unit-id", "sample"]))]
    forM_ cases $ \(originalBuild, (replays, noLink, policy, supplied)) -> do
      writeText arguments ""
      let command = (Process.proc wrapper supplied)
            { Process.cwd = Just project, Process.env = Just
                (("THC_PROXY_NO_LINK_UNIT", policy) : ("THC_PROXY_ORIGINAL_BUILD", if originalBuild then "1" else "") :
                  filter ((`notElem` ["THC_PROXY_NO_LINK_UNIT", "THC_PROXY_ORIGINAL_BUILD"]) . fst) environment) }
      (status, _, stderr) <- Process.readCreateProcessWithExitCode command ""
      assertEqual stderr ExitSuccess status
      calls <- splitArguments <$> readText arguments
      let (native, replay) = break (== "BEGIN") (drop 1 calls)
          externalPrefix = "-fplugin-library=" ++ project </> "plugin café.so" ++ ";thc-plugin;THC.Plugin;"
      assertEqual "native arguments retain the original response files and RTS flags"
        supplied (take (length supplied) native)
      let additions = drop (length supplied) native
      if noLink then do
        assertEqual "guest-only compilation omits linking and loads the exact plugin database"
          ["-no-link", "-package-db", project </> "plugin-db", "-fplugin-trustworthy"] (take 4 additions)
        assertEqual "guest-only compilation loads one actual plugin" 5 (length additions)
        specification <- maybe (fail "missing guest-only plugin") pure
          (stripPrefix externalPrefix (last additions))
        let pluginOptions = read specification :: [String]
            wanted = if supplied == ["@" ++ response] then ["closure=response"]
              else if "-fplugin-opt" `elem` supplied then ["closure=first", "closure=second"] else []
        assertEqual "guest-only parser rewrite preserves caller plugin options"
          ([project </> "capture/guest-core", "post-tidy", "unit-qualified", "foreign-import-provenance"] ++ wanted) pluginOptions
      else assertEqual "ordinary compiler calls stay unchanged" [] additions
      assertEqual "only selected Core compilations replay" (if replays then 2 else 1)
        (length $ filter (== "BEGIN") calls)
      if replays then do
        assertEqual "Core replay retains original arguments exactly" supplied
          (take (length supplied) (drop 1 replay))
        assertBool "replay does not change native debug settings or hidden binder identities"
          (all (`notElem` ["-g", "-g0", "-g1", "-g2", "-g3"])
            (drop (length supplied) (drop 1 replay)))
        let specifications = [value | Just value <- map (stripPrefix externalPrefix) replay]
        assertEqual "replay loads exactly one actual direct plugin" 1 (length specifications)
        pluginOptions <- case specifications of
          [value] -> pure (read value :: [String])
          _ -> fail "direct plugin specification missing"
        assertEqual "required output and provenance options precede caller additions"
          [project </> "capture/sample/core", "post-tidy", "unit-qualified", "source-notes", "foreign-import-provenance",
           "foreign-export-associations", "foreign-export-registration"]
          (take 7 pluginOptions)
        assertEqual "exactly one provenance opt-in" 1 (length $ filter (== "foreign-import-provenance") pluginOptions)
        let wanted = if supplied == ["@" ++ response] then ["closure=response"]
              else if "-fplugin-opt" `elem` supplied then ["closure=first", "closure=second"] else []
        assertEqual "caller plugin options and response-file options survive in order" wanted (drop 7 pluginOptions)
        assertBool "ordinary plugin loading does not eagerly link guest dependencies" ("-fplugin=THC.Plugin" `notElem` replay)
      else pure ()
    -- Real package databases exercise registration precedence and preserve
    -- native fields, while the recorder makes native/replay routing visible.
    ghc <- maybe "ghc" id <$> lookupEnv "GHC"
    pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
    let invoke options input = do
          (status, output, diagnostic) <- Process.readProcessWithExitCode pkg options input
          assertEqual diagnostic ExitSuccess status
          pure output
        database = project </> "native.db"
        warm = project </> "warm interfaces"
        fresh = project </> "capture/fresh/objects"
        unready = project </> "capture/unready/objects"
        parseInfo text = either (fail . show) (pure . snd)
          (Package.parseInstalledPackageInfo text)
    (_, libdirText, _) <- Process.readProcessWithExitCode ghc ["--print-libdir"] ""
    libdir <- case lines libdirText of [path] -> pure path; _ -> fail "missing GHC libdir"
    _ <- invoke ["init", database] ""
    forM_ [warm, fresh, unready] (Directory.createDirectoryIfMissing True)
    forM_ ["warm", "fresh", "unready"] $ \owner -> do
      _ <- invoke ["--package-db=" ++ database, "register", "--force", "-"] $ unlines
        ["name: " ++ owner, "version: 0.1", "id: " ++ owner, "key: " ++ owner,
         "exposed: True", "import-dirs: " ++ show warm, "extra-libraries: original_native"]
      pure ()
    writeText (project </> "capture/fresh/interfaces-ready") ""
    writeText arguments ""
    let supplied = ["--make", "-this-unit-id", "sample", "-clear-package-db",
                    "-global-package-db", "-no-user-package-db", "-package-db", database]
        replaySettings = [("THC_PROXY_CORE_LIBDIR", libdir), ("THC_PROXY_GHC_PKG", pkg),
          ("THC_PROXY_GLOBAL_UNITS", "sample\nfresh\nunready\n"),
          ("THC_PROXY_CORE_INTERFACES", Text.unpack (Text.decodeUtf8 (BL.toStrict (Aeson.encode
            (Map.fromList [("warm" :: String, warm), ("fresh", warm), ("unready", warm)])))))]
        command = (Process.proc wrapper supplied) { Process.cwd = Just project,
          Process.env = Just (replaySettings ++ filter ((`notElem` map fst replaySettings) . fst) environment) }
    (status, _, diagnostic) <- Process.readCreateProcessWithExitCode command ""
    assertEqual diagnostic ExitSuccess status
    calls <- splitArguments <$> readText arguments
    let (native, replay) = break (== "BEGIN") (drop 1 calls)
    assertEqual "Core interface view never changes native compiler arguments" supplied native
    assertBool "only Core replay selects the pinned boot view" (("-B" ++ libdir) `elem` replay)
    let overlays = [path | ("-package-db", path) <- zip replay (drop 1 replay),
                          path /= database, path /= project </> "plugin-db"]
    overlay <- case overlays of [path] -> pure path; _ -> fail "expected one private Core interface database"
    forM_ [("warm", warm), ("fresh", fresh), ("unready", warm)] $ \(owner, directory) -> do
      originalInfo <- parseInfo . Text.encodeUtf8 . Text.pack =<<
        invoke ["--package-db=" ++ database, "--expand-pkgroot", "--ipid", "describe", owner] ""
      selectedInfo <- parseInfo . Text.encodeUtf8 . Text.pack =<<
        invoke ["--package-db=" ++ overlay, "--expand-pkgroot", "--ipid", "describe", owner] ""
      assertEqual "ready replay overrides only importDirs; incomplete output uses warm interfaces"
        (originalInfo { Package.importDirs = [directory] }) selectedInfo
    requireFile (project </> "capture/sample/interfaces-ready")
  where
    splitArguments "" = []
    splitArguments input = let (value, rest) = break (== '\0') input
                           in value : case rest of [] -> []; _:more -> splitArguments more

storeProjectTest :: Env -> Test
storeProjectTest env = TestLabel "source-built Cabal store Core" $ TestCase $
  -- Keep assembler output paths portable; proxy-only cases above cover quotes.
  withFixtureNamed env "t/fixtures/run-store-project" "project café" $ \project -> do
    let base = takeDirectory project
        source = base </> "dependency-source"
        answer = source </> "src/SafeDependency.hs"
        output = base </> "output"
        archive = project </> "dep-data-0.1.0.0.tar.gz"
        invoke backend = run env base (Just backend) 240
          ["run", "--verify-artifacts", "--project-dir", project, "completed", "--thc-root", thcRoot env,
           "--runtime", runtime env, "--dist-dir", output]
        global plan = one (\unit -> string (field unit "style") == "global" &&
                            string (field unit "pkg-name") == "dep-data")
                       (objects plan "install-plan")
        bundle manifest identifier = field (one
          ((== identifier) . string . (`field` "id")) (objects manifest "units")) "bundle"
    copyTree (project </> "dep-data") source
    removePathForcibly (project </> "dep-data")
    sourceDist env source project
    prepared <- runPreparation env base
      ["build", "--project-dir", project, "completed", "--thc-root", thcRoot env,
       "--dist-dir", output]
    assertSuccess prepared
    assertNoStdout prepared
    firstPlan <- readJson (output </> "native/cache/plan.json")
    let firstId = string (field (global firstPlan) "id")
        executable = string (field (one
          (\unit -> string (field unit "component-name") == "exe:completed")
          (objects firstPlan "install-plan")) "bin-file")
    firstManifest <- readSourceManifest (output </> "packages.json")
    let firstPath = string (field (bundle firstManifest firstId) "path")
    firstInner <- readCore firstPath "manifest.json"
    assertEqual "store manifest identifies its Cabal unit" firstId
      (string $ field firstInner "unit")
    firstTime <- getModificationTime firstPath
    -- The first strict run reacquires the checked build and must reuse its
    -- publication. Its audit is independent of the chosen execution backend.
    first <- invoke "ast"
    assertSuccess first
    assertNoStdout first
    assertBackend "ast" first
    assertReachable output firstId
    repeated <- readSourceManifest (output </> "packages.json")
    assertEqual "store ZIP reused" firstPath (string $ field (bundle repeated firstId) "path")
    assertEqual "store ZIP not rewritten" firstTime =<< getModificationTime firstPath
    native <- runExe env project Nothing 60 executable []
    assertSuccess native
    assertEqual "native and THC output" (out native) (out first)
    second <- runExe env base (Just "bytecode") 240 (runtime env)
      ["--verify-artifacts", "--run-executable", '@' : (output </> "packages.json"),
       "main::Main.main", "ghc-internal:GHC.Internal.TopHandler.flushStdHandles", "--", "completed"]
    assertSuccess second
    assertNoStdout second
    assertBackend "bytecode" second
    assertEqual "native and bytecode output" (out native) (out second)

    original <- readText answer
    writeText answer (replaceText "stableValue = 42" "stableValue = 41" original)
    removeFile archive
    sourceDist env source project
    changed <- invoke "ast"
    assertFailure changed
    changedPlan <- readJson (output </> "native/cache/plan.json")
    let changedId = string (field (global changedPlan) "id")
    assertBool "changed source has a new Cabal store ID" (changedId /= firstId)
    changedManifest <- readSourceManifest (output </> "packages.json")
    assertBool "changed source has a new ZIP"
      (string (field (bundle changedManifest changedId) "path") /= firstPath)
    assertReachable output changedId

customStoreProjectTest :: Env -> Test
customStoreProjectTest env = TestLabel "Custom Setup capture recovers failures and retains runtime-only closure" $ TestCase $
  withFixtureNamed env "t/fixtures/run-store-project" "custom project" $ \project -> do
    let base = takeDirectory project
        dependency = base </> "dependency-source"
        leaf = base </> "leaf-source"
        setupOnly = base </> "setup-source"
        output = base </> "output"
        staging = output </> "native/cache/thc/staging"
        marker = base </> "fail-store-capture"
        buildArguments = ["build", "--project-dir", project, "completed", "--thc-root", thcRoot env,
                          "--dist-dir", output]
        invoke backend = run env base (Just backend) 240
          ["run", "--verify-artifacts", "--project-dir", project, "completed", "--thc-root", thcRoot env,
           "--runtime", runtime env, "--dist-dir", output]
        planned plan name = one ((== name) . string . (`field` "pkg-name")) (objects plan "install-plan")
        described manifest identifier = one ((== identifier) . string . (`field` "id")) (objects manifest "units")
    writeText marker ""
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
      ["", "  ghc-options: +RTS -A8m -RTS", "", "custom-setup",
       "  setup-depends: base, Cabal, directory, setup-only ==0.1.0.0"])
    -- The unique marker path gives this failure control fresh package source
    -- identity while compiler libraries continue using the shared cache.
    writeText (dependency </> "Setup.hs") $ unlines
      ["import Control.Monad (when)", "import Distribution.Simple (defaultMain)",
       "import System.Directory (doesFileExist)", "import System.Environment (lookupEnv)",
       "import System.Exit (die)", "import SetupOnly (prepare)",
       "main :: IO ()", "main = do",
       "  selected <- lookupEnv \"THC_PROXY_GLOBAL_UNITS\"",
       "  failing <- doesFileExist " ++ show marker,
       "  when (maybe False (not . null) selected && failing)",
       "    (die \"intentional store capture failure\")",
       "  prepare", "  defaultMain"]
    writeText (dependency </> "src/SafeDependency.hs") $ unlines
      ["module SafeDependency (stableValue) where", "import RuntimeLeaf (leafValue)",
       "stableValue :: Int", "stableValue = leafValue"]
    -- Native compilation and Core replay must preserve compiler RTS
    -- arguments and select completed without building an unrelated executable.
    let appDescription = project </> "app/app.cabal"
    appText <- readText appDescription
    writeText appDescription (appText ++ "\n  ghc-options: +RTS -A8m -RTS\n" ++ unlines
      ["", "executable unrelated", "  main-is: Unrelated.hs", "  hs-source-dirs: app",
       "  build-depends: base >=4.22 && <4.23", "  default-language: Haskell2010"])
    writeText (project </> "app/app/Unrelated.hs")
      "module Main where\nmain :: IO ()\nmain = intentionallyUnbuildableSibling\n"
    mapM_ (\source -> sourceDist env source project) [leaf, setupOnly, dependency]
    writeText (project </> "cabal.project") $ unlines
      ["packages: app/app.cabal dep-data-0.1.0.0.tar.gz runtime-leaf-0.1.0.0.tar.gz setup-only-0.1.0.0.tar.gz"]
    rejected <- runPreparation env base buildArguments
    assertFailure rejected
    assertContains "intentional store capture failure" (out rejected ++ err rejected)
    retained <- Directory.listDirectory staging
    assertBool "failed capture retains diagnostic evidence" (not (null retained))
    complete <- Directory.doesFileExist (output </> "packages.json")
    assertBool "failed capture cannot publish a complete project" (not complete)
    forM_ retained $ \name -> do
      path <- Directory.canonicalizePath (staging </> name)
      assertContains ("capture retained after failure: " ++ path) (err rejected)
    removeFile marker
    prepared <- run env base Nothing 240 buildArguments
    assertSuccess prepared
    assertNoStdout prepared
    assertEqual "successful replay cleans itself without deleting earlier evidence"
      (sort retained) . sort =<< Directory.listDirectory staging
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
        executable = string (field (one ((== "exe:completed") . string . (`field` "component-name"))
          (objects plan "install-plan")) "bin-file")
    native <- runExe env project Nothing 60 executable []
    assertSuccess native
    assertEqual "Custom Setup native and THC output" (out native) (out first)
    assertBool "real Cabal grouped library dependencies include transitive leaf"
      (leafId `elem` map string runtimeDeps)
    assertBool "real Custom Setup uses its host-only package" (setupId `elem` map string setupDeps)
    manifest <- readSourceManifest (output </> "packages.json")
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
  withFixtureNamed env "t/fixtures/run-native-variants" "native variants" $ \project -> do
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
          ["run", "--verify-artifacts", "--project-dir", project, "variants", "--thc-root", thcRoot env,
           "--runtime", runtime env, "--dist-dir", output]
    prepared <- runPreparation env base
      ["build", "--project-dir", project, "variants", "--thc-root", thcRoot env,
       "--dist-dir", output]
    assertSuccess prepared
    assertNoStdout prepared
    first <- invoke "ast"
    assertSuccess first
    assertNoStdout first
    assertBackend "ast" first
    audit <- readJson (output </> "audit.json")
    assertBool "unchanged strict audit accepted both call shapes" (bool $ field audit "accepted")
    assertEqual "no missing foreign or Haskell globals" [] (array $ field audit "missingGlobals")
    second <- runExe env base (Just "bytecode") 240 (runtime env)
      ["--verify-artifacts", "--run-executable", '@' : (output </> "packages.json"),
       "main::Main.main", "ghc-internal:GHC.Internal.TopHandler.flushStdHandles", "--", "variants"]
    assertSuccess second
    assertNoStdout second
    assertBackend "bytecode" second
    plan <- readJson (output </> "native/cache/plan.json")
    let component = one ((== "exe:variants") . string . (`field` "component-name"))
          [unit | unit <- objects plan "install-plan", string (field unit "type") == "configured"]
        identifier = string (field component "id")
    native <- runExe env project Nothing 60 (string $ field component "bin-file") []
    assertSuccess native
    assertNoStdout native
    manifest <- readSourceManifest (output </> "packages.json")
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
  assertEqual "no traps" 0 (number $ field diagnostics "unsupportedTraps")

one :: (a -> Bool) -> [a] -> a
one predicate values = case filter predicate values of
  [value] -> value
  _ -> error "expected one Cabal unit"
