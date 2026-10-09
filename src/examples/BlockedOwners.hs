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
import Control.Monad (unless, void)
import Data.List (sort)
import Data.Maybe (isJust)
import GHC.Conc (atomically, newTVarIO, readTVar, retry, writeTVar)
import System.Mem (performMajorGC)
import System.Mem.Weak (Weak, deRefWeak)

-- Return only weak identities and externally owned observation/release cells.
-- Keeping these allocations out of main avoids retaining its live key locals.
{-# NOINLINE unreachableCycle #-}
unreachableCycle :: IO ([Weak ThreadId], MVar String, MVar (), MVar (Either SomeException ()))
unreachableCycle = do
  cell <- newEmptyMVar
  changed <- newTVarIO False
  ready <- newEmptyMVar
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
    (putMVar ready () >> (mvarAction >> report "unexpected-normal-mvar") `catch` onMVar)
    (putMVar finished)
  second <- forkFinally
    (putMVar ready () >> (stmAction >> report "unexpected-normal-stm") `catch` onSTM)
    (putMVar finished)
  firstWeak <- mkWeakThreadId first
  secondWeak <- mkWeakThreadId second
  takeMVar ready
  takeMVar ready
  pure ([firstWeak, secondWeak], events, release, finished)

collectEvent :: MVar a -> IO a
collectEvent events = do
  next <- tryTakeMVar events
  case next of
    Just value -> pure value
    Nothing -> performMajorGC >> yield >> collectEvent events

liveCell :: IO ()
liveCell = do
  cell <- newEmptyMVar
  ready <- newEmptyMVar
  result <- newEmptyMVar
  void $ forkFinally (putMVar ready () >> takeMVar cell) (putMVar result)
  takeMVar ready
  performMajorGC
  putMVar cell (42 :: Int)
  value <- takeMVar result >>= either throwIO pure
  unless (value == 42) (error "live MVar completion changed")
  putStrLn "live-cell:42"

-- | Check mixed-cycle rescue and retained-cell progress after major collection.
main :: IO ()
main = do
  (identities, events, release, finished) <- unreachableCycle
  first <- collectEvent events
  second <- collectEvent events
  unless (sort [first, second] == ["mvar", "stm"]) (error "mixed cycle failed to rescue both owners")
  alive <- mapM (fmap isJust . deRefWeak) identities
  unless (and alive) (error "rescue prematurely cleared a weak ThreadId")
  putStrLn "mixed-cycle:mvar,stm;weak-identities:live"
  putMVar release ()
  putMVar release ()
  takeMVar finished >>= either throwIO pure
  takeMVar finished >>= either throwIO pure
  liveCell
