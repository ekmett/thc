-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module TestSupport
  ( Env(..), Result(..), setup, withFixture, withFixtureNamed, copyTree, run, runExe, checked, json, readJson
  , field, array, string, strings, bool, number, objects, named
  , assertContains, assertSuccess, assertFailure, assertNoStdout
  , writeText, readText, replaceText, findFiles, requireFile
  ) where

import Control.Exception (bracket)
import Control.Monad (forM)
import Data.Aeson (Value(..), eitherDecodeStrict')
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.Vector as Vector
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.List (isInfixOf, isPrefixOf, isSuffixOf)
import System.Directory
  ( copyFileWithMetadata, createDirectory, createDirectoryIfMissing, doesDirectoryExist
  , doesFileExist, findExecutable, getCurrentDirectory, getTemporaryDirectory
  , listDirectory, removeFile, removePathForcibly
  )
import System.Environment (getEnvironment, lookupEnv)
import System.Exit (ExitCode(..))
import System.FilePath ((</>))
import System.IO (hClose, openTempFile)
import qualified System.Process as Process
import System.Timeout (timeout)
import Test.HUnit (assertBool, assertEqual)
import qualified Test.HUnit as HUnit

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
    copyTree (root env </> fixture) package
    action package
  where
    fresh parent = do
      (file, handle) <- openTempFile parent "thc-driver-tests-"
      hClose handle
      removeFile file
      createDirectory file
      pure file

copyTree :: FilePath -> FilePath -> IO ()
copyTree source target = do
  createDirectoryIfMissing True target
  children <- listDirectory source
  mapM_ (\name -> do
    let src = source </> name
        dst = target </> name
    directory <- doesDirectoryExist src
    if directory then copyTree src dst else copyFileWithMetadata src dst) children

run :: Env -> FilePath -> Maybe String -> Int -> [String] -> IO Result
run env cwd backend seconds = runExe env cwd backend seconds (driver env)

runExe :: Env -> FilePath -> Maybe String -> Int -> FilePath -> [String] -> IO Result
runExe env cwd backend seconds executable arguments = do
  original <- getEnvironment
  let extra = case backend of
        Nothing -> original
        Just name -> ("THC_BACKEND", name) : filter ((/= "THC_BACKEND") . fst) original
      process = (Process.proc executable arguments) { Process.cwd = Just cwd, Process.env = Just extra }
  completed <- timeout (seconds * 1000000) (Process.readCreateProcessWithExitCode process "")
  case completed of
    Nothing -> do
      appendFile (scratch env </> "commands.log")
        (unlines ["$ " ++ unwords (executable : arguments), "cwd: " ++ cwd,
                  "timed out after " ++ show seconds ++ " seconds"])
      HUnit.assertFailure ("timed out: " ++ unwords arguments) >> fail "unreachable"
    Just (status, stdout, stderr) -> do
      appendFile (scratch env </> "commands.log")
        (unlines ["$ " ++ unwords (executable : arguments), "cwd: " ++ cwd,
                  "exit: " ++ show status, "stdout: " ++ stdout, "stderr: " ++ stderr])
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
