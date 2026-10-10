-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CApiFFI #-}
{-# LANGUAGE CPP #-}
{-# LANGUAGE InterruptibleFFI #-}
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Driver.Admission
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : CPP, CApiFFI, InterruptibleFFI; POSIX or Windows semaphores
--
-- Invocation-scoped borrowing of Cabal's actual parallel-build budget.
module THC.Driver.Admission
  ( Admission
  , localAdmission
  , withAdmission
  , waitForNative
  , withBuildAdmission
  , publishBuildSemaphore
  , observeBuildAdmission
  , effectiveBuildSemaphore
  ) where

import Control.Concurrent (forkIOWithUnmask, threadDelay)
#ifndef mingw32_HOST_OS
import Control.Concurrent (forkIO, killThread)
#endif
import Control.Concurrent.Chan (newChan, readChan, writeChan)
import Control.Concurrent.MVar (newEmptyMVar, putMVar, readMVar, tryPutMVar, tryReadMVar)
import Control.Exception (SomeException, bracket, finally, mask, mask_, onException,
                          throwIO, try, uninterruptibleMask_)
import Control.Monad (forM, forM_, unless, void)
import Data.Aeson (eitherDecodeStrict', encode, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import qualified Data.ByteString.Lazy.Char8 as BL8
import qualified Crypto.Hash.SHA256 as SHA
import Data.IORef (modifyIORef', newIORef, readIORef, writeIORef)
import Data.List (nub, sort, stripPrefix, isPrefixOf)
import Data.Char (isSpace)
import qualified Data.IntMap.Strict as IntMap
import qualified Data.Set as Set
import GHC.ResponseFile (expandResponse)
import GHC.Clock (getMonotonicTimeNSec)
import Text.Read (readMaybe)
import System.Environment (lookupEnv)
import Numeric (showHex)
import System.Directory (createDirectory, createDirectoryIfMissing, listDirectory,
                         removeFile, removePathForcibly, renameFile)
import System.FilePath ((</>), takeExtension)
import System.IO (hClose, hPutStrLn, openBinaryTempFile, openTempFile, stderr)
import System.IO.Error (isDoesNotExistError, tryIOError)
import THC.Driver.Process (drainWorkers)
#ifdef mingw32_HOST_OS
import qualified System.Semaphore as Sem
import qualified System.Win32.File as Win32
#else
import Foreign.C.Error (eINTR, getErrno, throwErrno, throwErrnoPath, throwErrnoIfMinus1_, throwErrnoIfMinus1Retry_)
import Foreign.C.String (CString, withCAString)
import Foreign.C.Types (CInt(..))
import Foreign.Ptr (Ptr)
#endif

-- | A capability valid only inside its invocation. Identity/discovery and
-- dependency readiness must happen outside 'withAdmission'.
data Admission = Admission (IO (Maybe BorrowedSemaphore)) (IO ())

-- | Ordinary standalone acquisition has no concurrent native producer.
localAdmission :: Admission
localAdmission = Admission (pure Nothing) (pure ())

-- | Acquire one physical token immediately around an owned helper process.
-- The action must stop/reap its descendants and drain readers on cancellation
-- before returning or throwing. Synchronous descendants reuse this admission.
-- No token or handle restoration after an external hard kill is promised.
withAdmission :: Admission -> IO a -> IO a
withAdmission (Admission ready _) action = do
  selected <- ready
  maybe action (`withToken` action) selected

-- | Wait without a resource token before existing native packaging owners.
-- They remain serial after successful Cabal completion rather than being
-- folded into the installed helper subprocess lifetime.
waitForNative :: Admission -> IO ()
waitForNative (Admission _ completed) = completed

-- | Run one native producer and the existing installed acquisition stage.
-- Actual helper entry waits for an authenticated name or successful native
-- completion. Both stages drain before closing the borrowed handle; Cabal
-- alone owns name creation/unlink. Missing late metadata is never local
-- admission while the native producer is still running.
withBuildAdmission :: FilePath -> (FilePath -> IO ()) -> (Admission -> IO a) -> IO a
withBuildAdmission staging native installed = do
  createDirectoryIfMissing True staging
  bracket (temporary staging) removePathForcibly $ \directory -> mask $ \restore -> do
    gate <- newEmptyMVar
    nativeResult <- newEmptyMVar
    monitorResult <- newEmptyMVar
    installedResult <- newEmptyMVar
    events <- newChan
    borrowed <- newIORef Nothing
    workers <- newIORef []
    let publish slot action = do
          result <- tryAny action
          putMVar slot result
          writeChan events ()
        spawn slot action = do
          done <- newEmptyMVar
          thread <- forkIOWithUnmask $ \unmask ->
            publish slot (unmask action) `finally` putMVar done ()
          modifyIORef' workers ((thread, done) :)
          pure ()
        ready = readMVar gate
        monitor = do
          let loop selected = do
                names <- readNames directory
                name <- case nub names of
                  [] -> pure Nothing
                  [value] -> pure (Just value)
                  _ -> fail "one Cabal invocation published different parallel budgets"
                unless (selected == Nothing || name == selected)
                  (fail "Cabal parallel-budget handoff changed")
                current <- readIORef borrowed
                case (current, name) of
                  (Nothing, Just value) -> do
                    opened <- mask_ $ do
                      opened <- tryIOError (openBorrowed value)
                      case opened of
                        Right semaphore -> writeIORef borrowed (Just semaphore)
                        Left _ -> pure ()
                      pure opened
                    case opened of
                      Right semaphore -> do
                        -- Mask open through publication: cancellation cannot
                        -- lose a newly borrowed handle before its close owner.
                        traceAdmission "borrowed-open" (Just value)
                        void (tryPutMVar gate (Just semaphore))
                      Left problem | isDoesNotExistError problem -> pure ()
                                   | otherwise -> ioError problem
                  _ -> pure ()
                finished <- tryReadMVar nativeResult
                case finished of
                  Just (Left problem) -> throwIO problem
                  Just (Right ()) -> do
                    -- Read names once more after the last proxy has exited.
                    finalNames <- readNames directory
                    if nub finalNames /= nub names then loop name else do
                      handle <- readIORef borrowed
                      case handle of Nothing -> traceAdmission "native-success-local" Nothing; Just _ -> pure ()
                      void (tryPutMVar gate handle)
                  Nothing -> threadDelay 10000 >> loop name
          loop Nothing
        await = do
          n <- tryReadMVar nativeResult
          m <- tryReadMVar monitorResult
          i <- tryReadMVar installedResult
          let check Nothing = pure ()
              check (Just result) = either throwIO (const (pure ())) result
          check n >> check m >> check i
          case (n, m, i) of
            (Just (Right ()), Just (Right ()), Just (Right result)) -> pure result
            _ -> readChan events >> await
    let run = do
          spawn nativeResult (traceAdmission "native-start" Nothing >> native directory >> traceAdmission "native-success" Nothing)
          spawn monitorResult monitor
          spawn installedResult (installed (Admission ready (readMVar nativeResult >>= either throwIO pure)))
          restore await
    -- Register the drain/close owner before any spawn. Repeated cancellation
    -- is deferred by idempotent worker joins, then propagated after close;
    -- synchronous cleanup failures remain observable.
    result <- tryAny run
    pending <- drainWorkers =<< readIORef workers
    readIORef borrowed >>= mapM_ closeBorrowed
    case result of
      Left problem -> throwIO problem
      Right value -> maybe (pure value) throwIO pending
  where
    temporary root = do
      (path, handle) <- openTempFile root "cabal-admission-"
      hClose handle
      removeFile path
      createDirectory path
      pure path

-- | Observe the existing compiler-proxy invocation without changing argv.
-- Response/option ambiguity leaves the gate closed until Cabal succeeds.
observeBuildAdmission :: [String] -> IO ()
observeBuildAdmission arguments = do
  directory <- lookupEnv "THC_PROXY_BUILD_ADMISSION"
  forM_ directory $ \path -> do
    -- GHC removes direct -B arguments before response expansion/mode parsing.
    expanded <- tryIOError (expandResponse (filter (not . ("-B" `isPrefixOf`)) arguments))
    case expanded of
      Right options -> forM_ (effectiveBuildSemaphore options) (publishBuildSemaphore path)
      Left _ -> pure ()

-- | Conservatively certify a unanimous effective semaphore from pinned GHC
-- option-consumption rules. This is an observer, not a compiler flag parser.
-- Unknown dynamic flags are allowed BOTH zero and one consumed argument;
-- overlap is disabled if the possible effective budgets disagree. Ordinary
-- mode removal happens first, as in GHC; other modes/RTS scope decline overlap.
-- In particular an unrelated HasArg payload cannot grant admission.
effectiveBuildSemaphore :: [String] -> Maybe String
effectiveBuildSemaphore arguments
  | any otherMode arguments || any ("-B" `isPrefixOf`) arguments || any (`elem` ["+RTS", "-RTS", "--RTS", "--"]) arguments = Nothing
  | otherwise = case Set.toList (IntMap.findWithDefault Set.empty count possibilities) of
      [Just name] | not (null name), '\0' `notElem` name -> Just name
      _ -> Nothing
  where
    options = filter (`notElem` ["--make", "-c"]) arguments
    count = length options
    possibilities = foldl step (IntMap.singleton 0 (Set.singleton Nothing)) (zip [0..] options)
    add position values = IntMap.insertWith Set.union position values
    setter position value = add position (Set.singleton value)
    step states (index, option) =
      let current = IntMap.findWithDefault Set.empty index states
          following = drop (index + 1) options
          trim = reverse . dropWhile isSpace . reverse . dropWhile isSpace
          dropEq ('=' : rest) = rest
          dropEq rest = rest
      in if Set.null current then states else case stripPrefix "-jsem" option of
        Just suffix -> case dropEq (trim suffix) of
          "" -> case following of
            name : _ -> setter (index + 2) (Just name) states
            [] -> add (index + 1) (Set.singleton Nothing) states
          name -> setter (index + 1) (Just name) states
        Nothing -> case stripPrefix "-j" option of
          Just suffix | null suffix || maybe False (> (0 :: Int)) (readMaybe (dropEq (trim suffix))) ->
            setter (index + 1) Nothing states
          Just _ -> add (index + 1) (Set.singleton Nothing) states
          Nothing | noArgument option || not ("-" `isPrefixOf` option) -> add (index + 1) current states
                  | otherwise -> add (index + 1) current
                      (if null following then states else add (index + 2) current states)
    -- These exact pinned NoArg/OptIntSuffix flags precede Cabal's -jsem.
    -- All other dynamic flags stay conservative; no full dictionary is copied.
    noArgument option = option `elem` ["-fbuilding-cabal-package", "-prof", "-split-sections", "-no-link"] ||
      any (\prefix -> case stripPrefix prefix option of
        Just "" -> True
        Just suffix -> case suffix of
          '=' : rest -> integer rest
          rest -> integer rest
        Nothing -> False) ["-O", "-g"]
    integer value = case readMaybe value :: Maybe Int of Just _ -> True; Nothing -> False
    otherMode option = option `elem`
      ["-?", "--help", "-V", "--version", "--numeric-version", "--info", "--show-options",
       "--supported-languages", "--supported-extensions", "--show-packages", "-M", "-E", "-C", "-S",
       "--run", "-unit", "--backpack", "--interactive", "--abi-hash", "-e", "--frontend"] ||
      any (`isPrefixOf` option) ["--show-iface", "--print-"]

-- | Publish only into the fresh invocation directory supplied by the owner.
-- A content-addressed immutable JSON file makes concurrent equal names safe;
-- multiple different names are rejected by the scope owner.
publishBuildSemaphore :: FilePath -> String -> IO ()
publishBuildSemaphore directory name = do
  unless (not (null name) && '\0' `notElem` name)
    (fail "invalid Cabal parallel-budget name")
  let bytes = BL.toStrict (encode name)
      digest = concatMap (\byte -> let value = showHex byte "" in
        replicate (2 - length value) '0' ++ value) (BS.unpack (SHA.hash bytes))
      path = directory </> digest ++ ".json"
  bracket (openBinaryTempFile directory "handoff-")
    (\(temporary, handle) -> hClose handle `finally` do
      void (tryIOError (removeFile temporary))) $ \(temporary, handle) -> do
      BS.hPut handle bytes
      hClose handle
      renameFile temporary path
      traceAdmission "handoff" (Just name)

readNames :: FilePath -> IO [String]
readNames directory = do
  entries <- sort <$> listDirectory directory
  forM [entry | entry <- entries, takeExtension entry == ".json"] $ \entry -> do
    bytes <- BS.readFile (directory </> entry)
    name <- either (fail . ("invalid parallel-budget handoff: " ++)) pure (eitherDecodeStrict' bytes)
    unless (not (null name) && '\0' `notElem` name)
      (fail "invalid Cabal parallel-budget name")
    pure name

#ifdef mingw32_HOST_OS
type BorrowedSemaphore = Sem.Semaphore

openBorrowed :: String -> IO BorrowedSemaphore
openBorrowed = Sem.openSemaphore . Sem.SemaphoreName

closeBorrowed :: BorrowedSemaphore -> IO ()
closeBorrowed = Sem.destroySemaphore

release :: BorrowedSemaphore -> IO ()
release semaphore = Sem.releaseSemaphore semaphore 1
#else
newtype BorrowedSemaphore = BorrowedSemaphore (Ptr ())

foreign import capi safe "semaphore.h sem_open"
  semOpen :: CString -> CInt -> IO (Ptr ())
foreign import capi unsafe "semaphore.h value SEM_FAILED"
  semFailed :: Ptr ()
foreign import capi interruptible "semaphore.h sem_wait"
  semWait :: Ptr () -> IO CInt
foreign import capi safe "semaphore.h sem_post"
  semPost :: Ptr () -> IO CInt
foreign import capi safe "semaphore.h sem_close"
  semClose :: Ptr () -> IO CInt

openBorrowed :: String -> IO BorrowedSemaphore
openBorrowed name = withCAString name $ \pointer -> do
  semaphore <- semOpen pointer 0
  if semaphore == semFailed then throwErrnoPath "Cabal parallel-budget sem_open" name
    else pure (BorrowedSemaphore semaphore)

closeBorrowed :: BorrowedSemaphore -> IO ()
closeBorrowed (BorrowedSemaphore semaphore) =
  throwErrnoIfMinus1_ "Cabal parallel-budget sem_close" (semClose semaphore)

release :: BorrowedSemaphore -> IO ()
release (BorrowedSemaphore semaphore) =
  throwErrnoIfMinus1Retry_ "Cabal parallel-budget sem_post" (semPost semaphore)
#endif

-- Explicit diagnostic evidence for the genuine one-token check. No trace is
-- emitted during ordinary builds and no raw pointer/handle is exposed.
traceAdmission :: String -> Maybe String -> IO ()
traceAdmission event name = do
  enabled <- lookupEnv "THC_TRACE_BUILD_ADMISSION"
  if enabled /= Just "1" then pure () else do
    now <- getMonotonicTimeNSec
    hPutStrLn stderr (BL8.unpack (encode (object
      ["thcBuildAdmission" .= event, "monotonicNs" .= now, "name" .= name])))

tryAny :: IO a -> IO (Either SomeException a)
tryAny = try

withToken :: BorrowedSemaphore -> IO a -> IO a
withToken semaphore action = mask $ \restore -> do
  traceAdmission "token-wait" Nothing
  result <- newEmptyMVar
#ifdef mingw32_HOST_OS
  waiter <- Sem.forkWaitOnSemaphoreInterruptible semaphore (putMVar result)
  let cancel = Sem.interruptWaitOnSemaphore waiter
      finish = Win32.closeHandle (Sem.cancelHandle waiter)
#else
  waiter <- forkIO $ mask_ $ do
    outcome <- tryAny $ case semaphore of
      BorrowedSemaphore pointer -> do
        status <- semWait pointer
        if status == 0 then pure True else do
          problem <- getErrno
          if problem == eINTR then pure False else throwErrno "Cabal parallel-budget sem_wait"
    putMVar result outcome
  let cancel = killThread waiter
      finish = pure ()
#endif
      abandon = uninterruptibleMask_ $ do
        -- This shield owns only cancellation of an interruptible semaphore
        -- wait and its immediate callback/post, never a subprocess reap.
        cancel
        outcome <- readMVar result
        finish `finally` case outcome of
          Right True -> release semaphore
          _ -> pure ()
  outcome <- restore (readMVar result) `onException` abandon
  case outcome of
    Right True -> (finish >> traceAdmission "token-acquired" Nothing >> restore action)
      `finally` (release semaphore >> traceAdmission "token-released" Nothing)
    Right False -> finish >> restore (withToken semaphore action)
    Left problem -> finish >> throwIO problem
