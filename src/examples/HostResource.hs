-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : HostResource
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : MagicHash, UnboxedTuples
--
-- Ordinary resource operations shared by native Haskell and a Java host.
module HostResource
  ( session
  , newCounter
  , newBuffer
  , checksum
  , cleanupCount
  , main
  ) where

import Control.Concurrent (threadDelay)
import Control.Exception (evaluate, mask_, onException)
import Control.Monad (unless)
import Data.IORef (IORef, atomicModifyIORef', newIORef, readIORef)
import Data.Word (Word8)
import qualified Foreign.Concurrent as Concurrent
import Foreign.ForeignPtr (ForeignPtr, withForeignPtr)
import Foreign.Marshal.Alloc (free)
import Foreign.Marshal.Array (mallocArray, peekArray, pokeArray)
import GHC.Exts (RealWorld, State#)
import GHC.IO (IO (IO))
import System.Mem (performGC)

-- Keep the same public entry calls in the native oracle and host application.
{-# OPAQUE newCounter #-}
-- | Create an independent cleanup observer; it does not retain any buffer.
newCounter :: IO (IORef Int)
newCounter = newIORef 0

{-# OPAQUE newBuffer #-}
-- | Allocate four bytes whose sum is 100; cleanup frees them and increments
-- the observer exactly once after the last owning reference is discarded.
newBuffer :: IORef Int -> IO (ForeignPtr Word8)
newBuffer counter = mask_ $ do
  pointer <- mallocArray 4
  (do
    pokeArray pointer [10, 20, 30, 40]
    Concurrent.newForeignPtr pointer $ do
      free pointer
      atomicModifyIORef' counter (\n -> (n + 1, ()))
    ) `onException` free pointer

{-# OPAQUE checksum #-}
-- | Borrow the live buffer and return its evaluated checksum.
checksum :: ForeignPtr Word8 -> IO Int
checksum owner = withForeignPtr owner $ \pointer -> do
  bytes <- peekArray 4 pointer
  evaluate (sum (map fromIntegral bytes))

{-# OPAQUE cleanupCount #-}
-- | Observe completed cleanup without keeping its resource alive.
cleanupCount :: IORef Int -> IO Int
cleanupCount counter = readIORef counter >>= evaluate

-- | One application instance exposes its operations as a logical Core tuple.
-- The returned closures retain only the observer; no buffer has been allocated.
-- Java supplies null for State# and sees [State#, create, checksum, cleanupCount].
session :: State# RealWorld
  -> (# State# RealWorld, IO (ForeignPtr Word8), ForeignPtr Word8 -> IO Int, IO Int #)
session state = case newCounter of
  IO open -> case open state of
    (# next, counter #) -> (# next, newBuffer counter, checksum, cleanupCount counter #)

{-# OPAQUE useNative #-}
useNative :: IO (ForeignPtr Word8) -> (ForeignPtr Word8 -> IO Int) -> IO Int -> IO ()
useNative create readBuffer countCleanup = do
  owner <- create
  performGC
  threadDelay 10000
  count <- countCleanup
  unless (count == 0) (fail "retained buffer was finalized")
  total <- readBuffer owner
  unless (total == 100) (fail "buffer checksum differs")
  putStrLn ("Retained buffer: " ++ show total)

awaitCleanup :: IO Int -> IO ()
awaitCleanup countCleanup = do
  count <- countCleanup
  case count of
    0 -> performGC >> threadDelay 10000 >> awaitCleanup countCleanup
    1 -> putStrLn "Released buffer: 1 cleanup"
    _ -> fail "buffer finalized more than once"

-- | Native reference run; Java instead holds the buffer in a host collection.
main :: IO ()
main = IO $ \state -> case session state of
  (# next, create, readBuffer, countCleanup #) ->
    case useNative create readBuffer countCleanup >> awaitCleanup countCleanup of
      IO run -> run next
