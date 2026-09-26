-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module InstalledHydrationTests (tests, helperMode) where

import Control.Concurrent (forkFinally, killThread, threadDelay)
import Control.Concurrent.MVar (newEmptyMVar, putMVar, readMVar)
import Control.Exception (IOException, bracket, finally, try)
import Control.Monad (forM_, unless)
import Data.Aeson (Value(Null), encode, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.List (isInfixOf)
import System.Directory (createDirectory, doesFileExist, getTemporaryDirectory, removeFile, removePathForcibly)
import System.Environment (getExecutablePath)
import System.Exit (ExitCode(..), exitWith)
import System.FilePath ((</>))
import System.IO (hClose, openTempFile, stderr, stdout)
import System.Timeout (timeout)
import Test.HUnit (Test(..), assertBool, assertEqual, assertFailure)
import THC.Driver.Installed

-- Subprocess orchestration controls, not executable Core evidence. The real
-- InterfaceFixtures separately compare original serial/parallel Core bytes and
-- exercise the unchanged package-cache and strict-audit contracts.
helperMode :: [String] -> Maybe (IO ())
helperMode arguments = case arguments of
  "--global" : _ -> Just $ readFile (option "--package-db" </> "registration") >>= putStr
  "--libdir" : _ -> Just $ do
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
          "module" .= name, "interface" .= option "--interface", "way" .= ("dynamic" :: String)]
        core = object ["schema" .= (1 :: Int), "unit" .= identifier, "module" .= name,
          "ghc" .= ("9.14.1" :: String), "boundary" .= ("optimized-Core-after-Tidy-before-CorePrep" :: String),
          "payload" .= replicate 200 (name ++ "\x03bb\x1f642")]
    if mode == "invalid-stdout" then BS.hPut stdout (BS.singleton 255)
    else if mode == "missing-after-B" then BL.putStrLn (encode missing) >> exitWith (ExitFailure 3)
    else if mode `elem` ["error", "error-after-B"] then do
      BL.putStrLn (encode (object ["schema" .= (1 :: Int), "status" .= ("error" :: String)]))
      exitWith (ExitFailure 1)
    else BL.putStrLn (encode (object ["schema" .= (1 :: Int), "status" .= ("loaded" :: String), "core" .= core]))
  _ -> Nothing
  where
    option name = case dropWhile (/= name) arguments of
      _ : value : _ -> value
      _ -> error ("Missing helper fixture option " ++ name)

identifier :: String
identifier = "thc-hydration-fixture-0.1"

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
    let context = InstalledContext executable directory executable (directory </> "global") [directory] Null
    unit <- discoverInstalled context identifier
    action directory context unit
  where
    fresh directory = do
      (path, handle) <- openTempFile directory "thc-installed-hydration-"
      hClose handle
      removeFile path
      createDirectory path
      pure path

tests :: Test
tests = TestLabel "bounded installed-interface hydration" $ TestList
  [ TestCase $ fixture ["A", "B", "C", "D"] $ \directory context unit -> do
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
        assertBool "Only two interfaces may start before a response is consumed" (not started)
        writeFile (directory </> "A.release") ""
        awaitFile (directory </> "C.finished")
        fourth <- doesFileExist (directory </> "D.started")
        assertBool "An early slow module bounds the completed response backlog" (not fourth)
        writeFile (directory </> "B.release") ""
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
      assertEqual "Concurrent large stderr drains preserve UTF-8 Core and EOF stdin" (Just serial) parallel
  , TestCase $ fixture ["A"] $ \directory context unit ->
      forM_ ["invalid-stdout", "invalid-stderr"] $ \mode -> do
        writeFile (directory </> "A.mode") mode
        result <- try (acquireInstalledWithJobs 2 context unit)
          :: IO (Either IOException (Either MissingCore InstalledCore))
        assertBool "Invalid UTF-8 must remain a protocol failure, never missing Core"
          (case result of Left _ -> True; _ -> False)
  ]
