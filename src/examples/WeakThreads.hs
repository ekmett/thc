-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : WeakThreads
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC thread status and weak pointers
--
-- Observe thread lifetime and resurrect ordinary state with standard libraries.
module WeakThreads (main) where

import Control.Concurrent
  ( MVar, ThreadId, forkIO, mkWeakThreadId, newEmptyMVar, putMVar, takeMVar, yield )
import Control.Exception (evaluate)
import Control.Monad (unless)
import Data.IORef (IORef, modifyIORef', newIORef, readIORef)
import Data.Maybe (isNothing)
import GHC.Conc (ThreadStatus(..), threadStatus)
import System.Mem (performGC)
import System.Mem.Weak (Weak, deRefWeak, finalize, mkWeak)

check :: String -> Bool -> IO ()
check name passed = unless passed (fail name)

awaitFinished :: ThreadId -> IO ()
awaitFinished thread = do
  status <- threadStatus thread
  case status of
    ThreadFinished -> pure ()
    ThreadDied -> fail "Worker died"
    _ -> yield >> awaitFinished thread

-- A weak ThreadId does not itself keep a completed thread alive.
awaitDead :: Weak a -> IO ()
awaitDead weak = do
  performGC
  value <- deRefWeak weak
  case value of
    Nothing -> pure ()
    Just _ -> yield >> awaitDead weak

{-# OPAQUE watchWorker #-}
watchWorker :: IO (Weak ThreadId)
watchWorker = do
  gate <- newEmptyMVar
  result <- newEmptyMVar
  thread <- forkIO (takeMVar gate >> putMVar result (6 * 7 :: Int))
  weak <- mkWeakThreadId thread
  performGC
  check "Running worker disappeared" . (== Just thread) =<< deRefWeak weak
  putStrLn "The weak observer can see the running worker."
  putMVar gate ()
  answer <- takeMVar result
  awaitFinished thread
  performGC
  check "Retained ThreadId disappeared" . (== Just thread) =<< deRefWeak weak
  putStrLn ("Worker returned " ++ show answer ++ "; its retained ThreadId is still observable.")
  pure weak

data Cell = Cell !(IORef Int)

{-# OPAQUE readCell #-}
readCell :: Cell -> IO Int
readCell (Cell cell) = readIORef cell

{-# OPAQUE incrementCell #-}
incrementCell :: Cell -> IO ()
incrementCell (Cell cell) = modifyIORef' cell (+ 1)

{-# OPAQUE registerResurrection #-}
registerResurrection :: IORef Int -> MVar Cell -> IO (Weak Cell)
registerResurrection count rescued = do
  key <- newIORef 41 >>= evaluate . Cell
  mkWeak key key (Just (modifyIORef' count (+ 1) >> putMVar rescued key))

{-# OPAQUE useResurrected #-}
useResurrected :: IORef Int -> MVar Cell -> MVar Int -> Weak Cell -> IO (Weak Cell)
useResurrected count rescued finished old = do
  key <- takeMVar rescued
  check "Resurrection revived the retired registration" . isNothing =<< deRefWeak old
  finalize old
  before <- readCell key
  incrementCell key
  after <- readCell key
  check "Resurrected state lost its value" (before == 41 && after == 42)
  putStrLn ("The finalizer returned its cell: " ++ show before ++ " -> " ++ show after ++ " after another update.")
  putStrLn "The original weak handle remains dead."
  mkWeak key key (Just (do
    modifyIORef' count (+ 1)
    readCell key >>= putMVar finished))

-- | Run lifetime checks. Collection is advisory; completion comes from weak
-- observations and finalizer MVars, never from a fixed collection count.
main :: IO ()
main = do
  watchWorker >>= awaitDead
  putStrLn "Dropping that ThreadId lets the observer forget the finished worker."
  count <- newIORef 0
  rescued <- newEmptyMVar
  finished <- newEmptyMVar
  old <- registerResurrection count rescued
  performGC
  fresh <- useResurrected count rescued finished old
  performGC
  check "Fresh finalizer lost the recovered value" . (== 42) =<< takeMVar finished
  check "Fresh weak registration did not retire" . isNothing =<< deRefWeak fresh
  finalize fresh
  check "Finalizer action was lost or repeated" . (== 2) =<< readIORef count
  putStrLn "A fresh weak handle released the recovered cell; each finalizer ran once."
