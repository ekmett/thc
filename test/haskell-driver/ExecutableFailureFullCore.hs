-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Main (main) where

import qualified Data.ByteString.Char8 as BS
import Data.List (find, isPrefixOf, isSuffixOf, stripPrefix, tails)
import System.Directory (doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (ExitCode (..), exitFailure)
import System.FilePath ((</>), takeDirectory)
import Test.HUnit (Counts (..), Test (..), assertBool, assertEqual, runTestTT)
import qualified Test.HUnit as HUnit
import TestSupport

main :: IO ()
main = do
  environment <- setup
  counts <- runTestTT (TestList [reportTests, fixture environment])
  if errors counts + failures counts == 0 then pure () else exitFailure

fixture :: Env -> Test
fixture environment = TestLabel "original executable uncaught IO exception" $ TestCase $
  withFixtureNamed environment "test/fixtures/run-executable-failure" "executable failure" $ \project -> do
    installedGhc <- lookupEnv "THC_INSTALLED_CORE_GHC"
    installedPkg <- lookupEnv "THC_INSTALLED_CORE_GHC_PKG"
    ghcSource <- lookupEnv "THC_INSTALLED_CORE_GHC_SOURCE"
    let output = takeDirectory project </> "output"
        path = project </> "failure.txt"
        invoke = run environment project (Just "bytecode") 900 $
          ["run", project, "--exe", "failure", "--installed-core", "required",
           "--thc-root", thcRoot environment,
           "--runtime", runtime environment, "--dist-dir", output] ++
          maybe [] (\compilerPath -> ["--with-ghc", compilerPath]) installedGhc ++
          maybe [] (\packagePath -> ["--with-ghc-pkg", packagePath]) installedPkg ++
          maybe [] (\sourcePath -> ["--ghc-source", sourcePath]) ghcSource
        planEntry plan = case filter (\component ->
          string (field component "pkg-name") == "run-executable-failure" &&
          string (field component "component-name") == "exe:failure")
          (objects plan "install-plan") of
            [component] -> component
            _ -> error "expected one failure executable in the Cabal plan"
    present <- doesFileExist path
    assertBool "fixture starts without an output file" (not present)
    result <- invoke
    assertFailure result
    assertEqual "stdout flushed on uncaught exception" "stdout before failure" (out result)
    assertEqual "bracket closed and flushed file" (BS.pack "file before failure") =<< BS.readFile path
    audit <- readJson (output </> "audit.json")
    assertBool "strict original Core accepted" (bool $ field audit "accepted")
    assertEqual "no unresolved definitions" [] (array $ field audit "missingGlobals")
    assertEqual "no audit issues" [] (array $ field audit "issues")
    assertEqual "original main and shutdown roots"
      ["main::Main.main", "ghc-internal:GHC.Internal.TopHandler.flushStdHandles"]
      (strings $ field audit "roots")
    plan <- readJson (output </> "native/cache/plan.json")
    removeFile path
    native <- runExe environment project Nothing 60
      (string $ field (planEntry plan) "bin-file") []
    assertFailure native
    assertEqual "native exit status" (ExitFailure 1) (code native)
    assertEqual "driver exit status" (code native) (code result)
    assertEqual "native and THC stdout" (out native) (out result)
    assertEqual "native and THC flushed file" (BS.pack "file before failure") =<< BS.readFile path
    let requireReport = either (\message -> HUnit.assertFailure message >> pure "") pure
    nativeReport <- requireReport (originalReport project Nothing (err native))
    guestReport <- requireReport (originalReport project (Just (runtime environment)) (err result))
    assertContains "user error (THC expected uncaught failure)" (err native)
    assertEqual "original TopHandler report matches native GHC" nativeReport guestReport
    removeFile path

-- Compilation and JVM startup diagnostics precede the unique, line-anchored
-- TopHandler header. From that header onward, retain every byte except the
-- exact native process prefix, fixture source prefix, and driver suffix.
originalReport :: FilePath -> Maybe FilePath -> String -> Either String String
originalReport project launcher output = do
  report <- case take 2 [body | (previous, rest) <- zip ('\n' : output) (tails output),
                        previous == '\n',
                        let body = maybe rest id (stripPrefix "failure: " rest),
                        reportHeader `isPrefixOf` body] of
    [body] -> Right body
    [] -> Left "missing original TopHandler exception report"
    _ -> Left "multiple original TopHandler exception reports"
  body <- case launcher of
    Nothing -> Right report
    Just command -> case find (`isSuffixOf` report) (driverSuffixes command) of
      Just suffix -> Right (take (length report - length suffix) report)
      Nothing -> Left "missing exact driver nonzero-command suffix"
  pure (relativeLocation body)
  where
    absolute = "\n  ioError, called at " ++ (project </> "app/Main.hs") ++ ":"
    relative = "\n  ioError, called at app/Main.hs:"
    relativeLocation input
      | Just rest <- stripPrefix absolute input = relative ++ rest
    relativeLocation [] = []
    relativeLocation (char : rest) = char : relativeLocation rest

reportHeader :: String
reportHeader = "Uncaught exception ghc-internal:GHC.Internal.IO.Exception.IOException:\n"

-- Cabal can wrap either side of the runtime command onto a new line. Match
-- only those layouts of this exact command and exit status, at the very end.
driverSuffixes :: FilePath -> [String]
driverSuffixes command =
  ["Error: thc: command failed:" ++ before ++ command ++ after ++ "(ExitFailure 1)\n" |
    before <- [" ", "\n"], after <- [" ", "\n"]]

reportTests :: Test
reportTests = TestLabel "exact executable exception report comparison" $ TestList $
  [ TestCase $ assertEqual "native process prefix" (Right expected)
      (originalReport project Nothing ("failure: " ++ expected))
  ] ++
  [ TestCase $ assertEqual "guest report and wrapped driver suffix" (Right expected)
      (guest ("compiler and JVM startup diagnostics\n" ++ absoluteReport ++ wrappedSuffix)) |
    wrappedSuffix <- driverSuffixes command
  ] ++
  [ TestLabel label $ TestCase $ assertBool label (guest output /= Right expected) |
    (label, output) <-
      [ ("missing report header", "user error (THC expected uncaught failure)\n" ++ suffix)
      , ("header must start a line", "junk: " ++ absoluteReport ++ suffix)
      , ("unknown process prefix", "other: " ++ absoluteReport ++ suffix)
      , ("multiple reports", absoluteReport ++ absoluteReport ++ suffix)
      , ("missing payload", reportHeader ++ "\n" ++ location ++ "\n\n" ++ suffix)
      , ("extra runtime fault in report", reportHeader ++ "\nruntime fault\n" ++ payload ++ "\n\n" ++ location ++ "\n\n" ++ suffix)
      , ("extra runtime fault after report", absoluteReport ++ "runtime fault\n" ++ suffix)
      , ("extra trailing junk", absoluteReport ++ suffix ++ "junk\n")
      , ("wrong runtime command", absoluteReport ++ "Error: thc: command failed:\n" ++ command ++ "-other\n(ExitFailure 1)\n")
      , ("wrong exit status", absoluteReport ++ "Error: thc: command failed:\n" ++ command ++ "\n(ExitFailure 2)\n")
      , ("missing driver suffix", absoluteReport)
      , ("wrong fixture source path", reportHeader ++ "\n" ++ payload ++ "\n\nHasCallStack backtrace:\n" ++
          "  ioError, called at /other/app/Main.hs:12:3 in run-executable-failure-0.1.0.0-inplace-failure:Main\n\n" ++ suffix)
      ]
  ]
  where
    project = "/tmp/thc-driver-tests-123/executable failure"
    command = "/tmp/thc/build/install/thc/bin/thc"
    payload = "user error (THC expected uncaught failure)"
    location = "  ioError, called at " ++ (project </> "app/Main.hs") ++
      ":12:3 in run-executable-failure-0.1.0.0-inplace-failure:Main"
    expected = reportHeader ++ "\n" ++ payload ++ "\n\nHasCallStack backtrace:\n" ++
      "  ioError, called at app/Main.hs:12:3 in run-executable-failure-0.1.0.0-inplace-failure:Main\n\n"
    absoluteReport = reportHeader ++ "\n" ++ payload ++ "\n\nHasCallStack backtrace:\n" ++ location ++ "\n\n"
    suffix = "Error: thc: command failed:\n" ++ command ++ "\n(ExitFailure 1)\n"
    guest = originalReport project (Just command)
