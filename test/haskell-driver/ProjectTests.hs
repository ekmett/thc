-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module ProjectTests (tests) where

import Control.Exception (bracket)
import Control.Monad (forM, forM_)
import Data.Aeson (Value)
import Data.List (isInfixOf, isPrefixOf, sort)
import System.Directory (canonicalizePath, copyFile, createDirectoryIfMissing,
                         doesFileExist, getModificationTime, listDirectory)
import System.Environment (lookupEnv, setEnv, unsetEnv)
import System.FilePath ((</>), splitDirectories, takeDirectory, takeFileName)
import Test.HUnit (Test(..), assertBool, assertEqual)
import qualified THC.Driver.NativeRecipe as NativeRecipe
import TestSupport

tests :: Env -> Test
tests env = TestList [projectTests env, cstringTests env]

projectTests :: Env -> Test
projectTests env = TestLabel "three-package project native versus THC run" $ TestCase $
  -- GHC 9.14's Linux -g assembler cannot quote a double quote in .file paths.
  -- Plan/run tests retain the quoted Unicode path coverage.
  withFixtureNamed env "test/fixtures/run-project" "project café" $ \project ->
  -- Content-keyed wired bundles survive fresh fixture directories; in-place
  -- project bundles still belong to this fixture's native build directory.
  withCache (scratch env </> "core-cache") $ do
    let base = takeDirectory project
        output = base </> "output"
        source = project </> "dep-data/src/Answer.hs"
        sourceOnlyRoot = base </> "THC source only"
        invoke backend target = run env base (Just backend) 240
          ["run", project, "--exe", target, "--thc-root", thcRoot env,
           "--runtime", runtime env, "--dist-dir", output]
        entryOf plan = one (\value -> string (field value "pkg-name") == "app-run" &&
                                      string (field value "component-name") == "exe:completed")
                           (objects plan "install-plan")
        unit manifest identifier = one ((== identifier) . string . (`field` "id"))
                                   (objects manifest "units")
        bundleRef manifest identifier = let bundle = field (unit manifest identifier) "bundle" in
          (string $ field bundle "path", string $ field bundle "sha256")
        modulePath manifest identifier = string $ field
          (one (const True) $ objects (unit manifest identifier) "modules") "path"
    -- The first project run must bootstrap the ordinary Cabal plugin library.
    -- Keep this source-only root private so shared compiler artifacts and other
    -- worktrees are never renamed or deleted during the test.
    forM_ ["compiler", "scripts", "src", "app", "test"] $ \directory ->
      copyTree (root env </> directory) (sourceOnlyRoot </> directory)
    forM_ ["thc.cabal", "cabal.project", "Setup.hs", "LICENSE", "LICENSE.txt", "README.md"] $ \name ->
      copyFile (root env </> name) (sourceOnlyRoot </> name)
    createDirectoryIfMissing True (sourceOnlyRoot </> "docs")
    copyFile (root env </> "docs/driver.md") (sourceOnlyRoot </> "docs/driver.md")
    initiallyBuilt <- doesFileExist (sourceOnlyRoot </> "build/compiler/plugin.json")
    assertBool "source-only checkout has no plugin manifest" (not initiallyBuilt)
    bootstrap <- run env base Nothing 120
      ["run", project, "--exe", "missing-bootstrap-probe", "--thc-root", sourceOnlyRoot,
       "--runtime", runtime env, "--dist-dir", output]
    assertFailure bootstrap
    assertNoStdout bootstrap
    assertContains "selected executable \"missing-bootstrap-probe\" has 0 matching local Cabal components"
      (unwords $ words $ err bootstrap)
    let pluginPath = sourceOnlyRoot </> "build/compiler/plugin.json"
    requireFile pluginPath
    plugin <- readJson pluginPath
    assertEqual "plugin manifest schema" 1 (number $ field plugin "schema")
    let pluginUnit = string $ field plugin "unitId"
        pluginDb = string $ field plugin "packageDb"
        shared = string $ field plugin "sharedLibrary"
        registered = string $ field plugin "cabalSharedLibrary"
    requireFile shared
    requireFile registered
    expectedPluginDir <- canonicalizePath (sourceOnlyRoot </> "build/compiler")
    actualPluginDir <- canonicalizePath (takeDirectory shared)
    assertEqual "published plugin directory" expectedPluginDir actualPluginDir
    packageTool <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
    let registeredField name = runExe env base Nothing 30 packageTool
          ["--unit-id", "field", pluginUnit, name, "--simple-output",
           "--package-db", pluginDb]
    registeredId <- registeredField "id"
    assertSuccess registeredId
    assertEqual "registered plugin unit" [pluginUnit] (words $ out registeredId)
    libraryDirs <- registeredField "dynamic-library-dirs"
    assertSuccess libraryDirs
    assertContains (takeDirectory registered) (out libraryDirs)
    assertEqual "published library filename"
      (takeFileName registered) (takeFileName shared)
    firstBundles <- forBackends env invoke output project entryOf unit bundleRef modulePath
    original <- readText source
    assertContains "I# 42#" original
    writeText source (replaceText "I# 42#" "I# 41#" original)
    changed <- invoke "ast" "completed"
    assertFailure changed
    assertNoStdout changed
    audit <- readJson (output </> "audit.json")
    assertBool "failed action has accepted Core" (bool $ field audit "accepted")
    manifest <- readJson (output </> "packages.json")
    plan <- readJson (output </> "native/cache/plan.json")
    let dependency = one ((== "dep-data") . string . (`field` "pkg-name"))
                         (objects plan "install-plan")
        depId = string (field dependency "id")
    assertBool "dependency cache invalidated"
      (bundleRef manifest depId /= lookupBundle depId firstBundles)
    let entry = entryOf plan
    native <- runExe env project Nothing 60 (string $ field entry "bin-file") []
    assertFailure native
    cleaned <- runExe env project Nothing 60 "cabal" ["clean", "--builddir", output </> "native"]
    assertSuccess cleaned
    forM_ (map (fst . snd) firstBundles ++ [fst $ bundleRef manifest depId]) $ \path -> do
      remains <- doesFileExist path
      assertBool "cabal clean removes in-place Core bundles" (not remains)

cstringTests :: Env -> Test
cstringTests env = TestLabel "pinned ghc-internal CString in package bundle" $ TestCase $
  withFixtureNamed env "test/fixtures/run-cstring" "project café" $ \project ->
  withCache (scratch env </> "core-cache") $ do
    let output = takeDirectory project </> "output"
        invoke backend = run env (takeDirectory project) (Just backend) 240
          ["run", project, "--exe", "cstring", "--thc-root", thcRoot env,
           "--runtime", runtime env, "--dist-dir", output]
        wired manifest = one ((== "ghc-internal") . string . (`field` "id"))
                            (objects manifest "units")
    bundles <- forM ["ast", "bytecode"] $ \backend -> do
      result <- invoke backend
      assertSuccess result
      assertNoStdout result
      diagnostics <- json (last $ lines $ err result)
      assertEqual "backend" backend (string $ field diagnostics "backend")
      assertEqual "unsupported traps" 0 (number $ field diagnostics "unsupportedTraps")
      audit <- readJson (output </> "audit.json")
      assertBool "strict audit" (bool $ field audit "accepted")
      assertEqual "no missing globals" [] (array $ field audit "missingGlobals")
      assertBool "original CString binding reached" $ any
        ((== "ghc-internal:GHC.Internal.CString.unpackCString#") . string . (`field` "id"))
        (objects audit "reachableBindings")
      manifest <- readJson (output </> "packages.json")
      let sourceModules = moduleNames $ wired manifest
      assertBool "original CString source included" ("GHC.Internal.CString" `elem` sourceModules)
      assertBool "original MonadFail source included"
        ("GHC.Internal.Control.Monad.Fail" `elem` sourceModules)
      assertBool "original exception backtrace source included"
        ("GHC.Internal.Exception.Backtrace" `elem` sourceModules)
      assertBool "original Typeable source included"
        ("GHC.Internal.Data.Typeable.Internal" `elem` sourceModules)
      let bundle = string $ field (field (wired manifest) "bundle") "path"
      requireFile bundle
      inner <- readCore bundle "manifest.json"
      inputs <- readCore bundle "inplace-manifest.json"
      let layout = field inner "targetLayout"
          compiler = field inputs "compiler"
          generated = objects inner "generatedSources"
      assertEqual "wired layout receipt matches hashed build inputs"
        layout (field inputs "targetLayout")
      assertEqual "nonprofiling Core way" "dynamic-nonprofiling"
        (string $ field compiler "way")
      assertBool "target word size is supported"
        (number (field layout "wordBytes") `elem` [4, 8])
      assertBool "InfoProv starts inside InfoProvEnt"
        (number (field layout "infoProvEntProvOffset") >
         number (field layout "infoProvEntInfoOffset") &&
         number (field layout "infoProvEntProvOffset") <
         number (field layout "infoProvEntBytes"))
      assertEqual "exact original hsc sources preprocessed"
        (sort ["GHC/Internal/Heap/Constants.hsc", "GHC/Internal/Heap/InfoTable/Types.hsc",
               "GHC/Internal/Heap/InfoTable.hsc", "GHC/Internal/Stack/Constants.hsc",
               "GHC/Internal/InfoProv/Types.hsc", "GHC/Internal/Stack/CCS.hsc",
               "GHC/Internal/ExecutionStack/Internal.hsc"])
        (sort [string (field source "path") | source <- generated])
      assertEqual "generated-source receipts match"
        generated (objects inputs "generatedSources")
      assertImportProvenanceOption inputs
      plan <- readJson (output </> "native/cache/plan.json")
      let entry = one ((== "exe:cstring") . string . (`field` "component-name"))
                      (objects plan "install-plan")
      native <- runExe env project Nothing 60 (string $ field entry "bin-file") []
      assertSuccess native
      assertNoStdout native
      moment <- getModificationTime bundle
      pure (bundle, moment)
    case bundles of
      [first, second] -> assertEqual "wired bundle reused across backends" first second
      _ -> fail "expected AST and bytecode CString bundle results"
    let base = takeDirectory project
        frontier = base </> "fail-frontier"
        frontierOutput = base </> "fail-output"
    copyTree (root env </> "test/fixtures/run-fail-frontier") frontier
    frontierResult <- run env base Nothing 240
      ["run", frontier, "--exe", "fail-frontier", "--thc-root", thcRoot env,
       "--runtime", runtime env, "--dist-dir", frontierOutput]
    assertFailure frontierResult
    assertNoStdout frontierResult
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

assertFailFrontierAudit :: Value -> IO ()
assertFailFrontierAudit audit = do
  assertBool "the partial ghc-internal bundle rejects unresolved MonadFail dependencies"
    (not $ bool $ field audit "accepted")
  let missing = map (string . (`field` "id")) (objects audit "missingGlobals")
      missingName name = any (name `isInfixOf`) missing
      issues = objects audit "issues"
  -- foreignCalls records only calls whose original descriptor, State/head
  -- proofs and capability admission passed; it is not a symbol inventory.
  -- libdwPoolTake is admitted with the original RTS USE_LIBDW=0 behavior.
  forM_ [("stg_cloneMyStackzh", "ghc-internal:GHC.Internal.Exception.Backtrace.$wcollectBacktraces'"),
         ("libdwPoolTake", "ghc-internal:GHC.Internal.ExecutionStack.Internal.collectStackTrace1"),
         ("libdwGetBacktrace", "ghc-internal:GHC.Internal.ExecutionStack.Internal.collectStackTrace1")] $
    \(symbol, owner) -> do
      let calls = filter (\call -> string (field call "symbol") == symbol &&
                                  string (field call "owner") == owner) (objects audit "foreignCalls")
      assertEqual (symbol ++ " has one validated original foreign call in " ++ owner) 1 (length calls)
      let call = one (const True) calls
          sameSite issue = field issue "owner" == field call "owner" &&
                           field issue "path" == field call "path"
      assertBool (symbol ++ " retains its Core expression location")
        ("/expr/" `isPrefixOf` string (field call "path"))
      assertBool (symbol ++ " is neither missing nor rejected at its call site")
        (not (missingName symbol) && not (any sameSite issues))
  -- This pinned source subset still lacks Bignum and encoding definitions, but
  -- the original decoder's foreign operations are now supported. Require their
  -- admission instead of treating the old capability failures as a test result.
  assertBool "missing source definitions explain the rejected partial bundle" (not $ null missing)
  assertEqual "supplied definitions have no unsupported operations" [] issues
  let stackCalls = filter (\call ->
        "ghc-internal:GHC.Internal.Stack.Decode." `isPrefixOf` string (field call "owner"))
        (objects audit "foreignCalls")
  forM_ ["getStackInfoTableAddrzh", "advanceStackFrameLocationzh", "getInfoTableAddrszh",
         "getStackClosurezh", "getSmallBitmapzh", "isArgGenBigRetFunTypezh", "getWordzh",
         "getRetFunSmallBitmapzh", "getUnderflowFrameNextChunkzh", "getStackFieldszh",
         "getBCOLargeBitmapzh", "getLargeBitmapzh", "getRetFunLargeBitmapzh"] $ \symbol -> do
    let calls = filter ((== symbol) . string . (`field` "symbol")) stackCalls
    assertBool (symbol ++ " has a validated original stack-decoder call") (not $ null calls)
    assertBool (symbol ++ " is not missing") (not $ missingName symbol)
    forM_ calls $ \call -> assertBool (symbol ++ " retains its Core expression location")
      ("/expr/" `isPrefixOf` string (field call "path"))
  assertBool "genuine MonadFail/Typeable definitions are supplied"
    (not $ any missingName ["$fMonadFailIO_$cfail", "sameTypeRep", "mkTrCon"])

withCache :: FilePath -> IO a -> IO a
withCache path action = bracket acquire restore (const action)
  where
    acquire = do
      prior <- lookupEnv "THC_CACHE_HOME"
      setEnv "THC_CACHE_HOME" path
      pure prior
    restore = maybe (unsetEnv "THC_CACHE_HOME") (setEnv "THC_CACHE_HOME")

forBackends
  :: Env -> (String -> String -> IO Result) -> FilePath -> FilePath
  -> (Value -> Value) -> (Value -> String -> Value)
  -> (Value -> String -> (FilePath, String)) -> (Value -> String -> FilePath)
  -> IO [(String, (FilePath, String))]
forBackends env invoke output project entryOf unit bundleRef modulePath = go Nothing
  [ ("ast", "completed"), ("bytecode", "app-run:exe:completed") ]
  where
    go previous [] = pure (maybe [] fst previous)
    go previous ((backend, target):remaining) = do
      result <- invoke backend target
      assertSuccess result
      assertNoStdout result
      diagnostics <- json (last $ lines $ err result)
      assertEqual "backend" backend (string $ field diagnostics "backend")
      assertEqual "unsupported traps" 0 (number $ field diagnostics "unsupportedTraps")
      assertEqual "forced thunk" 1 (number $ field diagnostics "thunkEvaluations")
      assertEqual "answerValue forced" 1
        (number $ field (field diagnostics "thunkEvaluationsByLabel") "answerValue")

      plan <- readJson (output </> "native/cache/plan.json")
      let entry = entryOf plan
          install = objects plan "install-plan"
          dep = one ((== "dep-data") . string . (`field` "pkg-name")) install
          helper = one ((== "th-helper") . string . (`field` "pkg-name")) install
          bridge = one ((== "lib:bridge") . string . (`field` "component-name")) install
          optional = one ((== "bench:optional-bench") . string . (`field` "component-name")) install
          depId = string (field dep "id")
          helperId = string (field helper "id")
          bridgeId = string (field bridge "id")
          entryId = string (field entry "id")
          optionalId = string (field optional "id")
      optionalBuilt <- doesFileExist (string $ field optional "build-info")
      assertBool "disabled benchmark has no build-info" (not optionalBuilt)
      native <- runExe env project Nothing 60 (string $ field entry "bin-file") []
      assertSuccess native
      assertEqual "native output" (out result) (out native)
      assertBool "CPP flag" (bool $ field (field dep "flags") "recent")
      assertBool "bridge depends on data" (depId `elem` strings (field bridge "depends"))
      assertBool "bridge depends on TH helper" (helperId `elem` strings (field bridge "depends"))
      assertBool "executable depends on bridge" (bridgeId `elem` strings (field entry "depends"))
      depInfo <- readJson (string $ field dep "build-info")
      let depArgs = strings (field (one (const True) $ objects depInfo "components") "compiler-args")
      assertBool "CPP args from Cabal" ("-optP-DPROJECT_RECENT" `elem` depArgs)
      bridgeInfo <- readJson (string $ field bridge "build-info")
      let bridgeComponent = one (const True) $ objects bridgeInfo "components"
          bridgeArgs = strings (field bridgeComponent "compiler-args")
          bridgeDist = string (field bridge "dist-dir")
      assertBool "native TH helper in compiler args" (helperId `elem` bridgeArgs)
      assertEqual "genuine named library build-info" "lib:bridge" (string $ field bridgeComponent "name")
      bridgeRoots <- NativeRecipe.componentRoots bridgeDist bridgeComponent
      assertEqual "genuine bridge Haskell outputs do not require native receipts" [] =<<
        NativeRecipe.componentNativeObjects (output </> "native") bridgeDist bridgeRoots bridgeComponent
      -- Keep the actual Cabal record after the fixture's deliberate clean, not
      -- just a hand-written shape in the bounded inventory regression.
      copyFile (string $ field bridge "build-info")
        (scratch env </> ("project-bridge-" ++ backend ++ "-build-info.json"))

      manifest <- readJson (output </> "packages.json")
      assertBool "unbuilt optional benchmark is outside the executable Core closure" $
        all ((/= optionalId) . string . (`field` "id")) (objects manifest "units")
      assertEqual "manifest format" "thc-core-packages" (string $ field manifest "format")
      assertEqual "manifest schema" 1 (number $ field manifest "schema")
      assertEqual "helper Core" ["THHelper"] (moduleNames $ unit manifest helperId)
      assertEqual "bridge and autogen Core" ["Bridge", "Paths_app_run"] (moduleNames $ unit manifest bridgeId)
      assertEqual "entry Core" ["Main"] (moduleNames $ unit manifest entryId)
      audit <- readJson (output </> "audit.json")
      assertBool "accepted" (bool $ field audit "accepted")
      assertEqual "no missing globals" [] (array $ field audit "missingGlobals")
      assertBool "imported thunk reachable" $ any
        ((== depId ++ ":Answer.answerValue") . string . (`field` "id"))
        (objects audit "reachableBindings")
      let bundles = [(identifier, bundleRef manifest identifier)
                  | identifier <- [depId, helperId, bridgeId, entryId]]
      forM_ bundles $ \(_, (path, _)) ->
        assertBool "in-place Core stays in Cabal's build directory"
          (splitDirectories (output </> "native") `isPrefixOf` splitDirectories path)
      forM_ bundles $ \(_, (path, _)) ->
        assertImportProvenanceOption =<< readCore path "inplace-manifest.json"
      times <- mapM (getModificationTime . fst . snd) bundles
      case previous of
        Nothing -> pure ()
        Just (before, beforeTimes) -> do
          assertEqual "Core cache reused" before bundles
          assertEqual "cached ZIP files were not rewritten" beforeTimes times
      core <- readCore (fst $ bundleRef manifest entryId) (modulePath manifest entryId)
      expected <- canonicalizePath (project </> "app-run/app/Main.hs")
      assertBool "source path and content" =<< anyM
        (\file -> do
          path <- canonicalizePath (string $ field file "path")
          pure (path == expected && string (field file "content") /= ""))
        (objects core "sourceFiles")
      go (Just (bundles, times)) remaining

moduleNames :: Value -> [String]
moduleNames = map (string . (`field` "name")) . (`objects` "modules")

assertImportProvenanceOption :: Value -> IO ()
assertImportProvenanceOption inputs =
  assertEqual "foreign import proof participates in exporter cache identity" 1
    (length $ filter (== "foreign-import-provenance")
      (strings $ field (field inputs "exporter") "options"))

lookupBundle :: String -> [(String, (FilePath, String))] -> (FilePath, String)
lookupBundle identifier bundles = maybe (error "missing bundle") id (lookup identifier bundles)

one :: (a -> Bool) -> [a] -> a
one predicate values = case filter predicate values of
  [value] -> value
  _ -> error "expected exactly one plan entry"

anyM :: Monad m => (a -> m Bool) -> [a] -> m Bool
anyM predicate values = or <$> mapM predicate values
