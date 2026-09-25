-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- Native symbol oracle and test consumer. Its home-unit FCallIds are replaced
-- only in a separate synthetic Core copy with pinned original GHC FCallIds.
module SharedCAFNative where

import GHC.Exts (Int(I#), Int#)
import GHC.Internal.Stable (newStablePtr, freeStablePtr)
import Foreign.Ptr (Ptr, nullPtr, castPtr)
import Foreign.StablePtr (castStablePtrToPtr)
import System.IO.Unsafe (unsafePerformIO)

foreign import ccall unsafe "getOrSetSystemEventThreadEventManagerStore"
  eventManagerStore :: Ptr a -> IO (Ptr a)

foreign import ccall unsafe "getOrSetGHCConcSignalSignalHandlerStore"
  signalHandlerStore :: Ptr a -> IO (Ptr a)

{-# OPAQUE bottom #-}
bottom :: Int
bottom = bottom

-- Never free an RTS-owned winner, nor write over a store owned by an
-- earlier native caller. The losing StablePtr is still ours to free.
probeStore :: (Ptr Int -> IO (Ptr Int)) -> IO Bool
probeStore getOrSet = do
  before <- getOrSet nullPtr
  if before /= nullPtr then (== before) <$> getOrSet nullPtr
  else do
    first <- newStablePtr bottom
    second <- newStablePtr bottom
    let winner = castPtr (castStablePtrToPtr first)
        loser = castPtr (castStablePtrToPtr second)
    installed <- getOrSet winner
    repeated <- getOrSet loser
    queried <- getOrSet nullPtr
    freeStablePtr second
    pure (installed == winner && repeated == winner && queried == winner)

{-# OPAQUE sharedEventManagerStore #-}
sharedEventManagerStore :: Int# -> Int#
sharedEventManagerStore raw = case unsafePerformIO (probeStore eventManagerStore) of
  True -> raw
  False -> case bottom of I# result -> result

{-# OPAQUE sharedSignalHandlerStore #-}
sharedSignalHandlerStore :: Int# -> Int#
sharedSignalHandlerStore raw = case unsafePerformIO (probeStore signalHandlerStore) of
  True -> raw
  False -> case bottom of I# result -> result
