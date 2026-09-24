-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module PlanTests (tests) where

import Control.Monad (forM, forM_)
import qualified Data.ByteString as BS
import Data.List (sort, sortOn)
import System.Directory
  ( createDirectory, createDirectoryLink, doesFileExist, renameDirectory
  , removeFile, canonicalizePath )
import System.FilePath ((</>), takeDirectory)
import System.Info (os)
import Test.HUnit (Test(..), assertBool, assertEqual)
import TestSupport

tests :: Env -> Test
tests env = TestList
  [ caseOf "real configuration and native artifacts" realArtifacts
  , caseOf "default components and flags" flags
  , caseOf "unknown flag and missing dependency" unknownFlag
  , caseOf "project boundary and explicit file" projectBoundary
  , caseOf "local and freeze project files" projectFiles
  , caseOf "real file discovery and canonical root" discovery
  , caseOf "ambiguous, missing and invalid input" invalidInputs
  , caseOf "custom Setup is rejected without execution" customSetup
  , caseOf "disabled component" disabled
  , caseOf "unsupported detailed test" detailed
  , caseOf "relative dist path" relativeDist
  , caseOf "CLI contract" cli
  ]
  where
    caseOf name body = TestLabel name $ TestCase $ withFixture env "test/fixtures/tiny" $ \package -> do
      let base = takeDirectory package
          cabalFile = package </> "tiny-fixture.cabal"
          dist = base </> "configuration"
          invoke good args = do
            result <- run env base Nothing 180 args
            if good then assertSuccess result else do
              assertFailure result
              assertNoStdout result
            pure result
          plan extra = do
            result <- invoke True (["plan-package", package, "--dist-dir", dist] ++ extra)
            json (out result)
          reject args expected = do
            result <- invoke False args
            assertContains expected (err result)
            pure ()
      body package base cabalFile dist invoke plan reject

    realArtifacts package _ cabalFile dist _ plan _ = do
      before <- fileTree package
      value <- plan ["--enable-tests", "--enable-benchmarks"]
      assertEqual "schema" "thc.cabal-package-plan.v1" (string (field value "schema"))
      assertEqual "stage" "cabal-installed-package-configuration" (string (field value "stage"))
      assertEqual "compiler" "ghc-9.14.1" (string (field value "compiler"))
      assertEqual "Cabal version" "3.16.0.0" (string (field value "cabalVersion"))
      assertBool "not solved" (not $ bool $ field value "solvedProjectPlan")
      assertBool "not built" (not $ bool $ field value "artifactsBuilt")
      assertEqual "package" "tiny-fixture-0.1.0.0" (string (field value "package"))
      canonical <- canonicalizePath package
      canonicalFile <- canonicalizePath cabalFile
      assertEqual "root" canonical (string (field value "packageRoot"))
      assertEqual "cabal file" canonicalFile (string (field value "cabalFile"))
      assertEqual "databases" ["global"] (strings (field value "packageDatabases"))
      assertBool "loud default" (not $ bool $ field (field value "flags") "loud")
      assertBool "unavailable default" (not $ bool $ field (field value "flags") "unavailable")
      requireFile (string (field value "setupConfig"))
      let components = objects value "components"
          componentNames = sort (map (string . (`field` "name")) components)
      assertEqual "components" (sort ["lib", "lib:words", "exe:hello", "test:greeting-test", "bench:greeting-bench"]) componentNames
      let lib = named "lib" components
          wordsLib = named "lib:words" components
          exe = named "exe:hello" components
      assertEqual "exposed" ["Greeting"] (strings (field lib "exposedModules"))
      assertBool "quiet selected" ("quiet" `elem` strings (field lib "sourceDirectories"))
      assertBool "loud not selected" ("loud" `notElem` strings (field lib "sourceDirectories"))
      let platform = if os == "linux" then "Platform.Linux" else "Platform.Other"
      assertEqual "other modules" (sort ["Message", platform]) (sort $ strings $ field lib "otherModules")
      assertBool "CPP option" ("-DRECENT_GHC" `elem` strings (field lib "cppOptions"))
      assertEqual "internal words" [string $ field wordsLib "unitId"] (strings $ field lib "internalDependencies")
      assertEqual "internal lib" [string $ field lib "unitId"] (strings $ field exe "internalDependencies")
      assertEqual "main" "Main.hs" (string $ field exe "mainSource")
      pkg <- runExe env package Nothing 60 "ghc-pkg" ["--global", "--no-user-package-db", "field", "base", "id", "--simple-output"]
      assertSuccess pkg
      assertBool "base unit" (trim (out pkg) `elem` map (string . (`field` "unitId")) (objects exe "dependencies"))
      assertEqual "distinct unit IDs" 5 (length $ unique $ map (string . (`field` "unitId")) components)
      forM_ components $ \component -> do
        exists <- doesFileExist (string $ field component "plannedArtifact")
        assertBool "not built by planning" (not exists)
      build <- runExe env package Nothing 180 "runghc" [root env </> "Setup.hs", "build", "--builddir=" ++ dist]
      assertSuccess build
      forM_ components $ \component -> do
        requireFile (string $ field component "plannedArtifact")
        forM_ (strings (field component "exposedModules") ++ strings (field component "otherModules") ++
               ["Main" | string (field component "mainSource") /= ""]) $ \moduleName ->
          requireFile (string (field component "objectDirectory") </> map dotToSlash moduleName ++ ".o")
      native <- runExe env package Nothing 60 (string $ field exe "plannedArtifact") []
      assertSuccess native
      assertEqual "native exe" "hello Cabal\n" (out native)
      nativeTest <- runExe env package Nothing 60 (string $ field (named "test:greeting-test" components) "plannedArtifact") []
      assertSuccess nativeTest
      after <- fileTree package
      assertEqual "source unchanged" before after

    flags _ _ _ _ _ plan _ = do
      value <- plan ["--flag", "loud"]
      assertBool "loud enabled" (bool $ field (field value "flags") "loud")
      let components = objects value "components"
      assertEqual "default components" (sort ["lib", "lib:words", "exe:hello"])
        (sort $ map (string . (`field` "name")) components)
      assertBool "loud path" ("loud" `elem` strings (field (named "lib" components) "sourceDirectories"))
      assertBool "quiet path" ("quiet" `notElem` strings (field (named "lib" components) "sourceDirectories"))
      disabledValue <- plan ["--flag", "loud", "--flag=-loud"]
      assertBool "last flag wins" (not $ bool $ field (field disabledValue "flags") "loud")

    unknownFlag package _ _ dist _ _ reject = do
      _ <- reject ["plan-package", package, "--flag", "typo"] "unknown package flag"
      reject ["plan-package", package, "--dist-dir", dist, "--flag", "unavailable"] "thc-deliberately-unavailable"

    projectBoundary package base cabalFile _ invoke _ reject = do
      writeText (base </> "cabal.project") "packages: \"tiny project*\"\n"
      _ <- reject ["plan-package", package] "cabal.project configuration is not supported"
      explicit <- invoke True ["plan-package", cabalFile]
      value <- json (out explicit)
      assertEqual "explicit package" "tiny-fixture-0.1.0.0" (string $ field value "package")
      reject ["plan-package", base </> "cabal.project"] "expected a package directory"

    projectFiles package _ _ _ _ _ reject = forM_ ["cabal.project.local", "cabal.project.freeze"] $ \name -> do
      let path = package </> name
      writeText path "constraints: base == 0\n"
      _ <- reject ["plan-package", package] "cabal.project configuration is not supported"
      removeFile path

    discovery package base _ _ _ plan reject = do
      createDirectory (package </> "not-a-file.cabal")
      value <- plan []
      assertEqual "real file discovery" "tiny-fixture-0.1.0.0" (string $ field value "package")
      let project = base </> "project"
          relocated = project </> "fixture"
          elsewhere = base </> "elsewhere"
      createDirectory project
      renameDirectory package relocated
      writeText (project </> "cabal.project") "packages: *\n"
      createDirectory elsewhere
      createDirectoryLink relocated (elsewhere </> "alias")
      reject ["plan-package", elsewhere </> "alias"] "cabal.project configuration is not supported"

    invalidInputs package base cabalFile _ _ _ reject = do
      copy <- readText cabalFile
      writeText (package </> "second.cabal") copy
      _ <- reject ["plan-package", package] "multiple .cabal files"
      removeFile (package </> "second.cabal")
      _ <- reject ["plan-package", base] "no .cabal package"
      _ <- reject ["plan-package", base </> "missing.cabal"] "does not exist"
      writeText cabalFile "this is not a package description\n"
      reject ["plan-package", cabalFile] "tiny-fixture.cabal"

    customSetup package _ cabalFile _ _ _ reject = do
      text <- readText cabalFile
      writeText cabalFile (replaceText "build-type: Simple" "build-type: Custom" text ++ "\ncustom-setup\n  setup-depends: base, Cabal\n")
      writeText (package </> "Setup.hs") "main = writeFile \"executed\" \"bad\"\n"
      _ <- reject ["plan-package", package] "only build-type: Simple"
      exists <- doesFileExist (package </> "executed")
      assertBool "custom Setup not executed" (not exists)

    disabled _ _ cabalFile _ _ plan _ = do
      text <- readText cabalFile
      writeText cabalFile (replaceText "executable hello\n  import: defaults" "executable hello\n  import: defaults\n  buildable: False" text)
      value <- plan []
      assertEqual "disabled" (sort ["lib", "lib:words"]) (sort $ map (string . (`field` "name")) $ objects value "components")

    detailed package _ cabalFile dist _ _ reject = do
      text <- readText cabalFile
      writeText cabalFile $ replaceText "type: exitcode-stdio-1.0\n  hs-source-dirs: test\n  main-is: Main.hs"
        "type: detailed-0.9\n  hs-source-dirs: test\n  test-module: Main" text
      reject ["plan-package", package, "--dist-dir", dist, "--enable-tests"] "only exitcode-stdio-1.0 test suites"

    relativeDist package base _ _ invoke _ _ = do
      result <- invoke True ["plan-package", package, "--dist-dir", "out"]
      value <- json (out result)
      assertEqual "relative path" "out/setup-config" (string $ field value "setupConfig")
      requireFile (package </> "out/setup-config")
      exists <- doesFileExist (base </> "out/setup-config")
      assertBool "no cwd dist" (not exists)

    cli _ _ _ _ invoke _ reject = do
      help <- invoke True ["--help"]
      assertContains "plan-package" (out help)
      assertContains "run" (out help)
      _ <- reject ["run"] "run requires --exe NAME"
      forM_ [["build", "--dry-run"], ["repl"], ["plan-package", "--unknown"], ["plan-package", "a", "b"]] $ \arguments ->
        reject arguments "Usage:"

    dotToSlash '.' = '/'
    dotToSlash character = character
    trim = reverse . dropWhile (== '\n') . reverse
    unique = foldr (\item items -> if item `elem` items then items else item : items) []

fileTree :: FilePath -> IO [(FilePath, BS.ByteString)]
fileTree directory = do
  files <- findFiles directory ""
  sortOn fst <$> forM files (\path -> (,) path <$> BS.readFile path)
