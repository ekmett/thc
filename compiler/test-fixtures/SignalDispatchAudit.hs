-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SignalDispatchAudit (setupHandler, awaitHandler, dispatchNative, auditMain) where

import Control.Concurrent.MVar
import Control.Exception (MaskingState(..), getMaskingState)
import Data.Dynamic (toDyn)
import Data.Word (Word8)
import Foreign.C.Types (CInt)
import Foreign.ForeignPtr (withForeignPtr)
import Foreign.Marshal.Alloc (mallocBytes)
import Foreign.Ptr (Ptr, castPtr)
import Foreign.Storable (peek, poke)
import GHC.Exts (Int#, Int(I#), RealWorld, State#)
import GHC.Internal.Conc.Signal (setHandler, runHandlersPtr)
import GHC.IO (IO(..), unsafePerformIO)

{-# NOINLINE observations #-}
observations :: MVar Int
observations = unsafePerformIO newEmptyMVar

-- The original library owns the handler table, ForeignPtr and forkIO. The
-- fixture only supplies a Haskell handler and records its observable result.
{-# OPAQUE setupHandler #-}
setupHandler :: Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
setupHandler signal s = case install (I# signal) of
  IO action -> case action s of (# s1, () #) -> (# s1, signal #)

install :: Int -> IO ()
install signal = do
  _ <- setHandler (fromIntegral signal) (Just (\info -> do
    delivered <- withForeignPtr info (\p -> peek (castPtr p) :: IO CInt)
    masking <- getMaskingState
    let mask = case masking of Unmasked -> 0; MaskedInterruptible -> 1; MaskedUninterruptible -> 2
    putMVar observations (signal * 10000 + fromIntegral delivered * 10 + mask), toDyn ()))
  pure ()

{-# OPAQUE awaitHandler #-}
awaitHandler :: Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
awaitHandler _token s = case takeMVar observations of
  IO action -> case action s of (# s1, I# observed #) -> (# s1, observed #)

-- Native GHC oracle uses the same original entry as the JVM bridge. Its owned
-- allocation becomes runHandlersPtr's ForeignPtr; this caller must not free it.
dispatchNative :: Int -> IO ()
dispatchNative signal = do
  pointer <- mallocBytes 128 :: IO (Ptr Word8)
  poke (castPtr pointer) (fromIntegral signal :: CInt)
  runHandlersPtr pointer (fromIntegral signal)

-- A real IO entry supplies the auditor's public boundary. The JVM test also
-- invokes the internal State# helpers directly, retaining the same original
-- modules and checking the bridge's exact runHandlersPtr entry proof.
{-# OPAQUE auditMain #-}
auditMain :: IO ()
auditMain = mapM_ (\signal@(I# n) -> do
  _ <- IO $ \s -> case setupHandler n s of (# s1, result #) -> (# s1, I# result #)
  dispatchNative signal
  _ <- IO $ \s -> case awaitHandler n s of (# s1, result #) -> (# s1, I# result #)
  pure ()) [1,2,3,15]
