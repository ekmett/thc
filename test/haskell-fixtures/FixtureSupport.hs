-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

module FixtureSupport
  ( run, runWithTimeout, CommandResult(..), runLogged, runLoggedExpect, runLoggedWithInput
  , writeJson, hashFile, hashes, hexBytes, splitTab, readInteger
  ) where

import Control.Monad (forM, unless)
import qualified Crypto.Hash.SHA256 as SHA256
import Data.Aeson (Value, object, (.=), encode)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import qualified Data.ByteString.Lazy as BL
import qualified Data.Map.Strict as Map
import Numeric (showHex)
import System.Directory (createDirectoryIfMissing)
import System.Environment (getEnvironment)
import System.Exit (ExitCode(..), die)
import System.FilePath ((</>))
import System.IO (IOMode(ReadMode, WriteMode), withBinaryFile)
import System.Process (CreateProcess(..), StdStream(..), proc, readCreateProcessWithExitCode,
                       waitForProcess, withCreateProcess)
import System.Timeout (timeout)

environmentWith :: [(String,String)] -> IO [(String,String)]
environmentWith overrides = do
  environment <- getEnvironment
  let replace key value rest = (key,value) : filter ((/= key) . fst) rest
  pure (foldr (uncurry replace) environment overrides)

run :: FilePath -> [(String,String)] -> FilePath -> [String] -> String -> IO String
run = runWithTimeout Nothing

runWithTimeout :: Maybe Int -> FilePath -> [(String,String)] -> FilePath -> [String] -> String -> IO String
runWithTimeout limit root overrides program args input = do
  environment <- environmentWith overrides
  let command = (proc program args) {cwd = Just root, env = Just environment}
      execute = readCreateProcessWithExitCode command input
  completed <- maybe (Just <$> execute) (\micros -> timeout micros execute) limit
  -- The process library brackets the child; timeout terminates and reaps it.
  (code,stdout,stderr) <- maybe (die (program ++ " timed out")) pure completed
  case code of
    ExitSuccess -> pure stdout
    ExitFailure n -> die (unlines [program ++ " failed (" ++ show n ++ ")", unwords args, stdout, stderr])

data CommandResult = CommandResult
  { commandStdout :: BS.ByteString
  , commandStderr :: BS.ByteString
  , commandRecord :: Value
  , commandArtifacts :: [FilePath]
  }

-- Seconds, repository root, relative log directory, label, environment overrides,
-- executable, arguments. No stdin; stdout/stderr are separate binary files, not
-- locale-decoded strings or lazy pipes. Nonzero/timeout fails after logging.
runLogged :: Int -> FilePath -> FilePath -> String -> [(String,String)] -> FilePath -> [String] -> IO CommandResult
runLogged = runLoggedExpect 0

-- Reserve stdin before the child runtime initializes. In particular, an RTS
-- may allocate an internal descriptor as fd0 when NoStream closes it; replacing
-- fd0 later from Haskell main would then clobber that unrelated descriptor.
-- The input path is relative to root and is recorded alongside the command.
runLoggedWithInput :: FilePath -> Int -> FilePath -> FilePath -> String -> [(String,String)] -> FilePath -> [String] -> IO CommandResult
runLoggedWithInput input = runLoggedExpectInput (Just input) 0

-- Negative CLI controls retain the actual exit status and require exactly the
-- expected code; they are not successful commands with swallowed failures.
runLoggedExpect :: Int -> Int -> FilePath -> FilePath -> String -> [(String,String)] -> FilePath -> [String] -> IO CommandResult
runLoggedExpect = runLoggedExpectInput Nothing

runLoggedExpectInput :: Maybe FilePath -> Int -> Int -> FilePath -> FilePath -> String -> [(String,String)] -> FilePath -> [String] -> IO CommandResult
runLoggedExpectInput input expected seconds root logs label overrides program args = do
  createDirectoryIfMissing True (root </> logs)
  environment <- environmentWith overrides
  let output = logs </> label ++ ".stdout"
      errors = logs </> label ++ ".stderr"
      recordPath = logs </> label ++ ".command.json"
      withInput action = case input of
        Nothing -> action NoStream
        Just path -> withBinaryFile (root </> path) ReadMode (action . UseHandle)
  completed <- withInput $ \stdinStream ->
    withBinaryFile (root </> output) WriteMode $ \out ->
      withBinaryFile (root </> errors) WriteMode $ \err ->
        withCreateProcess ((proc program args)
          {cwd = Just root, env = Just environment, std_in = stdinStream,
           std_out = UseHandle out, std_err = UseHandle err}) $ \_ _ _ child ->
            timeout (seconds * 1000000) (waitForProcess child)
  let exit = case completed of
        Just ExitSuccess -> Just (0 :: Int)
        Just (ExitFailure code) -> Just code
        Nothing -> Nothing
      record = object (["argv" .= (program:args), "environment" .= Map.fromList overrides,
                        "cwd" .= root, "exit" .= exit, "expectedExit" .= expected,
                        "timeoutSeconds" .= seconds] ++
                       ["stdin" .= path | Just path <- [input]] ++
                       ["timedOut" .= True | completed == Nothing])
  writeJson (root </> recordPath) record
  stdout <- BS.readFile (root </> output)
  stderr <- BS.readFile (root </> errors)
  unless (exit == Just expected) $ die $ unlines
    [ program ++ maybe (" timed out after " ++ show seconds ++ " seconds")
        (\code -> " exited " ++ show code ++ " (expected " ++ show expected ++ ")") exit
    , "argv: " ++ show (program:args)
    , "command record: " ++ root </> recordPath
    , "stdout: " ++ root </> output
    , "stderr: " ++ root </> errors
    , "stderr (first 8192 bytes, escaped): " ++ show (BSC.unpack (BS.take 8192 stderr))
    ]
  pure (CommandResult stdout stderr record [output,errors,recordPath])

writeJson :: FilePath -> Value -> IO ()
writeJson path value = BL.writeFile path (encode value <> "\n")

hexBytes :: BS.ByteString -> String
hexBytes = concatMap hexByte . BS.unpack
  where hexByte byte = let digits = showHex byte "" in replicate (2 - length digits) '0' ++ digits

hashFile :: FilePath -> IO String
hashFile path = hexBytes . SHA256.hash <$> BS.readFile path

hashes :: FilePath -> [FilePath] -> IO (Map.Map String String)
hashes root paths = Map.fromList <$> forM paths (\path -> do
  digest <- hashFile (root </> path)
  pure (path,digest))

splitTab :: String -> [String]
splitTab text = case break (== '\t') text of
  (piece,[]) -> [piece]
  (piece,_:rest) -> piece : splitTab rest

readInteger :: String -> Maybe Integer
readInteger text = case reads text of
  [(number,"")] -> Just number
  _ -> Nothing
