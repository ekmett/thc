-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Main where

import Control.Concurrent
import Control.Exception (SomeException, throwIO)
import Control.Monad (unless)
import Data.Bits (finiteBitSize)
import GHC.Conc (BlockReason(..), ThreadStatus(..), threadStatus)
import GHC.Exts
import qualified ManagedMVarAudit as P
import System.Environment (getArgs)

-- Native GHC oracle only: these driver dependencies never enter guest exports.
-- Ready handshakes plus an observed blocked state establish enqueue order.
-- No sleep duration or scheduler luck is used as an enqueue assertion.
queued :: IO a -> IO (MVar (Either SomeException a))
queued action = do
  ready <- newEmptyMVar
  done <- newEmptyMVar
  thread <- forkFinally (putMVar ready () >> action) (putMVar done)
  takeMVar ready
  awaitBlocked thread (1000000 :: Int)
  pure done
  where
    awaitBlocked _ 0 = error "native oracle: thread did not block within yield bound"
    awaitBlocked thread remaining = do
      status <- threadStatus thread
      case status of
        ThreadBlocked BlockedOnMVar -> pure ()
        ThreadRunning -> yield >> awaitBlocked thread (remaining - 1)
        _ -> error ("native oracle: unexpected worker status " ++ show status)

completed :: MVar (Either SomeException a) -> IO a
completed done = takeMVar done >>= either throwIO pure

nativeReaders :: Int -> IO Int
nativeReaders raw = do
  m <- newEmptyMVar
  first <- queued (readMVar m)
  second <- queued (readMVar m)
  putMVar m (raw + 31)
  a <- completed first
  b <- completed second
  snapshot <- tryReadMVar m
  c <- takeMVar m
  empty <- isEmptyMVar m
  unless (a == raw + 31 && b == a && snapshot == Just a && c == a && empty)
    (error "native oracle: reader broadcast or non-consuming read failed")
  pure (a + 17 * b + 257 * c)

nativeTakeFIFO :: Int -> IO Int
nativeTakeFIFO raw = do
  m <- newEmptyMVar
  first <- queued (takeMVar m)
  second <- queued (takeMVar m)
  third <- queued (takeMVar m)
  putMVar m (raw + 1)
  putMVar m (raw + 2)
  putMVar m (raw + 3)
  a <- completed first
  b <- completed second
  c <- completed third
  empty <- isEmptyMVar m
  unless ([a, b, c] == [raw + 1, raw + 2, raw + 3] && empty)
    (error "native oracle: queued take FIFO failed")
  pure (a + 17 * b + 257 * c)

nativePutFIFO :: Int -> IO Int
nativePutFIFO raw = do
  m <- newMVar raw
  first <- queued (putMVar m (raw + 1))
  second <- queued (putMVar m (raw + 2))
  third <- queued (putMVar m (raw + 3))
  a <- takeMVar m
  b <- takeMVar m
  c <- takeMVar m
  d <- takeMVar m
  mapM_ completed [first, second, third]
  empty <- isEmptyMVar m
  unless ([a, b, c, d] == [raw, raw + 1, raw + 2, raw + 3] && empty)
    (error "native oracle: queued put FIFO failed")
  pure (a + 17 * b + 257 * c + 65537 * d)

-- These ready-state native adapters do not enter the guest module's exports.
nativeWaitTake :: Int# -> Int#
nativeWaitTake raw = runRW# (\s0 ->
  case newMVar# s0 of { (# s1, m #) ->
  case putMVar# m (P.makeBox raw) s1 of { s2 -> P.waitTake m s2 } })

nativeWaitRead :: Int# -> Int#
nativeWaitRead raw = runRW# (\s0 ->
  case newMVar# s0 of { (# s1, m #) ->
  case putMVar# m (P.makeBox raw) s1 of { s2 -> P.waitRead m s2 } })

nativeWaitPut :: Int# -> Int#
nativeWaitPut raw = runRW# (\s0 ->
  case newMVar# s0 of { (# s1, m #) -> P.waitPut m raw s1 })

emit :: String -> (Int# -> Int#) -> Int -> IO ()
emit name f raw@(I# value) = row name raw (I# (f value))

row :: String -> Int -> Int -> IO ()
row name raw result = putStrLn (name ++ "\t" ++ show raw ++ "\t" ++ show result)

dispatch :: [String] -> IO ()
dispatch [name, value] = case name of
  "transitions" -> emit name P.transitions raw
  "lazyPayload" -> emit name P.lazyPayload raw
  "aliasRoundTrip" -> emit name P.aliasRoundTrip raw
  "unliftedPayload" -> emit name P.unliftedPayload raw
  "closurePayload" -> emit name P.closurePayload raw
  "nativeWaitTake" -> emit name nativeWaitTake raw
  "nativeWaitRead" -> emit name nativeWaitRead raw
  "nativeWaitPut" -> emit name nativeWaitPut raw
  "nativeReaders" -> nativeReaders raw >>= row name raw
  "nativeTakeFIFO" -> nativeTakeFIFO raw >>= row name raw
  "nativePutFIFO" -> nativePutFIFO raw >>= row name raw
  _ -> error "native oracle: unknown entry"
  where raw = read value
dispatch _ = error "native oracle: malformed request"

main :: IO ()
main = do
  args <- getArgs
  case args of
    ["--word-bits"] -> print (finiteBitSize (0 :: Int))
    [] -> getContents >>= mapM_ (dispatch . words) . lines
    _ -> error "native oracle: unexpected arguments"
