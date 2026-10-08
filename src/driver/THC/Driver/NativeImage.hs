-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP #-}
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Driver.NativeImage
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : OverloadedStrings; Linux x86-64, pinned GraalVM and host services
--
-- Fresh prepared executable production. The existing recipe owns capture,
-- native linkage and image construction; this module owns publication.
module THC.Driver.NativeImage
  ( validateNativeImage
  , buildNativeImage
  ) where

#ifndef mingw32_HOST_OS
import Control.Concurrent (threadDelay)
#endif
import Control.Exception (evaluate, finally, mask, mask_, onException)
import Control.Monad (forM, forM_, unless, void, when)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (Value(..), Result(..), eitherDecodeStrict', encode, fromJSON, object, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.List (isInfixOf, nub, sort)
import qualified Data.Map.Strict as Map
import Numeric (showHex)
import System.Directory
  ( canonicalizePath, copyFileWithMetadata, createDirectory, createDirectoryIfMissing
  , doesDirectoryExist, doesFileExist, executable, findExecutable, getFileSize
  , getPermissions, listDirectory, removeDirectoryRecursive, removeFile, removePathForcibly, renameDirectory
  , renameFile )
import System.Environment (getEnv, getEnvironment, lookupEnv)
import System.Exit (ExitCode(..))
import System.FilePath ((</>), isRelative, makeRelative, normalise, splitDirectories, takeDirectory)
import System.Info (arch, os)
import System.IO (IOMode(..), hClose, hPutStrLn, openTempFile, stderr, withBinaryFile, withFile)
import System.IO.Error (tryIOError)
#ifndef mingw32_HOST_OS
import System.IO.Error (catchIOError, isDoesNotExistError)
import System.Posix.Signals (sigKILL, signalProcessGroup)
#endif
import System.Process
  ( CreateProcess(..), StdStream(..), proc, readCreateProcessWithExitCode
  , waitForProcess, withCreateProcess )
import qualified System.Process as Process
import THC.Driver.Lock (withLock)
import THC.Driver.Run (runtimeLaunchArguments)

-- | Check the admitted platform and currently selected producer prerequisites
-- before Cabal acquisition. Does not create build products or run guest code.
-- Native compiler headers/libraries remain the real builder's responsibility.
validateNativeImage :: FilePath -> IO ()
validateNativeImage root = do
  require (os == "linux" && arch == "x86_64") "native image production requires Linux x86-64"
  requireFile "native image recipe" (root </> "research/native-image-preparation/prepared-image.sh")
  requireFile "installed THC runtime" (root </> "build/install/thc/lib/thc-0.1-experiment.jar")
  jdk <- lookupEnv "JAVA_HOME" >>= maybe (fail "native image production requires JAVA_HOME for GraalVM 25.3.4.1") pure
  forM_ ["java", "javac", "jar", "native-image"] $ \tool -> requireExecutable (jdk </> "bin" </> tool)
  inherited <- getEnvironment
  let clean = filter (\(key, _) -> notElem key ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"]) inherited
  (status, version, diagnostic) <- readCreateProcessWithExitCode
    (proc (jdk </> "bin/native-image") ["--version"]) {env = Just clean} ""
  require (status == ExitSuccess && isInfixOf "25.3.4.1" version)
    ("native image production requires GraalVM 25.3.4.1: " ++ diagnostic)
  forM_ ["bash", "cc", "ld"] $ \tool -> do
    found <- findExecutable tool
    maybe (fail ("native image tool missing: " ++ tool)) requireExecutable found
  readobj <- maybe "llvm-readobj" id <$> lookupEnv "THC_LLVM_READOBJ"
  found <- findExecutable readobj
  maybe (fail ("native image inspection tool missing: " ++ readobj)) requireExecutable found
  _ <- nativeProfile
  pure ()

-- | Build afresh from an already published component manifest. The caller owns
-- this component's output directory and manifest closure. Unrelated contents
-- survive; @artifacts@, @diagnostics@, @completion.json@ and @.native-image-*@
-- are producer-owned.
--
-- @completion.json@ exists only after successful construction and validation.
-- Failure removes the previous marker, retains previous artifacts, and reports
-- @.native-image-failed@ containing command/output evidence until the next
-- owned attempt. The result is
-- a dynamic ELF plus emitted sidecars, not static linking or cache evidence.
buildNativeImage :: FilePath -> FilePath -> String -> FilePath -> IO ()
buildNativeImage suppliedRoot suppliedOutput program suppliedManifest = do
  createDirectoryIfMissing True suppliedOutput
  output <- canonicalizePath suppliedOutput
  withLock (output </> ".native-image.lock") $ do
    let completion = output </> "completion.json"
        failed = output </> ".native-image-failed"
    exists <- doesFileExist completion
    when exists (removeFile completion)
    removePathForcibly failed
    root <- canonicalizePath suppliedRoot
    validateNativeImage root
    require (not (null program) && notElem '\0' program) "native image program name must be nonempty and contain no NUL"
    requireFile "native image component manifest" suppliedManifest
    manifest <- canonicalizePath suppliedManifest
    manifestHash <- hashFile manifest
    jdk <- canonicalizePath =<< getEnv "JAVA_HOME"
    readobj <- maybe "llvm-readobj" id <$> lookupEnv "THC_LLVM_READOBJ"
    inspector <- findExecutable readobj >>= maybe (fail ("native image inspection tool missing: " ++ readobj)) canonicalizePath
    profile <- nativeProfile
    stage <- freshDirectory output ".native-image-build-"
    let binding = object
          ["arguments" .= runtimeLaunchArguments True
            ["--run-executable", '@' : manifest, "main::Main.main",
             "ghc-internal:GHC.Internal.TopHandler.flushStdHandles"] program [],
           "properties" .= object ["thc.backend" .= ("ast" :: String),
             "thc.asyncExceptions" .= ("false" :: String), "thc.byteArrayStorage" .= ("native" :: String)]]
        bindingPath = stage </> "application.json"
        recipe = root </> "research/native-image-preparation/prepared-image.sh"
        arguments = [recipe, root, "executable", stage]
        report = do
          retained <- tryIOError (renameDirectory stage failed)
          case retained of
            Right () -> hPutStrLn stderr ("Native image build evidence retained at " ++ failed)
            Left problem -> hPutStrLn stderr ("Native image build evidence retained at " ++ stage ++
              "; failed to move to " ++ failed ++ ": " ++ show problem)
    onException (do
      BS.writeFile bindingPath (BL.toStrict (encode binding))
      BS.writeFile (stage </> "command.json") (BL.toStrict (encode ("bash" : arguments)))
      inherited <- getEnvironment
      let overrides = [("THC_NATIVE_IMAGE_EXECUTABLE_CONFIG", bindingPath),
            ("THC_NATIVE_IMAGE_EXECUTABLE_NAME", "program"), ("THC_NATIVE_IMAGE_VECTOR_PROFILE", profile),
            ("JAVA_HOME", jdk), ("THC_LLVM_READOBJ", inspector)]
          environment = overrides ++ filter (\(key, _) -> notElem key (map fst overrides)) inherited
      hPutStrLn stderr ("Building native image for " ++ program ++ " in " ++ stage)
      status <- withFile (stage </> "build.stdout") WriteMode $ \stdoutLog ->
        withFile (stage </> "build.stderr") WriteMode $ \stderrLog ->
          mask_ $ withCreateProcess (proc "bash" arguments) {cwd = Just root, env = Just environment,
            std_out = UseHandle stdoutLog, std_err = UseHandle stderrLog,
            create_group = True, use_process_jobs = True} $ \_ _ _ process ->
              waitProducer process
      require (status == ExitSuccess) ("native image producer failed: " ++ show status)
      groups <- artifactReport (stage </> "build-artifacts.json")
      require (elem "program" [normalise path | ("executables", paths) <- groups, path <- paths])
        "native image artifact report does not declare program as an executable"
      entries <- fmap concat $ forM groups $ \(kind, paths) ->
        if elem kind ["build_info", "debug_info", "c_headers", "import_libraries"] then pure [] else
          fmap concat $ forM paths $ \path -> do
            tree <- artifactTree stage path
            pure [(relative, (source, directory, [kind])) | (relative, source, directory) <- tree]
      let merge (source, directory, kinds) (_, _, previous) = (source, directory, sort (nub (kinds ++ previous)))
      deploy <- freshDirectory stage ".deploy-"
      inventory <- forM (Map.toAscList (Map.fromListWith merge entries)) $ \(relative, (source, directory, kinds)) -> do
        let destination = deploy </> relative
            identity = ["path" .= ("artifacts" </> relative), "categories" .= kinds]
        if directory then do
          require (not (any (\kind -> elem kind ["executables", "shared_libraries", "jdk_libraries"]) kinds))
            ("native image ELF artifact is a directory: " ++ relative)
          createDirectoryIfMissing True destination
          pure (object (identity ++ ["type" .= ("directory" :: String)]))
        else do
          createDirectoryIfMissing True (takeDirectory destination)
          copyFileWithMetadata source destination
          when (any (\kind -> elem kind ["executables", "shared_libraries", "jdk_libraries"]) kinds) $
            validateElf (elem "executables" kinds) destination
          digest <- hashFile destination
          size <- getFileSize destination
          pure (object (identity ++ ["type" .= ("file" :: String), "sha256" .= digest, "size" .= size]))
      native <- BS.readFile (stage </> "reproduction-inventory/native-libraries.json")
        >>= either (fail . ("invalid native image input receipt: " ++)) pure . eitherDecodeStrict'
      currentManifest <- hashFile manifest
      require (currentManifest == manifestHash) "native image component manifest changed during construction"
      let diagnostics = output </> "diagnostics"
      createDirectoryIfMissing True diagnostics
      forM_ ["command.json", "build.stdout", "build.stderr", "build-artifacts.json"] $ \name ->
        copyFileWithMetadata (stage </> name) (diagnostics </> name)
      let artifacts = output </> "artifacts"
          previous = stage </> "previous-artifacts"
      old <- doesDirectoryExist artifacts
      when old (renameDirectory artifacts previous)
      onException (renameDirectory deploy artifacts) (when old (renameDirectory previous artifacts))
      let result = object ["format" .= ("thc-native-image" :: String), "schema" .= (1 :: Int),
            "program" .= program, "profile" .= profile, "binding" .= binding,
            "manifest" .= object ["path" .= manifest, "sha256" .= manifestHash],
            "nativeLibraries" .= (native :: Value), "artifacts" .= inventory]
      atomicJson completion result
      ) report
    cleaned <- tryIOError (removeDirectoryRecursive stage)
    case cleaned of
      Left problem -> hPutStrLn stderr ("Native image staging cleanup failed at " ++ stage ++ ": " ++ show problem)
      Right () -> pure ()

nativeProfile :: IO String
nativeProfile = do
  selected <- maybe "resource-copy" id <$> lookupEnv "THC_NATIVE_IMAGE_VECTOR_PROFILE"
  require (elem selected ["resource-copy", "intrinsics"]) "native image profile must be resource-copy or intrinsics"
  pure selected

-- The pinned producer report owns output membership, including non-library
-- resource trees. Unknown categories are retained rather than silently lost.
artifactReport :: FilePath -> IO [(String, [FilePath])]
artifactReport path = do
  requireFile "native image artifact report" path
  report <- BS.readFile path >>= either (fail . ("invalid native image artifact report: " ++)) pure . eitherDecodeStrict'
  case report of
    Object fields -> forM (KM.toList fields) $ \(key, raw) -> case fromJSON raw :: Result [FilePath] of
      Success paths -> pure (Key.toString key, paths)
      Error problem -> fail ("invalid native image artifact category " ++ Key.toString key ++ ": " ++ problem)
    _ -> fail "native image artifact report must be an object"

artifactTree :: FilePath -> FilePath -> IO [(FilePath, FilePath, Bool)]
artifactTree stage requested = do
  let relative = normalise requested
  require (isRelative relative && notElem ".." (splitDirectories relative))
    ("native image artifact leaves its build directory: " ++ requested)
  collect [] relative
  where
    collect ancestors relative = do
      let path = stage </> relative
      source <- canonicalizePath path
      let owned = makeRelative stage source
      require (isRelative owned && notElem ".." (splitDirectories owned))
        ("native image artifact resolves outside its build directory: " ++ path)
      directory <- doesDirectoryExist source
      if directory then do
        require (notElem source ancestors) ("native image artifact has a directory cycle: " ++ path)
        children <- sort <$> listDirectory source
        nested <- concat <$> mapM (collect (source : ancestors) . (relative </>)) children
        pure ((relative, source, True) : nested)
      else do
        requireFile "native image artifact" source
        pure [(relative, source, False)]

-- Reaping and publishing the closed handle stay atomic against cancellation,
-- so the still-owned PID remains the process group ID until cleanup finishes.
waitProducer :: Process.ProcessHandle -> IO ExitCode
waitProducer process = mask $ \restore -> onException (restore wait) stop
  where
#ifdef mingw32_HOST_OS
    wait = waitForProcess process
    stop = Process.terminateProcess process >> void (waitForProcess process)
#else
    wait = mask $ \restore ->
      let poll = Process.getProcessExitCode process >>= maybe (restore (threadDelay 10000) >> poll) pure
      in poll
    stop = do
      Process.getPid process >>= mapM_ (\pid -> catchIOError (signalProcessGroup sigKILL pid)
        (\problem -> if isDoesNotExistError problem then pure () else ioError problem))
      void (waitForProcess process)
#endif

validateElf :: Bool -> FilePath -> IO ()
validateElf program path = do
  header <- withBinaryFile path ReadMode $ \handle -> BS.hGet handle 64
  let valid = BS.length header == 64 && BS.take 7 header == BS.pack [127,69,76,70,2,1,1] &&
        BS.index header 18 == 62 && BS.index header 19 == 0 &&
        BS.index header 17 == 0 && elem (BS.index header 16) (if program then [2,3] else [3])
  require valid ("native image artifact must be Linux x86-64 ELF: " ++ path)
  when program $ do
    permissions <- getPermissions path
    require (executable permissions) ("native image artifact is not executable: " ++ path)

requireFile :: String -> FilePath -> IO ()
requireFile label path = do
  found <- doesFileExist path
  require found (label ++ " missing: " ++ path)

requireExecutable :: FilePath -> IO ()
requireExecutable path = do
  requireFile "native image tool" path
  permissions <- getPermissions path
  require (executable permissions) ("native image tool is not executable: " ++ path)

require :: Bool -> String -> IO ()
require condition message = unless condition (fail message)

freshDirectory :: FilePath -> String -> IO FilePath
freshDirectory parent prefix = do
  (path, handle) <- openTempFile parent prefix
  hClose handle
  removeFile path
  createDirectory path
  pure path

hashFile :: FilePath -> IO String
hashFile path = withBinaryFile path ReadMode $ \handle -> do
  bytes <- BL.hGetContents handle
  digest <- evaluate (SHA.hashlazy bytes)
  pure (concatMap hex (BS.unpack digest))
  where
    hex byte = let value = showHex byte "" in replicate (2 - length value) '0' ++ value

atomicJson :: FilePath -> Value -> IO ()
atomicJson path value = do
  (temporary, handle) <- openTempFile (takeDirectory path) ".native-image-completion-"
  let cleanup = do exists <- doesFileExist temporary
                   when exists (removeFile temporary)
  finally (BS.hPut handle (BL.toStrict (encode value) <> BS.pack [10]) >> hClose handle >> renameFile temporary path) cleanup
