-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP #-}

-- |
-- Module      : THC.Driver.Process
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : CPP; POSIX process groups or Windows process jobs
--
-- Producer subprocess ownership for the Cabal driver.
module THC.Driver.Process
  ( runProducer
  , waitOwnedProcess
  , stopProcessTree
  , cancelCapturedProcess
  , drainWorkers
  ) where

import Control.Concurrent (ThreadId, killThread)
import Control.Concurrent.MVar (MVar, newEmptyMVar, putMVar, readMVar)
#ifndef mingw32_HOST_OS
import Control.Concurrent (threadDelay)
#endif
import Control.Exception (SomeAsyncException, SomeException, fromException, mask, mask_, onException, throwIO, try)
#ifndef mingw32_HOST_OS
import Control.Exception (uninterruptibleMask_)
#endif
import Control.Monad (void)
import System.Exit (ExitCode)
#ifndef mingw32_HOST_OS
import System.IO.Error (catchIOError, isDoesNotExistError)
import System.Posix.Signals (sigINT, sigKILL, signalProcessGroup)
import System.Timeout (timeout)
#endif
import qualified System.Process as Process
import System.Process.Internals (withForkWait)

-- | Stop and join registered workers before releasing their owned resources.
-- Repeated asynchronous cancellation is remembered and retried while joining;
-- callers release resources before propagating it. The waits remain
-- interruptible: this does not introduce an uninterruptible process reap.
-- Only idempotent thread cancellation/repeatable completion reads are retried.
drainWorkers :: [(ThreadId, MVar ())] -> IO (Maybe SomeException)
drainWorkers = mask_ . go Nothing
  where
    go pending [] = pure pending
    go pending workers@((thread, done) : rest) = do
      result <- try (killThread thread >> readMVar done)
      case result of
        Right () -> go pending rest
        Left problem -> case fromException problem :: Maybe SomeAsyncException of
          Just _ -> go (case pending of Nothing -> Just problem; _ -> pending) workers
          Nothing -> throwIO problem

-- | Run a producer with caller-owned streams in a private process group/job.
-- Arguments, environment, working directory and exit status are unchanged.
-- Cancellation stops and reaps the owned tree. After successful cleanup the
-- original exception propagates; a cleanup error instead propagates normally.
-- The driver handles interrupts rather than delegating
-- them to a foreground terminal group. Interactive guest launches retain
-- their separate foreground process policy.
--
-- On POSIX, the producer must wait for its descendants before normal exit;
-- after reaping its leader we cannot safely signal a potentially reused ID.
runProducer :: Process.CreateProcess -> IO ExitCode
runProducer command = mask $ \restore ->
  Process.withCreateProcess command
    { Process.create_group = True, Process.use_process_jobs = True,
      Process.delegate_ctlc = False } $ \_ _ _ child ->
      restore (waitOwnedProcess child) `onException` stopProcessTree child

-- | Wait for a group/job created by its sole owner. On POSIX, reaping and
-- publishing the closed handle are masked together against cancellation;
-- no other thread may reap this process. A pipe-capture owner must drain its
-- streams before this wait, retaining group ownership until EOF.
waitOwnedProcess :: Process.ProcessHandle -> IO ExitCode
#ifdef mingw32_HOST_OS
waitOwnedProcess child = do
  -- waitForProcess keeps and waits for the job; a worker leaves the owner
  -- free to terminate it even while the native wait is blocked.
  status <- newEmptyMVar
  withForkWait (Process.waitForProcess child >>= putMVar status) $ \waitChild ->
    (waitChild >> readMVar status) `onException` stopProcessTree child
#else
waitOwnedProcess child = mask $ \restore -> do
  let poll = Process.getProcessExitCode child >>= maybe (restore (threadDelay 10000) >> poll) pure
  poll
#endif

-- | Stop and reap a still-owned group/job. A closed handle is safe to pass:
-- on POSIX its former PID is never signalled after it can have been reused.
-- Repeated cancellation is deferred until this idempotent owned-tree cleanup
-- completes; reap stays interruptible and cleanup errors still propagate.
stopProcessTree :: Process.ProcessHandle -> IO ()
stopProcessTree child = mask_ $ go Nothing
  where
    go pending = do
      result <- try stop
      case result of
        Right () -> mapM_ throwIO pending
        Left problem -> case fromException problem :: Maybe SomeAsyncException of
          Just _ -> go (case pending of Nothing -> Just problem; _ -> pending)
          Nothing -> throwIO problem
    -- Repeating this owned-handle operation is safe: a ClosedHandle is never
    -- signalled, and waiting its already published exit is repeatable.
    stop = do
#ifdef mingw32_HOST_OS
      Process.terminateProcess child
#else
      Process.getPid child >>= mapM_ (\pid -> signalProcessGroup sigKILL pid
        `catchIOError` \problem -> if isDoesNotExistError problem then pure () else ioError problem)
#endif
      void (Process.waitForProcess child)

-- | Cancel a pipe-captured owner while its EOF readers are still alive.
-- On POSIX, give a managed owner two seconds to clean up private producer
-- groups after SIGINT. The repeatable action must confirm successful EOF on
-- both streams before the sole owner reaps the leader. Timeout or failure
-- falls back to immediate group cleanup while the handle remains owned.
-- Repeated cancellation cannot interrupt the bounded cooperative phase;
-- immediate fallback and reap remain outside that shield.
-- Windows jobs already contain the nested producers and are stopped directly.
--
-- A crashed, non-cooperating or externally hard-killed POSIX owner can leave
-- descendants in private groups; this protocol cannot contain escaped groups.
cancelCapturedProcess :: Process.ProcessHandle -> IO () -> IO ()
#ifdef mingw32_HOST_OS
cancelCapturedProcess child _ = stopProcessTree child
#else
cancelCapturedProcess child awaitEOF = mask $ \restore -> do
  completion <- newEmptyMVar
  let cooperate = do
        Process.getPid child >>= mapM_ (\pid -> signalProcessGroup sigINT pid
          `catchIOError` \problem -> if isDoesNotExistError problem then pure () else ioError problem)
        timeout 2000000 (awaitEOF >> void (waitOwnedProcess child)) >>= putMVar completion
  -- Only this deadline-bounded phase is shielded from a second cancellation.
  -- A failed worker still triggers hard cleanup and propagates its exception.
  withForkWait (restore cooperate) uninterruptibleMask_
    `onException` stopProcessTree child
  completed <- readMVar completion
  case completed of
    Nothing -> stopProcessTree child
    Just () -> pure ()
#endif
