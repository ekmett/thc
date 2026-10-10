-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : TestSupport
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Temporary-project, subprocess and bundle-inspection helpers for driver tests.
module TestSupport
  ( Env(..), Result(..), setup, withFixture, withFixtureNamed, copyTree, run, runExe, runPreparation, checked, json, readJson, readCore
  , readSourceManifest, readPublishedCore, readCoreBytes, readPublishedCoreBytes
  , field, array, string, strings, bool, number, objects, named
  , assertContains, assertSuccess, assertFailure, assertNoStdout
  , writeText, readText, replaceText, findFiles, requireFile
  ) where

import Codec.Archive.Zip (findEntryByPath, fromEntry, toArchiveOrFail)
import Control.Exception (bracket, evaluate, finally, mask, onException)
import Control.Monad (forM, forM_, void)
import Data.Aeson (Value(..), eitherDecodeStrict', toJSON)
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import qualified Data.Vector as Vector
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.List (isInfixOf, isPrefixOf, isSuffixOf, sort)
import Data.Word (Word64)
import GHC.Clock (getMonotonicTimeNSec)
import System.Directory
  ( copyFileWithMetadata, createDirectory, createDirectoryIfMissing, doesDirectoryExist
  , doesFileExist, findExecutable, getCurrentDirectory, getTemporaryDirectory
  , listDirectory, pathIsSymbolicLink, removeFile, removePathForcibly
  )
import System.Environment (getEnvironment, lookupEnv)
import System.Exit (ExitCode(..))
import System.FilePath ((</>), takeDirectory, takeExtension)
import System.IO (hClose, hPutStrLn, openTempFile)
import qualified System.IO as IO
import System.IO.Error (tryIOError)
import qualified System.Process as Process
import System.Process.Internals (ignoreSigPipe, withForkWait)
import System.Timeout (timeout)
import Test.HUnit (assertBool, assertEqual)
import qualified Test.HUnit as HUnit
import THC.Compact.Module (readModuleValue)
import THC.Driver.Process (waitOwnedProcess, stopProcessTree)

data Env = Env { root :: FilePath, thcRoot :: FilePath, driver :: FilePath
               , runtime :: FilePath, scratch :: FilePath }
data Result = Result { code :: ExitCode, out :: String, err :: String } deriving Show

setup :: IO Env
setup = do
  current <- getCurrentDirectory
  repo <- maybe current id <$> lookupEnv "THC_TEST_ROOT"
  driverOverride <- lookupEnv "THC_TEST_DRIVER"
  exe <- case driverOverride of
    Just path -> pure (Just path)
    Nothing -> findExecutable "thc"
  path <- maybe (fail "thc driver missing: set THC_TEST_DRIVER or use cabal test") pure exe
  runtimeOverride <- lookupEnv "THC_TEST_RUNTIME"
  thcRootOverride <- lookupEnv "THC_TEST_THC_ROOT"
  scratchOverride <- lookupEnv "THC_TEST_SCRATCH"
  let evidence = maybe (repo </> "build/driver-haskell-tests") id scratchOverride
  createDirectoryIfMissing True evidence
  pure Env { root = repo, thcRoot = maybe repo id thcRootOverride, driver = path
           , runtime = maybe (repo </> "build/install/thc/bin/thc") id runtimeOverride
           , scratch = evidence }

-- Directory fixtures must live outside the source tree: the source tree has a
-- cabal.project, and plan-package intentionally discovers ancestor projects.
withFixture :: Env -> FilePath -> (FilePath -> IO a) -> IO a
withFixture env fixture = withFixtureNamed env fixture "project \"café\""

withFixtureNamed :: Env -> FilePath -> FilePath -> (FilePath -> IO a) -> IO a
withFixtureNamed env fixture name action = do
  temporary <- getTemporaryDirectory
  bracket (fresh temporary) removePathForcibly $ \base -> do
    let package = base </> name
    (copyTree (root env </> fixture) package >> action package)
      `onException` reportFixtureAudits env base
  where
    fresh parent = do
      (file, handle) <- openTempFile parent "thc-driver-tests-"
      hClose handle
      removeFile file
      createDirectory file
      pure file

-- Failed assertions must not discard the only useful strict-audit diagnosis
-- when the surrounding bracket deletes its temporary project and bundles.
-- Never follow links into package stores or print the large Core/dependency maps.
reportFixtureAudits :: Env -> FilePath -> IO ()
reportFixtureAudits env base = do
  found <- tryIOError (findAudits base)
  case found of
    Left problem -> emit ("Fixture audit discovery failed: " ++ clipped (show problem))
    Right paths -> do
      forM_ (take 20 paths) $ \path -> do
        contents <- tryIOError (BS.readFile path)
        let summary = case contents of
              Left problem -> "unreadable audit: " ++ clipped (show problem)
              Right bytes -> case eitherDecodeStrict' bytes of
                Left problem -> "invalid audit JSON: " ++ clipped problem
                Right value -> auditSummary value
        emit ("Failed fixture audit " ++ show path ++ "\n" ++ summary)
      if length paths > 20 then emit (show (length paths - 20) ++ " further audit files omitted") else pure ()
  where
    emit message = do
      -- Either destination can be unavailable; neither may replace the original
      -- test exception. Native/guest stdout remains solely the captured oracle.
      void (tryIOError (hPutStrLn IO.stderr message))
      void (tryIOError (appendFile (scratch env </> "commands.log") (message ++ "\n")))
    findAudits directory = do
      names <- sort <$> listDirectory directory
      fmap concat $ forM names $ \name -> do
        let path = directory </> name
        linked <- pathIsSymbolicLink path
        if linked then pure [] else do
          nested <- doesDirectoryExist path
          if nested then findAudits path else pure [path | "audit.json" `isSuffixOf` name]

auditSummary :: Value -> String
auditSummary value = unlines $
  ["accepted=" ++ accepted ++ "; missingGlobals=" ++ show (length missing) ++
   "; issues=" ++ show (length issues)] ++
  bounded "missing" (map (\item -> "missing: " ++ clipped (string (field item "id"))) missing) ++
  bounded "issue" (map issue issues)
  where
    missing = array (field value "missingGlobals")
    issues = array (field value "issues")
    accepted = case field value "accepted" of Bool True -> "true"; Bool False -> "false"; _ -> "unknown"
    issue item = "issue: " ++ unwords [key ++ "=" ++ clipped (string (field item key)) |
      key <- ["code", "owner", "path", "detail"]]
    bounded label rows = take 100 rows ++ [show (length rows - 100) ++ " further " ++ label ++ " entries omitted" | length rows > 100]

clipped :: String -> String
clipped value = show (take 512 value) ++ if length (take 513 value) > 512 then "..." else ""

copyTree :: FilePath -> FilePath -> IO ()
copyTree source target = do
  createDirectoryIfMissing True target
  children <- filter (/= ".git") <$> listDirectory source
  mapM_ (\name -> do
    let src = source </> name
        dst = target </> name
    directory <- doesDirectoryExist src
    if directory then copyTree src dst else copyFileWithMetadata src dst) children

run :: Env -> FilePath -> Maybe String -> Int -> [String] -> IO Result
run env cwd backend seconds = runExe env cwd backend seconds (driver env)

-- Compiler preparation has no per-command execution budget. The outer CI job
-- bounds it; callers still check the returned result before executing the guest.
runPreparation :: Env -> FilePath -> [String] -> IO Result
runPreparation env cwd arguments = do
  process <- testProcess env cwd Nothing (driver env) arguments
  started <- getMonotonicTimeNSec
  result <- captureProcess process
  recordResult started env cwd (driver env) arguments result

runExe :: Env -> FilePath -> Maybe String -> Int -> FilePath -> [String] -> IO Result
runExe env cwd backend seconds executable arguments = do
  process <- testProcess env cwd backend executable arguments
  started <- getMonotonicTimeNSec
  completed <- timeout (seconds * 1000000) (captureProcess process)
  case completed of
    Nothing -> do
      appendFile (scratch env </> "commands.log")
        (unlines ["$ " ++ unwords (executable : arguments), "cwd: " ++ cwd,
                  "timed out after " ++ show seconds ++ " seconds"])
      HUnit.assertFailure ("timed out: " ++ unwords arguments) >> fail "unreachable"
    Just result -> recordResult started env cwd executable arguments result

-- process's exception cleanup signals only the direct child on POSIX and
-- reaps asynchronously. Stop our private group/job before closing pipes or
-- letting the fixture bracket remove files that descendants may still write.
captureProcess :: Process.CreateProcess -> IO (ExitCode, String, String)
captureProcess command = bracket (Process.createProcess command
    { Process.std_in = Process.CreatePipe, Process.std_out = Process.CreatePipe
    , Process.std_err = Process.CreatePipe, Process.create_group = True
    , Process.use_process_jobs = True }) cleanup $ \handles -> mask $ \restore ->
  case handles of
    (Just input, Just output, Just diagnostic, child) -> do
      stdout <- IO.hGetContents output
      stderr <- IO.hGetContents diagnostic
      -- Use process's own reader-thread lifetime helper. Wait for both EOFs
      -- before reaping so a departed parent's PID still owns its POSIX group.
      withForkWait (void (evaluate (length stdout))) $ \waitOutput ->
        withForkWait (void (evaluate (length stderr))) $ \waitDiagnostic ->
          restore (do
            ignoreSigPipe (hClose input)
            waitOutput
            waitDiagnostic
            status <- waitOwnedProcess child
            pure (status, stdout, stderr)) `onException` stopProcessTree child
    _ -> fail "Driver test subprocess pipes were unavailable"
  where
    cleanup (input, output, diagnostic, child) = stopProcessTree child `finally`
      (ignoreSigPipe (mapM_ hClose input) `finally` (mapM_ hClose output `finally` mapM_ hClose diagnostic))

testProcess :: Env -> FilePath -> Maybe String -> FilePath -> [String] -> IO Process.CreateProcess
testProcess env cwd backend executable arguments = do
  original <- getEnvironment
  -- Backend-comparison controls inspect launcher metrics explicitly. Restrict
  -- the opt-in to the driver/runtime child; native oracle commands are unchanged.
  -- JAVA_OPTS is consumed by the installed Gradle launcher without the JVM's
  -- JAVA_TOOL_OPTIONS announcement polluting the captured stderr.
  let withBackend = case backend of
        Nothing -> original
        Just name -> ("THC_BACKEND", name) : filter ((/= "THC_BACKEND") . fst) original
      extra = if backend /= Nothing && executable `elem` [driver env, runtime env]
        then ("JAVA_OPTS", maybe "" id (lookup "JAVA_OPTS" withBackend) ++ " -Dthc.diagnostics=true") :
             filter ((/= "JAVA_OPTS") . fst) withBackend
        else withBackend
  pure $ (Process.proc executable arguments) { Process.cwd = Just cwd, Process.env = Just extra }

recordResult :: Word64 -> Env -> FilePath -> FilePath -> [String] -> (ExitCode, String, String) -> IO Result
recordResult started env cwd executable arguments (status, stdout, stderr) = do
  finished <- getMonotonicTimeNSec
  let elapsed = fromIntegral (finished - started) / 1000000000 :: Double
  appendFile (scratch env </> "commands.log")
    (unlines ["$ " ++ unwords (executable : arguments), "cwd: " ++ cwd,
              "seconds: " ++ show elapsed, "exit: " ++ show status,
              "stdout: " ++ stdout, "stderr: " ++ stderr])
  pure (Result status stdout stderr)

checked :: Env -> FilePath -> Maybe String -> Int -> [String] -> IO Result
checked env cwd backend seconds arguments = do
  result <- run env cwd backend seconds arguments
  assertSuccess result
  pure result

json :: String -> IO Value
json input = case eitherDecodeStrict' (Text.encodeUtf8 (Text.pack input)) of
  Right value -> pure value
  Left problem -> HUnit.assertFailure ("invalid JSON: " ++ problem ++ "\n" ++ input) >> fail "unreachable"

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= \bytes -> case eitherDecodeStrict' bytes of
  Right value -> pure value
  Left problem -> HUnit.assertFailure ("invalid JSON in " ++ path ++ ": " ++ problem) >> fail "unreachable"

readCore :: FilePath -> FilePath -> IO Value
readCore bundle member = do
  payload <- readCoreBytes bundle member
  either fail pure (if takeExtension member == ".cbd" then readModuleValue payload else eitherDecodeStrict' payload)

readCoreBytes :: FilePath -> FilePath -> IO BS.ByteString
readCoreBytes bundle member = do
  -- The caller may replace the archive after inspecting one member. Do not
  -- retain a lazy read handle for uninspected ZIP members until a later GC.
  bytes <- BL.fromStrict <$> BS.readFile bundle
  archive <- either fail pure (toArchiveOrFail bytes)
  entry <- maybe (fail ("missing ZIP member " ++ member)) pure
           (findEntryByPath member archive)
  pure (BL.toStrict (fromEntry entry))

-- | Inspect acquisition provenance, not a runtime manifest. Cache tests need
-- the original ZIP build receipts behind per-module CBD publication. This
-- projection is only returned to assertions; never written back or
-- supplied to the guest/auditor. Direct publication is tested separately.
readSourceManifest :: FilePath -> IO Value
readSourceManifest path = do
  manifest <- readJson path
  units <- forM (objects manifest "units") $ \unit -> case objects unit "modules" of
    [] -> pure unit
    ref : _ -> do
      let compact = field ref "compact"
      receipt <- readJson (takeDirectory (string (field compact "path")) </> "publication.json")
      let source = field receipt "source"
      inner <- readCore (string (field source "path")) "manifest.json"
      pure (update "bundle" source $ update "modules" (field inner "modules") unit)
  pure (update "units" (toJSON units) manifest)
  where
    update key value (Object fields) = Object (KeyMap.insert (Key.fromString key) value fields)
    update _ _ value = value

-- | Read one published CBD, or its original acquisition member when a test
-- explicitly selected the source provenance projection above.
readPublishedCore :: Value -> Value -> IO Value
readPublishedCore unit ref = readPublishedCoreBytes unit ref >>= either fail pure . readModuleValue

readPublishedCoreBytes :: Value -> Value -> IO BS.ByteString
readPublishedCoreBytes unit ref = case field ref "compact" of
  Null -> readCoreBytes (string (field (field unit "bundle") "path")) (string (field ref "path"))
  compact -> BS.readFile (string (field compact "path"))

field :: Value -> String -> Value
field (Object object) name = maybe Null id (KeyMap.lookup (Key.fromString name) object)
field _ _ = Null

array :: Value -> [Value]
array (Array vector) = Vector.toList vector
array _ = []

objects :: Value -> String -> [Value]
objects value name = array (field value name)

string :: Value -> String
string (String value) = showText value
string _ = ""

showText :: Text.Text -> String
showText = Text.unpack

strings :: Value -> [String]
strings = map string . array

bool :: Value -> Bool
bool (Bool value) = value
bool _ = False

number :: Value -> Int
number (Number value) = floor value
number _ = 0

named :: String -> [Value] -> Value
named name values = case filter ((== name) . string . (`field` "name")) values of
  [value] -> value
  _ -> error ("expected one named value: " ++ name)

assertContains :: String -> String -> IO ()
assertContains needle haystack = assertBool ("missing " ++ show needle ++ " in " ++ haystack) (needle `isInfixOf` haystack)

assertSuccess :: Result -> IO ()
assertSuccess result = assertEqual (show result) ExitSuccess (code result)

assertFailure :: Result -> IO ()
assertFailure result = assertBool (show result) (code result /= ExitSuccess)

assertNoStdout :: Result -> IO ()
assertNoStdout result = assertEqual (show result) "" (out result)

writeText :: FilePath -> String -> IO ()
writeText = writeFile

readText :: FilePath -> IO String
readText path = Text.unpack . Text.decodeUtf8 <$> BS.readFile path

replaceText :: String -> String -> String -> String
replaceText needle replacement input
  | null needle = input
  | otherwise = go input
  where
    go [] = []
    go rest@(x:xs)
      | needle `isPrefixOf` rest = replacement ++ go (drop (length needle) rest)
      | otherwise = x : go xs

findFiles :: FilePath -> String -> IO [FilePath]
findFiles directory suffix = do
  exists <- doesDirectoryExist directory
  if not exists then pure [] else do
    names <- listDirectory directory
    fmap concat $ forM names $ \name -> do
      let path = directory </> name
      nested <- doesDirectoryExist path
      if nested then findFiles path suffix
      else pure [path | suffix `isSuffixOf` name]

requireFile :: FilePath -> IO ()
requireFile path = doesFileExist path >>= assertBool ("missing file: " ++ path)
