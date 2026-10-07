-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Native GHC observer for live async and blocked-child executable shutdown.
-- The shutdown mode observes a real blocked child before main returns; its
-- marker and process exit are compared with JVM and Native Image execution.
module Main where
import Control.Concurrent
import Control.Exception
import System.Timeout
import GHC.Exts (Int(I#))
import GHC.Conc (BlockReason(BlockedOnMVar), ThreadStatus(..), threadStatus)
import System.Environment (getArgs)
import qualified LiveAsyncAudit as A

force :: Int -> IO Int
force (I# token) = evaluate (I# (A.forceShared token))
forceStrict :: Int -> IO Int
forceStrict (I# token) = evaluate (I# (A.strictEntry token))
ready :: IO Int
ready = evaluate (I# (A.takeReady 0#))
count :: IO Int
count = evaluate (I# (A.prefixCount 0#))
main :: IO ()
main = do
  mode <- getArgs
  case mode of
    ["shutdown"] -> blockedChildShutdown
    _ -> interruptedShared mode

interruptedShared :: [String] -> IO ()
interruptedShared mode = do
  initial <- case mode of
    [] -> pure force
    ["strict"] -> pure forceStrict
    _ -> error "Expected no argument, strict or shutdown"
  result <- newEmptyMVar
  tid <- forkIO $ do
    x <- try (initial 0) :: IO (Either SomeException Int)
    putMVar result x
  r <- timeout 5000000 ready
  ack <- newEmptyMVar
  _ <- forkIO $ do
    throwTo tid ThreadKilled
    putMVar ack ()
  a <- timeout 5000000 (takeMVar result)
  b <- timeout 5000000 (takeMVar ack)
  release <- evaluate (I# (A.releaseGate 0#))
  c <- timeout 5000000 (force 1)
  d <- count
  warm <- evaluate (I# (A.warmLoop 1024#))
  case (r,a,b,release,c,d,warm) of
    (Just 1007, Just (Right (-1)), Just (), 1, Just 10000008, 1, 1031) ->
      putStr "1007\n-1\n10000008\n1\n1031\n"
    _ -> error ("Native interrupted-thunk protocol failed: " ++ show (r,a,b,release,c,d,warm))

-- The status observation, not elapsed time, establishes the shutdown condition.
blockedChildShutdown :: IO ()
blockedChildShutdown = do
  ready <- newEmptyMVar
  gate <- newEmptyMVar :: IO (MVar ())
  child <- forkIO $ do
    putMVar ready ()
    takeMVar gate
    error "Shutdown released the blocked child"
  takeMVar ready
  let awaitBlocked = do
        status <- threadStatus child
        case status of
          ThreadBlocked BlockedOnMVar -> pure ()
          ThreadRunning -> yield >> awaitBlocked
          _ -> error ("Unexpected child status: " ++ show status)
  blocked <- timeout 5000000 awaitBlocked
  case blocked of
    Nothing -> error "Child did not block before shutdown"
    Just () -> do
      putStr "blocked child shutdown\n"
      -- Keep the gate reachable through the final output, then return directly.
      empty <- isEmptyMVar gate
      if empty then pure () else error "Shutdown gate was unexpectedly released"
