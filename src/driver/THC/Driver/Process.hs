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
  ) where

#ifdef mingw32_HOST_OS
import Control.Concurrent.MVar (newEmptyMVar, putMVar, readMVar)
#else
import Control.Concurrent (threadDelay)
#endif
import Control.Exception (mask, onException)
import Control.Monad (void)
import System.Exit (ExitCode)
#ifndef mingw32_HOST_OS
import System.IO.Error (catchIOError, isDoesNotExistError)
import System.Posix.Signals (sigKILL, signalProcessGroup)
#endif
import qualified System.Process as Process
#ifdef mingw32_HOST_OS
import System.Process.Internals (withForkWait)
#endif

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
stopProcessTree :: Process.ProcessHandle -> IO ()
stopProcessTree child = do
#ifdef mingw32_HOST_OS
  Process.terminateProcess child
#else
  Process.getPid child >>= mapM_ (\pid -> signalProcessGroup sigKILL pid
    `catchIOError` \problem -> if isDoesNotExistError problem then pure () else ioError problem)
#endif
  void (Process.waitForProcess child)
