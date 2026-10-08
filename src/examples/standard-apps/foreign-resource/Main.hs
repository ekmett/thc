-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell2010
--
-- Native storage owned by an ordinary 'ForeignPtr', with deterministic and
-- automatic cleanup through the original Haskell library.
module Main (main) where

import Control.Concurrent (MVar, isEmptyMVar, newEmptyMVar, putMVar, threadDelay, tryTakeMVar)
import Control.Exception (evaluate, mask_, onException)
import Control.Monad (unless)
import Data.IORef (newIORef, readIORef)
import Data.Word (Word8)
import qualified Foreign.Concurrent as Concurrent
import Foreign.ForeignPtr (ForeignPtr, finalizeForeignPtr, plusForeignPtr, withForeignPtr)
import Foreign.Marshal.Alloc (free)
import Foreign.Marshal.Array (mallocArray, peekArray, pokeArray)
import Foreign.Storable (poke)
import System.Mem (performGC)

-- | Show that an interior alias keeps its allocation alive, then observe both
-- explicit and automatic cleanup. Collection timing is deliberately unspecified.
main :: IO ()
main = do
  explicit <- newEmptyMVar
  owner <- newResource explicit
  useResource owner explicit
  finalizeForeignPtr owner
  first <- awaitCleanup explicit
  finalizeForeignPtr owner
  duplicate <- isEmptyMVar explicit
  unless duplicate (fail "explicit finalization ran twice")
  print ("explicit", first)
  automatic <- newEmptyMVar
  useAutomatic automatic
  second <- awaitCleanup automatic
  print ("automatic", second)
  unless (first == 129 && second == 129) (fail "resource checksum differs")

newResource :: MVar Int -> IO (ForeignPtr Word8)
newResource finished = mask_ $ do
  pointer <- mallocArray 4
  (do
    pokeArray pointer [10, 20, 30, 40]
    captured <- newIORef ([1 .. 7] :: [Int])
    Concurrent.newForeignPtr pointer $ do
      bytes <- peekArray 4 pointer
      lazyState <- readIORef captured
      checksum <- evaluate (sum (map fromIntegral bytes) + sum lazyState)
      free pointer
      putMVar finished checksum
    ) `onException` free pointer

useResource :: ForeignPtr Word8 -> MVar Int -> IO ()
useResource owner finished = withForeignPtr (plusForeignPtr owner 1) $ \pointer -> do
  performGC
  threadDelay 10000
  live <- isEmptyMVar finished
  unless live (fail "resource finalized during withForeignPtr")
  poke pointer (21 :: Word8)

-- End the owner and alias scopes before requesting automatic cleanup.
{-# OPAQUE useAutomatic #-}
useAutomatic :: MVar Int -> IO ()
useAutomatic finished = do
  owner <- newResource finished
  useResource owner finished

awaitCleanup :: MVar Int -> IO Int
awaitCleanup finished = do
  result <- tryTakeMVar finished
  case result of
    Just checksum -> pure checksum
    Nothing -> do
      performGC
      threadDelay 10000
      awaitCleanup finished
