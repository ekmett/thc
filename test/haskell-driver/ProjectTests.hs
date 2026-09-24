-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module ProjectTests (tests) where

import Data.Aeson (Value)
import System.Directory (canonicalizePath)
import System.FilePath ((</>), takeDirectory)
import Test.HUnit (Test(..), assertBool, assertEqual)
import TestSupport

tests :: Env -> Test
tests env = TestLabel "three-package project native versus THC run" $ TestCase $
  withFixture env "test/fixtures/run-project" $ \project -> do
    let base = takeDirectory project
        output = base </> "output"
        source = project </> "dep-data/src/Answer.hs"
        invoke backend target = run env base (Just backend) 240
          ["run", project, "--exe", target, "--thc-root", thcRoot env,
           "--runtime", runtime env, "--dist-dir", output]
        entryOf plan = one (\value -> string (field value "pkg-name") == "app-run" &&
                                      string (field value "component-name") == "exe:completed")
                           (objects plan "install-plan")
        unit manifest identifier = one ((== identifier) . string . (`field` "id"))
                                   (objects manifest "units")
        modulePaths manifest identifier = map (string . (`field` "path"))
                                      (objects (unit manifest identifier) "modules")
    firstPaths <- forBackends env invoke output project entryOf unit modulePaths
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
      (modulePaths manifest depId /= lookupPaths depId firstPaths)
    let entry = entryOf plan
    native <- runExe env project Nothing 60 (string $ field entry "bin-file") []
    assertFailure native

forBackends
  :: Env -> (String -> String -> IO Result) -> FilePath -> FilePath
  -> (Value -> Value) -> (Value -> String -> Value) -> (Value -> String -> [FilePath])
  -> IO [(String, [FilePath])]
forBackends env invoke output project entryOf unit modulePaths = go Nothing
  [ ("ast", "completed"), ("bytecode", "app-run:exe:completed") ]
  where
    go previous [] = pure (maybe [] id previous)
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
      let paths = [(identifier, modulePaths manifest identifier)
                  | identifier <- [depId, helperId, bridgeId, entryId]]
      case previous of
        Nothing -> pure ()
        Just before -> assertEqual "Core cache reused" before paths
      core <- readJson (output </> one (const True) (modulePaths manifest entryId))
      expected <- canonicalizePath (project </> "app-run/app/Main.hs")
      assertBool "source path and content" =<< anyM
        (\file -> do
          path <- canonicalizePath (string $ field file "path")
          pure (path == expected && string (field file "content") /= ""))
        (objects core "sourceFiles")
      go (Just paths) remaining

moduleNames :: Value -> [String]
moduleNames = map (string . (`field` "name")) . (`objects` "modules")

lookupPaths :: String -> [(String, [FilePath])] -> [FilePath]
lookupPaths identifier paths = maybe [] id (lookup identifier paths)

one :: (a -> Bool) -> [a] -> a
one predicate values = case filter predicate values of
  [value] -> value
  _ -> error "expected exactly one plan entry"

anyM :: Monad m => (a -> m Bool) -> [a] -> m Bool
anyM predicate values = or <$> mapM predicate values
