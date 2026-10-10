-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP #-}
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : InstalledHydrationTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : CPP; native GHC and host filesystem/process services
--
-- Tests for installed hydration.
module InstalledHydrationTests (tests, helperMode) where

import Control.Concurrent (forkFinally, killThread, threadDelay)
import Control.Concurrent.MVar (newEmptyMVar, putMVar, readMVar)
import Control.Exception (IOException, bracket, finally, try)
#ifdef mingw32_HOST_OS
import Control.Exception (AsyncException(ThreadKilled), fromException)
import qualified System.Semaphore as Sem
import System.IO.Error (isDoesNotExistError)
#endif
import Control.Monad (forM_, unless)
import Data.Aeson (Value(Null), eitherDecodeStrict', encode, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.List (isInfixOf)
import System.Directory (canonicalizePath, createDirectory, doesFileExist, getCurrentDirectory,
  getTemporaryDirectory, removeFile, removePathForcibly)
import System.Environment (getExecutablePath)
import System.Exit (ExitCode(..), exitWith)
import System.FilePath ((</>), takeFileName)
import System.IO (hClose, hSetBinaryMode, openTempFile, stdin, stderr, stdout)
import System.Timeout (timeout)
import Test.HUnit (Test(..), assertBool, assertEqual, assertFailure)
import THC.Compact.Module (encodeModuleValue)
import THC.Driver.Installed
import THC.Driver.GhcProxy (coreReplayArguments)
import THC.Driver.Admission (withAdmission, withBuildAdmission, publishBuildSemaphore)

-- Subprocess orchestration controls, not executable Core evidence. The real
-- InterfaceFixtures separately compare original serial/parallel Core bytes and
-- exercise the unchanged package-cache and strict-audit contracts.
helperMode :: [String] -> Maybe (IO ())
helperMode arguments = case arguments of
  ["--child-directory-fixture"] -> Just $ do
    hSetBinaryMode stdout True
    getCurrentDirectory >>= BL.putStr . encode
  ["--binary-input-fixture"] -> Just $ do
    mapM_ (`hSetBinaryMode` True) [stdin, stdout, stderr]
    -- Fill both output pipes before consuming a request larger than stdin's
    -- pipe buffer. The parent must drain them while it writes the request.
    BS.hPut stdout binaryPipePayload
    BS.hPut stderr pipePayload
    BS.hGetContents stdin >>= BS.hPut stdout
  "--global" : _ -> Just $ readFile (option "--package-db" </> "registration") >>= putStr
  "--libdir" : _ -> Just $ do
    hSetBinaryMode stdout True
    let directory = option "--libdir"
        name = option "--module"
        mark suffix = directory </> name ++ suffix
    mode <- readFile (mark ".mode")
    writeFile (mark ".started") ""
    case mode of
      "gate" -> awaitFile (mark ".release")
      "slow" -> threadDelay 2000000
      "missing-after-B" -> awaitFile (directory </> "B.started")
      "error-after-B" -> awaitFile (directory </> "B.finished")
      _ -> pure ()
    writeFile (mark ".finished") ""
    if mode == "noisy" then do
      input <- getContents
      unless (null input) (fail "The helper expected empty stdin")
      BS.hPut stderr (BS.replicate (1024 * 1024) 120)
    else if mode == "invalid-stderr" then BS.hPut stderr (BS.singleton 255)
    else pure ()
    let missing = object ["schema" .= (1 :: Int), "status" .= ("unavailable" :: String),
          "capability" .= ("complete-interface-core" :: String), "unit" .= identifier,
          "module" .= name, "interface" .= option "--interface", "way" .=
            (if mode == "wrong-way" then (if option "--way" == "vanilla" then "dynamic" else "vanilla") else option "--way")]
        core = object ["schema" .= (1 :: Int), "unit" .= identifier, "module" .= name,
          "ghc" .= ("9.14.1" :: String), "boundary" .= ("optimized-Core-after-Tidy-before-CorePrep" :: String),
          "constructors" .= ([] :: [String]), "bindings" .= ([] :: [String])]
    if mode == "invalid-stdout" then BS.hPut stdout (BS.singleton 255)
    else if mode `elem` ["missing-after-B", "missing", "wrong-way"] then BL.putStrLn (encode missing) >> exitWith (ExitFailure 3)
    else if mode `elem` ["error", "error-after-B"] then do
      BL.putStrLn (encode (object ["schema" .= (1 :: Int), "status" .= ("error" :: String)]))
      exitWith (ExitFailure 1)
    else encodeModuleValue core >>= BS.hPut stdout
  _ -> Nothing
  where
    option name = case dropWhile (/= name) arguments of
      _ : value : _ -> value
      _ -> error ("Missing helper fixture option " ++ name)

identifier :: String
identifier = "thc-hydration-fixture-0.1"

pipePayload :: BS.ByteString
pipePayload = BS.replicate (1024 * 1024) 120

binaryPipePayload :: BS.ByteString
binaryPipePayload = BS.pack [0, 255, 254] <> pipePayload

awaitFile :: FilePath -> IO ()
awaitFile path = do
  found <- timeout 5000000 loop
  unless (found == Just ()) (fail ("Timed out waiting for fixture marker " ++ path))
  where loop = do
          exists <- doesFileExist path
          if exists then pure () else threadDelay 1000 >> loop

fixture :: [String] -> (FilePath -> InstalledContext -> InstalledUnit -> IO ()) -> IO ()
fixture names action = do
  temporary <- getTemporaryDirectory
  bracket (fresh temporary) removePathForcibly $ \directory -> do
    executable <- getExecutablePath
    forM_ names $ \name -> do
      writeFile (directory </> name ++ ".dyn_hi") "orchestration control only"
      writeFile (directory </> name ++ ".mode") "load"
    writeFile (directory </> "registration") $ unlines
      ["name: thc-hydration-fixture", "version: 0.1", "id: " ++ identifier,
       "key: " ++ identifier, "exposed: True", "exposed-modules: " ++ unwords names,
       "import-dirs: " ++ show directory]
    let context = InstalledContext executable directory executable (directory </> "global") [directory] Null executable Nothing
          DynamicInterfaces (directory </> "global")
    unit <- discoverInstalled context identifier
    action directory context unit
  where
    fresh directory = do
      (path, handle) <- openTempFile directory "thc-installed-hydration-"
      hClose handle
      removeFile path
      createDirectory path
      canonicalizePath path

#ifdef mingw32_HOST_OS
-- One real owner remains live across all borrowed scopes. Nonblocking claims
-- prove exact token conservation; MVars identify actual admitted action entry.
borrowedAdmissionOwnership :: FilePath -> IO ()
borrowedAdmissionOwnership directory = do
  name <- bracket (Sem.freshSemaphore "thc-admission-ownership" 1) Sem.destroySemaphore $ \owner -> do
    nativeRelease <- newEmptyMVar
    withBuildAdmission directory
      (\handoff -> do
        publishBuildSemaphore handoff (Sem.getSemaphoreName (Sem.semaphoreName owner))
        readMVar nativeRelease)
      (\admission -> do
        -- Native completion is still held: this must borrow the published name.
        withAdmission admission $ assertEqual "borrowed action owns the only token" False
          =<< Sem.tryWaitOnSemaphore owner
        exactlyOne owner
        assertEqual "owner consumes the token before blocked cancellation" True
          =<< Sem.tryWaitOnSemaphore owner
        blocked <- timeout 200000 $ withAdmission admission
          (assertFailure "tokenless wait entered an action" :: IO ())
        assertEqual "blocked borrowed wait is cancellable" Nothing blocked
        assertEqual "cancelling a tokenless wait does not post" False
          =<< Sem.tryWaitOnSemaphore owner
        Sem.releaseSemaphore owner 1
        withAdmission admission $ assertEqual "valid use after wait cancellation" False
          =<< Sem.tryWaitOnSemaphore owner
        failed <- try (withAdmission admission (fail "admitted action failed")) :: IO (Either IOException ())
        assertBool "admitted action failure propagates" (case failed of Left _ -> True; _ -> False)
        exactlyOne owner
        entered <- newEmptyMVar
        held <- newEmptyMVar
        result <- newEmptyMVar
        thread <- forkFinally
          (withAdmission admission $ do
            assertEqual "cancellable body holds the physical token" False
              =<< Sem.tryWaitOnSemaphore owner
            putMVar entered ()
            readMVar held) (putMVar result)
        flip finally (killThread thread >> readMVar result >> pure ()) $ do
          readMVar entered
          assertEqual "entered cancellable action owns the token" False
            =<< Sem.tryWaitOnSemaphore owner
          killThread thread
          outcome <- readMVar result
          assertBool "action cancellation propagates after token return"
            (case outcome of
              Left problem -> fromException problem == Just ThreadKilled
              Right _ -> False)
        exactlyOne owner
        putMVar nativeRelease ())
    exactlyOne owner
    pure (Sem.semaphoreName owner)
  reopened <- try (Sem.openSemaphore name) :: IO (Either IOException Sem.Semaphore)
  case reopened of
    Left problem -> assertBool "final owner close removes the named kernel object"
      (isDoesNotExistError problem)
    Right leaked -> Sem.destroySemaphore leaked >> assertFailure "borrowed handle survived scope close"
  where
    exactlyOne owner = do
      assertEqual "one token returned" True =<< Sem.tryWaitOnSemaphore owner
      assertEqual "no duplicate post" False =<< Sem.tryWaitOnSemaphore owner
      Sem.releaseSemaphore owner 1
#endif

tests :: Test
tests = TestLabel "bounded installed-interface hydration" $ TestList
  [
#ifdef mingw32_HOST_OS
    -- Windows borrowed handles/event cancellation need native API evidence.
    -- Existing host-independent missing/ambiguous metadata controls follow.
    TestCase $ fixture ["A"] $ \directory _ _ -> do
      completed <- timeout 5000000 (borrowedAdmissionOwnership directory)
      assertEqual "borrowed semaphore ownership completes" (Just ()) completed
  ,
#endif
    TestCase $ fixture ["A"] $ \directory context unit -> do
      nativeStarted <- newEmptyMVar
      nativeRelease <- newEmptyMVar
      helperReady <- newEmptyMVar
      result <- newEmptyMVar
      thread <- forkFinally
        (withBuildAdmission directory
          (\_ -> putMVar nativeStarted () >> readMVar nativeRelease)
          (\admission -> putMVar helperReady () >> acquireInstalledWithJobsAndAdmission admission 1 context unit))
        (putMVar result)
      flip finally (killThread thread >> readMVar result >> pure ()) $ do
        readMVar nativeStarted
        readMVar helperReady
        assertEqual "missing metadata never starts a helper while native is active" False
          =<< doesFileExist (directory </> "A.started")
        putMVar nativeRelease ()
        completed <- timeout 5000000 (readMVar result)
        case completed of
          Just (Right (Right core)) -> assertEqual "warm native success releases ordinary acquisition"
            identifier (coreOwner core)
          _ -> assertFailure ("warm admission did not finish: " ++ show completed)
  , TestCase $ fixture ["A"] $ \directory context unit -> do
      nativeStarted <- newEmptyMVar
      nativeRelease <- newEmptyMVar
      helperReady <- newEmptyMVar
      result <- newEmptyMVar
      thread <- forkFinally
        (withBuildAdmission directory
          (\handoff -> do
            publishBuildSemaphore handoff ("thc-absent-" ++ take 16 (reverse (takeFileName directory)))
            putMVar nativeStarted ()
            readMVar nativeRelease
            fail "native control failed")
          (\admission -> putMVar helperReady () >> acquireInstalledWithJobsAndAdmission admission 1 context unit))
        (putMVar result)
      flip finally (killThread thread >> readMVar result >> pure ()) $ do
        readMVar nativeStarted
        readMVar helperReady
        assertEqual "late missing name remains gated" False =<< doesFileExist (directory </> "A.started")
        putMVar nativeRelease ()
        completed <- timeout 5000000 (readMVar result)
        assertBool "native failure propagates instead of granting local admission"
          (case completed of Just (Left _) -> True; _ -> False)
        assertEqual "failed native stage never starts a converter" False
          =<< doesFileExist (directory </> "A.started")
  , TestCase $ fixture ["A", "B"] $ \directory context unit -> do
      writeFile (directory </> "A.mode") "slow"
      handoffReady <- newEmptyMVar
      result <- try $ withBuildAdmission directory
        (\handoff -> do
          putMVar handoffReady handoff
          _ <- acquireInstalledWithJobs 1 context unit
          pure ())
        (\admission -> do
          handoff <- readMVar handoffReady
          awaitFile (directory </> "A.started")
          publishBuildSemaphore handoff "thc-ambiguous-admission-a"
          publishBuildSemaphore handoff "thc-ambiguous-admission-b"
          withAdmission admission (assertFailure "ambiguous budget entered a helper")) :: IO (Either IOException ())
      assertBool "ambiguous invocation budget fails" (case result of Left _ -> True; _ -> False)
      assertEqual "failure reaps the actual owned native helper before its delayed write" False
        =<< doesFileExist (directory </> "A.finished")
  , TestCase $ fixture ["A"] $ \directory context dynamic -> do
      writeFile (directory </> "A.hi") "orchestration control only"
      let vanilla = context { installedInterfaceWay = VanillaInterfaces }
          private = vanilla { installedGlobalDb = directory </> "private database with spaces" }
      vanillaPath <- canonicalizePath (directory </> "A.hi")
      dynamicPath <- canonicalizePath (directory </> "A.dyn_hi")
      selected <- discoverInstalled vanilla identifier
      assertEqual "vanilla discovery selects only hi" [("A", vanillaPath)] (installedInterfaces selected)
      assertEqual "dynamic discovery still selects dyn_hi" [("A", dynamicPath)] (installedInterfaces dynamic)
      assertBool "way changes provenance" (installedProvenance context dynamic /= installedProvenance vanilla selected)
      assertBool "same libdir with private database changes replay identity"
        (installedViewIdentity vanilla /= installedViewIdentity private)
      assertEqual "private stack survives replay without an interface overlay or component options"
        (["-B" ++ installedLibdir private] ++ concatMap (\db -> ["-package-db", db]) (helperDatabases private))
        =<< coreReplayArguments (installedPackageTool private) (installedLibdir private)
          (helperDatabases private) directory [] []
      assertBool "helper uses vanilla" (["--way", "vanilla"] `isInfixOf` helperCommand vanilla selected ("A", vanillaPath))
      assertBool "helper receives private database before additional databases"
        (["--package-db", installedGlobalDb private, "--package-db", directory] `isInfixOf`
          helperCommand private selected ("A", vanillaPath))
      assertBool "native package tool receives the same private global database"
        (["--global-package-db", installedGlobalDb private] `isInfixOf` packageGlobalArguments private)
      writeFile (directory </> "A.mode") "missing"
      assertEqual "matching vanilla missing-Core response retains its exact identity"
        (Left (MissingCore identifier "A" vanillaPath)) =<< acquireInstalledWithJobs 1 vanilla selected
      writeFile (directory </> "A.mode") "wrong-way"
      failed <- try (acquireInstalledWithJobs 1 vanilla selected) :: IO (Either IOException (Either MissingCore InstalledCore))
      assertBool "other-way response is a protocol failure" (case failed of Left _ -> True; _ -> False)
      removeFile (directory </> "A.dyn_hi")
      missing <- try (discoverInstalled context identifier) :: IO (Either IOException InstalledUnit)
      assertBool "dynamic discovery never falls back to vanilla" (case missing of Left _ -> True; _ -> False)
  , TestCase $ fixture [] $ \directory context _ -> do
      before <- getCurrentDirectory
      (status, output, _) <- boundedInterfaceProcessIn directory (installedHelper context) ["--child-directory-fixture"]
      expected <- canonicalizePath directory
      assertEqual "only the child changes its working directory" ExitSuccess status
      assertEqual "the child receives the selected directory" (Right expected) (eitherDecodeStrict' output)
      assertEqual "the parent directory is unchanged" before =<< getCurrentDirectory
  , TestCase $ do
      executable <- getExecutablePath
      let request = BL.toStrict (encode (replicate (256 * 1024) '\x03bb'))
      response <- timeout 5000000 (boundedInterfaceProcessInput executable ["--binary-input-fixture"] request)
      assertEqual "binary Unicode input and both large outputs make progress without locale conversion"
        (Just (ExitSuccess, binaryPipePayload <> request, pipePayload)) response
  , TestCase $ fixture ["A"] $ \directory context unit -> do
      writeFile (directory </> "A.mode") "slow"
      result <- newEmptyMVar
      thread <- forkFinally (boundedInterfaceProcessInput (installedHelper context)
        (helperCommand context unit ("A", directory </> "A.dyn_hi")) pipePayload) (putMVar result)
      flip finally (killThread thread >> readMVar result >> pure ()) $ do
        awaitFile (directory </> "A.started")
        killThread thread
        failed <- readMVar result
        assertBool "Cancellation propagates while the helper leaves a large request unread"
          (case failed of Left _ -> True; _ -> False)
        threadDelay 2100000
        finished <- doesFileExist (directory </> "A.finished")
        assertBool "Blocked input writer cannot delay child termination" (not finished)
  , TestCase $ fixture ["A", "B", "C", "D"] $ \directory context unit -> do
      serial <- acquireInstalledWithJobs 1 context unit
      forM_ ["A", "B", "C", "D"] $ \name -> do
        removeFile (directory </> name ++ ".started")
        removeFile (directory </> name ++ ".finished")
      forM_ ["A", "B"] $ \name -> writeFile (directory </> name ++ ".mode") "gate"
      result <- newEmptyMVar
      thread <- forkFinally (acquireInstalledWithJobs 2 context unit) (putMVar result)
      flip finally (killThread thread >> readMVar result >> pure ()) $ do
        mapM_ (awaitFile . (directory </>)) ["A.started", "B.started"]
        started <- doesFileExist (directory </> "C.started")
        assertBool "Only two interfaces may run at once" (not started)
        writeFile (directory </> "B.release") ""
        awaitFile (directory </> "C.finished")
        awaitFile (directory </> "D.finished")
        first <- doesFileExist (directory </> "A.finished")
        assertBool "A slow first module does not block other workers from draining the queue" (not first)
        writeFile (directory </> "A.release") ""
        parallel <- readMVar result >>= either (fail . show) pure
        assertEqual "Payload bytes, owner and inventory order match serial hydration" serial parallel
        case parallel of
          Right core -> assertEqual "Output order" ["A", "B", "C", "D"] (map fst (coreModules core))
          Left missing -> assertFailure (show missing)
  , TestCase $ fixture ["A", "B", "C"] $ \directory context unit -> do
      writeFile (directory </> "A.mode") "missing-after-B"
      writeFile (directory </> "B.mode") "slow"
      result <- acquireInstalledWithJobs 2 context unit
      assertEqual "The first missing interface is retained" (Left (MissingCore identifier "A"
        (directory </> "A.dyn_hi"))) result
      threadDelay 2100000
      forM_ ["B.finished", "C.started"] $ \name -> do
        exists <- doesFileExist (directory </> name)
        assertBool "Early missing-Core return cancels/reaps later work without refilling" (not exists)
  , TestCase $ fixture ["A", "B", "C"] $ \directory context unit -> do
      writeFile (directory </> "A.mode") "error-after-B"
      writeFile (directory </> "B.mode") "error"
      result <- try (acquireInstalledWithJobs 2 context unit)
        :: IO (Either IOException (Either MissingCore InstalledCore))
      case result of
        Left failure -> assertBool "Report inventory-first failure, not fastest failure"
          ((identifier ++ ":A") `isInfixOf` show failure)
        Right value -> assertFailure (show value)
      third <- doesFileExist (directory </> "C.started")
      assertBool "No refill after the observed failure" (not third)
  , TestCase $ fixture ["A", "B"] $ \directory context unit -> do
      forM_ ["A", "B"] $ \name -> writeFile (directory </> name ++ ".mode") "slow"
      result <- newEmptyMVar
      thread <- forkFinally (acquireInstalledWithJobs 2 context unit) (putMVar result)
      flip finally (killThread thread >> readMVar result >> pure ()) $ do
        mapM_ (awaitFile . (directory </>)) ["A.started", "B.started"]
        killThread thread
        failed <- readMVar result
        assertBool "Caller cancellation propagates" (case failed of Left _ -> True; _ -> False)
        threadDelay 2100000
        forM_ ["A", "B"] $ \name -> do
          finished <- doesFileExist (directory </> name ++ ".finished")
          assertBool "Caller unwind terminated and reaped every helper" (not finished)
  , TestCase $ fixture ["A"] $ \directory context unit -> do
      appendFile (directory </> "registration") "synopsis: changed during acquisition\n"
      result <- try (acquireInstalledWithJobs 2 context unit)
        :: IO (Either IOException (Either MissingCore InstalledCore))
      assertBool "Final registration recheck is preserved" (case result of
        Left failure -> "registration changed" `isInfixOf` show failure
        _ -> False)
  , TestCase $ fixture ["A"] $ \directory context unit -> do
      forM_ [-1, 0, 65] $ \jobs -> do
        result <- try (acquireInstalledWithJobs jobs context unit)
          :: IO (Either IOException (Either MissingCore InstalledCore))
        assertBool "Reject invalid worker bound" (case result of Left _ -> True; _ -> False)
      started <- doesFileExist (directory </> "A.started")
      assertBool "Invalid bounds start no helper" (not started)
  , TestCase $ fixture [] $ \_ context unit -> do
      result <- acquireInstalledWithJobs 2 context unit
      assertEqual "Empty registered inventory retains its owner" (Right (InstalledCore identifier [])) result
  , TestCase $ fixture ["A", "B"] $ \directory context unit -> do
      serial <- acquireInstalledWithJobs 1 context unit
      forM_ ["A", "B"] $ \name -> writeFile (directory </> name ++ ".mode") "noisy"
      parallel <- timeout 5000000 (acquireInstalledWithJobs 2 context unit)
      assertEqual "Concurrent large stderr drains preserve CBD bytes and EOF stdin" (Just serial) parallel
  , TestCase $ fixture ["A"] $ \directory context unit ->
      forM_ ["invalid-stdout", "invalid-stderr"] $ \mode -> do
        writeFile (directory </> "A.mode") mode
        result <- try (acquireInstalledWithJobs 2 context unit)
          :: IO (Either IOException (Either MissingCore InstalledCore))
        assertBool "Malformed CBD or invalid UTF-8 diagnostics remain protocol failures, never missing Core"
          (case result of Left _ -> True; _ -> False)
  ]
