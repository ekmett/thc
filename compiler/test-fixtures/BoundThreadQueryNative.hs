-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main (main) where

import BoundThreadQueryAudit (boundThreadQuery)
import Control.Concurrent
import Control.Exception (SomeException, displayException, try)
import Data.IORef (newIORef, readIORef, writeIORef)
import Data.List (isInfixOf)
import GHC.Exts (Int(I#))

-- This exact source is linked in both RTS modes. The native control observations
-- are not a request to implement isCurrentThreadBound or forkOS in THC.
main :: IO ()
main = do
  mainBound <- isCurrentThreadBound
  child <- newEmptyMVar
  _ <- forkIO (isCurrentThreadBound >>= putMVar child)
  childBound <- takeMVar child
  ran <- newIORef False
  bound <- try (runInBoundThread (writeIORef ran True >> isCurrentThreadBound)) :: IO (Either SomeException Bool)
  actionRan <- readIORef ran
  case bound of
    Left exception | not rtsSupportsBoundThreads &&
      "RTS doesn't support multiple OS threads" `isInfixOf` displayException exception -> pure ()
    Right True | rtsSupportsBoundThreads -> pure ()
    _ -> fail "Unexpected runInBoundThread result or exception"
  print (rtsSupportsBoundThreads, mainBound, childBound, either (const False) id bound, actionRan)
  mapM_ emit [minBound, -4097, -1, 0, 1, 42, 4097, maxBound]
  where
    emit :: Int -> IO ()
    emit input@(I# value) = putStrLn (show input ++ "\t" ++ show (I# (boundThreadQuery value)))
