-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : BlockedOwners
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC thread reachability and explicit major collections
--
-- Observe blocked-owner rescue using ordinary libraries. The mixed MVar/STM
-- cycle cannot progress without rescue. Its handlers remain live so weak thread
-- identities must survive the rescue collection. An independently retained
-- MVar is the negative control. No collector count or schedule is asserted.
module BlockedOwners (main) where

import Control.Concurrent
import Control.Exception
import Control.Monad (unless)
import Data.List (sort)
import Data.Maybe (isJust)
import GHC.Conc (BlockReason (..), ThreadStatus (..), atomically, newTVarIO, readTVar, retry, threadStatus, writeTVar)
import System.Mem (performMajorGC)
import System.Mem.Weak (Weak, deRefWeak)

-- Keep the observed identity inside this call. No strong identity loan crosses
-- the collection requested after its allocating caller returns.
{-# NOINLINE awaitBlocked #-}
awaitBlocked :: BlockReason -> ThreadId -> IO ()
awaitBlocked expected identity = do
  status <- threadStatus identity
  case status of
    ThreadBlocked actual | actual == expected -> pure ()
    ThreadFinished -> error "blocked owner finished before its wait"
    ThreadDied -> error "blocked owner died before its wait"
    _ -> yield >> awaitBlocked expected identity

-- Return only weak identities and externally owned observation/release cells.
-- Keeping these allocations out of main avoids retaining its live key locals.
{-# NOINLINE unreachableCycle #-}
unreachableCycle :: IO ([Weak ThreadId], MVar String, MVar (), MVar (Either SomeException ()))
unreachableCycle = do
  cell <- newEmptyMVar
  changed <- newTVarIO False
  mvarReady <- newEmptyMVar
  stmReady <- newEmptyMVar
  events <- newEmptyMVar
  release <- newEmptyMVar
  finished <- newEmptyMVar
  let report name = putMVar events name >> takeMVar release
      onMVar :: BlockedIndefinitelyOnMVar -> IO ()
      onMVar _ = report "mvar"
      onSTM :: BlockedIndefinitelyOnSTM -> IO ()
      onSTM _ = report "stm"
      mvarAction = takeMVar cell >> atomically (writeTVar changed True)
      stmAction = atomically (readTVar changed >>= (`unless` retry)) >> putMVar cell ()
  first <- forkFinally
    (putMVar mvarReady () >> (mvarAction >> report "unexpected-normal-mvar") `catch` onMVar)
    (putMVar finished)
  second <- forkFinally
    (putMVar stmReady () >> (stmAction >> report "unexpected-normal-stm") `catch` onSTM)
    (putMVar finished)
  takeMVar mvarReady
  takeMVar stmReady
  awaitBlocked BlockedOnMVar first
  awaitBlocked BlockedOnSTM second
  firstWeak <- mkWeakThreadId first
  secondWeak <- mkWeakThreadId second
  pure ([firstWeak, secondWeak], events, release, finished)

-- Return the live cell after its waiter blocks, dropping the observation's
-- ThreadId so the cell itself supplies the ordinary owner root.
{-# NOINLINE allocateLiveCell #-}
allocateLiveCell :: IO (MVar Int, MVar (Either SomeException Int))
allocateLiveCell = do
  cell <- newEmptyMVar
  ready <- newEmptyMVar
  result <- newEmptyMVar
  worker <- forkFinally (putMVar ready () >> takeMVar cell) (putMVar result)
  takeMVar ready
  awaitBlocked BlockedOnMVar worker
  pure (cell, result)

liveCell :: IO ()
liveCell = do
  (cell, result) <- allocateLiveCell
  performMajorGC
  putMVar cell (42 :: Int)
  value <- takeMVar result >>= either throwIO pure
  unless (value == 42) (error "live MVar completion changed")
  putStrLn "live-cell:42"

-- | Check mixed-cycle rescue and retained-cell progress after major collection.
main :: IO ()
main = do
  (identities, events, release, finished) <- unreachableCycle
  -- Both waits are published before their strong ThreadId loans end. Leave
  -- resumed handlers mutator time by blocking on delivery after the request.
  performMajorGC
  first <- takeMVar events
  second <- takeMVar events
  unless (sort [first, second] == ["mvar", "stm"]) (error "mixed cycle failed to rescue both owners")
  alive <- mapM (fmap isJust . deRefWeak) identities
  unless (and alive) (error "rescue prematurely cleared a weak ThreadId")
  putStrLn "mixed-cycle:mvar,stm;weak-identities:live"
  putMVar release ()
  putMVar release ()
  takeMVar finished >>= either throwIO pure
  takeMVar finished >>= either throwIO pure
  liveCell
