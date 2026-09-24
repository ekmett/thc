-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module ProjectTests (tests) where

import Codec.Archive.Zip (findEntryByPath, fromEntry, toArchiveOrFail)
import Control.Exception (bracket)
import Control.Monad (forM_)
import Data.Aeson (Value, eitherDecode')
import qualified Data.ByteString.Lazy as BL
import Data.List (isPrefixOf)
import System.Directory (canonicalizePath, copyFile, createDirectoryIfMissing,
                         doesFileExist, getModificationTime)
import System.Environment (lookupEnv, setEnv, unsetEnv)
import System.FilePath ((</>), splitDirectories, takeDirectory)
import Test.HUnit (Test(..), assertBool, assertEqual)
import TestSupport

tests :: Env -> Test
tests env = TestLabel "three-package project native versus THC run" $ TestCase $
  -- GHC 9.14's Linux -g assembler cannot quote a double quote in .file paths.
  -- Plan/run tests retain the quoted Unicode path coverage.
  withFixtureNamed env "test/fixtures/run-project" "project café" $ \project ->
  withCache (takeDirectory project </> "cache") $ do
    let base = takeDirectory project
        output = base </> "output"
        source = project </> "dep-data/src/Answer.hs"
        sourceOnlyRoot = base </> "THC source only"
        invoke backend target = run env base (Just backend) 240
          ["run", project, "--exe", target, "--thc-root", sourceOnlyRoot,
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
    firstBundles <- forBackends env invoke output project entryOf unit bundleRef modulePath
    requireFile (sourceOnlyRoot </> "build/compiler/plugin.json")
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
          depId = string (field dep "id")
          helperId = string (field helper "id")
          bridgeId = string (field bridge "id")
          entryId = string (field entry "id")
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
      let bridgeArgs = strings (field (one (const True) $ objects bridgeInfo "components") "compiler-args")
      assertBool "native TH helper in compiler args" (helperId `elem` bridgeArgs)

      manifest <- readJson (output </> "packages.json")
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

lookupBundle :: String -> [(String, (FilePath, String))] -> (FilePath, String)
lookupBundle identifier bundles = maybe (error "missing bundle") id (lookup identifier bundles)

readCore :: FilePath -> FilePath -> IO Value
readCore bundle member = do
  bytes <- BL.readFile bundle
  archive <- either fail pure (toArchiveOrFail bytes)
  entry <- maybe (fail ("missing ZIP member " ++ member)) pure
           (findEntryByPath member archive)
  either fail pure (eitherDecode' $ fromEntry entry)

one :: (a -> Bool) -> [a] -> a
one predicate values = case filter predicate values of
  [value] -> value
  _ -> error "expected exactly one plan entry"

anyM :: Monad m => (a -> m Bool) -> [a] -> m Bool
anyM predicate values = or <$> mapM predicate values
